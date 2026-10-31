<template>
  <div class="page">
    <div v-if="loading" class="empty">读取中…</div>
    <div v-else-if="!m" class="empty">
      <p>{{ loadError || '这场会议不存在' }}</p>
      <button class="ghost" @click="$router.push('/meetings')">回到会议列表</button>
    </div>

    <template v-else>
      <header class="head">
        <button class="back" @click="$router.push('/meetings')">← 会议</button>
        <div class="title-block">
          <h1>{{ m.title }}</h1>
          <p class="meta">
            <span class="pill" :class="statusClass">{{ statusLabel }}</span>
            <span class="mono">{{ clockText }}</span>
            <span v-if="m.location">· {{ m.location }}</span>
            <span v-if="m.participants">· {{ m.participants }}</span>
            <span v-if="m.source">· {{ sourceLabel }}</span>
          </p>
        </div>
        <div class="actions">
          <button v-if="m.status === 'DRAFT'" class="primary" @click="start">开始会议</button>
          <template v-else-if="m.status === 'LIVE'">
            <button class="ghost" @click="pause">暂停</button>
            <button class="danger" @click="end">结束</button>
          </template>
          <template v-else-if="m.status === 'PAUSED'">
            <button class="primary" @click="resume">继续</button>
            <button class="danger" @click="end">结束</button>
          </template>
          <template v-else>
            <button class="ghost" @click="genSummary(true)" :disabled="!!busy">重新生成纪要</button>
          </template>
          <button class="ghost" @click="remove" :disabled="!!busy">删除</button>
        </div>
      </header>

      <div class="grid">
        <!-- ============ 左:转写流 ============ -->
        <section class="panel stream">
          <div class="panel-head">
            <span class="ptitle">实时转写</span>
            <span class="count">{{ m.segments.length }} 条</span>
            <span class="spacer"></span>
            <button class="ghost" :class="{ live: listening }" @click="toggleMic">
              <i v-if="listening" class="mic-dot"></i>{{ listening ? '停止识别' : '语音识别' }}
            </button>
            <button class="ghost" @click="openImport">导入转写稿</button>
          </div>

          <div ref="feedEl" class="feed">
            <div v-if="!m.segments.length" class="feed-empty">
              <p>还没有记录。</p>
              <p class="hint">
                点「语音识别」开始说,或者在下面直接打字 —— 第一句话会自动开始计时。
                也可以把之前的转写稿整段导进来。
              </p>
            </div>
            <article v-for="s in m.segments" :key="s.id" class="seg" :class="{ sys: s.kind === 'SYSTEM', marked: s.marked }">
              <span class="stamp">{{ s.stamp }}</span>
              <span class="who">{{ s.speaker || '未标注' }}</span>
              <p class="text">{{ s.text }}</p>
              <button class="mark" :title="s.marked ? '取消重点' : '标为重点'" @click="mark(s)">
                {{ s.marked ? '★' : '☆' }}
              </button>
            </article>
            <article v-if="interim" class="seg interim">
              <span class="stamp">{{ clockText }}</span>
              <span class="who">{{ speaker || '未标注' }}</span>
              <p class="text">{{ interim }}<i class="caret-blink">▍</i></p>
            </article>
          </div>

          <div class="compose">
            <div class="compose-top">
              <select v-model="speaker">
                <option value="">未标注</option>
                <option v-for="p in participantList" :key="p" :value="p">{{ p }}</option>
              </select>
              <span class="hint-small">
                {{ listening ? '正在听 —— 说完一句会自己落一行' : 'Enter 记录,Shift+Enter 换行' }}
              </span>
            </div>
            <div class="compose-row">
              <textarea v-model="draft" rows="2"
                        placeholder="听到什么记什么,不用修饰 —— 原话版要的就是原话"
                        @keydown.enter.exact.prevent="send" />
              <button class="primary" :disabled="!draft.trim()" @click="send">记录</button>
            </div>
            <p v-if="m.status === 'ENDED'" class="stale-hint">
              会已结束。现在补录的每一条都会让纪要和行程变成「待更新」—— 记得重新生成。
            </p>
          </div>
        </section>

        <!-- ============ 右:监控 ============ -->
        <aside class="panel monitor">
          <div class="metrics">
            <div class="metric"><b>{{ clockText }}</b><span>有效时长</span></div>
            <div class="metric"><b>{{ m.segments.length }}</b><span>发言条数</span></div>
            <div class="metric"><b>{{ actionCount }}</b><span>待办/决议</span></div>
            <div class="metric"><b>{{ m.participants ? participantList.length : 0 }}</b><span>参会人</span></div>
          </div>

          <div v-if="m.insights && m.insights.length" class="tips">
            <h4>主持人提示</h4>
            <p v-for="(t, i) in m.insights" :key="i" class="tip">· {{ t }}</p>
          </div>

          <div class="signals">
            <div class="sig-head">
              <h4>实时信号</h4>
              <span class="count">{{ m.signals ? m.signals.length : 0 }}</span>
              <span class="spacer"></span>
              <button v-for="f in filters" :key="f.kind"
                      class="chip" :class="{ on: filter === f.kind }" @click="filter = filter === f.kind ? '' : f.kind">
                {{ f.label }}
              </button>
            </div>
            <div v-if="!visibleSignals.length" class="sig-empty">
              还没有提取到决议或待办。等有人说了「就这么定」或者「我来做」,这里就会出现。
            </div>
            <div v-else class="sig-list">
              <div v-for="(s, i) in visibleSignals" :key="i" class="sig" :class="s.kind.toLowerCase()">
                <span class="sig-kind">{{ kindLabel(s.kind) }}</span>
                <span class="sig-stamp">{{ fmtStamp(s.atMs) }}</span>
                <span class="sig-quote">{{ s.quote }}</span>
                <span class="sig-who">{{ s.speaker || '未标注' }}</span>
              </div>
            </div>
          </div>

          <div v-if="m.speakerStats && m.speakerStats.length" class="speakers">
            <h4>谁说了多少</h4>
            <div v-for="s in m.speakerStats" :key="s.speaker" class="spk">
              <span class="spk-name">{{ s.speaker }}</span>
              <span class="spk-bar"><i :style="{ width: pct(s) + '%' }"></i></span>
              <span class="spk-val">{{ s.turns }} 条 · {{ s.chars }} 字</span>
            </div>
          </div>

          <details class="ingest">
            <summary>外部转写推送</summary>
            <p>把这段地址填进转写服务(或自己写个转发脚本),POST 一个片段数组即可实时进来。这条口令只对这场会有写权限。</p>
            <code>{{ pushUrl }}</code>
            <button class="ghost tiny" @click="copyPush">复制地址</button>
          </details>
        </aside>
      </div>

      <!-- ============ 成品 ============ -->
      <nav class="tabs">
        <button :class="{ on: tab === 'summary' }" @click="switchTab('summary')">总结版</button>
        <button :class="{ on: tab === 'verbatim' }" @click="switchTab('verbatim')">原话版</button>
        <button :class="{ on: tab === 'itinerary' }" @click="switchTab('itinerary')">
          行程<i v-if="m.itinerary && m.itinerary.length" class="badge">{{ m.itinerary.length }}</i>
        </button>
        <span class="spacer"></span>
        <span class="sum-state" :class="summaryClass">{{ summaryLabel }}</span>
      </nav>

      <!-- 总结版 -->
      <section v-if="tab === 'summary'" class="out">
        <div class="toolbar">
          <button class="primary" :disabled="!!busy || !m.segments.length" @click="genSummary(!m.summaryMd)">
            {{ busy === 'summary' ? '正在归纳…' : (m.summaryMd ? '重新生成' : '生成总结版') }}
          </button>
          <template v-if="m.summaryMd">
            <button class="ghost" @click="download('summary', 'md')">Markdown</button>
            <button class="ghost" @click="download('summary', 'docx')">Word</button>
            <button class="ghost" @click="download('summary', 'pdf')">PDF</button>
          </template>
          <span class="spacer"></span>
          <span v-if="m.summaryModel" class="tiny-note">模型:{{ m.summaryModel }}</span>
        </div>

        <p v-if="m.summaryStatus === 'PARTIAL'" class="warn-note">
          模型这次没跑通({{ m.summaryNote }}),先给了一版规则速记 —— 它会原句摘录,但不会替你判断哪句更重要。
        </p>
        <p v-if="m.summaryStatus === 'STALE'" class="warn-note">
          会后补录了新内容,这份纪要还是旧的。点「重新生成」才会包含补录的部分。
        </p>

        <div v-if="m.summaryMd" class="md-content" v-html="renderMarkdown(m.summaryMd)"></div>
        <div v-else class="empty">
          <p>还没有总结版。</p>
          <p class="hint">
            点「生成总结版」—— 会按「一句话结论 / 议题与结论 / 已定的事 / 待办 / 分歧与风险 / 需要老板拍板」分段。
            会上说的数字和日期会原样保留,没提到的地方会写「实录未提及」。
          </p>
        </div>
      </section>

      <!-- 原话版 -->
      <section v-else-if="tab === 'verbatim'" class="out">
        <div class="toolbar">
          <button class="ghost" :disabled="busy === 'verbatim'" @click="loadVerbatim">刷新</button>
          <button class="ghost" @click="download('verbatim', 'md')">Markdown</button>
          <button class="ghost" @click="download('verbatim', 'docx')">Word</button>
          <button class="ghost" @click="download('verbatim', 'pdf')">PDF</button>
          <span class="spacer"></span>
          <span class="tiny-note">这段不经过模型:只排序、去重、补时间戳</span>
        </div>
        <p class="warn-note quiet">
          原话版是唯一"一个字都没改过"的那份。它可能被拿去对质,所以这里不做润色,连「嗯」「对」都留着。
        </p>
        <div v-if="verbatimMd" class="md-content verbatim" v-html="renderMarkdown(verbatimMd)"></div>
        <div v-else class="empty"><p>还没有内容。</p></div>
      </section>

      <!-- 行程 -->
      <section v-else class="out">
        <div class="toolbar">
          <button class="primary" :disabled="!!busy || !m.segments.length" @click="genItinerary(!(m.itinerary && m.itinerary.length))">
            {{ busy === 'itinerary' ? '正在提取…' : (m.itinerary && m.itinerary.length ? '重新提取' : '从会议内容排行程') }}
          </button>
          <template v-if="m.itinerary && m.itinerary.length">
            <button class="ghost" @click="download('itinerary', 'md')">Markdown</button>
            <button class="ghost" @click="download('itinerary', 'docx')">Word</button>
            <button class="ghost" @click="api.downloadItineraryIcs(m.id, m.title)">导入日历(.ics)</button>
          </template>
          <span class="spacer"></span>
          <span class="tiny-note">时间不是原话里说定的,会标「待定」</span>
        </div>

        <div v-if="!m.itinerary || !m.itinerary.length" class="empty">
          <p>还没有行程。</p>
          <p class="hint">
            点上面的按钮,它会从发言里挑出「要发生的事」:约的会、要交的东西、要出差的那天。
            每条都带原话依据,时间在实录里没说的会标成「待定」,不替你编。
          </p>
        </div>

        <div v-else class="days">
          <div v-for="g in groups" :key="g.key" class="day">
            <h4 class="day-head">{{ g.label }}</h4>
            <div v-for="it in g.items" :key="it.id" class="item" :class="{ done: it.status === 'DONE', cancelled: it.status === 'CANCELLED' }">
              <div class="item-time">
                <b v-if="it.startAt">{{ timeOf(it) }}</b>
                <b v-else class="tbd">待定</b>
                <span v-if="it.endAt">{{ timeOf({ startAt: it.endAt }) }}</span>
              </div>
              <div class="item-body">
                <div class="item-top">
                  <span class="kind" :class="it.kind.toLowerCase()">{{ kindLabel(it.kind) }}</span>
                  <h5>{{ it.title }}</h5>
                  <span v-if="it.timeUnknown" class="tbd-chip">时间待定</span>
                  <span v-if="it.taskId" class="task-chip">已派任务 #{{ it.taskId }}</span>
                  <span class="spacer"></span>
                  <span v-if="it.status === 'DONE'" class="done-chip">已完成</span>
                  <span v-if="it.status === 'CANCELLED'" class="done-chip">已取消</span>
                </div>
                <p v-if="it.location || it.participants" class="item-meta">
                  <span v-if="it.location">地点:{{ it.location }}</span>
                  <span v-if="it.participants">参与:{{ it.participants }}</span>
                </p>
                <p v-if="it.prepare" class="item-prep">准备:{{ it.prepare }}</p>
                <p v-if="it.sourceQuote" class="item-quote">「{{ it.sourceQuote }}」</p>
                <div class="item-actions">
                  <template v-if="it.status === 'PLANNED'">
                    <button class="ghost tiny" @click="markItem(it, 'DONE')">标记完成</button>
                    <button class="ghost tiny" :disabled="!!it.taskId" @click="openAssign(it)">
                      {{ it.taskId ? '已派活' : '派给岗位准备' }}
                    </button>
                    <button class="ghost tiny" @click="markItem(it, 'CANCELLED')">取消</button>
                  </template>
                  <button v-else class="ghost tiny" @click="markItem(it, 'PLANNED')">恢复</button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>
    </template>

    <!-- 导入转写稿 -->
    <el-dialog v-model="importOpen" title="导入转写稿" width="640px" :close-on-click-modal="false">
      <p class="dlg-hint">
        整段粘进来就行。能认「[00:01:02] 张三: 内容」「00:01:02 张三:内容」「张三: 内容」三种写法;
        认不出说话人的整行当内容,不会瞎猜是谁说的。
      </p>
      <textarea v-model="importText" rows="12" class="import-area"
                placeholder="[00:00:05] 张伟: 今天主要三件事…"></textarea>
      <div class="dlg-row">
        <label>认不出说话人时,算作</label>
        <select v-model="importSpeaker">
          <option value="">未标注</option>
          <option v-for="p in participantList" :key="p" :value="p">{{ p }}</option>
        </select>
        <span class="spacer"></span>
        <button class="ghost tiny" @click="fillDemo">填入一段示例</button>
      </div>
      <template #footer>
        <el-button @click="importOpen = false">取消</el-button>
        <el-button type="primary" :loading="!!busy" @click="doImport">导入</el-button>
      </template>
    </el-dialog>

    <!-- 派活 -->
    <el-dialog v-model="assignOpen" title="派给岗位准备" width="460px" :close-on-click-modal="false">
      <p class="dlg-hint">
        派的是「会前准备」—— AI 不能替你去开会,但可以在会前把材料、清单、提纲准备好。
        会议原话会一起交给它,免得它自己编背景。
      </p>
      <p class="dlg-item">{{ assignItem?.title }}</p>
      <div class="dlg-row">
        <label>岗位</label>
        <select v-model="assignEmp">
          <option v-for="e in employees" :key="e.id" :value="e.id">{{ e.name }}{{ e.title ? ' · ' + e.title : '' }}</option>
        </select>
      </div>
      <textarea v-model="assignNote" rows="3" class="import-area" placeholder="额外交代(可选):例:顺便把上一轮比价记录带上"></textarea>
      <template #footer>
        <el-button @click="assignOpen = false">取消</el-button>
        <el-button type="primary" :loading="!!busy" :disabled="!assignEmp" @click="doAssign">派给它</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, bus } from '../api'
import { renderMarkdown } from '../utils/markdown'

const route = useRoute()
const id = Number(route.params.id)

const m = ref(null)
const loading = ref(true)
const loadError = ref('')
const elapsed = ref(0)
const listening = ref(false)
const interim = ref('')
const draft = ref('')
const speaker = ref('')
const tab = ref('summary')
const busy = ref('')
const verbatimMd = ref('')
const feedEl = ref(null)
const filter = ref('')

const importOpen = ref(false)
const importText = ref('')
const importSpeaker = ref('')

const assignOpen = ref(false)
const assignItem = ref(null)
const assignEmp = ref('')
const assignNote = ref('')
const employees = ref([])

const STATUS = { DRAFT: '未开始', LIVE: '进行中', PAUSED: '已暂停', ENDED: '已结束' }
const SUMMARY = { NONE: '未生成', STALE: '纪要待更新', PARTIAL: '速记版', READY: '已生成' }
const SRC = { BROWSER: '语音识别', MANUAL: '手工速记', IMPORT: '转写稿导入', PUSH: '外部推送' }
const KIND = {
  DECISION: '决议', ACTION: '待办', RISK: '风险', OPEN: '悬而未决', TIME: '时间', NUMBER: '数字',
  MEETING: '会议', CALL: '电话', TRAVEL: '出行', PREP: '准备', DEADLINE: '截止', OTHER: '其他'
}
const filters = [
  { kind: 'DECISION', label: '决议' },
  { kind: 'ACTION', label: '待办' },
  { kind: 'RISK', label: '风险' },
  { kind: 'OPEN', label: '悬而未决' }
]

const statusLabel = computed(() => STATUS[m.value?.status] || '')
const statusClass = computed(() => ({ DRAFT: 'plan', LIVE: 'live', PAUSED: 'warn', ENDED: 'done' }[m.value?.status] || ''))
const summaryLabel = computed(() => SUMMARY[m.value?.summaryStatus] || '')
const summaryClass = computed(() => ({ READY: 'ok', PARTIAL: 'warn', STALE: 'warn' }[m.value?.summaryStatus] || ''))
const sourceLabel = computed(() => SRC[m.value?.source] || '手工速记')
const participantList = computed(() =>
  (m.value?.participants || '').split(/[,，、]/).map(s => s.trim()).filter(Boolean))
const actionCount = computed(() =>
  (m.value?.signals || []).filter(s => s.kind === 'ACTION' || s.kind === 'DECISION').length)
const pushUrl = computed(() => `${location.origin}/api/meetings/ingest/${m.value?.ingestToken || ''}`)

const clockText = computed(() => {
  const total = Math.max(0, elapsed.value | 0)
  const h = Math.floor(total / 3600)
  const mm = String(Math.floor((total % 3600) / 60)).padStart(2, '0')
  const ss = String(total % 60).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
})

const visibleSignals = computed(() => {
  const all = m.value?.signals || []
  const list = filter.value ? all.filter(s => s.kind === filter.value) : all
  // 新的在上:开会时最该看的是刚说的那句
  return list.slice().reverse().slice(0, 40)
})

const groups = computed(() => {
  const items = (m.value?.itinerary || []).slice()
  items.sort((a, b) => {
    if (!a.startAt && !b.startAt) return 0
    if (!a.startAt) return 1
    if (!b.startAt) return -1
    return new Date(a.startAt) - new Date(b.startAt)
  })
  const out = []
  const idx = new Map()
  for (const it of items) {
    const key = it.startAt ? dayLabel(it.startAt) : '待定'
    if (!idx.has(key)) {
      idx.set(key, out.length)
      out.push({ key, label: key === '待定' ? '时间待定' : key, items: [] })
    }
    out[idx.get(key)].items.push(it)
  }
  return out
})

function msg(e) {
  return e?.response?.data?.message || e?.message || '未知错误'
}

function kindLabel(k) { return KIND[k] || k }
function fmtStamp(ms) {
  const s = Math.max(0, (ms || 0) / 1000)
  const p = (n) => String(n).padStart(2, '0')
  const h = Math.floor(s / 3600)
  return h > 0 ? `${h}:${p(Math.floor((s % 3600) / 60))}:${p(Math.floor(s % 60))}` : `${p(Math.floor(s / 60))}:${p(Math.floor(s % 60))}`
}
function dayLabel(iso) {
  const d = new Date(iso)
  const w = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${d.getMonth() + 1} 月 ${d.getDate()} 日 · 周${w}`
}
function timeOf(it) {
  const d = new Date(it.startAt)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
function pct(s) {
  const list = m.value?.speakerStats || []
  const total = list.reduce((a, x) => a + x.chars, 0) || 1
  return Math.max(3, Math.round(s.chars * 100 / total))
}

async function load() {
  try {
    const d = await api.meeting(id)
    m.value = d
    elapsed.value = d.elapsedSeconds || 0
  } catch (e) {
    loadError.value = '这场会议没读出来:' + msg(e)
    m.value = null
  } finally {
    loading.value = false
  }
}

/** 节流重取详情:信号、提示、发言占比这些是服务端算的,不在这边重复实现 */
let syncing = false
async function syncDetail() {
  if (syncing || busy.value) return
  syncing = true
  try {
    const d = await api.meeting(id)
    // 片段以服务端为准,但要防止正在输入的中间态被覆盖 —— 这里只替换数据,不动 draft
    m.value = d
    if (d.status === 'LIVE') elapsed.value = d.elapsedSeconds
  } catch { /* 网络抖动忽略 */ } finally {
    syncing = false
  }
}

function nearBottom() {
  const el = feedEl.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < 140
}
function scrollBottom() {
  nextTick(() => {
    const el = feedEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function mergeSegment(seg) {
  if (!m.value || !seg) return
  if (m.value.segments.some(s => s.id === seg.id)) return
  const stick = nearBottom()
  m.value.segments.push(seg)
  if (stick) scrollBottom()
}

async function start() {
  const r = await api.startMeeting(id)
  m.value.status = r.status
  elapsed.value = r.elapsedSeconds || 0
  ElMessage.success('开始计时')
}
async function pause() {
  const r = await api.pauseMeeting(id)
  m.value.status = r.status
  elapsed.value = r.elapsedSeconds || 0
  await syncDetail()
}
async function resume() {
  const r = await api.resumeMeeting(id)
  m.value.status = r.status
  elapsed.value = r.elapsedSeconds || 0
  await syncDetail()
}
async function end() {
  try {
    await ElMessageBox.confirm(
      '结束后还能补录,但纪要和行程要重新生成。确定结束?', '结束会议',
      { confirmButtonText: '结束', cancelButtonText: '再聊聊', type: 'warning' })
  } catch { return }
  const r = await api.endMeeting(id)
  m.value.status = r.status
  elapsed.value = r.elapsedSeconds || 0
  await syncDetail()
  if (m.value.segments.length) {
    await genSummary(true)
    tab.value = 'summary'
  }
}

async function send() {
  const text = draft.value.trim()
  if (!text) return
  const stick = nearBottom()
  draft.value = ''
  try {
    const r = await api.meetingSegment(id, { speaker: speaker.value || '', text })
    if (r.status && m.value.status !== r.status) m.value.status = r.status
    if (r.segment) {
      if (!m.value.segments.some(s => s.id === r.segment.id)) m.value.segments.push(r.segment)
    }
    if (Array.isArray(r.signals) && r.signals.length) m.value.signals.push(...r.signals)
    if (r.elapsedSeconds != null) elapsed.value = r.elapsedSeconds
    if (r.stale) ElMessage.warning(r.stale)
    if (stick) scrollBottom()
  } catch (e) {
    draft.value = text
    ElMessage.error('没记上:' + msg(e))
  }
}

async function mark(s) {
  try {
    const r = await api.markSegment(s.id)
    s.marked = r.marked
  } catch (e) { ElMessage.error(msg(e)) }
}

// ---------- 语音识别 ----------
let recog = null
function toggleMic() {
  if (listening.value) { stopMic(); return }
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition
  if (!SR) {
    ElMessage.warning('这个浏览器不支持实时语音识别。用 Chrome / Edge 打开这个页面,或者改用打字、导入转写稿。')
    return
  }
  try {
    recog = new SR()
    recog.lang = 'zh-CN'
    recog.continuous = true
    recog.interimResults = true
    recog.onresult = (e) => {
      let pending = ''
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const r = e.results[i]
        const t = (r[0]?.transcript || '').trim()
        if (!t) continue
        if (r.isFinal) pushRecognized(t)
        else pending += t
      }
      interim.value = pending
    }
    recog.onerror = (e) => {
      if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
        ElMessage.error('麦克风权限被拒绝。浏览器地址栏左边的锁里允许麦克风,再点一次。')
        listening.value = false
      } else if (e.error === 'network') {
        ElMessage.error('语音识别服务连不上(有些浏览器的识别要联网)。可以先打字,或导入转写稿。')
        listening.value = false
      }
      // no-speech 之类的忽略:说话间歇很正常
    }
    recog.onend = () => {
      // 浏览器在静音后会自己断开,还在听就续上
      if (listening.value) {
        try { recog.start() } catch { /* 已经在跑 */ }
      } else {
        interim.value = ''
      }
    }
    recog.start()
    listening.value = true
  } catch (e) {
    ElMessage.error('启动语音识别失败:' + (e.message || e))
    listening.value = false
  }
}
function stopMic() {
  listening.value = false
  interim.value = ''
  try { recog && recog.stop() } catch { /* 忽略 */ }
  recog = null
}
async function pushRecognized(text) {
  interim.value = ''
  try {
    const r = await api.meetingSegment(id, { speaker: speaker.value || '', text })
    if (r.status && m.value.status !== r.status) m.value.status = r.status
    if (r.segment && !m.value.segments.some(s => s.id === r.segment.id)) m.value.segments.push(r.segment)
    if (Array.isArray(r.signals) && r.signals.length) m.value.signals.push(...r.signals)
    if (r.elapsedSeconds != null) elapsed.value = r.elapsedSeconds
    scrollBottom()
  } catch (e) {
    ElMessage.error('这段没记上:' + msg(e))
  }
}

// ---------- 导入 ----------
function openImport() {
  importText.value = ''
  importSpeaker.value = participantList.value[0] || ''
  importOpen.value = true
}
function fillDemo() {
  importText.value = [
    '[00:00:05] 张伟: 今天主要三件事,退货率、华东渠道、双十一备货',
    '[00:00:22] 李娜: 先说退货率,这个月 4.7%,比上个月高了 1.2 个点,主要是华东那批货的尺码问题',
    '[00:00:48] 张伟: 4.7% 已经超过我们 3% 的红线了,得有个说法',
    '[00:01:05] 王强: 我来对接工厂做一次尺码复核,下周三之前给结论',
    '[00:01:20] 李娜: 我这边同步客服话术,这周内发一版',
    '[00:01:38] 张伟: 好,退货率这块就这么定,王强负责工厂复核,李娜负责客服话术,下周三一起看结果',
    '[00:01:55] 王强: 双十一备货我担心来不及,现在下单工厂排期要到 11 月 5 日',
    '[00:02:12] 张伟: 备货量先按去年同期的 1.3 倍报,预算大概 87 万,这个需要老板拍板',
    '[00:02:30] 李娜: 那 11 月 3 日下午两点我们和天猫小二对一下活动节奏?',
    '[00:02:45] 张伟: 可以,我来约,地点就在 3 楼会议室',
    '[00:03:00] 王强: 另外华东仓的库存盘点还没做,回头再说吧'
  ].join('\n')
}
async function doImport() {
  if (!importText.value.trim()) return
  busy.value = 'import'
  try {
    const r = await api.importTranscript(id, importText.value, importSpeaker.value)
    importOpen.value = false
    ElMessage.success(`导入 ${r.added} 条${r.duplicate ? `,跳过重复 ${r.duplicate} 条` : ''}`)
    await syncDetail()
    scrollBottom()
  } catch (e) {
    ElMessage.error('导入失败:' + msg(e))
  } finally {
    busy.value = ''
  }
}

// ---------- 成品 ----------
function switchTab(t) {
  tab.value = t
  if (t === 'verbatim' && !verbatimMd.value) loadVerbatim()
}
async function loadVerbatim() {
  busy.value = 'verbatim'
  try {
    const r = await api.meetingVerbatim(id)
    verbatimMd.value = r.md
  } catch (e) {
    ElMessage.error('原话没读出来:' + msg(e))
  } finally {
    busy.value = ''
  }
}

async function genSummary(force) {
  if (!m.value.segments.length) { ElMessage.warning('还没有发言,先生成不了纪要'); return }
  busy.value = 'summary'
  try {
    const r = await api.summarizeMeeting(id, force)
    if (r.summaryMd) m.value.summaryMd = r.summaryMd
    m.value.summaryStatus = r.summaryStatus
    m.value.summaryNote = r.note || ''
    if (r.skipped) { ElMessage.info('已经有纪要了,没重新生成') ; return }
    if (r.summaryStatus === 'PARTIAL') ElMessage.warning('模型这次没跑通,先给了一版规则速记:' + (r.note || ''))
    else ElMessage.success('总结版好了')
    await syncDetail()
  } catch (e) {
    ElMessage.error('生成失败:' + msg(e))
  } finally {
    busy.value = ''
  }
}

async function genItinerary(force) {
  if (!m.value.segments.length) { ElMessage.warning('还没有发言,排不出行程'); return }
  busy.value = 'itinerary'
  try {
    const r = await api.planItinerary(id, force)
    if (!r.ok) { ElMessage.error(r.error || '提取失败'); return }
    if (r.note) ElMessage.info(r.note)
    else ElMessage.success(`排了 ${r.added} 件${r.dropped ? `,丢掉 ${r.dropped} 条重复或坏时间` : ''}`)
    await syncDetail()
  } catch (e) {
    ElMessage.error('提取失败:' + msg(e))
  } finally {
    busy.value = ''
  }
}

async function markItem(it, status) {
  try {
    await api.itineraryItemStatus(it.id, status)
    it.status = status
  } catch (e) { ElMessage.error(msg(e)) }
}

async function download(kind, format) {
  try {
    await api.downloadMeeting(id, kind, format, m.value.title)
  } catch (e) {
    ElMessage.error('导出失败:' + msg(e))
  }
}

// ---------- 派活 ----------
async function openAssign(it) {
  assignItem.value = it
  assignNote.value = ''
  assignOpen.value = true
  if (!employees.value.length) {
    try {
      const org = await api.org()
      employees.value = (org.departments || [])
        .flatMap(d => d.employees || [])
        .filter(e => !e.ceo && e.status !== 'DISABLED')
    } catch (e) { ElMessage.error('岗位列表没读到:' + msg(e)) }
  }
  assignEmp.value = employees.value[0]?.id || ''
}
async function doAssign() {
  if (!assignEmp.value) return
  busy.value = 'assign'
  try {
    const r = await api.assignItineraryTask(assignItem.value.id, assignEmp.value, assignNote.value)
    assignItem.value.taskId = r.taskId
    assignOpen.value = false
    ElMessage.success(`已派给 ${r.employee},任务 #${r.taskId}`)
  } catch (e) {
    ElMessage.error('派活失败:' + msg(e))
  } finally {
    busy.value = ''
  }
}

function copyPush() {
  navigator.clipboard?.writeText(pushUrl.value)
    .then(() => ElMessage.success('地址已复制'))
    .catch(() => ElMessage.warning('复制失败,请手动选中'))
}

async function remove() {
  try {
    await ElMessageBox.confirm('删掉这场会以及它的全部发言和行程?这一步不可撤销。', '删除会议',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
  } catch { return }
  await api.deleteMeeting(id)
  location.hash = '#/meetings'
}

// ---------- 订阅与计时 ----------
let offBus = null
let ticker = null
let poller = null

onMounted(async () => {
  await load()
  if (m.value?.status === 'LIVE') scrollBottom()

  offBus = bus.on(async (evt) => {
    if (!evt || !m.value) return
    if (evt.type === 'meeting-segment' && evt.meetingId === id) {
      mergeSegment(evt.segment)
    } else if (evt.type === 'meeting-state' && evt.meetingId === id) {
      if (evt.status === 'DELETED') { location.hash = '#/meetings'; return }
      if (evt.status && m.value.status !== evt.status) m.value.status = evt.status
      if (evt.elapsedSeconds != null) elapsed.value = evt.elapsedSeconds
    }
  })

  // 计时本地走秒:每秒问一次服务器没有意义,状态变化时再对齐
  ticker = setInterval(() => {
    if (m.value?.status === 'LIVE') {
      elapsed.value += 1
      if (elapsed.value % 5 === 0) {
        const el = feedEl.value
        if (el && nearBottom()) el.scrollTop = el.scrollHeight
      }
    }
  }, 1000)

  // 信号/提示/占比是服务端算的,开会时每 15 秒同步一次
  poller = setInterval(() => {
    if (m.value && (m.value.status === 'LIVE' || m.value.status === 'PAUSED')) syncDetail()
  }, 15000)
})

onUnmounted(() => {
  if (offBus) offBus()
  if (ticker) clearInterval(ticker)
  if (poller) clearInterval(poller)
  stopMic()
})
</script>

<style scoped>
.page { padding: 20px 24px 70px; max-width: 1320px; margin: 0 auto; }
.head { display: flex; align-items: flex-start; gap: 16px; margin-bottom: 14px; }
.back { background: none; border: 0; color: var(--text-faint); font-size: 12px; cursor: pointer; padding: 4px 0; }
.back:hover { color: var(--glow-cyan); }
.title-block { min-width: 0; }
.title-block h1 { margin: 0 0 6px; font-family: var(--font-serif); font-size: 20px; font-weight: 400; letter-spacing: .1em; color: var(--text-moon); }
.meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 0; font-size: 11.5px; color: var(--text-faint); }
.mono { font-family: var(--font-mono); color: var(--glow-cyan); font-size: 12px; letter-spacing: .04em; }
.actions { margin-left: auto; display: flex; gap: 7px; flex-shrink: 0; }

.pill { font-size: 10.5px; padding: 1px 7px; border-radius: 999px; border: 1px solid var(--border-hair); color: var(--text-faint); }
.pill.live { color: var(--glow-cyan); border-color: var(--glow-cyan-dim); }
.pill.warn { color: var(--st-warn); border-color: rgba(var(--rgb-warn), .35); }
.pill.done { color: var(--st-ok); border-color: rgba(var(--rgb-ok), .3); }
.pill.plan { color: var(--st-plan); border-color: rgba(var(--rgb-plan), .3); }

.grid { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(300px, 1fr); gap: 14px; }
.panel { border: 1px solid var(--border-hair); border-radius: 12px; background: var(--tint-soft); overflow: hidden; }
.panel-head { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-bottom: 1px solid var(--border-hair); }
.ptitle { font-size: 12.5px; letter-spacing: .08em; color: var(--text-moon); }
.count { font-family: var(--font-mono); font-size: 11px; color: var(--text-faint); }
.spacer { flex: 1; }

.stream { display: flex; flex-direction: column; min-height: 420px; }
.feed { flex: 1; overflow-y: auto; padding: 12px 14px; max-height: 46vh; min-height: 240px; scroll-behavior: smooth; }
.feed-empty { padding: 34px 10px; text-align: center; color: var(--text-dim); font-size: 12.5px; }
.feed-empty .hint { font-size: 11.5px; color: var(--text-faint); line-height: 1.8; max-width: 420px; margin: 8px auto 0; }
.seg { display: grid; grid-template-columns: 52px 82px 1fr 20px; gap: 8px; align-items: start; padding: 5px 0; border-bottom: 1px dashed rgba(var(--rgb-ink), .06); }
.seg:last-child { border-bottom: 0; }
.seg.sys { opacity: .5; }
.seg.marked .text { color: var(--glow-cyan); }
.seg.interim { opacity: .6; }
.stamp { font-family: var(--font-mono); font-size: 10.5px; color: var(--text-faint); padding-top: 2px; }
.who { font-size: 11.5px; color: var(--glow-cyan-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; padding-top: 1px; }
.text { margin: 0; font-size: 13px; line-height: 1.75; color: var(--text-moon); word-break: break-word; }
.mark { background: none; border: 0; color: var(--text-faint); cursor: pointer; font-size: 12px; padding: 0; opacity: .3; }
.seg:hover .mark { opacity: .9; }
.mark:hover { color: var(--warm); }
.caret-blink { font-style: normal; color: var(--glow-cyan); animation: blink 1s steps(2) infinite; }
@keyframes blink { 50% { opacity: 0 } }

.compose { border-top: 1px solid var(--border-hair); padding: 10px 14px 12px; background: rgba(0, 0, 0, .12); }
.compose-top { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.compose select, .dlg-row select { background: var(--field-bg); border: 1px solid var(--border-hair-strong); border-radius: 7px; color: var(--text-moon); font-size: 12px; padding: 4px 8px; font-family: inherit; }
.hint-small { font-size: 11px; color: var(--text-faint); }
.compose-row { display: flex; gap: 8px; align-items: flex-end; }
.compose textarea { flex: 1; background: var(--field-bg); border: 1px solid var(--border-hair-strong); border-radius: 8px; color: var(--text-moon); font-size: 12.5px; line-height: 1.7; padding: 8px 10px; resize: vertical; font-family: inherit; }
.compose textarea:focus { outline: none; border-color: var(--glow-cyan-dim); }
.stale-hint { margin: 8px 0 0; font-size: 11px; color: var(--st-warn); }

.monitor { padding: 12px 14px 14px; display: flex; flex-direction: column; gap: 14px; max-height: 62vh; overflow-y: auto; }
.metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.metric { display: flex; flex-direction: column; gap: 2px; padding: 8px 6px; border: 1px solid var(--border-hair); border-radius: 8px; text-align: center; }
.metric b { font-family: var(--font-mono); font-size: 14px; font-weight: 400; color: var(--text-moon); }
.metric span { font-size: 10px; color: var(--text-faint); }
.tips { border-left: 2px solid var(--warm); padding-left: 10px; }
.tips h4, .signals h4, .speakers h4 { margin: 0 0 6px; font-size: 11px; font-weight: 400; letter-spacing: .08em; color: var(--text-faint); }
.tip { margin: 0 0 5px; font-size: 11.5px; line-height: 1.7; color: var(--warm); }

.sig-head { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; flex-wrap: wrap; }
.chip { background: none; border: 1px solid var(--border-hair); border-radius: 999px; color: var(--text-faint); font-size: 10px; padding: 1px 7px; cursor: pointer; }
.chip.on { color: var(--glow-cyan); border-color: var(--glow-cyan-dim); background: var(--glow-cyan-faint); }
.sig-list { display: flex; flex-direction: column; gap: 6px; max-height: 220px; overflow-y: auto; }
.sig { display: grid; grid-template-columns: 40px 42px 1fr; gap: 6px; font-size: 11.5px; line-height: 1.6; padding: 5px 7px; border-radius: 6px; background: var(--chip-bg); }
.sig-kind { color: var(--text-faint); font-size: 10px; }
.sig.decision .sig-kind { color: var(--st-ok); }
.sig.action .sig-kind { color: var(--glow-cyan); }
.sig.risk .sig-kind { color: var(--st-error); }
.sig.open .sig-kind { color: var(--st-warn); }
.sig-stamp { font-family: var(--font-mono); font-size: 10px; color: var(--text-faint); }
.sig-quote { color: var(--text-moon); }
.sig-who { grid-column: 2 / 4; font-size: 10px; color: var(--text-faint); }
.sig-empty { font-size: 11px; color: var(--text-faint); line-height: 1.7; }

.speakers { display: flex; flex-direction: column; gap: 5px; }
.spk { display: grid; grid-template-columns: 74px 1fr 92px; gap: 8px; align-items: center; font-size: 11px; }
.spk-name { color: var(--text-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.spk-bar { height: 3px; border-radius: 3px; background: var(--border-hair); overflow: hidden; }
.spk-bar i { display: block; height: 100%; background: var(--glow-cyan-dim); }
.spk-val { font-family: var(--font-mono); font-size: 10px; color: var(--text-faint); text-align: right; }

.ingest { font-size: 11px; color: var(--text-faint); border-top: 1px solid var(--border-hair); padding-top: 10px; }
.ingest summary { cursor: pointer; color: var(--text-dim); }
.ingest p { line-height: 1.7; margin: 8px 0; }
.ingest code { display: block; font-family: var(--font-mono); font-size: 10px; word-break: break-all; padding: 6px 8px; background: rgba(0, 0, 0, .25); border-radius: 6px; margin-bottom: 8px; color: var(--text-dim); }

.tabs { display: flex; align-items: center; gap: 4px; border-bottom: 1px solid var(--border-hair); margin: 18px 0 0; }
.tabs button { background: none; border: 0; color: var(--text-dim); font-size: 13px; padding: 9px 12px; cursor: pointer; position: relative; }
.tabs button.on { color: var(--glow-cyan); }
.tabs button.on::after { content: ''; position: absolute; left: 10px; right: 10px; bottom: -1px; height: 1px; background: var(--glow-cyan); }
.badge { font-style: normal; font-family: var(--font-mono); font-size: 10px; background: var(--chip-bg); border-radius: 8px; padding: 1px 5px; margin-left: 5px; color: var(--text-dim); }
.sum-state { font-size: 11px; color: var(--text-faint); }
.sum-state.ok { color: var(--st-ok); }
.sum-state.warn { color: var(--st-warn); }

.out { padding: 14px 2px 0; }
.toolbar { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; margin-bottom: 10px; }
.tiny-note { font-size: 11px; color: var(--text-faint); }
.warn-note { margin: 0 0 12px; padding: 9px 11px; border-radius: 8px; background: rgba(var(--rgb-warn), .08); border: 1px solid rgba(var(--rgb-warn), .25); color: var(--st-warn); font-size: 11.5px; line-height: 1.7; }
.warn-note.quiet { background: var(--tint-soft); border-color: var(--border-hair); color: var(--text-faint); }
.empty { padding: 40px 0; text-align: center; color: var(--text-dim); font-size: 13px; }
.empty .hint { font-size: 12px; color: var(--text-faint); margin-top: 8px; line-height: 1.8; max-width: 560px; margin-left: auto; margin-right: auto; }
.verbatim { max-height: 60vh; overflow-y: auto; }

.days { display: flex; flex-direction: column; gap: 18px; }
.day-head { margin: 0 0 8px; font-family: var(--font-serif); font-size: 13px; font-weight: 400; letter-spacing: .1em; color: var(--glow-cyan-dim); }
.item { display: grid; grid-template-columns: 72px 1fr; gap: 14px; padding: 10px 12px; border: 1px solid var(--border-hair); border-radius: 9px; background: var(--card-flat); margin-bottom: 8px; }
.item.done { opacity: .55; }
.item.cancelled { opacity: .4; text-decoration: line-through; }
.item-time { display: flex; flex-direction: column; gap: 2px; font-family: var(--font-mono); font-size: 12px; color: var(--text-moon); }
.item-time span { font-size: 10px; color: var(--text-faint); }
.item-time .tbd { color: var(--st-warn); font-size: 11px; }
.item-top { display: flex; align-items: center; gap: 8px; }
.item-top h5 { margin: 0; font-size: 13.5px; font-weight: 400; color: var(--text-moon); }
.kind { font-size: 10px; padding: 1px 6px; border-radius: 5px; border: 1px solid var(--border-hair); color: var(--text-faint); }
.kind.meeting { color: var(--glow-cyan); border-color: var(--glow-cyan-dim); }
.kind.travel { color: var(--st-plan); border-color: rgba(var(--rgb-plan), .35); }
.kind.prep { color: var(--warm); border-color: rgba(var(--rgb-warm), .35); }
.kind.call { color: var(--st-ok); border-color: rgba(var(--rgb-ok), .3); }
.kind.deadline { color: var(--st-error); border-color: rgba(var(--rgb-error), .3); }
.tbd-chip { font-size: 10px; color: var(--st-warn); border: 1px dashed rgba(var(--rgb-warn), .4); border-radius: 5px; padding: 1px 5px; }
.task-chip { font-size: 10px; color: var(--glow-cyan); font-family: var(--font-mono); }
.done-chip { font-size: 10px; color: var(--st-ok); }
.item-meta { display: flex; gap: 14px; margin: 6px 0 0; font-size: 11.5px; color: var(--text-dim); }
.item-prep { margin: 5px 0 0; font-size: 11.5px; color: var(--text-dim); line-height: 1.7; }
.item-quote { margin: 5px 0 0; font-size: 11.5px; color: var(--text-faint); border-left: 2px solid var(--border-hair); padding-left: 8px; line-height: 1.7; }
.item-actions { display: flex; gap: 6px; margin-top: 8px; }

button.primary, button.ghost, button.danger { font-size: 12px; padding: 5px 13px; border-radius: 7px; cursor: pointer; border: 1px solid var(--border-hair-strong); background: none; color: var(--text-dim); transition: .16s; }
button.primary { background: var(--rgb-btn); border-color: transparent; color: var(--rgb-on-btn); }
button.primary:hover:not(:disabled) { background: var(--rgb-btn-hi); }
button.ghost:hover:not(:disabled) { color: var(--text-moon); border-color: var(--glow-cyan-dim); }
button.danger { color: var(--st-error); border-color: rgba(var(--rgb-error), .35); }
button.ghost.live { color: var(--glow-cyan); border-color: var(--glow-cyan-dim); }
button.tiny { font-size: 11px; padding: 2px 9px; }
button:disabled { opacity: .4; cursor: not-allowed; }
.mic-dot { display: inline-block; width: 5px; height: 5px; border-radius: 50%; background: var(--st-error); margin-right: 5px; animation: blink 1.2s infinite; }

.dlg-hint { margin: 0 0 10px; font-size: 11.5px; color: var(--text-faint); line-height: 1.75; }
.import-area { width: 100%; background: var(--field-bg); border: 1px solid var(--border-hair-strong); border-radius: 8px; color: var(--text-moon); font-size: 12.5px; line-height: 1.75; padding: 9px 11px; font-family: var(--font-mono); resize: vertical; }
.dlg-row { display: flex; align-items: center; gap: 10px; margin-top: 10px; font-size: 11.5px; color: var(--text-faint); }
.dlg-item { margin: 0 0 8px; font-size: 13px; color: var(--text-moon); }

@media (max-width: 1000px) {
  .page { padding: 16px 14px 60px; }
  .grid { grid-template-columns: 1fr; }
  .monitor { max-height: none; }
  .feed { max-height: 40vh; }
  .head { flex-wrap: wrap; }
  .actions { margin-left: 0; width: 100%; }
}
</style>
