# Demo MCP transports

This folder contains one demo MCP implementation that can run as:

- stdio: `node mcp-servers/platform-demo/server.mjs --transport stdio`
- streamable HTTP: `node mcp-servers/platform-demo/server.mjs --transport streamable-http --port 8765`
- SSE: `node mcp-servers/platform-demo/server.mjs --transport sse --port 8766`

The stdio server is enabled by default in `company-platform/workspace/mcps.yml`.
The HTTP/SSE entries are present but disabled by default because they require starting this process separately before binding them to an Agent.
