<template>
  <div class="shell">
    <div class="ink-canvas"></div>
    <!-- 特效层:极光漂移 + 流星 -->
    <div class="fx-layer">
      <div class="aurora aurora-a"></div>
      <div class="aurora aurora-b"></div>
      <span class="meteor m1"></span>
      <span class="meteor m2"></span>
      <span class="meteor m3"></span>
    </div>
    <header class="topbar glass">
      <div class="brand" @click="$router.push('/')">
        <div class="logo-wrap">
          <svg class="logo" viewBox="0 0 32 32" width="26" height="26">
            <rect x="4" y="4" width="24" height="24" rx="7" fill="none"
                  stroke="var(--glow-cyan)" stroke-width="1.6" opacity=".9" />
            <circle cx="16" cy="16" r="2.6" fill="var(--glow-cyan)">
              <animate attributeName="opacity" values=".45;1;.45" dur="3.2s"
                       repeatCount="indefinite" calcMode="spline"
                       keySplines=".4 0 .2 1;.4 0 .2 1" />
            </circle>
          </svg>
          <span class="logo-ring"></span>
        </div>
        <span class="brand-name">空白格</span>
        <span class="brand-en">BLANKGRID</span>
      </div>
      <nav class="nav">
        <router-link to="/" class="nav-link" active-class="" exact-active-class="router-link-active">目标</router-link>
        <router-link to="/org" class="nav-link">组织</router-link>
        <router-link to="/board" class="nav-link">看板</router-link>
        <router-link to="/meetings" class="nav-link">会议</router-link>
        <router-link to="/artifacts" class="nav-link">成果</router-link>
        <router-link to="/approvals" class="nav-link">
          自主
          <!-- 待批单是岗位在后台任务里产生的,不点进设置页就永远看不见,所以角标挂在导航上 -->
          <i v-if="pendingApprovals > 0" class="nav-badge">{{ pendingApprovals }}</i>
        </router-link>
        <router-link to="/settings" class="nav-link">设置</router-link>
      </nav>
      <button class="play-btn" :class="{ busy: rpBusy }" :disabled="!replayReady"
              :title="replayTitle" @click="toggleReplay">
        <i>{{ rpBusy ? '■' : '▶' }}</i>{{ rpBusy ? rpLabel : '播放施工过程' }}
      </button>
      <div class="theme-switch" aria-label="主题">
        <button :class="{ on: theme === 'ink' }" @click="setTheme('ink')">墨夜</button>
        <button :class="{ on: theme === 'paper' }" @click="setTheme('paper')">宣纸</button>
        <button :class="{ on: theme === 'stitch' }" @click="setTheme('stitch')">素白</button>
      </div>
      <div class="corp">
        <span class="live-dot" :class="running > 0 ? 'is-run' : 'is-on'"></span>
        <span v-if="running > 0">施工中 <b>{{ running }}</b> 项</span>
        <span v-else>系统在线</span>
        <i class="sep"></i>
        <span>{{ activeEmployees }} 人在岗</span>
        <i class="sep"></i>
        <span class="clock">{{ clock }}</span>
      </div>
    </header>
    <div v-if="llmBlocked" class="llm-alert">
      <span class="alert-dot"></span>
      <b>大模型账户额度不足,生产线已暂停</b>
      <span class="alert-msg">{{ llmMessage }}</span>
      <router-link to="/settings" class="alert-go">去设置检查 →</router-link>
    </div>
    <!-- 组织页的读数带是页面的下边缘:它要贴到视口底,不能浮在 34px 的页面留白上 -->
    <main class="main" :class="{ 'main-flush': $route.name === 'org' }">
      <router-view v-slot="{ Component }">
        <transition name="rv" mode="out-in">
          <component :is="Component" :key="$route.fullPath" />
        </transition>
      </router-view>
    </main>

    <!-- 口令登录 -->
    <el-dialog v-model="authOpen" title="空白格 · 访问验证" width="360px" :close-on-click-modal="false">
      <el-input v-model="password" type="password" placeholder="请输入访问口令" show-password
                @keyup.enter="doLogin" />
      <p class="auth-hint" v-if="authError">{{ authError }}</p>
      <template #footer>
        <el-button type="primary" :loading="authLoading" @click="doLogin">进入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { connectWs, bus, login, api, authToken } from './api'
import { motionOk } from './utils/motion'

const route = useRoute()

const authOpen = ref(false)
const password = ref('')
const authError = ref('')
const authLoading = ref(false)
const running = ref(0)
const activeEmployees = ref(0)
// 账户欠费时后端会停掉调度,这里把原因顶在页面最上面 —— 否则看起来像"系统不动了"
const llmBlocked = ref(false)
const llmMessage = ref('')
const pendingApprovals = ref(0)
const clock = ref('')
// 认得的主题才接收,认不得的一律回墨夜 —— 免得 localStorage 里留着一个已删除的主题名,页面永远白屏
const KNOWN_THEMES = ['ink', 'paper', 'stitch']
const theme = ref(KNOWN_THEMES.includes(document.documentElement.dataset.theme)
  ? document.documentElement.dataset.theme : 'ink')
// 链接里能带主题(?theme=stitch):发给别人看截图、演示时,不用先教他去点哪个按钮
const qTheme = new URLSearchParams(location.search).get('theme')
if (qTheme && KNOWN_THEMES.includes(qTheme)) {
  theme.value = qTheme
  document.documentElement.dataset.theme = qTheme
}
const rpBusy = ref(false)
const rpLabel = ref('')
const replayReady = computed(() => /^\/goals\/[^/]+/.test(route.path) && motionOk())
const replayTitle = computed(() => {
  if (!/^\/goals\/[^/]+/.test(route.path)) return '进入项目详情页后可重放这条目标的施工过程'
  if (!motionOk()) return '已按系统偏好关闭动效(减少动态效果)'
  return '重放目标从拆解到归档的全过程,哪一步在亮就是当时卡在哪'
})
function toggleReplay() {
  if (!replayReady.value) return
  bus.emit({ type: 'ui-replay-toggle' })
}
// 离开项目详情页时按钮不该继续显示「施工中」
watch(() => route.path, p => {
  if (!/^\/goals\/[^/]+/.test(p)) { rpBusy.value = false; rpLabel.value = '' }
})
function setTheme(t) {
  theme.value = t
  document.documentElement.dataset.theme = t
  try { localStorage.setItem('bg-theme', t) } catch (e) { /* 隐私模式下只是记不住,不该拦人 */ }
}
let offAuth = null
let offStatus = null
let clockTimer = null
let statsTimer = null
let statsPoll = null

async function loadTopStats() {
  try {
    const s = await api.stats()
    running.value = s.running || 0
    activeEmployees.value = s.activeEmployees || s.totalEmployees || 0
    llmBlocked.value = !!s.llmBlocked
    llmMessage.value = s.llmMessage || ''
  } catch (e) { /* 口令未过时静默,登录框会负责提醒 */ }
  // 待批数单独问:它变化得比统计频繁,而且只有这个数字要出现在导航上
  try {
    const c = await api.toolApprovalCount()
    pendingApprovals.value = c.pending || 0
  } catch (e) { /* 忽略 */ }
}
function tickClock() {
  const d = new Date()
  clock.value = [d.getHours(), d.getMinutes(), d.getSeconds()]
    .map(n => String(n).padStart(2, '0')).join(':')
}

onMounted(async () => {
  connectWs()
  offAuth = bus.on(evt => {
    if (evt.type === 'auth-required') { authOpen.value = true; password.value = '' }
    if (evt.type === 'ui-replay-state') { rpBusy.value = !!evt.busy; rpLabel.value = evt.label || '' }
  })
  offStatus = bus.on(evt => {
    // 审批状态一变就立刻刷角标,别等 30 秒轮询
    if (evt.type === 'tool-approval') pendingApprovals.value = evt.pending || 0
    if (['task-update', 'goal-update', 'goal-completed', 'employee-status', 'ceo-cycle'].includes(evt.type)) {
      clearTimeout(statsTimer)
      statsTimer = setTimeout(loadTopStats, 420)
    }
  })
  tickClock()
  clockTimer = setInterval(tickClock, 1000)
  loadTopStats()
  // 生产线停住时不会再有事件推过来,得自己轮询,不然横幅永远转不过状态
  statsPoll = setInterval(loadTopStats, 15000)
  // 探测是否需要登录
  try { await api.opsFeed() } catch (e) { /* 401 由拦截器弹窗 */ }
})
onBeforeUnmount(() => {
  offAuth && offAuth()
  offStatus && offStatus()
  clearInterval(clockTimer)
  clearInterval(statsPoll)
  clearTimeout(statsTimer)
})

async function doLogin() {
  if (!password.value) return
  authLoading.value = true
  authError.value = ''
  try {
    await login(password.value)
    authOpen.value = false
    location.reload()
  } catch (e) {
    authError.value = e.response?.data?.message || '口令不对'
  } finally { authLoading.value = false }
}
</script>

<style scoped>
.shell { position: relative; z-index: 1; min-height: 100vh; display: flex; flex-direction: column; }

/* ===== 特效层:极光 + 流星 ===== */
.fx-layer { position: fixed; inset: 0; z-index: 0; pointer-events: none; overflow: hidden; }
/* 极光是「底噪」,不是「光效」。原来 0.5 的存在感会透过半透明卡面,
   把整页染成一层灰绿;收掉三成之后,青色就重新变成信号 ——
   只有正在跑的东西才发光。 */
.aurora {
  position: absolute; width: 720px; height: 720px; border-radius: 50%;
  filter: blur(90px); opacity: calc(.36 * var(--fx-a)); will-change: transform;
}
.aurora-a {
  left: -200px; top: -260px;
  background: radial-gradient(circle, rgba(var(--rgb-glow), .14), transparent 65%);
  animation: driftA 46s ease-in-out infinite alternate;
}
.aurora-b {
  right: -240px; bottom: -300px;
  background: radial-gradient(circle, rgba(var(--rgb-plan), .11), transparent 65%);
  animation: driftB 58s ease-in-out infinite alternate;
}
@keyframes driftA {
  0% { transform: translate(0, 0) scale(1); }
  100% { transform: translate(340px, 180px) scale(1.25); }
}
@keyframes driftB {
  0% { transform: translate(0, 0) scale(1.1); }
  100% { transform: translate(-300px, -160px) scale(.9); }
}
.meteor {
  position: absolute; width: 150px; height: 1.5px; border-radius: 999px;
  background: linear-gradient(90deg, transparent, rgba(var(--rgb-glow), var(--meteor-a)), transparent);
  opacity: 0; transform: rotate(-24deg); will-change: transform, opacity;
}
.m1 { top: 14%; left: 8%; animation: shoot 9s ease-in 3s infinite; }
.m2 { top: 30%; left: 55%; animation: shoot 12s ease-in 6.5s infinite; }
.m3 { top: 8%;  left: 70%; animation: shoot 15s ease-in 1.8s infinite; }
@keyframes shoot {
  0% { opacity: 0; transform: rotate(-24deg) translateX(-60px); }
  4% { opacity: .9; }
  14% { opacity: 0; transform: rotate(-24deg) translateX(560px); }
  100% { opacity: 0; transform: rotate(-24deg) translateX(560px); }
}

/* ===== 品牌 Logo 呼吸光环 ===== */
.logo-wrap { position: relative; display: grid; place-items: center; }
.logo { position: relative; z-index: 1; }
.logo-ring {
  position: absolute; width: 34px; height: 34px; border-radius: 50%;
  border: 1px solid rgba(var(--rgb-glow), .35);
  animation: ringPulse 3.2s var(--ease-quiet) infinite;
}
@keyframes ringPulse {
  0%, 100% { transform: scale(.85); opacity: .25; }
  50% { transform: scale(1.18); opacity: .8; }
}

.topbar {
  position: sticky; top: 14px; z-index: 20;
  margin: 14px 26px 0; padding: 13px 24px;
  display: flex; align-items: center; gap: 26px;
}
.brand { display: flex; align-items: center; gap: 13px; cursor: pointer; flex: none; white-space: nowrap; }
.brand-name { font-size: 19px; font-weight: 300; letter-spacing: .42em; margin-left: .1em; white-space: nowrap; }
.brand-en { font-size: 10px; letter-spacing: .34em; color: var(--text-faint); font-family: var(--font-mono); white-space: nowrap; }
/* 7 个板块 + 播放 + 主题 + 在线状态,在 1440 的屏上本来正好。
   flex-wrap 一开,挤不下的那一项不会缩,只会掉到第二行 —— 「设置」单独
   挂在下边,整个顶栏像被截断了。宁可挤一点,也不许折行。 */
.nav { display: flex; gap: 2px; flex-wrap: nowrap; }
.nav-link {
  position: relative; white-space: nowrap;
  color: var(--text-dim); text-decoration: none; font-size: 14px;
  letter-spacing: .13em; padding: 7px 13px; border-radius: 9px;
  transition: all .4s var(--ease-quiet);
}
.nav-link::after {
  content: ""; position: absolute; left: 50%; bottom: 2px; width: 0; height: 2px;
  border-radius: 2px; background: var(--glow-cyan);
  transform: translateX(-50%); transition: width .4s var(--ease-out);
  box-shadow: 0 0 8px rgba(var(--rgb-glow), .6);
}
.nav-link:hover { color: var(--text-moon); }
.nav-link.router-link-active {
  color: var(--glow-cyan);
  background: var(--glow-cyan-faint);
  /* 选中态原来带 14px 的字辉,在深底上会让「看板」两个字失去边缘。
     留一点点就够 —— 选中靠底色和下方那道短线,不靠发光。 */
  text-shadow: 0 0 10px rgba(var(--rgb-glow), .18);
}
.nav-link.router-link-active::after { width: 26px; }
/* 待批角标:它不是装饰,是"有东西在等你点头"的唯一提示 */
.nav-badge {
  position: absolute; top: 1px; right: 3px; font-style: normal;
  font-family: var(--font-mono); font-size: 9.5px; line-height: 1;
  padding: 2px 4px; border-radius: 7px; letter-spacing: 0;
  background: rgba(var(--rgb-warn), .92); color: #1a1206;
  box-shadow: 0 0 10px rgba(var(--rgb-warn), .45);
}
.corp {
  margin-left: 18px; display: flex; align-items: center; gap: 9px; white-space: nowrap;
  font-family: var(--font-mono); font-size: 10px; letter-spacing: .12em; color: var(--text-faint);
}
.play-btn {
  appearance: none; cursor: pointer; flex: none; white-space: nowrap;
  display: inline-flex; align-items: center; gap: 7px;
  font-family: var(--font-mono); font-size: 10.5px; letter-spacing: .14em;
  color: var(--glow-cyan); background: var(--glow-cyan-faint);
  border: 1px solid rgba(var(--rgb-glow), .32); border-radius: 999px; padding: 6px 14px;
  transition: color .3s var(--ease-quiet), background .3s var(--ease-quiet),
              border-color .3s var(--ease-quiet), box-shadow .3s var(--ease-quiet);
}
.play-btn i { font-style: normal; font-size: 8px; transform: translateY(-.5px) }
.play-btn:hover:not(:disabled) {
  border-color: rgba(var(--rgb-glow), .62); box-shadow: 0 0 18px rgba(var(--rgb-glow), .26);
}
.play-btn.busy {
  color: var(--st-warn); background: rgba(var(--rgb-warn), .07);
  border-color: rgba(var(--rgb-warn), .38); animation: playBusy 2.1s var(--ease-quiet) infinite;
}
.play-btn:disabled {
  color: var(--text-faint); background: transparent;
  border-color: var(--border-hair); cursor: not-allowed;
}
@keyframes playBusy { 0%, 100% { opacity: 1 } 50% { opacity: .68 } }
.theme-switch {  display: flex; gap: 2px; padding: 3px; flex: none; margin-left: auto;
  border: 1px solid var(--border-hair); border-radius: 999px; background: var(--tint-soft);
}
.theme-switch button {
  appearance: none; border: 0; background: transparent; cursor: pointer;
  font-family: var(--font-mono); font-size: 10px; letter-spacing: .16em;
  color: var(--text-faint); padding: 4px 12px; border-radius: 999px;
  transition: color .3s var(--ease-quiet), background .3s var(--ease-quiet);
}
.theme-switch button:hover { color: var(--text-dim); }
.theme-switch button.on { color: var(--glow-cyan); background: var(--glow-cyan-faint); }
.corp b { font-weight: 400; color: var(--warm); }
.corp .sep { width: 1px; height: 10px; background: var(--border-hair-strong); }
.corp .clock { font-variant-numeric: tabular-nums; }
.live-dot { width: 5px; height: 5px; border-radius: 50%; background: var(--text-faint); flex: none; }
.live-dot.is-on { background: var(--glow-cyan); box-shadow: 0 0 9px rgba(var(--rgb-glow), .5); }
.live-dot.is-run { background: var(--warm); box-shadow: 0 0 9px rgba(var(--rgb-warm), .5); animation: pulseDot 2.2s var(--ease-quiet) infinite; }
@keyframes pulseDot { 0%, 100% { opacity: 1; } 50% { opacity: .32; } }
.main { flex: 1; padding: 22px 26px 34px; position: relative; z-index: 1; }
/* 组织页自带一条通到页面底边的读数带,再留 34px 底白就变成"带子下面还有一块黑" */
.main.main-flush { padding-bottom: 0; }
/* 账户欠费横幅:不是装饰,是把"为什么不动了"顶到最显眼处 */
.llm-alert {
  margin: 10px 26px 0; padding: 9px 16px; display: flex; align-items: center; gap: 10px;
  border: 1px solid rgba(var(--rgb-warn, 210, 150, 60), .38);
  border-radius: 3px; background: rgba(var(--rgb-warn, 210, 150, 60), .07);
  font-size: 12px; letter-spacing: .04em; color: var(--text-dim);
  position: relative; z-index: 2;
}
.llm-alert b { font-weight: 400; color: var(--st-error); letter-spacing: .06em; }
.llm-alert .alert-msg { flex: 1; font-family: var(--font-mono); font-size: 11px; color: var(--text-faint); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.llm-alert .alert-go { color: var(--glow-cyan-dim); text-decoration: none; flex: none; }
.llm-alert .alert-go:hover { color: var(--glow-cyan); }
.alert-dot { width: 5px; height: 5px; border-radius: 50%; background: var(--st-error); box-shadow: 0 0 8px var(--st-error); animation: pulseDot 2.2s var(--ease-quiet) infinite; flex: none; }
.auth-hint { color: var(--st-error); font-size: 12px; margin: 10px 0 0; }
/* 顶栏是「品牌 + 导航 + 状态」三段,宽度先保导航完整;
   英文副标是唯一可以牺牲的装饰,窗口一窄就收掉 */
@media (max-width: 1380px) {
  .brand-en { display: none; }
}
@media (max-width: 1180px) {
  .topbar { gap: 16px; }
  .nav-link { padding: 7px 9px; letter-spacing: .06em; }
  .corp { margin-left: 4px; gap: 7px; }
}
@media (max-width: 760px) {
  .topbar { gap: 12px; padding: 10px 14px; margin: 10px 12px 0; flex-wrap: wrap; }
  .brand-name { font-size: 16px; letter-spacing: .2em; }
  .nav { gap: 2px; }
  .nav-link { padding: 6px 10px; letter-spacing: .1em; font-size: 13px; }
  .main { padding: 16px 12px 28px; }
}
@media (max-width: 900px) { .corp { display: none; } }
</style>
