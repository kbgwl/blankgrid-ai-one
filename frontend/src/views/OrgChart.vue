<template>
  <div class="org-chart" ref="stage" @mousemove="onTilt" @mouseleave="resetTilt">
    <!-- 全息底:透视网格地平线 / 星尘 / 慢扫光 / 暗角,只铺图纸区,不压 HUD 带 -->
    <div class="h-bg" aria-hidden="true">
      <div class="h-floor"></div>
      <div class="h-stars"></div>
      <div class="h-scan"></div>
      <div class="h-vig"></div>
    </div>

    <div class="canvas-wrap" ref="wrap" :style="{ paddingBottom: railSpace + 'px' }">
      <svg :width="size.w * viewScale" :height="size.h * viewScale"
           :viewBox="`0 0 ${size.w} ${size.h}`" class="chart-svg" :style="tiltStyle">
        <defs>
          <filter id="softGlow" x="-60%" y="-60%" width="220%" height="220%">
            <feGaussianBlur stdDeviation="2.6" result="b" />
            <feMerge>
              <feMergeNode in="b" /><feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
          <radialGradient id="coreHalo">
            <stop offset="0%" :stop-color="haloColor" stop-opacity=".62" />
            <stop offset="100%" :stop-color="haloColor" stop-opacity="0" />
          </radialGradient>
        </defs>

        <!-- CEO 核心:搏动光环 + 两圈反向旋转刻度环(纯装饰,压在卡片下层) -->
        <g class="ceo-core" :transform="`translate(${nodes.ceo.x}, ${nodes.ceo.y})`" aria-hidden="true">
          <circle class="core-halo" cx="0" cy="0" r="88" fill="url(#coreHalo)" />
          <g class="spin-rev">
            <circle r="145" fill="none" :stroke="haloColor" stroke-opacity=".3" stroke-width="1.2" stroke-dasharray="34 12 6 12" />
          </g>
          <g class="spin-slow" filter="url(#softGlow)">
            <circle r="118" fill="none" :stroke="haloColor" stroke-opacity=".48" stroke-width="1.3" stroke-dasharray="3 9" />
            <line v-for="t in ticks" :key="'tk' + t.k" class="tick"
                  :x1="t.x1" :y1="t.y1" :x2="t.x2" :y2="t.y2" :stroke-width="t.major ? 1.7 : 1"
                  :stroke="haloColor" :stroke-opacity="t.major ? .85 : .4" />
          </g>
        </g>

        <!-- 链路常驻:宽描边当辉光底,细描边当骨架;两者都跟着部门色 -->
        <g class="links" fill="none">
          <path v-for="l in links" :key="'b-' + l.id" :d="l.d" :stroke="l.color"
                stroke-width="5" :stroke-opacity="l.active ? .1 : .055" stroke-linecap="round" />
          <path v-for="l in links" :key="l.id" :d="l.d" :id="'p-' + l.id"
                :stroke="l.color" :stroke-opacity="l.active ? .5 : .3" stroke-width="1.1" />
        </g>
        <!-- 数据流转:虚线常驻缓行(在线),派活时提速提亮;光点沿轨道滑行 -->
        <g class="flows" fill="none">
          <path v-for="l in links" :key="'d-' + l.id" :d="l.d" class="flow-line"
                :class="{ 'is-fast': l.active }" :stroke="l.color"
                :stroke-opacity="l.active ? .78 : .3" :stroke-width="l.active ? 1.7 : 1.2" />
          <g v-if="!calm">
            <circle v-for="l in links" :key="'p-' + l.id" :r="l.active ? 3 : 2.2" :fill="l.color"
                    :class="l.active ? 'dot-active' : 'dot-idle-flow'" filter="url(#softGlow)">
              <animateMotion :dur="(l.active ? l.dur * 1.6 : l.dur * 2.6) + 's'" repeatCount="indefinite" rotate="auto">
                <mpath :href="'#p-' + l.id" />
              </animateMotion>
              <animate attributeName="opacity" :values="l.active ? '.55;1;.55' : '.2;.62;.2'"
                       :dur="(l.active ? l.dur : l.dur * 1.8) + 's'" repeatCount="indefinite" />
            </circle>
          </g>
        </g>

        <!-- 流转标注芯片:CEO → 各部门之间 -->
        <g v-for="c in edgeChips" :key="'chip-' + c.deptId">
          <foreignObject :x="c.x - CHIP_W/2" :y="c.y - CHIP_H/2" :width="CHIP_W" :height="CHIP_H">
            <div class="edge-chip glass" :style="{ '--dept-color': c.color }">
              <span class="chip-down">↓ {{ c.pending }} 待处理</span>
              <span class="chip-up">↑ {{ c.doneToday }} 今日完成</span>
            </div>
          </foreignObject>
        </g>

        <g class="nodes">
          <!-- CEO Agent:AI 决策者,公司顶点 -->
          <foreignObject :x="nodes.ceo.x - CEO_W/2" :y="nodes.ceo.y - CEO_H/2"
                         :width="CEO_W" :height="CEO_H">
            <div class="node-card ceo" :class="{ breathing: ceoStatus === 'RUNNING' }" @click.stop="goOps">
              <div class="ceo-badge" v-if="ceoStatus === 'RUNNING'">巡视中</div>
              <div class="ceo-seal">
                <svg viewBox="0 0 32 32" width="20" height="20">
                  <rect x="4" y="4" width="24" height="24" rx="7" fill="none"
                        stroke="var(--glow-cyan)" stroke-width="1.6" opacity=".9" />
                  <circle cx="16" cy="16" r="2.6" fill="var(--glow-cyan)">
                    <animate attributeName="opacity" values=".45;1;.45" dur="3.2s" repeatCount="indefinite" />
                  </circle>
                </svg>
              </div>
              <div class="node-name">CEO Agent</div>
              <div class="node-title">统观全局 · 已巡视 {{ cycleCount }} 轮</div>
            </div>
          </foreignObject>
          <!-- 部门与岗位 -->
          <template v-for="d in org.departments" :key="d.id">
            <foreignObject :x="nodes.depts[d.id].x - DEPT_W/2" :y="nodes.depts[d.id].y - DEPT_H/2"
                           :width="DEPT_W" :height="DEPT_H">
              <div class="node-card dept" :style="{ '--dept-color': deptColor(d.colorKey) }">
                <div class="node-name">{{ d.name }}</div>
                <div class="node-title">
                  {{ d.lead ? '负责人 · ' + d.lead.name : '暂无负责人' }} · {{ d.employees.length }} 格
                </div>
              </div>
            </foreignObject>
            <foreignObject v-for="(e, j) in d.employees" :key="e.id" class="fo-node"
                           :style="{ '--i': j }"
                           :x="nodes.emps[e.id].x - EMP_W/2" :y="nodes.emps[e.id].y - EMP_H/2"
                           :width="EMP_W" :height="EMP_H">
              <div class="node-card emp" :class="[statusClass(e), { rippling: rippleIds.has(e.id), 'is-spot': spotIds.has(e.id), 'is-lead': isLead(e) }]"
                   :style="{ '--dept-color': deptColor(d.colorKey) }" @click="open(e)">
                <GeoAvatar :seed="e.avatarSeed || 0" :color-key="d.colorKey" :size="40" />
                <div class="emp-body">
                  <div class="emp-head">
                    <span class="node-name">{{ e.name }}</span>
                    <span v-if="isLead(e)" class="lead-badge">负责人</span>
                    <span class="status-dot" :class="dotClass(e)"></span>
                  </div>
                  <div class="node-title">{{ e.currentTaskSummary || e.title }}</div>
                  <div class="emp-stats" v-if="stats[e.id]">
                    <span v-if="stats[e.id].metricLabel" class="st-metric">
                      <span class="st-metric-num">{{ stats[e.id].metricValue }}</span> {{ stats[e.id].metricLabel }}
                    </span>
                    <i v-if="stats[e.id].metricLabel" class="st-sep"></i>
                    <span class="st-num">{{ stats[e.id].doneToday }}</span> 今日
                    <i class="st-sep"></i>
                    <span class="st-num">{{ stats[e.id].doneTotal }}</span> 累计
                    <template v-if="stats[e.id].lastDoneAt">
                      <i class="st-sep"></i> 上次 {{ hhmm(stats[e.id].lastDoneAt) }}
                    </template>
                    <template v-if="stats[e.id].pending > 0">
                      <i class="st-sep"></i><span class="st-pending">待 {{ stats[e.id].pending }}</span>
                    </template>
                  </div>
                </div>
                <div v-if="e.status === 'RUNNING'" class="emp-prog"><i></i></div>
                <div class="emp-edge"></div>
              </div>
            </foreignObject>
            <!-- 空白待点亮格 -->
            <foreignObject v-if="d.employees.length === 0"
                           :x="nodes.depts[d.id].x - EMP_W/2" :y="nodes.depts[d.id].y + DEPT_H/2 + DEPT_EMP_GAP + vPad"
                           :width="EMP_W" :height="EMP_H">
              <div class="node-card blank-cell" @click="openAdd(d)">
                <span class="blank-plus">+</span>
                <span class="blank-hint">点亮一格</span>
              </div>
            </foreignObject>
          </template>
        </g>
      </svg>
    </div>

    <!-- 载入失败:说清楚发生了什么,并留下重试的出口 -->
    <div v-if="loadError" class="org-fail glass">
      <div class="fail-mark">!</div>
      <p class="fail-text">{{ loadError }}</p>
      <el-button size="small" plain :loading="loading" @click="load">重试</el-button>
      <span class="fail-hint">后端恢复后,部门与岗位会自动带出;实时状态不受影响</span>
    </div>

    <!-- 首次取数:给一张说得清的图纸底,而不是几秒空白 -->
    <div v-else-if="loading && !org.departments.length" class="org-loading glass">
      <span class="ld-ring"></span>
      <span class="ld-text">组织架构载入中</span>
    </div>

    <!-- 左上 HUD 读数:公司规模 + 待拍板 + 实时时钟 -->
    <div class="chart-status glass">
      <span class="duty-dot" :class="{ 'is-stale': stale }"></span>
      <span v-for="r in readouts" :key="r.label" class="hud-read" :title="r.hint">
        <b>{{ r.value }}</b><i>{{ r.label }}</i>
      </span>
      <span v-if="escalations > 0" class="duty-badge" @click="goBoard" title="CEO 上报了等你拍板的事项">
        <span class="badge-num">{{ escalations }}</span> 件待拍板
      </span>
      <span class="clock">{{ clock }}</span>
    </div>

    <!-- 右上操作 -->
    <div class="chart-actions glass">
      <span v-if="stale" class="stale-note" title="最近一次刷新没成功,看到的是上一次的组织架构">陈旧</span>
      <el-button text class="quiet-btn" @click="deptOpen = true">＋ 部门</el-button>
      <el-divider direction="vertical" />
      <el-button text class="quiet-btn" @click="openAdd()">添一格</el-button>
      <el-divider direction="vertical" />
      <el-button text class="quiet-btn" :loading="loading" @click="load">刷新</el-button>
    </div>

    <!-- 底部 HUD 带:实时事件流 · 状态图例与落款 · 兵力仪表 -->
    <footer class="hud-rail" ref="hudRail">
      <section class="hud-feed glass">
        <div class="hf-head">
          <span class="hf-title">实时事件流</span>
          <span class="hf-live" :class="{ quiet: !feedLines.length }"></span>
          <span class="hf-hint">{{ feedLines.length ? 'WebSocket 一到就补进队首' : '等待第一条施工事件' }}</span>
        </div>
        <transition-group tag="div" name="mv" class="hf-list">
          <div v-for="l in feedLines" :key="l.key" class="hf-line" :class="'tone-' + l.tone">
            <span class="hf-tag">{{ l.tag }}</span>
            <span class="hf-text">{{ l.text }}</span>
            <span class="hf-time">{{ l.time }}</span>
          </div>
        </transition-group>
        <p v-if="!feedLines.length" class="hf-none">公司在等一个目标:派活或 CEO 巡视后,这里会逐条打字机补上。</p>
      </section>

      <section class="hud-mid">
        <div class="hud-legend">
          <span class="lg"><i class="lg-dot lg-idle"></i>待机</span>
          <span class="lg"><i class="lg-dot lg-run"></i>执行中</span>
          <span class="lg"><i class="lg-dot lg-err"></i>异常</span>
          <span class="lg"><i class="lg-dot lg-off"></i>停用</span>
        </div>
        <div class="chart-slogan">
          <span class="slogan-text">一人,一格,一军</span>
        </div>
      </section>

      <section class="hud-gauges glass">
        <div v-for="g in gauges" :key="g.label" class="gauge" :title="g.hint">
          <svg viewBox="0 0 60 60" class="g-arc" aria-hidden="true">
            <circle class="g-track" cx="30" cy="30" r="21" />
            <circle class="g-val" cx="30" cy="30" r="21"
                    :stroke="g.color" :stroke-dasharray="g.circ" :stroke-dashoffset="g.off" />
          </svg>
          <div class="g-read"><b :style="{ color: g.color }">{{ g.num }}</b><i>{{ g.label }}</i></div>
        </div>
      </section>
    </footer>

    <EmployeePanel v-model="panelOpen" :employee="selected" @refresh="load" />
    <AddEmployeeDialog v-model="addOpen" :departments="org.departments" :presetDept="addPreset" @created="load" />
    <DepartmentDialog v-model="deptOpen" @created="load" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { api, bus } from '../api'
import { motionOk } from '../utils/motion'
import GeoAvatar from '../components/GeoAvatar.vue'
import EmployeePanel from '../components/EmployeePanel.vue'
import AddEmployeeDialog from '../components/AddEmployeeDialog.vue'
import DepartmentDialog from '../components/DepartmentDialog.vue'

const EMP_W = 218, EMP_H = 80, CEO_W = 250, CEO_H = 92, DEPT_W = 172, DEPT_H = 60
const calm = !motionOk()
/* CEO_CY 给刻度环留出上方空间(环顶要落在 viewBox 内);LINK_TOP 必须落在 CEO 卡片下缘之外 */
const DEPT_GAP = 64, CHIP_W = 176, CHIP_H = 30, CEO_CY = 150, LINK_TOP = 202

const wrap = ref(null)
const org = ref({ ceo: null, departments: [], stats: {} })
const panelOpen = ref(false)
const addOpen = ref(false)
const addPreset = ref(null)
const deptOpen = ref(false)
const selected = ref(null)
const rippleIds = reactive(new Set())
const spotIds = reactive(new Set())
const viewScale = ref(1)
const vPad = ref(0)
const railSpace = ref(104)
const hudRail = ref(null)
const clock = ref('')
let clockTimer = null

const stats = computed(() => org.value.stats || {})
const escalations = ref(0)
const ceoStatus = ref('IDLE')
const cycleCount = ref(0)
const loadError = ref('')
const loading = ref(false)
const stale = ref(false)

function goOps() { window.location.hash = '#/' }
function goBoard() { window.location.hash = '#/board' }
function isLead(e) { return e?.level === 'LEAD' }

const totalEmp = computed(() =>
  (org.value.departments || []).reduce((n, d) => n + d.employees.length, 0))
const onDuty = computed(() =>
  (org.value.departments || []).reduce((n, d) => n + d.employees.filter(e => e.status !== 'DISABLED').length, 0))

function tick() {
  clock.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

const allEmps = computed(() =>
  (org.value.departments || []).reduce((list, d) => list.concat(d.employees), []))
const runningCount = computed(() => allEmps.value.filter(e => e.status === 'RUNNING').length)
const errorCount = computed(() => allEmps.value.filter(e => e.status === 'ERROR').length)

/* HUD 左上读数:全部取自真实组织架构,没有就是 0 */
const readouts = computed(() => [
  { label: '部门', value: (org.value.departments || []).length, hint: '公司现有部门数' },
  { label: '在岗', value: `${onDuty.value}/${totalEmp.value}`, hint: '启用岗位 / 全部岗位' },
  { label: '执行中', value: runningCount.value, hint: '正在跑任务的岗位' },
  { label: '巡视', value: `${cycleCount.value} 轮`, hint: 'CEO 自动巡视累计轮次' }
])

/* 弧形仪表:周长即刻度,比例全部由在岗/执行/今日交付真实算出 */
const ARC_R = 21, ARC_C = 2 * Math.PI * ARC_R
function gauge(label, num, ratio, color, hint) {
  return {
    label, num, color, hint,
    circ: ARC_C,
    off: ARC_C * (1 - Math.max(0, Math.min(1, ratio)))
  }
}
const gauges = computed(() => {
  const t = totalEmp.value
  const s = stats.value
  let done = 0, pending = 0
  allEmps.value.forEach(e => {
    const st = s[e.id]
    if (st) { done += st.doneToday || 0; pending += st.pending || 0 }
  })
  return [
    gauge('在岗', onDuty.value, t ? onDuty.value / t : 0, 'var(--glow-cyan)', `${onDuty.value}/${t} 个岗位启用`),
    gauge('执行', runningCount.value, t ? runningCount.value / t : 0, 'var(--st-running)', '正在执行任务的岗位占比'),
    gauge('交付', done, done + pending ? done / (done + pending) : 0, 'var(--st-ok)', '今日已完成 / (今日完成 + 待处理)'),
    gauge('异常', errorCount.value, t ? errorCount.value / t : 0, 'var(--st-error)', '上次任务失败的岗位')
  ]
})

/* 事件流:ops/feed 播种 + WebSocket 增量,队首用 clip 打字机揭开 */
const typeMap = {
  GOAL: '目标', FEEDBACK: '纠偏', REPORT: 'CEO', ESCALATION: '上报',
  RUNNING: '执行', DONE: '交活', ERROR: '异常'
}
const toneMap = { GOAL: 'glow', FEEDBACK: 'warn', REPORT: 'plan', ESCALATION: 'error', DONE: 'ok', RUNNING: 'glow', ERROR: 'error' }
const feedLines = ref([])
let feedSeq = 0
function pushFeed(type, text) {
  const body = String(text || '').slice(0, 46)
  const head = feedLines.value[0]
  if (!body || (head && head.text === body)) return
  feedLines.value.unshift({
    key: ++feedSeq,
    tag: typeMap[type] || type || '事件',
    tone: toneMap[type] || 'dim',
    text: body,
    time: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
  })
  if (feedLines.value.length > 5) feedLines.value.length = 5
}

/* 视差倾斜:整块图纸跟着指针微微侧转,让全息有纵深(关掉动效偏好时不接管) */
const stage = ref(null)
const tilt = reactive({ x: 0, y: 0 })
function onTilt(e) {
  if (!motionOk()) return
  const el = stage.value
  if (!el) return
  const r = el.getBoundingClientRect()
  tilt.x = (e.clientX - r.left) / r.width - .5
  tilt.y = (e.clientY - r.top) / r.height - .5
}
const tiltStyle = computed(() => ({
  transform: `perspective(1400px) rotateX(${(-tilt.y * 3.4).toFixed(2)}deg) rotateY(${(tilt.x * 4.2).toFixed(2)}deg)`,
  transition: 'transform .6s var(--ease-out)'
}))
function resetTilt() { tilt.x = 0; tilt.y = 0 }

const haloColor = 'var(--glow-cyan)'
const ticks = (() => {
  const out = []
  for (let k = 0; k < 24; k++) {
    const a = (k * 15 - 90) * Math.PI / 180
    const major = k % 6 === 0
    const r1 = 124, r2 = major ? 140 : 132
    out.push({
      k, major,
      x1: +(Math.cos(a) * r1).toFixed(2), y1: +(Math.sin(a) * r1).toFixed(2),
      x2: +(Math.cos(a) * r2).toFixed(2), y2: +(Math.sin(a) * r2).toFixed(2)
    })
  }
  return out
})()

/* 自适应缩放:宽和高都参与取小,一屏看全公司;触到可读下限才允许横向平移
   悬浮 HUD 占掉的那条带要从可用高度里先减掉,否则最后一行会被面板盖住;
   宽度绑定(大屏常态)时把多余高度摊进行距 vPad,图纸才撑得满,不会底下空一片 */
const MIN_SCALE = 0.66
const MAX_PAD = 150
const WRAP_PAD = 14
function fit() {
  const el = wrap.value
  if (!el) return
  const railH = hudRail.value ? hudRail.value.offsetHeight : 0
  const band = Math.max(64, railH + 14)
  railSpace.value = band
  const base = layout(0)
  const availW = el.clientWidth - WRAP_PAD * 2
  const availH = Math.max(160, el.clientHeight - WRAP_PAD - band)
  const scale = Math.max(MIN_SCALE, Math.min(1, availW / base.w, availH / base.h))
  viewScale.value = scale
  /* h(pad) = h(0) + pad * (maxEmp + 1):部门行下沉、岗位首行、逐行间距各吃一份 */
  const slack = (availH / scale - base.h) / (base.maxEmp + 1)
  vPad.value = Math.max(0, Math.min(MAX_PAD, Math.round(slack)))
  nextTick(() => {
    if (el.scrollWidth > el.clientWidth) el.scrollLeft = (el.scrollWidth - el.clientWidth) / 2
  })
}

/* 图纸几何:每一处纵向留白都跟着 vPad 走,连线/芯片读同一份坐标,不会错位 */
const DEPT_Y = 300, DEPT_EMP_GAP = 56, EMP_ROW_GAP = 20, BOTTOM_PAD = 40
function layout(pad) {
  const depts = org.value.departments || []
  const cols = depts.length || 1
  const colW = Math.max(EMP_W, DEPT_W) + DEPT_GAP
  const w = cols * colW + DEPT_GAP
  const maxEmp = Math.max(0, ...depts.map(d => d.employees.length))
  const deptY = DEPT_Y + pad
  const firstEmpY = deptY + DEPT_H / 2 + DEPT_EMP_GAP + pad
  const rowPitch = EMP_H + EMP_ROW_GAP + pad
  const deptNodes = {}
  const empNodes = {}
  depts.forEach((d, i) => {
    const x = DEPT_GAP + colW * i + colW / 2
    deptNodes[d.id] = { x, y: deptY }
    d.employees.forEach((e, j) => {
      empNodes[e.id] = { x, y: firstEmpY + j * rowPitch }
    })
  })
  const lowest = maxEmp > 0
    ? firstEmpY + (maxEmp - 1) * rowPitch + EMP_H / 2
    : deptY + DEPT_H / 2
  return { ceo: { x: w / 2, y: CEO_CY }, depts: deptNodes, emps: empNodes, w, h: lowest + BOTTOM_PAD, maxEmp }
}

const nodes = computed(() => layout(vPad.value))

function deptColor(key) {
  return {
    product: 'var(--dept-product)',
    tech: 'var(--dept-tech)',
    design: 'var(--dept-design)',
    market: 'var(--dept-market)',
    ops: 'var(--dept-ops)'
  }[key] || 'var(--dept-tech)'
}

const links = computed(() => {
  const n = nodes.value
  const list = []
  const TOP = LINK_TOP
  ;(org.value.departments || []).forEach(d => {
    const dn = n.depts[d.id]
    const color = deptColor(d.colorKey)
    list.push({
      id: 'c-' + d.id,
      d: `M ${n.ceo.x} ${TOP} C ${n.ceo.x} ${TOP + 40}, ${dn.x} ${TOP - 10}, ${dn.x} ${dn.y - DEPT_H / 2}`,
      color,
      active: ceoStatus.value === 'RUNNING' || d.employees.some(e => e.status === 'RUNNING'),
      dur: 5
    })
    d.employees.forEach(e => {
      const en = n.emps[e.id]
      list.push({
        id: `e-${e.id}`,
        d: `M ${dn.x} ${dn.y + DEPT_H / 2} C ${dn.x} ${dn.y + DEPT_H / 2 + 28}, ${en.x} ${en.y - EMP_H / 2 - 28}, ${en.x} ${en.y - EMP_H / 2}`,
        color,
        active: e.status === 'RUNNING',
        dur: 4
      })
    })
  })
  return list
})

/* 流转标注芯片位置:CEO→部门连线中点 */
const edgeChips = computed(() => {
  const n = nodes.value
  const chips = []
  ;(org.value.departments || []).forEach(d => {
    const dn = n.depts[d.id]
    const s = stats.value
    let pending = 0, doneToday = 0
    d.employees.forEach(e => {
      const st = s[e.id]
      if (st) { pending += st.pending || 0; doneToday += st.doneToday || 0 }
    })
    chips.push({
      deptId: d.id,
      x: dn.x,
      y: (LINK_TOP + (dn.y - DEPT_H / 2)) / 2 + 6,
      pending, doneToday,
      color: deptColor(d.colorKey)
    })
  })
  return chips
})

const size = computed(() => ({ w: nodes.value.w, h: nodes.value.h }))

function statusClass(e) {
  if (e.status === 'RUNNING') return 'is-running breathing'
  if (e.status === 'ERROR') return 'is-error error-pulse'
  if (e.status === 'DISABLED') return 'is-off'
  return 'is-idle'
}
function dotClass(e) {
  return {
    RUNNING: 'dot-running',
    ERROR: 'dot-error',
    DISABLED: 'dot-off'
  }[e.status] || 'dot-idle'
}

function hhmm(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
}

function open(e) { selected.value = e; panelOpen.value = true }
function openAdd(dept) { addPreset.value = dept ? dept.id : null; addOpen.value = true }

/* 组织架构是这一页的唯一真数据源:取不到就明确告知,不能留一张空白图纸 */
async function load() {
  loading.value = true
  try {
    const data = await api.org()
    const depts = (data && data.departments) || []
    // 负责人排在本部门第一位,层级一眼可见
    for (const d of depts) {
      d.employees = d.employees || []
      d.employees.sort((a, b) => {
        const la = a.level === 'LEAD' ? 0 : 1
        const lb = b.level === 'LEAD' ? 0 : 1
        return la - lb || a.id - b.id
      })
    }
    org.value = { ceo: null, stats: {}, ...data, departments: depts }
    loadError.value = ''
    stale.value = false
  } catch (e) {
    const timeout = e.code === 'ECONNABORTED' || e.code === 'ETIMEDOUT'
    if (org.value.departments.length) stale.value = true
    else loadError.value = timeout
      ? '组织架构载入失败:后端长时间没有响应'
      : '组织架构载入失败,后端可能没有响应'
  } finally {
    loading.value = false
  }
  let feed = null
  try {
    feed = await api.opsFeed()
    escalations.value = feed.openEscalations || 0
    cycleCount.value = feed.cycleCount || 0
    if (feed.ceoRunning) ceoStatus.value = 'RUNNING'
  } catch (e) { /* 运营接口暂不可用时忽略 */ }
  // 首次进页用历史事件打底(倒序补进,队首始终是最新一条),之后交给 WebSocket 增量
  if (!feedLines.value.length) {
    (feed?.activities || []).slice(0, 5).reverse().forEach(a => pushFeed(a.type, a.content))
  }
  requestAnimationFrame(fit)
}

let off = null
let ro = null
/* 订阅、观察者、时钟都必须先建立:取数失败只是没数据,不能把实时通道一起带走 */
onMounted(() => {
  ro = new ResizeObserver(fit)
  ro.observe(wrap.value)
  /* HUD 带一长腿(事件流多一行、图例换行),预留高度就要重算,否则图纸白缩一圈 */
  if (hudRail.value) ro.observe(hudRail.value)
  tick()
  clockTimer = setInterval(tick, 1000)
  off = bus.on(evt => {
    if (evt.type === 'ceo-status') {
      const was = ceoStatus.value
      ceoStatus.value = evt.status || 'IDLE'
      if (evt.cycleNo) cycleCount.value = evt.cycleNo
      if (evt.status === 'RUNNING' && was !== 'RUNNING') {
        pushFeed('REPORT', `CEO 第 ${evt.cycleNo || cycleCount.value} 轮巡视开始`)
      }
    } else if (evt.type === 'escalation') {
      escalations.value = evt.count || 0
      pushFeed('ESCALATION', `有 ${escalations.value} 件事项等你拍板`)
    } else if (evt.type === 'employee-status' && evt.employeeId != null) {
      const wasRunning = statusOf(evt.employeeId) === 'RUNNING'
      applyStatus(evt.employeeId, evt.status, evt.currentTaskSummary)
      if (evt.status === 'RUNNING' && !wasRunning) {
        pushFeed('RUNNING', `${empName(evt.employeeId)} 接手 ${evt.currentTaskSummary || '新任务'}`)
      }
    } else if (evt.type === 'task-update' && evt.task?.employee?.id) {
      const t = evt.task
      const emp = t.employee
      applyStatus(emp.id,
        t.status === 'RUNNING' ? 'RUNNING'
          : t.status === 'FAILED' ? 'ERROR' : 'IDLE',
        t.status === 'RUNNING' ? (t.instruction || '').slice(0, 40) : '')
      if (t.status === 'RUNNING') pushFeed('RUNNING', `${emp.name} 接手 ${(t.instruction || '').slice(0, 26)}`)
      if (t.status === 'FAILED') pushFeed('ERROR', `${emp.name} 的任务失败,已回到待处理`)
      if (t.status === 'DONE') {
        rippleIds.add(emp.id)
        setTimeout(() => rippleIds.delete(emp.id), 1500)
        bumpStats(emp.id)
        pushFeed('DONE', `${emp.name} 交活 · 今日第 ${stats.value[emp.id]?.doneToday || 0} 件`)
      }
    }
  })
  load()
})
onBeforeUnmount(() => {
  off && off()
  ro && ro.disconnect()
  clockTimer && clearInterval(clockTimer)
})

function findEmp(id) {
  for (const d of org.value.departments || []) {
    const e = d.employees.find(x => x.id === id)
    if (e) return e
  }
  return null
}
function empName(id) { return findEmp(id)?.name || '岗位' }
function statusOf(id) { return findEmp(id)?.status }

function applyStatus(id, status, summary) {
  const e = findEmp(id)
  if (!e) return
  if (status === 'RUNNING' && e.status !== 'RUNNING' && motionOk()) {
    spotIds.add(id)
    setTimeout(() => spotIds.delete(id), 1500)
  }
  e.status = status
  e.currentTaskSummary = summary || ''
}

/* 任务完成时本地即时累加统计,不整页刷新 */
function bumpStats(empId) {
  const s = stats.value[empId]
  if (!s) return
  s.doneToday = (s.doneToday || 0) + 1
  s.doneTotal = (s.doneTotal || 0) + 1
  s.lastDoneAt = new Date().toISOString()
  s.pending = Math.max(0, (s.pending || 0) - 1)
}
</script>

<style scoped>
/* ===== 花活:入场波浪 / CEO 光环 / 扫描线 ===== */
.fo-node {
  animation: cardIn .8s var(--ease-out) both;
  animation-delay: calc(var(--i, 0) * .09s + .15s);
}
/* 入场:从景深里浮上来(模糊→清晰),一列一列错峰 */
@keyframes cardIn {
  from { opacity: 0; transform: translateY(18px) scale(.96); filter: blur(4px); }
  to { opacity: 1; transform: none; filter: none; }
}

@property --ang { syntax: '<angle>'; initial-value: 0deg; inherits: false; }
@keyframes ringSpin { to { --ang: 360deg; } }

/* 执行中扫描线 */
.node-card.emp.is-running::after {
  content: ""; position: absolute; left: 0; right: 0; height: 36%; top: -40%;
  background: linear-gradient(180deg, transparent, rgba(var(--rgb-glow), .09), transparent);
  animation: scan 2.6s linear infinite;
  pointer-events: none;
}
@keyframes scan { to { top: 120%; } }

/* 刚被派活:格子里外亮一下,像被点名 */
.node-card.emp.is-spot { animation: spotFlash 1.5s var(--ease-out) both; }
@keyframes spotFlash {
  0% { box-shadow: 0 0 0 1px rgba(var(--rgb-glow), .0); }
  22% { box-shadow: 0 0 0 1px rgba(var(--rgb-glow), .75), 0 0 26px rgba(var(--rgb-glow), .35); }
  100% { box-shadow: 0 0 0 1px rgba(var(--rgb-glow), .18); }
}
/* 交活:统计数字弹一下 */
.node-card.emp.rippling .st-num,
.node-card.emp.rippling .st-metric-num { animation: popScore .6s cubic-bezier(.34, 1.56, .64, 1) both; }

.org-chart {
  position: relative; height: calc(100vh - 140px);
  display: flex; flex-direction: column; overflow: hidden;
}
.canvas-wrap { flex: 1; min-height: 0; overflow: auto; padding: 14px; position: relative; z-index: 1; }
.chart-svg { display: block; margin: 0 auto; }

/* ===== 全息底:透视网格地平线 / 星尘 / 慢扫光 / 暗角(铺满整块舞台) ===== */
.h-bg { position: absolute; inset: 0; z-index: 0; pointer-events: none; overflow: hidden; }
.h-floor, .h-stars, .h-scan, .h-vig { position: absolute; pointer-events: none; }
.h-floor {
  left: -25%; right: -25%; bottom: 0; height: 56%;
  transform: perspective(560px) rotateX(68deg); transform-origin: bottom center;
  background-image:
    linear-gradient(color-mix(in srgb, var(--glow-cyan) 9%, transparent) 1px, transparent 1px),
    linear-gradient(90deg, color-mix(in srgb, var(--glow-cyan) 9%, transparent) 1px, transparent 1px);
  background-size: 70px 70px;
  -webkit-mask-image: radial-gradient(130% 92% at 50% 100%, #000 10%, transparent 70%);
  mask-image: radial-gradient(130% 92% at 50% 100%, #000 10%, transparent 70%);
  animation: floorRun 9s linear infinite;
}
@keyframes floorRun { to { background-position: 0 70px, 0 0; } }

.h-stars {
  inset: 0; opacity: var(--stardust);
  background-image:
    radial-gradient(1px 1px at 14% 22%, var(--glow-cyan), transparent),
    radial-gradient(1px 1px at 32% 68%, var(--warm), transparent),
    radial-gradient(1.4px 1.4px at 58% 14%, var(--glow-cyan), transparent),
    radial-gradient(1px 1px at 73% 52%, var(--text-moon), transparent),
    radial-gradient(1.2px 1.2px at 86% 30%, var(--glow-cyan), transparent),
    radial-gradient(1px 1px at 46% 84%, var(--warm), transparent);
  animation: twinkle 6.5s var(--ease-quiet) infinite alternate;
}
@keyframes twinkle {
  from { opacity: calc(var(--stardust) * .38); }
  to { opacity: var(--stardust); }
}

.h-scan {
  left: 0; right: 0; top: -40%; height: 38%;
  background: linear-gradient(180deg, transparent, rgba(var(--rgb-glow), .07), transparent);
  opacity: var(--fx-a);
  animation: scanDown 8.5s var(--ease-quiet) infinite;
}
@keyframes scanDown { from { transform: translateY(0); } to { transform: translateY(360%); } }

.h-vig {
  inset: 0;
  background: radial-gradient(122% 104% at 50% 44%, transparent 42%, rgba(var(--rgb-shadow), calc(.5 * var(--fx-a))) 100%);
}

/* ===== CEO 核心:搏动光环 + 反向旋转的双环刻度 ===== */
.ceo-core { pointer-events: none; }
.core-halo, .spin-slow, .spin-rev { transform-box: fill-box; transform-origin: 50% 50%; }
.core-halo { animation: coreBreath 3.4s var(--ease-quiet) infinite; }
@keyframes coreBreath {
  0%, 100% { opacity: .34; transform: scale(.93); }
  50% { opacity: .74; transform: scale(1.07); }
}
.spin-slow { animation: holoSpin 26s linear infinite; }
.spin-rev { animation: holoSpin 38s linear infinite reverse; }
@keyframes holoSpin { to { transform: rotate(360deg); } }
.tick { stroke-linecap: round; }

/* ===== 载入失败:浮在图纸中央的一张说明卡 ===== */
.org-fail {
  position: absolute; inset: 0;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 14px; text-align: center; padding: 32px;
  animation: riseIn .5s var(--ease-out) both;
}
.fail-mark {
  font-size: 30px; font-weight: 200; color: var(--st-error);
  border: 1px dashed rgba(var(--rgb-error), .4); width: 68px; height: 68px; border-radius: 18px;
  display: grid; place-items: center;
}
.fail-text { margin: 0; font-size: 12.5px; line-height: 1.9; color: var(--text-dim); letter-spacing: .08em; }
.fail-hint { font-size: 10.5px; letter-spacing: .1em; color: var(--text-faint); }

.stale-note {
  font-family: var(--font-mono); font-size: 9.5px; letter-spacing: .16em; color: var(--st-warn);
  border: 1px solid rgba(var(--rgb-warn), .32); border-radius: 999px; padding: 2px 9px; margin-right: 6px;
}
.duty-dot.is-stale { background: var(--st-warn); box-shadow: 0 0 8px rgba(var(--rgb-warn), .7); }

.org-loading {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center; gap: 12px;
  animation: riseIn .45s var(--ease-out) both;
}
.ld-ring {
  width: 16px; height: 16px; border-radius: 50%;
  border: 1px solid rgba(var(--rgb-glow), .28); border-top-color: var(--glow-cyan);
  animation: ldSpin 1.1s linear infinite;
}
@keyframes ldSpin { to { transform: rotate(360deg); } }
.ld-text { font-size: 11.5px; letter-spacing: .28em; color: var(--text-dim); }

/* HUD 面板统一切角:作战台的四块板子用同一个倒角 */
.chart-status, .chart-actions, .hud-feed, .hud-gauges {
  --cham: 10px; border-radius: 0;
  clip-path: polygon(var(--cham) 0, 100% 0, 100% calc(100% - var(--cham)),
                     calc(100% - var(--cham)) 100%, 0 100%, 0 var(--cham));
}

.chart-actions {
  position: absolute; top: 6px; right: 6px; z-index: 3; padding: 4px 10px 4px 8px;
  display: flex; align-items: center; gap: 2px;
}
.quiet-btn { color: var(--text-dim); letter-spacing: .15em; }
.quiet-btn:hover { color: var(--glow-cyan); }
.chart-actions :deep(.el-divider--vertical) { border-color: var(--border-hair); height: 14px; }

.chart-status {
  position: absolute; top: 6px; left: 6px; z-index: 3; padding: 7px 16px;
  display: flex; align-items: center; gap: 14px;
}
.duty-dot {
  width: 7px; height: 7px; border-radius: 50%; background: var(--glow-cyan);
  box-shadow: 0 0 8px rgba(var(--rgb-glow), .8); flex: none;
}
.hud-read { display: flex; align-items: baseline; gap: 5px; }
.hud-read b { font-family: var(--font-mono); font-size: 14px; font-weight: 600; color: var(--text-moon); }
.hud-read i { font-style: normal; font-size: 9.5px; letter-spacing: .2em; color: var(--text-faint); }
.clock { font-family: var(--font-mono); font-size: 13px; color: var(--glow-cyan); letter-spacing: .1em; }

/* ===== 底部 HUD 带:事件流 · 图例落款 · 兵力仪表 ===== */
/* ===== HUD 浮空带:贴在图纸下沿,不再从舞台里切走一条高度 ===== */
.hud-rail {
  position: absolute; left: 14px; right: 14px; bottom: 10px; z-index: 3;
  display: flex; align-items: flex-end; gap: 14px; flex-wrap: wrap;
  pointer-events: none;
}
/* 事件流照设计稿:左下固定宽度竖排浮窗,不横贯整行 */
.hud-feed { flex: 0 0 340px; width: 340px; min-width: 0; padding: 8px 14px 6px; pointer-events: auto; }
.hf-head { display: flex; align-items: center; gap: 8px; margin-bottom: 5px; }
.hf-title { font-size: 10.5px; letter-spacing: .26em; color: var(--text-dim); }
.hf-live {
  width: 5px; height: 5px; border-radius: 50%; background: var(--st-running);
  box-shadow: 0 0 7px rgba(var(--rgb-glow), .8); animation: dotBlink 2s ease-in-out infinite;
}
.hf-live.quiet { background: var(--st-off); box-shadow: none; animation: none; }
.hf-hint { font-size: 9.5px; letter-spacing: .1em; color: var(--text-faint); margin-left: auto; }
.hf-list { height: 100px; overflow: hidden; }
.hf-line {
  display: grid; grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: baseline; gap: 8px; padding: 1.5px 0;
}
.hf-tag {
  font-size: 8.5px; letter-spacing: .1em; padding: 1px 6px; border-radius: 3px;
  border: 1px solid var(--border-hair); color: var(--text-dim); white-space: nowrap;
}
.hf-text {
  display: block; font-size: 11px; color: var(--text-dim);
  overflow: hidden; white-space: nowrap; text-overflow: ellipsis;
}
.hf-time { font-family: var(--font-mono); font-size: 9.5px; color: var(--text-faint); }
/* 队首那条是刚到的:clip 逐格揭开 + 光标收尾 */
.hf-line:first-child .hf-text { animation: typeIn .8s steps(28, end) both; }
.hf-line:first-child .hf-text::after {
  content: "▌"; margin-left: 3px; color: var(--glow-cyan);
  animation: caretOut 1s var(--ease-quiet) both;
}
@keyframes typeIn { from { clip-path: inset(0 100% 0 0); } to { clip-path: inset(0 0 0 0); } }
@keyframes caretOut { 0%, 62% { opacity: .9; } 100% { opacity: 0; } }
.tone-glow .hf-text { color: var(--glow-cyan); }
.tone-ok .hf-text { color: var(--st-ok); }
.tone-warn .hf-text { color: var(--st-warn); }
.tone-error .hf-text { color: var(--st-error); }
.tone-plan .hf-text { color: var(--st-plan); }
.tone-glow .hf-tag { border-color: rgba(var(--rgb-glow), .34); color: var(--glow-cyan); }
.tone-ok .hf-tag { border-color: rgba(var(--rgb-ok), .34); color: var(--st-ok); }
.tone-warn .hf-tag { border-color: rgba(var(--rgb-warn), .34); color: var(--st-warn); }
.tone-error .hf-tag { border-color: rgba(var(--rgb-error), .34); color: var(--st-error); }
.tone-plan .hf-tag { border-color: rgba(var(--rgb-plan), .34); color: var(--st-plan); }
.hf-none { margin: 6px 0 0; font-size: 10.5px; letter-spacing: .1em; color: var(--text-faint); }

.hud-mid { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; align-items: center; gap: 7px; }
.hud-legend { display: flex; align-items: center; gap: 12px; }
.lg { display: inline-flex; align-items: center; gap: 5px; font-size: 9.5px; letter-spacing: .16em; color: var(--text-faint); }
.lg-dot { width: 6px; height: 6px; border-radius: 50%; }
.lg-idle { background: var(--st-idle); }
.lg-run { background: var(--st-running); box-shadow: 0 0 6px rgba(var(--rgb-glow), .65); }
.lg-err { background: var(--st-error); }
.lg-off { background: var(--st-off); }
.chart-slogan { pointer-events: none; }
.slogan-text { font-size: 11px; letter-spacing: .42em; margin-left: .42em; color: var(--text-faint); font-family: var(--font-serif); }

.hud-gauges { flex: 0 0 auto; display: flex; align-items: center; gap: 12px; padding: 7px 14px; pointer-events: auto; }
.gauge { position: relative; width: 58px; height: 58px; }
.g-arc { width: 58px; height: 58px; transform: rotate(-90deg); display: block; }
.g-track { fill: none; stroke: var(--border-hair-strong); stroke-width: 5; }
.g-val { fill: none; stroke-width: 5; stroke-linecap: round; transition: stroke-dashoffset .9s var(--ease-out); }
.g-read { position: absolute; inset: 0; display: grid; place-content: center; text-align: center; gap: 2px; }
.g-read b { font-family: var(--font-mono); font-size: 16px; font-weight: 600; line-height: 1; }
.g-read i { font-style: normal; font-size: 8.5px; letter-spacing: .14em; color: var(--text-faint); }

/* ===== 流转标注芯片 ===== */
.edge-chip {
  width: 100%; height: 100%; border-radius: 999px;
  display: flex; align-items: center; justify-content: center; gap: 10px;
  padding: 0 12px;
  border-color: color-mix(in srgb, var(--dept-color) 28%, transparent);
}
.chip-down, .chip-up { font-size: 10.5px; letter-spacing: .06em; white-space: nowrap; font-family: var(--font-mono); }
.chip-down { color: var(--text-dim); }
.chip-up { color: color-mix(in srgb, var(--dept-color) 80%, white); }

/* ===== 节点卡片 ===== */
.node-card {
  width: 100%; height: 100%; border-radius: 13px; cursor: pointer;
  background: linear-gradient(155deg, rgba(var(--rgb-panel-hi), .92), rgba(var(--rgb-surface-2), .9));
  border: 1px solid var(--border-hair);
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  transition: border-color .45s var(--ease-quiet), transform .45s var(--ease-out),
              box-shadow .45s var(--ease-quiet);
  font-family: var(--font-sans);
  box-shadow: var(--shadow-soft), inset 0 1px 0 rgba(var(--rgb-ink), .05);
  position: relative;
}
.node-card:hover { transform: translateY(-3px); }

/* 全息卡片:切角 + 内斜面。clip-path 会剪掉外投影,所以厚度靠内斜面撑出来 */
.node-card.dept, .node-card.emp, .node-card.blank-cell {
  --cham: 11px; border-radius: 0;
  clip-path: polygon(var(--cham) 0, 100% 0, 100% calc(100% - var(--cham)),
                     calc(100% - var(--cham)) 100%, 0 100%, 0 var(--cham));
  box-shadow: inset 0 1px 0 rgba(var(--rgb-ink), .05),
              inset -12px -12px 26px rgba(var(--rgb-shadow), .3);
}
.node-card.dept:hover, .node-card.emp:hover {
  box-shadow: inset 0 0 26px color-mix(in srgb, var(--dept-color) 14%, transparent),
              inset 0 1px 0 rgba(var(--rgb-ink), .06);
}

.node-card.ceo { padding: 8px 12px; gap: 4px; border-color: rgba(var(--rgb-glow), .35);
  background: linear-gradient(160deg, rgba(var(--rgb-boss), .9), rgba(var(--rgb-surface-2), .92));
  overflow: hidden;
}
/* CEO 告警徽标 */
.ceo-badge {
  position: absolute; top: -10px; right: -12px; z-index: 2;
  background: linear-gradient(135deg, rgb(var(--rgb-error-deep)), rgb(var(--rgb-error-deeper)));
  border: 1px solid rgba(var(--rgb-error), .5);
  border-radius: 999px; padding: 3px 12px;
  font-size: 11px; letter-spacing: .08em; color: var(--on-error);
  box-shadow: 0 0 14px rgba(var(--rgb-error), .35);
  animation: badgePulse 2.2s var(--ease-quiet) infinite;
}
.badge-num { font-family: var(--font-mono); font-weight: 600; color: var(--on-error-hi); }
@keyframes badgePulse {
  0%, 100% { box-shadow: 0 0 8px rgba(var(--rgb-error), .25); }
  50% { box-shadow: 0 0 20px rgba(var(--rgb-error), .55); }
}

/* 创始人视角:待拍板提醒挂在左上状态栏 */
.duty-badge {
  font-size: 11px; letter-spacing: .08em; padding: 2px 11px; border-radius: 999px;
  background: linear-gradient(135deg, rgba(var(--rgb-error-deep), .25), rgba(var(--rgb-error-deeper), .3));
  border: 1px solid rgba(var(--rgb-error), .45); color: var(--on-error);
  animation: badgePulse 2.2s var(--ease-quiet) infinite; cursor: pointer;
}
.duty-badge .badge-num { font-family: var(--font-mono); font-weight: 600; color: var(--on-error-hi); }

/* CEO 旋转光环:一道天青光沿卡片边框巡行 */.node-card.ceo::before {
  content: ""; position: absolute; inset: 0; border-radius: 13px; padding: 1.4px;
  background: conic-gradient(from var(--ang),
    transparent 0 62%, rgba(var(--rgb-glow), .85) 76%, rgba(var(--rgb-glow), .25) 84%, transparent 94%);
  -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  -webkit-mask-composite: xor;
  mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  mask-composite: exclude;
  animation: ringSpin 5.5s linear infinite;
  pointer-events: none;
}
.node-card.ceo:hover { border-color: rgba(var(--rgb-glow), .6);
  box-shadow: 0 0 26px rgba(var(--rgb-glow), .12), var(--shadow-soft); }
.ceo-seal { opacity: .9; }
.node-card.ceo .node-name { font-size: 16px; letter-spacing: .3em; margin-left: .3em; }

.node-card.dept { padding: 8px; border-color: color-mix(in srgb, var(--dept-color) 32%, transparent);
  background: linear-gradient(155deg, color-mix(in srgb, var(--dept-color) 7%, rgba(var(--rgb-panel-hi), .9)), rgba(var(--rgb-surface-2), .9));
  overflow: hidden;
}
/* 部门卡顶沿一道发光横杠:一眼认出这一列属于哪个部门 */
.node-card.dept::before {
  content: ""; position: absolute; top: 0; left: 10%; right: 10%; height: 2px;
  background: linear-gradient(90deg, transparent, var(--dept-color), transparent);
  box-shadow: 0 0 9px var(--dept-color); opacity: .85; pointer-events: none;
}
/* 部门卡悬停流光扫过 */
.node-card.dept::after {
  content: ""; position: absolute; top: 0; bottom: 0; width: 46%; left: -60%;
  background: linear-gradient(105deg, transparent, color-mix(in srgb, var(--dept-color) 14%, transparent), transparent);
  transform: skewX(-18deg); transition: left .7s var(--ease-out); pointer-events: none;
}
.node-card.dept:hover::after { left: 120%; }
.node-card.dept .node-name { color: var(--dept-color); font-size: 14px; letter-spacing: .28em; margin-left: .28em; }

.node-card.emp {
  flex-direction: row; align-items: center; gap: 12px; padding: 0 14px 0 12px;
  border-color: color-mix(in srgb, var(--dept-color) 18%, transparent);
  overflow: hidden;
}
.node-card.emp:hover {
  border-color: color-mix(in srgb, var(--dept-color) 45%, transparent);
  box-shadow: 0 0 20px color-mix(in srgb, var(--dept-color) 10%, transparent), var(--shadow-soft);
}
.emp-edge {
  position: absolute; left: 0; top: 14%; bottom: 14%; width: 2px; border-radius: 2px;
  background: linear-gradient(180deg, transparent, var(--dept-color), transparent);
  opacity: .45;
}
.node-card.emp.is-running { border-color: rgba(var(--rgb-glow), .55); }
.node-card.emp.is-running .emp-edge { background: linear-gradient(180deg, transparent, var(--glow-cyan), transparent); opacity: .9; }
.node-card.emp.is-error { border-color: rgba(var(--rgb-error), .5); }
.node-card.emp.is-off { opacity: .35; }
.node-card.emp.is-lead {
  border-color: color-mix(in srgb, var(--dept-color) 46%, transparent);
  background: linear-gradient(155deg, color-mix(in srgb, var(--dept-color) 7%, rgba(var(--rgb-panel-hi), .92)), rgba(var(--rgb-surface-2), .9));
}
.node-card.emp.is-lead::before {
  content: ""; position: absolute; top: 0; left: 14%; right: 14%; height: 1px;
  background: linear-gradient(90deg, transparent, color-mix(in srgb, var(--dept-color) 65%, white), transparent);
  opacity: .6;
}
.lead-badge {
  flex: none; font-size: 8.5px; letter-spacing: .06em; padding: 1px 6px; border-radius: 999px;
  color: color-mix(in srgb, var(--dept-color) 85%, white);
  background: color-mix(in srgb, var(--dept-color) 13%, transparent);
  border: 1px solid color-mix(in srgb, var(--dept-color) 25%, transparent);
  white-space: nowrap;
}

.emp-body { flex: 1; min-width: 0; }
.emp-head { display: flex; align-items: center; gap: 8px; }
.node-name { font-size: 13.5px; letter-spacing: .12em; white-space: nowrap; }
.node-title {
  font-size: 11px; color: var(--text-dim); letter-spacing: .08em; margin-top: 3px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 138px;
}
.is-running .node-title { color: var(--glow-cyan); font-family: var(--font-mono); font-size: 10.5px; }

.emp-stats {
  margin-top: 5px; font-size: 10px; color: var(--text-faint);
  font-family: var(--font-mono); letter-spacing: .04em;
  display: flex; align-items: center; white-space: nowrap;
}
.st-num { color: color-mix(in srgb, var(--dept-color) 75%, white); font-size: 11px; }
.st-metric { display: inline-flex; align-items: baseline; gap: 4px; color: var(--dept-color); }
.st-metric-num { font-family: var(--font-mono); font-size: 14px; font-weight: 600;
                 color: color-mix(in srgb, var(--dept-color) 85%, white);
                 text-shadow: 0 0 12px color-mix(in srgb, var(--dept-color) 40%, transparent); }
.st-pending { color: var(--st-warn); }
.st-sep { display: inline-block; width: 1px; height: 9px; background: var(--border-hair-strong);
          margin: 0 6px; opacity: .7; }

.status-dot { width: 7px; height: 7px; border-radius: 50%; flex: none; margin-left: auto; position: relative; }
.dot-idle { background: var(--st-idle); }
.dot-running { background: var(--st-running); box-shadow: 0 0 8px rgba(var(--rgb-glow), .7); animation: dotBlink 2s ease-in-out infinite; }
.dot-error { background: var(--st-error); box-shadow: 0 0 8px rgba(var(--rgb-error), .6); }
.dot-off { background: var(--st-off); }
@keyframes dotBlink { 0%,100% { opacity:.4 } 50% { opacity:1 } }

.blank-cell {
  border: 1px dashed rgba(var(--rgb-ink), .18); background: var(--tint-soft);
  color: var(--text-faint); font-size: 12px; letter-spacing: .3em;
  flex-direction: row; gap: 10px;
  transition: all .4s var(--ease-quiet);
}
.blank-cell:hover {
  border-color: rgba(var(--rgb-glow), .45); color: var(--glow-cyan-dim);
  box-shadow: 0 0 18px rgba(var(--rgb-glow), .07);
}
.blank-plus { font-size: 16px; font-weight: 200; margin-left: .3em; }
.blank-hint { margin-left: -.3em; }

/* 流光虚线:常驻缓行,派活时提速;CSS 动画吃 prefers-reduced-motion 总开关 */
.flow-line { stroke-linecap: round; stroke-dasharray: 8 16; animation: dashRun 3.6s linear infinite; }
.flow-line.is-fast { animation-duration: 1.1s; }
@keyframes dashRun { to { stroke-dashoffset: -24; } }

/* 执行中的岗位:底边一道不定位的进度扫条(时长未知,所以只表示「在跑」) */
.emp-prog {
  position: absolute; left: 0; right: 0; bottom: 0; height: 2px; overflow: hidden;
  background: rgba(var(--rgb-glow), .12);
}
.emp-prog i {
  display: block; width: 34%; height: 100%;
  background: linear-gradient(90deg, transparent, var(--glow-cyan), transparent);
  animation: progSlide 1.6s linear infinite;
}
@keyframes progSlide { from { transform: translateX(-110%); } to { transform: translateX(320%); } }

@media (max-width: 900px) {
  .hud-legend { display: none; }
  .hud-feed { flex: 1 1 100%; width: auto; }
  .hf-list { height: 60px; }
  .gauge, .g-arc { width: 46px; height: 46px; }
}
</style>
