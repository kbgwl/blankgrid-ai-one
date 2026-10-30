<template>
  <el-drawer v-model="show" size="480px" :with-header="false" :modal-class="'panel-modal'">
    <div class="panel" v-if="emp">
      <header class="panel-head">
        <div class="head-line">
          <h2 class="emp-name">{{ emp.name }}</h2>
          <span class="status-tag" :class="dotClass">{{ statusText }}</span>
          <el-button v-if="enabled" size="small" type="danger" plain class="halt-btn"
                     @click="toggleDuty">叫停</el-button>
          <el-button v-else size="small" type="primary" plain class="halt-btn"
                     @click="toggleDuty">复岗</el-button>
        </div>
        <p class="emp-title">{{ emp.title || '—' }}</p>
        <p class="emp-hierarchy">
          <span class="hier-level" :class="'hl-' + (emp.level || 'STAFF').toLowerCase()">{{ levelText }}</span>
          <span class="hier-dept">{{ emp.department?.name || '未分配部门' }}</span>
          <span class="hier-up">{{ managerName }}</span>
        </p>
        <p class="halt-hint" v-if="enabled">这个岗位出问题了?叫停后不再接任何任务,CEO 也会跳过它</p>
      </header>

      <!-- 派发任务 -->
      <section class="dispatch glass">
        <el-input v-model="instruction" type="textarea" :rows="3" resize="none"
                  placeholder="派一个任务给这位 AI……" />
        <!-- 常见活不必每次现想措辞:点一下填进输入框,再改细节 -->
        <div class="quick-row" v-if="quickTasks.length">
          <span class="quick-label">常派</span>
          <button v-for="q in quickTasks" :key="q" class="quick-chip" :title="q"
                  @click="instruction = q">{{ q.length > 16 ? q.slice(0, 16) + '…' : q }}</button>
        </div>
        <div class="dispatch-row">
          <el-select v-model="contextTaskId" placeholder="附上游产出(可选)" clearable size="small"
                     class="ctx-select" v-if="doneTasks.length">
            <el-option v-for="t in doneTasks" :key="t.id"
                       :label="`#${t.id} ${t.employee?.name || ''} · ${(t.instruction || '').slice(0, 14)}`"
                       :value="t.id" />
          </el-select>
          <el-button size="small" class="send-btn" @click="submit" :loading="sending">派发</el-button>
        </div>
      </section>

      <!-- 任务列表 -->
      <transition-group tag="div" name="mv" class="tasks">
        <div v-if="loadError" key="__fail" class="load-fail glass">
          <p>{{ loadError }}</p>
          <el-button size="small" plain @click="loadTasks">重试</el-button>
        </div>
        <div v-for="n in skeletonCount" :key="'sk' + n" class="task-item is-skeleton">
          <div class="sk sk-chip"></div>
          <div class="sk sk-line w-70"></div>
          <div class="sk sk-line"></div>
        </div>
        <div v-if="!tasks.length && !loading && !loadError" key="__empty" class="empty">此处尚是空白</div>
        <div v-for="t in tasks" :key="t.id" class="task-item glass"
             :class="{ 'm-land': flashIds.has(t.id), 'm-sweep': t.status === 'RUNNING' }"
             @click="openTask(t)">
          <div class="task-line">
            <span class="task-id">#{{ t.id }}</span>
            <span class="task-status" :class="'ts-' + t.status.toLowerCase()">{{ statusMap[t.status] || t.status }}</span>
          </div>
          <div class="task-prog" v-if="t.status === 'RUNNING'"><i></i></div>
          <div class="task-inst">{{ t.instruction }}</div>
          <div class="task-live" v-if="t.id === streamingId">
            <template v-if="streamText">{{ streamText }}</template>
            <span v-else class="live-wait">正在落笔,等第一个字……</span><span class="caret"></span>
          </div>
          <div class="task-result" v-else-if="t.status === 'DONE'">{{ brief(t.result) }}</div>
          <div class="task-err" v-else-if="t.status === 'FAILED'">{{ t.error }}</div>
        </div>
      </transition-group>

      <!-- 岗位设置 -->
      <el-collapse class="emp-settings">
        <el-collapse-item title="层级与汇报关系" name="hierarchy">
          <div class="hier-grid">
            <div class="hier-field">
              <label>层级</label>
              <el-select v-model="emp.level" size="small" style="width: 100%">
                <el-option label="部门负责人 · 拆活与验收" value="LEAD" />
                <el-option label="执行岗位 · 负责具体交付" value="STAFF" />
              </el-select>
            </div>
            <div class="hier-field">
              <label>所属部门</label>
              <el-select v-model="emp.departmentId" size="small" style="width: 100%" placeholder="未分配">
                <el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" />
              </el-select>
            </div>
          </div>
          <div class="hier-field" style="margin-top: 10px">
            <label>直属上级</label>
            <el-select v-model="emp.managerId" size="small" clearable style="width: 100%"
                       placeholder="选择上级,决定汇报链">
              <el-option v-for="m in managerOptions" :key="m.id"
                         :label="m.name + ' · ' + levelOf(m)" :value="m.id" />
            </el-select>
          </div>
          <div class="hier-field" style="margin-top: 10px">
            <label>岗位验收标准 · 该岗位交付物必须达到</label>
            <el-input v-model="emp.standards" type="textarea" :rows="4" resize="none"
                      placeholder="例如:所有结论必须给出来源与计算过程;方案必须包含排期、负责人、验收指标。" />
          </div>
          <div class="setting-row">
            <el-button size="small" @click="saveEmp" :loading="saving">保存层级与标准</el-button>
          </div>
        </el-collapse-item>
        <el-collapse-item title="岗位说明书" name="prompt">
          <el-input v-model="emp.systemPrompt" type="textarea" :rows="6" resize="none" />
          <div class="setting-row metric-row">
            <el-input v-model="emp.metricLabel" placeholder="核心指标名称,如:本周产出(架构卡展示近 7 天完成数)" size="small" />
          </div>
          <div class="setting-row">
            <el-input v-model="emp.model" placeholder="指定模型(留空用默认)" size="small" />
            <el-button size="small" @click="saveEmp" :loading="saving">保存</el-button>
            <el-button size="small" type="danger" plain @click="removeEmp">移除</el-button>
          </div>
        </el-collapse-item>
        <el-collapse-item title="成长档案 · 复盘沉淀" name="learnings">
          <p class="learn-hint" v-if="!emp.learnings">还没有教训。这位 AI 每次失败复盘后,结论会自动记在这里,并注入它后续的每一次执行。</p>
          <el-input v-else v-model="emp.learnings" type="textarea" :rows="5" resize="none" />
          <div class="setting-row" v-if="emp.learnings">
            <span class="learn-note">可手工修订;最新的在最上面,注入时最近优先</span>
            <el-button size="small" @click="saveEmp" :loading="saving">保存</el-button>
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>

    <TaskDetail v-model="taskOpen" :task-id="openedTaskId" />
  </el-drawer>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, bus } from '../api'
import { motionOk } from '../utils/motion'
import TaskDetail from './TaskDetail.vue'

const props = defineProps({
  modelValue: Boolean,
  employee: Object
})
const emit = defineEmits(['update:modelValue', 'refresh'])

const show = computed({
  get: () => props.modelValue,
  set: v => emit('update:modelValue', v)
})

const emp = ref(null)
const tasks = ref([])
const departments = ref([])
const allEmployees = ref([])
const instruction = ref('')
const contextTaskId = ref(null)
const sending = ref(false)
const saving = ref(false)
const taskOpen = ref(false)
const openedTaskId = ref(null)
const streamingId = ref(null)
const streamText = ref('')

const statusMap = {
  PENDING: '待处理', RUNNING: '执行中', DONE: '完成',
  FAILED: '失败', CANCELLED: '已取消'
}

const doneTasks = computed(() => tasks.value.filter(t => t.status === 'DONE'))

/**
 * 常派的活。
 *
 * <p>创始人一天要派十几次活,每次现想"怎么说清楚"是最没价值的时间。
 * 这里按岗位给三条最典型的任务,点一下填进输入框再改细节 ——
 * 注意只填措辞,不替它编数据:具体项目名、客户名仍然要人来补。
 */
const ROLE_TASKS = [
  { match: /产品/, tasks: [
    '写一份 XX 功能的 PRD:角色、主流程与异常分支、字段与状态、验收标准,能直接进开发',
    '做三家同类产品的竞品拆解:核心流程、定价、目标客群、我们能切的差异点',
    '把这次需求评审的结论整理成需求池,按优先级与工作量排序,标出依赖项'
  ] },
  { match: /设计|UI|UX|视觉/, tasks: [
    '为 XX 页面出设计说明:布局、间距、字号层级、配色、交互状态与空态/错误态',
    '按现有品牌资产整理一份组件规范:按钮、表单、卡片、表格的状态与尺寸',
    '做一次可用性走查:列出三个最影响完成任务的设计问题,每条给修改方案'
  ] },
  { match: /前端/, tasks: [
    '实现 XX 页面:给出完整可运行代码、组件拆分说明与边界情况处理',
    '排查这个页面的性能问题:定位瓶颈、给优化方案与预期收益',
    '写一份前端工程规范:目录结构、状态管理、请求封装、错误处理与提交约定'
  ] },
  { match: /后端|Java|开发工程师/, tasks: [
    '设计 XX 模块的接口与数据结构:请求响应示例、错误码、幂等与并发处理',
    '排查这个线上问题:可能原因排序、每条的验证命令、修复与回滚方案',
    '写一份数据库变更方案:DDL、索引、兼容旧数据、灰度与回滚步骤'
  ] },
  { match: /测试|QA/, tasks: [
    '为 XX 功能写测试用例:正常流、边界、异常、权限,标出优先级',
    '整理一份上线前回归清单,按模块分组,每项写清预期结果与验证方式',
    '复盘最近三个缺陷:根因分类、哪一环能提前拦住、补哪条用例'
  ] },
  { match: /运维|DevOps|SRE/, tasks: [
    '写一份上线部署手册:配置全文、部署脚本、验证命令、回滚步骤',
    '做一次容量评估:当前资源、瓶颈点、扩容阈值与成本估算',
    '写一份故障复盘:时间线、根因、影响面、改进项与责任人'
  ] },
  { match: /数据/, tasks: [
    '给这个业务设计指标口径:定义、计算方式、数据来源、谁负责维护',
    '分析这份数据:关键结论三条、支撑证据、下一步建议',
    '做一个周报模板:核心指标、环比、异常项与原因、下周动作'
  ] },
  { match: /内容|运营/, tasks: [
    '写三版推广文案:分别面向决策者、使用者、同行,每版给标题与正文',
    '做一个月的选题日历:主题、目标读者、发布渠道、转化目标',
    '复盘上一篇内容的数据:哪里有效、哪里流失、下一篇怎么改'
  ] },
  { match: /市场|策划|品牌/, tasks: [
    '做一次市场机会分析:规模、竞品、渠道、切入点与验证方式',
    '写一份活动方案:目标、玩法、物料清单、预算、排期与效果衡量',
    '整理品牌口径:一句话定位、三个支撑点、五个不能说错的表述'
  ] },
  { match: /销售/, tasks: [
    '为这个客户做分档报价:服务范围、交付周期、付款节点,以及明确不包含什么',
    '写一份投标技术方案,按评分办法逐条响应,不确定的资质标待确认',
    '整理客户异议清单:常见五个反对意见,每个给应对话术与证据'
  ] },
  { match: /客户成功|客服/, tasks: [
    '把这个客户投诉整理成工单:现象、影响、临时绕行方案、根因与整改',
    '做一份客户健康度评估:使用情况、工单趋势、续约风险与动作',
    '写一份交付后 30 天跟进计划:节点、检查项、对接人'
  ] },
  { match: /项目/, tasks: [
    '把这次交付拆成里程碑:每个 milestone 的产出、验收人、时间与风险',
    '做一次项目风险盘点:风险、概率、影响、应对与触发信号',
    '写一份周会纪要模板:进展、卡点、需要谁做什么、下周目标'
  ] },
  { match: /人事|HR|招聘/, tasks: [
    '为 XX 岗位写 JD:职责、硬性要求、加分项、薪资范围、汇报关系',
    '设计这个岗位的面试评估表:可打分的维度、评分标准、追问方向',
    '写一份前 30 天入职计划:周目标、带教人、检查点'
  ] },
  { match: /行政/, tasks: [
    '做一份办公用品采购比价:规格、单价、发票类型、到货周期',
    '写一份访客接待与视察准备清单:动线、点位、责任人、时间',
    '整理固定资产台账模板:编号、使用人、位置、状态、盘点周期'
  ] },
  { match: /财务|会计/, tasks: [
    '整理这季度的费用结构:主要科目、占比、异常项与建议',
    '写一份项目成本核算口径:人力、外包、云资源如何归集与分摊',
    '做一份发票与报销检查清单,按常见退回原因排序'
  ] },
  { match: /法务|合规/, tasks: [
    '审这份合同:风险条款逐条列出、建议修改措辞、可让与不可让的底线',
    '写一份数据处理与隐私说明,覆盖收集范围、用途、留存与第三方共享',
    '整理这份协议里的责任边界:谁承担什么、违约怎么算、争议怎么解'
  ] }
]

const DEFAULT_TASKS = [
  '把你这个岗位最常做的三件事写成一份作业规范,新人照着就能干',
  '复盘最近一次交付,列出三个最该改的问题和具体改法',
  '做一份本岗位的检查清单,每项可直接勾选'
]

function quickFor(e) {
  if (!e) return []
  for (const r of ROLE_TASKS) {
    if (r.match.test(e.name || '')) return r.tasks
  }
  return DEFAULT_TASKS
}

const quickTasks = computed(() => quickFor(emp.value))

const levelText = computed(() => ({
  CEO: 'CEO · 统观全局', LEAD: '部门负责人', STAFF: '执行岗位'
}[emp.value?.level] || '执行岗位'))
const managerName = computed(() => {
  if (emp.value?.level === 'CEO' || emp.value?.ceo) return '直接向创始人汇报'
  const m = allEmployees.value.find(x => x.id === emp.value?.managerId)
  return m ? '汇报给 ' + m.name : '未设置上级'
})
const managerOptions = computed(() =>
  allEmployees.value.filter(e => e.id !== emp.value?.id))
const statusText = computed(() => ({
  IDLE: '空闲', RUNNING: '执行中', ERROR: '出错', DISABLED: '停用'
}[emp.value?.status] || '空闲'))
const dotClass = computed(() => ({
  RUNNING: 'st-running', ERROR: 'st-error', DISABLED: 'st-off'
}[emp.value?.status] || 'st-idle'))
const enabled = computed({
  get: () => emp.value?.status !== 'DISABLED',
  set: () => {}
})

async function toggleDuty() {
  const disabled = !enabled.value
  try {
    await api.updateEmployee(emp.value.id, { disabled })
    emp.value.status = disabled ? 'DISABLED' : 'IDLE'
    ElMessage({ message: disabled ? '已停用' : '已复岗', type: 'success', plain: true })
    emit('refresh')
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

watch(() => [props.modelValue, props.employee?.id], async ([open, id]) => {
  if (open && id) {
    emp.value = props.employee
    panelFirst = true
    tasks.value = []
    loadError.value = ''
    await Promise.all([loadTasks(), loadOrg()])
  }
})

async function loadOrg() {
  try {
    const org = await api.org()
    departments.value = org.departments || []
    allEmployees.value = [org.ceo, ...departments.value.flatMap(d => d.employees)].filter(Boolean)
  } catch (e) { /* 忽略 */ }
}

function levelOf(e) {
  return { CEO: 'CEO', LEAD: '负责人', STAFF: '岗位' }[e.level] || '岗位'
}

let taskSeq = 0
async function loadTasks() {
  if (!emp.value) return
  const seq = ++taskSeq
  loadError.value = ''
  const firstPaint = !tasks.value.length
  if (firstPaint) loading.value = true
  try {
    const list = await api.tasks(emp.value.id)
    if (seq !== taskSeq) return
    tasks.value = list
    diffTasks(list)
    if (streamingId.value == null) {
      const live = list.find(t => t.status === 'RUNNING')
      if (live) { streamingId.value = live.id; streamText.value = '' }
    }
  } catch (e) {
    if (seq !== taskSeq) return
    if (tasks.value.length) return
    tasks.value = []
    loadError.value = '任务列表载入失败,后端可能没有响应'
  } finally {
    if (seq === taskSeq && firstPaint) loading.value = false
  }
}

/* 施工动效:只有状态变了的行才落地,整列不跟着 WS 刷新一起闪 */
const flashIds = ref(new Set())
const loading = ref(false)
const loadError = ref('')
const skeletonCount = computed(() => (loading.value && !tasks.value.length && !loadError.value) ? 3 : 0)
let prevTaskStatus = new Map()
let panelFirst = true
let flashTimer = null

function diffTasks(list) {
  const next = new Map(list.map(t => [t.id, t.status]))
  if (panelFirst || !motionOk()) {
    prevTaskStatus = next
    panelFirst = false
    return
  }
  const hot = new Set()
  for (const [id, st] of next) {
    const before = prevTaskStatus.get(id)
    if (before === undefined || before !== st) hot.add(id)
  }
  prevTaskStatus = next
  if (hot.size) {
    flashIds.value = hot
    clearTimeout(flashTimer)
    flashTimer = setTimeout(() => { flashIds.value = new Set() }, 1300)
  }
}

async function submit() {
  if (!instruction.value.trim()) return
  sending.value = true
  try {
    const ctx = contextTaskId.value ? [contextTaskId.value] : null
    await api.submitTask({ employeeId: emp.value.id, instruction: instruction.value, contextTaskIds: ctx })
    instruction.value = ''
    contextTaskId.value = null
    ElMessage({ message: '已派发', type: 'success', plain: true })
    await loadTasks()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message)
  } finally { sending.value = false }
}

function openTask(t) {
  if (t.status === 'RUNNING') return // 执行中在面板内流式观看
  openedTaskId.value = t.id
  taskOpen.value = true
}

async function saveEmp() {
  saving.value = true
  try {
    await api.updateEmployee(emp.value.id, {
      name: emp.value.name, title: emp.value.title,
      systemPrompt: emp.value.systemPrompt, model: emp.value.model || '',
      metricLabel: emp.value.metricLabel || '',
      learnings: emp.value.learnings || '',
      level: emp.value.level || 'STAFF',
      departmentId: emp.value.departmentId || (emp.value.department?.id ?? 0),
      managerId: emp.value.managerId || 0,
      standards: emp.value.standards || ''
    })
    ElMessage({ message: '已保存', type: 'success', plain: true })
    emit('refresh')
  } catch (e) { ElMessage.error('保存失败') } finally { saving.value = false }
}

async function removeEmp() {
  try {
    await ElMessageBox.confirm(`移除「${emp.value.name}」?`, '移除岗位', {
      confirmButtonText: '移除', cancelButtonText: '取消', type: 'warning'
    })
  } catch { return }
  try {
    await api.deleteEmployee(emp.value.id)
  } catch (e) {
    ElMessage.error('移除失败')
    return
  }
  show.value = false
  emit('refresh')
}

let off = null
import { onMounted } from 'vue'
onMounted(() => {
  off = bus.on(evt => {
    if (evt.type === 'task-delta' && evt.taskId === streamingId.value) {
      streamText.value += evt.delta
    } else if (evt.type === 'task-update') {
      const t = evt.task
      if (!t || t.employee?.id !== emp.value?.id) return
      if (t.status === 'RUNNING') { streamingId.value = t.id; streamText.value = '' }
      if (t.status === 'DONE' || t.status === 'FAILED') {
        if (t.id === streamingId.value) { streamingId.value = null; streamText.value = '' }
        emp.value.status = t.status === 'FAILED' ? 'ERROR' : 'IDLE'
        emp.value.currentTaskSummary = ''
      }
      loadTasks()
    }
  })
})
onBeforeUnmount(() => {
  off && off()
  clearTimeout(flashTimer)
})

function brief(s) {
  if (!s) return ''
  return s.length > 120 ? s.slice(0, 120) + '……' : s
}
</script>

<style scoped>
.panel { display: flex; flex-direction: column; gap: 16px; height: 100%; padding: 8px 4px; }
.panel-head {
  padding: 14px 16px; border-radius: 12px; position: relative; overflow: hidden;
  background: linear-gradient(155deg, rgba(var(--rgb-panel-hi), .9), rgba(var(--rgb-surface-2), .9));
  border: 1px solid var(--border-hair);
}
.panel-head::before {
  content: ""; position: absolute; left: 0; top: 12%; bottom: 12%; width: 2px; border-radius: 2px;
  background: linear-gradient(180deg, transparent, var(--glow-cyan), transparent); opacity: .7;
}
.panel-head .head-line { display: flex; align-items: center; gap: 14px; }
.emp-name { margin: 0; font-size: 20px; font-weight: 300; letter-spacing: .2em; }
.emp-title { margin: 6px 0 0; font-size: 12px; color: var(--text-dim); letter-spacing: .15em; }
.status-tag { font-size: 11px; padding: 2px 10px; border-radius: 999px; letter-spacing: .1em; }
.st-running { background: var(--glow-cyan-faint); color: var(--glow-cyan); box-shadow: 0 0 10px rgba(var(--rgb-glow), .25); }
.halt-btn { margin-left: auto; }
.halt-hint { margin: 6px 0 0; font-size: 11px; color: var(--text-faint); letter-spacing: .05em; }
.emp-hierarchy { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; margin: 8px 0 0; font-size: 10.5px; color: var(--text-faint); letter-spacing: .05em; }
.hier-level { padding: 2px 8px; border-radius: 999px; font-size: 9.5px; letter-spacing: .08em; }
.hl-lead { color: var(--glow-cyan); background: var(--glow-cyan-faint); border: 1px solid rgba(var(--rgb-glow), .22); }
.hl-staff { color: var(--text-dim); background: var(--chip-bg); }
.hl-ceo { color: var(--st-plan); background: rgba(var(--rgb-plan), .1); }
.hier-dept::before, .hier-up::before { content: "·"; margin-right: 9px; opacity: .5; }
.hier-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.hier-field { display: flex; flex-direction: column; gap: 6px; }
.hier-field label { font-size: 10.5px; letter-spacing: .18em; color: var(--text-faint); }
.learn-hint { font-size: 12px; color: var(--text-faint); margin: 0 0 10px; line-height: 1.7; }
.learn-note { font-size: 11px; color: var(--text-faint); margin-right: auto; align-self: center; }
.st-idle { background: rgba(var(--rgb-ink), .06); color: var(--text-dim); }
.st-running { background: var(--glow-cyan-faint); color: var(--glow-cyan); }
.st-error { background: rgba(var(--rgb-error), .12); color: var(--st-error); }
.st-off { background: rgba(var(--rgb-ink), .04); color: var(--text-faint); }

.dispatch { padding: 12px; display: flex; flex-direction: column; gap: 10px; }
.dispatch-row { display: flex; gap: 10px; align-items: center; }
.quick-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.quick-label { font-size: 10px; letter-spacing: .2em; color: var(--text-faint); margin-right: 2px; }
.quick-chip {
  font-family: var(--font-sans); font-size: 11px; color: var(--text-dim); cursor: pointer;
  background: var(--tint-soft); border: 1px solid var(--border-hair); border-radius: 999px;
  padding: 3px 10px; transition: color .25s var(--ease-quiet), border-color .25s var(--ease-quiet);
}
.quick-chip:hover { color: var(--glow-cyan); border-color: rgba(var(--rgb-glow), .35); }
.ctx-select { flex: 1; }
.send-btn { letter-spacing: .2em; }

.tasks { flex: 1; overflow: auto; display: flex; flex-direction: column; gap: 10px; padding-right: 4px; }
.empty { color: var(--text-faint); text-align: center; padding: 40px 0; letter-spacing: .3em; font-size: 13px; }
.task-item { padding: 12px 14px; cursor: pointer; transition: border-color .3s var(--ease-quiet); }
.task-item:hover { border-color: rgba(var(--rgb-ink), .2); }
/* 正在执行的这一行:一道光扫过,和看板/施工台同一套语言,只是压得更轻 */
.task-item.m-sweep { border-color: rgba(var(--rgb-glow), .28); }
.task-item.m-sweep::after { opacity: .15; width: 26%; }
.task-prog { margin-top: 8px; }
.task-item.is-skeleton { cursor: default; display: flex; flex-direction: column; gap: 9px; }
.task-item.is-skeleton .sk-line { height: 12px; }
.load-fail { padding: 16px; display: flex; flex-direction: column; align-items: flex-start; gap: 10px; }
.load-fail p { margin: 0; font-size: 12.5px; color: var(--text-dim); line-height: 1.7; }
.task-line { display: flex; justify-content: space-between; align-items: center; }
.task-id { font-family: var(--font-mono); font-size: 11px; color: var(--text-faint); }
.task-status { font-size: 11px; letter-spacing: .15em; }
.ts-pending { color: var(--text-dim); }
.ts-running { color: var(--glow-cyan); }
.ts-done { color: var(--dept-ops); }
.ts-failed { color: var(--st-error); }
.ts-cancelled { color: var(--text-faint); }
.task-inst { font-size: 13px; margin-top: 6px; color: var(--text-moon); line-height: 1.6; }
.task-result { font-size: 12px; margin-top: 6px; color: var(--text-dim); line-height: 1.7; }
.task-err { font-size: 12px; margin-top: 6px; color: var(--st-error); }
.task-live {
  margin-top: 8px; font-size: 12px; color: var(--glow-cyan); line-height: 1.7;
  white-space: pre-wrap; max-height: 160px; overflow: auto; font-family: var(--font-mono);
}
.caret { display: inline-block; width: 7px; height: 14px; background: var(--glow-cyan);
         vertical-align: -2px; margin-left: 2px; animation: caretBlink 1.1s steps(1) infinite; }
.live-wait { color: var(--text-faint); }
@keyframes caretBlink { 50% { opacity: 0; } }

.emp-settings :deep(.el-collapse) { border: none; background: transparent; }
.emp-settings :deep(.el-collapse-item__header) {
  background: transparent; color: var(--text-dim); border-color: var(--border-hair);
  letter-spacing: .2em; font-size: 13px;
}
.emp-settings :deep(.el-collapse-item__wrap) { background: transparent; border-color: var(--border-hair); }
.setting-row { display: flex; gap: 8px; margin-top: 10px; }
</style>
