<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { currentDomain, currentOrgId, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { notifyError, notifySuccess } from '../stores/notify'

const tools = ref<JsonMap[]>([])
const audit = ref<JsonMap[]>([])
const auditOpen = ref(false)
const domain = ref(currentDomain(''))
const activeDomain = ref('')
const activeCategory = ref('')
const keyword = ref('')
const output = ref<JsonMap | null>(null)
const error = ref('')
const createKind = ref<'http' | 'db-query' | 'sandbox-script'>('http')
const createOpen = ref(false)
function openCreate() { createKind.value = 'http'; createOpen.value = true }
const form = reactive({
  tool_id: '',
  tool_name: '',
  display_name: '',
  description: '',
  url: '',
  method: 'POST',
  query_template: 'select 1',
  max_rows: 50,
  runner_url: '',
  script: 'print("hello")',
  timeout_ms: 5000,
})
const policy = reactive({ agent_id: 'platform_knowledge_agent', action: 'allow' })
const riskChecked = ref<Record<string, boolean>>({})

function labelDomain(d: string) {
  if (d === 'platform') return '平台'
  if (d === 'global') return '全局'
  return d
}
function labelCategory(c: string) {
  return ({ flow: 'Flow Tool', standard: '标准工具', package: '平台工具包', domain: 'Domain 工具' } as Record<string, string>)[c] || c
}
function isRisky(t: JsonMap): boolean {
  if (String(t.source_type || '').toLowerCase() === 'mcp') return false
  const risk = String(t.risk_level || '').toLowerCase()
  const effect = String(t.side_effect || '').toLowerCase()
  return ['high', 'critical'].includes(risk) || ['write', 'code_exec'].includes(effect)
}
function isRiskApproved(t: JsonMap): boolean {
  const override = (t.config_override as JsonMap) || {}
  return Boolean(override.risk_approved || (override.risk_approval as JsonMap)?.approved)
}

const visible = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return tools.value.filter((t) => {
    if (activeDomain.value && String(t.domain || 'platform') !== activeDomain.value) return false
    if (activeCategory.value && String(t.category || '') !== activeCategory.value) return false
    if (!q) return true
    const haystack = [t.tool_id, t.name, t.display_name, t.description, t.domain, t.source_label, ...(t.parameter_names || []), ...(t.required || [])]
    return haystack.join(' ').toLowerCase().includes(q)
  })
})
const domainCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const t of tools.value) {
    const d = String(t.domain || 'platform')
    counts.set(d, (counts.get(d) || 0) + 1)
  }
  return Array.from(counts.entries()).sort(([a], [b]) => (a === 'platform' ? -1 : b === 'platform' ? 1 : a.localeCompare(b)))
})
const categoryCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const t of tools.value) {
    const c = String(t.category || 'unknown')
    counts.set(c, (counts.get(c) || 0) + 1)
  }
  return Array.from(counts.entries()).sort(([a], [b]) => a.localeCompare(b))
})
const errorCount = computed(() => tools.value.filter((t) => t.load_error).length)

function headers(json = false) {
  return makeHeaders(json, currentOrgId())
}
async function loadTools() {
  const qs = domain.value ? `?domain=${encodeURIComponent(domain.value)}` : ''
  const data = await readJson<JsonMap>(await fetch(`/platform/frontend/tools${qs}`, { headers: headers(false) }))
  tools.value = data.items || data.tools || []
}

async function setBinding(t: JsonMap, status: string, visibility?: string) {
  const key = String(t.tool_id || t.name)
  try {
    const body: JsonMap = {
      domain: t.domain && t.domain !== 'platform' ? t.domain : null,
      binding_status: status,
      binding_visibility: visibility || t.binding_visibility || 'discoverable',
    }
    if (isRisky(t)) body.config_override = { risk_approved: riskChecked.value[key] ?? isRiskApproved(t) }
    await readJson(await fetch(`/platform/frontend/tools/bindings/${encodeURIComponent(key)}`, { method: 'PUT', headers: headers(true), body: JSON.stringify(body) }))
    await loadTools()
    notifySuccess(`${key} 已${status === 'disabled' ? '停用' : '启用'}`)
  } catch (err) {
    notifyError(err)
  }
}
async function savePolicy(t: JsonMap) {
  if (!policy.agent_id.trim()) {
    notifyError('请输入 agent_id')
    return
  }
  try {
    const body: JsonMap = { domain: t.domain && t.domain !== 'platform' ? t.domain : null, action: policy.action }
    await readJson(await fetch(`/platform/frontend/tools/agents/${encodeURIComponent(policy.agent_id)}/policies/${encodeURIComponent(String(t.tool_id || t.name))}`, { method: 'PUT', headers: headers(true), body: JSON.stringify(body) }))
    notifySuccess(`已为 ${policy.agent_id} 保存 ${t.tool_id || t.name} 的策略 (${policy.action})`)
  } catch (err) {
    notifyError(err)
  }
}
async function createTool() {
  const tool_id = form.tool_id.trim()
  if (!tool_id) {
    notifyError('请填写 Tool ID')
    return
  }
  try {
    const base: JsonMap = {
      tool_id,
      tool_name: form.tool_name.trim() || tool_id,
      display_name: form.display_name || form.tool_name || tool_id,
      description: form.description,
      domain: domain.value && domain.value !== 'platform' ? domain.value : null,
    }
    let body: JsonMap
    let url: string
    if (createKind.value === 'http') {
      if (!form.url.trim()) { notifyError('请填写 URL'); return }
      body = { ...base, url: form.url.trim(), method: form.method, timeout_ms: form.timeout_ms }
      url = '/platform/frontend/tools/http'
    } else if (createKind.value === 'db-query') {
      if (!form.query_template.trim()) { notifyError('请填写 SQL 模板'); return }
      body = { ...base, query_template: form.query_template.trim(), max_rows: form.max_rows, timeout_ms: form.timeout_ms, parameter_schema: { type: 'object', properties: {} } }
      url = '/platform/frontend/tools/db-query'
    } else {
      if (!form.runner_url.trim() || !form.script.trim()) { notifyError('请填写 Runner URL 和脚本'); return }
      body = { ...base, runner_url: form.runner_url.trim(), script: form.script, timeout_ms: form.timeout_ms, parameter_schema: { type: 'object', properties: {} } }
      url = '/platform/frontend/tools/sandbox-script'
    }
    output.value = await readJson(await fetch(url, { method: 'POST', headers: headers(true), body: JSON.stringify(body) }))
    form.tool_id = ''
    form.tool_name = ''
    form.display_name = ''
    form.description = ''
    createOpen.value = false
    await loadTools()
    notifySuccess(`工具 ${tool_id} 已创建`)
  } catch (err) {
    notifyError(err)
  }
}
async function loadAudit() {
  auditOpen.value = !auditOpen.value
  if (!auditOpen.value) return
  try {
    const data = await readJson<JsonMap>(await fetch('/platform/frontend/tools/audit?limit=50', { headers: headers(false) }))
    audit.value = data.items || []
  } catch (err) {
    notifyError(err)
  }
}
function outputHeadline(d: JsonMap) {
  return d.message || d.detail || (d.tool_id ? `工具已创建: ${d.tool_id}` : '操作完成，详见下方响应。')
}
onMounted(async () => {
  try {
    await loadTools()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
})
</script>

<template>
  <section class="content">
    <p v-if="error" class="error-line">{{ error }}</p>
    <div class="stats">
      <div class="stat"><div class="stat-label">Tools</div><div class="stat-val">{{ tools.length }}</div></div>
      <div class="stat"><div class="stat-label">Domain</div><div class="stat-val">{{ domainCounts.length }}</div></div>
      <div class="stat"><div class="stat-label">Category</div><div class="stat-val">{{ categoryCounts.length }}</div></div>
      <div class="stat"><div class="stat-label">加载异常</div><div class="stat-val" :class="errorCount ? 'err' : ''">{{ errorCount }}</div></div>
    </div>
    <section class="panel">
      <div class="section-head"><div><div class="section-title">Tools 目录</div><div class="section-sub">查看、启停绑定、保存 Agent policy。</div></div><div class="actions"><button class="btn btn-primary btn-sm" @click="openCreate">+ 创建工具</button><button class="btn btn-ghost btn-sm" @click="loadAudit">{{ auditOpen ? '隐藏审计' : '审计' }}</button><button class="btn btn-ghost btn-sm" @click="loadTools">刷新</button></div></div>
      <div class="chip-list">
        <button class="filter-chip" :class="{ active: !activeDomain }" @click="activeDomain = ''">全部 <span class="count">{{ tools.length }}</span></button>
        <button v-for="[d, c] in domainCounts" :key="d" class="filter-chip" :class="{ active: activeDomain === d }" @click="activeDomain = activeDomain === d ? '' : d">{{ labelDomain(d) }} <span class="count">{{ c }}</span></button>
      </div>
      <div class="chip-list">
        <button v-for="[c, n] in categoryCounts" :key="c" class="filter-chip" :class="{ active: activeCategory === c }" @click="activeCategory = activeCategory === c ? '' : c">{{ labelCategory(c) }} <span class="count">{{ n }}</span></button>
      </div>
      <div class="toolbar">
        <div class="field wide"><label>搜索</label><input v-model="keyword" placeholder="搜索 tool/name/description/参数" /></div>
        <div class="field"><label>Agent Policy 目标</label><input v-model="policy.agent_id" placeholder="agent_id" /></div>
        <div class="field"><label>Policy Action</label><select v-model="policy.action"><option value="allow">allow</option><option value="deny">deny</option><option value="planner_visible">planner_visible</option><option value="default_active">default_active</option></select></div>
        <div class="field"><label>已显示</label><div class="kpi-sub">{{ visible.length }} / {{ tools.length }}</div></div>
      </div>
      <div v-if="auditOpen" class="table-wrap">
        <table>
          <thead><tr><th>事件</th><th>目标</th><th>Domain / Agent</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="a in audit" :key="a.id as string"><td><strong>{{ a.event_type || a.action }}</strong></td><td>{{ a.target_id || a.tool_id || a.name }}</td><td>{{ a.domain || 'platform' }} {{ a.agent_id || '' }}</td><td>{{ a.created_at }}</td></tr>
            <tr v-if="!audit.length"><td colspan="4" class="empty">暂无审计记录</td></tr>
          </tbody>
        </table>
      </div>
      <div class="tool-grid">
        <article v-for="t in visible" :key="(t.tool_id || t.name) as string" class="tool-card" :class="{ error: t.load_error }">
          <div class="tool-card-head">
            <strong>{{ t.display_name || t.name || t.tool_id }}</strong>
            <span class="tag" :class="(t.domain || 'platform') === 'platform' ? 'platform' : 'domain'">{{ labelDomain(String(t.domain || 'platform')) }}</span>
          </div>
          <p>{{ t.description || '无描述' }}</p>
          <div class="tags">
            <span class="tag">{{ labelCategory(String(t.category || '')) }}</span>
            <span class="tag">{{ t.source_type }}</span>
            <span class="tag">risk: {{ t.risk_level || 'low' }}</span>
            <span class="tag">{{ t.side_effect || 'read_only' }}</span>
            <span class="tag">{{ t.governance_mode || 'runtime_catalog' }}</span>
            <span v-if="t.catalog_status" class="tag">{{ t.catalog_status }}</span>
            <span class="tag">binding: {{ t.binding_status || 'unknown' }}</span>
            <span class="tag">{{ t.binding_visibility || 'unknown' }}</span>
            <span v-if="t.policy_status" class="tag">policy: {{ t.policy_status }}</span>
            <span v-if="t.load_error" class="tag error">加载异常</span>
          </div>
          <div class="params">
            <span>参数</span>
            <template v-if="(t.parameter_names || []).length">
              <span v-for="p in t.parameter_names" :key="p as string" class="param">{{ p }}<template v-if="(t.required || []).includes(p)">*</template></span>
            </template>
            <span v-else class="kpi-sub">无</span>
          </div>
          <div class="memory-context-row"><span>{{ t.tool_id || t.name }}</span><span>{{ t.source_label || '' }}</span></div>
          <div class="actions">
            <label v-if="isRisky(t)" class="kpi-sub" style="display:flex;align-items:center;gap:4px">
              <input type="checkbox" v-model="riskChecked[String(t.tool_id || t.name)]" :checked="isRiskApproved(t)" /> 风险确认
            </label>
            <button class="btn small" @click="setBinding(t, t.binding_status === 'disabled' ? 'enabled' : 'disabled')">{{ t.binding_status === 'disabled' ? '启用' : '停用' }}</button>
            <select class="select-sm" :value="t.binding_visibility || 'discoverable'" @change="setBinding(t, String(t.binding_status || 'enabled'), ($event.target as HTMLSelectElement).value)">
              <option value="discoverable">planner 可见</option>
              <option value="hidden">隐藏可调用</option>
            </select>
            <button class="btn small" @click="savePolicy(t)">保存策略</button>
          </div>
        </article>
        <div v-if="!visible.length" class="empty">暂无工具</div>
      </div>
    </section>
    <section v-if="output" class="panel"><div class="section-title">结果</div><div class="result-summary"><p>{{ outputHeadline(output) }}</p></div><details><summary>查看完整响应</summary><pre class="json-box">{{ JSON.stringify(output, null, 2) }}</pre></details></section>

    <!-- ═══ 创建工具弹窗 ═══ -->
    <div v-if="createOpen" class="modal-backdrop" @click.self="createOpen = false">
      <div class="modal">
        <div class="modal-title">创建工具</div>
        <div class="form-group"><label>类型</label><select v-model="createKind"><option value="http">HTTP Tool</option><option value="db-query">DB Query Tool</option><option value="sandbox-script">Sandbox Script Tool</option></select></div>
        <div class="form-row">
          <div class="form-group"><label>Tool ID *</label><input v-model="form.tool_id" placeholder="weather_lookup" /></div>
          <div class="form-group"><label>Domain</label><input v-model="domain" placeholder="platform / training" @change="loadTools" /></div>
        </div>
        <div class="form-row">
          <div class="form-group"><label>Tool Name</label><input v-model="form.tool_name" placeholder="同 Tool ID" /></div>
          <div class="form-group"><label>显示名</label><input v-model="form.display_name" /></div>
        </div>
        <div class="form-group"><label>描述</label><input v-model="form.description" /></div>

        <template v-if="createKind === 'http'">
          <div class="form-row">
            <div class="form-group" style="flex:2"><label>URL *</label><input v-model="form.url" placeholder="https://api.example.com/run" /></div>
            <div class="form-group"><label>Method</label><select v-model="form.method"><option>GET</option><option>POST</option><option>PUT</option><option>DELETE</option></select></div>
          </div>
        </template>
        <template v-else-if="createKind === 'db-query'">
          <div class="form-group"><label>SQL 模板 *</label><textarea v-model="form.query_template" rows="3" placeholder="SELECT id,name FROM equipment WHERE name=:name" /></div>
          <div class="form-group"><label>最大行数</label><input v-model.number="form.max_rows" type="number" min="1" max="500" /></div>
        </template>
        <template v-else>
          <div class="form-group"><label>Runner URL *</label><input v-model="form.runner_url" placeholder="http://sandbox-runner:8080/run" /></div>
          <div class="form-group"><label>脚本 *</label><textarea v-model="form.script" rows="3" /></div>
        </template>
        <div class="form-group"><label>Timeout (ms)</label><input v-model.number="form.timeout_ms" type="number" min="100" step="100" /></div>

        <div class="modal-actions">
          <button class="btn btn-ghost" @click="createOpen = false">取消</button>
          <button class="btn btn-primary" @click="createTool">创建</button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.modal-backdrop { position: fixed; inset: 0; background: rgba(15, 23, 42, .45); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 28px; }
.modal { background: #fff; border-radius: 16px; padding: 24px; width: 560px; max-width: 96vw; max-height: 90vh; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; box-shadow: var(--shadow-lg); }
.modal-title { font-size: 16px; font-weight: 700; }
.modal .form-row { display: flex; gap: 12px; flex-wrap: wrap; }
.modal .form-group { display: flex; flex-direction: column; gap: 5px; flex: 1; min-width: 130px; }
.modal .form-group label { font-size: 12px; font-weight: 600; color: var(--muted); }
.modal-actions { display: flex; gap: 8px; justify-content: flex-end; border-top: 1px solid var(--border); padding-top: 14px; }
</style>
