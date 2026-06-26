<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { currentDomain, currentOrgId, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { notifyError, notifySuccess } from '../stores/notify'
import { confirmDialog, promptDialog } from '../stores/dialog'

const PACKAGE_PERMISSIONS = ['db', 'kg', 'object_storage']

const skills = ref<JsonMap[]>([])
const packages = ref<JsonMap[]>([])
const activeSource = ref('')
const activeStatus = ref<'' | 'enabled' | 'disabled'>('')
const keyword = ref('')
const pkgStatus = ref('')
const pkgDomain = ref('')
const rejectReason = ref('')
const pkgOutput = ref<{ text: string; kind: 'ok' | 'err' | 'info' } | null>(null)
const error = ref('')
const domain = ref(currentDomain(''))
const packageFile = ref<File | null>(null)
const packageFileName = ref('')
const packageDomain = ref('')
const uploading = ref(false)
const uploadOpen = ref(false)
function openUpload() { packageFile.value = null; packageFileName.value = ''; pkgOutput.value = null; packageDomain.value = packageDomain.value || domain.value || 'platform'; uploadOpen.value = true }
const permDrafts = reactive<Record<string, { mode: string; checks: Set<string> }>>({})

function sourceOf(s: JsonMap) {
  return String(s.domain || s.source_domain || 'global')
}
function labelSource(src: string) {
  if (src === 'global' || src === 'platform') return '平台'
  return src || '平台'
}
function isEnabled(s: JsonMap) {
  return s.enabled !== false
}
const visibleSkills = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return skills.value.filter((s) => {
    if (activeSource.value && sourceOf(s) !== activeSource.value) return false
    if (activeStatus.value === 'enabled' && !isEnabled(s)) return false
    if (activeStatus.value === 'disabled' && isEnabled(s)) return false
    if (!q) return true
    return [s.skill_id, s.name, s.display_name, s.description].join(' ').toLowerCase().includes(q)
  })
})
const sourceCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const s of skills.value) {
    const src = sourceOf(s)
    counts.set(src, (counts.get(src) || 0) + 1)
  }
  return Array.from(counts.entries()).sort(([a], [b]) => {
    if (a === 'global' || a === 'platform') return -1
    if (b === 'global' || b === 'platform') return 1
    return a.localeCompare(b)
  })
})
const enabledCount = computed(() => skills.value.filter(isEnabled).length)
const disabledCount = computed(() => skills.value.length - enabledCount.value)

function headers(json = false) {
  return makeHeaders(json, currentOrgId())
}
function setOutput(text: string, kind: 'ok' | 'err' | 'info' = 'info') {
  pkgOutput.value = { text, kind }
}
function packageId(p: JsonMap) {
  return String(p.id || p.package_id || '')
}
function permissionsOf(p: JsonMap) {
  return Array.isArray(p.granted_permissions) ? p.granted_permissions : []
}
function permText(value: unknown) {
  if (value == null) return 'source 默认'
  const arr = Array.isArray(value) ? value : []
  return arr.length ? arr.join(', ') : '沙箱'
}
function statusClass(status: unknown) {
  const s = String(status || '')
  return ['validated', 'published', 'rejected'].includes(s) ? s : 'other'
}
function statusText(status: unknown) {
  return ({ validated: '待发布', published: '已发布', rejected: '已拒绝', deprecated: '已废弃' } as Record<string, string>)[String(status || '')] || String(status || 'unknown')
}
function draftFor(p: JsonMap) {
  const id = packageId(p)
  if (!permDrafts[id]) {
    const perms = permissionsOf(p)
    permDrafts[id] = { mode: p.granted_permissions == null ? 'default' : (perms.length ? 'custom' : 'sandbox'), checks: new Set(perms) }
  }
  return permDrafts[id]
}
function togglePermCheck(p: JsonMap, perm: string) {
  const draft = draftFor(p)
  if (draft.checks.has(perm)) draft.checks.delete(perm)
  else draft.checks.add(perm)
}
function collectPermissions(p: JsonMap) {
  const draft = draftFor(p)
  if (draft.mode === 'default') return null
  if (draft.mode === 'sandbox') return []
  return Array.from(draft.checks)
}
function asList(data: unknown): JsonMap[] {
  if (Array.isArray(data)) return data as JsonMap[]
  const obj = data as JsonMap
  if (Array.isArray(obj?.items)) return obj.items
  if (Array.isArray(obj?.skills)) return obj.skills
  return []
}
async function loadSkills() {
  try {
    const d = domain.value.trim()
    if (d && !activeSource.value) activeSource.value = d
    const qs = d ? `?domain=${encodeURIComponent(d)}` : ''
    const data = await readJson(await fetch(`/platform/frontend/skills${qs}`, { headers: headers(false) }))
    skills.value = asList(data)
    error.value = ''
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
  }
}
async function loadPackages() {
  try {
    const params = new URLSearchParams({ limit: '200' })
    const d = (pkgDomain.value || domain.value).trim()
    if (d) params.set('domain', d)
    if (pkgStatus.value) params.set('status', pkgStatus.value)
    const data = await readJson(await fetch(`/platform/frontend/skills/packages?${params}`, { headers: headers(false) }))
    packages.value = asList(data)
    for (const p of packages.value) draftFor(p)
  } catch (err) {
    setOutput(`加载失败: ${err instanceof Error ? err.message : String(err)}`, 'err')
  }
}
async function syncSkills() {
  try {
    const data = await readJson<JsonMap>(await fetch('/platform/frontend/skills/sync', { method: 'POST', headers: headers(false) }))
    const count = Array.isArray(data.synced) ? data.synced.length : 0
    await Promise.all([loadSkills(), loadPackages()])
    notifySuccess(`同步完成: ${count} 个 Skills`)
  } catch (err) {
    notifyError(err)
  }
}
async function toggleSkill(s: JsonMap, enable: boolean) {
  const action = enable ? 'enable' : 'disable'
  try {
    await readJson(await fetch(`/platform/frontend/skills/${encodeURIComponent(String(s.skill_id))}/${action}`, { method: 'POST', headers: headers(true), body: JSON.stringify({ domain: domain.value || sourceOf(s) }) }))
    s.enabled = enable
    notifySuccess(`${enable ? '已启用' : '已禁用'}: ${s.skill_id}`)
  } catch (err) {
    notifyError(err)
    loadSkills()
  }
}
async function testSkill(s: JsonMap) {
  const input = await promptDialog(`测试 Skill: ${s.skill_id}`, '输入参数 (JSON)', '{}')
  if (input === null) return
  let params: JsonMap = {}
  try {
    params = input.trim() ? JSON.parse(input) : {}
  } catch {
    notifyError('JSON 格式错误')
    return
  }
  try {
    const d = await readJson<JsonMap>(await fetch(`/platform/frontend/skills/${encodeURIComponent(String(s.skill_id))}/test`, { method: 'POST', headers: headers(true), body: JSON.stringify({ params }) }))
    setOutput(JSON.stringify(d, null, 2), 'ok')
    notifySuccess('测试完成')
  } catch (err) {
    notifyError(err)
  }
}
function onPickFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0] || null
  packageFile.value = file
  packageFileName.value = file?.name || ''
}
async function uploadPackage() {
  const d = (packageDomain.value || domain.value).trim()
  if (!d) {
    setOutput('请填写 Domain。', 'err')
    return
  }
  if (!packageFile.value) {
    setOutput('请选择 zip 包。', 'err')
    return
  }
  uploading.value = true
  setOutput('上传并校验中…', 'info')
  try {
    const fd = new FormData()
    fd.append('file', packageFile.value)
    const data = await readJson<JsonMap>(await fetch(`/platform/frontend/skills/packages/upload?domain=${encodeURIComponent(d)}`, { method: 'POST', headers: headers(false), body: fd }))
    setOutput(JSON.stringify(data, null, 2), 'ok')
    pkgDomain.value = d
    notifySuccess(`上传成功: ${data.skill_id}@${data.version}`)
    uploadOpen.value = false
    await loadPackages()
  } catch (err) {
    setOutput(`上传失败: ${err instanceof Error ? err.message : String(err)}`, 'err')
  } finally {
    uploading.value = false
  }
}
async function publishPackage(p: JsonMap) {
  try {
    const data = await readJson<JsonMap>(await fetch(`/platform/frontend/skills/packages/${encodeURIComponent(packageId(p))}/publish`, { method: 'POST', headers: headers(true), body: JSON.stringify({ permissions: collectPermissions(p) }) }))
    setOutput(JSON.stringify(data, null, 2), 'ok')
    await syncSkills()
    notifySuccess(`发布成功: ${data.skill_id || ''}@${data.version || ''}`)
  } catch (err) {
    setOutput(`发布失败: ${err instanceof Error ? err.message : String(err)}`, 'err')
  }
}
async function rejectPackage(p: JsonMap) {
  try {
    const data = await readJson<JsonMap>(await fetch(`/platform/frontend/skills/packages/${encodeURIComponent(packageId(p))}/reject`, { method: 'POST', headers: headers(true), body: JSON.stringify({ reason: rejectReason.value.trim() }) }))
    setOutput(JSON.stringify(data, null, 2), 'ok')
    await loadPackages()
    notifySuccess(`已拒绝 Package #${packageId(p)}`)
  } catch (err) {
    setOutput(`拒绝失败: ${err instanceof Error ? err.message : String(err)}`, 'err')
  }
}
async function savePackagePermissions(p: JsonMap) {
  try {
    const data = await readJson<JsonMap>(await fetch(`/platform/frontend/skills/packages/${encodeURIComponent(packageId(p))}/permissions`, { method: 'PATCH', headers: headers(true), body: JSON.stringify({ permissions: collectPermissions(p) }) }))
    setOutput(JSON.stringify(data, null, 2), 'ok')
    await syncSkills()
    notifySuccess(`权限已保存: Package #${packageId(p)}`)
  } catch (err) {
    setOutput(`保存权限失败: ${err instanceof Error ? err.message : String(err)}`, 'err')
  }
}
async function deletePackage(p: JsonMap) {
  if (!(await confirmDialog(`删除 Package #${packageId(p)}？`, { title: '删除包', danger: true }))) return
  try {
    const res = await fetch(`/platform/frontend/skills/packages/${encodeURIComponent(packageId(p))}`, { method: 'DELETE', headers: headers(false) })
    if (!res.ok) throw new Error(await res.text())
    setOutput(`Package #${packageId(p)} 已删除`, 'ok')
    await Promise.all([loadPackages(), loadSkills()])
    notifySuccess(`已删除 Package #${packageId(p)}`)
  } catch (err) {
    setOutput(`删除失败: ${err instanceof Error ? err.message : String(err)}`, 'err')
  }
}
onMounted(() => {
  pkgDomain.value = domain.value
  packageDomain.value = domain.value
  loadSkills()
  loadPackages()
})
</script>

<template>
  <div class="skills-workspace">
    <div class="filter-pane">
      <h3>来源</h3>
      <div class="filter-item" :class="{ active: !activeSource && !activeStatus }" @click="activeSource = ''; activeStatus = ''">
        <span>全部</span><span class="filter-count">{{ skills.length }}</span>
      </div>
      <div v-for="[src, c] in sourceCounts" :key="src" class="filter-item" :class="{ active: activeSource === src }" @click="activeSource = src; activeStatus = ''">
        <span>{{ labelSource(src) }}</span><span class="filter-count">{{ c }}</span>
      </div>
      <h3 style="margin-top:16px">状态</h3>
      <div class="filter-item" :class="{ active: activeStatus === 'enabled' }" @click="activeStatus = 'enabled'; activeSource = ''">
        <span>已启用</span><span class="filter-count">{{ enabledCount }}</span>
      </div>
      <div class="filter-item" :class="{ active: activeStatus === 'disabled' }" @click="activeStatus = 'disabled'; activeSource = ''">
        <span>已禁用</span><span class="filter-count">{{ disabledCount }}</span>
      </div>
    </div>

    <div class="skills-area">
      <div class="skills-toolbar">
        <input v-model="keyword" placeholder="搜索 Skill 名称或描述…" />
        <span class="skill-count">{{ visibleSkills.length }} 个 Skills</span>
        <button class="btn btn-ghost btn-sm" @click="syncSkills">🔄 同步扫描</button>
        <button class="btn btn-primary btn-sm" @click="loadSkills">刷新</button>
      </div>

      <section class="package-panel">
        <div class="package-head">
          <div class="package-title">Skill Packages</div>
          <div class="package-tools">
            <select v-model="pkgStatus" @change="loadPackages">
              <option value="">全部状态</option>
              <option value="validated">待发布</option>
              <option value="published">已发布</option>
              <option value="rejected">已拒绝</option>
            </select>
            <input v-model="pkgDomain" placeholder="Domain" @keydown.enter="loadPackages" />
            <input v-model="rejectReason" placeholder="拒绝原因" />
            <button class="btn btn-ghost btn-sm" @click="loadPackages">刷新包</button>
            <button class="btn btn-primary btn-sm" @click="openUpload">+ 上传 Skill 包</button>
          </div>
        </div>
        <div class="package-body">
          <table class="package-table">
            <thead>
              <tr><th>Package</th><th>状态</th><th>权限</th><th>校验</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-if="!packages.length"><td colspan="5" class="empty">暂无包记录</td></tr>
              <tr v-for="p in packages" :key="packageId(p)">
                <td>
                  <div class="pkg-id">#{{ packageId(p) }} {{ p.skill_id }}@{{ p.version }}</div>
                  <div class="pkg-sub">{{ p.domain }} · {{ p.source || 'uploaded' }} · {{ String(p.created_at || '').slice(0, 19) }}</div>
                </td>
                <td><span class="pkg-status" :class="statusClass(p.status)">{{ statusText(p.status) }}</span></td>
                <td>
                  <div class="pkg-perms" :class="{ custom: draftFor(p).mode === 'custom' }">
                    <select v-model="draftFor(p).mode">
                      <option value="default">source默认</option>
                      <option value="sandbox">沙箱</option>
                      <option value="custom">指定权限</option>
                    </select>
                    <div v-if="draftFor(p).mode === 'custom'" class="pkg-perm-checks">
                      <label v-for="perm in PACKAGE_PERMISSIONS" :key="perm">
                        <input type="checkbox" :checked="draftFor(p).checks.has(perm)" @change="togglePermCheck(p, perm)" /> {{ perm }}
                      </label>
                    </div>
                    <div class="pkg-sub">{{ permText(p.granted_permissions) }}</div>
                  </div>
                </td>
                <td>
                  <div v-if="(p.validation_errors as string[] | undefined)?.length" class="pkg-errors">{{ (p.validation_errors as string[]).join('\n') }}</div>
                  <span v-else class="pkg-pass">通过</span>
                </td>
                <td>
                  <div class="pkg-actions">
                    <template v-if="p.status === 'validated'">
                      <button class="btn btn-primary btn-sm" @click="publishPackage(p)">发布</button>
                      <button class="btn btn-danger btn-sm" @click="rejectPackage(p)">拒绝</button>
                    </template>
                    <button class="btn btn-ghost btn-sm" @click="savePackagePermissions(p)">保存权限</button>
                    <button class="btn btn-danger btn-sm" @click="deletePackage(p)">删除</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <div class="skills-grid">
        <p v-if="error" class="empty">加载失败: {{ error }}</p>
        <div v-else-if="!visibleSkills.length" class="empty">暂无 Skills</div>
        <div v-for="s in visibleSkills" :key="s.skill_id as string" class="skill-card" :class="{ disabled: !isEnabled(s) }">
          <div class="skill-head">
            <div style="display:flex;gap:10px;align-items:flex-start">
              <div class="skill-icon">⚡</div>
              <div>
                <div class="skill-name">{{ s.display_name || s.name || s.skill_id }}</div>
                <div class="skill-id">{{ s.skill_id }}</div>
              </div>
            </div>
            <div class="skill-toggle">
              <label class="toggle" :title="isEnabled(s) ? '点击禁用' : '点击启用'">
                <input type="checkbox" :checked="isEnabled(s)" @change="toggleSkill(s, ($event.target as HTMLInputElement).checked)" />
                <span class="toggle-slider"></span>
              </label>
            </div>
          </div>
          <div v-if="s.description" class="skill-desc">{{ s.description }}</div>
          <div class="skill-meta">
            <span class="skill-tag" :class="sourceOf(s) === 'global' || sourceOf(s) === 'platform' ? 'platform' : 'domain'">{{ labelSource(sourceOf(s)) }}</span>
            <span class="skill-tag" :class="isEnabled(s) ? 'enabled' : 'disabled'">{{ isEnabled(s) ? '已启用' : '已禁用' }}</span>
            <span v-if="s.version" class="skill-tag">v{{ s.version }}</span>
          </div>
          <div class="skill-actions">
            <button class="btn btn-ghost btn-sm" @click="testSkill(s)">测试</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 上传 Skill 包弹窗 -->
    <div v-if="uploadOpen" class="skill-modal" @click.self="uploadOpen = false">
      <div class="skill-card">
        <div class="skill-card-head"><div class="skill-card-title">上传 Skill 包</div><button class="btn btn-ghost btn-sm" @click="uploadOpen = false">关闭</button></div>
        <div class="skill-card-body">
          <div class="field"><label>业务域</label><input v-model="packageDomain" placeholder="platform / training" /></div>
          <div class="field"><label>Skill 包 (.zip)</label>
            <label class="file-pick">
              <input type="file" accept=".zip,application/zip" @change="onPickFile" />
              <span>{{ packageFileName || '点击选择 zip 包…' }}</span>
            </label>
          </div>
          <pre v-if="pkgOutput" class="package-output" :class="pkgOutput.kind">{{ pkgOutput.text }}</pre>
          <p class="upload-hint">上传后会自动校验；校验通过的包在下方列表「发布」后即成为可用 Skill。</p>
        </div>
        <div class="skill-card-actions">
          <button class="btn btn-ghost" @click="uploadOpen = false">取消</button>
          <button class="btn btn-primary" :disabled="uploading || !packageFile" @click="uploadPackage">{{ uploading ? '上传校验中…' : '上传并校验' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.skills-workspace { flex: 1; display: grid; grid-template-columns: 220px 1fr; overflow: hidden; }
.filter-pane { border-right: 1px solid var(--border); background: var(--panel); display: flex; flex-direction: column; overflow-y: auto; padding: 14px; }
.filter-pane h3 { font-size: 12px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: .05em; margin-bottom: 10px; }
.filter-item { padding: 8px 10px; border-radius: 8px; font-size: 12px; cursor: pointer; margin-bottom: 2px; transition: .12s; display: flex; align-items: center; justify-content: space-between; }
.filter-item:hover { background: #f1f5f9; }
.filter-item.active { background: #dbeafe; color: var(--blue-dim); font-weight: 600; }
.filter-count { font-size: 11px; color: var(--muted); background: #f1f5f9; padding: 1px 6px; border-radius: 99px; }
.filter-item.active .filter-count { background: #bfdbfe; color: var(--blue-dim); }

.skills-area { display: flex; flex-direction: column; overflow: hidden; }
.skills-toolbar { padding: 12px 16px; border-bottom: 1px solid var(--border); background: #f8fafc; display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.skills-toolbar input { flex: 1; height: 34px; }
.skill-count { font-size: 12px; color: var(--muted); white-space: nowrap; }

.package-panel { border-bottom: 1px solid var(--border); background: var(--panel); display: flex; flex-direction: column; max-height: 46vh; flex-shrink: 0; }
.package-head { padding: 11px 16px; display: flex; align-items: center; gap: 10px; border-bottom: 1px solid var(--border); }
.package-title { font-size: 13px; font-weight: 700; flex: 1; }
.package-tools { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.package-tools input, .package-tools select { height: 32px; font-size: 12px; }
.package-upload { display: grid; grid-template-columns: 170px minmax(180px, 1fr) auto; gap: 8px; padding: 12px 16px; border-bottom: 1px solid var(--border); background: #f8fafc; align-items: center; }
.package-upload input { height: 32px; font-size: 12px; }
.file-pick { display: flex; align-items: center; min-width: 0; border: 1px solid var(--border); border-radius: 7px; background: #fff; height: 32px; padding: 0 10px; font-size: 12px; color: var(--muted); cursor: pointer; overflow: hidden; }
.file-pick input { display: none; }
.file-pick span { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.package-output { margin: 0 16px 12px; padding: 9px 11px; border-radius: 8px; font-size: 12px; font-family: ui-monospace, Menlo, Consolas, monospace; white-space: pre-wrap; max-height: 150px; overflow: auto; border: 1px solid var(--border); background: #f8fafc; color: var(--text); }
.package-output.ok { background: #f0fdf4; border-color: #bbf7d0; color: #166534; }
.package-output.err { background: #fef2f2; border-color: #fecaca; color: #991b1b; }
.package-body { overflow: auto; padding: 0 16px 14px; }
.package-table { width: 100%; border-collapse: collapse; font-size: 12px; min-width: 860px; }
.package-table th { text-align: left; padding: 9px 10px; color: var(--muted); font-size: 11px; font-weight: 700; border-bottom: 1px solid var(--border); background: #fff; position: sticky; top: 0; z-index: 1; }
.package-table td { padding: 10px; border-bottom: 1px solid #f1f5f9; vertical-align: top; }
.pkg-id { font-family: ui-monospace, Menlo, Consolas, monospace; font-weight: 700; color: var(--text); }
.pkg-sub { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 11px; color: var(--muted); margin-top: 2px; word-break: break-all; }
.pkg-status { display: inline-flex; align-items: center; border-radius: 99px; padding: 2px 8px; font-size: 10px; font-weight: 700; }
.pkg-status.validated { background: #fef3c7; color: #92400e; }
.pkg-status.published { background: #dcfce7; color: #166534; }
.pkg-status.rejected { background: #fee2e2; color: #991b1b; }
.pkg-status.other { background: #f1f5f9; color: #475569; }
.pkg-errors { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 11px; color: var(--red); white-space: pre-wrap; max-width: 280px; }
.pkg-pass { color: var(--green); font-size: 12px; }
.pkg-actions { display: flex; gap: 6px; flex-wrap: wrap; }
.pkg-perms { display: grid; grid-template-columns: 1fr; gap: 5px; min-width: 150px; }
.pkg-perms select { height: 30px; font-size: 12px; }
.pkg-perm-checks { display: flex; gap: 6px; flex-wrap: wrap; color: var(--muted); font-size: 11px; }
.pkg-perm-checks label { display: inline-flex; align-items: center; gap: 3px; }

.skills-grid { flex: 1; overflow-y: auto; padding: 16px; display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 14px; align-content: start; }
.skill-card { background: var(--panel); border: 1px solid var(--border); border-radius: 12px; padding: 16px; display: flex; flex-direction: column; gap: 10px; transition: .15s; }
.skill-card:hover { border-color: var(--blue); box-shadow: 0 0 0 3px #dbeafe; }
.skill-card.disabled { opacity: .6; }
.skill-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.skill-icon { width: 36px; height: 36px; border-radius: 9px; background: linear-gradient(135deg, #dbeafe, #e0e7ff); display: flex; align-items: center; justify-content: center; font-size: 18px; flex-shrink: 0; }
.skill-name { font-size: 13px; font-weight: 700; line-height: 1.3; }
.skill-id { font-size: 11px; color: var(--muted); font-family: ui-monospace, Menlo, Consolas, monospace; }
.skill-toggle { flex-shrink: 0; }
.toggle { position: relative; display: inline-block; width: 36px; height: 20px; }
.toggle input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; cursor: pointer; inset: 0; background: #cbd5e1; border-radius: 99px; transition: .2s; }
.toggle-slider::before { content: ""; position: absolute; width: 14px; height: 14px; left: 3px; top: 3px; background: #fff; border-radius: 50%; transition: .2s; }
.toggle input:checked + .toggle-slider { background: var(--blue); }
.toggle input:checked + .toggle-slider::before { transform: translateX(16px); }
.skill-desc { font-size: 12px; color: var(--muted); line-height: 1.5; }
.skill-meta { display: flex; gap: 6px; flex-wrap: wrap; }
.skill-tag { font-size: 10px; font-weight: 600; padding: 2px 7px; border-radius: 99px; background: #f1f5f9; color: #475569; }
.skill-tag.platform { background: #dbeafe; color: #1d4ed8; }
.skill-tag.domain { background: #e0f2fe; color: #0369a1; }
.skill-tag.enabled { background: #dcfce7; color: #166534; }
.skill-tag.disabled { background: #fee2e2; color: #dc2626; }
.skill-actions { display: flex; gap: 6px; }
.empty { padding: 30px; text-align: center; color: var(--muted); font-size: 12px; grid-column: 1 / -1; }

@media (max-width: 980px) {
  .skills-workspace { grid-template-columns: 1fr; overflow: visible; }
  .filter-pane { border-right: 0; border-bottom: 1px solid var(--border); }
  .skills-area { overflow: visible; }
  .package-panel { max-height: none; }
}

/* 上传 Skill 包弹窗 */
.skill-modal { position: fixed; inset: 0; background: rgba(15, 23, 42, .45); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 28px; }
.skill-card { background: #fff; border-radius: 16px; width: 480px; max-width: 96vw; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 18px 44px rgba(15, 23, 42, .14); overflow: hidden; }
.skill-card-head { display: flex; align-items: center; gap: 12px; padding: 16px 20px; border-bottom: 1px solid var(--border); }
.skill-card-title { font-size: 16px; font-weight: 700; flex: 1; }
.skill-card-body { padding: 18px 20px; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; }
.skill-card-body .field { display: flex; flex-direction: column; gap: 5px; }
.skill-card-body .field > label { font-size: 12px; font-weight: 600; color: var(--muted); }
.skill-card-body .file-pick { height: 38px; }
.upload-hint { font-size: 12px; color: var(--muted); line-height: 1.5; }
.skill-card-actions { display: flex; gap: 8px; justify-content: flex-end; border-top: 1px solid var(--border); padding: 14px 20px; }
</style>
