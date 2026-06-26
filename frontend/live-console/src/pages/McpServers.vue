<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { currentOrgId, fmtDate, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { notifyError, notifySuccess } from '../stores/notify'
import { confirmDialog, promptDialog } from '../stores/dialog'

const BASE = '/platform/frontend/mcp'
const TOOLS_BASE = '/platform/frontend/tools'

const servers = ref<JsonMap[]>([])
const expanded = ref<Record<string, string>>({})
const serverTools = ref<Record<string, JsonMap[]>>({})
const bindings = ref<Record<string, JsonMap[]>>({})
const toolStatus = ref<Record<string, string>>({})
const toolVisibility = ref<Record<string, string>>({})
const toolOutput = ref<Record<string, string>>({})
const serverProbe = ref<Record<string, JsonMap | null>>({})
const serverProbing = ref<Record<string, boolean>>({})
const quickProbe = ref<JsonMap | null>(null)
const quickProbing = ref(false)
const quickEndpoint = ref('')
const error = ref('')
const loading = ref(true)

const modalOpen = ref(false)
const modalProbe = ref<JsonMap | null>(null)
const modalTesting = ref(false)
const saving = ref(false)
const form = reactive({
  id: '',
  name: '',
  endpoint: '',
  description: '',
  auth_header: '',
  timeout_ms: 5000,
  tool_filter: '',
})

function headers(json = false) {
  return makeHeaders(json, currentOrgId())
}
async function api(method: string, path = '', body?: JsonMap) {
  return await readJson<JsonMap>(await fetch(BASE + path, { method, headers: headers(Boolean(body)), body: body ? JSON.stringify(body) : undefined }))
}
async function toolsApi(method: string, path: string, body?: JsonMap) {
  return await readJson<JsonMap>(await fetch(TOOLS_BASE + path, { method, headers: headers(Boolean(body)), body: body ? JSON.stringify(body) : undefined }))
}

async function load() {
  loading.value = true
  try {
    const data = await api('GET', '')
    servers.value = (data.mcp_servers || data.items || data.servers || []) as JsonMap[]
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function openAdd() {
  form.id = ''
  form.name = ''
  form.endpoint = ''
  form.description = ''
  form.auth_header = ''
  form.timeout_ms = 5000
  form.tool_filter = ''
  modalProbe.value = null
  modalOpen.value = true
}
function editServer(s: JsonMap) {
  form.id = String(s.id)
  form.name = String(s.name || '')
  form.endpoint = String(s.endpoint || '')
  form.description = String(s.description || '')
  form.auth_header = ''
  form.timeout_ms = typeof s.timeout_ms === 'number' ? s.timeout_ms : 5000
  form.tool_filter = Array.isArray(s.tool_filter) ? (s.tool_filter as string[]).join(', ') : ''
  modalProbe.value = null
  modalOpen.value = true
}

async function testModalEndpoint() {
  if (!form.endpoint.trim()) {
    notifyError('请填写 Endpoint')
    return
  }
  modalTesting.value = true
  modalProbe.value = null
  try {
    const data = await api('POST', '/probe', { endpoint: form.endpoint.trim(), auth_header: form.auth_header.trim() || undefined })
    modalProbe.value = (data.probe as JsonMap) || null
  } catch (err) {
    modalProbe.value = { ok: false, error: err instanceof Error ? err.message : String(err) }
  } finally {
    modalTesting.value = false
  }
}

async function save() {
  if (!form.name.trim()) {
    notifyError('请填写名称')
    return
  }
  if (!form.endpoint.trim()) {
    notifyError('请填写 Endpoint')
    return
  }
  saving.value = true
  try {
    const tool_filter = form.tool_filter.split(',').map((s) => s.trim()).filter(Boolean)
    const body: JsonMap = {
      name: form.name.trim(),
      endpoint: form.endpoint.trim(),
      description: form.description.trim() || null,
      timeout_ms: form.timeout_ms || 5000,
      tool_filter,
    }
    if (form.auth_header.trim()) body.auth_header = form.auth_header.trim()
    if (form.id) await api('PATCH', `/${form.id}`, body)
    else await api('POST', '', body)
    modalOpen.value = false
    await load()
    notifySuccess(`MCP Server ${body.name} 已${form.id ? '保存' : '注册'}`)
  } catch (err) {
    notifyError(err)
  } finally {
    saving.value = false
  }
}

function probeMessage(probe: JsonMap | null): string {
  if (!probe) return ''
  if (probe.ok) {
    const tools = Array.isArray(probe.tools) ? probe.tools.join(' ') : ''
    return `✓ 连通正常 · ${probe.server_name || ''} v${probe.server_version || ''} · ${probe.tool_count ?? 0} 个工具${tools ? `\n${tools}` : ''}`
  }
  return `✗ 连通失败 [${probe.stage || '-'}]: ${probe.error || probe.message || '未知错误'}`
}
function healthBadge(s: JsonMap): { label: string; cls: string } {
  const status = (s.metadata as JsonMap | undefined)?.health_status || 'unknown'
  if (status === 'healthy') return { label: '健康', cls: 'badge-green' }
  if (status === 'unhealthy') return { label: '异常', cls: 'badge-red' }
  return { label: '未检测', cls: 'badge-gray' }
}
function healthMeta(s: JsonMap): string {
  const m = (s.metadata as JsonMap) || {}
  const parts: string[] = []
  if (m.last_tool_count != null) parts.push(`${m.last_tool_count} 个工具`)
  if (m.server_name) parts.push(`${m.server_name}${m.server_version ? ` v${m.server_version}` : ''}`)
  if (m.last_discovered_at) parts.push(`发现 ${fmtDate(m.last_discovered_at)}`)
  if (m.last_error) parts.push(`错误 ${m.last_error}`)
  return parts.length ? parts.join(' · ') : '尚未执行连通测试'
}
function paramNames(t: JsonMap): string {
  const props = (t.parameter_schema as JsonMap | undefined)?.properties
  if (!props || typeof props !== 'object') return '无'
  const keys = Object.keys(props).sort()
  return keys.length ? keys.join(', ') : '无'
}

async function probe(s: JsonMap) {
  const id = String(s.id)
  serverProbing.value[id] = true
  try {
    const data = await api('POST', `/${id}/probe`)
    serverProbe.value[id] = (data.probe as JsonMap) || null
    if (data.server) {
      const idx = servers.value.findIndex((x) => String(x.id) === id)
      if (idx >= 0) servers.value[idx] = data.server as JsonMap
    }
    if (expanded.value[id] === 'tools') await showTools(s, true)
  } catch (err) {
    serverProbe.value[id] = { ok: false, error: err instanceof Error ? err.message : String(err) }
  } finally {
    serverProbing.value[id] = false
  }
}
async function probeQuick() {
  if (!quickEndpoint.value.trim()) {
    notifyError('请输入 endpoint')
    return
  }
  quickProbing.value = true
  try {
    const data = await api('POST', '/probe', { endpoint: quickEndpoint.value.trim() })
    quickProbe.value = (data.probe as JsonMap) || null
  } catch (err) {
    quickProbe.value = { ok: false, error: err instanceof Error ? err.message : String(err) }
  } finally {
    quickProbing.value = false
  }
}
async function toggle(s: JsonMap) {
  try {
    await api('PATCH', `/${s.id}`, { enabled: !s.enabled })
    await load()
    notifySuccess(`${s.name || s.id} 已${s.enabled ? '禁用' : '启用'}`)
  } catch (err) {
    notifyError(err)
  }
}
async function remove(s: JsonMap) {
  if (!(await confirmDialog(`确认删除 MCP 服务器 "${s.name || s.id}"？相关绑定也会一并删除。`, { title: '删除服务器', danger: true }))) return
  try {
    await api('DELETE', `/${s.id}`)
    await load()
    notifySuccess(`MCP Server ${s.name || s.id} 已删除`)
  } catch (err) {
    notifyError(err)
  }
}
async function showTools(s: JsonMap, reload = false) {
  const id = String(s.id)
  if (!reload) expanded.value[id] = expanded.value[id] === 'tools' ? '' : 'tools'
  if (reload || expanded.value[id] === 'tools') {
    const data = await api('GET', `/${id}/tools`)
    const tools = (data.tools || data.items || []) as JsonMap[]
    serverTools.value[id] = tools
    for (const t of tools) {
      const key = `${id}:${t.tool_id}`
      toolStatus.value[key] = t.binding_status === 'enabled' ? 'enabled' : 'disabled'
      toolVisibility.value[key] = t.binding_visibility === 'discoverable' ? 'discoverable' : 'hidden'
    }
  }
}
async function showBindings(s: JsonMap) {
  const id = String(s.id)
  expanded.value[id] = expanded.value[id] === 'bindings' ? '' : 'bindings'
  if (expanded.value[id] === 'bindings') {
    const data = await api('GET', `/${id}/bindings`)
    bindings.value[id] = (data.bindings || data.items || []) as JsonMap[]
  }
}
async function probeAndReloadTools(s: JsonMap) {
  await probe(s)
  await showTools(s, true)
}
async function saveToolBinding(serverId: string, t: JsonMap) {
  const toolId = String(t.tool_id)
  const key = `${serverId}:${toolId}`
  try {
    await toolsApi('PUT', `/bindings/${encodeURIComponent(toolId)}`, {
      binding_status: toolStatus.value[key] || 'enabled',
      binding_visibility: toolVisibility.value[key] || 'discoverable',
      domain: null,
    })
    notifySuccess(`已保存工具绑定 ${t.tool_name || toolId}`)
    await showTools({ id: serverId }, true)
  } catch (err) {
    notifyError(err)
  }
}
async function testTool(serverId: string, t: JsonMap) {
  const toolId = String(t.tool_id)
  const key = `${serverId}:${toolId}`
  const raw = await promptDialog(`测试工具: ${t.tool_name || toolId}`, '输入 JSON 参数', '{}')
  if (raw === null) return
  let args: JsonMap = {}
  try {
    args = raw.trim() ? JSON.parse(raw) : {}
    if (!args || typeof args !== 'object' || Array.isArray(args)) throw new Error('参数必须是 JSON object')
  } catch (e) {
    notifyError(`JSON 参数无效: ${e instanceof Error ? e.message : String(e)}`)
    return
  }
  toolOutput.value[key] = '测试中…'
  try {
    const data = await toolsApi('POST', `/${encodeURIComponent(toolId)}/test`, { domain: null, arguments: args })
    toolOutput.value[key] = `ok · ${data.latency_ms}ms\n${data.result_preview || JSON.stringify(data.result ?? data, null, 2)}`
  } catch (err) {
    toolOutput.value[key] = `failed · ${err instanceof Error ? err.message : String(err)}`
  }
}
async function schemaHistory(serverId: string, t: JsonMap) {
  const toolId = String(t.tool_id)
  const key = `${serverId}:${toolId}`
  toolOutput.value[key] = '加载 schema 历史…'
  try {
    const data = await toolsApi('GET', `/${encodeURIComponent(toolId)}/schema-snapshots?limit=5`)
    const items = (data.items || []) as JsonMap[]
    if (!items.length) {
      toolOutput.value[key] = '暂无 schema 历史。先执行"测试连通"同步 tools/list。'
      return
    }
    toolOutput.value[key] = items
      .map((item) => {
        const props = (item.parameter_schema as JsonMap | undefined)?.properties
        const names = props && typeof props === 'object' ? Object.keys(props).sort() : []
        const time = item.discovered_at ? fmtDate(item.discovered_at) : '-'
        return `${item.version || '-'} · ${String(item.checksum || '').slice(0, 12)} · ${time} · 参数:${names.length ? names.join(',') : '无'}`
      })
      .join('\n')
  } catch (err) {
    toolOutput.value[key] = err instanceof Error ? err.message : String(err)
  }
}

onMounted(load)
</script>

<template>
  <div class="mcp-content">
    <div class="toolbar">
      <span class="toolbar-label">快速连通测试</span>
      <input v-model="quickEndpoint" class="probe-input" placeholder="输入 MCP endpoint，如 http://localhost:8101/mcp" @keydown.enter="probeQuick" />
      <div class="toolbar-right">
        <button class="btn btn-ghost btn-sm" :disabled="quickProbing" @click="probeQuick">{{ quickProbing ? '探测中…' : '测试连通性' }}</button>
        <button class="btn btn-primary btn-sm" @click="openAdd">+ 注册服务器</button>
        <button class="btn btn-ghost btn-sm" @click="load">↻ 刷新</button>
      </div>
    </div>
    <div v-if="quickProbe" class="probe-result" :class="quickProbe.ok ? 'probe-ok' : 'probe-fail'" style="margin-bottom:16px">{{ probeMessage(quickProbe) }}</div>

    <div class="section-title">已注册的 MCP 服务器</div>

    <div v-if="loading" class="empty-state"><div class="icon">🔌</div><p>正在加载...</p></div>
    <div v-else-if="error" class="empty-state"><div class="icon">⚠️</div><p style="color:var(--red)">{{ error }}</p></div>
    <div v-else-if="!servers.length" class="empty-state">
      <div class="icon">🔌</div>
      <p>还没有注册 MCP 服务器</p>
      <button class="btn btn-primary btn-sm" @click="openAdd">+ 注册第一个服务器</button>
    </div>
    <div v-else class="servers-grid">
      <div v-for="s in servers" :key="s.id as string" class="server-card" :class="{ 'disabled-card': !s.enabled }">
        <div class="server-head">
          <div class="server-icon">🔌</div>
          <div class="server-info">
            <div class="server-name">{{ s.name }}</div>
            <div class="server-endpoint">{{ s.endpoint }}</div>
            <div class="server-badges">
              <span class="badge" :class="s.enabled ? 'badge-green' : 'badge-red'">{{ s.enabled ? '● 启用' : '○ 禁用' }}</span>
              <span class="badge" :class="healthBadge(s).cls">{{ healthBadge(s).label }}</span>
              <span v-if="s.has_auth" class="badge badge-blue">🔑 有认证</span>
              <span class="badge badge-gray">{{ s.timeout_ms }}ms</span>
            </div>
          </div>
        </div>
        <div v-if="s.description" class="server-desc">{{ s.description }}</div>
        <div class="server-desc">健康状态：{{ healthMeta(s) }}</div>
        <div class="server-tools">
          工具过滤：
          <template v-if="(s.tool_filter as string[] | undefined)?.length">
            <span v-for="t in (s.tool_filter as string[])" :key="t">{{ t }}</span>
          </template>
          <span v-else style="color:var(--muted)">全部工具</span>
        </div>
        <div v-if="serverProbing[String(s.id)]" class="probe-result" style="background:#f8fafc;color:var(--muted)">正在探测...</div>
        <div v-else-if="serverProbe[String(s.id)]" class="probe-result" :class="serverProbe[String(s.id)]?.ok ? 'probe-ok' : 'probe-fail'">{{ probeMessage(serverProbe[String(s.id)]) }}</div>
        <div class="server-actions">
          <button class="btn btn-ghost btn-sm" @click="probe(s)">🔍 测试连通</button>
          <button class="btn btn-ghost btn-sm" @click="showTools(s)">🧰 发现工具</button>
          <button class="btn btn-ghost btn-sm" @click="showBindings(s)">🔗 Agent 绑定</button>
          <button class="btn btn-ghost btn-sm" @click="editServer(s)">编辑</button>
          <button class="btn btn-sm" :class="s.enabled ? 'btn-ghost' : 'btn-success'" @click="toggle(s)">{{ s.enabled ? '禁用' : '启用' }}</button>
          <button class="btn btn-danger btn-sm" @click="remove(s)">删除</button>
        </div>

        <div v-if="expanded[String(s.id)] === 'tools'" class="tools-panel open">
          <div class="bindings-label">发现工具</div>
          <div v-if="(serverTools[String(s.id)] || []).length" class="panel-rows">
            <div v-for="t in serverTools[String(s.id)] || []" :key="t.tool_id as string" class="tool-row">
              <div>
                <div class="tool-name">{{ t.tool_name }}</div>
                <div class="tool-meta">{{ t.tool_id }} · runtime: {{ t.runtime_name }}</div>
                <div class="tool-meta">参数：{{ paramNames(t) }}</div>
                <div v-if="t.description" class="tool-desc">{{ t.description }}</div>
              </div>
              <div class="tool-actions">
                <span class="badge" :class="toolStatus[`${s.id}:${t.tool_id}`] === 'enabled' ? 'badge-green' : 'badge-red'">{{ toolStatus[`${s.id}:${t.tool_id}`] === 'enabled' ? 'enabled' : t.binding_status }}</span>
                <select v-model="toolStatus[`${s.id}:${t.tool_id}`]" class="select-sm">
                  <option value="enabled">启用</option>
                  <option value="disabled">停用</option>
                </select>
                <select v-model="toolVisibility[`${s.id}:${t.tool_id}`]" class="select-sm">
                  <option value="discoverable">可见</option>
                  <option value="hidden">隐藏</option>
                </select>
                <button class="btn btn-ghost btn-sm" @click="testTool(String(s.id), t)">测试</button>
                <button class="btn btn-ghost btn-sm" @click="schemaHistory(String(s.id), t)">历史</button>
                <button class="btn btn-primary btn-sm" @click="saveToolBinding(String(s.id), t)">保存</button>
                <div v-if="toolOutput[`${s.id}:${t.tool_id}`]" class="tool-test-out">{{ toolOutput[`${s.id}:${t.tool_id}`] }}</div>
              </div>
            </div>
          </div>
          <div v-else class="tools-empty">
            还没有同步到工具目录。先点"测试连通"，成功后会自动同步 tools/list。
            <button class="btn btn-ghost btn-sm" style="align-self:flex-start;margin-top:6px" @click="probeAndReloadTools(s)">测试并同步</button>
          </div>
        </div>

        <div v-if="expanded[String(s.id)] === 'bindings'" class="bindings-panel open">
          <div class="bindings-label">Agent 绑定</div>
          <template v-if="(bindings[String(s.id)] || []).length">
            <div v-for="b in bindings[String(s.id)] || []" :key="(b.id || b.agent_id) as string" class="binding-row">
              <div class="binding-agent">{{ b.agent_id }}</div>
              <span class="badge" :class="b.enabled ? 'badge-green' : 'badge-gray'">{{ b.enabled ? '启用' : '禁用' }}</span>
            </div>
          </template>
          <div v-else class="tools-empty">暂无绑定的 Agent</div>
        </div>
      </div>
    </div>

    <div v-if="modalOpen" class="modal-backdrop" @click.self="modalOpen = false">
      <div class="modal">
        <div class="modal-title">{{ form.id ? '编辑 MCP 服务器' : '注册 MCP 服务器' }}</div>
        <div class="form-group">
          <label class="form-label">服务器名称 *</label>
          <input v-model="form.name" class="form-input" placeholder="如 datetime-tools、calc-tools" />
        </div>
        <div class="form-group">
          <label class="form-label">Endpoint *</label>
          <input v-model="form.endpoint" class="form-input mono" placeholder="http://localhost:8101/mcp" />
          <div class="form-hint">必须是完整 URL，接受 POST JSON-RPC 请求（MCP Streamable HTTP）</div>
        </div>
        <div class="form-group">
          <label class="form-label">描述</label>
          <input v-model="form.description" class="form-input" placeholder="简要描述这个 MCP 服务器的用途" />
        </div>
        <div class="form-row">
          <div class="form-group">
            <label class="form-label">超时 (ms)</label>
            <input v-model.number="form.timeout_ms" class="form-input" type="number" min="500" max="60000" />
          </div>
          <div class="form-group">
            <label class="form-label">Authorization Header</label>
            <input v-model="form.auth_header" class="form-input" type="password" placeholder="Bearer token... (可选)" />
          </div>
        </div>
        <div class="form-group">
          <label class="form-label">初始 Tool 过滤（兼容字段，留空 = 首次同步全部）</label>
          <input v-model="form.tool_filter" class="form-input" placeholder="tool1, tool2, tool3（逗号分隔）" />
          <div class="form-hint">正式启停请在服务器卡片的"发现工具"里逐个配置</div>
        </div>
        <div v-if="modalProbe" class="probe-result" :class="modalProbe.ok ? 'probe-ok' : 'probe-fail'">{{ probeMessage(modalProbe) }}</div>
        <div class="modal-actions">
          <button class="btn btn-ghost" @click="modalOpen = false">取消</button>
          <button class="btn btn-ghost" :disabled="modalTesting" @click="testModalEndpoint">🔍 {{ modalTesting ? '探测中…' : '测试连通性' }}</button>
          <button class="btn btn-primary" :disabled="saving" @click="save">{{ form.id ? '保存' : '注册' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mcp-content { flex: 1; overflow-y: auto; padding: 24px; }
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; background: var(--panel); padding: 12px 16px; border-radius: 12px; border: 1px solid var(--border); }
.toolbar-label { font-size: 13px; font-weight: 600; flex-shrink: 0; }
.probe-input { flex: 1; height: 34px; font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; }
.toolbar-right { margin-left: auto; display: flex; gap: 8px; }
.section-title { font-size: 12px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: .05em; margin-bottom: 12px; }

.servers-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 16px; align-content: start; }
.server-card { background: var(--panel); border: 1px solid var(--border); border-radius: 12px; padding: 18px; display: flex; flex-direction: column; gap: 12px; transition: .15s; }
.server-card:hover { border-color: var(--blue); box-shadow: 0 0 0 3px #dbeafe; }
.server-card.disabled-card { opacity: .6; }
.server-head { display: flex; align-items: flex-start; gap: 12px; }
.server-icon { width: 40px; height: 40px; border-radius: 10px; background: linear-gradient(135deg, #dbeafe, #e0e7ff); display: flex; align-items: center; justify-content: center; font-size: 20px; flex-shrink: 0; }
.server-info { flex: 1; min-width: 0; }
.server-name { font-size: 14px; font-weight: 700; }
.server-endpoint { font-size: 11px; color: var(--muted); font-family: ui-monospace, Menlo, Consolas, monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 2px; }
.server-badges { display: flex; gap: 5px; flex-wrap: wrap; margin-top: 4px; }
.server-badges .badge { font-size: 10px; font-weight: 600; padding: 2px 7px; border-radius: 99px; }
.badge-green { background: #dcfce7; color: #166534; }
.badge-red { background: #fee2e2; color: #dc2626; }
.badge-blue { background: #dbeafe; color: #1d4ed8; }
.badge-gray { background: #f1f5f9; color: #475569; }
.server-desc { font-size: 12px; color: var(--muted); line-height: 1.5; }
.server-tools { font-size: 11px; color: var(--muted); }
.server-tools span { font-family: ui-monospace, Menlo, Consolas, monospace; background: #f8fafc; padding: 1px 5px; border-radius: 4px; margin-right: 4px; border: 1px solid var(--border); }
.server-actions { display: flex; gap: 6px; flex-wrap: wrap; border-top: 1px solid var(--border); padding-top: 10px; }
.btn-success { background: #dcfce7; color: #166534; border-color: #86efac; }
.btn-success:hover { background: #bbf7d0; }

.probe-result { font-size: 11px; padding: 8px 10px; border-radius: 8px; font-family: ui-monospace, Menlo, Consolas, monospace; white-space: pre-wrap; word-break: break-all; max-height: 160px; overflow-y: auto; }
.probe-ok { background: #dcfce7; color: #166534; border: 1px solid #86efac; }
.probe-fail { background: #fee2e2; color: #dc2626; border: 1px solid #fca5a5; }

.tools-panel, .bindings-panel { border-top: 1px solid var(--border); padding-top: 10px; display: flex; flex-direction: column; gap: 8px; }
.bindings-label { font-size: 11px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: .05em; }
.panel-rows { display: flex; flex-direction: column; gap: 6px; }
.binding-row { display: flex; align-items: center; gap: 8px; background: #f8fafc; border: 1px solid var(--border); border-radius: 8px; padding: 6px 10px; }
.binding-agent { flex: 1; font-size: 12px; font-family: ui-monospace, Menlo, Consolas, monospace; color: var(--text); word-break: break-all; }
.tool-row { display: flex; flex-direction: column; gap: 8px; min-width: 0; background: #f8fafc; border: 1px solid var(--border); border-radius: 8px; padding: 10px 11px; }
.tool-row > div:first-child { min-width: 0; }
.tool-name { font-size: 12px; font-weight: 700; font-family: ui-monospace, Menlo, Consolas, monospace; color: var(--text); overflow-wrap: anywhere; }
.tool-meta { font-size: 11px; color: var(--muted); font-family: ui-monospace, Menlo, Consolas, monospace; overflow-wrap: anywhere; margin-top: 3px; }
.tool-desc { font-size: 11px; color: var(--muted); line-height: 1.4; margin-top: 4px; }
.tool-actions { display: flex; gap: 6px; align-items: center; justify-content: flex-start; flex-wrap: wrap; padding-top: 8px; border-top: 1px dashed var(--border); }
.tool-test-out { flex-basis: 100%; font-size: 11px; color: var(--muted); font-family: ui-monospace, Menlo, Consolas, monospace; white-space: pre-wrap; word-break: break-all; }
.select-sm { border: 1px solid var(--border); border-radius: 7px; background: #fff; color: var(--text); font-size: 12px; height: 30px; padding: 0 8px; }
.tools-empty { font-size: 12px; color: var(--muted); padding: 4px 0; display: flex; flex-direction: column; }

.empty-state { text-align: center; padding: 60px 20px; color: var(--muted); }
.empty-state .icon { font-size: 48px; margin-bottom: 12px; }
.empty-state p { font-size: 14px; margin-bottom: 16px; }

.modal-backdrop { position: fixed; inset: 0; background: rgba(0, 0, 0, .45); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal { background: #fff; border-radius: 16px; padding: 24px; width: 480px; max-width: 96vw; max-height: 90vh; overflow-y: auto; display: flex; flex-direction: column; gap: 16px; }
.modal-title { font-size: 15px; font-weight: 700; }
.form-group { display: flex; flex-direction: column; gap: 5px; }
.form-label { font-size: 12px; font-weight: 600; color: var(--muted); }
.form-input { border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; font-size: 13px; outline: none; font-family: inherit; height: auto; }
.form-input.mono { font-family: ui-monospace, Menlo, Consolas, monospace; }
.form-input:focus { border-color: var(--blue); }
.form-hint { font-size: 11px; color: var(--muted); }
.form-row { display: flex; gap: 10px; }
.form-row .form-group { flex: 1; }
.modal-actions { display: flex; gap: 8px; justify-content: flex-end; border-top: 1px solid var(--border); padding-top: 14px; }

@media (max-width: 980px) {
  .mcp-content { padding: 16px; }
  .servers-grid { grid-template-columns: 1fr; }
  .toolbar { align-items: stretch; flex-direction: column; }
  .toolbar-right { margin-left: 0; }
}
</style>
