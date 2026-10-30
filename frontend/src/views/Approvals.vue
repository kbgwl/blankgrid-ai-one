<template>
  <div class="page">
    <header class="head">
      <div>
        <h1>自主调用</h1>
        <p class="sub">
          模型自己决定要做什么,这里是它申请过、做过、以及凭什么这么做的地方。
        </p>
      </div>
      <div class="tally">
        <div class="t-item">
          <b :class="{ hot: pending > 0 }">{{ pending }}</b>
          <span>待你批准</span>
        </div>
        <div class="t-item">
          <b>{{ todayCalls }}</b>
          <span>近期调用</span>
        </div>
        <div class="t-item">
          <b :class="{ bad: failRate > 20 }">{{ failRate }}%</b>
          <span>失败率</span>
        </div>
      </div>
    </header>

    <nav class="tabs">
      <button v-for="t in tabs" :key="t.id" :class="{ on: tab === t.id }" @click="switchTab(t.id)">
        {{ t.label }}
        <i v-if="t.id === 'pending' && pending > 0" class="dot">{{ pending }}</i>
      </button>
      <span class="spacer"></span>
      <button class="ghost" :disabled="loading" @click="refresh">刷新</button>
    </nav>

    <!-- ========== 待审批 ========== -->
    <section v-if="tab === 'pending'" class="panel">
      <div v-if="!items.length" class="empty">
        <p>没有待批的调用。</p>
        <p class="hint">
          岗位在任务中调用了「需批准」的工具,或 CEO 巡视时申请授权,都会出现在这里。
        </p>
      </div>
      <template v-else>
        <div class="bulkbar">
          <label class="check">
            <input type="checkbox" :checked="allChecked" @change="toggleAll" />
            <span>全选</span>
          </label>
          <span class="count">已选 {{ selected.length }} / {{ items.length }}</span>
          <span class="spacer"></span>
          <button class="primary" :disabled="!selected.length || busy" @click="batchApprove">
            批准并执行({{ selected.length }})
          </button>
        </div>

        <article v-for="a in items" :key="a.id" class="card" :class="{ picked: selected.includes(a.id) }">
          <div class="card-top">
            <input type="checkbox" :checked="selected.includes(a.id)" @change="toggle(a.id)" />
            <span class="tool">{{ a.toolName }}</span>
            <span class="cat">{{ a.category }}</span>
            <span class="who">{{ a.actor || '未知岗位' }}</span>
            <span class="when">{{ fmt(a.createdAt) }}</span>
          </div>

          <p v-if="a.reason" class="reason">{{ a.reason }}</p>

          <pre class="args">{{ pretty(a.argsJson) }}</pre>

          <div class="card-foot">
            <span class="link-info">
              <template v-if="a.goalId">目标 #{{ a.goalId }}</template>
              <template v-if="a.taskId"> · 任务 #{{ a.taskId }}</template>
            </span>
            <span class="spacer"></span>
            <button class="ghost" :disabled="busy" @click="openReject(a)">拒绝</button>
            <button class="primary" :disabled="busy" @click="approveOne(a)">批准并执行</button>
          </div>
        </article>
      </template>
    </section>

    <!-- ========== 决策档案 ========== -->
    <section v-else-if="tab === 'traces'" class="panel">
      <div v-if="!traces.length" class="empty">
        <p>还没有决策档案。</p>
        <p class="hint">CEO 每巡视一次,都会记下它查了什么、申请了什么、最后怎么拍的板。</p>
      </div>
      <article v-for="t in traces" :key="t.id" class="card trace" @click="openTrace(t)">
        <div class="card-top">
          <span class="cycle">第 {{ t.cycleNo }} 轮</span>
          <span class="cat">{{ t.kind }}</span>
          <span v-if="t.model" class="model">{{ t.model }}</span>
          <span class="spacer"></span>
          <span class="when">{{ fmt(t.createdAt) }}</span>
        </div>
        <p class="summary">{{ t.summary || '(本轮无摘要)' }}</p>
        <div class="meta">
          <span v-if="t.toolNames">取证工具:{{ t.toolNames }}</span>
          <span v-else>本轮没有取证</span>
          <span v-if="t.approvalsRequested" class="warn">申请授权 {{ t.approvalsRequested }} 条</span>
          <span>{{ t.evidenceChars }} 字证据</span>
        </div>
      </article>
    </section>

    <!-- ========== 调用流水 ========== -->
    <section v-else class="panel">
      <div class="agg">
        <div v-for="g in byTool" :key="g.name" class="agg-item">
          <b>{{ g.name }}</b>
          <span>{{ g.calls }} 次 · {{ avgMs(g) }}ms<template v-if="g.fails"> · </template><em v-if="g.fails">{{ g.fails }} 次失败</em></span>
        </div>
      </div>

      <div v-if="!calls.length" class="empty"><p>还没有工具调用记录。</p></div>
      <div v-else class="rows">
        <div class="row head">
          <span>时间</span><span>工具</span><span>来源</span><span>调用方</span>
          <span>耗时</span><span>结果</span>
        </div>
        <div v-for="c in calls" :key="c.id" class="row" :class="{ fail: !c.ok }">
          <span class="mono">{{ fmt(c.createdAt) }}</span>
          <span class="tool">{{ c.toolName }}</span>
          <span class="src">{{ srcLabel(c.source) }}</span>
          <span>{{ c.actor || '—' }}</span>
          <span class="mono">{{ c.ms }}ms</span>
          <span class="preview" :title="c.resultPreview">{{ c.resultPreview }}</span>
        </div>
      </div>
    </section>

    <!-- 拒绝理由 -->
    <div v-if="rejecting" class="overlay" @click.self="rejecting = null">
      <div class="dialog">
        <h3>拒绝 {{ rejecting.toolName }}</h3>
        <p class="hint">
          写清为什么不行。它会进公司动态,模型下一轮读得到 —— 只点拒绝不说话,
          下一轮它只会换个参数再申请一次。
        </p>
        <textarea v-model="rejectReason" rows="3" placeholder="例如:这个包不该在本机打开"></textarea>
        <div class="dialog-foot">
          <button class="ghost" @click="rejecting = null">取消</button>
          <button class="danger" :disabled="busy" @click="doReject">确认拒绝</button>
        </div>
      </div>
    </div>

    <!-- 决策全文 -->
    <div v-if="traceDetail" class="overlay" @click.self="traceDetail = null">
      <div class="dialog wide">
        <h3>第 {{ traceDetail.cycleNo }} 轮决策</h3>
        <p class="hint">
          {{ traceDetail.model || '默认模型' }} · {{ fmt(traceDetail.createdAt) }}
          <template v-if="traceDetail.toolNames"> · 取证 {{ traceDetail.toolNames }}</template>
        </p>
        <div class="sect">
          <h4>它查到了什么</h4>
          <pre class="doc">{{ traceDetail.evidence || '(本轮没有取证)' }}</pre>
        </div>
        <div class="sect">
          <h4>它怎么拍的板</h4>
          <pre class="doc">{{ traceDetail.decisionJson || '(无)' }}</pre>
        </div>
        <div class="dialog-foot">
          <button class="ghost" @click="traceDetail = null">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, bus } from '../api'

const tabs = [
  { id: 'pending', label: '待审批' },
  { id: 'traces', label: '决策档案' },
  { id: 'calls', label: '调用流水' }
]

const tab = ref('pending')
const items = ref([])
const traces = ref([])
const calls = ref([])
const byTool = ref([])
const pending = ref(0)
const selected = ref([])
const busy = ref(false)
const loading = ref(false)
const rejecting = ref(null)
const rejectReason = ref('')
const traceDetail = ref(null)

const allChecked = computed(() => items.value.length > 0 && selected.value.length === items.value.length)
const todayCalls = computed(() => calls.value.length)
const failRate = computed(() => {
  if (!calls.value.length) return 0
  const bad = calls.value.filter(c => !c.ok).length
  return Math.round((bad / calls.value.length) * 100)
})

function fmt(t) {
  if (!t) return '—'
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return '—'
  const pad = n => String(n).padStart(2, '0')
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  return sameDay ? hm : `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hm}`
}

function pretty(json) {
  if (!json) return '{}'
  try { return JSON.stringify(JSON.parse(json), null, 2) } catch { return json }
}

function srcLabel(s) {
  return { DECISION: '决策取证', TASK: '岗位任务', APPROVAL: '批准重放' }[s] || s || '—'
}

function avgMs(g) {
  return g.calls ? Math.round(g.ms / g.calls) : 0
}

async function loadPending() {
  const r = await api.toolApprovals('pending')
  items.value = r.items || []
  pending.value = r.pending || 0
  // 列表变了以后,已经不存在的勾选要清掉,否则"批量批准 3 条"里有两张已被别人处理过
  selected.value = selected.value.filter(id => items.value.some(a => a.id === id))
}

async function loadTraces() {
  traces.value = await api.opsTraces(30)
}

async function loadCalls() {
  const r = await api.toolCalls(0)
  calls.value = r.items || []
  byTool.value = r.byTool || []
}

async function refresh() {
  loading.value = true
  try {
    if (tab.value === 'pending') await loadPending()
    else if (tab.value === 'traces') await loadTraces()
    else await loadCalls()
    const c = await api.toolApprovalCount()
    pending.value = c.pending || 0
  } catch (e) {
    // 列表接口失败时保留旧数据,页面进入可重试状态而不是白屏
  } finally {
    loading.value = false
  }
}

function switchTab(id) {
  tab.value = id
  refresh()
}

function toggle(id) {
  const i = selected.value.indexOf(id)
  if (i >= 0) selected.value.splice(i, 1)
  else selected.value.push(id)
}

function toggleAll() {
  selected.value = allChecked.value ? [] : items.value.map(a => a.id)
}

async function approveOne(a) {
  busy.value = true
  try {
    const r = await api.approveTool(a.id)
    if (r.ok) {
      ElMessage({ message: r.message || '已批准,正在执行', type: 'success', plain: true })
    } else {
      ElMessage({ message: r.message || '批准失败', type: 'warning', plain: true })
    }
    await refresh()
  } finally {
    busy.value = false
  }
}

async function batchApprove() {
  if (!selected.value.length) return
  busy.value = true
  try {
    const r = await api.batchApproveTools(selected.value)
    ElMessage({
      message: r.message || `已批准 ${r.approved} 条`,
      type: r.skipped && r.skipped.length ? 'warning' : 'success',
      plain: true
    })
    selected.value = []
    await refresh()
  } finally {
    busy.value = false
  }
}

function openReject(a) {
  rejecting.value = a
  rejectReason.value = ''
}

async function doReject() {
  if (!rejecting.value) return
  busy.value = true
  try {
    await api.rejectToolWithReason(rejecting.value.id, rejectReason.value)
    ElMessage({ message: '已拒绝', type: 'success', plain: true })
    rejecting.value = null
    await refresh()
  } finally {
    busy.value = false
  }
}

async function openTrace(t) {
  traceDetail.value = await api.opsTrace(t.id)
}

let off = null
let timer = null

onMounted(async () => {
  await refresh()
  // 待批单是后台任务产生的,不会有人按刷新;靠推送 + 兜底轮询两条腿
  off = bus.on(evt => {
    if (evt.type === 'tool-approval') {
      pending.value = evt.pending || pending.value
      if (tab.value === 'pending') loadPending()
    }
  })
  timer = setInterval(async () => {
    try {
      const c = await api.toolApprovalCount()
      pending.value = c.pending || 0
    } catch { /* 网络抖动忽略 */ }
  }, 30000)
})

onUnmounted(() => {
  if (off) off()
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.page { padding: 22px 26px 60px; max-width: 1180px; margin: 0 auto; }
.head { display: flex; align-items: flex-start; gap: 24px; margin-bottom: 18px; }
.head h1 { font-family: var(--font-serif); font-size: 22px; font-weight: 400; letter-spacing: .14em; margin: 0 0 6px; }
.sub { font-size: 12.5px; color: var(--text-dim); margin: 0; max-width: 560px; line-height: 1.7; }
.tally { margin-left: auto; display: flex; gap: 26px; }
.t-item { text-align: right; }
.t-item b { display: block; font-family: var(--font-mono); font-size: 21px; font-weight: 400; color: var(--text-moon); }
.t-item b.hot { color: var(--glow-cyan); }
.t-item b.bad { color: var(--st-error); }
.t-item span { font-size: 11px; color: var(--text-faint); letter-spacing: .06em; }

.tabs { display: flex; align-items: center; gap: 4px; border-bottom: 1px solid var(--border-hair); margin-bottom: 16px; }
.tabs button { background: none; border: 0; color: var(--text-dim); font-size: 13px; padding: 9px 12px; cursor: pointer; position: relative; }
.tabs button.on { color: var(--glow-cyan); }
.tabs button.on::after { content: ''; position: absolute; left: 10px; right: 10px; bottom: -1px; height: 1px; background: var(--glow-cyan); }
.tabs .dot { font-style: normal; font-family: var(--font-mono); font-size: 10px; background: var(--rgb-glow); color: #06110f; border-radius: 8px; padding: 1px 5px; margin-left: 5px; }
.spacer { flex: 1; }

.panel { display: flex; flex-direction: column; gap: 10px; }
.empty { padding: 40px 0; text-align: center; color: var(--text-dim); font-size: 13px; }
.empty .hint { font-size: 12px; color: var(--text-faint); margin-top: 8px; }

.bulkbar { display: flex; align-items: center; gap: 14px; padding: 9px 14px; background: var(--tint-soft); border: 1px solid var(--border-hair); border-radius: 9px; font-size: 12.5px; }
.check { display: flex; align-items: center; gap: 6px; cursor: pointer; color: var(--text-dim); }
.count { color: var(--text-faint); font-family: var(--font-mono); font-size: 11.5px; }

.card { padding: 14px 16px; background: var(--card-flat); border: 1px solid var(--border-hair); border-radius: 10px; }
.card.picked { border-color: var(--glow-cyan-dim); }
.card-top { display: flex; align-items: center; gap: 9px; font-size: 12.5px; }
.tool { font-family: var(--font-mono); color: var(--glow-cyan); font-size: 12px; }
.cat { font-size: 10.5px; color: var(--text-faint); border: 1px solid var(--border-hair); border-radius: 5px; padding: 1px 6px; }
.who { color: var(--text-dim); }
.when { margin-left: auto; color: var(--text-faint); font-family: var(--font-mono); font-size: 11px; }
.model { font-size: 10.5px; color: var(--st-plan); font-family: var(--font-mono); }
.cycle { font-family: var(--font-mono); color: var(--text-dim); font-size: 11.5px; }

.reason { margin: 9px 0 0; font-size: 12.5px; color: var(--text-moon); line-height: 1.7; border-left: 2px solid var(--glow-cyan-faint); padding-left: 10px; }
.args { margin: 9px 0 0; padding: 9px 11px; background: rgba(0, 0, 0, .22); border-radius: 7px; font-family: var(--font-mono); font-size: 11.5px; color: var(--text-dim); max-height: 150px; overflow: auto; white-space: pre-wrap; word-break: break-all; }
.card-foot { display: flex; align-items: center; gap: 8px; margin-top: 11px; }
.link-info { font-size: 11px; color: var(--text-faint); font-family: var(--font-mono); }

button.primary, button.ghost, button.danger { font-size: 12px; padding: 5px 13px; border-radius: 7px; cursor: pointer; border: 1px solid var(--border-hair-strong); background: none; color: var(--text-dim); transition: .16s; }
button.primary { background: var(--rgb-btn); border-color: transparent; color: var(--rgb-on-btn); }
button.primary:hover:not(:disabled) { background: var(--rgb-btn-hi); }
button.ghost:hover:not(:disabled) { color: var(--text-moon); border-color: var(--glow-cyan-dim); }
button.danger { color: var(--st-error); border-color: rgba(var(--rgb-error), .35); }
button:disabled { opacity: .4; cursor: not-allowed; }

.summary { margin: 8px 0 0; font-size: 12.5px; line-height: 1.75; color: var(--text-moon); }
.meta { display: flex; gap: 14px; margin-top: 8px; font-size: 11px; color: var(--text-faint); }
.meta .warn { color: var(--st-warn); }
.trace { cursor: pointer; }
.trace:hover { border-color: var(--glow-cyan-dim); }

.agg { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 6px; }
.agg-item { display: flex; flex-direction: column; gap: 3px; padding: 8px 13px; background: var(--tint-soft); border: 1px solid var(--border-hair); border-radius: 8px; }
.agg-item b { font-family: var(--font-mono); font-size: 12px; color: var(--glow-cyan); font-weight: 400; }
.agg-item span { font-size: 11px; color: var(--text-faint); }
.agg-item em { font-style: normal; color: var(--st-error); }

.rows { display: flex; flex-direction: column; font-size: 12px; }
.row { display: grid; grid-template-columns: 96px 132px 76px 92px 68px 1fr; gap: 10px; padding: 8px 10px; border-bottom: 1px solid var(--border-hair); align-items: center; }
.row.head { color: var(--text-faint); font-size: 11px; letter-spacing: .04em; }
.row.fail { background: rgba(var(--rgb-error), .05); }
.row .mono { font-family: var(--font-mono); font-size: 11px; color: var(--text-dim); }
.src { font-size: 11px; color: var(--text-faint); }
.preview { color: var(--text-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.overlay { position: fixed; inset: 0; background: rgba(0, 0, 0, .55); display: flex; align-items: center; justify-content: center; z-index: 60; padding: 30px; }
.dialog { width: 460px; max-width: 100%; background: rgb(var(--rgb-panel)); border: 1px solid var(--border-hair-strong); border-radius: 12px; padding: 20px; }
.dialog.wide { width: 860px; max-height: 82vh; display: flex; flex-direction: column; }
.dialog h3 { margin: 0 0 8px; font-family: var(--font-serif); font-size: 16px; font-weight: 400; letter-spacing: .1em; }
.dialog .hint { font-size: 11.5px; color: var(--text-faint); line-height: 1.7; margin: 0 0 12px; }
.dialog textarea { width: 100%; background: rgba(0, 0, 0, .25); border: 1px solid var(--border-hair-strong); border-radius: 8px; color: var(--text-moon); font-size: 12.5px; padding: 9px 11px; resize: vertical; font-family: inherit; }
.dialog-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.sect { margin-top: 12px; min-height: 0; }
.sect h4 { margin: 0 0 6px; font-size: 11.5px; color: var(--text-faint); font-weight: 400; letter-spacing: .06em; }
.doc { margin: 0; padding: 10px 12px; background: rgba(0, 0, 0, .25); border-radius: 8px; font-family: var(--font-mono); font-size: 11.5px; line-height: 1.75; color: var(--text-dim); max-height: 30vh; overflow: auto; white-space: pre-wrap; word-break: break-word; }

@media (max-width: 900px) {
  .page { padding: 16px 14px 50px; }
  .head { flex-direction: column; gap: 12px; }
  .tally { margin-left: 0; gap: 20px; }
  .row { grid-template-columns: 1fr 1fr; }
  .row.head { display: none; }
  .preview { display: none; }
}
</style>
