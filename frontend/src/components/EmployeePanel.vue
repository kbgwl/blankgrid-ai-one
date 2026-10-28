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
