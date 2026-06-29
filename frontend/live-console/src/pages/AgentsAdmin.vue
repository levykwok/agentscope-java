<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { currentDomain, currentOrgId, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { notifyError, notifySuccess } from '../stores/notify'

const CAP_LABELS: Record<string, string> = {
  supports_tool_calling: '工具调用',
  supports_skill_tools: 'Skill 工具',
  supports_skill_context: 'Skill 上下文',
  supports_retrieval: '检索增强',
  supports_memory: '对话记忆',
}
const TRAIT_LABELS: Record<string, string> = {
  has_react_loop: 'ReAct 循环',
  has_planner: 'Planner',
  has_completion_gate: 'CompletionGate',
  has_step_scoping: 'Step Scope',
}

const agents = ref<JsonMap[]>([])
const flows = ref<JsonMap[]>([])
const skills = ref<JsonMap[]>([])
const tools = ref<JsonMap[]>([])
const toolSourceFilter = ref('')
const modelChoices = ref<JsonMap>({ fixed_slots: [], aliases: [] })
const selectedId = ref('')
const spec = ref<JsonMap | null>(null)
const output = ref<JsonMap | null>(null)
const domainFilter = ref(currentDomain(''))
const keyword = ref('')
const domainOptions = ref<JsonMap[]>([{ domain: '', label: '全部业务域' }, { domain: 'platform', label: '平台' }])
async function loadDomains() {
  try {
    const status = await readJson<JsonMap>(await fetch('/platform/frontend/infra/status', { headers: makeHeaders(false, 'platform') }))
    const raw = status.domains && typeof status.domains === 'object' ? status.domains as JsonMap : {}
    const rows = Object.entries(raw).map(([domain, snap]) => ({ domain, label: String((snap as JsonMap)?.display_name || domain) }))
    domainOptions.value = [{ domain: '', label: '全部业务域' }, { domain: 'platform', label: '平台' }, ...rows.filter((r) => r.domain !== 'platform')]
  } catch { /* ignore */ }
}
const step = ref(0)
const running = ref(false)
const configOpen = ref(false)
function openConfig() { configOpen.value = true; step.value = 0 }
const tryInputType = ref('chat')
const tryFlowKey = ref('')
const tryQuery = ref('')

const form = reactive({
  agent_id: '',
  display_name: '',
  description: '',
  domain: domainFilter.value || 'platform',
  enabled: true,
  role: '',
  planner_rules: '',
  require_structured_plan: true,
  included_skills: [] as string[],
  included_tools: [] as string[],
  router_rules: [] as JsonMap[],
  orchestration_mode: 'SINGLE',
  orchestration_routes: [] as JsonMap[],
  workflow_steps: [] as JsonMap[],
  subagents: [] as JsonMap[],
  model_policy: {} as JsonMap,
  flow_bindings: [] as JsonMap[],
})

function toolKey(t: JsonMap) { return String(t.name || t.tool_id || '') }
function toolSource(t: JsonMap): string {
  if (t.runtime_name || String(t.source_type || '').includes('mcp') || String(t.category || '').includes('mcp')) return 'mcp'
  if (String(t.category || '') === 'flow') return 'flow'
  if (String(t.source_type || '') === 'domain_package' || (t.domain && String(t.domain) !== 'platform')) return 'domain'
  return 'platform'
}
const TOOL_SRC_LABEL: Record<string, string> = { platform: '平台', domain: '业务域', flow: 'Flow', mcp: 'MCP' }
// MCP tool ids look like "mcp:<server_id>:<tool_name>"; fall back to runtime_config.
function mcpServerIdOfTool(t: JsonMap): string {
  const m = String(t.tool_id || '').match(/^mcp:(\d+):/)
  if (m) return m[1]
  const rc = (t.runtime_config as JsonMap) || {}
  return rc.mcp_server_id != null ? String(rc.mcp_server_id) : ''
}
// Candidacy: an MCP tool is only selectable once its server is bound to this agent.
const candidateTools = computed(() => tools.value.filter((t) => {
  if (toolSource(t) !== 'mcp') return true
  const sid = mcpServerIdOfTool(t)
  return sid ? !!mcpBindings.value[sid] : false
}))
const toolSourceCounts = computed(() => {
  const m = new Map<string, number>()
  for (const t of candidateTools.value) m.set(toolSource(t), (m.get(toolSource(t)) || 0) + 1)
  return Array.from(m.entries()).sort(([a], [b]) => a.localeCompare(b))
})
const visibleTools = computed(() => toolSourceFilter.value ? candidateTools.value.filter((t) => toolSource(t) === toolSourceFilter.value) : candidateTools.value)
function isToolSelected(t: JsonMap) { return form.included_tools.includes(toolKey(t)) }
function toggleTool(t: JsonMap) {
  const k = toolKey(t)
  const i = form.included_tools.indexOf(k)
  if (i >= 0) form.included_tools.splice(i, 1)
  else form.included_tools.push(k)
}

// ── MCP server bindings (agent binds MCP server first, then selects its tools) ──
const mcpServers = ref<JsonMap[]>([])
const mcpBindings = ref<Record<string, JsonMap | null>>({})  // server_id -> binding row for current agent (null = not bound)
const mcpBusy = ref<Record<string, boolean>>({})
function isMcpBound(s: JsonMap) { return !!mcpBindings.value[String(s.id)] }

async function loadMcpServers() {
  try {
    const d = await api('/platform/frontend/mcp')
    mcpServers.value = (d.mcp_servers || d.items || []) as JsonMap[]
  } catch { mcpServers.value = [] }
}

async function loadMcpBindings() {
  const agentId = String(form.agent_id || '').trim()
  const map: Record<string, JsonMap | null> = {}
  if (agentId && mcpServers.value.length) {
    await Promise.all(mcpServers.value.map(async (s) => {
      try {
        const d = await api(`/platform/frontend/mcp/${s.id}/bindings`)
        const list = (d.bindings || d.items || []) as JsonMap[]
        map[String(s.id)] = list.find((b) => String(b.agent_id) === agentId) || null
      } catch { map[String(s.id)] = null }
    }))
  }
  mcpBindings.value = map
}

async function toggleMcpBinding(s: JsonMap) {
  const agentId = String(form.agent_id || '').trim()
  if (!agentId || !selectedId.value) { notifyError('请先创建并选择 Agent，再绑定 MCP 服务器'); return }
  const sid = String(s.id)
  mcpBusy.value = { ...mcpBusy.value, [sid]: true }
  try {
    const existing = mcpBindings.value[sid]
    if (existing) {
      await api(`/platform/frontend/mcp/bindings/${existing.id}`, { method: 'DELETE' })
      mcpBindings.value = { ...mcpBindings.value, [sid]: null }
      notifySuccess(`已解绑 ${s.name}`)
    } else {
      const d = await api('/platform/frontend/mcp/bindings', { method: 'POST', body: JSON.stringify({ agent_id: agentId, mcp_server_id: Number(s.id), enabled: true }) })
      mcpBindings.value = { ...mcpBindings.value, [sid]: (d.binding || { id: 0, agent_id: agentId, mcp_server_id: s.id, enabled: true }) as JsonMap }
      notifySuccess(`已绑定 ${s.name}，其工具已加入候选`)
      await loadDeps()  // binding re-syncs the tool catalog server-side; refresh the picker
    }
  } catch (e) {
    notifyError(`绑定操作失败：${e}`)
  } finally {
    mcpBusy.value = { ...mcpBusy.value, [sid]: false }
  }
}

const selected = computed(() => agents.value.find((a) => a.agent_id === selectedId.value) || null)
const visible = computed(() => agents.value.filter((a) => {
  if (domainFilter.value && String(a.domain || '') !== domainFilter.value) return false
  const q = keyword.value.trim().toLowerCase()
  if (!q) return true
  return [a.display_name, a.name, a.agent_id, a.description].join(' ').toLowerCase().includes(q)
}))
function flowLabel(flowId: string) { const f = flowById(flowId); return f?.display_name || f?.name || flowId }
const flowBindingList = computed(() => (form.flow_bindings as JsonMap[]).filter((b) => b.flow_id).map((b) => ({ key: String(b.key || ''), flow_id: String(b.flow_id || ''), name: flowLabel(String(b.flow_id || '')) })))
const modelPolicyList = computed(() => Object.entries(form.model_policy)
  .filter(([slot]) => slot !== 'nodes' && slot !== 'slots')
  .map(([slot, model]) => ({ slot, model: String(model || '') || '跟随默认绑定' })))
const stepLabels = ['基本信息', '编排', '技能范围', '工具 / MCP', '模型策略', 'Prompt']
const isBuiltin = computed(() => String(selected.value?.source || 'builtin') === 'builtin')
const flowKeyOptions = computed(() => form.flow_bindings.map((b) => String(b.key || '')).filter(Boolean))
const fixedSlotKeys = computed(() => new Set((modelChoices.value.fixed_slots as JsonMap[] || []).map((s) => String(s.slot_key || ''))))
const aliasKeys = computed(() => new Set((modelChoices.value.aliases as JsonMap[] || []).map((a) => String(a.alias_name || ''))))

function headers(json = false) { return makeHeaders(json, currentOrgId()) }
async function api(path: string, opts: RequestInit = {}) { return await readJson<JsonMap>(await fetch(path, { headers: headers(Boolean(opts.body)), ...opts })) }
function domainQuery() { return domainFilter.value ? `?domain=${encodeURIComponent(domainFilter.value)}` : '' }

function flowById(flowId: string): JsonMap | null { return flows.value.find((f) => String(f.flow_id || f.id) === flowId) || null }
function flowCaps(flowId: string): JsonMap { return (flowById(flowId)?.capabilities as JsonMap) || {} }
function capBadges(flowId: string): Array<{ label: string; on: boolean; trait: boolean }> {
  const caps = flowCaps(flowId)
  if (!Object.keys(caps).length) return []
  const out: Array<{ label: string; on: boolean; trait: boolean }> = []
  for (const [k, label] of Object.entries(CAP_LABELS)) out.push({ label, on: caps[k] === true, trait: false })
  for (const [k, label] of Object.entries(TRAIT_LABELS)) out.push({ label, on: caps[k] === true, trait: true })
  return out
}
function policyLabel(key: string, value: string): string {
  if (value) return `→ ${value}`
  if (fixedSlotKeys.value.has(key)) return '→ 跟随默认绑定'
  if (aliasKeys.value.has(key)) return '→ 自定义插槽'
  return '→ 默认链路'
}

async function loadModelChoices(domain: string) {
  try {
    const params = new URLSearchParams({ domain: domain || 'platform', org_id: currentOrgId() })
    modelChoices.value = await api(`/platform/frontend/models/aliases/available?${params}`)
  } catch {
    modelChoices.value = { fixed_slots: [], aliases: [] }
  }
}

async function loadDeps() {
  const [f, s, t] = await Promise.all([
    api(`/platform/frontend/flows${domainQuery()}`),
    api(`/platform/frontend/skills${domainQuery()}`),
    api(`/platform/frontend/tools${domainQuery()}`),
  ])
  flows.value = (f.items || f.flows || []) as JsonMap[]
  skills.value = (Array.isArray(s) ? s : s.items || s.skills || []) as JsonMap[]
  tools.value = (Array.isArray(t) ? t : t.items || t.tools || []) as JsonMap[]
  await loadMcpServers()
  await loadMcpBindings()
}

async function loadAgents() {
  const d = await api(`/platform/frontend/agents${domainQuery()}`)
  const items = (d.items || d.agents || []) as JsonMap[]
  for (const a of items) {
    if (String(a.source || 'builtin') !== 'builtin' && !a.skill_scope) {
      try {
        const s = await api(`/platform/frontend/agents/${encodeURIComponent(String(a.agent_id))}/spec`)
        const cfg = (s.config_json || {}) as JsonMap
        Object.assign(a, cfg)
        const wf = (s.workflow_json || {}) as JsonMap
        const wfFlows = (wf.flows || {}) as JsonMap
        if (Object.keys(wfFlows).length) {
          const fb: JsonMap = {}
          for (const [k, v] of Object.entries(wfFlows)) fb[k] = typeof v === 'string' ? v : ((v as JsonMap)?.flow_id || v)
          a.flow_bindings = fb
        }
      } catch { /* ignore merge failure */ }
    }
  }
  agents.value = items
  if (!selectedId.value && agents.value[0]) await selectAgent(String(agents.value[0].agent_id))
}

async function selectAgent(id: string) {
  selectedId.value = id
  step.value = 0
  output.value = null
  try {
    const d = await api(`/platform/frontend/agents/${encodeURIComponent(id)}/spec`)
    spec.value = d
    const cfg = (d.config_json || {}) as JsonMap
    const wf = (d.workflow_json || {}) as JsonMap
    const a = agents.value.find((x) => x.agent_id === id) || {}
    const wfFlows = (wf.flows || {}) as JsonMap
    const orchestration = (wf.orchestration || {}) as JsonMap
    const flowBindings = Object.entries(wfFlows).map(([key, v]) => ({ key, flow_id: typeof v === 'string' ? v : ((v as JsonMap)?.flow_id || '') }))
    const pp = (cfg.prompt_policy || {}) as JsonMap
    Object.assign(form, {
      agent_id: id,
      display_name: cfg.name || a.display_name || a.name || id,
      description: cfg.description || a.description || '',
      domain: cfg.domain || a.domain || domainFilter.value || 'platform',
      enabled: a.enabled !== false,
      role: pp.role || '',
      planner_rules: ((pp.planner_rules as string[]) || []).join('\n'),
      require_structured_plan: pp.require_structured_plan !== false,
      included_skills: ((cfg.skill_scope as JsonMap)?.include as string[]) || [],
      included_tools: ((cfg.tool_scope as JsonMap)?.include as string[]) || [],
      router_rules: (((cfg.router_policy as JsonMap)?.rules as JsonMap[]) || []).map((r) => ({
        intent: r.intent || '',
        keywords: Array.isArray(r.keywords) ? (r.keywords as string[]).join(', ') : (r.keywords || ''),
        flow_key: r.flow_key || '',
      })),
      orchestration_mode: orchestration.mode || 'SINGLE',
      orchestration_routes: ((orchestration.routes as JsonMap[]) || []).map((r) => ({
        ruleId: r.ruleId || r.rule_id || '',
        targetAgentId: r.targetAgentId || r.target_agent_id || r.agent_id || '',
        contains: r.contains || '',
      })),
      workflow_steps: ((orchestration.workflow as JsonMap[]) || []).map((s) => ({
        stepId: s.stepId || s.step_id || '',
        agentId: s.agentId || s.agent_id || '',
        instruction: s.instruction || '',
      })),
      subagents: ((orchestration.subagents as JsonMap[]) || []).map((s) => ({
        bindingId: s.bindingId || s.binding_id || '',
        targetAgentId: s.targetAgentId || s.target_agent_id || s.agent_id || '',
        role: s.role || '',
        description: s.description || '',
        exposeToUser: s.exposeToUser ?? s.expose_to_user ?? true,
      })),
      model_policy: { ...((cfg.model_policy as JsonMap) || {}) },
      flow_bindings: flowBindings,
    })
    tryFlowKey.value = ''
    await loadModelChoices(String(form.domain))
    await loadMcpBindings()
  } catch {
    spec.value = null
  }
}

function newAgent() {
  Object.assign(form, {
    agent_id: '', display_name: '', description: '', domain: domainFilter.value || 'platform', enabled: true,
    role: '', planner_rules: '', require_structured_plan: true,
    included_skills: [], included_tools: [], router_rules: [],
    orchestration_mode: 'SINGLE', orchestration_routes: [], workflow_steps: [], subagents: [],
    model_policy: {}, flow_bindings: [{ key: 'default', flow_id: '' }],
  })
  spec.value = null; selectedId.value = ''; step.value = 0; output.value = null
  mcpBindings.value = {}
  configOpen.value = true
  loadModelChoices(String(form.domain))
}

function toggleSkill(id: string) {
  const i = form.included_skills.indexOf(id)
  if (i >= 0) form.included_skills.splice(i, 1)
  else form.included_skills.push(id)
}
function addFlowBinding() { form.flow_bindings.push({ key: '', flow_id: '' }) }
function removeFlowBinding(i: number) { form.flow_bindings.splice(i, 1) }
function addRouterRule() { form.router_rules.push({ intent: '', keywords: '', flow_key: form.flow_bindings[0]?.key || '' }) }
function removeRouterRule(i: number) { form.router_rules.splice(i, 1) }
function addOrchestrationRoute() { form.orchestration_routes.push({ ruleId: `route_${form.orchestration_routes.length + 1}`, targetAgentId: '', contains: '' }) }
function removeOrchestrationRoute(i: number) { form.orchestration_routes.splice(i, 1) }
function addWorkflowStep() { form.workflow_steps.push({ stepId: `step_${form.workflow_steps.length + 1}`, agentId: '', instruction: '' }) }
function removeWorkflowStep(i: number) { form.workflow_steps.splice(i, 1) }
function addSubagent() { form.subagents.push({ bindingId: `subagent_${form.subagents.length + 1}`, targetAgentId: '', role: '', description: '', exposeToUser: true }) }
function removeSubagent(i: number) { form.subagents.splice(i, 1) }
function toggleModelPolicy(key: string) {
  if (key in form.model_policy) delete form.model_policy[key]
  else form.model_policy[key] = ''
}
function setModelPolicySlot(key: string, selectedSlot: string) { form.model_policy[key] = selectedSlot }
function customSlotsFor(targetSlotKey: string): JsonMap[] {
  const key = String(targetSlotKey || '').trim()
  return (modelChoices.value.aliases as JsonMap[] || []).filter((a) => String(a.target_slot_key || 'qa') === key)
}
function customSlotSelected(a: JsonMap): boolean {
  const alias = String(a.alias_name || '').trim()
  const target = String(a.target_slot_key || 'qa')
  return !!alias && (String(form.model_policy[target] || '') === alias || alias in form.model_policy)
}
function toggleCustomSlot(a: JsonMap) {
  const alias = String(a.alias_name || '').trim()
  const target = String(a.target_slot_key || 'qa')
  if (!alias) return
  if (customSlotSelected(a)) {
    if (String(form.model_policy[target] || '') === alias) delete form.model_policy[target]
    delete form.model_policy[alias]
  } else {
    form.model_policy[target] = alias
    delete form.model_policy[alias]
  }
}
const boundFlowNodeSlots = computed(() => {
  const out: JsonMap[] = []
  for (const b of form.flow_bindings as JsonMap[]) {
    const flowKey = String(b.key || '').trim()
    const flowId = String(b.flow_id || '').trim()
    if (!flowKey || !flowId) continue
    const flow = flowById(flowId)
    for (const n of ((flow?.nodes as JsonMap[]) || [])) {
      const nodeId = String(n.node_id || '').trim()
      if (!nodeId) continue
      const modelSlots = Array.isArray(n.model_slots) ? n.model_slots.map((s) => String(s || '').trim()).filter(Boolean) : []
      for (const slotKey of Array.from(new Set(modelSlots))) {
        out.push({
          ...n,
          flow_key: flowKey,
          flow_id: flowId,
          policy_key: `${flowKey}.${nodeId}`,
          slot_key: slotKey,
        })
      }
    }
  }
  return out
})
function nodePolicies(): JsonMap {
  if (!form.model_policy.nodes || typeof form.model_policy.nodes !== 'object') form.model_policy.nodes = {}
  return form.model_policy.nodes as JsonMap
}
function nodePolicyEntry(policyKey: string): JsonMap {
  const nodes = nodePolicies()
  const entry = nodes[policyKey]
  return entry && typeof entry === 'object' ? entry as JsonMap : {}
}
function normalizedNodePolicyEntry(entry: JsonMap): JsonMap {
  const out: JsonMap = {}
  for (const [slot, model] of Object.entries(entry)) {
    if (String(model || '').trim()) out[slot] = model
  }
  return out
}
function nodePolicySlot(policyKey: string, slotKey: string): string {
  const entry = normalizedNodePolicyEntry(nodePolicyEntry(policyKey))
  return String(entry[String(slotKey || '')] || '')
}
function setNodePolicy(policyKey: string, slotKey: string, selectedSlot: string) {
  const nodes = nodePolicies()
  const slot = String(slotKey || 'qa')
  const selected = String(selectedSlot || '')
  const current = normalizedNodePolicyEntry(nodePolicyEntry(policyKey))
  if (!selected) {
    delete current[slot]
    if (!Object.keys(current).length) delete nodes[policyKey]
    else nodes[policyKey] = current
  } else {
    nodes[policyKey] = { ...current, [slot]: selected }
  }
  if (!Object.keys(nodes).length) delete form.model_policy.nodes
}
function clearNodePolicy(policyKey: string, slotKey?: string) {
  const nodes = nodePolicies()
  if (slotKey) {
    const current = normalizedNodePolicyEntry(nodePolicyEntry(policyKey))
    delete current[slotKey]
    if (Object.keys(current).length) nodes[policyKey] = current
    else delete nodes[policyKey]
  } else {
    delete nodes[policyKey]
  }
  if (!Object.keys(nodes).length) delete form.model_policy.nodes
}
function slotByKey(slotKey: string): JsonMap {
  return (modelChoices.value.fixed_slots as JsonMap[] || []).find((s) => String(s.slot_key) === slotKey) || {}
}
function slotLabel(slotKey: string): string {
  const slot = slotByKey(slotKey)
  const name = String(slot.display_name || '').trim()
  const key = String(slot.slot_key || slotKey || '').trim()
  return name && key && name !== key ? `${name} (${key})` : (name || key)
}
function modelPolicyPayload(): JsonMap {
  const out: JsonMap = {}
  const aliasKeys = new Set((modelChoices.value.aliases as JsonMap[] || []).map((a) => String(a.alias_name || '').trim()).filter(Boolean))
  for (const [key, value] of Object.entries(form.model_policy)) {
    if (key === 'nodes' || key === 'slots') continue
    if (String(value || '').trim() || aliasKeys.has(key)) out[key] = value
  }
  const nodes = form.model_policy.nodes as JsonMap | undefined
  if (nodes && typeof nodes === 'object') {
    const cleanNodes: JsonMap = {}
    for (const [nodeKey, entry] of Object.entries(nodes)) {
      if (!entry || typeof entry !== 'object') continue
      const cleanEntry = normalizedNodePolicyEntry(entry as JsonMap)
      if (Object.keys(cleanEntry).length) cleanNodes[nodeKey] = cleanEntry
    }
    if (Object.keys(cleanNodes).length) out.nodes = cleanNodes
  }
  return out
}

function prevStep() { step.value = Math.max(0, step.value - 1) }
async function nextOrSubmit() { if (step.value < stepLabels.length - 1) step.value += 1; else await saveAgent() }

async function saveAgent() {
  if (!form.display_name.trim()) { notifyError('请填写显示名'); return }
  const flowBindings: Record<string, string> = {}
  for (const b of form.flow_bindings) {
    const k = String(b.key || '').trim()
    const v = String(b.flow_id || '').trim()
    if (k && v) flowBindings[k] = v
  }
  const mode = String(form.orchestration_mode || 'SINGLE').toUpperCase()
  const orchestration: JsonMap = { mode }
  if (mode === 'ROUTER') {
    const routes = form.orchestration_routes
      .map((r) => ({ ruleId: String(r.ruleId || '').trim(), targetAgentId: String(r.targetAgentId || '').trim(), contains: String(r.contains || '').trim() }))
      .filter((r) => r.ruleId && r.targetAgentId && r.contains)
    if (!routes.length) { notifyError('ROUTER 至少需要一条有效路由'); step.value = 1; return }
    orchestration.routes = routes
  }
  if (mode === 'WORKFLOW') {
    const workflow = form.workflow_steps
      .map((s) => ({ stepId: String(s.stepId || '').trim(), agentId: String(s.agentId || '').trim(), instruction: String(s.instruction || '').trim() }))
      .filter((s) => s.stepId && s.agentId)
    if (!workflow.length) { notifyError('WORKFLOW 至少需要一个有效步骤'); step.value = 1; return }
    orchestration.workflow = workflow
  }
  if (mode === 'SUPERVISOR') {
    const subagents = form.subagents
      .map((s) => ({ bindingId: String(s.bindingId || '').trim(), targetAgentId: String(s.targetAgentId || '').trim(), role: String(s.role || '').trim(), description: String(s.description || '').trim(), exposeToUser: s.exposeToUser !== false, toolRefs: [] }))
      .filter((s) => s.bindingId && s.targetAgentId)
    if (!subagents.length) { notifyError('SUPERVISOR 至少需要一个子 Agent'); step.value = 1; return }
    orchestration.subagents = subagents
  }
  const routerRules: JsonMap[] = []
  const seenFlowKeys = new Set<string>()
  for (const r of form.router_rules) {
    const intent = String(r.intent || '').trim()
    const keywords = String(r.keywords || '').split(/[\s,，;；、\n]+/).map((s) => s.trim()).filter(Boolean)
    const flowKey = String(r.flow_key || '').trim()
    if (!intent && !keywords.length) continue
    if (!flowKey || !flowBindings[flowKey]) { notifyError('路由意图缺少有效 Flow Key'); step.value = 1; return }
    if (seenFlowKeys.has(flowKey)) { notifyError(`路由意图重复：${flowKey}`); step.value = 1; return }
    seenFlowKeys.add(flowKey)
    routerRules.push({ intent, keywords, flow_key: flowKey })
  }
  const routerPolicy = routerRules.length ? { mode: 'hybrid', default_flow: Object.keys(flowBindings)[0] || '', rules: routerRules } : {}
  const skillScope = form.included_skills.length ? { include: form.included_skills } : {}
  const toolScope = form.included_tools.length ? { include: form.included_tools } : {}
  const plannerRules = form.planner_rules.split('\n').map((l) => l.trim()).filter(Boolean)
  const configJson: JsonMap = {
    name: form.display_name,
    description: form.description || null,
    domain: form.domain,
    ...(Object.keys(skillScope).length ? { skill_scope: skillScope } : {}),
    ...(Object.keys(toolScope).length ? { tool_scope: toolScope } : {}),
    ...(Object.keys(modelPolicyPayload()).length ? { model_policy: modelPolicyPayload() } : {}),
    ...(Object.keys(routerPolicy).length ? { router_policy: routerPolicy } : {}),
    prompt_policy: {
      ...(form.role ? { role: form.role } : {}),
      ...(plannerRules.length ? { planner_rules: plannerRules } : {}),
      require_structured_plan: form.require_structured_plan,
    },
    retrieval_policy: { domain: form.domain },
  }
  const workflowJson: JsonMap = {
    default_flow: Object.keys(flowBindings)[0] || '',
    flows: Object.fromEntries(Object.entries(flowBindings).map(([k, v]) => [k, { flow_id: v }])),
    orchestration,
  }
  try {
    let agentId = selectedId.value
    if (!agentId) {
      const created = await api('/platform/frontend/agents', { method: 'POST', body: JSON.stringify({ name: form.display_name, description: form.description || null, workspace_id: form.domain, project_id: 'default' }) })
      agentId = String(created.agent_id || '')
    }
    await api(`/platform/frontend/agents/${encodeURIComponent(agentId)}/spec`, { method: 'PUT', body: JSON.stringify({ config_json: configJson, workflow_json: workflowJson }) })
    await loadAgents()
    await selectAgent(agentId)
    configOpen.value = false
    notifySuccess(`Agent ${agentId} 配置已保存`)
  } catch (err) { notifyError(err) }
}

const testResult = computed(() => {
  const data = output.value
  if (!data) return null
  const result = (data.result || (data.output_ref as JsonMap)?.result || data.output_ref || data) as JsonMap
  const answer = String(result.answer || data.answer || '').trim()
  const citations = Array.isArray(result.citations) ? result.citations : []
  const errMsg = String(result.error || data.detail || data.error || '')
  const status = String(result.status || (data.ok !== false && !errMsg ? 'OK' : 'ERROR'))
  const route = String(result.route || result.effective_mode || '')
  return { answer, citations, errMsg, status, route, ok: data.ok !== false && !errMsg }
})

async function runAgentTest() {
  if (!form.agent_id) { notifyError('请先选择 Agent'); return }
  const query = tryQuery.value.trim()
  if (!query) { notifyError('请输入测试 query'); return }
  running.value = true
  const t0 = Date.now()
  try {
    const body: JsonMap = { agent_id: form.agent_id, input_type: tryInputType.value, payload: { query }, context: {}, artifacts: [] }
    if (tryFlowKey.value) body.flow_name = tryFlowKey.value
    const d = await api('/platform/frontend/agents/runs', { method: 'POST', body: JSON.stringify(body) })
    output.value = { ...d, elapsed_ms: Date.now() - t0, ok: true }
  } catch (err) {
    output.value = { error: String(err), elapsed_ms: Date.now() - t0, ok: false }
  } finally {
    running.value = false
  }
}

onMounted(async () => { await loadDomains(); await loadDeps(); await loadAgents() })
</script>
<template>
<section class="agent-admin">
  <aside class="left-pane">
    <div class="pane-head"><h2>Agent 列表</h2><button class="btn btn-primary btn-sm" @click="newAgent">新建</button></div>
    <div class="list-filters">
      <select v-model="domainFilter" @change="loadAgents"><option v-for="d in domainOptions" :key="d.domain" :value="d.domain">{{ d.label }}</option></select>
      <input v-model="keyword" placeholder="搜索 agent 名称 / ID…" />
    </div>
    <div class="entity-list">
      <div v-for="a in visible" :key="a.agent_id" class="entity-item" :class="{selected:selectedId===a.agent_id}" @click="selectAgent(a.agent_id)">
        <div class="entity-name">{{ a.display_name||a.name||a.agent_id }}</div>
        <span class="entity-type">{{ a.domain||'platform' }}</span>
        <span class="badge" :class="String(a.source||'builtin')==='builtin'?'badge-builtin':'badge-db'">{{ String(a.source||'builtin')==='builtin'?'内置':'自定义' }}</span>
        <span v-if="a.intent_router" class="badge badge-router">路由</span>
      </div>
      <div v-if="!visible.length" class="empty">暂无 Agent</div>
    </div>
  </aside>
  <main class="content">
    <section v-if="selected" class="detail-hero">
      <div class="detail-hero-icon">🤖</div>
      <div class="detail-hero-body">
        <div class="detail-hero-name">{{ selected.display_name||selected.name||selected.agent_id }}</div>
        <div class="detail-hero-id">{{ selected.agent_id }}</div>
        <div class="detail-hero-desc">{{ selected.description||'暂无描述' }}</div>
        <div class="detail-hero-badges">
          <span class="badge" :class="isBuiltin?'badge-builtin':'badge-db'">{{ isBuiltin?'内置 / 代码定义':'自定义 / DB 配置' }}</span>
          <span class="badge badge-active">运行中</span>
          <span v-if="selected.intent_router" class="badge badge-router">意图路由</span>
          <span v-if="selected.domain" class="badge badge-domain">{{ selected.domain }}</span>
        </div>
      </div>
      <div class="hero-actions"><button class="btn btn-primary btn-sm" @click="openConfig">编辑配置</button></div>
    </section>

    <section v-if="selected" class="panel agent-overview">
      <div class="section-title">配置概览</div>
      <div class="ov-grid">
        <div class="ov-cell"><span class="ov-k">业务域</span><span class="ov-v">{{ form.domain || 'platform' }}</span></div>
        <div class="ov-cell"><span class="ov-k">状态</span><span class="ov-v">{{ form.enabled ? '启用' : '停用' }}</span></div>
        <div class="ov-cell"><span class="ov-k">来源</span><span class="ov-v">{{ isBuiltin ? '内置' : '自定义' }}</span></div>
        <div class="ov-cell"><span class="ov-k">Flow</span><span class="ov-v">{{ flowBindingList.length }} 个</span></div>
        <div class="ov-cell"><span class="ov-k">技能范围</span><span class="ov-v">{{ form.included_skills.length ? form.included_skills.length + ' 个' : '全部' }}</span></div>
        <div class="ov-cell"><span class="ov-k">工具范围</span><span class="ov-v">{{ form.included_tools.length ? form.included_tools.length + ' 个' : '未限制' }}</span></div>
        <div class="ov-cell"><span class="ov-k">模型策略</span><span class="ov-v">{{ modelPolicyList.length ? modelPolicyList.length + ' 项' : '默认' }}</span></div>
        <div class="ov-cell"><span class="ov-k">路由规则</span><span class="ov-v">{{ form.router_rules.length }} 条</span></div>
      </div>

      <div class="ov-section">
        <div class="ov-label">Flow 绑定</div>
        <div v-if="flowBindingList.length" class="ov-rows">
          <div v-for="b in flowBindingList" :key="b.key" class="ov-row"><span class="ov-row-k">{{ b.key }}</span><span class="ov-row-v">{{ b.name }}</span></div>
        </div>
        <div v-else class="ov-empty">未绑定 Flow</div>
      </div>

      <div class="ov-cols">
        <div class="ov-section">
          <div class="ov-label">工具范围</div>
          <div v-if="form.included_tools.length" class="chip-wrap"><span v-for="t in form.included_tools" :key="t" class="ov-chip tool">{{ t }}</span></div>
          <div v-else class="ov-empty">未限制（可用 flow / skill 自带工具）</div>
        </div>
        <div class="ov-section">
          <div class="ov-label">技能范围</div>
          <div v-if="form.included_skills.length" class="chip-wrap"><span v-for="s in form.included_skills" :key="s" class="ov-chip skill">{{ s }}</span></div>
          <div v-else class="ov-empty">未限制（可用全部技能）</div>
        </div>
      </div>

      <div v-if="modelPolicyList.length" class="ov-section">
        <div class="ov-label">模型策略</div>
        <div class="ov-rows">
          <div v-for="p in modelPolicyList" :key="p.slot" class="ov-row"><span class="ov-row-k">{{ p.slot }}</span><span class="ov-row-v">{{ p.model }}</span></div>
        </div>
      </div>

      <div v-if="form.role" class="ov-section">
        <div class="ov-label">角色 / System Prompt</div>
        <div class="ov-prompt">{{ form.role }}</div>
      </div>
    </section>

    <section class="try-panel">
      <div class="try-panel-head"><div class="try-panel-title">▶ 快速调用</div><div style="font-size:11px;color:var(--muted)">{{ form.agent_id }}</div></div>
      <div class="try-panel-body">
        <div class="try-row">
          <div class="form-group">
            <div style="font-size:11px;font-weight:600;color:var(--muted);margin-bottom:4px">输入类型</div>
            <select class="try-select" v-model="tryInputType"><option value="chat">chat</option><option value="task">task</option></select>
          </div>
          <div class="form-group">
            <div style="font-size:11px;font-weight:600;color:var(--muted);margin-bottom:4px">Flow</div>
            <select class="try-select" v-model="tryFlowKey">
              <option value="">自动路由 / 默认 Flow</option>
              <option v-for="k in flowKeyOptions" :key="k" :value="k">{{ k }}</option>
            </select>
          </div>
        </div>
        <textarea class="try-query" v-model="tryQuery" placeholder="输入测试 query…"></textarea>
        <div style="display:flex;justify-content:flex-end"><button class="btn-run" :disabled="running" @click="runAgentTest">{{ running?'运行中…':'运行' }}</button></div>
        <div v-if="testResult" class="try-result">
          <div class="try-result-head">
            <span class="try-result-status" :class="testResult.ok?'ok':'err'">{{ testResult.status }}</span>
            <span class="try-result-meta">{{ output?.elapsed_ms }} ms{{ testResult.citations.length?`  ·  ${testResult.citations.length} 引用`:'' }}{{ testResult.route?`  ·  route: ${testResult.route}`:'' }}</span>
          </div>
          <div class="try-result-body" :class="{empty:!testResult.answer && !testResult.errMsg}" :style="testResult.errMsg?'color:var(--red)':''">{{ testResult.errMsg || testResult.answer || '（无回答）' }}</div>
          <div class="try-result-raw"><details><summary>原始响应 JSON</summary><pre>{{ JSON.stringify(output,null,2) }}</pre></details></div>
        </div>
      </div>
    </section>

    <div v-if="configOpen" class="modal-backdrop config-modal" @click.self="configOpen=false">
      <div class="config-card">
      <div class="config-head">
        <div><div class="config-title">{{ selectedId ? '编辑 Agent' : '新建 Agent' }}</div><div class="config-sub">{{ form.display_name||'未命名 Agent' }}</div></div>
        <div class="config-head-actions">
          <button class="btn btn-ghost btn-sm" @click="prevStep">上一步</button>
          <button class="btn btn-ghost btn-sm" @click="nextOrSubmit">下一步</button>
          <button class="btn btn-primary btn-sm" @click="saveAgent">保存配置</button>
          <button class="btn btn-ghost btn-sm" @click="configOpen=false">关闭</button>
        </div>
      </div>
      <div class="config-body">
      <div class="tab-bar inline"><button v-for="(label,i) in stepLabels" :key="label" class="tab-btn" :class="{active:step===i}" @click="step=i">{{ i+1 }} {{ label }}</button></div>

      <div v-if="step===0" class="editor">
        <div class="field wide">
          <label>显示名</label><input v-model="form.display_name"/>
          <label>描述</label><input v-model="form.description"/>
        </div>
        <div class="editor-side">
          <div class="field"><label>Domain</label><input v-model="form.domain" @change="loadModelChoices(form.domain)"/></div>
          <div class="field"><label>Enabled</label><select v-model="form.enabled"><option :value="true">true</option><option :value="false">false</option></select></div>
          <div v-if="form.agent_id" class="field"><label>Agent ID</label><input :value="form.agent_id" disabled/></div>
        </div>
      </div>

      <div v-else-if="step===1" class="panel-inner">
        <div class="field wide">
          <label>编排模式</label>
          <select v-model="form.orchestration_mode">
            <option value="SINGLE">SINGLE - 单 Agent</option>
            <option value="ROUTER">ROUTER - 按关键词路由到目标 Agent</option>
            <option value="WORKFLOW">WORKFLOW - 串行执行多个 Agent</option>
            <option value="SUPERVISOR">SUPERVISOR - 主 Agent 挂载子 Agent</option>
          </select>
        </div>
        <p class="pick-hint">这里保存的是 AgentScope runtime 实际读取的 orchestration，不再使用 Flow 绑定冒充编排。</p>

        <div v-if="form.orchestration_mode === 'ROUTER'">
          <div class="actions"><button class="btn btn-ghost btn-sm" @click="addOrchestrationRoute">添加路由</button></div>
          <table>
            <thead><tr><th>规则 ID</th><th>包含关键词</th><th>目标 Agent</th><th></th></tr></thead>
            <tbody>
              <tr v-for="(r,i) in form.orchestration_routes" :key="'route'+i">
                <td><input v-model="r.ruleId" placeholder="route_research"/></td>
                <td><input v-model="r.contains" placeholder="用户消息包含该文本时命中"/></td>
                <td>
                  <select v-model="r.targetAgentId">
                    <option value="">选择 Agent</option>
                    <option v-for="a in agents" :key="String(a.agent_id)" :value="a.agent_id">{{ a.display_name || a.name || a.agent_id }}</option>
                  </select>
                </td>
                <td><button class="btn small danger" @click="removeOrchestrationRoute(i)">删除</button></td>
              </tr>
              <tr v-if="!form.orchestration_routes.length"><td colspan="4" class="empty">暂无路由，保存 ROUTER 前至少添加一条。</td></tr>
            </tbody>
          </table>
        </div>

        <div v-else-if="form.orchestration_mode === 'WORKFLOW'">
          <div class="actions"><button class="btn btn-ghost btn-sm" @click="addWorkflowStep">添加步骤</button></div>
          <table>
            <thead><tr><th>步骤 ID</th><th>执行 Agent</th><th>指令</th><th></th></tr></thead>
            <tbody>
              <tr v-for="(s,i) in form.workflow_steps" :key="'step'+i">
                <td><input v-model="s.stepId" placeholder="research"/></td>
                <td>
                  <select v-model="s.agentId">
                    <option value="">选择 Agent</option>
                    <option v-for="a in agents" :key="String(a.agent_id)" :value="a.agent_id">{{ a.display_name || a.name || a.agent_id }}</option>
                  </select>
                </td>
                <td><input v-model="s.instruction" placeholder="传给该步骤 Agent 的额外指令"/></td>
                <td><button class="btn small danger" @click="removeWorkflowStep(i)">删除</button></td>
              </tr>
              <tr v-if="!form.workflow_steps.length"><td colspan="4" class="empty">暂无步骤，保存 WORKFLOW 前至少添加一个。</td></tr>
            </tbody>
          </table>
        </div>

        <div v-else-if="form.orchestration_mode === 'SUPERVISOR'">
          <div class="actions"><button class="btn btn-ghost btn-sm" @click="addSubagent">添加子 Agent</button></div>
          <table>
            <thead><tr><th>绑定名</th><th>目标 Agent</th><th>说明</th><th>暴露</th><th></th></tr></thead>
            <tbody>
              <tr v-for="(s,i) in form.subagents" :key="'sub'+i">
                <td><input v-model="s.bindingId" placeholder="researcher"/></td>
                <td>
                  <select v-model="s.targetAgentId">
                    <option value="">选择 Agent</option>
                    <option v-for="a in agents" :key="String(a.agent_id)" :value="a.agent_id">{{ a.display_name || a.name || a.agent_id }}</option>
                  </select>
                </td>
                <td><input v-model="s.description" placeholder="这个子 Agent 负责什么"/></td>
                <td><select v-model="s.exposeToUser"><option :value="true">true</option><option :value="false">false</option></select></td>
                <td><button class="btn small danger" @click="removeSubagent(i)">删除</button></td>
              </tr>
              <tr v-if="!form.subagents.length"><td colspan="5" class="empty">暂无子 Agent，保存 SUPERVISOR 前至少添加一个。</td></tr>
            </tbody>
          </table>
        </div>

        <div v-else class="empty">SINGLE 模式不需要额外编排配置。</div>
      </div>

      <div v-else-if="step===2" class="tool-grid">
        <article v-for="s in skills" :key="s.skill_id" class="tool-card" @click="toggleSkill(s.skill_id)">
          <div class="tool-card-head"><strong>{{ s.display_name||s.skill_id }}</strong><span class="badge" :class="form.included_skills.includes(s.skill_id)?'active':''">{{ form.included_skills.includes(s.skill_id)?'已选':'未选' }}</span></div>
          <p>{{ s.description }}</p>
        </article>
        <div v-if="!form.included_skills.length" class="empty" style="grid-column:1/-1">未限制技能范围（使用所有平台技能）</div>
      </div>

      <div v-else-if="step===3" class="tool-pick">
        <div class="mcp-bind">
          <div class="mcp-bind-head">
            <strong>MCP 服务器绑定</strong>
            <span class="mcp-bind-hint">先绑定 MCP 服务器，其工具才会出现在下方候选中。</span>
          </div>
          <div v-if="!form.agent_id" class="mcp-bind-warn">新建的 Agent 请先保存后再绑定 MCP 服务器。</div>
          <div v-else-if="!mcpServers.length" class="mcp-bind-empty">本组织暂无已注册的 MCP 服务器（请在「MCP 服务器」页注册）。</div>
          <div v-else class="mcp-bind-list">
            <label v-for="s in mcpServers" :key="String(s.id)" class="mcp-bind-row" :class="{bound:isMcpBound(s)}">
              <input type="checkbox" :checked="isMcpBound(s)" :disabled="!!mcpBusy[String(s.id)]" @change="toggleMcpBinding(s)" />
              <span class="mcp-bind-name">{{ s.name }}</span>
              <span class="mcp-bind-ep">{{ s.endpoint }}</span>
              <span v-if="s.enabled === false" class="badge badge-gray">已禁用</span>
              <span v-if="isMcpBound(s)" class="badge active">已绑定</span>
            </label>
          </div>
        </div>
        <div class="chip-list">
          <button class="filter-chip" :class="{active:!toolSourceFilter}" @click="toolSourceFilter=''">全部 <span class="count">{{ candidateTools.length }}</span></button>
          <button v-for="[src,c] in toolSourceCounts" :key="src" class="filter-chip" :class="{active:toolSourceFilter===src}" @click="toolSourceFilter = toolSourceFilter===src ? '' : src">{{ TOOL_SRC_LABEL[src]||src }} <span class="count">{{ c }}</span></button>
        </div>
        <div class="tool-grid" style="margin-top:12px">
          <article v-for="t in visibleTools" :key="toolKey(t)" class="tool-card pick" :class="{selected:isToolSelected(t)}" @click="toggleTool(t)">
            <div class="tool-card-head"><strong>{{ t.display_name||t.name||t.tool_id }}</strong><span class="badge" :class="isToolSelected(t)?'active':''">{{ isToolSelected(t)?'已选':'未选' }}</span></div>
            <p>{{ t.description||'无描述' }}</p>
            <div class="tags">
              <span class="tag" :class="toolSource(t)==='platform'?'platform':toolSource(t)==='domain'?'domain':''">{{ TOOL_SRC_LABEL[toolSource(t)]||toolSource(t) }}</span>
              <span v-if="t.runtime_name" class="tag mcp">🔌 {{ t.runtime_name }}</span>
              <span class="tag">{{ toolKey(t) }}</span>
            </div>
          </article>
          <div v-if="!tools.length" class="empty" style="grid-column:1/-1">暂无可用工具；MCP 工具需先在「MCP 服务器」里测试连通并同步发现。</div>
          <div v-else-if="!visibleTools.length" class="empty" style="grid-column:1/-1">该来源下暂无工具。</div>
        </div>
        <p class="pick-hint">已选 {{ form.included_tools.length }} 个工具。{{ form.included_tools.length ? '' : '未选择则不额外开放工具（仅 flow / skill 自带工具可用）。' }}</p>
      </div>

      <div v-else-if="step===4" class="panel-inner">
        <div v-if="!(modelChoices.fixed_slots as JsonMap[]||[]).length && !(modelChoices.aliases as JsonMap[]||[]).length" class="empty">暂无可选模型策略；将使用平台插槽默认链路</div>
        <div v-else class="model-policy-list">
          <template v-if="(modelChoices.fixed_slots as JsonMap[]||[]).length">
            <div class="model-policy-group-label">默认插槽</div>
            <div v-for="slot in (modelChoices.fixed_slots as JsonMap[])" :key="String(slot.slot_key)" class="model-policy-item" :class="{selected:String(slot.slot_key) in form.model_policy}" @click="toggleModelPolicy(String(slot.slot_key))">
              <div class="model-policy-check">{{ String(slot.slot_key) in form.model_policy?'✓':'' }}</div>
              <div>
                <div class="model-policy-name">{{ slot.display_name||slot.slot_key }}<span class="model-policy-kind">{{ slot.model_kind }}</span></div>
                <div class="model-policy-id">{{ slot.slot_key }} · {{ policyLabel(String(slot.slot_key), String(form.model_policy[String(slot.slot_key)]||'')) }}</div>
              </div>
              <select class="model-policy-select" :value="form.model_policy[String(slot.slot_key)]||''" :disabled="!(String(slot.slot_key) in form.model_policy)" @click.stop @change="setModelPolicySlot(String(slot.slot_key), ($event.target as HTMLSelectElement).value)">
                <option value="">跟随默认绑定</option>
                <option v-for="a in customSlotsFor(String(slot.slot_key))" :key="String(a.alias_name)" :value="a.alias_name">{{ a.alias_name }}</option>
              </select>
            </div>
          </template>
          <template v-if="(modelChoices.aliases as JsonMap[]||[]).length">
            <div class="model-policy-group-label">自定义插槽</div>
            <div v-for="a in (modelChoices.aliases as JsonMap[])" :key="String(a.alias_name)" class="model-policy-item" :class="{selected:customSlotSelected(a)}" @click="toggleCustomSlot(a)">
              <div class="model-policy-check">{{ customSlotSelected(a)?'✓':'' }}</div>
              <div>
                <div class="model-policy-name">{{ a.alias_name }}<span class="model-policy-kind">{{ a.model_kind }}</span></div>
                <div class="model-policy-id">{{ a.domain }} → {{ a.target_slot_key||'qa' }} · {{ a.model_id }}</div>
              </div>
              <div class="model-policy-alias-val">{{ a.model_display_name||a.model_id }}</div>
            </div>
          </template>
          <template v-if="boundFlowNodeSlots.length">
            <div class="model-policy-group-label">节点覆盖</div>
            <div v-for="n in boundFlowNodeSlots" :key="`${String(n.policy_key)}:${String(n.slot_key)}`" class="model-policy-item node-policy-item" :class="{selected:!!nodePolicySlot(String(n.policy_key), String(n.slot_key))}">
              <div class="model-policy-check">{{ nodePolicySlot(String(n.policy_key), String(n.slot_key)) ? '✓' : '' }}</div>
              <div>
                <div class="model-policy-name">{{ n.display_name || n.node_id }}<span class="model-policy-kind">{{ n.kind || 'node' }}</span></div>
                <div class="model-policy-id">{{ n.flow_key }}.{{ n.node_id }} · {{ n.class_name || '' }}</div>
              </div>
              <div class="model-policy-slot">{{ slotLabel(String(n.slot_key)) }}</div>
              <select class="model-policy-select" :value="nodePolicySlot(String(n.policy_key), String(n.slot_key))" @click.stop @change="setNodePolicy(String(n.policy_key), String(n.slot_key), ($event.target as HTMLSelectElement).value)">
                <option value="">跟随 {{ slotLabel(String(n.slot_key)) }} 默认</option>
                <option v-for="a in customSlotsFor(String(n.slot_key))" :key="String(a.alias_name)" :value="a.alias_name">{{ a.alias_name }}</option>
              </select>
              <button v-if="nodePolicySlot(String(n.policy_key), String(n.slot_key))" class="btn btn-ghost btn-sm" @click.stop="clearNodePolicy(String(n.policy_key), String(n.slot_key))">清除</button>
            </div>
          </template>
        </div>
      </div>

      <div v-else class="panel-inner">
        <div class="field wide"><label>角色 / System Prompt</label><textarea v-model="form.role" /></div>
        <div class="field wide"><label>Planner 规则（每行一条）</label><textarea v-model="form.planner_rules" /></div>
        <div class="field"><label>需要结构化 Plan</label><select v-model="form.require_structured_plan"><option :value="true">true</option><option :value="false">false</option></select></div>
      </div>
      </div>
      </div>
    </div>

    <section v-if="spec" class="panel">
      <div class="section-title">当前 Spec</div>
      <details><summary>查看 JSON Spec</summary><pre class="json-box">{{ JSON.stringify(spec,null,2) }}</pre></details>
    </section>
  </main>
</section>
</template>

<style scoped>
.tool-pick { padding: 4px 2px; }
.tool-pick .tool-card { cursor: pointer; }
.tool-pick .tool-card.pick { transition: border-color .15s, box-shadow .15s, transform .15s; }
.tool-pick .tool-card.pick:hover { transform: translateY(-2px); }
.tool-pick .tool-card.selected { border-color: var(--blue); box-shadow: 0 0 0 3px #dbeafe; }
.tool-pick .tag.mcp { background: #ecfdf5; color: #047857; }
.pick-hint { font-size: 12px; color: var(--muted); margin-top: 10px; }
.node-policy-item { grid-template-columns: 24px minmax(190px, 1fr) 150px minmax(180px, 240px) auto; }
.model-policy-slot { color: var(--muted); font-size: 12px; border: 1px solid var(--border); border-radius: 6px; padding: 5px 8px; text-align: center; }
.model-policy-select.compact { min-width: 130px; }
@media (max-width: 900px) {
  .node-policy-item { grid-template-columns: 24px 1fr; }
  .node-policy-item .model-policy-select,
  .node-policy-item .model-policy-slot,
  .node-policy-item .btn { grid-column: 2; width: 100%; }
}
/* MCP 服务器绑定区块 */
.mcp-bind { border: 1px solid var(--border); border-radius: 10px; padding: 12px 14px; margin-bottom: 14px; background: #f8fafc; }
.mcp-bind-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
.mcp-bind-head strong { font-size: 13px; }
.mcp-bind-hint { font-size: 12px; color: var(--muted); }
.mcp-bind-warn, .mcp-bind-empty { font-size: 12px; color: #b45309; }
.mcp-bind-list { display: flex; flex-direction: column; gap: 6px; }
.mcp-bind-row { display: flex; align-items: center; gap: 10px; padding: 7px 10px; border: 1px solid var(--border); border-radius: 8px; background: #fff; cursor: pointer; }
.mcp-bind-row.bound { border-color: var(--blue); box-shadow: 0 0 0 2px #dbeafe; }
.mcp-bind-row input { cursor: pointer; }
.mcp-bind-name { font-weight: 600; font-size: 13px; }
.mcp-bind-ep { flex: 1; font-size: 11px; font-family: ui-monospace, Menlo, Consolas, monospace; color: var(--muted); word-break: break-all; }
.mcp-bind .badge.badge-gray { background: #f1f5f9; color: #64748b; }
/* 技能步骤也加上可点光标与一致的选中高亮 */
.tool-card { cursor: pointer; }

/* 列表筛选：业务域下拉 + 搜索 */
.list-filters { padding: 10px 12px; border-bottom: 1px solid var(--border); display: grid; gap: 7px; }
.list-filters select, .list-filters input { height: 34px; width: 100%; }
/* 概览卡片右侧操作 */
.hero-actions { margin-left: auto; display: flex; align-items: flex-start; }
/* 配置概览 */
.agent-overview { padding: 16px 18px; }
.ov-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin: 6px 0 4px; }
.ov-cell { background: #f8fafc; border: 1px solid #eef2f7; border-radius: 10px; padding: 9px 11px; display: flex; flex-direction: column; gap: 3px; }
.ov-k { font-size: 11px; color: var(--muted); }
.ov-v { font-size: 14px; font-weight: 700; }
.ov-section { margin-top: 16px; }
.ov-label { font-size: 11px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: .04em; margin-bottom: 8px; }
.ov-rows { display: flex; flex-direction: column; gap: 6px; }
.ov-row { display: flex; justify-content: space-between; gap: 10px; background: #f8fafc; border: 1px solid #eef2f7; border-radius: 8px; padding: 7px 11px; font-size: 12px; }
.ov-row-k { font-family: ui-monospace, Menlo, Consolas, monospace; color: #475569; }
.ov-row-v { font-weight: 600; font-family: ui-monospace, Menlo, Consolas, monospace; }
.ov-cols { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-top: 16px; }
.ov-cols .ov-section { margin-top: 0; }
.chip-wrap { display: flex; flex-wrap: wrap; gap: 6px; }
.ov-chip { font-size: 11px; padding: 3px 9px; border-radius: 99px; font-family: ui-monospace, Menlo, Consolas, monospace; }
.ov-chip.tool { background: #ecfdf5; color: #047857; }
.ov-chip.skill { background: #eff6ff; color: #1d4ed8; }
.ov-empty { font-size: 12px; color: #94a3b8; }
.ov-prompt { font-size: 12.5px; line-height: 1.6; color: #334155; background: #f8fafc; border: 1px solid #eef2f7; border-radius: 8px; padding: 10px 12px; white-space: pre-wrap; }
@media (max-width: 1100px) { .ov-grid { grid-template-columns: repeat(2, 1fr); } .ov-cols { grid-template-columns: 1fr; } }
/* Agent 配置弹窗 */
.config-modal { position: fixed; inset: 0; background: rgba(15, 23, 42, .45); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 28px; }
.config-card { background: #fff; border-radius: 16px; width: 820px; max-width: 96vw; max-height: 90vh; display: flex; flex-direction: column; box-shadow: var(--shadow-lg); overflow: hidden; }
.config-head { display: flex; align-items: center; gap: 12px; padding: 16px 22px; border-bottom: 1px solid var(--border); flex-shrink: 0; }
.config-title { font-size: 16px; font-weight: 700; }
.config-sub { font-size: 12px; color: var(--muted); margin-top: 3px; }
.config-head-actions { margin-left: auto; display: flex; gap: 6px; flex-wrap: wrap; }
.config-body { padding: 18px 22px; overflow-y: auto; }
.config-body .tab-bar.inline { position: sticky; top: 0; background: #fff; padding: 6px 0 10px; margin: 0 0 14px; z-index: 2; flex-wrap: wrap; border-bottom: 1px solid #f1f5f9; }
@media (max-width: 720px) { .config-card { width: 100%; height: 100%; max-height: 100vh; border-radius: 0; } }
</style>
