<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { confidenceText, useMemoryApi } from './memoryPageSupport'
import { currentDomain, fmtDate, readJson, type JsonMap } from '../lib/platformApi'
import { notifyError, notifySuccess } from '../stores/notify'
import { confirmDialog, formDialog, promptDialog } from '../stores/dialog'

const api = useMemoryApi()
const domainOptions = ref<JsonMap[]>([{ domain: '', label: '全部业务域' }, { domain: 'platform', label: '平台' }])
async function loadDomains() {
  try {
    const status = await readJson<JsonMap>(await fetch('/platform/frontend/infra/status', { headers: api.headers(false) }))
    const raw = status.domains && typeof status.domains === 'object' ? status.domains as JsonMap : {}
    domainOptions.value = [{ domain: '', label: '全部业务域' }, { domain: 'platform', label: '平台' }, ...Object.entries(raw).map(([d, s]) => ({ domain: d, label: String((s as JsonMap)?.display_name || d) })).filter((r) => r.domain !== 'platform')]
  } catch { /* ignore */ }
}
const rows = ref<JsonMap[]>([])
const keyword = ref('')
const filters = reactive({ scope: '', status: '', memory_type: '', domain: currentDomain('') })
const editor = reactive({ id: '', content: '', scope: 'user', memory_type: 'preference', status: 'active', confidence: 1 })
const episodic = ref<JsonMap>({})
const maintenance = ref<JsonMap>({})
const maintenanceResult = ref<JsonMap | null>(null)
const selected = ref<JsonMap | null>(null)
const detailContent = ref('')
const auditRows = ref<JsonMap[]>([])
const sourceRun = ref<JsonMap | null>(null)
const statusText = ref('')
const loading = ref(false)
const editorOpen = ref(false)
const contextLabel = computed(() => `${api.orgId()} / ${api.userId()}${filters.domain ? ' / ' + filters.domain : ''}`)
const filteredRows = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return rows.value
  return rows.value.filter((item) => [item.id, item.content, item.content_summary, item.domain, item.scope, item.memory_type, item.status].join(' ').toLowerCase().includes(q))
})
const stats = computed(() => ({ count: filteredRows.value.length, pending: filteredRows.value.filter((item) => item.status === 'pending_confirm').length, active: filteredRows.value.filter((item) => (item.status || 'active') === 'active').length, disabled: filteredRows.value.filter((item) => ['inactive','disabled','expired','rejected','deleted','merged'].includes(String(item.status || ''))).length, actions: Number(maintenanceResult.value?.planned_actions || 0) }))

function queryString() {
  const p = new URLSearchParams({ limit: '200' })
  if (filters.scope) p.set('scope', filters.scope)
  if (filters.status) p.set('status', filters.status)
  if (filters.memory_type) p.set('memory_type', filters.memory_type)
  if (filters.domain) p.set('domain', filters.domain)
  return p.toString()
}
function pretty(value: unknown) { try { return JSON.stringify(value ?? {}, null, 2) } catch { return String(value || '') } }
function runIdOf(item: JsonMap | null) { return String(item?.source_links?.run_id || item?.source_run_id || '') }
async function loadRows() { rows.value = await api.list(queryString()) }
async function loadEpisodic() { episodic.value = await api.episodic(filters.domain) }
async function loadMaintenance() { maintenance.value = await api.maintenanceStatus(filters.domain) }
async function loadAll() {
  loading.value = true
  statusText.value = '加载中…'
  try {
    await Promise.all([loadRows(), loadEpisodic(), loadMaintenance()])
    statusText.value = `已刷新 · ${new Date().toLocaleTimeString('zh-CN')}`
  } catch (err) {
    statusText.value = err instanceof Error ? err.message : String(err)
  } finally {
    loading.value = false
  }
}
function edit(item: JsonMap) { editor.id = String(item.id || ''); editor.content = String(item.content || ''); editor.scope = String(item.scope || 'user'); editor.memory_type = String(item.memory_type || 'preference'); editor.status = String(item.status || 'active'); editor.confidence = Number(item.confidence ?? 1) }
function resetEditor() { editor.id = ''; editor.content = ''; editor.scope = 'user'; editor.memory_type = 'preference'; editor.status = 'active'; editor.confidence = 1 }
function openEditor(item?: JsonMap) { if (item) edit(item); else resetEditor(); editorOpen.value = true }
async function save() { try { await api.save(editor, filters.domain); notifySuccess(editor.id ? '已保存' : '已新增'); resetEditor(); editorOpen.value = false; await loadRows() } catch (err) { notifyError(err) } }
async function setStatus(item: JsonMap, status: string) { try { await api.patchStatus(item.id, status); notifySuccess(status === 'active' ? '已启用' : '已停用'); await loadRows() } catch (err) { notifyError(err) } }
async function confirmMemory(item: JsonMap) { try { await api.confirm(item.id); notifySuccess('已确认'); await loadRows(); if (selected.value?.id === item.id) await openDetail(item) } catch (err) { notifyError(err) } }
async function reject(item: JsonMap) {
  const reason = await promptDialog('拒绝记忆', '拒绝原因（可留空）', '')
  if (reason === null) return
  try { await api.reject(item.id, reason || ''); notifySuccess('已拒绝'); await loadRows() } catch (err) { notifyError(err) }
}
async function remove(item: JsonMap) {
  if (!await confirmDialog(`确定删除记忆 #${item.id} 吗？`, { title: '删除记忆', danger: true })) return
  try { await api.remove(item.id); notifySuccess('已删除'); if (selected.value?.id === item.id) closeDetail(); await loadRows() } catch (err) { notifyError(err) }
}
async function mergeMemory(item: JsonMap) {
  const values = await formDialog({
    title: '合并记忆',
    message: `将记忆 #${item.id} 合并到目标记忆`,
    fields: [
      { key: 'target_id', label: '目标记忆 ID', placeholder: '例如 123' },
      { key: 'update', label: '合并后的目标内容（可留空）', type: 'textarea' },
      { key: 'comment', label: '合并说明（可留空）' },
    ],
  })
  if (!values) return
  const target = Number(values.target_id)
  if (!Number.isInteger(target) || target <= 0 || target === Number(item.id)) { notifyError('目标 ID 无效'); return }
  try {
    await api.merge(item.id, target, values.update || '', values.comment || '')
    notifySuccess('已合并')
    await loadRows()
  } catch (err) { notifyError(err) }
}
async function openDetail(item: JsonMap) {
  const data = await api.get(item.id)
  selected.value = data.item || data
  detailContent.value = String(selected.value?.content || '')
  sourceRun.value = null
  await loadAudit(item.id)
}
function closeDetail() { selected.value = null; auditRows.value = []; sourceRun.value = null }
async function loadAudit(id: unknown) { const p = new URLSearchParams({ target_type: 'long_term', target_id: String(id), limit: '20' }); const data = await api.audit(p.toString()); auditRows.value = Array.isArray(data.items) ? data.items : [] }
async function saveDetail() { if (!selected.value) return; try { await api.patch(selected.value.id, { content: detailContent.value }); notifySuccess('详情内容已保存'); await openDetail(selected.value); await loadRows() } catch (err) { notifyError(err) } }
async function confirmDetail() { if (!selected.value) return; try { await api.confirm(selected.value.id, detailContent.value); notifySuccess('已确认'); closeDetail(); await loadRows() } catch (err) { notifyError(err) } }
async function rejectDetail() {
  if (!selected.value) return
  const reason = await promptDialog('拒绝记忆', '拒绝原因（可留空）', '')
  if (reason === null) return
  try { await api.reject(selected.value.id, reason || ''); notifySuccess('已拒绝'); closeDetail(); await loadRows() } catch (err) { notifyError(err) }
}
async function loadSourceRun() {
  const id = runIdOf(selected.value)
  if (!id) return
  const [run, steps] = await Promise.all([
    readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(id)}`, { headers: api.headers(false) })),
    readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(id)}/steps`, { headers: api.headers(false) })),
  ])
  sourceRun.value = { run, steps }
}
async function previewMaintenance() { try { maintenanceResult.value = await api.maintenanceDryRun(filters.domain); notifySuccess(`维护预检完成：${maintenanceResult.value.planned_actions || 0} 个动作`) } catch (err) { notifyError(err) } }
async function runEpisodicMaintenance(dry: boolean) { try { const data = await api.episodicMaintenance(filters.domain, dry); notifySuccess(dry ? `过期清理预检：${data.planned_actions || 0} 条` : `已清理 ${data.applied_actions || 0} 条`); await loadEpisodic() } catch (err) { notifyError(err) } }
async function runEpisodicRebuild(dry: boolean) { try { const data = await api.episodicRebuild(filters.domain, dry); notifySuccess(dry ? `重建预检：${data.planned_actions || 0} 个会话` : `已重建 ${data.indexed || 0} 条`); await loadEpisodic() } catch (err) { notifyError(err) } }
async function clearEpisodic(allDomains = false) {
  const domain = allDomains ? '' : filters.domain
  if (!await confirmDialog(`确认清空 ${allDomains ? '全部 Domain' : domain || '当前 Domain'} 的跨会话历史索引吗？`, { title: '清空历史索引', danger: true })) return
  try { const data = await api.episodicClear(domain); notifySuccess(`已清空 ${data.deleted || 0} 条`); await loadEpisodic() } catch (err) { notifyError(err) }
}
function showPendingQueue() { filters.status = 'pending_confirm'; loadRows() }
function showDefaultQueue() { filters.status = ''; loadRows() }

onMounted(() => { loadDomains(); loadAll() })
</script>

<template>
  <div class="memory-page">
    <div class="mem-head">
      <div><div class="mem-title">记忆管理</div><div class="mem-context">{{ contextLabel }}</div></div>
      <div class="mem-head-right"><span class="mem-status">{{ statusText }}</span><button class="btn btn-ghost btn-sm" @click="loadAll">{{ loading ? '刷新中…' : '🔄 刷新' }}</button></div>
    </div>

    <div class="stats">
      <div class="stat"><div class="stat-label">当前列表</div><div class="stat-val">{{ stats.count }}</div></div>
      <div class="stat"><div class="stat-label">待确认</div><div class="stat-val" :class="stats.pending ? 'warn' : ''">{{ stats.pending }}</div></div>
      <div class="stat"><div class="stat-label">Active</div><div class="stat-val">{{ stats.active }}</div></div>
      <div class="stat"><div class="stat-label">Disabled</div><div class="stat-val">{{ stats.disabled }}</div></div>
      <div class="stat"><div class="stat-label">维护动作</div><div class="stat-val">{{ stats.actions }}</div></div>
    </div>

    <!-- 长期记忆：筛选 + 列表 合为一体 -->
    <section class="panel">
      <div class="section-head">
        <div><div class="section-title">长期记忆</div><div class="section-sub">只会注入 active 且未过期的记忆；共享 scope 修改需管理员角色。</div></div>
        <div class="actions">
          <button class="btn btn-ghost btn-sm" @click="showPendingQueue">待确认队列</button>
          <button class="btn btn-ghost btn-sm" @click="showDefaultQueue">默认列表</button>
          <button class="btn btn-primary btn-sm" @click="openEditor()">+ 新增记忆</button>
        </div>
      </div>
      <div class="toolbar">
        <div class="field"><label>Scope</label><select v-model="filters.scope" @change="loadRows"><option value="">全部可见</option><option value="user">user</option><option value="org">org</option><option value="global">global</option></select></div>
        <div class="field"><label>状态</label><select v-model="filters.status" @change="loadRows"><option value="">默认</option><option value="pending_confirm">pending_confirm</option><option value="active">active</option><option value="inactive">inactive</option><option value="expired">expired</option><option value="rejected">rejected</option><option value="merged">merged</option><option value="deleted">deleted</option><option value="disabled">disabled</option></select></div>
        <div class="field"><label>类型</label><select v-model="filters.memory_type" @change="loadRows"><option value="">全部</option><option value="preference">preference</option><option value="fact">fact</option><option value="constraint">constraint</option><option value="experience">experience</option><option value="result">result</option></select></div>
        <div class="field"><label>Domain</label><select v-model="filters.domain" @change="loadAll"><option v-for="d in domainOptions" :key="d.domain as string" :value="d.domain">{{ d.label }}</option></select></div>
        <div class="field wide"><label>关键词</label><input v-model="keyword" placeholder="本页过滤记忆正文" /></div>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>ID</th><th>范围</th><th>类型</th><th>状态</th><th>置信度</th><th>内容</th><th>更新时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-if="!filteredRows.length"><td colspan="8"><div class="empty">暂无记忆</div></td></tr>
            <tr v-for="m in filteredRows" :key="m.id">
              <td class="mono">{{ m.id }}</td>
              <td>{{ m.scope || 'user' }}<div class="muted-small">{{ m.domain || '' }}</div></td>
              <td>{{ m.memory_type }}</td>
              <td><span class="badge" :class="m.status">{{ m.status }}</span></td>
              <td>{{ confidenceText(m.confidence) }}</td>
              <td><div class="memory-cell">{{ m.content }}</div></td>
              <td>{{ fmtDate(m.updated_at || m.created_at) }}</td>
              <td><div class="actions">
                <button class="btn small" @click="openDetail(m)">详情</button>
                <button class="btn small" @click="openEditor(m)">编辑</button>
                <button v-if="m.status === 'pending_confirm'" class="btn small" @click="confirmMemory(m)">确认</button>
                <button v-if="m.status === 'pending_confirm'" class="btn small danger" @click="reject(m)">拒绝</button>
                <button class="btn small" @click="setStatus(m, m.status === 'active' ? 'inactive' : 'active')">{{ m.status === 'active' ? '停用' : '启用' }}</button>
                <button class="btn small" @click="mergeMemory(m)">合并</button>
                <button class="btn small danger" @click="remove(m)">删除</button>
              </div></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- 跨会话索引（运维）-->
    <section class="panel">
      <div class="section-head"><div><div class="section-title">跨会话索引 · Episodic</div><div class="section-sub">后台 episodic index，用于找回历史对话摘要。</div></div><button class="btn btn-ghost btn-sm" @click="loadEpisodic">刷新状态</button></div>
      <div class="mem-mini-row">
        <div class="mini"><span>检索</span><b>{{ episodic.enabled === false ? '关闭' : '开启' }}</b></div>
        <div class="mini"><span>索引写入</span><b>{{ episodic.index_enabled === false ? '关闭' : '开启' }}</b></div>
        <div class="mini"><span>TTL</span><b>{{ episodic.ttl_days ? episodic.ttl_days + ' 天' : '—' }}</b></div>
        <div class="mini"><span>条数</span><b>{{ episodic.active_count ?? 0 }} / {{ episodic.total_count ?? 0 }}</b></div>
        <div class="mini wide"><span>最近更新</span><b>{{ episodic.latest_updated_at ? fmtDate(episodic.latest_updated_at) : '—' }}</b></div>
      </div>
      <div class="mem-ops">
        <div class="op-group"><span class="op-label">长期记忆维护</span><button class="btn btn-ghost btn-sm" @click="previewMaintenance">预检 (dry-run)</button></div>
        <div class="op-group"><span class="op-label">过期清理</span><button class="btn btn-ghost btn-sm" @click="runEpisodicMaintenance(true)">预检</button><button class="btn btn-ghost btn-sm" @click="runEpisodicMaintenance(false)">执行</button></div>
        <div class="op-group"><span class="op-label">索引重建</span><button class="btn btn-ghost btn-sm" @click="runEpisodicRebuild(true)">预检</button><button class="btn btn-ghost btn-sm" @click="runEpisodicRebuild(false)">执行</button></div>
        <div class="op-group"><span class="op-label">清空索引</span><button class="btn btn-danger btn-sm" @click="clearEpisodic(false)">当前 Domain</button><button class="btn btn-danger btn-sm" @click="clearEpisodic(true)">全部 Domain</button></div>
      </div>
      <div v-if="maintenanceResult" class="mem-mini-row dry">
        <div class="mini"><span>扫描</span><b>{{ maintenanceResult.scanned || 0 }}</b></div>
        <div class="mini"><span>用户数</span><b>{{ maintenanceResult.user_count || 0 }}</b></div>
        <div class="mini"><span>计划动作</span><b>{{ maintenanceResult.planned_actions || 0 }}</b></div>
        <div class="mini wide"><span>预检原因</span><b>{{ Object.entries(maintenanceResult.reason_counts || {}).map(([k,v]) => `${k}: ${v}`).join(' · ') || '无' }}</b></div>
      </div>
    </section>

    <!-- 新增 / 编辑 弹窗 -->
    <div v-if="editorOpen" class="mem-modal" @click.self="editorOpen = false">
      <div class="mem-card">
        <div class="mem-card-head"><div class="mem-card-title">{{ editor.id ? '编辑记忆 #' + editor.id : '新增记忆' }}</div><button class="btn btn-ghost btn-sm" @click="editorOpen = false">关闭</button></div>
        <div class="mem-card-body">
          <div class="field wide"><label>内容</label><textarea v-model="editor.content" rows="4" placeholder="例如：用户偏好回答先给结论，再给依据。" /></div>
          <div class="form-row">
            <div class="field"><label>Scope</label><select v-model="editor.scope" :disabled="!!editor.id"><option value="user">user</option><option value="org">org</option><option value="global">global</option></select></div>
            <div class="field"><label>类型</label><select v-model="editor.memory_type" :disabled="!!editor.id"><option value="preference">preference</option><option value="fact">fact</option><option value="constraint">constraint</option><option value="experience">experience</option><option value="result">result</option></select></div>
            <div class="field"><label>状态</label><select v-model="editor.status"><option value="active">active</option><option value="pending_confirm">pending_confirm</option><option value="inactive">inactive</option><option value="expired">expired</option><option value="rejected">rejected</option><option value="merged">merged</option><option value="deleted">deleted</option><option value="disabled">disabled</option></select></div>
            <div class="field"><label>置信度</label><input v-model.number="editor.confidence" type="number" min="0" max="1" step="0.01" /></div>
          </div>
        </div>
        <div class="mem-card-actions"><button class="btn btn-ghost" @click="editorOpen = false">取消</button><button class="btn btn-primary" @click="save">保存</button></div>
      </div>
    </div>

    <!-- 记忆详情 弹窗 -->
    <div v-if="selected" class="mem-modal" @click.self="closeDetail">
      <div class="mem-card lg">
        <div class="mem-card-head"><div class="mem-card-title">记忆详情 #{{ selected.id }}</div><button class="btn btn-ghost btn-sm" @click="closeDetail">关闭</button></div>
        <div class="mem-card-body">
          <div class="detail-grid">
            <div class="detail-key">状态</div><div class="detail-val"><span class="badge" :class="selected.status">{{ selected.status || 'active' }}</span></div>
            <div class="detail-key">范围</div><div class="detail-val">{{ selected.scope || 'user' }} {{ selected.scope_id || '' }}</div>
            <div class="detail-key">类型</div><div class="detail-val">{{ selected.memory_type }}</div>
            <div class="detail-key">置信度</div><div class="detail-val">{{ confidenceText(selected.confidence) }}</div>
            <div class="detail-key">Domain</div><div class="detail-val">{{ selected.domain || '' }}</div>
            <div class="detail-key">来源</div><div class="detail-val">{{ selected.source_links?.session_id || selected.source_session_id || '' }} {{ runIdOf(selected) }}</div>
          </div>
          <div class="field wide"><label>内容</label><textarea v-model="detailContent" rows="4" /></div>
          <div class="detail-actions">
            <button v-if="selected.status === 'pending_confirm'" class="btn btn-primary btn-sm" @click="confirmDetail">确认并生效</button>
            <button v-if="selected.status === 'pending_confirm'" class="btn btn-danger btn-sm" @click="rejectDetail">拒绝</button>
            <button class="btn btn-ghost btn-sm" @click="saveDetail">保存内容</button>
            <button class="btn btn-ghost btn-sm" @click="openEditor(selected)">完整编辑</button>
            <button v-if="runIdOf(selected)" class="btn btn-ghost btn-sm" @click="loadSourceRun">查看 AgentRun</button>
          </div>
          <details class="mem-detail-extra"><summary>来源 / 变更记录</summary>
            <div class="field wide"><label>Source Links</label><pre class="json-box">{{ pretty(selected.source_links || {}) }}</pre></div>
            <div v-if="sourceRun" class="field wide"><label>AgentRun / Steps</label><pre class="json-box">{{ pretty(sourceRun) }}</pre></div>
            <div class="field wide"><label>Source Ref</label><pre class="json-box">{{ pretty(selected.source_ref) }}</pre></div>
            <div class="field wide"><label>最近变更</label><pre class="json-box">{{ auditRows.length ? auditRows.map(item => `${fmtDate(item.created_at)} ${item.event_type} by ${item.actor_user_id || 'system'}\n${item.metadata ? pretty(item.metadata) : ''}`).join('\n\n') : '暂无变更记录' }}</pre></div>
          </details>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.memory-page { flex: 1; overflow-y: auto; padding: 24px; display: flex; flex-direction: column; gap: 18px; }
.memory-page > * { flex-shrink: 0; }
.mem-head { display: flex; align-items: center; gap: 12px; }
.mem-title { font-size: 18px; font-weight: 700; }
.mem-context { font-size: 12px; color: var(--muted); margin-top: 3px; font-family: ui-monospace, Menlo, Consolas, monospace; }
.mem-head-right { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.mem-status { font-size: 12px; color: var(--muted); }
.stat-val.warn { color: var(--yellow); }

.mem-mini-row { display: grid; grid-template-columns: repeat(4, 1fr) 1.4fr; gap: 10px; margin-top: 4px; }
.mem-mini-row.dry { margin-top: 14px; padding-top: 14px; border-top: 1px dashed var(--border); }
.mini { background: #f8fafc; border: 1px solid #eef2f7; border-radius: 10px; padding: 9px 12px; display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.mini.wide { grid-column: span 1; }
.mini > span { font-size: 11px; color: var(--muted); }
.mini > b { font-size: 14px; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.mem-ops { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 14px; }
.op-group { display: flex; align-items: center; gap: 6px; border: 1px solid #eef2f7; border-radius: 10px; padding: 7px 10px; background: #fbfcfe; }
.op-label { font-size: 11px; font-weight: 700; color: var(--muted); margin-right: 2px; }

.mem-modal { position: fixed; inset: 0; background: rgba(15, 23, 42, .45); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 28px; }
.mem-card { background: #fff; border-radius: 16px; width: 560px; max-width: 96vw; max-height: 90vh; display: flex; flex-direction: column; box-shadow: var(--shadow-lg); overflow: hidden; }
.mem-card.lg { width: 720px; }
.mem-card-head { display: flex; align-items: center; gap: 12px; padding: 16px 20px; border-bottom: 1px solid var(--border); flex-shrink: 0; }
.mem-card-title { font-size: 16px; font-weight: 700; flex: 1; }
.mem-card-body { padding: 18px 20px; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; }
.mem-card-body .form-row { display: flex; gap: 12px; flex-wrap: wrap; }
.mem-card-body .form-row .field { flex: 1; min-width: 120px; }
.mem-card-actions { display: flex; gap: 8px; justify-content: flex-end; border-top: 1px solid var(--border); padding: 14px 20px; flex-shrink: 0; }
.mem-card .detail-grid { display: grid; grid-template-columns: 80px 1fr 80px 1fr; gap: 9px 12px; font-size: 13px; }
.mem-card .detail-key { color: var(--muted); }
.mem-card .detail-val { font-weight: 600; word-break: break-all; }
.detail-actions { display: flex; gap: 6px; flex-wrap: wrap; }
.mem-detail-extra summary { cursor: pointer; font-size: 12px; color: var(--muted); font-weight: 600; }
.mem-detail-extra .field { margin-top: 10px; }

@media (max-width: 980px) {
  .memory-page { padding: 16px; }
  .mem-mini-row { grid-template-columns: repeat(2, 1fr); }
  .mem-card .detail-grid { grid-template-columns: 80px 1fr; }
}
</style>
