<template>
  <el-drawer v-model="show" size="560px" :with-header="false">
    <div class="task-view" v-if="detail">
      <header>
        <div class="head-line">
          <span class="task-id">#{{ detail.task.id }}</span>
          <span class="task-emp">{{ detail.task.employee?.name }}</span>
          <span class="task-status" :class="'ts-' + detail.task.status.toLowerCase()">
            {{ statusMap[detail.task.status] }}
          </span>
        </div>
        <div class="inst">{{ detail.task.instruction }}</div>
        <div class="chain" v-if="detail.upstream && detail.upstream.length">
          <span class="chain-label">协作链</span>
          <button v-for="u in detail.upstream" :key="u.id" class="chain-chip"
                  @click="jump(u.id)" :title="u.instruction">
            #{{ u.id }} {{ u.employeeName }}
          </button>
        </div>
        <div class="meta" v-if="detail.task.startedAt">
          {{ fmt(detail.task.startedAt) }} · {{ detail.task.model || '默认模型' }}
        </div>
        <div v-if="isRunning" class="task-prog"><i></i></div>
      </header>

      <section class="logs" ref="logsEl">
        <div v-for="(log, i) in detail.logs" :key="i" class="log-item" :class="'role-' + log.role">
          <div class="log-role">{{ roleMap[log.role] || log.role }}</div>
          <div class="log-content">{{ log.content }}</div>
        </div>
        <div v-if="isRunning" class="log-item role-assistant is-live">
          <div class="log-role">正在书写<span class="caret"></span></div>
          <div class="log-content">{{ stream || '模型已开始思考,第一个字马上落下…' }}</div>
        </div>
      </section>

      <footer class="task-actions">
        <el-button v-if="canRedispatch" size="small" type="primary" plain @click="redispatch">重新派发</el-button>
        <el-button v-if="canCancel" size="small" type="danger" plain @click="cancel">
          {{ detail.task.status === 'RUNNING' ? '中止任务' : '取消任务' }}
        </el-button>
      </footer>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, computed, watch, nextTick, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { api, bus } from '../api'

const props = defineProps({ modelValue: Boolean, taskId: Number })
const emit = defineEmits(['update:modelValue'])

const show = computed({
  get: () => props.modelValue,
  set: v => emit('update:modelValue', v)
})

const detail = ref(null)
const statusMap = { PENDING: '待处理', RUNNING: '执行中', DONE: '完成', FAILED: '失败', CANCELLED: '已取消' }
const roleMap = { system: '岗位说明书', user: '指令', assistant: '回复', info: '备注', error: '错误' }

const canCancel = computed(() =>
  detail.value && ['PENDING', 'RUNNING'].includes(detail.value.task.status))
const canRedispatch = computed(() =>
  detail.value && ['FAILED', 'CANCELLED'].includes(detail.value.task.status)
  && detail.value.task.employee && !detail.value.task.employee.ceo)

const stream = ref('')
const logsEl = ref(null)
const isRunning = computed(() => detail.value?.task?.status === 'RUNNING')

function scrollLogs() {
  const el = logsEl.value
  if (el) el.scrollTop = el.scrollHeight
}

let off = null
let scrollTimer = null

function unwatchLive() {
  if (off) { off(); off = null }
}

watch(() => [props.modelValue, props.taskId], async ([open, id]) => {
  if (!open || !id) { unwatchLive(); return }
  stream.value = ''
  detail.value = await api.taskDetail(id)
  nextTick(scrollLogs)
  unwatchLive()
  off = bus.on(evt => {
    const cur = detail.value?.task?.id
    if (cur == null) return
    if (evt.type === 'task-delta' && String(evt.taskId) === String(cur)) {
      stream.value += evt.delta || ''
      if (!scrollTimer) scrollTimer = setTimeout(() => { scrollTimer = null; scrollLogs() }, 140)
    } else if (evt.type === 'task-update' && String(evt.task?.id) === String(cur)) {
      api.taskDetail(cur).then(d => {
        stream.value = ''
        detail.value = d
        nextTick(scrollLogs)
      }).catch(() => {})
    }
  })
})

onBeforeUnmount(() => {
  unwatchLive()
  clearTimeout(scrollTimer)
})

function fmt(iso) {
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

async function cancel() {
  await api.cancelTask(props.taskId)
  detail.value = await api.taskDetail(props.taskId)
  ElMessage({ message: '已取消', type: 'success', plain: true })
}

async function redispatch() {
  const t = detail.value.task
  const task = await api.submitTask({
    employeeId: t.employee.id,
    instruction: t.instruction,
    contextTaskIds: t.sourceTaskIds ? t.sourceTaskIds.split(',').map(Number).filter(Boolean) : null
  })
  ElMessage({ message: `已重新派发为 #${task.id}`, type: 'success', plain: true })
  show.value = false
}

async function jump(id) {
  stream.value = ''
  detail.value = await api.taskDetail(id)
  nextTick(scrollLogs)
}
</script>

<style scoped>
.task-view { display: flex; flex-direction: column; gap: 18px; height: 100%; }
.head-line { display: flex; align-items: center; gap: 14px; }
.task-id { font-family: var(--font-mono); color: var(--text-faint); font-size: 12px; }
.task-emp { font-size: 15px; letter-spacing: .15em; }
.task-status { font-size: 11px; letter-spacing: .15em; }
.ts-done { color: var(--dept-ops); }
.ts-failed { color: var(--st-error); }
.ts-cancelled, .ts-pending { color: var(--text-dim); }
.inst { margin-top: 10px; font-size: 14px; line-height: 1.8; padding: 12px 14px;
        border-radius: 10px; background: var(--tint-soft); border: 1px solid var(--border-hair); }
.chain { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.chain-label { font-size: 10px; letter-spacing: .25em; color: var(--text-faint); }
.chain-chip {
  font-family: var(--font-mono); font-size: 11px; cursor: pointer;
  background: var(--glow-cyan-faint); color: var(--glow-cyan);
  border: 1px solid rgba(var(--rgb-glow), .25); border-radius: 999px;
  padding: 2px 10px; transition: all .3s var(--ease-quiet);
}
.chain-chip:hover { border-color: rgba(var(--rgb-glow), .55); transform: translateY(-1px); }
.meta { margin-top: 8px; font-size: 11px; color: var(--text-faint); font-family: var(--font-mono); }
.logs { flex: 1; overflow: auto; display: flex; flex-direction: column; gap: 14px; padding-right: 4px; }
.log-item { padding: 12px 14px 12px 16px; border-radius: 10px; border: 1px solid var(--border-hair); position: relative; }
.role-user { background: var(--tint-soft); }
.role-assistant { border-color: rgba(var(--rgb-glow), .22); background: rgba(var(--rgb-glow), .025); }
.role-assistant::before {
  content: ""; position: absolute; left: 0; top: 14%; bottom: 14%; width: 2px; border-radius: 2px;
  background: linear-gradient(180deg, transparent, var(--glow-cyan), transparent); opacity: .65;
}
.role-error { border-color: rgba(var(--rgb-error), .3); background: rgba(var(--rgb-error), .03); }
.log-role { font-size: 10px; letter-spacing: .25em; color: var(--text-faint); margin-bottom: 8px; }
.role-assistant .log-role { color: var(--glow-cyan-dim); }
.log-content { font-size: 13px; line-height: 1.9; white-space: pre-wrap; color: var(--text-moon); }
footer.task-actions { text-align: right; display: flex; justify-content: flex-end; gap: 10px; }

/* 执行中:任务头部一条走不完的施工线,正文边出边滚 */
.task-prog { margin-top: 12px; }
.log-item.is-live { border-color: rgba(var(--rgb-glow), .3); background: rgba(var(--rgb-glow), .03); }
.log-item.is-live .log-content { color: rgba(var(--rgb-ink), .8); }
.caret {
  display: inline-block; width: 5px; height: 13px; margin-left: 7px; vertical-align: -2px;
  background: var(--glow-cyan); box-shadow: 0 0 8px rgba(var(--rgb-glow), .7);
  animation: caretBlink 1.05s steps(2, start) infinite;
}
@keyframes caretBlink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }
</style>
