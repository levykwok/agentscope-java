<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import AgentWaitingCard from '../components/AgentWaitingCard.vue'
import { currentDomain, currentOrgId, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { notifyError } from '../stores/notify'

const agents = ref<JsonMap[]>([])
const selectedId = ref('')
const domainFilter = ref(currentDomain(''))
const keyword = ref('')
const domainOptions = ref<JsonMap[]>([{ domain: '', label: '全部业务域' }, { domain: 'platform', label: '平台' }])
const sessions = ref<JsonMap[]>([])
const selectedSessionId = ref('')
const sessionDetail = ref<JsonMap | null>(null)
const sessionAssetTab = ref<'context' | 'log' | 'tasks' | 'memory' | 'files'>('context')
const sessionPanelOpen = ref(false)
const messages = ref<JsonMap[]>([])
const query = ref('')
const running = ref(false)
const flowKey = ref('')
const msgsEl = ref<HTMLElement | null>(null)

const selected = computed(() => agents.value.find((a) => a.agent_id === selectedId.value) || null)
const visible = computed(() => agents.value.filter((a) => {
  if (domainFilter.value && String(a.domain || '') !== domainFilter.value) return false
  const q = keyword.value.trim().toLowerCase()
  if (!q) return true
  return [a.display_name, a.name, a.agent_id, a.description].join(' ').toLowerCase().includes(q)
}))
const flowKeys = computed<string[]>(() => {
  const wf = (selected.value?.flow_bindings || selected.value?.flows) as JsonMap | undefined
  return wf && typeof wf === 'object' && !Array.isArray(wf) ? Object.keys(wf) : []
})
const sessionRaw = computed(() => (sessionDetail.value?.raw || {}) as JsonMap)
const sessionFiles = computed(() => (sessionDetail.value?.files || {}) as JsonMap)
const sessionTasks = computed(() => sessionDetail.value?.tasks || {})
const sessionMemory = computed(() => (sessionDetail.value?.memory || {}) as JsonMap)
const contextEntryCount = computed(() => Array.isArray(sessionDetail.value?.context_entries) ? sessionDetail.value.context_entries.length : 0)
const logEntryCount = computed(() => Array.isArray(sessionDetail.value?.log_entries) ? sessionDetail.value.log_entries.length : 0)

function headers(json = false) { return makeHeaders(json, currentOrgId()) }
function isBuiltin(a: JsonMap) { return String(a.source || 'builtin') === 'builtin' }
function normalizeMessage(m: JsonMap) {
  return { role: String(m.role || 'assistant'), content: String(m.content || m.text || '') }
}

async function loadDomains() {
  try {
    const status = await readJson<JsonMap>(await fetch('/platform/frontend/infra/status', { headers: headers(false) }))
    const raw = status.domains && typeof status.domains === 'object' ? status.domains as JsonMap : {}
    domainOptions.value = [{ domain: '', label: '全部业务域' }, { domain: 'platform', label: '平台' }, ...Object.entries(raw).map(([d, s]) => ({ domain: d, label: String((s as JsonMap)?.display_name || d) })).filter((r) => r.domain !== 'platform')]
  } catch { /* ignore */ }
}
async function loadAgents() {
  try {
    const qs = domainFilter.value ? `?domain=${encodeURIComponent(domainFilter.value)}` : ''
    const d = await readJson<JsonMap>(await fetch(`/platform/frontend/agents${qs}`, { headers: headers(false) }))
    agents.value = (d.items || d.agents || []) as JsonMap[]
    if ((!selectedId.value || !agents.value.some((a) => a.agent_id === selectedId.value)) && agents.value[0]) selectAgent(String(agents.value[0].agent_id))
  } catch (err) { notifyError(err) }
}
async function loadSessions() {
  try {
    const p = new URLSearchParams()
    if (domainFilter.value) p.set('domain', domainFilter.value)
    if (selectedId.value) p.set('agent_id', selectedId.value)
    const qs = p.toString() ? `?${p}` : ''
    const d = await readJson<JsonMap>(await fetch(`/platform/frontend/chat/sessions${qs}`, { headers: headers(false) }))
    sessions.value = (d.items || d.sessions || []) as JsonMap[]
    if (!selectedSessionId.value && sessions.value[0]) await selectSession(String(sessions.value[0].session_id || sessions.value[0].id))
  } catch (err) { notifyError(err) }
}
async function createSession() {
  try {
    const title = selected.value ? `和 ${selected.value.display_name || selected.value.agent_id} 的对话` : '新对话'
    const d = await readJson<JsonMap>(await fetch('/platform/frontend/chat/sessions', { method: 'POST', headers: headers(true), body: JSON.stringify({ title, domain: currentDomain('platform'), agent_id: selectedId.value }) }))
    const row = (d.session || d.item || d) as JsonMap
    await loadSessions()
    await selectSession(String(row.session_id || row.id))
  } catch (err) { notifyError(err) }
}
async function selectSession(id: string) {
  selectedSessionId.value = id
  await loadSessionDetail(id, true)
}
async function loadSessionDetail(id: string, replaceMessages: boolean) {
  try {
    const qs = selectedId.value ? `?agent_id=${encodeURIComponent(selectedId.value)}` : ''
    const d = await readJson<JsonMap>(await fetch(`/platform/frontend/chat/sessions/${encodeURIComponent(id)}${qs}`, { headers: headers(false) }))
    sessionDetail.value = d
    if (replaceMessages) messages.value = ((d.messages || []) as JsonMap[]).map(normalizeMessage)
    await scrollDown()
  } catch (err) { notifyError(err) }
}
async function deleteSession(id: string) {
  try {
    const qs = selectedId.value ? `?agent_id=${encodeURIComponent(selectedId.value)}` : ''
    await readJson<JsonMap>(await fetch(`/platform/frontend/chat/sessions/${encodeURIComponent(id)}${qs}`, { method: 'DELETE', headers: headers(false) }))
    if (selectedSessionId.value === id) { selectedSessionId.value = ''; messages.value = []; sessionDetail.value = null }
    await loadSessions()
  } catch (err) { notifyError(err) }
}
async function selectAgent(id: string) {
  selectedId.value = id
  messages.value = []
  sessionDetail.value = null
  flowKey.value = ''
  selectedSessionId.value = ''
  await loadSessions()
}

async function scrollDown() { await nextTick(); if (msgsEl.value) msgsEl.value.scrollTop = msgsEl.value.scrollHeight }

function resultTextFromRun(run: JsonMap) {
  const out = (run.output_ref || {}) as JsonMap
  const res = (out.result || out) as JsonMap
  return String(res.answer || res.text || '').trim()
}

function eventTitle(ev: JsonMap) {
  const type = String(ev.event_type || ev.type || '')
  if (type === 'waiting_user_input') return '等待用户输入'
  if (type === 'waiting.resumed') return '已提交用户输入'
  if (type === 'waiting.rejected') return '用户取消等待'
  if (type === 'resume.continuation.scheduled') return '已排队继续执行'
  if (type === 'resume.continuation.started') return '继续执行'
  if (type === 'tool_call.started') return '工具调用开始'
  if (type === 'tool_call.succeeded') return '工具调用完成'
  if (type === 'tool_call.failed') return '工具调用失败'
  if (type === 'run.succeeded') return '运行完成'
  if (type === 'run.failed') return '运行失败'
  return type || '事件'
}

function eventStatus(ev: JsonMap) {
  const type = String(ev.event_type || ev.type || '')
  if (type.includes('failed') || type.includes('rejected') || type.includes('expired')) return 'error'
  if (type.includes('started') || type.includes('scheduled') || type === 'waiting_user_input') return type === 'waiting_user_input' ? 'warning' : 'running'
  return 'success'
}

function appendEventSteps(msg: JsonMap, events: JsonMap[]) {
  const seen = new Set((msg.steps || []).map((s: JsonMap) => String(s.event_id || `${s.step}:${s.title}:${s.summary}`)))
  for (const ev of events) {
    const id = String(ev.event_id || '')
    if (id && seen.has(id)) continue
    const payload = (ev.payload || {}) as JsonMap
    const row = { event_id: id, step: ev.event_type || ev.type, title: eventTitle(ev), status: eventStatus(ev), summary: payload.tool_name || payload.waiting_id || payload.error || '', detail: payload }
    msg.steps.push(row)
    if (id) seen.add(id)
  }
}

async function fetchRunEvents(runId: string, afterId?: number) {
  const p = new URLSearchParams({ limit: '200' })
  if (afterId && afterId > 0) p.set('after_id', String(afterId))
  return await readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(runId)}/events?${p}`, { headers: headers(false) }))
}

async function refreshRunMessage(msg: JsonMap) {
  const runId = String(msg.meta?.run_id || '')
  if (!runId) return null
  const afterId = Number((msg.meta || {}).events_after_id || 0)
  const [run, waiting, eventPage] = await Promise.all([
    readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(runId)}`, { headers: headers(false) })),
    readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(runId)}/waiting`, { headers: headers(false) })).catch(() => ({ item: null })),
    fetchRunEvents(runId, afterId).catch((): JsonMap => ({ events: [], items: [], next_after_id: afterId, waiting: null })),
  ])
  const detail = (run.run || run) as JsonMap
  const events = Array.isArray(eventPage.events) ? eventPage.events : Array.isArray(eventPage.items) ? eventPage.items : []
  appendEventSteps(msg, events)
  msg.meta = { ...(msg.meta || {}), events_after_id: Number(eventPage.next_after_id || afterId || 0) }
  msg.waiting = waiting.item || eventPage.waiting || null
  if (detail.status === 'succeeded') {
    msg.content = resultTextFromRun(detail) || msg.content || '已完成。'
    msg.pending = false
    msg.waiting = null
  } else if (['failed', 'cancelled', 'canceled'].includes(String(detail.status || ''))) {
    msg.content = detail.error?.message ? `执行失败：${detail.error.message}` : msg.content || '执行失败。'
    msg.meta = { ...(msg.meta || {}), error: true }
    msg.pending = false
  } else if (detail.status === 'waiting_user_input') {
    msg.pending = false
  } else {
    msg.pending = true
  }
  await scrollDown()
  return detail
}

async function pollRunUntilSettled(msg: JsonMap) {
  for (let i = 0; i < 60; i += 1) {
    const detail = await refreshRunMessage(msg)
    const status = String(detail?.status || '')
    if (['succeeded', 'failed', 'cancelled', 'canceled', 'waiting_user_input'].includes(status)) return
    await new Promise((resolve) => setTimeout(resolve, 1500))
  }
  msg.pending = false
}

async function onWaitingResumed(msg: JsonMap) {
  msg.waiting = null
  msg.pending = true
  msg.content = '已提交，继续执行中...'
  await pollRunUntilSettled(msg)
}

async function onWaitingRejected(msg: JsonMap) {
  msg.waiting = null
  msg.pending = false
  msg.content = '已取消本次等待。'
  await refreshRunMessage(msg)
}

async function send() {
  const text = query.value.trim()
  if (!text || running.value || !selectedId.value) return
  if (!selectedSessionId.value) await createSession()
  messages.value.push({ role: 'user', content: text })
  messages.value.push({ role: 'assistant', content: '', steps: [], meta: null, pending: true })
  const msg = messages.value[messages.value.length - 1]
  query.value = ''
  running.value = true
  await scrollDown()
  const t0 = Date.now()
  const handle = (ev: JsonMap) => {
    if (ev.type === 'activity') {
      msg.steps.push({ step: ev.step, title: ev.title, status: ev.status, summary: ev.summary })
      scrollDown()
    } else if (ev.type === 'waiting_user_input') {
      msg.waiting = ev.waiting || ev
      msg.pending = false
      msg.content = ''
      msg.meta = { ...(msg.meta || {}), run_id: ev.run_id, status: 'waiting_user_input' }
      msg.steps.push({ step: 'waiting_user_input', title: '等待用户输入', status: 'warning', summary: String((ev.waiting || ev).question || '') })
      scrollDown()
    } else if (ev.type === 'done') {
      const out = (ev.output_ref || {}) as JsonMap
      const res = (out.result || out) as JsonMap
      msg.content = ev.status === 'waiting_user_input' ? '' : String(res.answer || res.text || '').trim() || '（无回答）'
      msg.meta = { route: res.route || res.effective_mode || ev.flow_name, trace_id: ev.trace_id, citations: Array.isArray(res.citations) ? res.citations : [], elapsed: Date.now() - t0, run_id: ev.run_id }
      msg.pending = ev.status !== 'waiting_user_input' ? false : msg.pending
      if (ev.status === 'waiting_user_input' && out.waiting_user_input) msg.waiting = out.waiting_user_input
    } else if (ev.type === 'error') {
      msg.content = `出错：${ev.message || ev.error || '执行失败'}`
      msg.meta = { error: true }
      msg.pending = false
    }
  }
  try {
    const body: JsonMap = { agent_id: selectedId.value, session_id: selectedSessionId.value, input_type: 'chat', payload: { query: text }, context: {}, artifacts: [] }
    if (flowKey.value) body.flow_name = flowKey.value
    const resp = await fetch('/agent-runs/run/stream', { method: 'POST', headers: headers(true), body: JSON.stringify(body) })
    if (!resp.ok || !resp.body) throw new Error(`HTTP ${resp.status}`)
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      const parts = buf.split('\n\n')
      buf = parts.pop() || ''
      for (const part of parts) {
        const line = part.split('\n').find((l) => l.startsWith('data:'))
        if (!line) continue
        try { handle(JSON.parse(line.slice(5).trim())) } catch { /* ignore */ }
      }
    }
    if (msg.pending) { msg.content = msg.content || '（无回答）'; msg.pending = false }
  } catch (err) {
    msg.content = `请求失败：${err instanceof Error ? err.message : String(err)}`
    msg.meta = { error: true }
    msg.pending = false
    notifyError(err)
  } finally {
    running.value = false
    await loadSessions()
    if (selectedSessionId.value) await loadSessionDetail(selectedSessionId.value, false)
    await scrollDown()
  }
}

onMounted(async () => { await loadDomains(); await loadAgents(); await loadSessions() })
</script>

<template>
  <section class="agent-admin">
    <aside class="left-pane">
      <div class="pane-head"><h2>选择 Agent</h2></div>
      <div class="wb-filters">
        <select v-model="domainFilter" @change="loadAgents"><option v-for="d in domainOptions" :key="d.domain as string" :value="d.domain">{{ d.label }}</option></select>
        <input v-model="keyword" placeholder="搜索 agent 名称 / ID…" />
      </div>
      <div class="entity-list">
        <div v-for="a in visible" :key="a.agent_id" class="entity-item" :class="{ selected: selectedId === a.agent_id }" @click="selectAgent(String(a.agent_id))">
          <div class="entity-name">{{ a.display_name || a.name || a.agent_id }}</div>
          <span class="entity-type">{{ a.domain || 'platform' }}</span>
          <span class="badge" :class="isBuiltin(a) ? 'badge-builtin' : 'badge-db'">{{ isBuiltin(a) ? '内置' : '自定义' }}</span>
        </div>
        <div v-if="!visible.length" class="empty">暂无 Agent</div>
      </div>
      <div class="session-panel">
        <div class="session-head">
          <h3>会话</h3>
          <button class="btn btn-primary btn-sm" @click="createSession">新建</button>
        </div>
        <div class="session-list">
          <div v-for="s in sessions" :key="s.session_id || s.id" class="session-item" :class="{ selected: selectedSessionId === String(s.session_id || s.id) }" @click="selectSession(String(s.session_id || s.id))">
            <div class="session-title">{{ s.title || '新对话' }}</div>
            <button class="session-del" title="删除会话" @click.stop="deleteSession(String(s.session_id || s.id))">×</button>
          </div>
          <div v-if="!sessions.length" class="empty small">暂无会话</div>
        </div>
      </div>
    </aside>

    <main class="wb-main">
      <div v-if="selected" class="wb-bar">
        <div class="wb-icon">🤖</div>
        <div class="wb-agent">
          <strong>{{ selected.display_name || selected.name || selected.agent_id }}</strong>
          <span>{{ selected.agent_id }}</span>
          <span v-if="selectedSessionId">session: {{ selectedSessionId }}</span>
        </div>
        <div class="wb-bar-right">
          <button class="btn btn-ghost btn-sm" :disabled="!selectedSessionId" @click="sessionPanelOpen = true">会话详情</button>
          <select v-if="flowKeys.length" v-model="flowKey" class="wb-flow"><option value="">自动路由 / 默认 Flow</option><option v-for="k in flowKeys" :key="k" :value="k">{{ k }}</option></select>
          <button class="btn btn-ghost btn-sm" :disabled="!messages.length" @click="messages = []">清空对话</button>
        </div>
      </div>

      <div ref="msgsEl" class="wb-msgs">
        <div v-if="!messages.length" class="wb-empty">
          <div class="wb-empty-icon">💬</div>
          <h3>{{ selected ? '开始与 ' + (selected.display_name || selected.agent_id) + ' 对话' : '选择左侧 Agent' }}</h3>
          <p>这里用你配置好的 Agent 正式对话（按其 Flow / 工具 / 技能 / 模型策略执行）。</p>
        </div>
        <div v-for="(m, i) in messages" :key="i" class="msg-row" :class="m.role">
          <div class="msg-avatar" :class="m.role === 'user' ? 'user' : 'ai'">{{ m.role === 'user' ? '你' : 'AI' }}</div>
          <div class="bubble-wrap">
            <details v-if="m.role !== 'user' && (m.steps || []).length" class="wb-steps" :open="m.pending">
              <summary>执行过程 · {{ m.steps.length }} 步</summary>
              <div v-for="(st, si) in m.steps" :key="si" class="wb-step" :class="st.status">
                <span class="wb-step-dot"></span>
                <span class="wb-step-t">{{ st.title }}</span>
                <span v-if="st.summary" class="wb-step-s">{{ st.summary }}</span>
              </div>
            </details>
            <div v-if="m.role !== 'user' || m.content" class="bubble" :class="[m.role === 'user' ? 'user' : 'ai', m.meta?.error ? 'err' : '', m.pending && !m.content ? 'streaming' : '']">{{ m.content || (m.pending ? (m.steps && m.steps.length ? '生成回答中…' : '运行中…') : '') }}</div>
            <AgentWaitingCard v-if="m.waiting && m.meta?.run_id" :run-id="String(m.meta.run_id)" :waiting="m.waiting" @resumed="onWaitingResumed(m)" @rejected="onWaitingRejected(m)" />
            <div v-if="m.meta && !m.meta.error" class="msg-meta">
              <span v-if="m.meta.route">route: {{ m.meta.route }}</span>
              <span v-if="m.meta.elapsed">{{ m.meta.elapsed }} ms</span>
              <span v-if="(m.meta.citations || []).length">{{ m.meta.citations.length }} 引用</span>
            </div>
            <div v-if="(m.meta?.citations || []).length" class="sources">
              <span v-for="(c, ci) in m.meta.citations.slice(0, 5)" :key="ci" class="source-chip">📄 {{ c.filename || c.doc_id || c.title || '来源' }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="wb-composer">
        <textarea v-model="query" :disabled="!selectedId" rows="1" placeholder="输入消息，Enter 发送，Shift+Enter 换行…" @keydown.enter.exact.prevent="send" />
        <button class="btn btn-primary" :disabled="running || !query.trim() || !selectedId" @click="send">{{ running ? '运行中…' : '发送' }}</button>
      </div>
    </main>

    <div v-if="sessionPanelOpen" class="session-drawer-mask" @click="sessionPanelOpen = false"></div>
    <aside v-if="sessionPanelOpen" class="session-drawer">
      <div class="drawer-head">
        <div>
          <h3>会话详情</h3>
          <p>AgentScope workspace 里的 context / log / tasks / memory。</p>
        </div>
        <button class="drawer-close" @click="sessionPanelOpen = false">×</button>
      </div>
      <div class="drawer-session">
        <span>session</span>
        <strong>{{ selectedSessionId || '未选择' }}</strong>
      </div>
      <div class="asset-tabs">
        <button :class="{ active: sessionAssetTab === 'context' }" @click="sessionAssetTab = 'context'">上下文 {{ contextEntryCount }}</button>
        <button :class="{ active: sessionAssetTab === 'log' }" @click="sessionAssetTab = 'log'">日志 {{ logEntryCount }}</button>
        <button :class="{ active: sessionAssetTab === 'tasks' }" @click="sessionAssetTab = 'tasks'">任务</button>
        <button :class="{ active: sessionAssetTab === 'memory' }" @click="sessionAssetTab = 'memory'">记忆</button>
        <button :class="{ active: sessionAssetTab === 'files' }" @click="sessionAssetTab = 'files'">文件</button>
      </div>
      <pre v-if="sessionAssetTab === 'context'" class="asset-raw">{{ sessionRaw.context || '暂无 context jsonl。' }}</pre>
      <pre v-else-if="sessionAssetTab === 'log'" class="asset-raw">{{ sessionRaw.log || '暂无 log jsonl。' }}</pre>
      <pre v-else-if="sessionAssetTab === 'tasks'" class="asset-raw">{{ JSON.stringify(sessionTasks, null, 2) }}</pre>
      <pre v-else-if="sessionAssetTab === 'memory'" class="asset-raw">{{ sessionMemory.memory_md || '暂无 MEMORY.md。' }}</pre>
      <pre v-else class="asset-raw">{{ JSON.stringify(sessionFiles, null, 2) }}</pre>
    </aside>
  </section>
</template>

<style scoped>
.wb-filters { padding: 10px 12px; border-bottom: 1px solid var(--border); display: grid; gap: 7px; }
.wb-filters select, .wb-filters input { height: 34px; width: 100%; }
.session-panel { border-top: 1px solid var(--border); padding: 12px; display: flex; flex-direction: column; gap: 10px; min-height: 190px; }
.session-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.session-head h3 { font-size: 13px; font-weight: 800; color: var(--text); }
.session-list { display: flex; flex-direction: column; gap: 6px; overflow-y: auto; }
.session-item { display: flex; align-items: center; gap: 8px; padding: 8px 9px; border: 1px solid #e2e8f0; border-radius: 10px; background: #fff; cursor: pointer; transition: border-color .16s, background .16s; }
.session-item:hover { border-color: #bfdbfe; background: #f8fbff; }
.session-item.selected { border-color: #2563eb; background: #eff6ff; }
.session-title { flex: 1; min-width: 0; font-size: 12px; font-weight: 700; color: #334155; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.session-del { border: 0; background: transparent; color: #94a3b8; cursor: pointer; font-size: 18px; line-height: 1; }
.session-del:hover { color: #dc2626; }
.empty.small { padding: 14px; font-size: 12px; }
.wb-main { display: flex; flex-direction: column; overflow: hidden; min-height: 0; }
.wb-bar { display: flex; align-items: center; gap: 12px; padding: 12px 18px; border-bottom: 1px solid var(--border); background: #fff; flex-shrink: 0; }
.wb-icon { width: 38px; height: 38px; border-radius: 10px; background: linear-gradient(135deg, var(--blue), #818cf8); display: flex; align-items: center; justify-content: center; font-size: 18px; flex-shrink: 0; }
.wb-agent { display: flex; flex-direction: column; min-width: 0; }
.wb-agent strong { font-size: 14px; font-weight: 700; }
.wb-agent span { font-size: 11px; color: var(--muted); font-family: ui-monospace, Menlo, Consolas, monospace; }
.wb-bar-right { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.wb-flow { height: 32px; font-size: 12px; max-width: 220px; }
.wb-msgs { flex: 1; overflow-y: auto; padding: 22px 24px; display: flex; flex-direction: column; gap: 16px; background: linear-gradient(180deg, #f7f9fc, #eef2f8); }
.wb-empty { margin: auto; text-align: center; color: #94a3b8; max-width: 420px; }
.wb-empty-icon { font-size: 42px; margin-bottom: 8px; }
.wb-empty h3 { font-size: 16px; font-weight: 700; color: var(--text); margin-bottom: 6px; }
.wb-empty p { font-size: 13px; line-height: 1.7; }
.bubble.err { background: #fef2f2; border-color: #fecaca; color: #991b1b; }
.msg-meta { display: flex; gap: 10px; flex-wrap: wrap; font-size: 11px; color: var(--muted); margin-top: 4px; }
.wb-steps { margin-bottom: 6px; border: 1px solid #e2e8f0; border-radius: 10px; background: #fff; padding: 6px 10px; max-width: 600px; }
.wb-steps summary { font-size: 11px; font-weight: 600; color: var(--muted); cursor: pointer; }
.wb-step { display: flex; align-items: center; gap: 7px; font-size: 12px; padding: 4px 0; color: #334155; }
.wb-step-dot { width: 8px; height: 8px; border-radius: 50%; background: #cbd5e1; flex-shrink: 0; }
.wb-step.success .wb-step-dot { background: var(--green); }
.wb-step.skipped .wb-step-dot { background: #cbd5e1; }
.wb-step.error .wb-step-dot, .wb-step.failed .wb-step-dot { background: var(--red); }
.wb-step-t { font-weight: 600; }
.wb-step-s { color: var(--muted); font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.wb-composer { border-top: 1px solid var(--border); padding: 12px 16px; display: flex; gap: 10px; align-items: flex-end; background: #fff; flex-shrink: 0; }
.wb-composer textarea { flex: 1; min-height: 44px; max-height: 140px; resize: none; line-height: 1.5; padding: 10px 14px; }
.session-drawer-mask { position: fixed; inset: 0; background: rgba(15, 23, 42, .24); z-index: 30; }
.session-drawer { position: fixed; top: 0; right: 0; bottom: 0; width: min(520px, 92vw); background: #fff; border-left: 1px solid var(--border); box-shadow: -18px 0 45px rgba(15, 23, 42, .18); z-index: 31; display: flex; flex-direction: column; }
.drawer-head { padding: 18px 20px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; gap: 14px; }
.drawer-head h3 { font-size: 18px; font-weight: 800; color: var(--text); margin-bottom: 5px; }
.drawer-head p { font-size: 12px; color: var(--muted); line-height: 1.6; }
.drawer-close { border: 0; background: transparent; color: #64748b; cursor: pointer; font-size: 26px; line-height: 1; }
.drawer-close:hover { color: #0f172a; }
.drawer-session { margin: 14px 18px 10px; padding: 10px 12px; border: 1px solid #e2e8f0; border-radius: 12px; background: #f8fafc; display: grid; gap: 4px; }
.drawer-session span { font-size: 11px; color: var(--muted); text-transform: uppercase; letter-spacing: .08em; }
.drawer-session strong { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; color: #334155; overflow-wrap: anywhere; }
.asset-tabs { display: flex; gap: 7px; flex-wrap: wrap; padding: 0 18px 12px; border-bottom: 1px solid var(--border); }
.asset-tabs button { border: 1px solid #dbeafe; background: #fff; border-radius: 999px; padding: 6px 11px; font-size: 12px; color: #334155; cursor: pointer; }
.asset-tabs button.active { background: #2563eb; border-color: #2563eb; color: #fff; }
.asset-raw { flex: 1; overflow: auto; margin: 0; padding: 16px 18px; background: #0f172a; color: #dbeafe; font-size: 12px; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 980px) {
  .agent-admin { grid-template-columns: 1fr; overflow: visible; }
  .wb-main { min-height: 600px; }
}
</style>
