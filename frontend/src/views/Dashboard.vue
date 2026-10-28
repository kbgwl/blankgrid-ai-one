<template>
  <div class="board">
    <div class="board-main">
      <!-- 统计带 -->
      <div class="stat-row">
        <div class="stat glass" v-for="s in statCards" :key="s.label" :class="{ 'is-pulse': statPulse.has(s.label) }">
          <div class="stat-num" :class="{ cyan: s.cyan }">{{ s.value }}</div>
          <div class="stat-label">{{ s.label }}</div>
        </div>
        <div class="stat glass cycle-stat">
          <el-button type="primary" size="small" :loading="cycling" @click="runCycle">
            {{ ceoRunning ? '巡视中…' : '立即巡视' }}
          </el-button>
          <div class="stat-label">CEO 已巡视 {{ cycleCount }} 轮</div>
        </div>
      </div>

      <!-- 创始人指挥台 -->
      <div class="command glass">
        <span v-if="noProvider" class="no-key" @click="$router.push('/settings')" title="CEO 和所有岗位都还无法开工">
          ⚙ 未配置大模型 API——去设置页点亮引擎
        </span>
        <span v-if="stats.budgetExceeded" class="budget-warn" title="今日 token 预算已用尽,CEO 自动巡视已暂停">
          ⚠ 预算已用尽
        </span>
        <span v-if="stale" class="stale-note" title="最近一次刷新没成功,看到的是上一次的结果">数据可能陈旧</span>
        <el-input v-model="goalText" placeholder="给公司一个目标,CEO 立即拆解并派活" size="small" class="cmd-input" @keyup.enter="submitGoal" />
        <el-button size="small" type="primary" plain :disabled="!goalText.trim()" :loading="creating" @click="submitGoal">下达</el-button>
        <el-button size="small" text class="feed-btn" @click="$router.push('/')">目标指挥台</el-button>
        <el-divider direction="vertical" />
        <el-input v-model="feedbackText" placeholder="纠偏指令(路走歪了就写这里)" size="small" class="cmd-input warn" />
        <el-button size="small" class="warn-btn" :disabled="!feedbackText.trim()" @click="submitFeedback">纠偏</el-button>
        <span v-if="openItems.length" class="pend-chip" title="点一下定位到需复核列" @click="focusReview">
          {{ openItems.length }} 件待拍板
        </span>
      </div>

      <!-- 看板取不到时说明原因,而不是留四条空泳道 -->
      <div v-if="loadError" class="board-fail glass">
        <div class="fail-mark">!</div>
        <p>{{ loadError }}</p>
        <el-button size="small" plain :loading="loading" @click="load">重试</el-button>
      </div>

      <!-- 任务看板 -->
      <div v-else class="lanes">
        <div class="lane" v-for="lane in lanes" :key="lane.status"
             :class="{ 'is-recv': recvLanes.has(lane.status), 'is-work': lane.status === 'RUNNING' && lane.items.length }">
          <div class="lane-head">
            <span class="lane-title">{{ lane.label }}</span>
            <span class="lane-count">{{ lane.items.length }}</span>
          </div>
          <transition-group tag="div" name="mv" class="lane-body">
            <div v-if="!lane.items.length" key="__empty" class="lane-empty">—</div>
            <div v-for="t in lane.items" :key="t.id" class="card"
                 :class="{ 'm-land': flashIds.has(t.id) }" @click="openTask(t)">
              <div class="card-line">
                <span class="card-id">
                  #{{ t.id }}
                  <i v-if="t.kind === 'WORK'" class="kind-badge kb-work">交付</i>
                  <i v-else-if="t.kind === 'REVIEW'" class="kind-badge kb-review">评审</i>
                </span>
                <span class="card-emp" :style="{ color: deptColor(t) }">{{ t.employee?.name }}</span>
              </div>
              <div class="card-inst">
                <span v-if="t.sourceTaskIds" class="card-chain" title="基于上游产出">⤳ {{ t.sourceTaskIds }}</span>
                {{ t.instruction }}
              </div>
              <div v-if="t.status === 'RUNNING'" class="task-prog"><i></i></div>
              <div class="card-time">{{ fmt(t.createdAt) }}</div>
            </div>
          </transition-group>
        </div>

        <!-- 需复核:等你拍板的事独立成列,不沉在动态角落里 -->
        <div class="lane lane-review" ref="reviewEl" :class="{ 'is-recv': reviewHot }">
          <div class="lane-head">
            <span class="lane-title">需复核</span>
            <span class="lane-count">{{ openItems.length }}</span>
          </div>
          <transition-group tag="div" name="mv" class="lane-body">
            <div v-if="!openItems.length" key="__empty" class="lane-empty">—</div>
            <div v-for="a in openItems" :key="'e' + a.id" class="card esc-card">
              <div class="card-line">
                <span class="card-id">#{{ a.id }}</span>
                <span class="esc-tag">{{ typeMap[a.type] || a.type }}</span>
              </div>
              <div class="card-inst">{{ a.content }}</div>
              <div class="esc-foot">
                <span class="card-time">{{ fmt(a.createdAt) }}</span>
                <el-button text size="small" class="ack-btn" @click.stop="ack(a)">
                  {{ a.type === 'ESCALATION' ? '已拍板' : '关闭' }}
                </el-button>
              </div>
            </div>
          </transition-group>
        </div>
      </div>
    </div><!-- /board-main -->

    <!-- 实时动态常驻右侧:WebSocket 事件一到就补进列表并落一下 -->
    <aside class="feed-rail glass">
      <div class="rail-head">
        <span class="rail-title">实时动态</span>
        <el-button text size="small" class="feed-refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
      <div class="rail-meta">
        <span>CEO 已巡视 {{ cycleCount }} 轮</span>
        <span v-if="openItems.length" class="rail-badge">{{ openItems.length }} 件待拍板</span>
      </div>
      <div class="feed-body">
        <div v-if="!activities.length" class="feed-empty">暂无动态</div>
        <transition-group tag="div" name="mv" class="rail-list">
          <article v-for="a in railItems" :key="a.id" class="feed-item"
                   :class="['t-' + a.type.toLowerCase(), { 'is-new': newActs.has(a.id) }]">
            <div class="feed-line">
              <span class="feed-tag">{{ typeMap[a.type] || a.type }}</span>
              <span class="feed-time">{{ fmt(a.createdAt) }}</span>
            </div>
            <div class="feed-content">{{ a.content }}</div>
          </article>
        </transition-group>
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
const stats = ref({})
const noProvider = ref(false)
const activities = ref([])
const ceoRunning = ref(false)
const cycleCount = ref(0)
const cycling = ref(false)
const creating = ref(false)
const goalText = ref('')
const feedbackText = ref('')
const reviewEl = ref(null)
const taskOpen = ref(false)
const openedTaskId = ref(null)
const loadError = ref('')
const loading = ref(false)
const stale = ref(false)
const newActs = ref(new Set())
const reviewHot = ref(false)

/* 需复核 = 还挂着 OPEN、需要创始人点头的动态 */
const openItems = computed(() =>
  activities.value.filter(a => a.status === 'OPEN' && ['ESCALATION', 'GOAL', 'FEEDBACK'].includes(a.type)))
const railItems = computed(() => activities.value.slice(0, 60))

function focusReview() {
  if (!reviewEl.value) return
  reviewEl.value.scrollIntoView({ behavior: motionOk() ? 'smooth' : 'auto', block: 'nearest', inline: 'center' })
  reviewHot.value = true
  setTimeout(() => { reviewHot.value = false }, 1100)
}

const typeMap = { GOAL: '目标', FEEDBACK: '纠偏', REPORT: 'CEO', ESCALATION: '上报' }

function deptColor(t) {
  const key = t.employee?.department?.colorKey
  return { product: 'var(--dept-product)', tech: 'var(--dept-tech)', design: 'var(--dept-design)', market: 'var(--dept-market)', ops: 'var(--dept-ops)' }[key] || 'var(--text-dim)'
}

/* ===== 施工动效:卡片换道时落地,接住卡片的泳道亮一下 ===== */
const flashIds = ref(new Set())
const recvLanes = ref(new Set())
let prevTaskStatus = new Map()
let prevLaneCounts = {}
let boardFirstLoad = true
let flashTimer = null

function diffBoard(list) {
  const nextStatus = new Map(list.map(t => [t.id, t.status]))
  if (boardFirstLoad || !motionOk()) {
    prevTaskStatus = nextStatus
    boardFirstLoad = false
    return
  }
  const moved = new Set()
  for (const [id, st] of nextStatus) {
    const before = prevTaskStatus.get(id)
    if (before && before !== st) moved.add(id)
  }
  prevTaskStatus = nextStatus
  if (moved.size) {
    flashIds.value = moved
    clearTimeout(flashTimer)
    flashTimer = setTimeout(() => { flashIds.value = new Set() }, 1300)
  }
}

const statPulse = ref(new Set())
let prevStatVals = null

function pulseStats(st) {
  const next = {
    '今日完成': st.doneToday ?? 0, '执行中': st.running ?? 0,
    '今日 token': fmtTokens(st.tokensToday), '累计 token': fmtTokens(st.tokensTotal),
    '失败待复盘': st.failed ?? 0
  }
  if (prevStatVals && motionOk()) {
    const hot = new Set()
    for (const k of Object.keys(next)) if (prevStatVals[k] !== undefined && prevStatVals[k] !== next[k]) hot.add(k)
    if (hot.size) {
      statPulse.value = hot
      setTimeout(() => { statPulse.value = new Set() }, 700)
    }
  }
  prevStatVals = next
}

function noteLaneCounts(list) {
  const counts = { PENDING: 0, RUNNING: 0, DONE: 0, FAILED: 0 }
  for (const t of list) {
    if (t.status === 'FAILED' || t.status === 'CANCELLED') counts.FAILED++
    else if (counts[t.status] !== undefined) counts[t.status]++
  }
  if (motionOk()) {
    const hot = new Set()
    for (const k of Object.keys(counts)) if (prevLaneCounts[k] !== undefined && counts[k] > prevLaneCounts[k]) hot.add(k)
    if (hot.size) {
      recvLanes.value = hot
      setTimeout(() => { recvLanes.value = new Set() }, 1100)
    }
  }
  prevLaneCounts = counts
}

const lanes = computed(() => [
  { status: 'PENDING', label: '待处理', items: tasks.value.filter(t => t.status === 'PENDING') },
  { status: 'RUNNING', label: '执行中', items: tasks.value.filter(t => t.status === 'RUNNING') },
  { status: 'DONE', label: '完成', items: tasks.value.filter(t => t.status === 'DONE') },
  { status: 'FAILED', label: '失败 · 已取消', items: tasks.value.filter(t => t.status === 'FAILED' || t.status === 'CANCELLED') }
])

function fmtTokens(n) {
  n = n || 0
  return n >= 10000 ? (n / 10000).toFixed(1) + ' 万' : String(n)
}

const statCards = computed(() => [
  { label: '今日完成', value: stats.value.doneToday ?? 0, color: 'var(--dept-ops)' },
  { label: '执行中', value: stats.value.running ?? 0, color: 'var(--glow-cyan)', cyan: true },
  { label: '今日 token', value: fmtTokens(stats.value.tokensToday), cyan: true },
  { label: '累计 token', value: fmtTokens(stats.value.tokensTotal) },
  { label: '失败待复盘', value: stats.value.failed ?? 0, color: 'var(--st-error)' }
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

/* 任务与统计各取各的:一边挂了不能把另一边一起拖成 0,更不能拖掉实时订阅 */
async function load() {
  loading.value = true
  const settle = p => p.then(v => ({ v }), e => ({ e }))
  const [tk, st] = await Promise.all([settle(api.tasks()), settle(api.stats())])
  if (tk.v) { tasks.value = tk.v; diffBoard(tk.v); noteLaneCounts(tk.v) }
  if (st.v) { pulseStats(st.v); stats.value = st.v; noProvider.value = st.v.hasProvider === false }
  loadError.value = (tk.v || st.v) ? '' : `看板载入失败:${failText(tk.e || st.e)}`
  stale.value = Boolean((tk.v && st.e) || (st.v && tk.e))
  loading.value = false
  try {
    const feed = await api.opsFeed()
    diffFeed(feed.activities || [])
    activities.value = feed.activities || []
    ceoRunning.value = feed.ceoRunning || false
    cycleCount.value = feed.cycleCount || 0
  } catch (e) { /* feed 接口不可用时忽略 */ }
}

/* 动态栏是常驻的,所以新事件必须自己跳出来:标蓝一下再退回常态 */
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
  /* 订阅先建立,再取数:取数失败只是暂时没数据,实时通道必须一直在 */
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
/* 左侧看流程,右侧看动态:一屏之内既知道卡在哪,也知道刚发生了什么 */
.board { display: grid; grid-template-columns: minmax(0, 1fr) 336px; gap: 16px; height: calc(100vh - 140px); }
.board-main { display: flex; flex-direction: column; gap: 16px; min-width: 0; min-height: 0; }
.stat-row { display: flex; gap: 14px; }
.stat { flex: 1; padding: 16px 18px 13px; text-align: center; position: relative; overflow: hidden;
        transition: border-color .4s var(--ease-quiet); }
.stat:hover { border-color: rgba(var(--rgb-glow), .22); }
.stat::after {
  content: ""; position: absolute; left: 30%; right: 30%; bottom: 0; height: 1px;
  background: linear-gradient(90deg, transparent, var(--glow-cyan-dim), transparent);
  opacity: .5;
}
.stat-num { font-size: 26px; font-weight: 200; font-family: var(--font-mono); }
.stat-num.cyan { color: var(--glow-cyan); text-shadow: 0 0 14px rgba(var(--rgb-glow), .3); }
.stat-label { font-size: 10.5px; color: var(--text-dim); letter-spacing: .26em; margin-top: 6px; margin-left: .26em; }
.cycle-stat { display: flex; flex-direction: column; justify-content: center; align-items: center; }

.command { display: flex; align-items: center; gap: 10px; padding: 10px 16px; }
.budget-warn { flex: none; font-size: 11px; color: var(--st-warn); border: 1px solid rgba(var(--rgb-warn), .4);
               border-radius: 999px; padding: 3px 10px; animation: badgePulse 2.2s ease-in-out infinite; }
@keyframes badgePulse { 0%,100% { box-shadow: 0 0 4px rgba(var(--rgb-warn), .15); } 50% { box-shadow: 0 0 14px rgba(var(--rgb-warn), .35); } }
.cmd-input { flex: 1; }
.cmd-input.warn :deep(.el-input__inner) { color: var(--st-warn); }
.warn-btn { color: var(--st-warn); }
.feed-btn { color: var(--glow-cyan); letter-spacing: .15em; }

.board-fail {
  flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 14px; text-align: center; padding: 32px;
  animation: riseIn .5s var(--ease-out) both;
}
.board-fail p { margin: 0; font-size: 12.5px; line-height: 1.9; color: var(--text-dim); letter-spacing: .08em; }
.fail-mark {
  font-size: 30px; font-weight: 200; color: var(--st-error);
  border: 1px dashed rgba(var(--rgb-error), .4); width: 68px; height: 68px; border-radius: 18px;
  display: grid; place-items: center;
}
.stale-note { font-size: 11px; letter-spacing: .06em; color: var(--st-warn); flex: none; }

.lanes { flex: 1; display: flex; gap: 16px; min-height: 0; }
.lane { flex: 1; display: flex; flex-direction: column; min-height: 0; min-width: 0;
        transform: translateZ(0); /* 修复部分 GPU 环境下普通内容不合成绘制的问题 */
        background: linear-gradient(170deg, rgba(var(--rgb-panel), .6), rgba(var(--rgb-surface-2), .66));
        border: 1px solid rgba(var(--rgb-ink), .09); border-radius: 14px;
        padding: 12px 10px 14px; }
.lane-head { display: flex; justify-content: space-between; align-items: center; padding: 2px 8px 12px; }
.lane-title { font-size: 13.5px; letter-spacing: .3em; color: rgba(var(--rgb-ink), .8); }
.lane-count { font-family: var(--font-mono); font-size: 11px; color: var(--glow-cyan);
              border: 1px solid rgba(var(--rgb-glow), .3); border-radius: 999px; padding: 1px 9px; }
.lane-body { flex: 1; overflow: auto; display: flex; flex-direction: column; gap: 10px; padding: 2px 4px 4px; }
/* 接到新卡片:泳道亮一下,告诉创始人「这一步刚跑完」 */
.lane.is-recv { border-color: rgba(var(--rgb-glow), .34); }
.lane.is-recv .lane-count { animation: popScore .55s cubic-bezier(.34, 1.56, .64, 1) both; }
.lane.is-recv::before {
  content: ""; position: absolute; left: 10px; right: 10px; top: 0; height: 1px;
  background: linear-gradient(90deg, transparent, var(--glow-cyan), transparent);
  animation: recvFlash 1.1s var(--ease-out) both;
}
@keyframes recvFlash {
  0% { opacity: 0; transform: scaleX(.3); }
  35% { opacity: 1; transform: scaleX(1); }
  100% { opacity: 0; }
}
/* 执行中:整条泳道有一道慢扫的光,一眼看出公司在动 */
.lane.is-work { position: relative; overflow: hidden; }
.lane.is-work::after {
  content: ""; position: absolute; left: 0; right: 0; top: 0; height: 40%; pointer-events: none;
  background: linear-gradient(180deg, rgba(var(--rgb-glow), .07), transparent);
  animation: liveSweep 4.2s var(--ease-quiet) infinite;
}
@keyframes liveSweep {
  0% { transform: translateY(-110%); opacity: 0; }
  22% { opacity: .95; }
  100% { transform: translateY(300%); opacity: 0; }
}
.stat.is-pulse .stat-num { animation: popScore .6s cubic-bezier(.34, 1.56, .64, 1) both; }
.lane-empty { text-align: center; color: var(--text-faint); padding: 34px 0; letter-spacing: .2em; }
.card { padding: 12px 14px; cursor: pointer;
        background: linear-gradient(155deg, rgba(var(--rgb-raise), .92), rgba(var(--rgb-panel-hi), .94));
        border: 1px solid rgba(var(--rgb-ink), .13);
        border-radius: 12px;
        transition: border-color .35s var(--ease-quiet), transform .35s var(--ease-out); }
.card:hover { border-color: rgba(var(--rgb-glow), .25); transform: translateY(-2px); }
.card-line { display: flex; justify-content: space-between; }
.card-id { font-family: var(--font-mono); font-size: 11px; color: var(--text-faint); }
.kind-badge { font-style: normal; font-family: var(--font-sans); font-size: 8.5px; letter-spacing: .08em;
              padding: 1px 6px; border-radius: 999px; margin-left: 6px; }
.kb-work { color: var(--glow-cyan-dim); background: var(--glow-cyan-faint); }
.kb-review { color: var(--st-warn); background: rgba(var(--rgb-warn), .1); }
.card-emp { font-size: 12px; color: var(--glow-cyan-dim); letter-spacing: .1em; }
.card-inst { font-size: 12.5px; margin-top: 6px; line-height: 1.7; overflow: hidden;
             display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; }
.card-time { font-size: 10.5px; color: var(--text-faint); margin-top: 8px; font-family: var(--font-mono); }
.card-chain { color: var(--glow-cyan-dim); font-family: var(--font-mono); font-size: 11px; margin-right: 6px; }

/* ===== 需复核列:与泳道同构,但用琥珀色说明「这是等你的」 ===== */
.lane-review { background: linear-gradient(170deg, rgba(var(--rgb-warn), .05), rgba(var(--rgb-surface-2), .66)); }
.lane-review .lane-count { color: var(--st-warn); border-color: rgba(var(--rgb-warn), .34); }
.lane-review.is-recv { border-color: rgba(var(--rgb-warn), .45); }
.esc-card { cursor: default; }
.esc-card:hover { transform: none; }
.esc-tag { font-size: 9.5px; letter-spacing: .18em; padding: 1px 8px; border-radius: 999px;
           color: var(--st-warn); background: rgba(var(--rgb-warn), .12); }
.esc-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; }
.esc-foot .card-time { margin-top: 0; }
.pend-chip {
  flex: none; font-size: 10.5px; letter-spacing: .12em; color: var(--st-warn); cursor: pointer;
  border: 1px solid rgba(var(--rgb-warn), .34); border-radius: 999px; padding: 3px 10px;
  transition: background .3s var(--ease-quiet);
}
.pend-chip:hover { background: rgba(var(--rgb-warn), .1); }

/* ===== 常驻实时动态 ===== */
.feed-rail { display: flex; flex-direction: column; min-height: 0; padding: 14px 12px 0; }
.rail-head { display: flex; justify-content: space-between; align-items: center; }
.feed-refresh { color: var(--text-dim); }
.rail-title { font-size: 14px; letter-spacing: .3em; color: var(--glow-cyan-dim); }
.rail-meta { display: flex; align-items: center; gap: 10px; margin: 8px 0 12px;
             font-family: var(--font-mono); font-size: 10px; letter-spacing: .1em; color: var(--text-faint); }
.rail-badge { color: var(--st-warn); border: 1px solid rgba(var(--rgb-warn), .3); border-radius: 999px; padding: 1px 8px; }
.rail-list { display: flex; flex-direction: column; gap: 10px; }
.feed-item { animation: riseIn .5s var(--ease-out) both; }
.feed-item.is-new {
  animation: railLand 1.7s var(--ease-out) both;
  border-color: rgba(var(--rgb-glow), .4);
}
@keyframes railLand {
  0% { opacity: 0; transform: translateY(-12px); box-shadow: 0 0 0 1px rgba(var(--rgb-glow), .55), 0 0 22px rgba(var(--rgb-glow), .22); }
  22% { opacity: 1; transform: none; }
  70% { box-shadow: 0 0 0 1px rgba(var(--rgb-glow), .3), 0 0 14px rgba(var(--rgb-glow), .12); }
  100% { box-shadow: none; }
}

/* 动态列表 */
.feed-body { flex: 1; overflow: auto; display: flex; flex-direction: column; gap: 10px; padding-right: 4px; }
.feed-empty { text-align: center; color: var(--text-faint); padding: 60px 0; letter-spacing: .25em; }
.feed-item { padding: 12px 16px; border-radius: 10px; border: 1px solid var(--border-hair); background: var(--tint-soft); }
.feed-item.t-escalation { border-color: rgba(var(--rgb-error), .35); background: rgba(var(--rgb-error), .04); }
.feed-item.t-goal { border-color: rgba(var(--rgb-glow), .3); }
.feed-line { display: flex; align-items: center; gap: 12px; }
.feed-tag { font-size: 10px; letter-spacing: .25em; padding: 1px 9px; border-radius: 999px;
            background: var(--chip-bg); color: var(--text-dim); }
.t-goal .feed-tag { background: var(--glow-cyan-faint); color: var(--glow-cyan); }
.t-escalation .feed-tag { background: rgba(var(--rgb-error), .12); color: var(--st-error); }
.t-feedback .feed-tag { background: rgba(var(--rgb-warn), .12); color: var(--st-warn); }
.feed-time { font-size: 10.5px; color: var(--text-faint); font-family: var(--font-mono); }
.ack-btn { margin-left: auto; color: var(--glow-cyan); }
.feed-content { margin-top: 8px; font-size: 13px; line-height: 1.8; white-space: pre-wrap; }

.stat-label { white-space: nowrap; }
.lane-title { white-space: nowrap; }

@media (max-width: 1180px) {
  /* 放不下右侧栏时改成上下堆叠:动态仍然常驻,只是换个位置 */
  .board { grid-template-columns: 1fr; height: auto; }
  .feed-rail { max-height: 440px; }
}
@media (max-width: 1100px) {
  .command { flex-wrap: wrap; }
  .command :deep(.el-divider--vertical) { display: none; }
  .command .cmd-input { flex: 1 1 240px; min-width: 0; }
  .budget-warn { margin-left: auto; }
}
@media (max-width: 900px) {
  .board { height: auto; }
  .stat-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
  .stat-label { letter-spacing: .16em; margin-left: .16em; }
  .lanes { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
  .lane { min-height: 280px; max-height: 420px; }
}
@media (max-width: 560px) {
  .stat-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .stat { padding: 12px 10px 10px; }
  .stat-num { font-size: 22px; }
  .command { padding: 10px 12px; gap: 8px; }
}
@media (max-width: 430px) {
  .lanes { grid-template-columns: 1fr; }
  .lane { max-height: none; }
}
</style>
