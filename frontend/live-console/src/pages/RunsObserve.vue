<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AgentWaitingCard from '../components/AgentWaitingCard.vue'
import { currentOrgId, fmtDate, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { notifyError } from '../stores/notify'

const runs = ref<JsonMap[]>([])
const agentsList = ref<JsonMap[]>([])
const agentFilter = ref('')
const statusFilter = ref('')
const limit = ref('50')
const loading = ref(false)
const selectedRunId = ref('')
const detail = ref<JsonMap | null>(null)
const steps = ref<JsonMap[]>([])
const events = ref<JsonMap[]>([])
const waiting = ref<JsonMap | null>(null)
const detailLoading = ref(false)

function headers(json = false) { return makeHeaders(json, currentOrgId()) }
function statusCls(s: string) {
  const v = String(s || '').toLowerCase()
  if (['succeeded', 'success', 'completed', 'ok', 'done'].includes(v)) return 'badge-green'
  if (['failed', 'error', 'cancelled', 'canceled'].includes(v)) return 'badge-red'
  if (['running', 'queued', 'pending', 'in_progress'].includes(v)) return 'badge-amber'
  return 'badge-gray'
}
function agentName(id: string) { return agentsList.value.find((a) => a.agent_id === id)?.display_name || id }
function elapsed(r: JsonMap) {
  const a = r.started_at ? Date.parse(String(r.started_at)) : 0
  const b = r.finished_at ? Date.parse(String(r.finished_at)) : 0
  if (a && b && b >= a) return `${b - a} ms`
  return '—'
}
function answerOf(r: JsonMap | null) {
  if (!r) return ''
  const out = (r.output_ref || {}) as JsonMap
  const res = (out.result || out) as JsonMap
  return String(res.answer || out.answer || res.text || '').trim()
}

async function loadAgents() {
  try {
    const d = await readJson<JsonMap>(await fetch('/platform/frontend/agents', { headers: headers(false) }))
    agentsList.value = (d.items || d.agents || []) as JsonMap[]
  } catch { /* ignore */ }
}
async function loadRuns() {
  loading.value = true
  try {
    const p = new URLSearchParams({ limit: limit.value || '50' })
    if (agentFilter.value) p.set('agent_id', agentFilter.value)
    if (statusFilter.value) p.set('status', statusFilter.value)
    const d = await readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs?${p}`, { headers: headers(false) }))
    runs.value = (d.items || d.runs || []) as JsonMap[]
  } catch (err) {
    notifyError(err)
  } finally {
    loading.value = false
  }
}
async function openRun(r: JsonMap) {
  selectedRunId.value = String(r.run_id)
  detailLoading.value = true
  detail.value = r
  steps.value = []
  events.value = []
  waiting.value = null
  try {
    const [d, s, e, w] = await Promise.all([
      readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(String(r.run_id))}`, { headers: headers(false) })),
      readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(String(r.run_id))}/steps`, { headers: headers(false) })),
      readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(String(r.run_id))}/events?limit=200`, { headers: headers(false) })),
      readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(String(r.run_id))}/waiting`, { headers: headers(false) })).catch(() => ({ item: null })),
    ])
    detail.value = d.run || d
    steps.value = (s.items || s.steps || []) as JsonMap[]
    events.value = (e.items || e.events || []) as JsonMap[]
    waiting.value = (w.item || null) as JsonMap | null
  } catch (err) {
    notifyError(err)
  } finally {
    detailLoading.value = false
  }
}
function pretty(v: unknown) { try { return JSON.stringify(v ?? {}, null, 2) } catch { return String(v ?? '') } }
function eventStatusCls(ev: JsonMap) {
  const type = String(ev.event_type || ev.type || '')
  if (type.includes('failed') || type.includes('rejected') || type.includes('expired')) return 'badge-red'
  if (type.includes('started') || type.includes('scheduled') || type === 'waiting_user_input') return 'badge-amber'
  return 'badge-green'
}
function eventSummary(ev: JsonMap) {
  const p = (ev.payload || {}) as JsonMap
  return String(p.tool_name || p.waiting_id || p.error || p.stage || '')
}
async function refreshSelectedRun() {
  const run = detail.value
  if (!run?.run_id) return
  await openRun(run)
}
async function onWaitingChanged() {
  await refreshSelectedRun()
}

const stats = computed(() => ({
  total: runs.value.length,
  ok: runs.value.filter((r) => statusCls(String(r.status)) === 'badge-green').length,
  fail: runs.value.filter((r) => statusCls(String(r.status)) === 'badge-red').length,
  running: runs.value.filter((r) => statusCls(String(r.status)) === 'badge-amber').length,
}))

onMounted(async () => { await loadAgents(); await loadRuns() })
</script>

<template>
  <div class="runs-page">
    <div class="stats">
      <div class="stat"><div class="stat-label">运行总数</div><div class="stat-val">{{ stats.total }}</div></div>
      <div class="stat"><div class="stat-label">成功</div><div class="stat-val" style="color:var(--green)">{{ stats.ok }}</div></div>
      <div class="stat"><div class="stat-label">失败</div><div class="stat-val" :style="stats.fail ? 'color:var(--red)' : ''">{{ stats.fail }}</div></div>
      <div class="stat"><div class="stat-label">运行中</div><div class="stat-val" :style="stats.running ? 'color:var(--yellow)' : ''">{{ stats.running }}</div></div>
    </div>

    <div class="runs-body">
      <aside class="runs-list-pane">
        <div class="runs-filters">
          <select v-model="agentFilter" @change="loadRuns"><option value="">全部 Agent</option><option v-for="a in agentsList" :key="a.agent_id as string" :value="a.agent_id">{{ a.display_name || a.agent_id }}</option></select>
          <select v-model="statusFilter" @change="loadRuns"><option value="">全部状态</option><option value="succeeded">succeeded</option><option value="failed">failed</option><option value="running">running</option><option value="queued">queued</option></select>
          <div class="runs-filter-row"><input v-model="limit" type="number" min="1" placeholder="条数" @keydown.enter="loadRuns" /><button class="btn btn-ghost btn-sm" @click="loadRuns">{{ loading ? '刷新中' : '刷新' }}</button></div>
        </div>
        <div class="runs-list">
          <div v-if="!runs.length" class="empty">{{ loading ? '加载中…' : '暂无运行记录' }}</div>
          <button v-for="r in runs" :key="r.run_id as string" class="run-item" :class="{ selected: selectedRunId === r.run_id }" @click="openRun(r)">
            <div class="run-item-head">
              <span class="run-agent">{{ agentName(String(r.agent_id)) }}</span>
              <span class="badge" :class="statusCls(String(r.status))">{{ r.status }}</span>
            </div>
            <div class="run-item-sub">{{ fmtDate(r.created_at) }}</div>
            <div class="run-item-id">{{ r.run_id }}</div>
          </button>
        </div>
      </aside>

      <main class="run-detail">
        <div v-if="!detail" class="empty-state"><div class="empty-icon">📈</div><h3>运行观测</h3><p>选择左侧一次运行，查看它的状态、步骤、工具调用与结果。</p></div>
        <template v-else>
          <section class="panel">
            <div class="section-head"><div><div class="section-title">{{ agentName(String(detail.agent_id)) }}</div><div class="section-sub mono">{{ detail.run_id }}</div></div><span class="badge" :class="statusCls(String(detail.status))">{{ detail.status }}</span></div>
            <div class="rd-grid">
              <div class="rd-cell"><span>Agent</span><b>{{ detail.agent_id }}</b></div>
              <div class="rd-cell"><span>版本</span><b>{{ detail.spec_key || detail.agent_version || 'main' }}</b></div>
              <div class="rd-cell"><span>耗时</span><b>{{ elapsed(detail) }}</b></div>
              <div class="rd-cell"><span>用户</span><b>{{ detail.user_id || '—' }}</b></div>
              <div class="rd-cell"><span>开始</span><b>{{ detail.started_at ? fmtDate(detail.started_at) : '—' }}</b></div>
              <div class="rd-cell"><span>结束</span><b>{{ detail.finished_at ? fmtDate(detail.finished_at) : '—' }}</b></div>
              <div class="rd-cell wide"><span>Trace</span><b class="mono">{{ detail.trace_id || '—' }}</b></div>
            </div>
            <div v-if="detail.error" class="rd-error">错误 [{{ detail.error.code }}]：{{ detail.error.message }}</div>
          </section>

          <section v-if="waiting" class="panel">
            <div class="section-head"><div class="section-title">当前等待</div><span class="section-sub">waiting_user_input</span></div>
            <AgentWaitingCard :run-id="String(detail.run_id)" :waiting="waiting" @resumed="onWaitingChanged" @rejected="onWaitingChanged" />
          </section>

          <section v-if="answerOf(detail)" class="panel"><div class="section-title">回答</div><div class="rd-answer">{{ answerOf(detail) }}</div></section>

          <section class="panel">
            <div class="section-head"><div class="section-title">执行步骤</div><span class="section-sub">{{ steps.length }} 步</span></div>
            <div v-if="detailLoading" class="empty">加载中…</div>
            <div v-else-if="!steps.length" class="empty">无步骤记录</div>
            <div v-else class="rd-steps">
              <div v-for="(st, i) in steps" :key="i" class="rd-step" :class="statusCls(String(st.status))">
                <div class="rd-step-rail"><span></span></div>
                <div class="rd-step-card">
                  <div class="rd-step-head">
                    <strong>{{ st.step_type }}</strong>
                    <span class="rd-step-id">{{ st.step_id }}</span>
                    <span class="badge" :class="statusCls(String(st.status))">{{ st.status }}</span>
                    <span v-if="st.duration_ms != null" class="rd-step-dur">{{ st.duration_ms }} ms</span>
                  </div>
                  <div v-if="st.error" class="rd-error sm">[{{ st.error.code }}] {{ st.error.message }}</div>
                  <details v-if="Object.keys(st.input_summary || {}).length || Object.keys(st.output_summary || {}).length">
                    <summary>输入 / 输出</summary>
                    <pre v-if="Object.keys(st.input_summary || {}).length" class="json-box">输入: {{ pretty(st.input_summary) }}</pre>
                    <pre v-if="Object.keys(st.output_summary || {}).length" class="json-box">输出: {{ pretty(st.output_summary) }}</pre>
                  </details>
                </div>
              </div>
            </div>
          </section>

          <section class="panel">
            <div class="section-head"><div class="section-title">Run Events</div><span class="section-sub">{{ events.length }} 条</span></div>
            <div v-if="!events.length" class="empty">无事件记录</div>
            <div v-else class="event-list">
              <article v-for="ev in events" :key="String(ev.event_id || ev.id)" class="event-row">
                <div class="event-main">
                  <strong>{{ ev.event_type || ev.type }}</strong>
                  <span v-if="eventSummary(ev)">{{ eventSummary(ev) }}</span>
                  <small>{{ fmtDate(ev.created_at) }}</small>
                </div>
                <span class="badge" :class="eventStatusCls(ev)">{{ ev.step_id || ev.sequence }}</span>
                <details v-if="Object.keys(ev.payload || {}).length">
                  <summary>payload</summary>
                  <pre class="json-box">{{ pretty(ev.payload) }}</pre>
                </details>
              </article>
            </div>
          </section>

          <section v-if="(detail.sandbox_runs || []).length" class="panel">
            <div class="section-head"><div class="section-title">沙箱运行</div><span class="section-sub">{{ detail.sandbox_runs.length }} 次</span></div>
            <div class="table-wrap"><table>
              <thead><tr><th>工具</th><th>类型</th><th>状态</th><th>耗时</th><th>退出码</th></tr></thead>
              <tbody><tr v-for="(sb, i) in detail.sandbox_runs" :key="i"><td class="mono">{{ sb.tool_name || '—' }}</td><td>{{ sb.sandbox_type || sb.backend || '—' }}</td><td><span class="badge" :class="statusCls(String(sb.status))">{{ sb.status }}</span></td><td>{{ sb.duration_ms != null ? sb.duration_ms + ' ms' : '—' }}</td><td>{{ sb.exit_code ?? '—' }}</td></tr></tbody>
            </table></div>
          </section>

          <section class="panel"><details><summary>原始 output_ref</summary><pre class="json-box">{{ pretty(detail.output_ref) }}</pre></details></section>
        </template>
      </main>
    </div>
  </div>
</template>

<style scoped>
.runs-page { flex: 1; overflow: hidden; padding: 18px 24px; display: flex; flex-direction: column; gap: 16px; }
.runs-page > .stats { flex-shrink: 0; }
.runs-body { flex: 1; min-height: 0; display: grid; grid-template-columns: 320px 1fr; gap: 16px; overflow: hidden; }
.runs-list-pane { display: flex; flex-direction: column; min-height: 0; background: var(--panel); border: 1px solid #eef2f7; border-radius: 12px; overflow: hidden; box-shadow: var(--shadow-sm); }
.runs-filters { padding: 10px 12px; border-bottom: 1px solid var(--border); display: flex; flex-direction: column; gap: 7px; }
.runs-filters select, .runs-filters input { height: 32px; width: 100%; font-size: 12px; }
.runs-filter-row { display: flex; gap: 7px; }
.runs-filter-row input { flex: 1; }
.runs-list { flex: 1; overflow-y: auto; padding: 8px; display: flex; flex-direction: column; gap: 6px; }
.run-item { text-align: left; border: 1px solid transparent; background: #f8fafc; border-radius: 9px; padding: 9px 11px; cursor: pointer; display: flex; flex-direction: column; gap: 3px; transition: .12s; }
.run-item:hover { background: #f1f5f9; }
.run-item.selected { background: #eff6ff; border-color: #93c5fd; }
.run-item-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.run-agent { font-size: 13px; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.run-item-sub { font-size: 11px; color: var(--muted); }
.run-item-id { font-size: 10px; color: #94a3b8; font-family: ui-monospace, Menlo, Consolas, monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.run-detail { min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 16px; }
.run-detail > * { flex-shrink: 0; }
.run-detail .panel { padding: 16px 18px; }
.rd-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.rd-cell { background: #f8fafc; border: 1px solid #eef2f7; border-radius: 9px; padding: 8px 11px; display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.rd-cell.wide { grid-column: 1 / -1; }
.rd-cell > span { font-size: 11px; color: var(--muted); }
.rd-cell > b { font-size: 13px; font-weight: 700; word-break: break-all; }
.rd-error { margin-top: 12px; background: #fef2f2; border: 1px solid #fecaca; color: #991b1b; border-radius: 8px; padding: 9px 11px; font-size: 12px; }
.rd-error.sm { margin-top: 6px; padding: 6px 9px; font-size: 11px; }
.rd-answer { font-size: 13px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; color: #334155; }

.rd-steps { display: flex; flex-direction: column; }
.rd-step { display: grid; grid-template-columns: 24px 1fr; gap: 8px; position: relative; padding-bottom: 12px; }
.rd-step:not(:last-child)::before { content: ""; position: absolute; left: 11px; top: 20px; bottom: 0; width: 2px; background: #e2e8f0; }
.rd-step-rail span { width: 14px; height: 14px; border-radius: 50%; background: #cbd5e1; display: block; margin: 4px auto 0; box-shadow: 0 0 0 3px #fff; position: relative; z-index: 1; }
.rd-step.badge-green .rd-step-rail span { background: var(--green); }
.rd-step.badge-red .rd-step-rail span { background: var(--red); }
.rd-step.badge-amber .rd-step-rail span { background: var(--yellow); }
.rd-step-card { border: 1px solid #eef2f7; border-radius: 10px; padding: 10px 12px; background: #fff; box-shadow: var(--shadow-sm); }
.rd-step-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.rd-step-head strong { font-size: 13px; }
.rd-step-id { font-size: 11px; color: var(--muted); font-family: ui-monospace, Menlo, Consolas, monospace; }
.rd-step-dur { font-size: 11px; color: var(--muted); margin-left: auto; }
.rd-step-card details summary { font-size: 12px; color: var(--muted); cursor: pointer; margin-top: 8px; }
.event-list { display: grid; gap: 8px; }
.event-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px; border: 1px solid #eef2f7; border-radius: 10px; padding: 10px 12px; background: #fff; }
.event-main { min-width: 0; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.event-main strong { font-size: 12px; font-family: ui-monospace, Menlo, Consolas, monospace; }
.event-main span { font-size: 12px; color: #475569; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 360px; }
.event-main small { font-size: 11px; color: var(--muted); margin-left: auto; }
.event-row details { grid-column: 1 / -1; }
.event-row details summary { font-size: 12px; color: var(--muted); cursor: pointer; }

@media (max-width: 980px) {
  .runs-page { overflow: visible; }
  .runs-body { grid-template-columns: 1fr; overflow: visible; }
  .runs-list-pane { max-height: 360px; }
}
</style>
