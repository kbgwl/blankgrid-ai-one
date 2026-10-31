<template>
  <div class="detail" v-if="data">
    <!-- 项目头 -->
    <header class="project-head glass">
      <button class="back" @click="$router.push('/')">
        <span class="back-arrow">←</span> 指挥台
      </button>

      <div class="head-main">
        <div class="head-eyebrow">
          <span class="head-status" :class="'hs-' + (goal.status || '').toLowerCase()">
            <i></i>{{ statusText }}
          </span>
          <span class="head-id">PROJECT · {{ String(goal.id).padStart(3, '0') }}</span>
          <span v-if="goal.deadline" class="head-deadline">截止 {{ fmtDate(goal.deadline) }}</span>
        </div>
        <h1 class="head-title">{{ goal.title }}</h1>
        <p class="head-summary">{{ goal.ceoSummary || 'CEO 正在理解目标并拆解部门工作流…' }}</p>
        <div class="head-meta">
          <span><b>{{ data.workstreams }}</b> 部门工作流</span>
          <span><b>{{ (rp.on ? rp.del.size : data.done) }}/{{ data.deliverables }}</b> 交付完成</span>
          <span><b>{{ rp.on ? rp.art.size : data.artifacts }}</b> 成果</span>
          <span><b>{{ fmtTokens(rp.on ? Math.round((data.tokens || 0) * pctShown / 100) : data.tokens) }}</b> tokens</span>
          <span v-if="goal.finalScore && (!rp.on || rp.step >= 3)"><b class="score">{{ goal.finalScore }}</b> 评审均分</span>
        </div>
      </div>

      <div class="head-right">
        <div class="ring" :style="{ '--p': pctShown }">
          <div class="ring-inner">
            <span class="ring-num">{{ pctShown }}<i>%</i></span>
            <span class="ring-label">进度</span>
          </div>
        </div>
        <div class="head-actions">
          <el-button v-if="canStart" size="small" type="primary" plain :loading="acting" @click="doAction('start')">开始推进</el-button>
          <el-button v-if="canPause" size="small" plain :loading="acting" @click="doAction('pause')">暂停</el-button>
          <el-button v-if="canResume" size="small" type="primary" plain :loading="acting" @click="doAction('resume')">恢复</el-button>
          <el-button v-if="canAccept" size="small" class="accept-btn" :loading="acting" @click="accept">复核归档</el-button>
          <el-button v-if="canReplan" size="small" plain :loading="acting" @click="doAction('replan')">重新拆解</el-button>
          <el-button v-if="stuckCount > 0" size="small" plain :loading="acting" @click="retryAllBlocked">
            重试受阻 {{ stuckCount }}
          </el-button>
          <el-dropdown trigger="click" @command="exportAs">
            <el-button size="small" text class="export-btn">导出 <i class="caret">▾</i></el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="zip">zip 工程包(Markdown 原样)</el-dropdown-item>
                <el-dropdown-item command="docx" divided>Word 全书 · .docx</el-dropdown-item>
                <el-dropdown-item command="pdf">PDF 全书 · .pdf</el-dropdown-item>
                <el-dropdown-item command="pptx">PPT 汇报 · .pptx</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-dropdown trigger="click" @command="exportToDir">
            <el-button size="small" text class="export-btn" :loading="dirExporting">存到目录 <i class="caret">▾</i></el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="all">全部格式(Markdown + Word + PDF + PPT)</el-dropdown-item>
                <el-dropdown-item command="docx" divided>只要 Word</el-dropdown-item>
                <el-dropdown-item command="pdf">只要 PDF</el-dropdown-item>
                <el-dropdown-item command="pptx">只要 PPT</el-dropdown-item>
                <el-dropdown-item command="md">只要 Markdown</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </header>

    <!-- 缺料:成果卡在这里不是能力问题,是缺事实。必须显眼,否则用户只会看到一堆"受阻" -->
    <section v-if="needsInputList.length" class="needs glass">
      <div class="needs-head">
        <span class="needs-mark">需要你补 3 分钟</span>
        <h3>有 {{ needsInputList.length }} 件事公司查不到,只能你来给</h3>
        <p>它们不补齐,这部分成果只能靠猜。填完会立刻重新排队,已确认的事实此后全程生效。</p>
      </div>
      <ul class="needs-list">
        <li v-for="(n, i) in needsInputList" :key="i"><span class="needs-no">{{ String(i + 1).padStart(2, '0') }}</span>{{ n }}</li>
      </ul>
      <div class="needs-form">
        <el-input v-model="factsText" type="textarea" :rows="4" resize="none"
                  placeholder="按上面的编号逐条写清楚,例如:1) 产品共 11 个功能:…… 2) 近 12 个月付费客户 37 家……" />
        <div class="needs-actions">
          <span class="needs-hint">写清楚比写多重要;不确定的写“暂不确定”,也比我编一个强。</span>
          <el-button type="primary" size="small" :loading="submittingFacts" :disabled="!factsText.trim()" @click="submitFacts">提交补料</el-button>
        </div>
      </div>
    </section>

    <!-- 已确认事实:让创始人知道"公司现在以什么为准",避免同一件事反复问 -->
    <section v-else-if="goal.providedFacts" class="facts glass">
      <div class="facts-head">
        <span class="facts-tag">已确认事实</span>
        <span class="facts-hint">后续拆解、执行、评审都以此为准</span>
        <button class="facts-toggle" @click="factsOpen = !factsOpen">{{ factsOpen ? '收起' : '展开' }}</button>
      </div>
      <pre v-if="factsOpen" class="facts-body">{{ goal.providedFacts }}</pre>
    </section>

    <!-- 预算用尽:不再静默停住,直接给追加入口 -->
    <section v-if="budgetExhausted" class="budget glass">
      <div class="budget-head">
        <span class="budget-tag">预算用尽</span>
        <h3>已消耗 {{ fmtTokens(goal.spentTokens) }} / {{ fmtTokens(goal.budgetTokens) }} token,项目停在半路</h3>
        <p>剩下的交付物还排在队列里,加一笔额度就会接着跑,不用重新拆解。</p>
      </div>
      <div class="budget-actions">
        <el-button v-for="n in [100000, 300000, 500000]" :key="n" size="small" plain
                   :disabled="addingBudget" @click="addBudget(n)">+{{ fmtTokens(n) }}</el-button>
        <el-input-number v-model="extraBudget" :min="10000" :step="10000" size="small" :controls="false" />
        <el-button type="primary" size="small" :loading="addingBudget" @click="addBudget(extraBudget)">追加并继续</el-button>
      </div>
    </section>

    <!-- 四步生产线:拆解 → 分派 → 交付 → 归档,当前卡住的那一步在亮 -->
    <nav class="pipeline glass" :class="{ 'is-rp': rp.on }" aria-label="施工进度">
      <div v-for="s in stages" :key="s.no" class="pstep" :class="'ps-' + s.state">
        <span class="pnum">{{ s.no }}</span>
        <b class="pname">{{ s.name }}</b>
        <small class="pnote">{{ s.note }}</small>
      </div>
    </nav>

    <div class="detail-grid">
      <main class="main-col">
        <!-- 目标简报 -->
        <section class="brief glass">
          <div class="brief-block" v-if="goal.brief">
            <div class="brief-label">目标说明</div>
            <p>{{ goal.brief }}</p>
          </div>
          <div class="brief-block" v-if="goal.successCriteria">
            <div class="brief-label">成功标准</div>
            <p>{{ goal.successCriteria }}</p>
          </div>
          <div class="brief-block" v-if="goal.qualityBar">
            <div class="brief-label">质量底线</div>
            <p>{{ goal.qualityBar }}</p>
          </div>
          <div class="brief-block" v-if="goal.planNotes">
            <div class="brief-label">CEO 拆解思路</div>
            <p>{{ goal.planNotes }}</p>
          </div>
        </section>

        <!-- 部门工作流 -->
        <section class="streams">
          <div class="streams-head">
            <h2>部门工作流</h2>
            <span class="streams-sub">每个部门由负责人拆解并验收</span>
          </div>

          <div v-if="!streamList.length || rp.planning" class="planning glass">
            <div class="planning-orbit"><span></span><span></span><span></span></div>
            <p class="planning-title">CEO 正在拆解目标</p>
            <p class="planning-sub">分析目标 → 匹配部门 → 分配负责人 → 下发验收标准。通常需要 20-60 秒。</p>
          </div>

          <div v-for="(w, i) in streamList" :key="w.workstream.id" class="stream glass"
               :class="{ 'is-live': w.workstream.status === 'ACTIVE', 'rp-hidden': rpHide.stream(i), 'm-rise': rp.on && i === rp.streams - 1 }"
               :style="{ '--dc': deptColor(w.workstream.department?.colorKey) }">
            <div class="stream-rail">
              <span class="rail-dot" :class="'rs-' + (w.workstream.status || '').toLowerCase()"></span>
              <span class="rail-line" v-if="i < streamList.length - 1"></span>
            </div>

            <div class="stream-body">
              <div class="stream-top">
                <div class="stream-dept">
                  <span class="dept-name">{{ w.workstream.department?.name || '部门' }}</span>
                  <span class="stream-status" :class="'ss-' + (w.workstream.status || '').toLowerCase()">{{ wsStatus(w.workstream.status) }}</span>
                </div>
                <div class="stream-lead">
                  <span class="lead-label">负责人</span>
                  <span class="lead-name">{{ w.workstream.lead?.name || '未指定' }}</span>
                </div>
              </div>

              <h3 class="stream-title">{{ w.workstream.title }}</h3>
              <p class="stream-brief" v-if="w.workstream.brief">{{ w.workstream.brief }}</p>

              <div class="stream-progress">
                <div class="bar"><i :style="{ width: barPct(w) + '%' }"></i></div>
                <span>{{ rp.on ? (w.deliverables || []).filter(rpShowDel).length : w.done }}/{{ w.total }}</span>
              </div>

              <div class="deliverables">
                <div v-for="d in w.deliverables" :key="d.id" class="delivery"
                     :class="['ds-' + (d.status || '').toLowerCase(), { 'm-land': flash.del.has(d.id), 'rp-hidden': rpHide.del(d) }]">
                  <span class="d-status" :class="'dsc-' + (d.status || '').toLowerCase()">{{ dStatus(d.status) }}</span>
                  <div class="d-main">
                    <div class="d-title-line">
                      <span class="d-title">{{ d.title }}</span>
                      <span class="d-score" v-if="d.reviewScore && !rpHide.score(d)" :class="{ 'm-pop': flash.score.has(d.id) }">{{ d.reviewScore }} 分</span>
                    </div>
                    <div class="d-meta">
                      <span>{{ d.owner?.name || '未指定' }}</span>
                      <span v-if="d.outputType">{{ d.outputType }}</span>
                      <span v-if="d.attempts > 1">第 {{ d.attempts }} 次执行</span>
                      <span v-if="verCount.get(d.id) > 1" class="d-vers" @click="openArtifact(artifactOf(d))">
                        共 {{ verCount.get(d.id) }} 版
                      </span>
                      <span v-if="depText(d)">依赖 {{ depText(d) }}</span>
                    </div>
                    <p class="d-notes" v-if="(d.status === 'BLOCKED' || d.status === 'WAITING' || d.status === 'CANCELLED') && d.reviewNotes">{{ d.reviewNotes }}</p>
                  </div>
                  <div class="d-actions">
                    <el-button v-if="artifactOf(d)" text size="small" class="d-art" @click="openArtifact(artifactOf(d))">
                      查看成果
                    </el-button>
                    <el-button v-if="d.status === 'BLOCKED'" text size="small" class="d-retry"
                               title="把已用轮次清零,等于重新来过" @click="retryDeliverable(d)">
                      重试
                    </el-button>
                    <el-button v-if="d.status === 'BLOCKED'" text size="small" class="d-retry"
                               title="上限 +1 轮,历史轮次仍算数 —— 差一口气的成果用这个,不要用重试" @click="grantRound(d)">
                      再给一轮
                    </el-button>
                    <el-button v-if="d.status === 'BLOCKED' || d.status === 'WAITING'" text size="small" class="d-drop"
                               title="不再投入这项。目标按剩下的项结项,砍掉的不计入完成度" @click="dropDeliverable(d)">
                      砍掉
                    </el-button>
                  </div>
                </div>
              </div>

              <div class="stream-foot" v-if="w.workstream.acceptanceCriteria">
                <span class="af-label">验收标准</span>
                <p>{{ w.workstream.acceptanceCriteria }}</p>
              </div>
              <div class="stream-foot" v-if="w.workstream.status === 'BLOCKED'">
                <el-button size="small" text class="ws-retry" @click="retryWorkstream(w.workstream)">重置该工作流并重试</el-button>
              </div>
            </div>
          </div>
        </section>
      </main>

      <!-- 侧栏 -->
      <aside class="side-col">
        <section class="side-block glass">
          <div class="side-head">
            <h2>成果库</h2>
            <span>{{ rp.on ? rp.art.size : artifacts.length }}</span>
          </div>
          <div v-if="!artifacts.length" class="side-empty">评审通过的成果会自动归档到这里</div>
          <div v-for="a in artifacts" :key="a.id" class="art-item"
               :class="{ 'm-land': flash.art.has(a.id), 'rp-hidden': rpHide.art(a) }" @click="openArtifact(a.id)">
            <div class="art-line">
              <span class="art-type">{{ a.type }}</span>
              <span class="art-score" :class="{ good: a.score >= threshold }">{{ a.score || '—' }}</span>
            </div>
            <div class="art-name">{{ a.title }}</div>
            <div class="art-meta">
              <span>v{{ a.version }}</span>
              <span>{{ a.author || '—' }}</span>
              <span :class="'ast-' + (a.status || '').toLowerCase()">{{ artStatus(a.status) }}</span>
            </div>
          </div>
          <div class="side-more" v-if="artifacts.length" @click="$router.push('/artifacts')">进入成果库 →</div>
        </section>

        <section class="side-block glass">
          <div class="side-head">
            <h2>项目动态</h2>
            <span>{{ rp.on ? rp.feed.size : activities.length }}</span>
          </div>
          <div v-if="!activities.length" class="side-empty">暂无动态</div>
          <div class="feed">
            <div v-for="a in activities.slice(0, 30)" :key="a.id" class="feed-item"
                 :class="['ft-' + (a.type || '').toLowerCase(), { 'm-fresh': flash.act.has(a.id), 'rp-hidden': rpHide.feed(a) }]">
              <span class="feed-dot"></span>
              <div class="feed-body">
                <div class="feed-top">
                  <span class="feed-type">{{ activityType(a.type) }}</span>
                  <span class="feed-time">{{ fmt(a.createdAt) }}</span>
                </div>
                <p class="feed-text">{{ a.content }}</p>
              </div>
            </div>
          </div>
        </section>
      </aside>
    </div>

    <ArtifactDrawer v-model="artifactOpen" :artifact-id="artifactId" />
  </div>

  <div v-else class="detail-loading">
    <div v-if="loadError" class="load-fail glass">
      <div class="fail-mark">!</div>
      <p>{{ loadError }}</p>
      <div class="fail-row">
        <el-button size="small" plain :loading="retrying" @click="retry">重试</el-button>
        <el-button size="small" text @click="$router.push('/')">返回指挥台</el-button>
      </div>
    </div>
    <template v-else>
      <div class="loading-orbit"><span></span><span></span><span></span></div>
      <p>载入项目……</p>
      <div class="detail-skeleton" aria-hidden="true">
        <i class="sk"></i>
        <i class="sk"></i>
        <i class="sk"></i>
        <i class="sk"></i>
        <i class="sk"></i>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, bus } from '../api'
import { motionOk, tween } from '../utils/motion'
import ArtifactDrawer from '../components/ArtifactDrawer.vue'

const route = useRoute()
const data = ref(null)
const acting = ref(false)
const artifactOpen = ref(false)
const artifactId = ref(null)
const threshold = ref(85)

/* ===== 施工动效:状态差分后只让变化的行「落地」,不做全页重刷 ===== */
const flash = ref({ del: new Set(), score: new Set(), art: new Set(), act: new Set() })
const rp = reactive({ on: false, planning: false, step: -1, streams: 0,
                      del: new Set(), score: new Set(), art: new Set(), feed: new Set() })
const pctShown = ref(0)
let prevStatus = new Map()
let prevScore = new Map()
let prevArt = new Set()
let prevAct = new Set()
let firstLoad = true
let clearTimer = null
let cancelTween = null

function diffMotion(g) {
  const nextDel = new Map()
  const nextScore = new Map()
  for (const w of g.workstreamList || []) {
    for (const d of w.deliverables || []) {
      nextDel.set(d.id, d.status)
      nextScore.set(d.id, d.reviewScore || null)
    }
  }
  const nextArt = new Set((g.artifactList || []).map(a => a.id))
  const nextAct = new Set((g.activities || []).map(a => a.id))

  if (!firstLoad && motionOk() && !rp.on) {
    const del = new Set()
    const score = new Set()
    for (const [id, st] of nextDel) {
      if (prevStatus.has(id) && prevStatus.get(id) !== st) del.add(id)
      const sc = nextScore.get(id)
      if (prevScore.has(id) && prevScore.get(id) !== sc && sc != null) score.add(id)
    }
    const art = new Set()
    for (const id of nextArt) if (!prevArt.has(id)) art.add(id)
    const act = new Set()
    for (const id of nextAct) if (!prevAct.has(id)) act.add(id)
    if (del.size || score.size || art.size || act.size) {
      flash.value = { del, score, art, act }
      clearTimeout(clearTimer)
      clearTimer = setTimeout(() => {
        flash.value = { del: new Set(), score: new Set(), art: new Set(), act: new Set() }
      }, 1400)
    }
  }

  prevStatus = nextDel
  prevScore = nextScore
  prevArt = nextArt
  prevAct = nextAct
  firstLoad = false
}

const goal = computed(() => data.value?.goal || {})
const streamList = computed(() => data.value?.workstreamList || [])
const artifacts = computed(() => data.value?.artifactList || [])
// 每条交付物已经产出过几版(返工一次就多一版)。有历史版本才显示,没返工过的不占位置。
const verCount = computed(() => {
  const m = new Map()
  for (const a of artifacts.value) {
    if (a.deliverableId) m.set(a.deliverableId, (m.get(a.deliverableId) || 0) + 1)
  }
  return m
})
const activities = computed(() => data.value?.activities || [])

const pct = computed(() => {
  if (!data.value) return 0
  if (goal.value.status === 'DONE') return 100
  if (data.value.deliverables > 0) return Math.round(data.value.done * 100 / data.value.deliverables)
  return goal.value.progress || 0
})
watch(pct, (to, from) => {
  if (from === undefined) { pctShown.value = to; return }
  if (rp.on) return
  cancelTween && cancelTween()
  cancelTween = tween(pctShown.value, to, 850, v => { pctShown.value = v })
}, { immediate: true })
const allDone = computed(() => data.value?.deliverables > 0 && data.value?.done === data.value?.deliverables)
const canStart = computed(() => ['PLANNING', 'FAILED'].includes(goal.value.status) && !data.value?.workstreams)
const canPause = computed(() => ['ACTIVE', 'PLANNING', 'REVIEW'].includes(goal.value.status))
const canResume = computed(() => ['PAUSED', 'FAILED'].includes(goal.value.status))
const canAccept = computed(() => goal.value.status === 'REVIEW' || (allDone.value && goal.value.status !== 'DONE'))
const canReplan = computed(() => ['FAILED', 'REVIEW'].includes(goal.value.status))
// 受阻 + 等上游的条数:账户欠费、换模型、重启之后,一键全部推回队列
const stuckCount = computed(() => {
  const rows = (data.value.workstreamList || []).flatMap(w => w.deliverables || [])
  return rows.filter(d => d.status === 'BLOCKED' || d.status === 'WAITING').length
})

const statusMap = {
  PLANNING: 'CEO 拆解中', ACTIVE: '推进中', REVIEW: '待创始人复核',
  DONE: '已归档', PAUSED: '已暂停', FAILED: '拆解失败'
}
const statusText = computed(() => statusMap[goal.value.status] || goal.value.status || '—')

const dsMap = {
  PENDING: '待开始', READY: '待执行', RUNNING: '执行中', REVIEW: '评审中',
  REWORK: '返工中', DONE: '已完成', BLOCKED: '受阻', WAITING: '等上游', CANCELLED: '已砍掉'
}
const wsMap = { PLANNING: '拆解中', ACTIVE: '推进中', DONE: '已完成', BLOCKED: '受阻', PAUSED: '已暂停' }
const artMap = { DRAFT: '草稿', REVIEW: '待评审', APPROVED: '已通过', REJECTED: '未通过' }
const actMap = { REPORT: '进展', ESCALATION: '上报', GOAL: '目标', FEEDBACK: '纠偏' }

function dStatus(s) { return dsMap[s] || s || '—' }
function wsStatus(s) { return wsMap[s] || s || '—' }
function artStatus(s) { return artMap[s] || s || '—' }
function activityType(s) { return actMap[s] || s || '动态' }
function deptColor(key) {
  return { product: 'var(--dept-product)', tech: 'var(--dept-tech)', design: 'var(--dept-design)', market: 'var(--dept-market)', ops: 'var(--dept-ops)' }[key] || 'var(--dept-tech)'
}
function artifactOf(d) { return d.artifactId || d.lastArtifactId || null }
function depText(d) {
  if (!d.dependencies) return ''
  return String(d.dependencies).split(',').filter(Boolean).length + ' 项'
}
function fmtTokens(n) {
  n = n || 0
  return n >= 10000 ? (n / 10000).toFixed(1) + ' 万' : String(n)
}
function fmt(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}
function fmtDate(iso) { return new Date(iso).toLocaleDateString('zh-CN') }

/* ===== 四步生产线:一眼看出「现在卡在哪个部门」;重放时这一步在亮就是当时卡在这 ===== */
const stageDefs = [
  { no: '01', name: '目标定稿' },
  { no: '02', name: '拆解工作流' },
  { no: '03', name: '部门交付' },
  { no: '04', name: '质检归档' }
]
const stages = computed(() => {
  const g = goal.value
  const d = data.value || {}
  const total = d.deliverables || 0
  const settled = [true, (d.workstreams || 0) > 0, total > 0 && d.done === total, g.status === 'DONE']
  const stuckAt = settled.indexOf(false)
  const notes = [
    fmt(g.createdAt),
    settled[1] ? `${d.workstreams} 条` : '等待下发',
    settled[2] ? `${total} 项` : `${d.done || 0}/${total || 0} 项`,
    settled[3] ? `${g.finalScore || '—'} 分` : `底线 ${threshold.value} 分`
  ]
  return stageDefs.map((s, i) => {
    let state
    if (rp.on) state = i < rp.step ? 'done' : (i === rp.step ? 'run' : 'wait')
    else state = settled[i] ? 'done' : (i === stuckAt ? 'run' : 'wait')
    return { ...s, state, note: state === 'wait' ? '待进行' : notes[i] }
  })
})

/* ===== 播放施工过程:复用实时差分那套落地类,按时间线把已发生的事重演一遍 ===== */
let rpTimers = []
function rpSay(label, busy) { bus.emit({ type: 'ui-replay-state', busy, label }) }
function at(ms, fn) { rpTimers.push(setTimeout(fn, ms)) }
function rpLand(kind, id) {
  flash.value = { ...flash.value, [kind]: new Set([...flash.value[kind], id]) }
}
function rpEnd() {
  rpTimers.forEach(clearTimeout)
  rpTimers = []
  clearTimeout(clearTimer)
  rp.on = false
  rp.planning = false
  rp.step = -1
  rp.streams = 0
  rp.del.clear(); rp.score.clear(); rp.art.clear(); rp.feed.clear()
  flash.value = { del: new Set(), score: new Set(), art: new Set(), act: new Set() }
  cancelTween && cancelTween()
  pctShown.value = pct.value
  rpSay('', false)
}
function rpToggle() {
  if (rp.on) { rpEnd(); return }
  if (!data.value) {
    ElMessage({ message: '项目还没载入完,载入后才能重放施工过程', plain: true })
    return
  }
  if (!motionOk()) {
    ElMessage({ message: '系统偏好设置了「减少动态效果」,施工动效已关闭', plain: true })
    return
  }
  const g = data.value
  const streams = streamList.value
  const dels = streams.reduce((acc, w) => acc.concat(w.deliverables || []), [])
  const scored = dels.filter(x => x.reviewScore)
  const arts = artifacts.value
  const feed = activities.value.slice(0, 30)
  const totalDel = g.deliverables || dels.length || 1

  rp.on = true
  rp.planning = true
  rp.step = 0
  rp.streams = 0
  rpSay('CEO 拆解中', true)
  cancelTween && cancelTween()
  pctShown.value = 0

  let t = 1400
  at(t, () => { rp.planning = false; rp.step = 1; rpSay('分派部门', true) })
  streams.forEach((w, i) => at(t + 260 + i * 420, () => { rp.streams = i + 1 }))
  t += 260 + streams.length * 420 + 240
  at(t, () => { rp.step = 2; rpSay('部门执行', true) })
  dels.forEach((d, i) => at(t + 240 + i * 270, () => {
    rp.del.add(d.id)
    rpLand('del', d.id)
    pctShown.value = Math.min(96, Math.round(rp.del.size * 100 / totalDel))
  }))
  t += 240 + dels.length * 270 + 240
  at(t, () => { rp.step = 3; rpSay('质量评审', true) })
  scored.forEach((d, i) => at(t + 220 + i * 230, () => { rp.score.add(d.id); rpLand('score', d.id) }))
  t += 220 + scored.length * 230 + 260
  at(t, () => rpSay('成果归档', true))
  arts.forEach((a, i) => at(t + 180 + i * 250, () => { rp.art.add(a.id); rpLand('art', a.id) }))
  feed.forEach((a, i) => at(t + 180 + i * 190, () => { rp.feed.add(a.id); rpLand('act', a.id) }))
  t += 180 + Math.max(arts.length * 250, feed.length * 190) + 320
  at(t, () => { cancelTween && cancelTween(); cancelTween = tween(pctShown.value, pct.value, 900, v => { pctShown.value = v }) })
  at(t + 1050, rpEnd)
}
const rpHide = {
  stream: i => rp.on && i >= rp.streams,
  del: d => rp.on && !rp.del.has(d.id),
  score: d => rp.on && !rp.score.has(d.id),
  art: a => rp.on && !rp.art.has(a.id),
  feed: a => rp.on && !rp.feed.has(a.id)
}
function barPct(w) {
  if (!w.total) return 0
  const shown = rp.on ? (w.deliverables || []).filter(d => rp.del.has(d.id)).length : w.done
  return Math.round(shown * 100 / w.total)
}
function rpShowDel(d) { return rp.del.has(d.id) }

let reqSeq = 0
async function load() {
  const seq = ++reqSeq
  try {
    const [g, st] = await Promise.all([api.goal(route.params.id), api.stats()])
    if (seq !== reqSeq) return
    data.value = g
    threshold.value = st.qualityThreshold || 85
    loadError.value = ''
    diffMotion(g)
  } catch (e) {
    if (seq !== reqSeq) return
    // 已经有画面就别把用户踢走,保留当前视图等下一次刷新
    if (data.value) return
    loadError.value = e.code === 'ECONNABORTED' || e.code === 'ETIMEDOUT'
      ? '后端长时间没有响应,可能是服务未启动或版本过旧'
      : '项目载入失败'
  }
}

const loadError = ref('')
const retrying = ref(false)
async function retry() {
  retrying.value = true
  await load()
  retrying.value = false
}

async function doAction(action) {
  acting.value = true
  try {
    if (action === 'start') await api.startGoal(goal.value.id)
    if (action === 'pause') await api.pauseGoal(goal.value.id)
    if (action === 'resume') await api.resumeGoal(goal.value.id)
    if (action === 'replan') await api.replanGoal(goal.value.id)
    ElMessage({ message: '已更新', type: 'success', plain: true })
    await load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.response?.data?.message || '操作失败')
  } finally { acting.value = false }
}

async function accept() {
  try {
    await ElMessageBox.confirm('确认全部交付已达标,归档该目标?归档后成果会进入成果库,可随时导出。', '复核归档', {
      confirmButtonText: '确认归档', cancelButtonText: '再看看', type: 'warning'
    })
  } catch { return }
  acting.value = true
  try {
    await api.acceptGoal(goal.value.id)
    ElMessage({ message: '目标已归档', type: 'success', plain: true })
    await load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '归档失败')
  } finally { acting.value = false }
}

async function retryDeliverable(d) {
  acting.value = true
  try {
    await api.retryDeliverable(goal.value.id, d.id)
    ElMessage({ message: '已重置,等待重新执行', type: 'success', plain: true })
    await load()
  } catch (e) { ElMessage.error(e.response?.data?.error || '重试失败') } finally { acting.value = false }
}

// 和「重试」的区别:重试把 attempts 清零(白送满额轮次),这里是上限 +1。
// 差一口气的成果该用这个 —— 否则前几轮指出的问题会从零开始被重新犯一遍。
async function grantRound(d) {
  acting.value = true
  try {
    const r = await api.grantRound(goal.value.id, d.id, 1, '')
    ElMessage({ message: `已追加 1 轮,上限提到 ${r.cap} 轮`, type: 'success', plain: true })
    await load()
  } catch (e) { ElMessage.error(e.response?.data?.error || '追加失败') } finally { acting.value = false }
}

// 砍掉一项:目标结项的前提是"所有交付要么通过、要么砍掉"。
// 没有这个出口,一项反复不过的活能把整个目标永久钉在 REVIEW 上 —— 剩下的干得再好也没用。
// 所以要理由:砍掉是决策,不是删除,事后得能回答"当时为什么不做这件事"。
async function dropDeliverable(d) {
  let reason = ''
  try {
    const r = await ElMessageBox.prompt(
      `砍掉「${d.title}」后不再投入这项,目标按剩下的项结项。写一句理由,事后才查得到当时怎么想的。`,
      '砍掉这项', {
        confirmButtonText: '确认砍掉', cancelButtonText: '再想想',
        inputPlaceholder: '例如:依赖的外部接口不开放,这项对目标不是关键路径',
        inputValidator: (v) => (v && v.trim().length >= 2) || '理由不能为空 —— 一句话就行',
      })
    reason = r.value
  } catch (e) { return }
  acting.value = true
  try {
    await api.cancelDeliverable(goal.value.id, d.id, reason)
    ElMessage({ message: '已砍掉,不再计入完成度', type: 'success', plain: true })
    await load()
  } catch (e) { ElMessage.error(e.response?.data?.error || '操作失败') } finally { acting.value = false }
}

async function retryAllBlocked() {
  acting.value = true
  try {
    const r = await api.retryBlocked(goal.value.id)
    ElMessage({ message: r.reset > 0 ? `已把 ${r.reset} 项重新推回队列` : '没有需要重试的项', type: 'success', plain: true })
    await load()
  } catch (e) { ElMessage.error(e.response?.data?.error || '重试失败') } finally { acting.value = false }
}

async function retryWorkstream(ws) {  acting.value = true
  try {
    await api.retryWorkstream(goal.value.id, ws.id)
    ElMessage({ message: '工作流已重置', type: 'success', plain: true })
    await load()
  } catch (e) { ElMessage.error(e.response?.data?.error || '重试失败') } finally { acting.value = false }
}

async function exportAs(format) {
  try {
    await api.downloadGoal(goal.value.id, goal.value.title, format === 'zip' ? null : format)
    if (format !== 'zip') ElMessage({ message: '正在生成 ' + format.toUpperCase() + ',稍等', type: 'info', plain: true })
  } catch (e) { ElMessage.error('导出失败') }
}

const dirExporting = ref(false)
async function exportToDir(format) {
  dirExporting.value = true
  try {
    const r = await api.exportGoalToDir(goal.value.id, format || 'all')
    if (r.ok) {
      ElMessage({ message: '已写入 ' + r.dir + '(' + r.files + ' 个文件)', type: 'success', plain: true, duration: 4600 })
    } else {
      ElMessage.error(r.error || '写入失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '写入失败')
  } finally { dirExporting.value = false }
}

/* ===== 缺料补报 ===== */
const factsText = ref('')
const factsOpen = ref(false)
const submittingFacts = ref(false)
const needsInputList = computed(() => {
  const raw = goal.value.needsInput
  if (!raw) return []
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr.filter(Boolean) : []
  } catch (e) {
    return String(raw).split('\n').filter(Boolean)
  }
})
async function submitFacts() {
  const text = factsText.value.trim()
  if (!text) return
  submittingFacts.value = true
  try {
    const r = await api.provideInputs(goal.value.id, text)
    factsText.value = ''
    factsOpen.value = true
    ElMessage({
      message: r.reset > 0 ? `已记录,${r.reset} 项受阻交付重新排队` : '已记录,后续拆解与执行以此为准',
      type: 'success', plain: true
    })
    await load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '提交失败')
  } finally { submittingFacts.value = false }
}

function openArtifact(id) {
  artifactId.value = id
  artifactOpen.value = true
}

/* ===== 预算用尽:以前是静默停住,现在直接给追加入口 ===== */
const budgetExhausted = computed(() => {
  const g = goal.value
  if (!g || !g.budgetTokens) return false
  return (g.spentTokens || 0) >= g.budgetTokens
})
const addingBudget = ref(false)
const extraBudget = ref(100000)
async function addBudget(tokens) {
  const n = Number(tokens) || 0
  if (n <= 0) return
  addingBudget.value = true
  try {
    await api.addGoalBudget(goal.value.id, n)
    ElMessage({ message: `已追加 ${fmtTokens(n)} token,项目继续推进`, type: 'success', plain: true })
    await load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '追加失败')
  } finally { addingBudget.value = false }
}

let off = null
let timer = null
onMounted(() => {
  load()
  off = bus.on(evt => {
    if (evt.type === 'ui-replay-toggle') { rpToggle(); return }
    const relevant = evt.type === 'goal-completed'
      || (evt.type === 'goal-update' && (!evt.goalId || String(evt.goalId) === String(route.params.id)))
      || evt.type === 'task-update'
    if (!relevant) return
    clearTimeout(timer)
    timer = setTimeout(() => load(), 800)
  })
})
onBeforeUnmount(() => {
  off && off()
  clearTimeout(timer)
  clearTimeout(clearTimer)
  rpTimers.forEach(clearTimeout)
  rpTimers = []
  if (rp.on) rpSay('', false)
  cancelTween && cancelTween()
})
</script>

<style scoped>
.detail { display: flex; flex-direction: column; gap: 18px; max-width: 1440px; margin: 0 auto; }
.detail-loading { display: grid; place-items: center; gap: 18px; height: 60vh; color: var(--text-faint); letter-spacing: .3em; font-size: 12px; }
/* 载入中给版式不给空白;失败给原因和出口(.sk 基元在 brand.css) */
.detail-skeleton { width: min(720px, 90vw); display: flex; flex-direction: column; gap: 14px; }
.detail-skeleton .sk:nth-child(1) { width: 128px; height: 15px; border-radius: 999px; }
.detail-skeleton .sk:nth-child(2) { width: 58%; height: 24px; }
.detail-skeleton .sk:nth-child(3) { width: 100%; height: 3px; }
.detail-skeleton .sk:nth-child(4) { height: 108px; border-radius: 12px; animation-delay: .2s; }
.detail-skeleton .sk:nth-child(5) { height: 108px; border-radius: 12px; animation-delay: .4s; }
.load-fail { width: min(520px, 90vw); padding: 28px 32px; display: flex; flex-direction: column; align-items: center; gap: 14px;
             animation: riseIn .5s var(--ease-out) both; }
.fail-mark { width: 42px; height: 42px; border-radius: 50%; display: grid; place-items: center; font-size: 18px;
             color: var(--warm); background: rgba(var(--rgb-warm), .09); border: 1px solid rgba(var(--rgb-warm), .24); }
.load-fail p { margin: 0; font-size: 12.5px; line-height: 1.9; color: var(--text-dim); letter-spacing: .06em; }
.fail-row { display: flex; gap: 10px; }

/* ===== 项目头 ===== */
.project-head { position: relative; display: flex; gap: 28px; padding: 22px 26px 20px; overflow: hidden; }
.project-head::before {
  content: ""; position: absolute; inset: 0; pointer-events: none;
  background: radial-gradient(ellipse 50% 120% at 16% 0%, rgba(var(--rgb-glow), .06), transparent 65%);
}
.back {
  position: absolute; top: 18px; right: 22px; background: none; border: none; cursor: pointer;
  color: var(--text-faint); font-family: var(--font-sans); font-size: 11px; letter-spacing: .14em;
  transition: color .3s var(--ease-quiet);
}
.back:hover { color: var(--glow-cyan); }
.back-arrow { margin-right: 5px; font-family: var(--font-mono); }
.head-main { flex: 1; min-width: 0; }
.head-eyebrow { display: flex; align-items: center; gap: 14px; padding-right: 90px; }
.head-status {
  display: inline-flex; align-items: center; gap: 7px; font-size: 10.5px; letter-spacing: .1em;
  border-radius: 999px; padding: 4px 11px; background: var(--chip-bg); color: var(--text-dim);
}
.head-status i { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.hs-active { color: var(--glow-cyan); background: var(--glow-cyan-faint); }
.hs-active i { box-shadow: 0 0 8px rgba(var(--rgb-glow), .8); animation: breatheDot 2.6s infinite; }
.hs-planning { color: var(--st-plan); background: rgba(var(--rgb-plan), .1); }
.hs-review { color: var(--st-warn); background: rgba(var(--rgb-warn), .1); }
.hs-done { color: var(--st-ok); background: rgba(var(--rgb-ok), .1); }
.hs-paused, .hs-failed { color: var(--st-error); background: rgba(var(--rgb-error), .09); }
@keyframes breatheDot { 0%,100% { opacity: .4; } 50% { opacity: 1; } }
.head-id { font-family: var(--font-mono); font-size: 9.5px; letter-spacing: .18em; color: var(--text-faint); }
.head-deadline { font-size: 10.5px; color: var(--text-faint); letter-spacing: .08em; }
.head-title { margin: 14px 0 0; font-size: clamp(24px, 2.6vw, 34px); font-weight: 200; letter-spacing: .04em; line-height: 1.4; }
.head-summary { margin: 10px 0 0; max-width: 760px; font-size: 12.5px; line-height: 1.85; color: var(--text-dim); }
.head-meta { display: flex; flex-wrap: wrap; gap: 22px; margin-top: 16px; font-size: 10.5px; color: var(--text-faint); letter-spacing: .08em; }
.head-meta b { font-family: var(--font-mono); font-weight: 400; color: var(--text-moon); margin-right: 4px; }
.head-meta b.score { color: var(--glow-cyan); }

.head-right { display: flex; flex-direction: column; align-items: flex-end; justify-content: space-between; gap: 16px; padding-top: 6px; }
.ring {
  --p: 0;
  width: 96px; height: 96px; border-radius: 50%; flex: none;
  background: conic-gradient(var(--glow-cyan) calc(var(--p) * 1%), rgba(var(--rgb-ink), .07) 0);
  display: grid; place-items: center; position: relative;
  box-shadow: 0 0 26px rgba(var(--rgb-glow), .12);
  transition: background .8s var(--ease-out);
}
.ring::after {
  content: ""; position: absolute; inset: 5px; border-radius: 50%;
  background: linear-gradient(160deg, rgb(var(--rgb-panel)), rgb(var(--rgb-surface-2)));
}
.ring-inner { position: relative; z-index: 1; text-align: center; }
.ring-num { font-family: var(--font-mono); font-size: 22px; font-weight: 200; color: var(--glow-cyan); }
.ring-num i { font-style: normal; font-size: 11px; margin-left: 1px; opacity: .6; }
.ring-label { display: block; font-size: 9px; letter-spacing: .24em; color: var(--text-faint); margin-top: 2px; }
.head-actions { display: flex; flex-wrap: wrap; gap: 6px; justify-content: flex-end; max-width: 380px; }
.accept-btn { color: var(--st-ok); border-color: rgba(var(--rgb-ok), .35); background: rgba(var(--rgb-ok), .06); }
.export-btn { color: var(--glow-cyan-dim); letter-spacing: .12em; }

/* ===== 栅格 ===== */
.detail-grid { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 18px; align-items: start; padding-bottom: 30px; }
.main-col, .side-col { display: flex; flex-direction: column; gap: 16px; min-width: 0; }

/* ===== 简报 ===== */
.brief { padding: 20px 24px; display: flex; flex-direction: column; gap: 16px; }
.brief-block { display: flex; gap: 18px; }
.brief-label {
  flex: none; width: 74px; font-size: 10px; letter-spacing: .18em; color: var(--glow-cyan-dim);
  padding-top: 4px; position: relative;
}
.brief-label::before {
  content: ""; position: absolute; left: -12px; top: 6px; width: 5px; height: 5px; border-radius: 1px;
  background: var(--glow-cyan); opacity: .6;
}
.brief-block p { margin: 0; font-size: 12.5px; line-height: 1.9; color: var(--text-dim); white-space: pre-wrap; }

/* ===== 工作流 ===== */
.streams { display: flex; flex-direction: column; gap: 12px; }
.streams-head { display: flex; align-items: baseline; gap: 16px; margin-top: 4px; }
.streams-head h2 { margin: 0; font-size: 17px; font-weight: 300; letter-spacing: .22em; }
.streams-sub { font-size: 10.5px; color: var(--text-faint); letter-spacing: .08em; }

.planning { padding: 40px 20px; text-align: center; }
.planning-orbit { display: flex; justify-content: center; gap: 7px; margin-bottom: 16px; }
.planning-orbit span, .loading-orbit span {
  width: 7px; height: 7px; border-radius: 50%; background: var(--glow-cyan); opacity: .3;
  animation: orbitPulse 1.4s ease-in-out infinite;
}
.planning-orbit span:nth-child(2), .loading-orbit span:nth-child(2) { animation-delay: .18s; }
.planning-orbit span:nth-child(3), .loading-orbit span:nth-child(3) { animation-delay: .36s; }
@keyframes orbitPulse { 0%,100% { opacity: .2; transform: scale(.8); } 50% { opacity: 1; transform: scale(1.2); } }
.planning-title { margin: 0; font-size: 14px; letter-spacing: .2em; color: var(--text-moon); }
.planning-sub { margin: 8px 0 0; font-size: 11.5px; color: var(--text-faint); letter-spacing: .05em; }

.stream { display: flex; padding: 18px 20px 16px 0; overflow: hidden; position: relative; }
.stream::before {
  content: ""; position: absolute; inset: 0; pointer-events: none;
  background: radial-gradient(ellipse 60% 90% at 0% 0%, color-mix(in srgb, var(--dc) 5%, transparent), transparent 70%);
}
.stream-rail { width: 46px; flex: none; display: flex; flex-direction: column; align-items: center; padding-top: 6px; position: relative; }
.rail-dot {
  width: 9px; height: 9px; border-radius: 50%; background: color-mix(in srgb, var(--dc) 70%, white);
  box-shadow: 0 0 12px color-mix(in srgb, var(--dc) 55%, transparent); position: relative; z-index: 1;
}
.rail-dot.rs-blocked { background: var(--st-error); box-shadow: 0 0 12px rgba(var(--rgb-error), .6); }
.rail-dot.rs-done { background: var(--st-ok); }
.rail-line {
  position: absolute; top: 20px; bottom: -34px; width: 1px;
  background: linear-gradient(180deg, color-mix(in srgb, var(--dc) 30%, transparent), transparent);
}
.stream-body { flex: 1; min-width: 0; }
.stream-top { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.stream-dept { display: flex; align-items: center; gap: 10px; }
.dept-name { font-size: 12px; letter-spacing: .18em; color: color-mix(in srgb, var(--dc) 80%, white); }
.stream-status { font-size: 9.5px; letter-spacing: .1em; padding: 2px 8px; border-radius: 999px; background: var(--chip-bg); color: var(--text-faint); }
.ss-active { color: var(--glow-cyan-dim); background: var(--glow-cyan-faint); }
.ss-blocked { color: var(--st-error); background: rgba(var(--rgb-error), .1); }
.ss-done { color: var(--st-ok); background: rgba(var(--rgb-ok), .1); }
.stream-lead { font-size: 10.5px; color: var(--text-faint); letter-spacing: .06em; }
.lead-label { margin-right: 6px; opacity: .65; }
.lead-name { color: var(--text-dim); }
.stream-title { margin: 10px 0 0; font-size: 16.5px; font-weight: 300; letter-spacing: .04em; line-height: 1.5; }
.stream-brief { margin: 8px 0 0; font-size: 12px; line-height: 1.8; color: var(--text-dim); white-space: pre-wrap; }
.stream-progress { display: flex; align-items: center; gap: 12px; margin-top: 13px; }
.stream-progress .bar { flex: 1; height: 2px; border-radius: 2px; background: rgba(var(--rgb-ink), .07); overflow: hidden; }
.stream-progress .bar i {
  display: block; height: 100%; border-radius: 2px;
  background: linear-gradient(90deg, color-mix(in srgb, var(--dc) 60%, transparent), var(--dc));
  box-shadow: 0 0 8px color-mix(in srgb, var(--dc) 45%, transparent);
  transition: width .8s var(--ease-out);
}
.stream-progress span { font-family: var(--font-mono); font-size: 10.5px; color: var(--text-faint); }

/* 施工进行中:光带扫过卡片,进度条走流纹,里程碑点呼吸 */
.stream.is-live::after {
  content: ""; position: absolute; left: 0; right: 0; top: 0; height: 32%; pointer-events: none;
  background: linear-gradient(180deg, color-mix(in srgb, var(--dc) 10%, transparent), transparent);
  animation: liveSweep 3.6s var(--ease-quiet) infinite;
}
@keyframes liveSweep {
  0% { transform: translateY(-110%); opacity: 0; }
  20% { opacity: .9; }
  100% { transform: translateY(360%); opacity: 0; }
}
.stream.is-live .rail-dot.rs-active { animation: railPulse 2.3s var(--ease-quiet) infinite; }
@keyframes railPulse {
  0%, 100% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--dc) 42%, transparent); }
  55% { box-shadow: 0 0 0 8px transparent, 0 0 18px color-mix(in srgb, var(--dc) 75%, transparent); }
}
.stream.is-live .stream-progress .bar i {
  background-image: repeating-linear-gradient(115deg, var(--hatch) 0 4px, transparent 4px 12px);
  background-size: 24px 100%;
  animation: flowStripes 1.15s linear infinite;
}
@keyframes flowStripes { to { background-position: 24px 0; } }

.deliverables { display: flex; flex-direction: column; gap: 7px; margin-top: 14px; }
.delivery {
  display: flex; align-items: flex-start; gap: 12px; padding: 10px 12px;
  border: 1px solid var(--border-hair); border-radius: 9px;
  background: var(--tint-soft);
  border-left: 2px solid transparent;
  transition: border-color .3s var(--ease-quiet), background .3s var(--ease-quiet);
}
.delivery:hover { background: var(--tint-soft); }
.delivery.ds-done { border-left-color: rgba(var(--rgb-ok), .5); }
.delivery.ds-running, .delivery.ds-review { border-left-color: rgba(var(--rgb-glow), .6); }
.delivery.ds-blocked { border-left-color: rgba(var(--rgb-error), .7); background: rgba(var(--rgb-error), .03); }
.d-status {
  flex: none; margin-top: 2px; font-size: 9px; letter-spacing: .08em;
  padding: 2px 7px; border-radius: 999px; background: var(--chip-bg); color: var(--text-faint);
}
.dsc-running, .dsc-review { color: var(--glow-cyan); background: var(--glow-cyan-faint); }
.dsc-done { color: var(--st-ok); background: rgba(var(--rgb-ok), .1); }
.dsc-blocked { color: var(--st-error); background: rgba(var(--rgb-error), .1); }
.dsc-waiting { color: var(--st-plan); background: rgba(var(--rgb-plan), .12); }
.delivery.ds-waiting { border-left-color: rgba(var(--rgb-plan), .5); }
.dsc-pending, .dsc-ready { color: var(--text-dim); }
.d-main { flex: 1; min-width: 0; }
.d-title-line { display: flex; align-items: baseline; gap: 10px; }
.d-title { font-size: 12.5px; color: var(--text-moon); line-height: 1.5; }
.d-score { font-family: var(--font-mono); font-size: 10px; color: var(--glow-cyan-dim); }
.d-meta { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 4px; font-size: 10px; color: var(--text-faint); letter-spacing: .04em; }
.d-notes {
  margin: 7px 0 0; font-size: 11px; line-height: 1.7; color: rgba(var(--rgb-error), .8);
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; white-space: pre-wrap;
}
.d-actions { flex: none; display: flex; gap: 2px; }
.d-vers { color: var(--glow-cyan-dim); cursor: pointer; border-bottom: 1px dashed rgba(var(--rgb-glow), .35); }
.d-vers:hover { color: var(--glow-cyan); }
.d-art { color: var(--glow-cyan-dim); font-size: 11px; }
.d-retry { color: var(--st-warn); font-size: 11px; }
/* 砍掉不是红色告警,是个平静的决定:压暗但留在原地,理由还能点开看 */
.d-drop { color: var(--st-idle); font-size: 11px; }
.d-drop:hover { color: var(--st-error); }
.delivery.ds-cancelled { opacity: .62; }
.delivery.ds-cancelled .d-title { text-decoration: line-through; text-decoration-thickness: 1px; }
.delivery.ds-cancelled .d-notes { opacity: .9; }
.stream-foot { margin-top: 12px; padding-top: 11px; border-top: 1px dashed var(--border-hair); }
.af-label { font-size: 9.5px; letter-spacing: .16em; color: var(--text-faint); }
.stream-foot p { margin: 6px 0 0; font-size: 11.5px; line-height: 1.8; color: var(--text-dim); white-space: pre-wrap; }
.ws-retry { color: var(--st-warn); font-size: 11px; padding-left: 0; }

/* ===== 侧栏 ===== */
.side-block { padding: 16px 18px 14px; }
.side-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 12px; }
.side-head h2 { margin: 0; font-size: 13px; font-weight: 300; letter-spacing: .24em; }
.side-head span { font-family: var(--font-mono); font-size: 10.5px; color: var(--glow-cyan-dim); }
.side-empty { font-size: 11.5px; color: var(--text-faint); line-height: 1.8; padding: 8px 0 10px; letter-spacing: .04em; }
.art-item {
  padding: 10px 11px; border: 1px solid var(--border-hair); border-radius: 9px; cursor: pointer;
  margin-bottom: 8px; transition: all .3s var(--ease-quiet);
}
.art-item:hover { border-color: rgba(var(--rgb-glow), .28); background: rgba(var(--rgb-glow), .025); }
.art-line { display: flex; align-items: center; justify-content: space-between; }
.art-type { font-size: 9px; letter-spacing: .14em; color: var(--text-faint); }
.art-score { font-family: var(--font-mono); font-size: 11px; color: var(--text-faint); }
.art-score.good { color: var(--glow-cyan); }
.art-name { margin-top: 5px; font-size: 12px; color: var(--text-moon); line-height: 1.5; }
.art-meta { display: flex; gap: 10px; margin-top: 5px; font-size: 9.5px; color: var(--text-faint); font-family: var(--font-mono); }
.ast-approved { color: var(--st-ok); }
.ast-review { color: var(--st-warn); }
.ast-rejected { color: var(--st-error); }
.side-more { margin-top: 10px; font-size: 10.5px; letter-spacing: .14em; color: var(--text-dim); cursor: pointer; text-align: right; }
.side-more:hover { color: var(--glow-cyan); }

.feed { display: flex; flex-direction: column; max-height: 520px; overflow: auto; padding-right: 2px; }
.feed-item { display: flex; gap: 10px; padding: 9px 0; border-bottom: 1px dashed rgba(var(--rgb-ink), .05); }
.feed-item:last-child { border-bottom: none; }
.feed-dot { width: 5px; height: 5px; border-radius: 50%; background: rgba(var(--rgb-glow), .5); margin-top: 6px; flex: none; }
.ft-escalation .feed-dot { background: var(--st-error); box-shadow: 0 0 7px rgba(var(--rgb-error), .6); }
.feed-body { flex: 1; min-width: 0; }
.feed-top { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.feed-type { font-size: 9px; letter-spacing: .16em; color: var(--text-faint); }
.ft-escalation .feed-type { color: var(--st-error); }
.feed-time { font-family: var(--font-mono); font-size: 9px; color: var(--text-faint); }
.feed-text { margin: 4px 0 0; font-size: 11px; line-height: 1.7; color: var(--text-dim); white-space: pre-wrap; }

/* ===== 缺料补报:整页最该被看见的东西,用暖色而不是报错的红 ===== */
.needs {
  padding: 18px 24px 20px; border-radius: 14px;
  border: 1px solid rgba(var(--rgb-warn), .32);
  background: linear-gradient(165deg, rgba(var(--rgb-warn), .055), rgba(var(--rgb-surface-2), .92));
  animation: riseIn .5s var(--ease-out) both;
}
/* 预算用尽:比缺料更"硬"的信号,用错误色,但同样给出口 */
.budget {
  padding: 18px 24px 20px; border-radius: 14px;
  border: 1px solid rgba(var(--rgb-error), .34);
  background: linear-gradient(165deg, rgba(var(--rgb-error), .055), rgba(var(--rgb-surface-2), .92));
  animation: riseIn .5s var(--ease-out) both;
}
.budget-head { display: flex; flex-direction: column; gap: 6px; }
.budget-tag {
  align-self: flex-start; font-size: 10px; letter-spacing: .22em; color: var(--st-error);
  border: 1px solid rgba(var(--rgb-error), .3); border-radius: 999px; padding: 3px 10px;
}
.budget-head h3 { margin: 4px 0 0; font-size: 15.5px; font-weight: 400; color: var(--text-main); }
.budget-head p { margin: 0; font-size: 12.5px; line-height: 1.75; color: var(--text-dim); }
.budget-actions { display: flex; align-items: center; gap: 8px; margin-top: 14px; flex-wrap: wrap; }
.budget-actions :deep(.el-input-number) { width: 128px; }
.needs-head { display: flex; flex-direction: column; gap: 6px; }
.needs-mark {
  align-self: flex-start; font-family: var(--font-mono); font-size: 9.5px; letter-spacing: .22em;
  color: var(--st-warn); border: 1px solid rgba(var(--rgb-warn), .38); border-radius: 999px; padding: 2px 10px;
}
.needs-head h3 { margin: 2px 0 0; font-size: 15px; font-weight: 300; letter-spacing: .1em; color: var(--text-moon); }
.needs-head p { margin: 0; font-size: 11.5px; line-height: 1.8; color: var(--text-dim); }
/* 12 张暖色便签叠在一张暖色卡里,叠出来是一块发棕的糊 —— 信息没变清楚,
   只是变重了。这里退回清单:一条一条,靠编号对齐和发丝分隔线站住,
   整块仍然是暖的,但不再抢那张卡片本身的权重。 */
.needs-list {
  list-style: none; margin: 12px 0 0; padding: 0;
  border-top: 1px solid rgba(var(--rgb-warn), .16);
}
.needs-list li {
  display: flex; gap: 14px; font-size: 12.5px; line-height: 1.8; color: var(--text-moon);
  padding: 10px 2px; border-bottom: 1px solid rgba(var(--rgb-warn), .1);
}
.needs-list li:last-child { border-bottom: none; }
.needs-no {
  font-family: var(--font-mono); font-size: 10.5px; color: var(--st-warn);
  flex: none; width: 18px; padding-top: 3px; text-align: right; opacity: .85;
}
.needs-form { margin-top: 14px; display: flex; flex-direction: column; gap: 10px; }
.needs-actions { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.needs-hint { font-size: 11px; color: var(--text-faint); }

.facts { padding: 12px 18px; border-radius: 12px; border: 1px solid var(--border-hair); background: var(--tint-soft); }
.facts-head { display: flex; align-items: center; gap: 12px; font-size: 11px; color: var(--text-dim); }
.facts-tag {
  font-family: var(--font-mono); font-size: 9.5px; letter-spacing: .18em; color: var(--st-ok);
  border: 1px solid rgba(var(--rgb-ok), .3); border-radius: 999px; padding: 2px 9px;
}
.facts-toggle { margin-left: auto; background: none; border: 0; cursor: pointer; color: var(--glow-cyan-dim);
               font-size: 11px; letter-spacing: .1em; }
.facts-body { margin: 10px 0 0; font-family: var(--font-sans); font-size: 12px; line-height: 1.9;
              color: var(--text-dim); white-space: pre-wrap; word-break: break-word; }

/* ===== 四步生产线:当前卡住的那一步顶边流光,走完了才落定 ===== */
.pipeline {  display: grid; grid-template-columns: repeat(4, minmax(0, 1fr));
  position: relative; overflow: hidden; margin-top: 2px;
}
.pstep { position: relative; padding: 14px 18px 15px; border-right: 1px solid var(--border-hair) }
.pstep:last-child { border-right: 0 }
.pstep::before {
  content: ""; position: absolute; left: 0; right: 0; top: 0; height: 2px;
  background: var(--border-hair); transition: background var(--dur-mid) var(--ease-quiet);
}
.pnum { font-family: var(--font-mono); font-size: 9px; letter-spacing: .12em; color: var(--text-faint) }
.pname { display: block; font-size: 12.5px; font-weight: 400; margin: 8px 0 4px; letter-spacing: .06em; color: var(--text-dim) }
.pnote { display: block; font-family: var(--font-mono); font-size: 8.5px; letter-spacing: .02em; color: var(--text-faint) }
.ps-done::before { background: rgba(var(--rgb-glow), .8) }
.ps-done .pnum { color: var(--glow-cyan) }
.ps-done .pname { color: var(--text-moon) }
/* 未开始的步骤原来整体压到 42%,深底上直接消失 —— 页面看起来像
   「只有一步」。压到 60%:它仍然退后,但你能看见后面还有三步要走。 */
.ps-wait { opacity: .6; transition: opacity var(--dur-mid) var(--ease-quiet) }
.ps-run::before {
  background: linear-gradient(90deg, var(--glow-cyan), var(--warm), var(--glow-cyan));
  background-size: 220% 100%; animation: flowDash 1.9s linear infinite; opacity: 1;
}
.ps-run .pnum { border-radius: 50%; animation: breath var(--dur-breath) var(--ease-quiet) infinite }
.ps-run .pname { color: var(--text-moon) }
.is-rp::after {
  content: ""; position: absolute; left: 0; top: 0; height: 2px; width: 120px; pointer-events: none;
  background: linear-gradient(90deg, transparent, var(--glow-cyan), transparent);
  animation: rpTravel 1.6s cubic-bezier(.4, 0, .6, 1) infinite;
}
@keyframes rpTravel { from { transform: translateX(-120px) } to { transform: translateX(1400px) } }

@media (max-width: 1080px) {
  .detail-grid { grid-template-columns: 1fr; }
  .pipeline { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .pstep { border-bottom: 1px solid var(--border-hair) }  .project-head { flex-direction: column; }
  .head-right { flex-direction: row; align-items: center; justify-content: space-between; width: 100%; }
  .head-actions { justify-content: flex-start; }
}
@media (max-width: 640px) {
  .brief-block { flex-direction: column; gap: 6px; }
  .brief-label { width: auto; }
}
/* 重放施工过程时,还没轮到的行不占位(放在最后,压过 .stream/.delivery 自己的 display) */
.rp-hidden { display: none }
</style>
