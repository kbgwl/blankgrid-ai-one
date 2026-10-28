<template>
  <div class="library">
    <header class="lib-head">
      <div>
        <div class="lib-eyebrow">COMPANY OUTPUT · 成果库</div>
        <h1 class="lib-title">公司产出的每一份成果</h1>
        <p class="lib-sub">通过评审的交付物自动归档。点左边任意一份,右侧直接读正文——先看值不值,再决定要不要下载。</p>
      </div>
      <div class="lib-right">
        <div class="lib-stats">
          <div class="ls"><b>{{ approvedCount }}</b><span>已通过</span></div>
          <div class="ls"><b>{{ reviewCount }}</b><span>待评审</span></div>
          <div class="ls"><b>{{ avgScore || '—' }}</b><span>平均分</span></div>
        </div>
        <div class="lib-tools">
          <el-select v-model="goalId" placeholder="全部项目" clearable size="small" class="goal-select">
            <el-option v-for="g in goals" :key="g.goal.id" :label="g.goal.title" :value="g.goal.id" />
          </el-select>
          <el-input v-model="q" placeholder="搜索标题或内容" clearable size="small" class="search">
            <template #prefix><span class="search-icon">⌕</span></template>
          </el-input>
        </div>
      </div>
      <span v-if="stale" class="stale-note">最新一次刷新没成功,看到的是上一次的结果</span>
    </header>

    <div v-if="loadError" class="empty glass load-fail">
      <div class="empty-mark">!</div>
      <p>{{ loadError }}</p>
      <el-button size="small" plain :loading="loading" @click="load">重试</el-button>
    </div>

    <div v-else-if="!all.length && !loading" class="empty glass">
      <div class="empty-mark">稿</div>
      <p>还没有成果。下达一个目标,团队通过评审的产出会自动归档到这里。</p>
      <el-button size="small" type="primary" plain @click="$router.push('/')">去下达目标</el-button>
    </div>

    <div v-else class="lib-shell">
      <!-- 第一栏:分组筛选,数量随其它条件实时联动 -->
      <aside class="filters glass">
        <div v-for="g in groups" :key="g.key" class="fgroup">
          <h4>{{ g.label }}</h4>
          <div class="flist">
            <button v-for="o in g.options" :key="g.key + '-' + o.value"
                    class="frow" :class="{ on: pick[g.key] === o.value }"
                    @click="toggle(g.key, o.value)">
              <span class="fname">{{ o.label }}</span>
              <span class="c">{{ o.count }}</span>
            </button>
          </div>
        </div>
        <button v-if="hasFilter" class="f-clear" @click="clearFilters">清空筛选</button>
      </aside>

      <!-- 第二栏:成果清单 -->
      <section class="art-list glass">
        <div v-if="loading && !filtered.length" class="list-skel">
          <div v-for="n in 5" :key="'sk' + n" class="sk-row"><i class="sk"></i><i class="sk w-40"></i></div>
        </div>
        <p v-else-if="!filtered.length" class="list-none">没有符合条件的成果,换个筛选条件试试。</p>
        <article v-for="(a, i) in filtered" :key="a.id" class="arow"
                 :class="{ on: a.id === activeId, 'is-new': newIds.has(a.id) }"
                 :style="{ '--i': i }" @click="openArtifact(a.id)">
          <h4 class="ar-title">{{ a.title }}</h4>
          <p class="ar-prev">{{ preview(a.content) }}</p>
          <div class="am">
            <span class="dept-tag" :style="{ '--dept': deptVar(a) }">{{ deptName(a) }}</span>
            <span class="type-tag">{{ a.type || '文档' }}</span>
            <span class="am-time">{{ fmt(a.createdAt) }}</span>
            <span class="score-pill" :class="scoreClass(a)">{{ a.score || '—' }}</span>
          </div>
        </article>
      </section>

      <!-- 第三栏:常驻阅读面板 -->
      <aside class="reader glass" ref="readerEl">
        <div v-if="!active" class="rd-empty">
          <div class="rd-empty-mark">读</div>
          <p>选一份成果,这里直接展开正文。</p>
        </div>
        <template v-else>
          <header class="reader-head">
            <div class="rd-eyebrow">{{ goalName }} · {{ deptName(active) }} · {{ active.type || '文档' }}</div>
            <h2 class="rd-title">{{ active.title }}</h2>
            <div class="rd-meta">
              <span class="dept-tag" :style="{ '--dept': deptVar(active) }">{{ authorName }}</span>
              <span>v{{ active.version }}</span>
              <span>{{ fmtFull(active.createdAt) }}</span>
              <span class="score-pill" :class="scoreClass(active)" :key="'p' + active.id">{{ active.score || '—' }}</span>
            </div>
          </header>
          <div class="reader-body" :key="active.id">
            <div class="qa" :class="{ fail: !passed(active) }">
              <div class="qa-top">
                <span>QUALITY REVIEW</span>
                <span>{{ active.score || 0 }} / {{ threshold }} · {{ passed(active) ? '通过' : '未通过' }}</span>
              </div>
              <div class="qa-bar"><i :style="{ '--w': Math.min(100, active.score || 0) + '%' }"></i></div>
              <p>{{ active.reviewNotes || '评审没有留下额外意见。' }}</p>
            </div>
            <div v-if="editing" class="rd-edit">
              <el-input v-model="draft" type="textarea" :rows="22" resize="vertical" />
              <div class="rd-edit-actions">
                <span class="hint">保存后标记为「人工修订」,评审分只作历史参考。</span>
                <div class="btns">
                  <el-button size="small" @click="toggleEdit">取消</el-button>
                  <el-button type="primary" size="small" :loading="savingEdit" :disabled="!draft.trim()" @click="saveEdit">保存</el-button>
                </div>
              </div>
            </div>
            <article v-else class="md-content rd-md" v-html="html"></article>
            <footer class="rd-foot">
              <button class="rd-btn" @click="toggleEdit">{{ editing ? '取消编辑' : '✎ 编辑正文' }}</button>
              <button v-if="active.status !== 'APPROVED'" class="rd-btn" @click="adoptActive">✓ 定为最终版</button>
              <el-dropdown trigger="click" @command="(f) => download(active, f)">
                <button class="rd-btn">↓ 下载 <i class="caret">▾</i></button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="md">Markdown</el-dropdown-item>
                    <el-dropdown-item command="docx" divided>Word · .docx</el-dropdown-item>
                    <el-dropdown-item command="pdf">PDF · .pdf</el-dropdown-item>
                    <el-dropdown-item command="pptx">PPT · .pptx</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <el-dropdown trigger="click" @command="(f) => saveToDir(active, f)">
                <button class="rd-btn">⇥ 存到落盘目录 <i class="caret">▾</i></button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="all">全部格式</el-dropdown-item>
                    <el-dropdown-item command="docx" divided>只要 Word</el-dropdown-item>
                    <el-dropdown-item command="pdf">只要 PDF</el-dropdown-item>
                    <el-dropdown-item command="pptx">只要 PPT</el-dropdown-item>
                    <el-dropdown-item command="md">只要 Markdown</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <button class="rd-btn quiet" @click="copyLink(active)">复制链接</button>
              <router-link v-if="active.goalId" class="rd-btn quiet" :to="'/goals/' + active.goalId">查看来源项目</router-link>
            </footer>
          </div>
        </template>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, reactive, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { motionOk } from '../utils/motion'
import { api, bus } from '../api'
import { renderMarkdown } from '../utils/markdown'

const route = useRoute()
const router = useRouter()

const all = ref([])
const goals = ref([])
const deptOf = ref({})
const q = ref('')
const goalId = ref(null)
const threshold = ref(85)
const activeId = ref(null)
const loading = ref(false)
const loadError = ref('')
const stale = ref(false)
const readerEl = ref(null)

const pick = reactive({ status: '', dept: '', type: '', band: '' })

const bands = [
  { label: '95 及以上', value: 'a', test: s => s >= 95 },
  { label: '90 – 94', value: 'b', test: s => s >= 90 && s < 95 },
  { label: '85 – 89', value: 'c', test: s => s >= 85 && s < 90 },
  { label: '低于 85', value: 'd', test: s => s > 0 && s < 85 }
]

/* 每一组的候选按“除本组外的其它条件”计数,勾任何一项都不会让自己消失 */
function slice(except) {
  const kw = q.value.trim().toLowerCase()
  return all.value.filter(a => {
    if (kw && !`${a.title || ''} ${a.content || ''}`.toLowerCase().includes(kw)) return false
    if (goalId.value && String(a.goalId) !== String(goalId.value)) return false
    if (except !== 'status' && pick.status && a.status !== pick.status) return false
    if (except !== 'dept' && pick.dept && deptName(a) !== pick.dept) return false
    if (except !== 'type' && pick.type && (a.type || '文档') !== pick.type) return false
    if (except !== 'band' && pick.band) {
      const b = bands.find(x => x.value === pick.band)
      if (b && !b.test(a.score || 0)) return false
    }
    return true
  })
}

const statusLabels = { APPROVED: '已通过', REVIEW: '待评审', REJECTED: '未通过', DRAFT: '草稿' }

const groups = computed(() => {
  const st = slice('status')
  const dp = slice('dept')
  const ty = slice('type')
  const bd = slice('band')
  const count = (list, fn) => {
    const m = new Map()
    for (const a of list) { const k = fn(a); if (!k) continue; m.set(k, (m.get(k) || 0) + 1) }
    return [...m.entries()]
  }
  const gs = []
  gs.push({
    key: 'status', label: '状态',
    options: [{ label: '所有成果', value: '', count: st.length }]
      .concat(count(st, a => a.status).map(([v, c]) => ({ label: statusLabels[v] || v, value: v, count: c })))
  })
  const depts = count(dp, a => deptName(a))
  if (depts.length) gs.push({ key: 'dept', label: '部门', options: [{ label: '全部部门', value: '', count: dp.length }]
    .concat(depts.map(([v, c]) => ({ label: v, value: v, count: c }))) })
  const types = count(ty, a => a.type || '文档')
  if (types.length) gs.push({ key: 'type', label: '类型', options: [{ label: '全部类型', value: '', count: ty.length }]
    .concat(types.map(([v, c]) => ({ label: v, value: v, count: c }))) })
  gs.push({
    key: 'band', label: '评分',
    options: [{ label: '不限分数', value: '', count: bd.length }]
      .concat(bands.map(b => ({ label: b.label, value: b.value, count: bd.filter(a => b.test(a.score || 0)).length })))
      .filter(o => o.count || o.value === '')
  })
  return gs
})

const hasFilter = computed(() => Object.values(pick).some(Boolean))

const filtered = computed(() => slice())

const active = computed(() => all.value.find(a => a.id === activeId.value) || null)
const approvedCount = computed(() => all.value.filter(a => a.status === 'APPROVED').length)
const reviewCount = computed(() => all.value.filter(a => a.status === 'REVIEW').length)
const avgScore = computed(() => {
  const scored = all.value.filter(a => a.status === 'APPROVED' && a.score)
  if (!scored.length) return 0
  return Math.round(scored.reduce((n, a) => n + a.score, 0) / scored.length)
})
const goalName = computed(() => {
  const a = active.value
  if (!a) return ''
  const g = goals.value.find(x => x.goal.id === a.goalId)
  return g ? 'GOAL · ' + String(g.goal.id).padStart(3, '0') : '成果'
})
const authorName = computed(() => active.value?.author || '—')
// 面板已给出标题,正文首个 H1 通常是同一句,去掉避免重复
const html = computed(() => renderMarkdown((active.value?.content || '').replace(/^\s*#\s+[^\n]*(?:\n+|$)/, '')))

/* ===== 人工修订:在这里直接改正文,不必回到项目里 ===== */
const editing = ref(false)
const draft = ref('')
const savingEdit = ref(false)

function toggleEdit() {
  if (!active.value) return
  editing.value = !editing.value
  draft.value = editing.value ? (active.value.content || '') : ''
}

async function saveEdit() {
  if (!active.value) return
  savingEdit.value = true
  try {
    const r = await api.editArtifact(active.value.id, { content: draft.value })
    const i = all.value.findIndex(a => a.id === active.value.id)
    if (i >= 0) all.value[i] = { ...all.value[i], ...r }
    editing.value = false
    ElMessage({ message: '已保存为人工修订版', type: 'success', plain: true })
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '保存失败')
  } finally { savingEdit.value = false }
}

async function adoptActive() {
  if (!active.value) return
  try {
    const r = await api.adoptArtifact(active.value.id)
    const i = all.value.findIndex(a => a.id === active.value.id)
    if (i >= 0) all.value[i] = { ...all.value[i], ...r }
    ElMessage({ message: '已定为最终版', type: 'success', plain: true })
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '操作失败')
  }
}

watch(activeId, () => { editing.value = false; draft.value = '' })

function toggle(key, value) {
  pick[key] = pick[key] === value ? '' : value
}
function clearFilters() {
  pick.status = pick.dept = pick.type = pick.band = ''
}

function deptName(a) {
  const d = deptOf.value[a.author]
  return d ? d.name : '未归部门'
}
function deptVar(a) {
  const d = deptOf.value[a.author]
  return d ? 'var(--dept-' + d.colorKey + ')' : 'var(--glow-cyan)'
}
function passed(a) { return (a.score || 0) >= threshold.value }
function scoreClass(a) {
  if (!a.score) return 'na'
  return passed(a) ? 'good' : 'fail'
}
function preview(s) {
  if (!s) return ''
  const clean = String(s)
    .replace(/^\s*#\s+[^\n]*(?:\n+|$)/, ' ')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^\s*\|?[\s:|-]{3,}\|?\s*$/gm, ' ')
    .replace(/^\s{0,3}#{1,6}\s+/gm, ' ')
    .replace(/^\s{0,3}[-*+]\s+/gm, ' ')
    .replace(/[|>*_`#]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return clean.length > 96 ? clean.slice(0, 96) + '……' : clean
}
function fmt(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}
function fmtFull(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}

function select(id) { activeId.value = id }

function openArtifact(id) {
  select(id)
  // 窄屏时阅读面板在列表下方,手动选完要滚过去
  if (window.innerWidth <= 1180 && readerEl.value) {
    readerEl.value.scrollIntoView({ behavior: motionOk() ? 'smooth' : 'auto', block: 'start' })
  }
}

/* 选中项写进地址,复制出来的链接和刷新后的页面都能停在同一份成果上 */
watch(activeId, id => {
  if (id) router.replace({ query: { ...route.query, art: String(id) } })
})

async function copyLink(a) {
  const url = `${location.origin}${location.pathname}#/artifacts?art=${a.id}`
  try {
    await navigator.clipboard.writeText(url)
    ElMessage({ message: '链接已复制,粘给谁都能直接打开这一份', type: 'success', plain: true })
  } catch (e) {
    ElMessage({ message: url, plain: true, duration: 6000 })
  }
}

async function download(a, format) {
  try { await api.downloadArtifact(a.id, a.title, format || 'md') } catch (e) { ElMessage.error('下载失败') }
}

/* 落盘和下载是两件事:下载是把文件交给你,落盘是让它待在你能找到的目录里 */
async function saveToDir(a, format) {
  try {
    const r = await api.exportArtifactToDir(a.id, format || 'all')
    if (r.ok) ElMessage({ message: '已写入 ' + (r.dir || '') + '(' + r.files + ' 个文件)', type: 'success', plain: true, duration: 4600 })
    else ElMessage.error(r.error || '写入失败')
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '写入失败')
  }
}

async function loadDepartments() {
  try {
    const org = await api.org()
    const map = {}
    for (const d of org.departments || []) {
      for (const e of d.employees || []) map[e.name] = { name: d.name, colorKey: d.colorKey || 'tech' }
    }
    deptOf.value = map
  } catch (e) { /* 部门接口没就绪时按「未归部门」列出,不影响读成果 */ }
}

let reqSeq = 0
async function load() {
  const seq = ++reqSeq
  loading.value = true
  loadError.value = ''
  try {
    const [arts, gs, st] = await Promise.all([api.artifacts(), api.goals(), api.stats()])
    if (seq !== reqSeq) return
    markNew(arts || [])
    all.value = arts || []
    goals.value = gs || []
    threshold.value = st.qualityThreshold || 85
    stale.value = false
    if (!activeId.value && all.value.length) {
      const want = Number(route.query.art)
      const hit = want && all.value.find(a => a.id === want)
      select(hit ? hit.id : all.value[0].id)
    }
  } catch (e) {
    if (seq !== reqSeq) return
    if (all.value.length) { stale.value = true; return }
    loadError.value = e.code === 'ECONNABORTED' || e.code === 'ETIMEDOUT'
      ? '后端长时间没有响应,可能是服务未启动或版本过旧'
      : '成果载入失败'
  } finally { if (seq === reqSeq) loading.value = false }
}

/* 新归档的成果:行落地 + 分数盖章 */
const newIds = ref(new Set())
let knownIds = null
let newTimer = null

function markNext(list) { knownIds = new Set(list.map(a => a.id)) }

function markNew(list) {
  if (knownIds === null || !motionOk()) { markNext(list); return }
  const hot = new Set()
  for (const a of list) if (!knownIds.has(a.id)) hot.add(a.id)
  markNext(list)
  if (hot.size) {
    newIds.value = hot
    clearTimeout(newTimer)
    newTimer = setTimeout(() => { newIds.value = new Set() }, 1500)
  }
}

/* 列表刷新后,当前选中项可能已被筛掉或删掉;留在最后一份能读的成果上 */
watch(filtered, list => {
  if (list.length && !list.some(a => a.id === activeId.value)) select(list[0].id)
})

let off = null
let timer = null
onMounted(() => {
  load()
  loadDepartments()
  off = bus.on(evt => {
    if (['goal-update', 'goal-completed', 'task-update'].includes(evt.type)) {
      clearTimeout(timer)
      timer = setTimeout(load, 1000)
    }
  })
})
onBeforeUnmount(() => { off && off(); clearTimeout(timer); clearTimeout(newTimer) })
</script>

<style scoped>
.library { display: flex; flex-direction: column; gap: 18px; max-width: 1420px; margin: 0 auto; padding-bottom: 30px; }
.lib-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 30px; padding: 16px 0 4px; flex-wrap: wrap; }
.lib-eyebrow { font-family: var(--font-mono); font-size: 10px; letter-spacing: .3em; color: var(--text-faint); }
.lib-title { margin: 14px 0 0; font-size: clamp(26px, 3vw, 38px); font-weight: 200; letter-spacing: .06em; }
.lib-sub { margin: 10px 0 0; font-size: 12px; color: var(--text-dim); letter-spacing: .05em; max-width: 560px; }
.lib-right { display: flex; flex-direction: column; align-items: flex-end; gap: 14px; }
.lib-stats { display: flex; gap: 34px; }
.ls { text-align: right; }
.ls b { display: block; font-family: var(--font-mono); font-size: 25px; font-weight: 200; color: var(--glow-cyan); }
.ls span { font-size: 9.5px; letter-spacing: .2em; color: var(--text-faint); }
.lib-tools { display: flex; gap: 10px; }
.goal-select { width: 200px; }
.search { width: 230px; }
.search-icon { font-size: 13px; color: var(--text-faint); }
.stale-note { font-size: 11px; color: var(--st-warn); letter-spacing: .06em; }

.empty { padding: 60px 20px; text-align: center; color: var(--text-faint); letter-spacing: .12em; font-size: 12.5px; }
.empty-mark {
  font-size: 32px; font-weight: 200; color: rgba(var(--rgb-ink), .1);
  border: 1px dashed var(--border-hair-strong); width: 72px; height: 72px; border-radius: 18px;
  display: grid; place-items: center; margin: 0 auto 16px;
}
.empty p { margin: 0 0 16px; }
.load-fail p { margin: 0 0 16px; }

.lib-shell { display: grid; grid-template-columns: 172px minmax(0, 1fr) 396px; gap: 15px; align-items: start; }

/* ===== 第一栏:筛选 ===== */
.filters { padding: 16px 16px 12px; position: sticky; top: 86px; }
.fgroup { margin-bottom: 18px; }
.fgroup h4 { margin: 0 0 6px; font-family: var(--font-mono); font-size: 9px; font-weight: 400;
             letter-spacing: .22em; color: var(--text-faint); }
.flist { display: flex; flex-direction: column; }
.frow {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  background: none; border: none; padding: 5px 7px; margin: 0 -7px; border-radius: 7px; cursor: pointer;
  font-family: var(--font-sans); font-size: 11.5px; letter-spacing: .04em; color: var(--text-dim);
  transition: color .3s var(--ease-quiet), background .3s var(--ease-quiet);
}
.frow:hover { color: var(--text-moon); background: var(--tint-soft); }
.frow.on { color: var(--glow-cyan); background: var(--glow-cyan-faint); }
.frow .c { font-family: var(--font-mono); font-size: 9px; color: var(--text-faint); }
.frow.on .c { color: var(--glow-cyan-dim); }
.f-clear {
  background: none; border: none; border-top: 1px solid var(--border-hair); padding: 9px 0 2px;
  width: 100%; cursor: pointer; font-size: 10.5px; letter-spacing: .16em; color: var(--text-faint);
  transition: color .3s var(--ease-quiet);
}
.f-clear:hover { color: var(--glow-cyan); }

/* ===== 第二栏:清单 ===== */
.art-list { overflow: hidden; }
.arow {
  display: block; padding: 13px 17px 12px; border-bottom: 1px solid var(--border-hair);
  cursor: pointer; position: relative; transition: background .35s var(--ease-quiet);
  animation: rowIn .5s var(--ease-out) both; animation-delay: calc(var(--i, 0) * 45ms);
}
@keyframes rowIn { from { opacity: 0; transform: translateY(9px); } to { opacity: 1; transform: none; } }
.arow:last-child { border-bottom: 0; }
.arow:hover { background: var(--tint-soft); }
.arow.on { background: var(--tint-soft); }
.arow.on::before {
  content: ""; position: absolute; left: 0; top: 10px; bottom: 10px; width: 2px; border-radius: 2px;
  background: var(--glow-cyan); box-shadow: 0 0 10px rgba(var(--rgb-glow), .55);
  animation: railIn .4s var(--ease-out) both;
}
@keyframes railIn { from { transform: scaleY(.1); opacity: 0; } to { transform: none; opacity: 1; } }
/* 刚归档进来的成果:整行落地,分数盖章 */
.arow.is-new { animation: newLand .85s var(--ease-out) both; }
@keyframes newLand {
  0% { opacity: 0; transform: translateY(14px); box-shadow: inset 0 0 0 1px rgba(var(--rgb-glow), .5); }
  55% { opacity: 1; transform: none; }
  100% { box-shadow: none; }
}
.arow.is-new .score-pill { animation: popScore .6s cubic-bezier(.34, 1.56, .64, 1) .2s both; }
.ar-title { margin: 0; font-family: var(--font-serif); font-size: 14px; font-weight: 500;
            line-height: 1.55; letter-spacing: .03em; color: var(--text-moon); }
.ar-prev { margin: 5px 0 0; font-size: 11px; line-height: 1.7; color: var(--text-faint);
           display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden; }
.am { display: flex; align-items: center; gap: 10px; margin-top: 8px; font-size: 9.5px; color: var(--text-faint); }
.am-time { font-family: var(--font-mono); }
.dept-tag { display: inline-flex; align-items: center; gap: 5px; font-size: 10px; color: var(--text-dim); white-space: nowrap; }
.dept-tag::before { content: ""; width: 5px; height: 5px; border-radius: 50%; background: var(--dept, var(--glow-cyan)); }
.type-tag { font-family: var(--font-mono); font-size: 9.5px; letter-spacing: .08em; color: var(--text-faint); }
.score-pill {
  margin-left: auto; font-family: var(--font-mono); font-size: 10px; padding: 1px 7px; border-radius: 6px;
  letter-spacing: .02em; color: var(--glow-cyan);
  border: 1px solid rgba(var(--rgb-glow), .3); background: var(--glow-cyan-faint);
}
.score-pill.fail { color: var(--st-warn); border-color: rgba(var(--rgb-warn), .3); background: rgba(var(--rgb-warn), .1); }
.score-pill.na { color: var(--text-faint); border-color: var(--border-hair); background: var(--chip-bg); }

.list-skel { padding: 16px 17px; display: flex; flex-direction: column; gap: 18px; }
.sk-row { display: flex; flex-direction: column; gap: 8px; }
.sk { height: 11px; border-radius: 6px; background: var(--tint-soft); animation: skShim 1.5s ease-in-out infinite; }
.sk.w-40 { width: 40%; height: 8px; }
.list-none { padding: 40px 20px; text-align: center; font-size: 12px; color: var(--text-faint); margin: 0; letter-spacing: .1em; }

/* ===== 第三栏:常驻阅读面板 ===== */
.reader { position: sticky; top: 86px; max-height: calc(100vh - 132px); overflow: auto; scrollbar-width: thin; }
.reader-head {
  padding: 17px 21px 13px; border-bottom: 1px solid var(--border-hair);
  position: sticky; top: 0; z-index: 2; background: var(--surface-grad); backdrop-filter: blur(14px);
  animation: riseIn .45s var(--ease-out) both;
}
.rd-eyebrow { font-family: var(--font-mono); font-size: 9px; letter-spacing: .16em; color: var(--text-faint); margin-bottom: 9px; }
.rd-title { font-family: var(--font-serif); font-size: 19px; font-weight: 500; line-height: 1.55;
            letter-spacing: .03em; margin: 0 0 11px; color: var(--text-moon); }
.rd-meta { display: flex; align-items: center; gap: 10px; font-size: 9.5px; color: var(--text-faint); flex-wrap: wrap; }
.rd-meta span:not(.dept-tag):not(.score-pill) { font-family: var(--font-mono); }
.rd-meta .score-pill { animation: popScore .55s cubic-bezier(.34, 1.56, .64, 1) .15s both; }
.reader-body { padding: 16px 21px 20px; animation: pageTurn .5s var(--ease-out) both; }

.qa { border: 1px solid rgba(var(--rgb-glow), .26); background: var(--glow-cyan-faint);
      border-radius: 10px; padding: 11px 14px 12px; margin-bottom: 17px; }
.qa.fail { border-color: rgba(var(--rgb-warn), .32); background: rgba(var(--rgb-warn), .08); }
.qa-top { display: flex; align-items: center; justify-content: space-between;
          font-family: var(--font-mono); font-size: 9px; letter-spacing: .12em; color: var(--glow-cyan); }
.qa.fail .qa-top { color: var(--st-warn); }
.qa-bar { height: 3px; border-radius: 3px; background: rgba(var(--rgb-ink), .08); margin: 9px 0 8px; overflow: hidden; }
.qa-bar i { display: block; height: 100%; border-radius: 3px; background: linear-gradient(90deg, rgba(var(--rgb-glow), .35), var(--glow-cyan));
            box-shadow: 0 0 8px rgba(var(--rgb-glow), .4); animation: fillTo .9s var(--ease-out) .1s both; }
.qa.fail .qa-bar i { background: linear-gradient(90deg, rgba(var(--rgb-warn), .3), var(--st-warn)); box-shadow: 0 0 8px rgba(var(--rgb-warn), .35); }
@keyframes fillTo { from { width: 0; } to { width: var(--w, 0%); } }
.qa p { margin: 0; font-size: 11.5px; line-height: 1.8; color: var(--text-dim); }

/* 成果正文用衬线,读起来像刊物而不是控制台 */
.rd-md { font-family: var(--font-serif); font-size: 13.5px; line-height: 2.05; color: var(--text-dim); }
.rd-md :deep(h1), .rd-md :deep(h2), .rd-md :deep(h3), .rd-md :deep(h4) {
  font-family: var(--font-serif); font-weight: 500; color: var(--text-moon); letter-spacing: .03em;
}
.rd-md :deep(h2) { font-size: 17px; margin: 26px 0 12px; }
.rd-md :deep(h3) { font-size: 14.5px; margin: 20px 0 9px; }
.rd-md :deep(strong) { color: var(--text-moon); font-weight: 600; }
.rd-md :deep(.md-table) { font-family: var(--font-serif); font-size: 12px; min-width: 0; }

.rd-foot { display: flex; align-items: center; gap: 8px; padding-top: 15px; border-top: 1px solid var(--border-hair); margin-top: 18px; flex-wrap: wrap; }
/* 人工修订:阅读区切成可写状态 */
.rd-edit { margin-top: 16px; }
.rd-edit :deep(.el-textarea__inner) {
  background: rgba(255,255,255,.02); color: var(--text-main);
  font-family: var(--font-mono); font-size: 12.5px; line-height: 1.9;
  border-color: var(--border-hair); box-shadow: none;
}
.rd-edit-actions { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 10px; }
.rd-edit-actions .hint { font-size: 11px; color: var(--text-faint); line-height: 1.6; }
.rd-edit-actions .btns { display: flex; gap: 8px; flex: none; }
.rd-btn {
  background: var(--chip-bg); border: 1px solid rgba(var(--rgb-glow), .3); color: var(--glow-cyan);
  font-family: var(--font-sans); font-size: 11px; letter-spacing: .1em; padding: 6px 13px; border-radius: 8px;
  cursor: pointer; text-decoration: none; transition: all .3s var(--ease-quiet);
}
.rd-btn:hover { border-color: rgba(var(--rgb-glow), .6); box-shadow: 0 0 14px rgba(var(--rgb-glow), .18); }
.rd-btn.quiet { color: var(--text-dim); border-color: var(--border-hair); background: none; }
.rd-btn.quiet:hover { color: var(--glow-cyan); border-color: rgba(var(--rgb-glow), .35); box-shadow: none; }

.rd-empty { padding: 70px 24px; text-align: center; color: var(--text-faint); font-size: 12px; letter-spacing: .12em; }
.rd-empty-mark {
  font-family: var(--font-serif); font-size: 26px; color: rgba(var(--rgb-ink), .12);
  border: 1px dashed var(--border-hair-strong); width: 62px; height: 62px; border-radius: 16px;
  display: grid; place-items: center; margin: 0 auto 16px;
}

@media (max-width: 1180px) {
  .lib-shell { grid-template-columns: 172px minmax(0, 1fr); }
  .reader { grid-column: 1 / -1; position: static; max-height: none; }
}
@media (max-width: 900px) {
  .lib-head { align-items: flex-start; }
  .lib-right { align-items: flex-start; width: 100%; }
  .lib-tools, .goal-select, .search { width: 100%; }
  .lib-tools { flex-direction: column; }
  .lib-shell { grid-template-columns: 1fr; }
  .filters { position: static; display: flex; gap: 26px; flex-wrap: wrap; }
  .fgroup { margin-bottom: 0; }
}
</style>
