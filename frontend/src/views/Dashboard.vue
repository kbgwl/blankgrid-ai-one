<template>
  <div class="board">
    <div class="board-main">
      <!-- ===== 状态条:公司现在是什么情况,一行说清 ===== -->
      <section class="hud glass">
        <div class="hud-state">
          <span class="hud-dot" :class="dotClass"></span>
          <div class="hud-text">
            <b>{{ headline }}</b>
            <span>{{ subline }}</span>
          </div>
        </div>

        <div class="hud-metrics">
          <div v-for="m in metrics" :key="m.k" class="hm" :class="{ 'is-hot': m.hot, 'is-warn': m.warn }">
            <span class="hm-v">{{ m.v }}</span>
            <span class="hm-k">{{ m.k }}</span>
          </div>
        </div>

        <div class="hud-act">
          <el-button size="small" :loading="loading" @click="load">刷新</el-button>
          <el-button size="small" type="primary" plain :loading="cycling" @click="runCycle">
            {{ ceoRunning ? '巡视中…' : '立即巡视' }}
          </el-button>
        </div>
      </section>

      <!-- ===== 在办目标:公司到底在为什么干活 ===== -->
      <section v-if="goalList.length" class="goals glass">
        <div class="goals-head">
          <h3>在办目标</h3>
          <span class="goals-sub">进度按已通过评审的交付物算,点一下进项目现场</span>
        </div>
        <div class="goal-list">
          <button v-for="row in goalList" :key="row.goal.id" class="goal"
                  :class="{ 'is-need': needCount(row) }" @click="$router.push('/goals/' + row.goal.id)">
            <div class="goal-line">
              <span class="goal-status" :class="'gs-' + (row.goal.status || '').toLowerCase()">{{ goalStatus(row.goal.status) }}</span>
              <span class="goal-title">{{ row.goal.title }}</span>
              <span class="goal-pct">{{ row.goal.progress || 0 }}<i>%</i></span>
            </div>
            <div class="goal-bar"><i :style="{ width: (row.goal.progress || 0) + '%' }"></i></div>
            <div class="goal-meta">
              <span>{{ row.done }}/{{ row.deliverables }} 交付</span>
              <span>{{ row.artifacts }} 份成果</span>
              <span v-if="row.goal.finalScore">均分 {{ row.goal.finalScore }}</span>
              <span v-if="row.blocked" class="bad">受阻 {{ row.blocked }}</span>
              <span v-if="needCount(row)" class="bad need">待你补 {{ needCount(row) }} 项</span>
            </div>
          </button>
        </div>
      </section>

      <!-- ===== 指挥:下目标 / 纠偏 ===== -->
      <section class="command glass">
        <div class="cmd-row">
          <span class="cmd-label">下目标</span>
          <el-input v-model="goalText" placeholder="一句话说清要什么、给谁看、怎样算成" size="default"
                    class="cmd-input" @keyup.enter="submitGoal" />
          <el-button type="primary" :disabled="!goalText.trim()" :loading="creating" @click="submitGoal">下达</el-button>
        </div>
        <div class="cmd-row sub">
          <span class="cmd-label">纠偏</span>
          <el-input v-model="feedbackText" placeholder="路走歪了就写这里,CEO 下一轮会读到" size="small"
                    class="cmd-input" @keyup.enter="submitFeedback" />
          <el-button size="small" :disabled="!feedbackText.trim()" @click="submitFeedback">发出</el-button>
        </div>
        <p v-if="noProvider" class="cmd-alert" @click="$router.push('/settings')">
          ⚙ 还没配置大模型 API,CEO 和所有岗位都开不了工 —— 去设置页点亮引擎
        </p>
        <p v-else-if="stats.budgetExceeded" class="cmd-alert warn">⚠ 今日 token 预算已用尽,自动巡视已暂停</p>
      </section>

      <!-- ===== 等你拍板:不在泳道里,它是消息不是任务 ===== -->
      <section v-if="openItems.length" class="decide glass">
        <div class="decide-head">
          <span class="decide-tag">等你拍板</span>
          <b>{{ openItems.length }} 件</b>
          <span class="decide-sub">需要你点头或补料的事,不处理会一直挡在路上</span>
          <button class="decide-all" :disabled="acking" @click="ackAll">{{ acking ? '处理中…' : '全部已阅' }}</button>
        </div>
        <div class="decide-list">
          <article v-for="a in openItems.slice(0, 6)" :key="a.id" class="decide-item" :class="'t-' + a.type.toLowerCase()">
            <div class="di-top">
              <span class="di-type">{{ typeMap[a.type] || a.type }}</span>
              <!-- 同一件事反复了几次。老板判断「值不值得管」靠的是这个,不是「有条上报」 -->
              <span v-if="(a.occurrences || 1) > 1" class="di-times">×{{ a.occurrences }}</span>
              <span class="di-time">{{ fmt(a.lastSeenAt || a.createdAt) }}</span>
            </div>
            <p class="di-text">{{ a.content }}</p>
            <div class="di-foot">
              <router-link v-if="a.goalId" class="di-go" :to="'/goals/' + a.goalId">去处理 →</router-link>
              <button class="di-ack" @click="ack(a)">知道了</button>
            </div>
          </article>
        </div>
      </section>

      <!-- 看板取不到时说明原因,而不是留四条空泳道 -->
      <div v-if="loadError" class="board-fail glass">
        <div class="fail-mark">!</div>
        <p>{{ loadError }}</p>
        <el-button size="small" plain :loading="loading" @click="load">重试</el-button>
      </div>

      <!-- ===== 任务流 ===== -->
      <div v-else class="lanes">
        <div class="lane" v-for="lane in lanes" :key="lane.status" :class="'lk-' + lane.status.toLowerCase()">
          <div class="lane-head">
            <span class="lane-title">{{ lane.label }}</span>
            <span class="lane-count">{{ lane.items.length }}</span>
          </div>
          <div class="lane-body">
            <div v-if="!lane.items.length" class="lane-empty">{{ lane.empty }}</div>
            <div v-for="t in lane.items" :key="t.id" class="card"
                 :class="{ 'm-land': flashIds.has(t.id) }" @click="openTask(t)">
              <div class="card-line">
                <span class="card-id">#{{ t.id }}</span>
                <i v-if="t.kind === 'WORK'" class="kind-badge kb-work">交付</i>
                <i v-else-if="t.kind === 'REVIEW'" class="kind-badge kb-review">评审</i>
                <span class="card-emp" :style="{ color: deptColor(t) }">{{ t.employee?.name }}</span>
              </div>
              <div class="card-inst">
                <span v-if="t.sourceTaskIds" class="card-chain" title="基于上游产出">⤳ {{ t.sourceTaskIds }}</span>
                {{ t.instruction }}
              </div>
              <div v-if="t.status === 'RUNNING'" class="task-prog"><i></i></div>
              <div class="card-time">{{ fmt(t.createdAt) }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ===== 实时动态:常驻右侧 ===== -->
    <aside class="feed-rail glass">
      <div class="rail-head">
        <span class="rail-title">实时动态</span>
        <span class="rail-meta">巡视 {{ cycleCount }} 轮</span>
      </div>
      <div class="feed-body">
        <div v-if="!activities.length" class="feed-empty">暂无动态</div>
        <article v-for="a in railItems" :key="a.id" class="feed-item"
                 :class="['t-' + a.type.toLowerCase(), { 'is-new': newActs.has(a.id), 'is-open': a.status === 'OPEN' }]">
          <div class="feed-line">
            <span class="feed-tag">{{ typeMap[a.type] || a.type }}</span>
            <span class="feed-time">{{ fmt(a.createdAt) }}</span>
          </div>
          <div class="feed-content">{{ a.content }}</div>
        </article>
      </div>
    </aside>

    <TaskDetail v-model="taskOpen" :task-id="openedTaskId" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { api, bus } from '../api'
import { motionOk } from '../utils/motion'
import TaskDetail from '../components/TaskDetail.vue'

const tasks = ref([])
const goals = ref([])
const stats = ref({})
const noProvider = ref(false)
const activities = ref([])
const ceoRunning = ref(false)
const cycleCount = ref(0)
const cycling = ref(false)
const creating = ref(false)
const goalText = ref('')
const feedbackText = ref('')
const taskOpen = ref(false)
const openedTaskId = ref(null)
const loadError = ref('')
const loading = ref(false)
const acking = ref(false)
const newActs = ref(new Set())

const typeMap = { GOAL: '目标', FEEDBACK: '纠偏', REPORT: '进展', ESCALATION: '上报' }

/* 需要创始人处理的事:挂着 OPEN 的上报/纠偏 */
const openItems = computed(() =>
  activities.value.filter(a => a.status === 'OPEN' && ['ESCALATION', 'GOAL', 'FEEDBACK'].includes(a.type)))
const railItems = computed(() => activities.value.slice(0, 60))

/* 目标按「有事要处理」优先、再看更新时间排,已完成沉底 */
const goalList = computed(() => {
  const rank = s => ({ REVIEW: 0, ACTIVE: 1, PLANNING: 1, PAUSED: 2, FAILED: 2, DONE: 3 }[s] ?? 1)
  return [...goals.value]
    .sort((a, b) => (rank(a.goal.status) - rank(b.goal.status))
      || String(b.goal.updatedAt || '').localeCompare(String(a.goal.updatedAt || '')))
    .slice(0, 6)
})

function needCount(row) {
  const raw = row.goal?.needsInput
  if (!raw) return 0
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr.filter(Boolean).length : 0
  } catch (e) {
    return String(raw).split('\n').filter(Boolean).length
  }
}
function goalStatus(s) {
  return { PLANNING: '拆解中', ACTIVE: '推进中', REVIEW: '待复核', DONE: '已归档', PAUSED: '已暂停', FAILED: '失败' }[s] || s || '—'
}

/* ===== 状态条:一句话 + 六个数字 ===== */
const dotClass = computed(() => {
  if (loadError.value) return 'is-bad'
  if (noProvider.value) return 'is-bad'
  if ((stats.value.running || 0) > 0) return 'is-run'
  return 'is-on'
})
const headline = computed(() => {
  if (loadError.value) return '看不到公司的实时状态'
  if (noProvider.value) return '公司还没通电'
  if (ceoRunning.value) return 'CEO 正在巡视'
  const n = stats.value.running || 0
  if (n > 0) return `正在施工 · ${n} 项并行`
  if (openItems.value.length) return `${openItems.value.length} 件事等你拍板`
  return '系统在线,随时可以下目标'
})
const subline = computed(() => {
  if (loadError.value) return loadError.value
  if (noProvider.value) return '去「设置」填一个大模型服务商,整条生产线才会动'
  const done = stats.value.deliverablesDone || 0
  const total = stats.value.deliverables || 0
  const parts = []
  if (total) parts.push(`交付 ${done}/${total}`)
  if (stats.value.deliverablesWaiting) parts.push(`${stats.value.deliverablesWaiting} 项等上游`)
  if (stats.value.goalsNeedInput) parts.push(`${stats.value.goalsNeedInput} 个目标缺料`)
  parts.push(`门槛 ${stats.value.qualityThreshold || '—'} 分`)
  return parts.join(' · ')
})

function fmtTokens(n) {
  n = n || 0
  return n >= 10000 ? (n / 10000).toFixed(1) + ' 万' : String(n)
}
const metrics = computed(() => {
  const st = stats.value
  return [
    { k: '在跑', v: st.running ?? 0, hot: (st.running || 0) > 0 },
    { k: '待办', v: st.pending ?? 0 },
    { k: '今日完成', v: st.doneToday ?? 0 },
    { k: '待你处理', v: openItems.value.length + (st.goalsNeedInput || 0), warn: (openItems.value.length + (st.goalsNeedInput || 0)) > 0 },
    { k: '成果', v: st.artifactsApproved ?? 0 },
    { k: '今日 token', v: fmtTokens(st.tokensToday) }
  ]
})

function deptColor(t) {
  const key = t.employee?.department?.colorKey
  return { product: 'var(--dept-product)', tech: 'var(--dept-tech)', design: 'var(--dept-design)', market: 'var(--dept-market)', ops: 'var(--dept-ops)' }[key] || 'var(--text-dim)'
}

/* 卡片换道时亮一下:只保留这一处动效,用来指出"刚刚发生了什么" */
const flashIds = ref(new Set())
let prevTaskStatus = new Map()
let boardFirstLoad = true
let flashTimer = null

function diffBoard(list) {
  const next = new Map(list.map(t => [t.id, t.status]))
  if (boardFirstLoad || !motionOk()) {
    prevTaskStatus = next
    boardFirstLoad = false
    return
  }
  const moved = new Set()
  for (const [id, st] of next) {
    const before = prevTaskStatus.get(id)
    if (before && before !== st) moved.add(id)
  }
  prevTaskStatus = next
  if (moved.size) {
    flashIds.value = moved
    clearTimeout(flashTimer)
    flashTimer = setTimeout(() => { flashIds.value = new Set() }, 1300)
  }
}

const lanes = computed(() => [
  { status: 'PENDING', label: '待处理', empty: '队列是空的', items: tasks.value.filter(t => t.status === 'PENDING') },
  { status: 'RUNNING', label: '执行中', empty: '没有任务在跑', items: tasks.value.filter(t => t.status === 'RUNNING') },
  { status: 'DONE', label: '完成', empty: '还没有完成的', items: tasks.value.filter(t => t.status === 'DONE') },
  { status: 'FAILED', label: '失败 · 取消', empty: '没有失败', items: tasks.value.filter(t => t.status === 'FAILED' || t.status === 'CANCELLED') }
])

function openTask(t) {
  openedTaskId.value = t.id
  taskOpen.value = true
}
function fmt(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}
function failText(e) {
  return e && (e.code === 'ECONNABORTED' || e.code === 'ETIMEDOUT')
    ? '后端长时间没有响应' : '后端可能没有响应'
}

/* 三个接口各取各的:一个挂了不该把另两个一起拖成空 */
async function load() {
  loading.value = true
  const settle = p => p.then(v => ({ v }), e => ({ e }))
  const [tk, st, gl] = await Promise.all([settle(api.tasks()), settle(api.stats()), settle(api.goals())])
  if (tk.v) { tasks.value = tk.v; diffBoard(tk.v) }
  if (st.v) { stats.value = st.v; noProvider.value = st.v.hasProvider === false }
  if (gl.v) goals.value = gl.v
  loadError.value = (tk.v || st.v) ? '' : `看板载入失败:${failText(tk.e || st.e)}`
  loading.value = false
  try {
    const feed = await api.opsFeed()
    diffFeed(feed.activities || [])
    activities.value = feed.activities || []
    ceoRunning.value = feed.ceoRunning || false
    cycleCount.value = feed.cycleCount || 0
  } catch (e) { /* feed 接口不可用时保留上一次的动态 */ }
}

let prevActIds = null
let actTimer = null
function diffFeed(list) {
  const ids = new Set(list.map(a => a.id))
  if (prevActIds && motionOk()) {
    const fresh = new Set([...ids].filter(i => !prevActIds.has(i)))
    if (fresh.size) {
      newActs.value = fresh
      clearTimeout(actTimer)
      actTimer = setTimeout(() => { newActs.value = new Set() }, 1700)
    }
  }
  prevActIds = ids
}

async function submitGoal() {
  const title = goalText.value.trim()
  if (!title || creating.value) return
  creating.value = true
  try {
    const r = await api.createGoal({ title, autoStart: true })
    goalText.value = ''
    ElMessage({ message: '目标已下达,CEO 开始拆解', type: 'success', plain: true })
    if (r.goal?.id) setTimeout(() => { window.location.hash = '#/goals/' + r.goal.id }, 350)
    load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.response?.data?.message || '下达失败')
  } finally { creating.value = false }
}
async function submitFeedback() {
  const text = feedbackText.value.trim()
  if (!text) return
  try {
    await api.setFeedback(text)
    feedbackText.value = ''
    ElMessage({ message: '纠偏已发出', type: 'success', plain: true })
    load()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '纠偏没有送达')
  }
}
async function ack(a) {
  try {
    await api.ackActivity(a.id)
    load()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '这条动态没能关闭')
  }
}
/* 一次清掉所有待办:这些事里有相当一部分是「知道了,现在没法动」,
   逼着逐条点等于逼着放弃看完 */
async function ackAll() {
  acking.value = true
  try {
    const r = await api.ackAllEscalations()
    ElMessage.success(`已关闭 ${r.acked || 0} 条`)
    load()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '批量关闭失败')
  } finally {
    acking.value = false
  }
}
async function runCycle() {
  cycling.value = true
  try {
    const r = await api.ceoCycle()
    ElMessage({ message: r.ok ? ('巡视完成:' + (r.summary || '')) : r.message, type: r.ok ? 'success' : 'warning', plain: true })
    load()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '巡视失败')
  } finally { cycling.value = false }
}

let off = null
let timer = null
onMounted(() => {
  off = bus.on(evt => {
    if (['task-update', 'ceo-status', 'escalation', 'ceo-cycle', 'goal-update', 'goal-completed'].includes(evt.type)) {
      clearTimeout(timer)
      timer = setTimeout(load, 420)
    }
  })
  load()
})
onBeforeUnmount(() => {
  off && off()
  clearTimeout(timer)
  clearTimeout(flashTimer)
  clearTimeout(actTimer)
})
</script>

<style scoped>
/* ===== 版式:左边干活,右边看发生了什么 ===== */
.board { display: grid; grid-template-columns: minmax(0, 1fr) 322px; gap: 14px; align-items: start; }
.board-main { display: flex; flex-direction: column; gap: 12px; min-width: 0; }

/* ===== 状态条 ===== */
.hud { display: flex; align-items: center; gap: 24px; padding: 16px 20px; }
.hud-state { display: flex; align-items: center; gap: 12px; min-width: 0; flex: 1; }
.hud-dot { width: 7px; height: 7px; border-radius: 50%; flex: none; background: var(--text-faint); }
.hud-dot.is-on { background: var(--glow-cyan); box-shadow: 0 0 10px rgba(var(--rgb-glow), .55); }
.hud-dot.is-run { background: var(--warm); box-shadow: 0 0 12px rgba(var(--rgb-warm), .6); animation: breathe 2.2s var(--ease-quiet) infinite; }
.hud-dot.is-bad { background: var(--st-error); box-shadow: 0 0 10px rgba(var(--rgb-error), .55); }
.hud-text { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.hud-text b { font-size: 14.5px; font-weight: 400; letter-spacing: .08em; color: var(--text-moon); }
.hud-text span { font-size: 11px; color: var(--text-faint); letter-spacing: .04em; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hud-metrics { display: flex; align-items: stretch; gap: 0; flex: none; }
.hm {
  display: flex; flex-direction: column; align-items: center; gap: 3px;
  padding: 0 16px; border-left: 1px solid var(--border-hair);
}
.hm-v { font-family: var(--font-mono); font-size: 19px; font-weight: 300; color: var(--text-moon); font-variant-numeric: tabular-nums; }
.hm-k { font-size: 9.5px; letter-spacing: .16em; color: var(--text-faint); white-space: nowrap; }
.hm.is-hot .hm-v { color: var(--glow-cyan); }
.hm.is-warn .hm-v { color: var(--st-warn); }
.hud-act { display: flex; gap: 8px; flex: none; }

/* ===== 在办目标 ===== */
.goals { padding: 14px 18px 16px; }
.goals-head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 10px; }
.goals-head h3 { margin: 0; font-size: 12.5px; font-weight: 400; letter-spacing: .22em; color: var(--text-moon); }
.goals-sub { font-size: 10.5px; color: var(--text-faint); }
.goal-list { display: flex; flex-direction: column; gap: 2px; }
.goal {
  appearance: none; text-align: left; cursor: pointer; background: none; border: 0; width: 100%;
  padding: 10px 10px 9px; border-radius: 9px;
  transition: background .3s var(--ease-quiet);
}
.goal:hover { background: var(--tint-soft); }
.goal.is-need { background: rgba(var(--rgb-warn), .045); }
.goal.is-need:hover { background: rgba(var(--rgb-warn), .08); }
.goal-line { display: flex; align-items: center; gap: 10px; }
.goal-status {
  flex: none; font-family: var(--font-mono); font-size: 9px; letter-spacing: .14em;
  border-radius: 4px; padding: 2px 7px; color: var(--text-dim); background: var(--chip-bg);
}
.gs-active, .gs-planning { color: var(--glow-cyan); background: var(--glow-cyan-faint); }
.gs-review { color: var(--st-warn); background: rgba(var(--rgb-warn), .12); }
.gs-done { color: var(--st-ok); background: rgba(var(--rgb-ok), .1); }
.gs-failed { color: var(--st-error); background: rgba(var(--rgb-error), .1); }
.goal-title {
  flex: 1; min-width: 0; font-size: 13px; color: var(--text-moon); letter-spacing: .02em;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.goal-pct { font-family: var(--font-mono); font-size: 13px; color: var(--text-dim); font-variant-numeric: tabular-nums; }
.goal-pct i { font-style: normal; font-size: 9px; opacity: .6; margin-left: 1px; }
.goal-bar { height: 2px; border-radius: 2px; background: var(--border-hair); margin: 8px 0 6px; overflow: hidden; }
.goal-bar i { display: block; height: 100%; background: linear-gradient(90deg, rgba(var(--rgb-glow), .5), var(--glow-cyan)); transition: width .7s var(--ease-out); }
.goal.is-need .goal-bar i { background: linear-gradient(90deg, rgba(var(--rgb-warn), .5), var(--st-warn)); }
.goal-meta { display: flex; gap: 14px; font-family: var(--font-mono); font-size: 9.5px; letter-spacing: .06em; color: var(--text-faint); }
.goal-meta .bad { color: var(--st-error); }
.goal-meta .need { color: var(--st-warn); }

/* ===== 指挥 ===== */
.command { display: flex; flex-direction: column; gap: 9px; padding: 13px 16px; }
.cmd-row { display: flex; align-items: center; gap: 10px; }
.cmd-row.sub { opacity: .92; }
.cmd-label {
  flex: none; width: 42px; font-size: 10.5px; letter-spacing: .18em; color: var(--text-faint);
}
.cmd-row.sub .cmd-label { color: var(--st-warn); opacity: .8; }
.cmd-input { flex: 1; }
.cmd-alert {
  margin: 2px 0 0; font-size: 11px; letter-spacing: .04em; color: var(--st-warn); cursor: pointer;
}
.cmd-alert.warn { color: var(--st-warn); opacity: .85; }

/* ===== 等你拍板 ===== */
.decide { padding: 14px 18px 16px; border-color: rgba(var(--rgb-warn), .26); }
.decide-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.decide-tag {
  font-family: var(--font-mono); font-size: 9.5px; letter-spacing: .2em; color: var(--st-warn);
  border: 1px solid rgba(var(--rgb-warn), .38); border-radius: 999px; padding: 2px 10px;
}
.decide-head b { font-family: var(--font-mono); font-size: 13px; font-weight: 400; color: var(--st-warn); }
.decide-sub { font-size: 10.5px; color: var(--text-faint); }
.decide-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 10px; }
.decide-item {
  border: 1px solid var(--border-hair); border-left: 2px solid rgba(var(--rgb-warn), .5);
  border-radius: 9px; padding: 10px 12px; background: var(--tint-soft);
  display: flex; flex-direction: column; gap: 6px;
}
.decide-item.t-escalation { border-left-color: rgba(var(--rgb-error), .6); }
.di-top { display: flex; align-items: center; gap: 8px; }
.di-type { font-size: 9.5px; letter-spacing: .16em; color: var(--st-warn); }
.di-time { margin-left: auto; font-family: var(--font-mono); font-size: 9.5px; color: var(--text-faint); }
.di-text {
  margin: 0; font-size: 11.5px; line-height: 1.7; color: var(--text-dim);
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
}
.di-foot { display: flex; align-items: center; gap: 12px; }
.di-go { font-size: 10.5px; color: var(--glow-cyan); text-decoration: none; letter-spacing: .06em; }
.di-ack {
  margin-left: auto; background: none; border: 0; cursor: pointer;
  font-size: 10.5px; color: var(--text-faint); letter-spacing: .08em;
}
.di-ack:hover { color: var(--text-moon); }
.di-times {
  font-size: 10px; padding: 0 5px; border-radius: 999px;
  color: var(--st-warn); background: rgba(var(--rgb-warn), .1);
  border: 1px solid rgba(var(--rgb-warn), .22);
}
.decide-all {
  margin-left: auto; background: none; cursor: pointer;
  border: 1px solid var(--border-hair); border-radius: 999px;
  padding: 2px 10px; font-size: 10.5px; letter-spacing: .08em;
  color: var(--text-faint); transition: color .3s var(--ease-quiet), border-color .3s var(--ease-quiet);
}
.decide-all:hover { color: var(--text-moon); border-color: var(--border-hair-strong); }
.decide-all:disabled { opacity: .5; cursor: default; }

/* ===== 泳道 ===== */
.lanes { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.lane {
  display: flex; flex-direction: column; min-width: 0; border-radius: 12px;
  border: 1px solid var(--border-hair);
  background: linear-gradient(175deg, rgba(var(--rgb-panel), .5), rgba(var(--rgb-surface-2), .6));
  padding: 11px 9px 10px;
}
.lane-head { display: flex; align-items: center; gap: 8px; padding: 0 6px 9px; border-bottom: 1px solid var(--border-hair); }
.lane-title { font-size: 11.5px; letter-spacing: .18em; color: rgba(var(--rgb-ink), .72); }
.lane-count { margin-left: auto; font-family: var(--font-mono); font-size: 10px; color: var(--text-faint); }
.lk-running .lane-title { color: var(--glow-cyan); }
.lk-running .lane-count { color: var(--glow-cyan); }
.lk-failed .lane-title { color: rgba(var(--rgb-error), .8); }
.lane-body { flex: 1; display: flex; flex-direction: column; gap: 8px; padding: 9px 3px 2px; max-height: 46vh; overflow: auto; }
.lane-empty { text-align: center; color: var(--text-faint); font-size: 11px; padding: 26px 0; letter-spacing: .08em; }
.card {
  padding: 10px 11px; cursor: pointer; border-radius: 9px;
  background: rgba(var(--rgb-panel-hi), .8);
  border: 1px solid rgba(var(--rgb-ink), .1);
  transition: border-color .3s var(--ease-quiet), transform .3s var(--ease-out);
}
.card:hover { border-color: rgba(var(--rgb-glow), .3); transform: translateY(-1px); }
.card.m-land { animation: cardLand 1.3s var(--ease-out) both; }
@keyframes cardLand {
  0% { box-shadow: 0 0 0 1px rgba(var(--rgb-glow), .5); }
  100% { box-shadow: none; }
}
.card-line { display: flex; align-items: center; gap: 6px; }
.card-id { font-family: var(--font-mono); font-size: 9.5px; color: var(--text-faint); }
.kind-badge { font-style: normal; font-size: 8.5px; letter-spacing: .06em; padding: 1px 5px; border-radius: 999px; }
.kb-work { color: var(--glow-cyan-dim); background: var(--glow-cyan-faint); }
.kb-review { color: var(--st-warn); background: rgba(var(--rgb-warn), .1); }
.card-emp { margin-left: auto; font-size: 11px; letter-spacing: .06em; }
.card-inst {
  font-size: 11.5px; margin-top: 5px; line-height: 1.65; color: var(--text-dim); overflow: hidden;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
}
.card-chain { color: var(--glow-cyan-dim); font-family: var(--font-mono); font-size: 10px; margin-right: 4px; }
.card-time { font-size: 9px; color: var(--text-faint); margin-top: 6px; font-family: var(--font-mono); }
.task-prog { height: 2px; margin-top: 7px; border-radius: 2px; background: var(--border-hair); overflow: hidden; }
.task-prog i { display: block; height: 100%; width: 40%; background: var(--glow-cyan); animation: prog 1.6s var(--ease-quiet) infinite; }
@keyframes prog {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(300%); }
}

/* ===== 取数失败 ===== */
.board-fail {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 14px; text-align: center; padding: 48px 32px;
}
.board-fail p { margin: 0; font-size: 12.5px; line-height: 1.9; color: var(--text-dim); letter-spacing: .06em; }
.fail-mark {
  font-size: 26px; font-weight: 200; color: var(--st-error);
  border: 1px dashed rgba(var(--rgb-error), .4); width: 60px; height: 60px; border-radius: 16px;
  display: grid; place-items: center;
}

/* ===== 实时动态 ===== */
.feed-rail {
  display: flex; flex-direction: column; padding: 14px 12px 12px;
  position: sticky; top: 92px; max-height: calc(100vh - 116px);
}
.rail-head { display: flex; align-items: baseline; gap: 10px; padding: 0 4px 10px; border-bottom: 1px solid var(--border-hair); }
.rail-title { font-size: 12.5px; letter-spacing: .26em; color: var(--text-moon); }
.rail-meta { margin-left: auto; font-family: var(--font-mono); font-size: 9.5px; color: var(--text-faint); }
.feed-body { flex: 1; overflow: auto; display: flex; flex-direction: column; gap: 8px; padding: 12px 4px 2px; }
.feed-empty { text-align: center; color: var(--text-faint); padding: 48px 0; letter-spacing: .2em; font-size: 11.5px; }
.feed-item { padding: 10px 12px; border-radius: 9px; border: 1px solid var(--border-hair); background: var(--tint-soft); }
.feed-item.is-open { border-color: rgba(var(--rgb-warn), .28); }
.feed-item.is-new { animation: feedIn 1.7s var(--ease-out) both; }
@keyframes feedIn {
  0% { opacity: 0; transform: translateY(-8px); border-color: rgba(var(--rgb-glow), .5); }
  20% { opacity: 1; transform: none; }
  100% { border-color: var(--border-hair); }
}
.feed-item.t-escalation { border-color: rgba(var(--rgb-error), .3); background: rgba(var(--rgb-error), .035); }
.feed-line { display: flex; align-items: center; gap: 10px; }
.feed-tag {
  font-size: 9px; letter-spacing: .2em; padding: 1px 7px; border-radius: 999px;
  background: var(--chip-bg); color: var(--text-faint);
}
.t-goal .feed-tag { color: var(--glow-cyan); background: var(--glow-cyan-faint); }
.t-escalation .feed-tag { color: var(--st-error); background: rgba(var(--rgb-error), .12); }
.t-feedback .feed-tag { color: var(--st-warn); background: rgba(var(--rgb-warn), .12); }
.feed-time { margin-left: auto; font-family: var(--font-mono); font-size: 9px; color: var(--text-faint); }
.feed-content { margin-top: 6px; font-size: 11.5px; line-height: 1.7; color: var(--text-dim); white-space: pre-wrap; }

@keyframes breathe { 0%, 100% { opacity: 1; } 50% { opacity: .35; } }

@media (max-width: 1240px) {
  .board { grid-template-columns: 1fr; }
  .feed-rail { position: static; max-height: 420px; }
  .hud { flex-wrap: wrap; }
}
@media (max-width: 1000px) {
  .lanes { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .lane-body { max-height: 320px; }
  .hud-metrics { flex-wrap: wrap; row-gap: 10px; }
}
@media (max-width: 620px) {
  .hud { gap: 14px; padding: 14px 15px; }
  .hud-metrics { width: 100%; }
  .hm { padding: 0 12px; }
  .hm-v { font-size: 16px; }
  .lanes { grid-template-columns: 1fr; }
  .lane-body { max-height: none; }
  .cmd-row { flex-wrap: wrap; }
  .cmd-label { width: auto; }
}
</style>
