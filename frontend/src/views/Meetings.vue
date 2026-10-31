<template>
  <div class="page">
    <header class="head">
      <div>
        <h1>会议</h1>
        <p class="sub">
          会还在开的时候,纪要和待办就跟着记录内容一起生成出来。会后的三份东西:一份归纳过的<b>总结版</b>、
          一份没被改过一个字的<b>原话版</b>、一份从内容里排出来的<b>行程</b>。
        </p>
      </div>
      <button class="primary big" @click="openCreate">新建会议</button>
    </header>

    <div v-if="loadError" class="err">
      <p>{{ loadError }}</p>
      <button class="ghost" @click="load">重试</button>
    </div>

    <section v-if="!list.length && !loading" class="howto">
      <h2>三种记录方式,选一种就行</h2>
      <div class="how-grid">
        <div class="how">
          <b>语音实时识别</b>
          <span>用 Chrome / Edge 打开会议页,点「语音识别」,对着麦克风说话就会一行行长出来。需要给浏览器麦克风权限。</span>
        </div>
        <div class="how">
          <b>手工速记</b>
          <span>会上一人打字记录。谁说的选一下发言人,一句话回车一条 —— 比会后回忆靠谱。</span>
        </div>
        <div class="how">
          <b>导入转写稿</b>
          <span>腾讯会议 / 飞书妙记 / 通义听悟导出的文字,整段粘进来,能认出「[00:01:02] 张三: 内容」这类格式。</span>
        </div>
      </div>
      <p class="how-foot">
        外部转写服务也能直接推:每场会都有一条只属于它的推送地址和口令,在会议详情里复制,不用把控制台登录口令给出去。
      </p>
    </section>

    <section class="list">
      <article v-for="x in list" :key="x.id" class="card" @click="$router.push(`/meetings/${x.id}`)">
        <div class="card-top">
          <span class="dot" :class="statusClass(x.status)"></span>
          <h3>{{ x.title }}</h3>
          <span class="pill" :class="statusClass(x.status)">{{ statusLabel(x.status) }}</span>
          <span class="spacer"></span>
          <span class="when">{{ fmtDate(x.startedAt || x.scheduledAt || x.createdAt) }}</span>
        </div>
        <div class="card-meta">
          <span>{{ minutes(x.elapsedSeconds) }}</span>
          <span>{{ x.segments }} 条发言</span>
          <span v-if="x.agendaCount">议程 {{ x.agendaCount }} 条</span>
          <span v-if="x.itineraryCount">{{ x.itineraryCount }} 件行程</span>
          <span :class="['sum', summaryClass(x.summaryStatus)]">{{ summaryLabel(x.summaryStatus) }}</span>
          <span class="src">{{ sourceLabel(x.source) }}</span>
        </div>
        <p v-if="x.participants" class="who">参会:{{ x.participants }}</p>
      </article>

      <div v-if="loading" class="empty">读取中…</div>
      <div v-else-if="!list.length" class="empty">
        <p>还没有会议。</p>
        <p class="hint">点右上角「新建会议」建一场,或者直接建一场空会,把之前的转写稿粘进去试试。</p>
      </div>
    </section>

    <el-dialog v-model="createOpen" title="新建会议" width="520px" :close-on-click-modal="false">
      <div class="form">
        <label>会议主题 <i>*</i></label>
        <input v-model="form.title" placeholder="例:Q4 渠道复盘" @keyup.enter="submit" />

        <label>时间</label>
        <input v-model="form.scheduledAt" type="datetime-local" />

        <label>地点</label>
        <input v-model="form.location" placeholder="例:无锡 3 楼会议室 / 腾讯会议" />

        <label>参会人</label>
        <input v-model="form.participants" placeholder="逗号分隔,例:张伟,李娜,王强" />

        <label>议题(可选)</label>
        <textarea v-model="form.agenda" rows="3"
                  placeholder="会前想清楚要聊什么,一条一行。会后对照它才知道哪些没聊到"></textarea>
      </div>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submit">建好,进入会议</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'

const router = useRouter()
const list = ref([])
const loading = ref(false)
const loadError = ref('')
const createOpen = ref(false)
const creating = ref(false)
const form = ref({ title: '', scheduledAt: '', location: '', participants: '', agenda: '' })

const STATUS = { DRAFT: '未开始', LIVE: '进行中', PAUSED: '已暂停', ENDED: '已结束' }
const SUMMARY = { NONE: '未生成纪要', STALE: '纪要待更新', PARTIAL: '速记版纪要', READY: '纪要已生成' }
const SRC = { BROWSER: '语音识别', MANUAL: '手工速记', IMPORT: '转写稿导入', PUSH: '外部推送' }

const statusLabel = (s) => STATUS[s] || s
const statusClass = (s) => ({ DRAFT: 'plan', LIVE: 'live', PAUSED: 'warn', ENDED: 'done' }[s] || '')
const summaryLabel = (s) => SUMMARY[s] || ''
const summaryClass = (s) => ({ READY: 'ok', PARTIAL: 'warn', STALE: 'warn' }[s] || '')
const sourceLabel = (s) => SRC[s] || '手工速记'

function minutes(sec) {
  if (!sec) return '未计时'
  const m = Math.round(sec / 60)
  if (m < 60) return `${m || 1} 分钟`
  return `${Math.floor(m / 60)} 小时${m % 60 ? ' ' + (m % 60) + ' 分钟' : ''}`
}

function fmtDate(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}月${d.getDate()}日 ${p(d.getHours())}:${p(d.getMinutes())}`
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    list.value = await api.meetings()
  } catch (e) {
    loadError.value = '会议列表没读出来:' + (e.response?.data?.message || e.message)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  const now = new Date()
  const p = (n) => String(n).padStart(2, '0')
  form.value = {
    title: '',
    scheduledAt: `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}T${p(now.getHours())}:${p(now.getMinutes())}`,
    location: '', participants: '', agenda: ''
  }
  createOpen.value = true
}

async function submit() {
  if (!form.value.title.trim()) return
  creating.value = true
  try {
    const r = await api.createMeeting(form.value)
    createOpen.value = false
    router.push(`/meetings/${r.id}`)
  } catch (e) {
    loadError.value = '建会议失败:' + (e.response?.data?.message || e.message)
  } finally {
    creating.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 22px 26px 60px; max-width: 1180px; margin: 0 auto; }
.head { display: flex; align-items: flex-start; gap: 24px; margin-bottom: 18px; }
.head h1 { font-family: var(--font-serif); font-size: 22px; font-weight: 400; letter-spacing: .14em; margin: 0 0 8px; }
.sub { font-size: 12.5px; color: var(--text-dim); margin: 0; max-width: 660px; line-height: 1.85; }
.sub b { color: var(--glow-cyan); font-weight: 400; }
button.primary.big { margin-left: auto; font-size: 13px; padding: 8px 18px; }

.err { display: flex; align-items: center; gap: 12px; padding: 12px 14px; margin-bottom: 14px; border-radius: 9px; background: rgba(var(--rgb-error), .08); border: 1px solid rgba(var(--rgb-error), .3); color: var(--st-error); font-size: 12.5px; }
.err p { margin: 0; }

.howto { padding: 18px 20px; border: 1px solid var(--border-hair); border-radius: 12px; background: var(--tint-soft); margin-bottom: 18px; }
.howto h2 { margin: 0 0 14px; font-family: var(--font-serif); font-size: 15px; font-weight: 400; letter-spacing: .1em; color: var(--text-moon); }
.how-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.how { display: flex; flex-direction: column; gap: 6px; }
.how b { font-size: 12.5px; color: var(--glow-cyan); font-weight: 400; }
.how span { font-size: 12px; color: var(--text-dim); line-height: 1.8; }
.how-foot { margin: 14px 0 0; font-size: 11.5px; color: var(--text-faint); line-height: 1.7; }

.list { display: flex; flex-direction: column; gap: 10px; }
.card { padding: 14px 16px; border: 1px solid var(--border-hair); border-radius: 10px; background: var(--card-flat); cursor: pointer; transition: .18s; }
.card:hover { border-color: var(--glow-cyan-dim); background: var(--card-flat-hi); }
.card-top { display: flex; align-items: center; gap: 9px; }
.card-top h3 { margin: 0; font-size: 14px; font-weight: 400; color: var(--text-moon); letter-spacing: .04em; }
.dot { width: 6px; height: 6px; border-radius: 50%; background: var(--st-idle); }
.dot.live { background: var(--st-running); box-shadow: 0 0 8px var(--glow-cyan-dim); animation: breathe 2.4s infinite; }
.dot.done { background: var(--st-ok); }
.dot.warn { background: var(--st-warn); }
.dot.plan { background: var(--st-plan); }
@keyframes breathe { 0%,100% { opacity: .45 } 50% { opacity: 1 } }
.spacer { flex: 1; }
.pill { font-size: 10.5px; padding: 1px 7px; border-radius: 999px; border: 1px solid var(--border-hair); color: var(--text-faint); }
.pill.live { color: var(--glow-cyan); border-color: var(--glow-cyan-dim); }
.pill.warn { color: var(--st-warn); border-color: rgba(var(--rgb-warn), .35); }
.pill.done { color: var(--st-ok); border-color: rgba(var(--rgb-ok), .3); }
.pill.plan { color: var(--st-plan); border-color: rgba(var(--rgb-plan), .3); }
.when { font-family: var(--font-mono); font-size: 11px; color: var(--text-faint); }
.card-meta { display: flex; gap: 14px; margin-top: 9px; font-size: 11.5px; color: var(--text-dim); }
.card-meta .sum.ok { color: var(--st-ok); }
.card-meta .sum.warn { color: var(--st-warn); }
.card-meta .src { margin-left: auto; color: var(--text-faint); }
.who { margin: 8px 0 0; font-size: 11.5px; color: var(--text-faint); }

.empty { padding: 40px 0; text-align: center; color: var(--text-dim); font-size: 13px; }
.empty .hint { font-size: 12px; color: var(--text-faint); margin-top: 8px; }

.form { display: flex; flex-direction: column; gap: 6px; }
.form label { font-size: 11.5px; color: var(--text-faint); margin-top: 8px; letter-spacing: .04em; }
.form label i { color: var(--st-error); font-style: normal; }
.form input, .form textarea { width: 100%; background: var(--field-bg); border: 1px solid var(--border-hair-strong); border-radius: 8px; color: var(--text-moon); font-size: 12.5px; padding: 8px 10px; font-family: inherit; }
.form input:focus, .form textarea:focus { outline: none; border-color: var(--glow-cyan-dim); }
.form textarea { resize: vertical; }

button.primary, button.ghost, button.danger { font-size: 12px; padding: 5px 13px; border-radius: 7px; cursor: pointer; border: 1px solid var(--border-hair-strong); background: none; color: var(--text-dim); transition: .16s; }
button.primary { background: var(--rgb-btn); border-color: transparent; color: var(--rgb-on-btn); }
button.primary:hover:not(:disabled) { background: var(--rgb-btn-hi); }
button.ghost:hover:not(:disabled) { color: var(--text-moon); border-color: var(--glow-cyan-dim); }
button:disabled { opacity: .4; cursor: not-allowed; }

@media (max-width: 900px) {
  .page { padding: 16px 14px 50px; }
  .head { flex-direction: column; gap: 12px; }
  .how-grid { grid-template-columns: 1fr; }
  .card-meta { flex-wrap: wrap; gap: 10px; }
}
</style>
