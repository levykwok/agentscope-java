#!/usr/bin/env node
'use strict';

import http from 'node:http';
import { randomUUID } from 'node:crypto';

const PROTOCOL_VERSION = '2025-11-25';
const SERVER_INFO = { name: 'company-platform-demo-mcp', version: '0.2.0' };

const tools = [
  {
    name: 'platform_echo',
    title: 'Platform Echo',
    description: 'Echo text back from the local demo MCP server. Useful for verifying MCP wiring.',
    inputSchema: {
      type: 'object',
      properties: {
        text: { type: 'string', description: 'Text to echo.' }
      },
      required: ['text'],
      additionalProperties: false
    }
  },
  {
    name: 'platform_time',
    title: 'Platform Time',
    description: 'Return the current server time and timezone.',
    inputSchema: {
      type: 'object',
      properties: {
        timezone: { type: 'string', description: 'Optional IANA timezone, for example Asia/Shanghai.' }
      },
      additionalProperties: false
    }
  },
  {
    name: 'platform_sum',
    title: 'Platform Sum',
    description: 'Sum a list of numbers. Useful for testing structured tool arguments.',
    inputSchema: {
      type: 'object',
      properties: {
        numbers: {
          type: 'array',
          items: { type: 'number' },
          description: 'Numbers to sum.'
        }
      },
      required: ['numbers'],
      additionalProperties: false
    }
  }
];

const args = new Map();
for (let i = 2; i < process.argv.length; i += 1) {
  const arg = process.argv[i];
  if (!arg.startsWith('--')) continue;
  const key = arg.slice(2);
  const next = process.argv[i + 1];
  if (next && !next.startsWith('--')) {
    args.set(key, next);
    i += 1;
  } else {
    args.set(key, 'true');
  }
}

const transport = args.get('transport') || 'stdio';
if (transport === 'stdio') {
  startStdio();
} else if (transport === 'streamable-http' || transport === 'http') {
  startHttp({ mode: 'streamable-http', port: Number(args.get('port') || 8765) });
} else if (transport === 'sse') {
  startHttp({ mode: 'sse', port: Number(args.get('port') || 8766) });
} else {
  process.stderr.write(`Unsupported transport: ${transport}\n`);
  process.exit(2);
}

function startStdio() {
  let buffer = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (chunk) => {
    buffer += chunk;
    let newline;
    while ((newline = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (line) writeJsonRpc(process.stdout, handleJsonRpcLine(line));
    }
  });
}

function startHttp({ mode, port }) {
  const sessions = new Map();
  const server = http.createServer(async (req, res) => {
    try {
      if (req.method === 'GET' && (req.url === '/health' || req.url === '/')) {
        sendJson(res, 200, { ok: true, mode, serverInfo: SERVER_INFO });
        return;
      }
      if (mode === 'sse' && req.method === 'GET' && req.url.startsWith('/sse')) {
        openSse(req, res, sessions);
        return;
      }
      if (mode === 'sse' && req.method === 'POST' && req.url.startsWith('/message')) {
        const message = await readJson(req);
        const sessionId = new URL(req.url, 'http://localhost').searchParams.get('sessionId') || message.sessionId;
        const session = sessions.get(sessionId);
        const response = handleJsonRpc(message);
        if (session && response) sendSse(session.res, response);
        sendJson(res, 202, { ok: true, sessionId });
        return;
      }
      if (mode === 'streamable-http' && req.method === 'POST' && (req.url === '/mcp' || req.url === '/')) {
        const message = await readJson(req);
        const response = handleJsonRpc(message);
        if (response === undefined) {
          res.writeHead(202).end();
          return;
        }
        sendJson(res, 200, response, { 'Mcp-Session-Id': req.headers['mcp-session-id'] || randomUUID() });
        return;
      }
      sendJson(res, 404, { error: 'not_found' });
    } catch (error) {
      sendJson(res, 500, errorEnvelope(null, -32603, error.message || 'Internal error'));
    }
  });
  server.listen(port, '127.0.0.1', () => {
    process.stderr.write(`[platform-demo-mcp] ${mode} listening on http://127.0.0.1:${port}\n`);
  });
}

function openSse(req, res, sessions) {
  const sessionId = new URL(req.url, 'http://localhost').searchParams.get('sessionId') || randomUUID();
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no'
  });
  sessions.set(sessionId, { res });
  sendSse(res, { endpoint: `/message?sessionId=${encodeURIComponent(sessionId)}` }, 'endpoint');
  req.on('close', () => sessions.delete(sessionId));
}

function handleJsonRpcLine(line) {
  try {
    return handleJsonRpc(JSON.parse(line));
  } catch (error) {
    return errorEnvelope(null, -32700, 'Parse error');
  }
}

function handleJsonRpc(request) {
  if (!request || request.jsonrpc !== '2.0' || typeof request.method !== 'string') {
    return errorEnvelope(request?.id ?? null, -32600, 'Invalid Request');
  }

  const { id, method, params } = request;
  try {
    if (method === 'notifications/initialized' || method.startsWith('notifications/')) {
      return undefined;
    }
    if (method === 'initialize') {
      return resultEnvelope(id, {
        protocolVersion: PROTOCOL_VERSION,
        capabilities: { tools: { listChanged: false } },
        serverInfo: SERVER_INFO,
        instructions: 'Demo MCP server for the company AgentScope platform.'
      });
    }
    if (method === 'tools/list') {
      return resultEnvelope(id, { tools });
    }
    if (method === 'tools/call') {
      return resultEnvelope(id, callTool(params || {}));
    }
    return errorEnvelope(id, -32601, `Method not found: ${method}`);
  } catch (error) {
    return errorEnvelope(id, -32603, error.message || 'Internal error');
  }
}

function callTool(params) {
  const name = String(params.name || '');
  const callArgs = params.arguments && typeof params.arguments === 'object' ? params.arguments : {};
  if (name === 'platform_echo') return textResult(String(callArgs.text ?? ''));
  if (name === 'platform_time') {
    const timezone = String(callArgs.timezone || Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC');
    const now = new Date();
    return textResult(JSON.stringify({ iso: now.toISOString(), timezone, local: now.toLocaleString('zh-CN', { timeZone: timezone }) }, null, 2));
  }
  if (name === 'platform_sum') {
    const numbers = Array.isArray(callArgs.numbers) ? callArgs.numbers.map(Number) : [];
    if (numbers.some((n) => Number.isNaN(n))) throw new Error('numbers must contain only numeric values');
    return textResult(String(numbers.reduce((a, b) => a + b, 0)));
  }
  throw new Error(`Unknown tool: ${name}`);
}

function textResult(text) {
  return { content: [{ type: 'text', text }] };
}

function resultEnvelope(id, result) {
  return { jsonrpc: '2.0', id, result };
}

function errorEnvelope(id, code, message) {
  return { jsonrpc: '2.0', id, error: { code, message } };
}

function writeJsonRpc(stream, message) {
  if (message === undefined) return;
  stream.write(`${JSON.stringify(message)}\n`);
}

function sendJson(res, status, body, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json', ...headers });
  res.end(JSON.stringify(body));
}

function sendSse(res, body, event = 'message') {
  res.write(`event: ${event}\n`);
  res.write(`data: ${JSON.stringify(body)}\n\n`);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.setEncoding('utf8');
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      try { resolve(body ? JSON.parse(body) : {}); }
      catch (error) { reject(error); }
    });
    req.on('error', reject);
  });
}

process.on('uncaughtException', (error) => {
  process.stderr.write(`[platform-demo-mcp] ${error.stack || error.message}\n`);
});

