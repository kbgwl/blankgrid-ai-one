<template>
  <div class="cc">
    <!-- 英雄区:创始人只写目标 -->
    <section class="hero">
      <div class="hero-glow"></div>
      <div class="hero-inner">
        <div class="eyebrow">
          <span class="eyebrow-dot"></span>
          AI COMPANY OS · 无锡空白格
        </div>
        <h1 class="hero-title">
          给公司一个目标<br />
          <span class="thin">剩下的,交给团队</span>
        </h1>
        <p class="hero-sub">
          CEO 自动拆成部门工作流,负责人拆成交付物,团队执行,评审不过就返工。
          你只定义结果,公司负责从 0 到 1。
        </p>

        <div class="composer glass">
          <div class="composer-main">
            <label class="field-label">公司目标<i>必填</i></label>
            <el-input
              v-model="form.title"
              class="goal-title-input"
              placeholder="写下公司目标,例如:做一款 AI 记账 App"
              maxlength="300"
              @keyup.enter="create"
            />
            <label class="field-label">补充背景<i>可选</i></label>
            <el-input
              v-model="form.brief"
              class="goal-brief-input"
              type="textarea"
              :rows="3"
              resize="none"
              placeholder="目标用户、必须包含的内容、对外口径。信息越具体,拆解越准。"
            />
          </div>

          <div class="preset-row">
            <span class="preset-label">试试</span>
            <button v-for="p in presets" :key="p.title" class="preset" @click="usePreset(p)">
              {{ p.title }}
            </button>
          </div>

          <div class="composer-foot">
            <button class="advanced-toggle" @click="advanced = !advanced">
              {{ advanced ? '收起高级要求' : '高级要求' }}
            </button>
            <span class="quality-chip" v-if="stats.qualityThreshold">
              质量线 {{ stats.qualityThreshold }} 分
            </span>
            <span class="provider-warn" v-if="stats.hasProvider === false" @click="$router.push('/settings')">
              未配置大模型 · 去设置
            </span>
            <div class="spacer"></div>
            <span v-if="!canLaunch" class="launch-hint">先写下目标,或点「试试」里的例子</span>
            <el-button class="launch-btn" :class="{ 'is-idle': !canLaunch }" :loading="creating" @click="create">
              下达目标
              <span class="arrow">→</span>
            </el-button>
          </div>

          <transition name="fold">
            <div v-if="advanced" class="advanced">
              <div class="adv-field">
                <label>成功标准 · 可检查的交付结果</label>
                <el-input v-model="form.successCriteria" type="textarea" :rows="3" resize="none"
                          placeholder="例如:包含用户调研、产品 PRD、技术方案、品牌视觉、官网可运行;每项均有评审结论。" />
              </div>
              <div class="adv-field">
                <label>质量底线 · 留空使用公司默认</label>
                <el-input v-model="form.qualityBar" type="textarea" :rows="2" resize="none"
                          placeholder="交付物必须能直接使用,禁止空话与正确的废话……" />
              </div>
              <div class="adv-row">
                <div class="adv-field">
                  <label>截止时间</label>
                  <el-input v-model="form.deadline" placeholder="如 2026-11-30" />
                </div>
                <div class="adv-field">
                  <label>目标 token 预算 · 0 为不限</label>
                  <el-input-number v-model="form.budgetTokens" :min="0" :step="100000"
                                   controls-position="right" style="width: 100%" />
                </div>
              </div>
            </div>
          </transition>
        </div>
      </div>
    </section>

    <!-- 公司脉搏 -->
    <section class="pulse">
      <div class="pulse-card glass" v-for="s in pulseCards" :key="s.label" :class="{ 'is-pulse': pulseHot.has(s.label) }">
        <div class="pulse-num" :class="{ cyan: s.cyan, warn: s.warn }">{{ s.value }}</div>
        <div class="pulse-label">{{ s.label }}</div>
        <div v-if="s.hint" class="pulse-hint">{{ s.hint }}</div>
        <div class="pulse-line" :class="{ cyan: s.cyan }"></div>
      </div>
    </section>

    <!-- 目标推进 + CEO 巡视侧栏 -->
    <div class="body-grid">
    <section class="goals">
      <div class="section-head">
        <div>
          <h2 class="section-title">目标推进</h2>
          <p class="section-sub">每条目标都是一条完整生产线:拆解 → 执行 → 评审 → 成果归档</p>
        </div>
        <div class="section-tools">
          <span class="goal-count">{{ goals.length }} 条目标</span>
          <span v-if="stale" class="stale-note">最新一次刷新没成功,看到的是上一次的结果</span>
          <el-button text class="refresh-btn" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>

      <div v-if="loadError" class="empty glass load-fail">
        <div class="empty-mark">!</div>
        <p>{{ loadError }}</p>
        <el-button size="small" plain :loading="loading" @click="load">重试</el-button>
      </div>

      <div v-else-if="!goals.length && !loading" class="empty glass">
        <div class="empty-mark">空</div>
        <p>公司还没有目标。写下一个目标,CEO 会立即开始拆解。</p>
      </div>

      <div class="goal-grid" v-if="!loadError">
        <div v-for="n in skeletonCount" :key="'sk-' + n" class="goal-card glass is-skeleton" aria-hidden="true">
          <div class="sk sk-chip"></div>
          <div class="sk sk-title"></div>
          <div class="sk sk-line"></div>
          <div class="sk sk-line w-70"></div>
          <div class="sk sk-bar"></div>
          <div class="sk sk-metrics"></div>
        </div>
        <article
          v-for="g in goals"
          :key="g.goal.id"
          class="goal-card glass"
          :class="['st-' + (g.goal.status || '').toLowerCase(), {
            'is-live': ['ACTIVE', 'PLANNING', 'REVIEW'].includes(g.goal.status),
            'is-gain': gainIds.has(g.goal.id)
          }]"
          @click="openGoal(g.goal.id)"
        >
          <div class="card-top">
            <span class="status-chip" :class="'sc-' + (g.goal.status || '').toLowerCase()">
              <i class="sc-dot"></i>{{ statusText(g.goal.status) }}
            </span>
            <span class="goal-no">GOAL · {{ String(g.goal.id).padStart(3, '0') }}</span>
          </div>

          <h3 class="goal-name">{{ g.goal.title }}</h3>
          <p class="goal-desc">{{ g.goal.ceoSummary || g.goal.brief || '等待 CEO 拆解…' }}</p>

          <div class="goal-depts" v-if="g.departments?.length">
            <span v-for="d in g.departments.slice(0, 5)" :key="d.id" class="dept-pill"
                  :style="{ '--dc': deptColor(d.colorKey) }">
              {{ d.name }}<i v-if="d.lead"> · {{ d.lead }}</i>
            </span>
          </div>

          <div class="progress-block">
            <div class="progress-line">
              <div class="bar"><i :style="{ width: pct(g) + '%' }"></i></div>
              <span class="pct">{{ pct(g) }}%</span>
            </div>
            <div class="progress-sub">
              <span>{{ g.done }}/{{ g.deliverables }} 项交付完成</span>
              <span v-if="g.reviewing">评审中 {{ g.reviewing }}</span>
            </div>
          </div>

          <div class="goal-metrics">
            <div class="metric"><b>{{ g.workstreams }}</b><span>工作流</span></div>
            <div class="metric"><b>{{ g.artifacts }}</b><span>成果</span></div>
            <div class="metric"><b :class="{ good: g.goal.finalScore >= threshold }">{{ g.goal.finalScore || '—' }}</b><span>均分</span></div>
            <div class="metric"><b>{{ money(g.cost) }}</b><span>花费</span></div>
          </div>

          <div v-if="g.blocked" class="blocked">
            <span class="blocked-icon">!</span>{{ g.blocked }} 项受阻,需要你处理
          </div>

          <footer class="card-foot">
            <span class="created">{{ fmt(g.goal.createdAt) }}</span>
            <span class="enter">进入项目 <i>→</i></span>
          </footer>
        </article>
      </div>
    </section>

      <aside class="side-col">
        <section class="patrol glass" :class="{ 'is-run': cycling || ceoRunning, 'm-sweep': cycling || ceoRunning }">
          <div class="patrol-top">
            <span class="patrol-orbit"><i></i><i></i><i></i></span>
            <div class="patrol-id">
              <div class="patrol-who">{{ ceoName }}</div>
              <div class="patrol-title">{{ ceoTitle || '统观全局' }}</div>
            </div>
          </div>
          <p class="patrol-state">{{ patrolState }}</p>
          <div class="patrol-meta">
            <span>已巡视 <b>{{ cycleCount }}</b> 轮</span>
            <span v-if="activities[0]">最近 {{ fmt(activities[0].createdAt) }}</span>
          </div>
          <div class="patrol-act">
            <el-button size="small" plain :loading="cycling" @click="runCycle">立即巡视</el-button>
            <el-button size="small" text @click="$router.push('/board')">去看板 <i>→</i></el-button>
          </div>
        </section>

        <section class="wire glass">
          <div class="wire-head">
            <h2>实时动态</h2>
            <span>{{ activities.length }}</span>
          </div>
          <div v-if="!activities.length" class="wire-empty">团队还没汇报</div>
          <div class="wire-list">
            <div v-for="a in activities.slice(0, 9)" :key="a.id" class="wire-item"
                 :class="['wt-' + (a.type || '').toLowerCase(), { 'm-fresh': hotFeed.has(a.id), 'is-open': a.type === 'ESCALATION' && a.status === 'OPEN' }]">
              <span class="wire-dot"></span>
              <div class="wire-body">
                <div class="wire-top">
                  <span class="wire-type">{{ actType(a.type) }}</span>
                  <span class="wire-time">{{ fmt(a.createdAt) }}</span>
                </div>
                <p class="wire-text">{{ a.content }}</p>
                <el-button v-if="a.type === 'ESCALATION' && a.status === 'OPEN'" text size="small"
                           class="wire-ack" @click="ack(a)">知道了</el-button>
              </div>
            </div>
          </div>
          <div class="side-more" @click="$router.push('/board')">看全部动态 <i>→</i></div>
        </section>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, bus } from '../api'
import { motionOk } from '../utils/motion'

const router = useRouter()
const goals = ref([])
const stats = ref({})
const loading = ref(false)
const creating = ref(false)
const advanced = ref(false)

const form = ref({
  title: '', brief: '', successCriteria: '', qualityBar: '',
  deadline: '', budgetTokens: 0
})

const presets = [
  {
    title: '做一款 AI 记账 App',
    brief: '面向 22-30 岁刚工作的年轻人。需要完整产品方案:用户调研、竞品分析、产品 PRD、品牌视觉方向、可运行的官网落地页。要求每个结论有依据,方案可直接进入开发。'
  },
  {
    title: '写一份行业研究报告',
    brief: '研究「AI Agent 对企业软件的影响」。需要市场规模与增速、关键玩家对比、典型落地场景、风险与机会,并给出可执行的企业采用建议。数据需标注来源,结论要具体。'
  },
  {
    title: '从 0 发布一个品牌',
    brief: '为一个高端手冲咖啡品牌做从 0 到 1 的发布方案:品牌定位、命名与口号、视觉识别、包装方向、首发内容与渠道计划、30 天发布排期。要求可直接执行。'
  },
  {
    title: '做一份季度增长方案',
    brief: '为一家 SaaS 公司制定下季度增长方案。需要现状诊断、增长模型、渠道组合、预算分配、实验计划与指标看板。所有建议要能落地并量化。'
  },
  /* 下面几条是软件公司的日常活:不是"写点东西",而是有明确交付与验收的真实项目。
     模板只填目标与背景,拆解仍然交给 CEO —— 模板的作用是省掉描述需求的那十分钟,
     不是替他把活干了。 */
  {
    title: '做公司官网(可上线)',
    brief: '给一家 20 人的软件开发公司做官网:首页、服务与案例、团队、联系表单、隐私政策。要求:响应式、可访问、SEO 基础项齐全,交付可直接部署的静态站点代码与上线步骤说明。文案要具体,不要"赋能""领先"这类空话。'
  },
  {
    title: '投一个政府信息化项目',
    brief: '为一个区级政务数据平台项目准备投标材料:技术方案、实施方案与里程碑、团队配置、质量与安全保障、售后服务承诺、报价说明。评分办法要求逐条响应,不能漏项;不确定的资质与业绩必须标注待确认,不要编。'
  },
  {
    title: '招一名后端工程师',
    brief: '为 Java 后端岗位做完整招聘包:岗位 JD(职责、硬性要求、加分项、薪资范围、汇报关系)、面试评估表(可打分的维度)、笔试或实操题、offer 沟通要点、前 30 天入职计划。要能直接挂到招聘平台、面试官能当场打分。'
  },
  {
    title: '给客户做一次项目验收',
    brief: '一个已交付的企业管理后台需要走验收:整理交付清单与功能对照表、编写验收测试用例、准备验收会议材料与演示脚本、列出遗留问题与整改计划、附运维交接与售后响应说明。要以客户能签字为标准,不要自说自话。'
  }
]

const threshold = computed(() => stats.value.qualityThreshold || 85)
/**
 * 有目标文字就能下达。之前按钮绑的是 !title.trim(),而很多人把目标写进了下面那栏「补充背景」,
 * 按钮看起来还是亮的(样式用 !important 盖掉了禁用态),点下去毫无反应 —— 这是最劝退的一种交互。
 * 现在:两栏任填其一即可,填错栏也能直接开工(见 create())。
 */
const canLaunch = computed(() => !!(form.value.title.trim() || form.value.brief.trim()))
const loadError = ref('')
const stale = ref(false)
const skeletonCount = computed(() => (loading.value && !goals.value.length && !loadError.value) ? 3 : 0)

const pulseCards = computed(() => [
  { label: '推进中目标', value: stats.value.goalsActive ?? 0, cyan: true },
  { label: '已完成目标', value: stats.value.goalsDone ?? 0 },
  { label: '交付物完成', value: `${stats.value.deliverablesDone ?? 0}/${stats.value.deliverables ?? 0}` },
  { label: '成果已通过评审', value: stats.value.artifactsApproved ?? 0, cyan: true },
  { label: '在岗 AI 员工', value: stats.value.activeEmployees ?? 0 },
  // token 数没法决策,换成钱;今天烧了 60 万 token 花掉 4 块,创始人才有感觉
  { label: '今日花费', value: money(stats.value.costToday), hint: `今日 ${fmtTokens(stats.value.tokensToday)} token` }
])

const statusMap = {
  PLANNING: 'CEO 拆解中', ACTIVE: '推进中', REVIEW: '待创始人复核',
  DONE: '已归档', PAUSED: '已暂停', FAILED: '拆解失败'
}

function statusText(s) { return statusMap[s] || s || '—' }
function deptColor(key) {
  return { product: 'var(--dept-product)', tech: 'var(--dept-tech)', design: 'var(--dept-design)', market: 'var(--dept-market)', ops: 'var(--dept-ops)' }[key] || 'var(--dept-tech)'
}
function pct(g) {
  if (g.goal.status === 'DONE') return 100
  if (g.deliverables > 0) return Math.round(g.done * 100 / g.deliverables)
  return g.goal.progress || 0
}
function fmtTokens(n) {
  n = n || 0
  return n >= 10000 ? (n / 10000).toFixed(1) + ' 万' : String(n)
}
/** 金额:小钱也给两位小数,不然「¥0」看起来像没在跑 */
function money(v) {
  const n = Number(v || 0)
  if (n <= 0) return '¥0'
  return '¥' + (n < 10 ? n.toFixed(2) : n.toFixed(1))
}
function fmt(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }) + ' ' +
    new Date(iso).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
}

function usePreset(p) {
  form.value.title = p.title
  form.value.brief = p.brief
  document.querySelector('.composer')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

async function create() {
  if (creating.value) return
  const title = form.value.title.trim()
  const brief = form.value.brief.trim()
  if (!title && !brief) {
    ElMessage({ message: '先写下目标,一句话也行', type: 'warning', plain: true })
    document.querySelector('.goal-title-input input')?.focus()
    return
  }
  // 只填了「补充背景」:把那一段当目标用,别让人因为填错栏白等
  if (!title) {
    const [first, ...rest] = brief.split('\n')
    form.value.title = first.slice(0, 300)
    form.value.brief = rest.join('\n').trim()
  }
  creating.value = true
  try {
    const r = await api.createGoal({ ...form.value, autoStart: true })
    const id = r.goal?.id
    ElMessage({ message: '目标已下达,CEO 开始拆解', type: 'success', plain: true })
    form.value = { title: '', brief: '', successCriteria: '', qualityBar: '', deadline: '', budgetTokens: 0 }
    advanced.value = false
    await load()
    if (id) router.push(`/goals/${id}`)
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.response?.data?.message || '下达失败')
  } finally { creating.value = false }
}

function openGoal(id) { router.push(`/goals/${id}`) }

/* ===== 施工动效:进度真正前进过的那条目标,卡片亮一下 ===== */
const gainIds = ref(new Set())
const pulseHot = ref(new Set())
let prevPct = new Map()
let prevPulseVals = null
let ccFirst = true
let gainTimer = null
let pulseTimer = null

function diffGoals(list) {
  const next = new Map(list.map(g => [g.goal.id, pct(g)]))
  if (ccFirst || !motionOk()) {
    prevPct = next
    ccFirst = false
    return
  }
  const hot = new Set()
  for (const [id, p] of next) {
    const before = prevPct.get(id)
    if (before !== undefined && p > before) hot.add(id)
  }
  prevPct = next
  if (hot.size) {
    gainIds.value = hot
    clearTimeout(gainTimer)
    gainTimer = setTimeout(() => { gainIds.value = new Set() }, 1500)
  }
}

function diffPulse() {
  const next = {}
  for (const c of pulseCards.value) next[c.label] = c.value
  if (prevPulseVals && motionOk()) {
    const hot = new Set()
    for (const k of Object.keys(next)) if (prevPulseVals[k] !== undefined && prevPulseVals[k] !== next[k]) hot.add(k)
    if (hot.size) {
      pulseHot.value = hot
      clearTimeout(pulseTimer)
      pulseTimer = setTimeout(() => { pulseHot.value = new Set() }, 700)
    }
  }
  prevPulseVals = next
}

let reqSeq = 0
async function loadGoals() {
  const seq = ++reqSeq
  loading.value = true
  loadError.value = ''
  try {
    const gs = await api.goals()
    if (seq !== reqSeq) return
    goals.value = gs || []
    stale.value = false
    diffGoals(goals.value)
  } catch (e) {
    if (seq !== reqSeq) return
    // 已经拿到过目标就留着看,只标明这不是最新的
    if (goals.value.length) { stale.value = true; return }
    loadError.value = e.code === 'ECONNABORTED' || e.code === 'ETIMEDOUT'
      ? '后端长时间没有响应,可能是服务未启动或版本过旧'
      : '目标列表载入失败'
  } finally { if (seq === reqSeq) loading.value = false }
}

/* 脉搏、巡视与目标列表各走各的请求:目标接口慢,不该把整页一起拖成 0 */
async function loadStats() {
  try {
    const st = await api.stats()
    stats.value = st || {}
    diffPulse()
  } catch (e) { /* 拿不到就留着上一次 */ }
}
async function loadFeed() {
  try {
    const f = await api.opsFeed()
    activities.value = f.activities || []
    openEscalations.value = f.openEscalations || 0
    ceoRunning.value = !!f.ceoRunning
    cycleCount.value = f.cycleCount || 0
    diffFeed()
  } catch (e) { /* 巡视侧栏只是看不见,不影响主流程 */ }
}
async function loadCeo() {
  try {
    const o = await api.org()
    ceoName.value = o?.ceo?.name || 'CEO'
    ceoTitle.value = o?.ceo?.title || ''
  } catch (e) { /* 用兜底文案 */ }
}
function load() {
  return Promise.all([loadGoals(), loadStats(), loadFeed(), loadCeo()])
}

/* ===== CEO 巡视与实时动态 ===== */
const activities = ref([])
const openEscalations = ref(0)
const ceoRunning = ref(false)
const cycleCount = ref(0)
const ceoName = ref('CEO')
const ceoTitle = ref('')
const cycling = ref(false)
const hotFeed = ref(new Set())
let prevFeed = new Set()
let feedFirst = true
let feedTimer = null

function diffFeed() {
  const ids = activities.value.map(a => a.id)
  if (feedFirst || !motionOk()) {
    prevFeed = new Set(ids)
    feedFirst = false
    return
  }
  const hot = new Set(ids.filter(id => !prevFeed.has(id)))
  prevFeed = new Set(ids)
  if (hot.size) {
    hotFeed.value = hot
    clearTimeout(feedTimer)
    feedTimer = setTimeout(() => { hotFeed.value = new Set() }, 1600)
  }
}
const actMap = { REPORT: '进展', ESCALATION: '上报', GOAL: '目标', FEEDBACK: '纠偏', CYCLE: '巡视' }
function actType(t) { return actMap[t] || t || '动态' }
const patrolState = computed(() => {
  if (cycling.value || ceoRunning.value) return '正在巡视公司现状…'
  if (openEscalations.value) return `${openEscalations.value} 条上报等你拍板`
  return '在岗 · 定期巡视,有异常会报到这里'
})
async function runCycle() {
  if (cycling.value) return
  cycling.value = true
  try {
    const r = await api.ceoCycle()
    ElMessage({ message: r.ok ? ('巡视完成:' + (r.summary || '')) : (r.message || '这一轮没跑出结论'),
                type: r.ok ? 'success' : 'warning', plain: true })
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '巡视没成功')
  } finally {
    cycling.value = false
    loadFeed()
  }
}
async function ack(a) {
  try {
    await api.ackActivity(a.id)
    a.status = 'ACKED'
    openEscalations.value = Math.max(0, openEscalations.value - 1)
  } catch (e) { ElMessage.error('确认失败') }
}

let off = null
let timer = null
let feedDebounce = null
onMounted(() => {
  load()
  off = bus.on(evt => {
    if (['ceo-cycle', 'ceo-status', 'escalation', 'activity'].includes(evt.type)) {
      clearTimeout(feedDebounce)
      feedDebounce = setTimeout(loadFeed, 380)
    }
    if (['goal-update', 'goal-completed', 'ceo-cycle', 'budget-exceeded'].includes(evt.type)) {
      clearTimeout(timer)
      timer = setTimeout(() => { loadGoals(); loadStats() }, 600)
    }
  })
})
onBeforeUnmount(() => {
  off && off()
  clearTimeout(timer)
  clearTimeout(feedDebounce)
  clearTimeout(gainTimer)
  clearTimeout(pulseTimer)
  clearTimeout(feedTimer)
})
</script>

<style scoped>
.cc { display: flex; flex-direction: column; gap: 26px; max-width: 1420px; margin: 0 auto; }

/* ===== Hero ===== */
.hero { position: relative; padding: 34px 0 4px; }
.hero-glow {
  position: absolute; left: 8%; right: 8%; top: -60px; height: 360px; pointer-events: none;
  background: radial-gradient(ellipse at 50% 30%, rgba(var(--rgb-glow), .1), transparent 62%);
  filter: blur(30px);
}
.hero-inner { position: relative; }
.eyebrow {
  display: inline-flex; align-items: center; gap: 10px;
  font-family: var(--font-mono); font-size: 10.5px; letter-spacing: .32em;
  color: var(--text-faint);
}
.eyebrow-dot {
  width: 6px; height: 6px; border-radius: 50%; background: var(--glow-cyan);
  box-shadow: 0 0 10px rgba(var(--rgb-glow), .8); animation: breatheDot 3.2s ease-in-out infinite;
}
@keyframes breatheDot { 0%, 100% { opacity: .35; } 50% { opacity: 1; } }
.hero-title {
  margin: 20px 0 0; font-size: clamp(34px, 4.4vw, 58px); line-height: 1.22;
  font-weight: 200; letter-spacing: .06em; color: var(--text-moon);
}
.hero-title .thin {
  font-weight: 200; color: transparent;
  background: linear-gradient(100deg, rgba(var(--rgb-ink), .92), var(--glow-cyan) 62%, rgba(var(--rgb-glow), .55));
  -webkit-background-clip: text; background-clip: text;
}
.hero-sub {
  max-width: 620px; margin: 18px 0 26px; font-size: 14px; line-height: 2;
  color: var(--text-dim); letter-spacing: .04em;
}

/* ===== 目标输入 ===== */
.composer {
  position: relative; padding: 18px 20px 16px; overflow: hidden;
  border-color: rgba(var(--rgb-glow), .13);
}
.composer::before {
  content: ""; position: absolute; inset: 0; pointer-events: none;
  background: radial-gradient(ellipse 60% 100% at 50% 0%, rgba(var(--rgb-glow), .055), transparent 70%);
}
.composer-main { position: relative; display: flex; flex-direction: column; gap: 10px; }
.goal-title-input :deep(.el-input__wrapper) {
  background: transparent !important; box-shadow: none !important; padding: 4px 2px;
}
.goal-title-input :deep(.el-input__inner) {
  font-size: 18px; letter-spacing: .03em; color: var(--text-moon);
  height: 46px;
}
.goal-title-input :deep(.el-input__inner::placeholder) { color: var(--text-faint); }
.goal-brief-input :deep(.el-textarea__inner) {
  background: var(--tint-soft) !important; border-radius: 10px !important;
  font-size: 13px; line-height: 1.85;
}
.preset-row { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 12px 0 14px; }
.preset-label { font-size: 10.5px; letter-spacing: .22em; color: var(--text-faint); margin-right: 2px; }
.preset {
  font-family: var(--font-sans); font-size: 11.5px; color: var(--text-dim);
  background: var(--tint-soft); border: 1px solid var(--border-hair);
  border-radius: 999px; padding: 5px 13px; cursor: pointer;
  transition: all .35s var(--ease-quiet);
}
.preset:hover { color: var(--glow-cyan); border-color: rgba(var(--rgb-glow), .35); background: var(--glow-cyan-faint); }
.composer-foot { display: flex; align-items: center; gap: 14px; padding-top: 4px; }
.advanced-toggle {
  background: none; border: none; color: var(--text-dim); font-family: var(--font-sans);
  font-size: 11.5px; letter-spacing: .12em; cursor: pointer; padding: 0;
  border-bottom: 1px dashed var(--border-hair-strong);
}
.advanced-toggle:hover { color: var(--glow-cyan); }
.quality-chip {
  font-size: 10.5px; color: var(--glow-cyan-dim); letter-spacing: .1em;
  border: 1px solid rgba(var(--rgb-glow), .22); border-radius: 999px; padding: 3px 10px;
}
.provider-warn {
  font-size: 10.5px; color: var(--st-warn); letter-spacing: .1em; cursor: pointer;
  border: 1px solid rgba(var(--rgb-warn), .3); border-radius: 999px; padding: 3px 10px;
}
.spacer { flex: 1; }
/* 两栏都空时唯一要说明的事:按钮不是坏了,是还没写目标 */
.launch-hint { font-size: 11px; color: var(--text-faint); letter-spacing: .06em; }
.launch-btn {
  background: linear-gradient(135deg, rgb(var(--rgb-btn-hi)), rgb(var(--rgb-btn))) !important;
  border: 1px solid rgba(var(--rgb-glow), .4) !important;
  color: var(--btn-fg) !important; letter-spacing: .2em; padding: 10px 24px;
  box-shadow: 0 0 24px rgba(var(--rgb-glow), .12);
  transition: all .4s var(--ease-quiet);
  cursor: pointer;
}
.launch-btn:hover { box-shadow: 0 0 32px rgba(var(--rgb-glow), .28); transform: translateY(-1px); }
/* 待填状态要看得出来:之前用 !important 盖掉了 Element 的禁用样式,
   按钮看着是亮的、点了没反应,比灰色不可点更糟 */
.launch-btn.is-idle {
  opacity: .5;
  box-shadow: none !important;
  filter: saturate(.5);
}
.launch-btn.is-idle:hover { transform: none; box-shadow: none !important; }
.field-label {
  display: block; font-size: 10.5px; letter-spacing: .22em; color: var(--text-faint);
  margin: 0 0 6px 2px;
}
.field-label i {
  font-style: normal; margin-left: 8px; letter-spacing: .08em;
  color: var(--glow-cyan-dim); opacity: .8;
}
/* 「补充背景」和上面一栏之间留出呼吸,免得两栏看起来像一个输入框 */
.composer-main .goal-brief-input { margin-top: 16px; }
.goal-title-input :deep(.el-input__inner) { font-size: 14.5px; letter-spacing: .02em; }
.pulse-hint { font-size: 10px; color: var(--text-faint); letter-spacing: .06em; margin-top: 2px; }
.arrow { margin-left: 6px; font-family: var(--font-mono); }
.advanced { border-top: 1px solid var(--border-hair); margin-top: 14px; padding-top: 16px; display: flex; flex-direction: column; gap: 14px; }
.adv-field { display: flex; flex-direction: column; gap: 7px; flex: 1; }
.adv-field label { font-size: 10.5px; letter-spacing: .2em; color: var(--text-faint); }
.adv-row { display: flex; gap: 14px; }
.fold-enter-active, .fold-leave-active { transition: all .35s var(--ease-quiet); overflow: hidden; }
.fold-enter-from, .fold-leave-to { opacity: 0; max-height: 0; }
.fold-enter-to, .fold-leave-from { opacity: 1; max-height: 420px; }

/* ===== 脉搏 ===== */
.pulse { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.pulse-card {
  position: relative; padding: 15px 16px 13px; overflow: hidden;
  transition: border-color .4s var(--ease-quiet), transform .4s var(--ease-out);
}
.pulse-card:hover { border-color: rgba(var(--rgb-glow), .2); transform: translateY(-2px); }
.pulse-num { font-family: var(--font-mono); font-size: 24px; font-weight: 200; letter-spacing: .02em; }
.pulse-num.cyan { color: var(--glow-cyan); text-shadow: 0 0 16px rgba(var(--rgb-glow), .3); }
.pulse-num.warn { color: var(--st-warn); }
.pulse-label { margin-top: 5px; font-size: 10px; letter-spacing: .2em; color: var(--text-faint); }
.pulse-line {
  position: absolute; left: 16px; right: 16px; bottom: 0; height: 1px;
  background: linear-gradient(90deg, transparent, rgba(var(--rgb-ink), .12), transparent);
}
.pulse-line.cyan { background: linear-gradient(90deg, transparent, rgba(var(--rgb-glow), .45), transparent); }
.pulse-card.is-pulse .pulse-num { animation: popScore .6s cubic-bezier(.34, 1.56, .64, 1) both; }

/* ===== 目标区 ===== */
.goals { display: flex; flex-direction: column; gap: 16px; padding-bottom: 30px; }
.section-head { display: flex; align-items: flex-end; justify-content: space-between; }
.section-title { margin: 0; font-size: 19px; font-weight: 300; letter-spacing: .24em; }
.section-sub { margin: 8px 0 0; font-size: 11.5px; color: var(--text-faint); letter-spacing: .06em; }
.section-tools { display: flex; align-items: center; gap: 14px; }
.goal-count { font-family: var(--font-mono); font-size: 11px; color: var(--glow-cyan-dim); letter-spacing: .1em; }
.refresh-btn { color: var(--text-dim); letter-spacing: .15em; }
.refresh-btn:hover { color: var(--glow-cyan); }

.empty { padding: 54px 20px; text-align: center; color: var(--text-faint); letter-spacing: .18em; font-size: 13px; }
.empty-mark {
  font-size: 34px; font-weight: 200; color: rgba(var(--rgb-ink), .1);
  border: 1px dashed var(--border-hair-strong); width: 74px; height: 74px; border-radius: 18px;
  display: grid; place-items: center; margin: 0 auto 18px;
}

.goal-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(390px, 1fr)); gap: 16px; }
.goal-card {
  position: relative; padding: 20px 22px 16px; cursor: pointer; overflow: hidden;
  transition: transform .45s var(--ease-out), border-color .45s var(--ease-quiet), box-shadow .45s var(--ease-quiet);
  animation: cardRise .7s var(--ease-out) both;
}
@keyframes cardRise { from { opacity: 0; transform: translateY(14px); } to { opacity: 1; transform: none; } }
.goal-card:hover {
  transform: translateY(-4px);
  border-color: rgba(var(--rgb-glow), .25);
  box-shadow: var(--shadow-hover), 0 0 0 1px rgba(var(--rgb-glow), .06);
}
.goal-card::after {
  content: ""; position: absolute; left: 0; top: 0; bottom: 0; width: 2px;
  background: linear-gradient(180deg, transparent, var(--glow-cyan), transparent);
  opacity: 0; transition: opacity .4s var(--ease-quiet);
}
.goal-card:hover::after { opacity: .55; }
.goal-card.st-done::after { background: linear-gradient(180deg, transparent, var(--st-ok), transparent); opacity: .4; }
.goal-card.st-review::after { background: linear-gradient(180deg, transparent, var(--st-warn), transparent); opacity: .55; }

/* 施工进行中:生产线进度条走流纹,光带缓慢扫过卡片 */
.goal-card.is-live::before {
  content: ""; position: absolute; left: 0; right: 0; top: 0; height: 34%; pointer-events: none;
  background: linear-gradient(180deg, rgba(var(--rgb-glow), .07), transparent);
  animation: liveSweep 4.4s var(--ease-quiet) infinite;
}
@keyframes liveSweep {
  0% { transform: translateY(-110%); opacity: 0; }
  24% { opacity: 1; }
  100% { transform: translateY(320%); opacity: 0; }
}
.goal-card.is-live .bar i {
  background-image: repeating-linear-gradient(115deg, var(--hatch) 0 4px, transparent 4px 12px);
  background-size: 24px 100%;
  animation: flowStripes 1.2s linear infinite;
}
@keyframes flowStripes { to { background-position: 24px 0; } }
/* 这一轮真的往前推进了 */
.goal-card.is-gain { border-color: rgba(var(--rgb-glow), .3); }
.goal-card.is-gain .pct { animation: popScore .6s cubic-bezier(.34, 1.56, .64, 1) both; }

.card-top { display: flex; align-items: center; justify-content: space-between; }
/* 骨架屏:后端还没吐数据时,先给出版式而非空白(.sk 基元在 brand.css) */
.goal-card.is-skeleton { pointer-events: none; animation: none; }
.goal-card.is-skeleton:nth-child(2) .sk { animation-delay: .2s; }
.goal-card.is-skeleton:nth-child(3) .sk { animation-delay: .4s; }
.load-fail p { margin: 0 0 16px; }
.status-chip {
  display: inline-flex; align-items: center; gap: 7px;
  font-size: 10.5px; letter-spacing: .12em; padding: 4px 11px; border-radius: 999px;
  background: var(--chip-bg); color: var(--text-dim);
}
.sc-dot { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.sc-active { color: var(--glow-cyan); background: var(--glow-cyan-faint); }
.sc-active .sc-dot { box-shadow: 0 0 8px rgba(var(--rgb-glow), .8); animation: breatheDot 2.6s infinite; }
.sc-planning { color: var(--st-plan); background: rgba(var(--rgb-plan), .1); }
.sc-review { color: var(--st-warn); background: rgba(var(--rgb-warn), .1); }
.sc-done { color: var(--st-ok); background: rgba(var(--rgb-ok), .1); }
.sc-paused, .sc-failed { color: var(--st-error); background: rgba(var(--rgb-error), .09); }
.goal-no { font-family: var(--font-mono); font-size: 9.5px; letter-spacing: .18em; color: var(--text-faint); }

.goal-name { margin: 16px 0 0; font-size: 19px; font-weight: 300; letter-spacing: .04em; line-height: 1.5; }
.goal-desc {
  margin: 9px 0 0; font-size: 12.5px; line-height: 1.8; color: var(--text-dim);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  min-height: 45px;
}
.goal-depts { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 13px; }
.dept-pill {
  font-size: 10px; color: color-mix(in srgb, var(--dc) 80%, white);
  border: 1px solid color-mix(in srgb, var(--dc) 22%, transparent);
  background: color-mix(in srgb, var(--dc) 6%, transparent);
  border-radius: 999px; padding: 3px 9px; letter-spacing: .04em;
}
.dept-pill i { font-style: normal; opacity: .6; }

.progress-block { margin-top: 17px; }
.progress-line { display: flex; align-items: center; gap: 12px; }
.bar {
  flex: 1; height: 3px; border-radius: 3px; overflow: hidden;
  background: rgba(var(--rgb-ink), .07);
}
.bar i {
  display: block; height: 100%; border-radius: 3px;
  background: linear-gradient(90deg, rgba(var(--rgb-glow), .55), var(--glow-cyan));
  box-shadow: 0 0 10px rgba(var(--rgb-glow), .45);
  transition: width .8s var(--ease-out);
}
.pct { font-family: var(--font-mono); font-size: 11px; color: var(--glow-cyan); }
.progress-sub { display: flex; justify-content: space-between; margin-top: 8px; font-size: 10.5px; color: var(--text-faint); letter-spacing: .05em; }

.goal-metrics { display: flex; gap: 26px; margin-top: 16px; padding-top: 15px; border-top: 1px solid var(--border-hair); }
.metric { display: flex; flex-direction: column; gap: 3px; }
.metric b { font-family: var(--font-mono); font-size: 15px; font-weight: 400; color: var(--text-moon); }
.metric b.good { color: var(--glow-cyan); }
.metric span { font-size: 9.5px; letter-spacing: .16em; color: var(--text-faint); }

.blocked {
  margin-top: 13px; font-size: 11.5px; color: var(--st-warn);
  background: rgba(var(--rgb-warn), .06); border: 1px solid rgba(var(--rgb-warn), .22);
  border-radius: 8px; padding: 8px 11px; display: flex; align-items: center; gap: 8px;
}
.blocked-icon {
  width: 15px; height: 15px; border-radius: 50%; display: grid; place-items: center;
  background: rgba(var(--rgb-warn), .18); font-size: 10px; font-family: var(--font-mono);
}
.card-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 15px; }
.created { font-family: var(--font-mono); font-size: 10px; color: var(--text-faint); }
.enter { font-size: 11.5px; color: var(--text-dim); letter-spacing: .12em; transition: color .35s var(--ease-quiet); }
.enter i { font-style: normal; margin-left: 4px; }
.goal-card:hover .enter { color: var(--glow-cyan); }

/* ===== CEO 巡视 + 实时动态侧栏 ===== */
.body-grid { display: grid; grid-template-columns: minmax(0, 1fr) 328px; gap: 22px; align-items: start; }
.goals { min-width: 0; }
.side-col { display: flex; flex-direction: column; gap: 16px; position: sticky; top: 86px; }

.patrol { position: relative; overflow: hidden; padding: 18px 20px 16px; display: flex; flex-direction: column; gap: 12px; }
.patrol-top { display: flex; align-items: center; gap: 13px; }
.patrol-orbit { position: relative; width: 34px; height: 34px; flex: none; border-radius: 50%;
  border: 1px solid rgba(var(--rgb-glow), .3); display: grid; place-items: center; }
.patrol-orbit i { position: absolute; width: 4px; height: 4px; border-radius: 50%; background: var(--glow-cyan); }
.patrol-orbit i:nth-child(1) { animation: orbitSpin 3.4s linear infinite; }
.patrol-orbit i:nth-child(2) { animation: orbitSpin 3.4s linear infinite 1.13s; opacity: .6 }
.patrol-orbit i:nth-child(3) { animation: orbitSpin 3.4s linear infinite 2.26s; opacity: .35 }
@keyframes orbitSpin {
  from { transform: rotate(0deg) translateX(15px); }
  to { transform: rotate(360deg) translateX(15px); }
}
.is-run .patrol-orbit { animation: breath var(--dur-breath) var(--ease-quiet) infinite; }
.patrol-who { font-size: 14px; letter-spacing: .1em; color: var(--text-moon); }
.patrol-title { font-family: var(--font-mono); font-size: 9.5px; letter-spacing: .16em; color: var(--text-faint); margin-top: 3px; }
.patrol-state { margin: 0; font-size: 11.5px; line-height: 1.7; color: var(--text-dim); }
.is-run .patrol-state { color: var(--glow-cyan); }
.patrol-meta { display: flex; flex-wrap: wrap; gap: 14px; font-family: var(--font-mono); font-size: 9.5px; color: var(--text-faint); letter-spacing: .06em; }
.patrol-meta b { color: var(--text-dim); font-weight: 400; }
.patrol-act { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding-top: 2px; border-top: 1px dashed var(--border-hair); }

.wire { padding: 16px 18px 14px; display: flex; flex-direction: column; gap: 10px; }
.wire-head { display: flex; align-items: baseline; justify-content: space-between; }
.wire-head h2 { margin: 0; font-size: 13px; font-weight: 400; letter-spacing: .18em; color: var(--text-moon); }
.wire-head span { font-family: var(--font-mono); font-size: 10px; color: var(--text-faint); }
.wire-empty { font-size: 11.5px; color: var(--text-faint); padding: 10px 0 4px; }
.wire-list { display: flex; flex-direction: column; max-height: 430px; overflow: auto; }
.wire-item { display: flex; gap: 10px; padding: 9px 0; border-bottom: 1px dashed var(--border-hair); }
.wire-item:last-child { border-bottom: none; }
.wire-dot { width: 5px; height: 5px; border-radius: 50%; margin-top: 6px; flex: none; background: rgba(var(--rgb-glow), .5); }
.wt-escalation .wire-dot { background: var(--st-error); box-shadow: 0 0 7px rgba(var(--rgb-error), .6); }
.wire-item.is-open .wire-dot { animation: errPulse 2.4s var(--ease-quiet) infinite; }
.wire-body { flex: 1; min-width: 0; }
.wire-top { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.wire-type { font-size: 9px; letter-spacing: .16em; color: var(--text-faint); }
.wt-escalation .wire-type { color: var(--st-error); }
.wire-time { font-family: var(--font-mono); font-size: 9px; color: var(--text-faint); }
.wire-text { margin: 4px 0 0; font-size: 11px; line-height: 1.65; color: var(--text-dim);
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.wire-ack { margin-top: 4px; color: var(--st-warn); }
.side-more { margin-top: 2px; font-size: 10.5px; letter-spacing: .14em; color: var(--text-dim); cursor: pointer; text-align: right; }
.side-more:hover { color: var(--glow-cyan); }
.side-more i { font-style: normal; margin-left: 3px; }

@media (max-width: 1180px) {
  .body-grid { grid-template-columns: 1fr; }
  .side-col { position: static; flex-direction: row; align-items: flex-start; }
  .patrol, .wire { flex: 1; min-width: 0; }
}
@media (max-width: 1100px) {
  .pulse { grid-template-columns: repeat(3, 1fr); }
}@media (max-width: 760px) {
  .pulse { grid-template-columns: repeat(2, 1fr); }
  .goal-grid { grid-template-columns: 1fr; }
  .adv-row { flex-direction: column; }
}
</style>
