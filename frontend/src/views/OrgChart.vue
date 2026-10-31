<template>
  <div class="org-chart" ref="stage" @mousemove="onTilt" @mouseleave="resetTilt">
    <!-- 虚空底:天光 / 三层墨山 / 两道云气 / 尘 / 暗角,只铺图纸区,不压读数带。
         原来这里是「透视网格 + 慢扫光」—— 那是全息作战台的语言:地平线、网格、扫描波。
         这套语汇讲的是"数据正在被投射出来",不是"一座宗门悬在云上"。
         换掉地网之后,页面才有远近:天光在上,云在下,山从云里挑出来。 -->
    <div class="h-bg" aria-hidden="true">
      <div class="h-sky"></div>
      <!-- 天光:从画面上沿斜洒下来的一道光锥,落在阵眼上。
           这一页原来没有光源 —— 所有东西一样亮,所以是平的。
           有了一道从上面下来的光,CEO 才"立"在光里,而不是"贴"在图上。 -->
      <div class="h-beam"></div>
      <!-- 三层墨山:用平滑脊线画,不是 clip-path 的折线。
           折线是数据图的语言 —— 它画出来的是"三角形",不是"山"。
           脊线用 C 命令连,长短峰错开,远山高而糊、近山低而实。 -->
      <svg class="h-mtn is-far" viewBox="0 0 1200 260" preserveAspectRatio="none">
        <path d="M0,260 L0,150 C58,124 108,170 168,134 C228,98 266,152 328,120 C390,88 428,144 498,112
                 C568,80 608,142 678,114 C748,86 788,148 858,118 C928,88 968,152 1038,122
                 C1108,92 1158,142 1200,126 L1200,260 Z" />
      </svg>
      <svg class="h-mtn is-mid" viewBox="0 0 1200 260" preserveAspectRatio="none">
        <path d="M0,260 L0,196 C68,168 118,214 188,172 C248,136 298,200 368,160 C438,120 478,194 548,154
                 C618,114 668,190 738,152 C808,114 858,192 928,156 C998,120 1058,184 1118,154
                 C1168,130 1188,172 1200,164 L1200,260 Z" />
      </svg>
      <div class="h-mist is-low"></div>
      <svg class="h-mtn is-near" viewBox="0 0 1200 260" preserveAspectRatio="none">
        <path d="M0,260 L0,226 C88,210 148,236 238,218 C328,200 388,240 478,222 C568,204 628,242 718,224
                 C808,206 868,242 958,222 C1048,202 1118,236 1200,218 L1200,260 Z" />
      </svg>
      <div class="h-mist is-high"></div>
      <div class="h-stars"></div>
      <div class="h-vig"></div>
    </div>

    <!-- 顶栏:左读数 · 右操作。原来这两块 absolute 浮在画布顶上,图一滚就从它们底下穿过去;
         现在它们是舞台的第一段 —— 顺序必须是 顶栏 / 画布 / 底部带,它就是这里的第 0 段。 -->
    <div class="stage-bar">
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
        <!-- 导出的是每个岗位真正收到的指令原文:判断这套编制合不合理,得横着比 -->
        <el-dropdown trigger="click" @command="exportManual">
          <el-button text class="quiet-btn">说明书</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="md">Markdown</el-dropdown-item>
              <el-dropdown-item command="docx">Word</el-dropdown-item>
              <el-dropdown-item command="pdf">PDF</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-divider direction="vertical" />
        <el-button text class="quiet-btn" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="canvas-wrap" ref="wrap">
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
            <stop offset="0%" :stop-color="haloColor" stop-opacity=".5" />
            <stop offset="100%" :stop-color="haloColor" stop-opacity="0" />
          </radialGradient>
          <!-- 泳道光:上端淡入、腰上最亮、到底散尽 —— 两端都不留硬边 -->
          <linearGradient v-for="d in org.departments" :key="'lg-' + d.id" :id="'lane-' + d.id"
                          x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" :stop-color="deptColor(d.colorKey)" stop-opacity="0" />
            <stop offset="9%" :stop-color="deptColor(d.colorKey)" stop-opacity=".15" />
            <stop offset="46%" :stop-color="deptColor(d.colorKey)" stop-opacity=".06" />
            <stop offset="100%" :stop-color="deptColor(d.colorKey)" stop-opacity="0" />
          </linearGradient>
        </defs>

        <!-- CEO 冠冕:只在上方展开的一段刻度弧。
             原来是两圈整圆环 —— 半径 145 的下沿正好从部门卡和浮空芯片中间穿过,
             虚线加刻度在深底上碎成一片噪点,还和芯片打架。
             改成半圈:它替 CEO 画出「顶点」这一层,但永远不往下侵占部门的地盘。 -->
        <g class="ceo-core" :transform="`translate(${nodes.ceo.x}, ${nodes.ceo.y})`" aria-hidden="true">
          <ellipse class="core-halo" cx="0" cy="-26" rx="212" ry="132" fill="url(#coreHalo)" />
          <path class="crest-arc" :d="crest.d" fill="none" :stroke="haloColor" />
          <path class="crest-sweep" v-if="!calm" :d="crest.d" fill="none" :stroke="haloColor" />
          <line v-for="t in crest.ticks" :key="'tk' + t.k" class="crest-tick"
                :x1="t.x1" :y1="t.y1" :x2="t.x2" :y2="t.y2"
                :stroke-width="t.major ? 1.3 : 1"
                :stroke="haloColor" :stroke-opacity="t.major ? .5 : .26" />
        </g>

        <!-- 剑气:CEO 是持剑的人,五条链路是从他手上递出去的剑意。
             原来是「贝塞尔弧 + 沿轨道匀速滑行的光点」—— 那是地铁线路图的语言:
             有轨道、有班次、按时刻表跑。剑意不是这么走的:它从手上射出去,到了就散。
             所以改成三件东西:细而利的本体、一段从阵眼射向峰头的流光、落点的剑尖。
             本体不加圆头 —— 圆头是管线的语言,剑锋是尖的。 -->
        <g class="links" fill="none">
          <path v-for="l in links" :key="'b-' + l.id" :d="l.d" :stroke="l.color"
                stroke-width="8" :stroke-opacity="l.active ? .12 : .06" stroke-linecap="round" />
          <path v-for="l in links" :key="l.id" :d="l.d" :id="'p-' + l.id"
                :stroke="l.color" :stroke-opacity="l.active ? .62 : .38" stroke-width="1.2" />
        </g>
        <!-- 射出的那一道:pathLength 归一成 1000,一段 90 长的光沿路径走完一个周期。
             每条路的时长和起跑时间都不同 —— 五条齐步走是动画,不齐步走才是气。
             这里不用 !calm 去砍掉:关掉动效的人看到的应该是一道停在半路的剑光,
             不是一条空 link —— CSS 动画自己吃 @media 就行,不必让 JS 再判断一次。 -->
        <g class="blades" fill="none">
          <path v-for="(l, k) in links" :key="'bl-' + l.id" :d="l.d" pathLength="1000"
                class="blade" :class="{ 'is-hot': l.active }" :stroke="l.color"
                :style="{ animationDuration: (l.active ? l.dur * .72 : l.dur * 1.6) + 's',
                          animationDelay: (k * .43) + 's' }" />
        </g>
        <!-- 落点:剑意扎进这张卡的上沿。主干才有 —— 十九条全扎,整页就成刺猬了 -->
        <g class="tips">
          <g v-for="l in links" v-show="l.tip" :key="'tp-' + l.id"
             :transform="`translate(${l.tx}, ${l.ty})`">
            <path d="M0 -11 L0 1" :stroke="l.color" stroke-width="1.2" stroke-opacity=".4" />
            <circle cy="4" r="1.7" :fill="l.color" :opacity="l.active ? .95 : .42" />
          </g>
        </g>

        <!-- 部门负载原来画成五枚浮在 CEO→部门连线上的小芯片。
             问题是它们各自悬在不同 x 上,而看负载的全部意义就是**横着比** ——
             五个不在同一基线上的数字,读一次要来回跳五次眼睛。
             搬到底部那条读数带里(负载几件),画布上就只剩结构。 -->

        <!-- 峰体:每列从部门卡向下淌一道自己颜色的极淡光。
             五个部门的编制天生不齐(2 / 5 / 2 / 4 / 6),短的两列底下就空出三四百像素,
             那不是留白,是"图上缺了一块"。
             原来铺的是竖直矩形 —— 矩形就是柱子的形状,五列于是读成"五根柱子"。
             改成上窄下宽:卡片是峰顶,光是顺着山脚散下去的,五列才读成五座峰。 -->
        <g class="lanes" aria-hidden="true">
          <path v-for="l in lanes" :key="'ln-' + l.id"
                :d="`M ${l.x - 104} ${l.y1} L ${l.x + 104} ${l.y1} L ${l.x + 156} ${l.y2} L ${l.x - 156} ${l.y2} Z`"
                :fill="`url(#lane-${l.id})`" />
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
                  <!-- 原来这里是「本周 · 今日 · 累计 · 上次 · 待」五段流水,按 0.78 的
                       适配比例落到屏幕上只有 8px,读起来是一串点。
                       而且主位放的是 metricLabel —— 只有五个负责人填了这个字段,
                       其余十四个人的卡片上是「本周交付」后面跟着一片空白,
                       同一排卡片一半有数字一半没有,像坏了一半。
                       现在统一成:今日(唯一的主数,人人都有) | 累计或自定义指标 | 待。 -->
                  <div class="emp-stats" v-if="stats[e.id]">
                    <span class="st-hero">
                      <b>{{ stats[e.id].doneToday }}</b><i>今日</i>
                    </span>
                    <i class="st-split"></i>
                    <span class="st-pair">
                      <b>{{ stats[e.id].metricValue != null ? stats[e.id].metricValue : stats[e.id].doneTotal }}</b>{{ stats[e.id].metricLabel || '累计' }}
                    </span>
                    <span v-if="stats[e.id].pending > 0" class="st-pair is-pending">
                      <b>{{ stats[e.id].pending }}</b>待
                    </span>
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

    <!-- 一个部门都没有:图纸上什么都不摆,人会以为这页坏了,而不是"还没开始" -->
    <div v-else-if="!org.departments.length" class="org-empty">
      <div class="empty-card glass">
        <div class="empty-mark" aria-hidden="true"><i></i><i></i><i></i></div>
        <p class="empty-title">还没有部门</p>
        <p class="empty-text">
          组织是这家公司的骨架。先立一个部门,再往里放岗位 ——<br />
          每个岗位写清一句职责,它就能自己接活、自己交活。
        </p>
        <el-button @click="deptOpen = true">建第一个部门</el-button>
      </div>
    </div>

    <!-- 画布底沿:滚到底就没有,没滚到底才出现 ——
         没有它,最后一行卡是被齐着切开的,读起来是"坏了";有了它,是"下面还有"。 -->
    <div class="canvas-fade" :class="{ 'is-on': moreBelow }" aria-hidden="true"></div>

    <!-- 底部:一条读数,不是三块面板。
         原来这里并排放着「事件流(两行日志)· 部门负载(五根柱)· 岗位态势(四个环)」——
         三种完全不同的视觉语言塞在同一条 110px 的带子里,窄屏上还要互相夺宽度
         (1080 宽时:事件流只剩 594、负载压到 246、四个环挤进 200)。
         更要紧的是其中两块**信息本来就重复**:部门卡上已经印着今日/待办,
         顶栏读数里也已经写着在岗与执行中 —— 同一组数字换个画法再画一遍,
         不是信息,是噪音。
         而且那五根柱子被数据形状判了死刑:4 / 14 / 0 / 28 / 5,按最大值归一之后
         四根只剩 6px 的毛刺、一根是空的 —— 读者看到的不是"负载不均",是"画坏了"。
         现在一行:左边一句「刚刚发生了什么」,右边一排「现在什么数」。 -->
    <footer class="hud-rail" ref="rail">
      <div class="hud-live" :class="lead ? 'tone-' + lead.tone : 'is-quiet'">
        <span class="hf-live" :class="{ quiet: !lead }"></span>
        <span class="hl-cap">实时</span>
        <span class="hf-time">{{ lead ? lead.time : '--:--' }}</span>
        <span v-if="lead" class="hf-tag">{{ lead.tag }}</span>
        <!-- 一条就够:它回答的是"现在在干什么",不是"翻旧账"。要翻去 看板。 -->
        <span class="hf-text">{{ lead ? lead.text : '公司在等一个目标:派活或 CEO 巡视后,这里会逐条补上。' }}</span>
      </div>

      <div class="hud-nums">
        <div class="hn-group is-load">
          <span class="hl-cap">负载</span>
          <span v-for="d in loadBars" :key="d.id" class="hn-load" :title="d.hint">
            <i :style="{ background: d.color }"></i>{{ d.short }}<b>{{ d.done + d.pending }}</b>
          </span>
        </div>
        <span class="hn-sep"></span>
        <div class="hn-group">
          <span v-for="g in gauges" :key="g.label" class="hn-stat" :title="g.hint">
            <!-- 0 的那项要是和其余几个一样亮,读者看到的是「仪表坏了」,不是「这项是零」 -->
            <b :style="{ color: Number(g.num) ? g.color : 'var(--text-faint)' }">{{ g.num }}</b><i>{{ g.label }}</i>
          </span>
        </div>
      </div>
    </footer>

    <!-- 落款:竖排在右侧留白里,像画上的边款。原来它横在事件流和仪表之间,
         七个字孤零零悬在七百像素的空当正中,读起来是"这里忘了放东西"。
         现在图按宽度铺满,窄屏根本没有留白可放 —— 没有留白就不摆,别硬压在卡片上。 -->
    <div class="chart-slogan" v-if="sloganRoom" aria-hidden="true"><span>一人,一格,一军</span></div>

    <EmployeePanel v-model="panelOpen" :employee="selected" @refresh="load" />
    <AddEmployeeDialog v-model="addOpen" :departments="org.departments" :presetDept="addPreset" @created="load" />
    <DepartmentDialog v-model="deptOpen" @created="load" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { api, bus } from '../api'
import { motionOk } from '../utils/motion'
import GeoAvatar from '../components/GeoAvatar.vue'
import EmployeePanel from '../components/EmployeePanel.vue'
import AddEmployeeDialog from '../components/AddEmployeeDialog.vue'
import DepartmentDialog from '../components/DepartmentDialog.vue'

const EMP_W = 236, EMP_H = 78, CEO_W = 250, CEO_H = 92, DEPT_W = 172, DEPT_H = 60
const calm = !motionOk()
/* CEO_CY 给冠冕弧留出上方空间(弧顶要落在 viewBox 内);LINK_TOP 必须落在 CEO 卡片下缘之外 */
const DEPT_GAP = 56, CEO_CY = 142, LINK_TOP = 190

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
/* 图按宽度铺满之后,窄屏边上没有留白可放边款了 —— 这时它不该压到最后一列上 */
const sloganRoom = ref(false)
/* 画布下面还有没有内容:决定底沿那道渐隐亮不亮 */
const moreBelow = ref(false)
const rail = ref(null)
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

/* 四个公司级读数。上一版是四个 56px 的环形仪表 —— 窄屏上四个圈挤进 200px,
   而且其中两个恒为 0,两根空环并排看着像仪表坏了。数字本身已经够读,
   不需要再用一圈弧把它说第二遍。 */
function gauge(label, num, color, hint) { return { label, num, color, hint } }
const gauges = computed(() => {
  const t = totalEmp.value
  const s = stats.value
  let done = 0, pending = 0
  allEmps.value.forEach(e => {
    const st = s[e.id]
    if (st) { done += st.doneToday || 0; pending += st.pending || 0 }
  })
  return [
    gauge('在岗', onDuty.value, 'var(--glow-cyan)', `${onDuty.value}/${t} 个岗位启用`),
    gauge('执行', runningCount.value, 'var(--st-running)', '正在执行任务的岗位占比'),
    gauge('交付', done, 'var(--st-ok)', '今日已完成 / (今日完成 + 待处理)'),
    gauge('异常', errorCount.value, 'var(--st-error)', '上次任务失败的岗位')
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
  // 原来这里 .slice(0, 46) 硬切:一条 60 字的周报会先被砍成半句,再被 CSS 省略号
  // 砍第二次,屏幕上永远只剩 20 个字加一个「…」。不切,交给宽度自己决定显示多少。
  const body = String(text || '').trim()
  const head = feedLines.value[0]
  if (!body || (head && head.text === body)) return
  feedLines.value.unshift({
    key: ++feedSeq,
    tag: typeMap[type] || type || '事件',
    tone: toneMap[type] || 'dim',
    text: body,
    time: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
  })
  if (feedLines.value.length > 4) feedLines.value.length = 4
}
/* 底带只读队首那一条。留四条是为了让「同一条重复推送」的判重还有可比的邻居,
   真显示出来的永远是最新一条 —— 一条读数带放不下第二行,也不用放。 */
const lead = computed(() => feedLines.value[0] || null)

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
/* 冠冕弧:以卡片中心为原点、圆心在下方的一段圆弧(R=170),从卡片左上斜上方
   越过头顶落到右上。端点刻意收在 ±130(卡片半宽 125)之外一点,
   这样圆弧永远不会压到卡片,也不会像整圆环那样垂到部门行里去。 */
const crest = (() => {
  const R = 170, CY = 46.4
  const a0 = 220.1 * Math.PI / 180
  const a1 = 319.9 * Math.PI / 180
  const pt = (a, r) => ({
    x: +(Math.cos(a) * r).toFixed(2),
    y: +(CY + Math.sin(a) * r).toFixed(2)
  })
  const s = pt(a0, R), e = pt(a1, R)
  const ticks = []
  const N = 21
  for (let k = 0; k < N; k++) {
    const a = a0 + (a1 - a0) * k / (N - 1)
    const major = k % 5 === 0
    const p1 = pt(a, R), p2 = pt(a, R - (major ? 13 : 7))
    ticks.push({ k, major, x1: p1.x, y1: p1.y, x2: p2.x, y2: p2.y })
  }
  return { d: `M ${s.x} ${s.y} A ${R} ${R} 0 0 1 ${e.x} ${e.y}`, ticks }
})()

/* 自适应缩放:宽高取小。上一版只按宽度缩 —— 图铺满了,但 1920×960 上算出来
   1.0,画布装不下,最后一行岗位卡被底带去齐截掉一截。那半截卡片比字号小半号
   难看得多:读者看到的是「页面没画完」。
   所以先问一句「一屏装得下吗」:装得下就装(1920×960 → 0.86,正文 9.4px,
   左右各留 279)。装不下(矮窗口)才守住 MIN_SCALE,把剩下的高度交给画布滚,
   底沿渐隐负责说明「下面还有」。 */
const MIN_SCALE = 0.8
const MAX_SCALE = 1
const MAX_PAD = 150
const WRAP_PAD = 14
/* 舞台底边到视口底之间留给 .main 的 padding-bottom */
function fit() {
  const el = wrap.value
  const st = stage.value
  if (!el || !st) return
  /* 先把舞台钉成「视口减去舞台以上的部分」:顶栏 / 画布 / 底部带从此是同一块定高舞台的
     三段,谁也不 absolute 浮在谁身上。
     画布高度一旦是确定的,下面就能直接量它 —— 而不用从视口反推。 */
  const top = st.getBoundingClientRect().top + window.scrollY
  const pb = parseFloat(getComputedStyle(st.parentElement).paddingBottom) || 0
  const vh = document.documentElement.clientHeight
  st.style.height = Math.max(380, Math.round(vh - top - pb)) + 'px'
  /* 底沿渐隐要贴在底部带的上沿 —— 带子多高由它自己决定,这里只把数值传下去 */
  st.style.setProperty('--rail-h', (rail.value ? rail.value.offsetHeight : 0) + 'px')

  /* 组织还没到就别算比例:这时 layout(0) 是「一个部门都没有」的 348×324,
     按它算出来是 scale 1.0 + vPad 150(「还有富余,把行距摊开」),数据一到全图 1931 高。
     宣纸主题上就是这么炸的:整页只剩一张拉长的图。 */
  if (!(org.value.departments || []).length) return

  const base = layout(0)
  const availW = el.clientWidth - WRAP_PAD * 2
  const availH = el.clientHeight - WRAP_PAD * 2
  const scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, availW / base.w, availH / base.h))
  viewScale.value = scale
  /* 高度真有富余(大屏、或者部门都很浅)才把行距摊开 —— 图挤在上面、底下空一片,
     和当年「表格永远顶着上沿」是同一个毛病。 */
  const extra = availH - base.h * scale
  const slack = extra > 0 ? extra / (scale * (base.maxEmp + 1)) : 0
  vPad.value = Math.max(0, Math.min(MAX_PAD, Math.round(slack)))
  sloganRoom.value = availW - base.w * scale > 150
  nextTick(() => {
    if (el.scrollWidth > el.clientWidth + 1) el.scrollLeft = (el.scrollWidth - el.clientWidth) / 2
    syncMore()
  })
}

/* 画布能不能再往下滚 —— 只有「还能」的时候才在底沿压一道渐隐 */
function syncMore() {
  const el = wrap.value
  if (!el) return
  moreBelow.value = el.scrollTop + el.clientHeight < el.scrollHeight - 4
}

/* 图纸几何:每一处纵向留白都跟着 vPad 走,连线/读同一份坐标,不会错位。
   纵向这几档都收了一格:整张图是按「一屏看全」缩放的,省下 30px 图高
   就能把适配比例从 .88 抬到 .90 以上 —— 每一点都直接变成字大一号。 */
const DEPT_Y = 268, DEPT_EMP_GAP = 48, EMP_ROW_GAP = 16, BOTTOM_PAD = 26
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
  /* 泳道光:从部门卡下沿一直淌到全图最低那张卡的位置。
     终点对齐的是「最深的那一列」,所以五道光长短不一,收在同一个底 —— 板子才有下沿。 */
  const laneList = depts.map(d => ({
    id: d.id,
    x: deptNodes[d.id].x,
    y1: deptY + DEPT_H / 2 + 2,
    y2: lowest + 14
  }))
  return { ceo: { x: w / 2, y: CEO_CY }, depts: deptNodes, emps: empNodes, lanes: laneList, w, h: lowest + BOTTOM_PAD, maxEmp }
}

const nodes = computed(() => layout(vPad.value))
const lanes = computed(() => nodes.value.lanes)

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
  const depts = org.value.departments || []
  const cnt = depts.length || 1
  depts.forEach((d, i) => {
    const dn = n.depts[d.id]
    const color = deptColor(d.colorKey)
    /* 起点沿 CEO 卡底边铺开 —— 五条线如果都从同一个点出发,
       在浅带宽里会挤成一个结(两条线还互相穿过),看起来像根打了结的电线。
       铺开之后是一把扇子:起点本来就有先后的位置关系。 */
    const t = cnt > 1 ? i / (cnt - 1) : .5
    const sx = n.ceo.x + (t - .5) * CEO_W * .58
    const dy = dn.y - DEPT_H / 2
    const span = dy - TOP
    list.push({
      id: 'c-' + d.id,
      /* 这一段只有 50px 高(CEO 卡底边到峰顶)。这么短的距离上,任何贝塞尔都是
         "甩上去的一根绳子" —— 弧度吃不掉,只剩扁。剑意在这一段本来就该是直的。 */
      d: `M ${sx} ${TOP} L ${dn.x} ${dy}`,
      color,
      active: ceoStatus.value === 'RUNNING' || d.employees.some(e => e.status === 'RUNNING'),
      dur: 5,
      /* 剑意的落点:扎在这座峰的卡沿上 */
      tip: true, tx: dn.x, ty: dy, span
    })
    d.employees.forEach(e => {
      const en = n.emps[e.id]
      list.push({
        id: `e-${e.id}`,
        d: `M ${dn.x} ${dn.y + DEPT_H / 2} C ${dn.x} ${dn.y + DEPT_H / 2 + 28}, ${en.x} ${en.y - EMP_H / 2 - 28}, ${en.x} ${en.y - EMP_H / 2}`,
        color,
        active: e.status === 'RUNNING',
        dur: 4,
        tip: false, tx: en.x, ty: en.y - EMP_H / 2
      })
    })
  })
  return list
})

/* 部门负载:五个部门排在同一条基线上,才能一眼比出谁压着活、谁今天没动。
   柱高按「完成 + 待处理」的部门最大值归一到 44px,实心是已交付、斜纹是还压着。
   有活就给 3px 保底 —— 4 件按 28 件归一之后只剩 6px,再被四舍五入抹平,
   那根柱子就等于没有,读者看到的是一格空白。 */
const LOAD_H = 44
const loadBars = computed(() => {
  const s = stats.value
  const rows = (org.value.departments || []).map(d => {
    let pending = 0, done = 0
    d.employees.forEach(e => {
      const st = s[e.id]
      if (st) { pending += st.pending || 0; done += st.doneToday || 0 }
    })
    return {
      id: d.id,
      name: d.name,
      short: (d.name || '').replace(/部$/, '') || '—',
      color: deptColor(d.colorKey),
      pending, done
    }
  })
  const max = Math.max(1, ...rows.map(r => r.done + r.pending))
  const px = n => (n > 0 ? Math.max(3, Math.round(LOAD_H * n / max)) : 0)
  rows.forEach(r => {
    r.donePx = px(r.done)
    r.pendPx = px(r.pending)
    r.hint = r.done + r.pending === 0
      ? `${r.name}:今天还没派活`
      : `${r.name}:今日完成 ${r.done} 件 · 待处理 ${r.pending} 件`
  })
  return rows
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

function open(e) { selected.value = e; panelOpen.value = true }
function openAdd(dept) { addPreset.value = dept ? dept.id : null; addOpen.value = true }

/* 组织架构是这一页的唯一真数据源:取不到就明确告知,不能留一张空白图纸 */
// 导出全岗位说明书。逐页点二十次看不出"职责有没有重叠、哪一环没人接",
// 合成一份横着看才看得出来 —— 而这是调整编制时最先要看的东西。
async function exportManual(format) {
  try {
    await api.exportManual(format)
    ElMessage({ message: `说明书已导出(${format.toUpperCase()})`, type: 'success', plain: true })
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '导出失败')
  }
}

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
  /* 画布现在是「由内容撑高」的:窗口变矮,它的高度不会跟着变,ResizeObserver
     就不会响。可 fit 算的是「视口里还剩多少」,所以必须直接听 resize。 */
  window.addEventListener('resize', fit)
  wrap.value && wrap.value.addEventListener('scroll', syncMore, { passive: true })
  /* 组织一到/一变就重算比例:数据是新的「图纸尺寸」,而比例是照图纸算的。
     这条原来靠 load() 末尾的一帧 rAF 兜着,可首帧可能比数据早 —— 于是比例
     留在按空组织算出来的 1.0 上,后面没人再喊一次。 */
  watch(org, () => nextTick(fit))
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
  window.removeEventListener('resize', fit)
  wrap.value && wrap.value.removeEventListener('scroll', syncMore)
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
/* 关掉动效的人不该看到一张空图纸:cardIn 的 fill-mode 是 both,
   动画不跑就停在 from 的 opacity:0 —— 岗位卡会整片消失。 */
@media (prefers-reduced-motion: reduce) {
  .fo-node { animation: none; }
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
.node-card.emp.rippling .st-hero b { animation: popScore .6s cubic-bezier(.34, 1.56, .64, 1) both; }

.org-chart {
  position: relative;
  /* 舞台高度由 fit() 写死成「视口减顶栏」,内部三段各自定位:
     顶栏(flex:none) / 画布(flex:1,自己滚) / 底部带(flex:none)。
     原来是 min-height + 内容撑高 + HUD absolute 浮在图上 —— 内容一超过视口,
     浮动那块就压住最后两排卡,而 fit() 还在拿视口反推比例,越算越乱。 */
  display: flex; flex-direction: column;
  min-height: 380px;
  overflow: hidden;
}
/* 顶栏也是一段,不是浮在画布上的两块玻璃。
   z-index:3 是为了压住载入/失败/空组织那几张 inset:0 的说明卡 ——
   它们盖画布是对的,盖住读数条和按钮就不对了。 */
.stage-bar {
  flex: none; position: relative; z-index: 3;
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px;
  /* 和底部带同一个版心:顶栏两块玻璃的左右边缘,正好落在底带三栏的起止线上 */
  padding: 6px max(20px, calc((100% - 1360px) / 2)) 0;
}
/* 画布:唯一允许滚动的容器。scrollbar-gutter 常驻留出滚动条的位置 ——
   否则「有没有竖滚动条」会让 clientWidth 跳 8px,比例跟着跳,图来回抽一下。
   min-width:0 是这套算法的命门:flex 项的自动最小宽度贴着内容走,
   图一旦按 1.0 铺出来,画布就撑到图的宽度,于是「可用宽度」= 图的宽度 = 比例 1.0,
   自洽了,也就再也缩不下去 —— 上一版 1400×664 停在 1.0、横向溢出 156px 就是这么来的。 */
.canvas-wrap {
  flex: 1 1 auto; min-width: 0; min-height: 0; overflow: auto; padding: 14px 14px 26px;
  position: relative; z-index: 1;
  scrollbar-gutter: stable;
  overscroll-behavior: contain;
}
.chart-svg { display: block; margin: 0 auto; }

/* 画布底沿:只在「下面还有内容」时出现。
   试过 84px 的长渐隐,结果是最后一排卡在雾里露着图标和名字 —— 比硬裁更糟:
   硬裁是「容器到头了」,半透明是「这里好像坏了」。
   现在留 30px:够让最后一行卡的边软下去,又不足以让卡片「在雾里露着名字」。 */
.canvas-fade {
  position: absolute; left: 0; right: 0; z-index: 2;
  bottom: var(--rail-h, 120px);
  height: 30px; pointer-events: none; opacity: 0;
  transition: opacity .3s var(--ease-quiet);
  background: linear-gradient(180deg, transparent, var(--bg-ink) 96%);
}
.canvas-fade.is-on { opacity: 1; }

/* ===== 虚空底:天光 / 墨山 / 云气 / 尘 / 暗角(铺满整块舞台)
   五层全部乘 --org-fx。它默认是 1(墨夜),宣纸与素白是 0 ——
   纸上画墨山云海,那是把另一幅画贴在这幅画上,画错了地方。 ===== */
.h-bg { position: absolute; inset: 0; z-index: 0; pointer-events: none; overflow: hidden; }
.h-bg > div { position: absolute; pointer-events: none; }
/* 天光:顶上那一片,说明光源在哪。没有它,山和云都是自己发亮的 */
.h-sky {
  inset: 0;
  opacity: var(--org-fx);
  background:
    radial-gradient(72% 46% at 50% -4%, rgba(var(--rgb-glow), .13), transparent 64%),
    radial-gradient(120% 70% at 50% 108%, rgba(var(--rgb-glow), .07), transparent 70%);
}

/* 天光:一个下宽上窄的光锥,顶在画面上沿,落在阵眼那一带。
   用 clip-path 切梯形而不是画矩形 —— 矩形的四条边会让人看出"这里放了个盒子"。 */
.h-beam {
  left: 50%; top: -2%; width: 54%; height: 46%;
  transform: translateX(-50%);
  background: linear-gradient(180deg,
    rgba(var(--rgb-glow), .115),
    rgba(var(--rgb-glow), .045) 46%,
    rgba(var(--rgb-glow), 0) 82%);
  clip-path: polygon(40% 0, 60% 0, 100% 100%, 0 100%);
  filter: blur(7px);
  opacity: var(--org-fx);
  animation: beamBreath 8.6s ease-in-out infinite alternate;
}
@keyframes beamBreath {
  from { opacity: calc(.62 * var(--org-fx)); transform: translateX(-50%) scaleX(.97); }
  to   { opacity: var(--org-fx); transform: translateX(-50%) scaleX(1.03); }
}

/* 墨山:三层。远山高、淡、糊;近山低、黑、实 —— 远近靠这三件事拉开,不靠透视变形。
   山体比底色更黑(压在云光上才看得见),山脊用 drop-shadow 沿裁剪边漏一道微光,
   于是"挑出来的那道背"是亮的,山是没入雾里的。 */
.h-mtn {
  position: absolute;
  left: -4%; width: 108%; bottom: 0;
  opacity: var(--org-fx);
  fill: #020409;
}
.h-mtn path { fill: inherit; }
.h-mtn.is-far {
  height: 40%;
  filter: blur(2.6px) drop-shadow(0 -1px 0 rgba(var(--rgb-glow), .2));
  opacity: calc(.7 * var(--org-fx));
}
.h-mtn.is-mid {
  height: 27%;
  filter: blur(1.2px) drop-shadow(0 -1px 0 rgba(var(--rgb-glow), .13));
  opacity: calc(.95 * var(--org-fx));
}
.h-mtn.is-near {
  height: 15%;
  filter: drop-shadow(0 -1px 0 rgba(var(--rgb-glow), .07));
  opacity: var(--org-fx);
}

/* 云气:两道极淡的光带横着流,一高一低、一快一慢。
   这是整页唯一的"大动",所以它必须慢到只能感觉到、看不出来。 */
.h-mist {
  left: -12%; right: -12%;
  filter: blur(22px);
  opacity: var(--org-fx);
  background:
    radial-gradient(48% 100% at 26% 50%, rgba(var(--rgb-glow), .10), transparent 72%),
    radial-gradient(40% 100% at 68% 58%, rgba(var(--rgb-glow), .08), transparent 74%);
}
.h-mist.is-low { bottom: 0; height: 54%; animation: mistDrift 46s ease-in-out infinite alternate; }
.h-mist.is-high {
  bottom: 38%; height: 36%;
  background:
    radial-gradient(42% 100% at 58% 50%, rgba(var(--rgb-glow), .095), transparent 74%),
    radial-gradient(34% 100% at 22% 46%, rgba(var(--rgb-glow), .07), transparent 76%);
  animation: mistDrift 33s ease-in-out infinite alternate-reverse;
}
@keyframes mistDrift {
  from { transform: translate3d(-3.5%, 0, 0); }
  to   { transform: translate3d(3.5%, -1.2%, 0); }
}

.h-stars {
  inset: 0; opacity: calc(var(--stardust) * var(--org-fx));
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
  from { opacity: calc(var(--stardust) * var(--org-fx) * .38); }
  to { opacity: calc(var(--stardust) * var(--org-fx)); }
}

.h-vig {
  inset: 0;
  background: radial-gradient(122% 104% at 50% 42%, transparent 38%, rgba(var(--rgb-shadow), calc(.62 * var(--org-fx))) 100%);
}

/* ===== 峰体:短列底下那几百像素不再是「图上缺一块」 =====
   底部现在有云漫上来接管空白,这道光就退到"只是给这一列压个底"的位置 ——
   再亮就成了柱子。墨夜给一档,纸与素白更薄:纸上淌一道淡彩会脏。 */
.lanes { opacity: calc(.24 + .3 * var(--org-fx)); }

/* ===== CEO 冠冕:一段刻度弧 + 缓慢巡行的高光 ===== */
.ceo-core { pointer-events: none; }
.core-halo {
  transform-box: fill-box; transform-origin: 50% 50%;
  animation: coreBreath 4.2s var(--ease-quiet) infinite;
}
@keyframes coreBreath {
  0%, 100% { opacity: .5; transform: scale(.97); }
  50% { opacity: .92; transform: scale(1.03); }
}
.crest-arc { stroke-width: 1; stroke-opacity: .22; }
/* 巡行高光:16px 长的一段亮弧沿冠冕走一圈(路径长约 296),像仪表上的扫描指针 */
.crest-sweep {
  stroke-width: 1.6; stroke-linecap: round;
  stroke-dasharray: 16 300;
  animation: crestSweep 7.5s linear infinite;
}
@keyframes crestSweep { from { stroke-dashoffset: 0; } to { stroke-dashoffset: -316; } }
.crest-tick { stroke-linecap: round; }

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

/* ===== 空组织:一张空图纸也得说得清下一步做什么 ===== */
.org-empty {
  position: absolute; inset: 0; z-index: 2;
  display: flex; align-items: center; justify-content: center;
  padding: 32px; pointer-events: none;
}
.empty-card {
  pointer-events: auto;
  display: flex; flex-direction: column; align-items: center; gap: 10px;
  padding: 40px 52px 36px; text-align: center; max-width: 520px; gap: 13px;
  animation: riseIn .5s var(--ease-out) both;
}
/* 三个空格:中间那个是虚线的 —— 图纸上还没画上去的那一格 */
.empty-mark { display: flex; align-items: center; gap: 9px; margin-bottom: 6px; }
.empty-mark i { display: block; width: 36px; height: 24px; border-radius: 6px; }
.empty-mark i:first-child, .empty-mark i:last-child {
  border: 1px solid var(--border-hair-strong); opacity: .45;
}
.empty-mark i:nth-child(2) {
  border: 1px dashed color-mix(in srgb, var(--glow-cyan) 50%, transparent);
  background: color-mix(in srgb, var(--glow-cyan) 7%, transparent);
}
.empty-title { margin: 0; font-size: 19px; letter-spacing: .3em; margin-left: .3em; color: var(--text-moon); font-weight: 300; }
.empty-text {
  margin: 0 0 8px; font-size: 12.5px; line-height: 2.1; letter-spacing: .06em;
  color: var(--text-dim);
}
.ld-ring {
  width: 16px; height: 16px; border-radius: 50%;
  border: 1px solid rgba(var(--rgb-glow), .28); border-top-color: var(--glow-cyan);
  animation: ldSpin 1.1s linear infinite;
}
@keyframes ldSpin { to { transform: rotate(360deg); } }
.ld-text { font-size: 11.5px; letter-spacing: .28em; color: var(--text-dim); }

/* 顶栏两块板跟上卡片的语言:不再切角。切角是「作战台」的记号,
   保留它等于在云海上面挂了两块星舰铭牌。 */
.chart-status, .chart-actions {
  border-radius: 8px;
}

/* 顶栏两块板:在流内,左右各贴一边,和图/底部带的左右边对齐(都是 14px) */
.chart-status {
  padding: 7px 16px;
  display: flex; align-items: center; gap: 14px;
}
.chart-actions {
  padding: 4px 10px 4px 8px;
  display: flex; align-items: center; gap: 2px;
}
.quiet-btn { color: var(--text-dim); letter-spacing: .15em; }
.quiet-btn:hover { color: var(--glow-cyan); }
.chart-actions :deep(.el-divider--vertical) { border-color: var(--border-hair); height: 14px; }

.duty-dot {
  width: 7px; height: 7px; border-radius: 50%; background: var(--glow-cyan);
  box-shadow: 0 0 8px rgba(var(--rgb-glow), .8); flex: none;
}
.hud-read { display: flex; align-items: baseline; gap: 5px; }
.hud-read b { font-family: var(--font-mono); font-size: 14px; font-weight: 600; color: var(--text-moon); }
.hud-read i { font-style: normal; font-size: 9.5px; letter-spacing: .2em; color: var(--text-faint); }
.clock { font-family: var(--font-mono); font-size: 13px; color: var(--glow-cyan); letter-spacing: .1em; }

/* ===== 底带:一条读数,不是三块面板 =====
   原来是「事件流(两行日志)· 部门负载(五根柱)· 岗位态势(四个环)」并排 ——
   三种视觉语言挤在 110px 里,窄屏上还要互相夺宽度。而且两块的信息本来就重复:
   部门卡上印着今日/待办,顶栏读数里写着在岗与执行中,换个画法再画一遍是噪音。
   现在只有一行、40px 高:左边一句「刚刚发生了什么」,右边一排「现在什么数」。
   矮下来的 70px 全部还给画布 —— 那是这一页真正缺的东西。 */
.hud-rail {
  flex: none; position: relative; z-index: 3;
  display: flex; align-items: center; gap: 26px;
  height: 40px;
  /* 和顶栏同一个版心:左边缘对齐读数条,右边缘对齐操作按钮 */
  padding: 0 max(20px, calc((100% - 1360px) / 2));
  border-top: 1px solid var(--border-hair);
  /* 舞台底那层透视网格铺到页面最下沿;浅色主题 --org-fx=0 不画网格,也就不需要底色 */
  background: linear-gradient(180deg,
    rgba(var(--rgb-surface-2), .82) 0, rgba(var(--rgb-surface-2), .95) 100%);
}
/* 浅色两套网格不画 —— 再叠一层只会得到一条比纸更亮的横条,像张白纸条贴在页脚 */
:root[data-theme="paper"] .hud-rail,
:root[data-theme="stitch"] .hud-rail { background: none; }

/* --- 左:一句话。单行 + 省略号,不给第二行 --- */
.hud-live {
  flex: 1 1 auto; min-width: 0;
  display: flex; align-items: center; gap: 9px;
}
.hud-live .hf-text {
  flex: 0 1 auto; min-width: 0;
  font-size: 11.5px; line-height: 1.2; letter-spacing: .01em; color: var(--text-dim);
  overflow: hidden; white-space: nowrap; text-overflow: ellipsis;
}
.hf-live {
  flex: none; width: 5px; height: 5px; border-radius: 50%; background: var(--st-running);
  box-shadow: 0 0 7px rgba(var(--rgb-glow), .8); animation: dotBlink 2s ease-in-out infinite;
}
.hf-live.quiet { background: var(--st-off); box-shadow: none; animation: none; }
.hl-cap { flex: none; font-size: 9.5px; letter-spacing: .3em; color: var(--text-faint); }
.hf-time { flex: none; font-family: var(--font-mono); font-size: 10px; color: var(--text-faint); }
.hf-tag {
  flex: none; max-width: 66px;
  font-size: 8.5px; letter-spacing: .06em; padding: 1px 5px; border-radius: 3px;
  border: 1px solid var(--border-hair); color: var(--text-dim); white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis;
}
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

/* --- 右:一排数 ---
   五个部门用「色点 + 简称 + 数」,后面四个是公司级读数。同一字号、同一基线,
   读起来是一行字,不是三个控件 —— 这正是上一版的问题:三段各有各的画法,
   并排放就是"拼装"。 */
.hud-nums { flex: none; display: flex; align-items: center; gap: 16px; }
.hn-group { display: flex; align-items: center; gap: 12px; }
.hn-group.is-load { gap: 11px; }
.hn-load {
  display: inline-flex; align-items: center; gap: 4px; white-space: nowrap;
  font-size: 10px; letter-spacing: .04em; color: var(--text-faint);
}
.hn-load i { flex: none; width: 5px; height: 5px; border-radius: 1px; }
.hn-load b { font: 600 11px/1 var(--font-mono); color: var(--text-moon); letter-spacing: 0; }
.hn-sep { flex: none; width: 1px; height: 13px; background: var(--border-hair); }
.hn-stat { display: inline-flex; align-items: baseline; gap: 4px; white-space: nowrap; }
.hn-stat b { font: 600 13px/1 var(--font-mono); letter-spacing: -.01em; }
.hn-stat i { font-style: normal; font-size: 9.5px; letter-spacing: .1em; color: var(--text-faint); }

/* ===== 落款:竖排进右侧留白,像画上的边款 ===== */
.chart-slogan {
  position: absolute; right: 24px; top: 46%; transform: translateY(-50%);
  z-index: 1; pointer-events: none; opacity: .7;
}
.chart-slogan span {
  writing-mode: vertical-rl; font-family: var(--font-serif);
  font-size: 11px; letter-spacing: .46em; color: var(--text-faint);
}

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

/* 卡片:原来这里是「切角 + 内斜面」的全息面板 —— 斜切角是星舰界面的语法,
   一眼就把它归到"科幻 HUD"那边,和云海墨山是两个世界。
   改成一枚方牌:小圆角、薄、透一点,让底下的云能隐约过过来。 */
.node-card.dept, .node-card.emp, .node-card.blank-cell {
  border-radius: 5px;
  box-shadow: inset 0 1px 0 rgba(var(--rgb-ink), .05),
              0 1px 2px rgba(var(--rgb-shadow), .5);
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
.node-card.ceo .node-name { font-size: 17px; letter-spacing: .28em; margin-left: .28em; }

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
.node-card.dept .node-name { color: var(--dept-color); font-size: 15px; letter-spacing: .24em; margin-left: .24em; }

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
  flex: none; font-size: 9px; letter-spacing: .04em; padding: 1px 6px; border-radius: 999px;
  color: color-mix(in srgb, var(--dept-color) 85%, white);
  background: color-mix(in srgb, var(--dept-color) 13%, transparent);
  border: 1px solid color-mix(in srgb, var(--dept-color) 25%, transparent);
  white-space: nowrap;
}

.emp-body { flex: 1; min-width: 0; }
.emp-head { display: flex; align-items: center; gap: 8px; }
/* 名号用宋:无衬线是"系统里的一个字段",宋体是"牌位上的一行字"。
   这一笔比任何背景特效都管用 —— 它换的是这页的"字的气质"。 */
.node-name {
  font-size: 15px; letter-spacing: .1em; white-space: nowrap;
  font-family: var(--font-serif); font-weight: 500;
}
.node-title {
  font-size: 12px; color: var(--text-dim); letter-spacing: .06em; margin-top: 3px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 158px;
}
.is-running .node-title { color: var(--glow-cyan); font-family: var(--font-mono); font-size: 11.5px; }

/* 岗位数据:今日(主) | 一道实分栏 | 累计 / 自定义指标 / 待
   数值色统一和 --text-moon 调:深底上提亮、纸上压深 —— 直接混白在浅色主题上会发虚。 */
.emp-stats {
  margin-top: 4px; font-size: 10px; color: var(--text-faint);
  font-family: var(--font-mono); letter-spacing: .03em;
  display: flex; align-items: center; gap: 0; white-space: nowrap;
}
.st-hero { display: inline-flex; align-items: baseline; gap: 3px; }
.st-hero b {
  font-size: 15.5px; font-weight: 600; line-height: 1;
  color: color-mix(in srgb, var(--dept-color) 78%, var(--text-moon));
  text-shadow: 0 0 12px color-mix(in srgb, var(--dept-color) 30%, transparent);
}
.st-hero i { font-style: normal; font-size: 9.5px; color: var(--text-faint); }
/* 两组之间用一道齐高的实线,而不是 9px 的小点线:分栏要能被看见,才叫分栏 */
.st-split {
  display: inline-block; width: 1px; height: 15px; background: var(--border-hair-strong);
  margin: 0 8px; flex: none;
}
.st-pair { display: inline-flex; align-items: baseline; gap: 2px; }
.st-pair + .st-pair { margin-left: 8px; }
.st-pair b { font-size: 12px; font-weight: 600; color: var(--text-moon); }
.st-pair.is-pending { color: var(--st-warn); }
.st-pair.is-pending b { color: var(--st-warn); }

.status-dot { width: 8px; height: 8px; border-radius: 50%; flex: none; margin-left: auto; position: relative; }
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

/* 剑气射出的那一道:pathLength 归一成 1000,所以 dash 的数值就是路径的千分比。
   一段 90(9%)长的光从阵眼走到峰头,走完散掉 —— 不是循环的高光,是一剑。
   时长和起跑时间由模板按每条路给,五条不同步。 */
.blade {
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-dasharray: 90 910;
  animation-name: bladeRun;
  animation-timing-function: cubic-bezier(.42, .02, .42, 1);
  animation-iteration-count: infinite;
}
.blade.is-hot { stroke-dasharray: 150 850; stroke-width: 2.2; }
@keyframes bladeRun {
  0%   { stroke-dashoffset: 0; opacity: 0; }
  9%   { opacity: .85; }
  72%  { opacity: .55; }
  100% { stroke-dashoffset: -1000; opacity: 0; }
}
/* 关掉动效:留一道停在半路的剑光。它说明这条路是活的,只是不出来跑。 */
@media (prefers-reduced-motion: reduce) {
  .blade { animation: none; stroke-dashoffset: -430; opacity: .6; }
}
.tips { pointer-events: none; opacity: var(--org-fx); }

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

/* 窄屏右侧留白不够,边款会压到最后一列的卡片上 —— 它不是信息,先收 */
@media (max-width: 1360px) {
  .chart-slogan { display: none; }
}
/* 窗口矮的时候这条带每高 1px 都是从图上抢 1px —— 它已经不剩什么可收的了,
   只把行高压到 34;再往下(笔记本半屏那种)就该整页滚了。 */
@media (max-height: 780px) {
  .hud-rail { height: 34px; }
}

/* 窄到一行放不下两组数时,先舍「负载」:那五个数在部门卡上已经印着,
   而这一行最该读的是左边那句话。 */
@media (max-width: 1120px) {
  .hud-rail { gap: 18px; padding: 0 16px; }
  .hud-nums { gap: 12px; }
  .hn-group { gap: 10px; }
  .hn-group.is-load { display: none; }
  .hud-nums .hn-sep { display: none; }
}

@media (max-width: 820px) {
  .hn-stat i { display: none; }
}
</style>
