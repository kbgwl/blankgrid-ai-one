<template>
  <div class="settings">
    <header class="set-head">
      <div>
        <div class="set-eyebrow">SETTINGS · WORKSPACE</div>
        <h1 class="set-title">把公司的规则,写清楚。</h1>
        <p class="set-sub">模型、质量门槛、访问与备份——四件事决定这家公司能跑多稳。改动不会自动生效,点保存才写入。</p>
      </div>
      <div class="head-actions">
        <span v-if="dirtyCount" class="dirty-note">{{ dirtyCount }} 项未保存</span>
        <span v-else-if="savedFlash" class="saved-stamp">已保存</span>
        <el-button type="primary" :loading="saving" :disabled="!loaded" @click="save">保存设置</el-button>
      </div>
    </header>

    <div v-if="loadError" class="empty glass">
      <div class="empty-mark">!</div>
      <p>{{ loadError }}</p>
      <el-button size="small" plain :loading="loading" @click="load">重试</el-button>
    </div>

    <div v-else class="set-grid">
      <nav class="set-nav">
        <button v-for="s in navItems" :key="s.id" class="nav-item" :class="{ on: s.id === activeSection }"
                @click="jump(s.id)">{{ s.label }}</button>
      </nav>

      <div class="set-main">
        <!-- ===== 模型服务 ===== -->
        <section id="models" class="set-card glass" :style="{ '--i': 0 }">
          <div class="set-card-head">
            <div>
              <h3>模型服务</h3>
              <p>OpenAI 兼容协议。密钥只存在本机 data/settings.json,不进数据库,也不会回显到页面。</p>
            </div>
            <span class="badge" :class="modelBadge.cls">{{ modelBadge.text }}</span>
          </div>
          <div class="set-body">
            <div v-if="!form.providers.length" class="row-none">还没有服务商。没有可用的大模型,CEO 拆解与部门执行都会停下来。</div>
            <div v-for="(p, i) in form.providers" :key="i" class="prov" :class="{ dirty: provDirty }">
              <div class="prov-top">
                <b class="prov-name">{{ p.name || '未命名服务商' }}</b>
                <span class="badge sm" :class="p.primary ? 'ok' : ''">{{ p.primary ? '主用' : '备用' }}</span>
                <span class="badge sm" :class="p.apiKey ? '' : 'warn'">{{ p.apiKey === '********' ? '密钥已保存' : (p.apiKey ? '密钥已填写' : '缺密钥') }}</span>
                <span class="probe" :class="'ps-' + probeState(i)">
                  {{ probeText(i) }}
                  <button class="probe-btn" :disabled="!p.baseUrl || probeState(i) === 'run'" @click="probe(i)">
                    {{ probeState(i) === 'run' ? '检测中' : '检测连通' }}
                  </button>
                </span>
              </div>
              <div class="prov-grid">
                <label class="pf"><span>名称</span><el-input v-model="p.name" placeholder="如:智谱 GLM" size="small" /></label>
                <label class="pf wide"><span>Base URL</span><el-input v-model="p.baseUrl" placeholder="https://open.bigmodel.cn/api/paas/v4" size="small" /></label>
                <label class="pf"><span>默认模型</span><el-input v-model="p.model" placeholder="如:glm-4.6" size="small" /></label>
                <label class="pf"><span>API Key</span><el-input v-model="p.apiKey" placeholder="留空则不修改" size="small" show-password /></label>
                <label class="pf check"><span>设为主用</span><el-switch v-model="p.primary" @change="onlyPrimary(i)" /></label>
                <button class="pf-remove" @click="removeProvider(i)">移除</button>
              </div>
            </div>
            <button class="add-row" @click="addProvider">+ 添加服务商</button>
          </div>
          <p class="tip">「检测连通」只从浏览器确认网关地址能否访问与往返耗时,不会带上密钥;密钥与模型是否真的有效,要等后端提供检测接口。</p>
        </section>

        <!-- ===== 质量与目标 ===== -->
        <section id="quality" class="set-card glass" :style="{ '--i': 1 }">
          <div class="set-card-head">
            <div>
              <h3>质量与目标</h3>
              <p>决定什么算「交付完成」,以及不合格时怎么返工。</p>
            </div>
            <span class="badge">门槛 {{ form.reviewThreshold }} 分</span>
          </div>
          <div class="set-body">
            <div class="set-row" :class="{ dirty: dirty('reviewThreshold') }">
              <div class="lbl"><b>质量门槛</b><span>低于该分数的成果自动返工,不会进入归档</span></div>
              <div class="ctl slider-ctl">
                <el-slider v-model="form.reviewThreshold" :min="60" :max="99" :step="1" :show-tooltip="false" class="th-slider" />
                <b class="ctl-num">{{ form.reviewThreshold }}</b>
              </div>
            </div>
            <div class="set-row" :class="{ dirty: dirty('maxReviewRounds') }">
              <div class="lbl"><b>单件最多返工轮次</b><span>用尽仍不达标会标记受阻并上报你拍板</span></div>
              <div class="ctl"><el-input-number v-model="form.maxReviewRounds" :min="1" :max="5" size="small" /></div>
            </div>
            <div class="set-row" :class="{ dirty: dirty('autoStartGoals') }">
              <div class="lbl"><b>下达目标后自动拆解</b><span>关闭后目标先进入待拆解,可在项目页手动开始</span></div>
              <div class="ctl"><el-switch v-model="form.autoStartGoals" /></div>
            </div>
            <div class="set-row" :class="{ dirty: dirty('ceoIntervalMinutes') }">
              <div class="lbl"><b>CEO 定时巡视</b><span>每 N 分钟检查阻塞、返工与待拍板事项,并写入动态</span></div>
              <div class="ctl stepper-ctl"><el-input-number v-model="form.ceoIntervalMinutes" :min="1" :max="720" size="small" /><i class="unit">分钟</i></div>
            </div>
          </div>
          <p class="tip">建议保持在 82–88:门槛过低会让成果失去区分度,过高会让返工吃掉预算。最近 30 天平均 <b>{{ avgScore === null ? '待后端返回' : (avgScore || '暂无已归档成果') }}</b>{{ avgScore ? ' 分' : '' }}。</p>
        </section>

        <!-- ===== 公司与权限 ===== -->
        <section id="company" class="set-card glass" :style="{ '--i': 2 }">
          <div class="set-card-head">
            <div>
              <h3>公司与权限</h3>
              <p>谁能打开这个页面,以及公司一天最多花多少。</p>
            </div>
            <span class="badge" :class="passwordSet ? 'ok' : ''">{{ passwordSet ? '访问口令已启用' : '未设访问口令' }}</span>
          </div>
          <div class="set-body">
            <div class="set-row" :class="{ dirty: dirty('password') }">
              <div class="lbl"><b>访问口令</b><span>设置后打开页面需要输入口令;留原样表示不修改</span></div>
              <div class="ctl wide-ctl">
                <el-input v-model="form.password" placeholder="留空或 ******** 表示不修改" size="small" show-password />
                <el-button size="small" text type="danger" @click="clearPassword">清除口令</el-button>
              </div>
            </div>
            <div class="set-row" :class="{ dirty: dirty('dailyTokenBudget') }">
              <div class="lbl"><b>每日 token 预算</b><span>到顶自动暂停 CEO 自动巡视,0 = 不限</span></div>
              <div class="ctl stepper-ctl"><el-input-number v-model="form.dailyTokenBudget" :min="0" :step="10000" size="small" controls-position="right" /></div>
            </div>
            <div class="set-row col" :class="{ dirty: dirty('knowledgeBase') }">
              <div class="lbl"><b>公司知识库 · 全员共享</b><span>这里的文字会附加到每一位 AI 的系统提示词,相当于全员入职培训</span></div>
              <el-input v-model="form.knowledgeBase" type="textarea" :rows="6" resize="none"
                        placeholder="公司背景、产品信息、行事准则、文风要求……" />
            </div>
          </div>
        </section>

        <!-- ===== 数据与备份 ===== -->
        <section id="data" class="set-card glass" :style="{ '--i': 3 }">
          <div class="set-card-head">
            <div><h3>数据与备份</h3><p>数据库与设置都在本机 data/ 目录。</p></div>
          </div>
          <div class="set-body">
            <div class="set-row">
              <div class="lbl"><b>立即备份</b><span>启动与每 6 小时自动备份,保留最近 7 份</span></div>
              <div class="ctl"><el-button size="small" :loading="backing" @click="doBackup">备份一次</el-button></div>
            </div>
          </div>
        </section>

        <!-- ===== 高级模式 ===== -->
        <section v-if="showAdvanced" id="advanced" class="set-card glass adv" :style="{ '--i': 4 }">
          <div class="set-card-head">
            <div><h3>高级模式</h3><p>直接作用于每一次调用的原始参数,改错会让所有产出一起变差。</p></div>
          </div>
          <div class="set-body">
            <div class="set-row" :class="{ dirty: dirty('concurrency') }">
              <div class="lbl"><b>并发执行数</b><span>同时开工的任务条数,受服务商限流约束</span></div>
              <div class="ctl"><el-input-number v-model="form.concurrency" :min="1" :max="8" size="small" /></div>
            </div>
            <div class="set-row col" :class="{ dirty: dirty('qualityBar') }">
              <div class="lbl"><b>默认质量底线</b><span>写入每一个拆解与执行提示词</span></div>
              <el-input v-model="form.qualityBar" type="textarea" :rows="5" resize="none"
                        placeholder="交付物必须能直接使用,而不是解释你准备怎么做……" />
            </div>
          </div>
        </section>

        <button class="adv-toggle" @click="showAdvanced = !showAdvanced">
          <i>{{ showAdvanced ? '▾' : '▸' }}</i>{{ showAdvanced ? '收起高级模式' : '展开高级模式' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { motionOk } from '../utils/motion'
import { api } from '../api'

const defaults = {
  providers: [], knowledgeBase: '', concurrency: 4, dailyTokenBudget: 0,
  ceoIntervalMinutes: 15, password: '', qualityBar: '',
  reviewThreshold: 85, maxReviewRounds: 2, autoStartGoals: true
}

const form = ref({ ...defaults })
const base = ref(null)
const loaded = ref(false)
const loading = ref(false)
const saving = ref(false)
const backing = ref(false)
const loadError = ref('')
const savedFlash = ref(false)
const hasProvider = ref(null)
const avgScore = ref(null)
const showAdvanced = ref(false)
const activeSection = ref('models')
const probes = reactive({})

const navItems = [
  { id: 'models', label: '模型服务' },
  { id: 'quality', label: '质量与目标' },
  { id: 'company', label: '公司与权限' },
  { id: 'data', label: '数据与备份' }
]

const dirtyKeys = computed(() => {
  if (!base.value) return []
  const b = JSON.parse(base.value)
  const f = form.value
  return Object.keys(f).filter(k => JSON.stringify(b[k]) !== JSON.stringify(f[k]))
})
function dirty(k) { return dirtyKeys.value.includes(k) }
const dirtyCount = computed(() => dirtyKeys.value.length)
const provDirty = computed(() => dirty('providers'))
const passwordSet = computed(() => !!form.value.password && form.value.password !== '********')

const modelBadge = computed(() => {
  if (hasProvider.value === true) return { text: '模型服务已配置', cls: 'ok' }
  if (hasProvider.value === false) return { text: '尚无可用大模型', cls: 'err' }
  return { text: '配置状态待后端返回', cls: 'warn' }
})

function probeState(i) { return probes[i] ? probes[i].state : 'idle' }
function probeText(i) {
  const p = probes[i]
  if (!p) return '未检测'
  if (p.state === 'run') return '检测中'
  return p.state === 'ok' ? `网关可达 · ${p.ms}ms` : `网关无响应 · ${p.ms}ms`
}

/* 只探测网关可达性与往返耗时:no-cors 不发送任何凭据,也读不到响应体 */
async function probe(i) {
  const url = String(form.value.providers[i]?.baseUrl || '').replace(/\/+$/, '')
  if (!url) return
  probes[i] = { state: 'run' }
  const t0 = performance.now()
  const ctl = new AbortController()
  const timer = setTimeout(() => ctl.abort(), 6000)
  try {
    await fetch(url + '/models', { mode: 'no-cors', signal: ctl.signal })
    probes[i] = { state: 'ok', ms: Math.round(performance.now() - t0) }
  } catch (e) {
    probes[i] = { state: 'bad', ms: Math.round(performance.now() - t0) }
  } finally {
    clearTimeout(timer)
  }
}

function onlyPrimary(i) {
  if (!form.value.providers[i].primary) return
  form.value.providers.forEach((p, n) => { if (n !== i) p.primary = false })
}
function addProvider() {
  form.value.providers.push({ name: '', baseUrl: '', apiKey: '', model: '', primary: !form.value.providers.length })
}
function removeProvider(i) { form.value.providers.splice(i, 1) }

function jump(id) {
  activeSection.value = id
  const el = document.getElementById(id)
  if (el) el.scrollIntoView({ behavior: motionOk() ? 'smooth' : 'auto', block: 'start' })
}

let observer = null
function observeSections() {
  const secs = navItems.map(s => document.getElementById(s.id)).filter(Boolean)
  if (!secs.length || typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver(entries => {
    const hit = entries.filter(e => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0]
    if (hit) activeSection.value = hit.target.id
  }, { rootMargin: '-96px 0px -60% 0px', threshold: 0 })
  secs.forEach(s => observer.observe(s))
}

function snapshot() { base.value = JSON.stringify(form.value) }

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const s = await api.settings()
    // 后端只回它认识的字段,缺的保留默认值而不是把表单清空
    form.value = { ...defaults, ...s }
    snapshot()
    loaded.value = true
  } catch (e) {
    loadError.value = e.code === 'ECONNABORTED' || e.code === 'ETIMEDOUT'
      ? '后端长时间没有响应,设置读不出来'
      : '设置载入失败'
  } finally { loading.value = false }
  try {
    const [st, arts] = await Promise.all([api.stats(), api.artifacts({ status: 'APPROVED' })])
    hasProvider.value = st.hasProvider === undefined ? null : !!st.hasProvider
    const scored = (arts || []).filter(a => a.score)
    avgScore.value = scored.length ? Math.round(scored.reduce((n, a) => n + a.score, 0) / scored.length) : 0
  } catch (e) { /* 统计数据只是让提示更具体,拿不到就留白 */ }
  await nextTick()
  observeSections()
}

async function save() {
  saving.value = true
  try {
    const r = await api.saveSettings(form.value)
    form.value = { ...defaults, ...r }
    snapshot()
    savedFlash.value = true
    setTimeout(() => { savedFlash.value = false }, 1800)
    ElMessage({ message: '已保存', type: 'success', plain: true })
  } catch (e) {
    ElMessage.error('保存失败')
  } finally { saving.value = false }
}

async function doBackup() {
  backing.value = true
  try {
    const r = await api.backupNow()
    ElMessage({ message: r.ok ? ('已备份:' + r.file) : '备份失败', type: r.ok ? 'success' : 'error', plain: true })
  } finally { backing.value = false }
}

async function clearPassword() {
  form.value.password = '__CLEAR__'
  await save()
  form.value.password = ''
}

onMounted(load)
onBeforeUnmount(() => observer && observer.disconnect())
</script>

<style scoped>
.settings { max-width: 1180px; margin: 0 auto; display: flex; flex-direction: column; gap: 20px; padding-bottom: 40px; }
.set-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 28px; padding: 18px 0 2px; flex-wrap: wrap; }
.set-eyebrow { font-family: var(--font-mono); font-size: 10px; letter-spacing: .3em; color: var(--text-faint); }
.set-title { margin: 13px 0 0; font-size: clamp(25px, 3vw, 36px); font-weight: 200; letter-spacing: .06em; }
.set-sub { margin: 9px 0 0; font-size: 12px; color: var(--text-dim); letter-spacing: .05em; max-width: 560px; }
.head-actions { display: flex; align-items: center; gap: 14px; }
.dirty-note { font-family: var(--font-mono); font-size: 10.5px; letter-spacing: .1em; color: var(--st-warn); animation: riseIn .4s var(--ease-out) both; }
.saved-stamp {
  font-family: var(--font-mono); font-size: 10.5px; letter-spacing: .18em; color: var(--st-ok);
  border: 1px solid rgba(var(--rgb-ok), .35); background: rgba(var(--rgb-ok), .1);
  padding: 3px 10px; border-radius: 999px; animation: stampIn .5s cubic-bezier(.34, 1.56, .64, 1) both;
}
@keyframes stampIn { from { opacity: 0; transform: scale(.86); } to { opacity: 1; transform: none; } }

.empty { padding: 60px 20px; text-align: center; color: var(--text-faint); letter-spacing: .12em; font-size: 12.5px; }
.empty-mark {
  font-size: 32px; font-weight: 200; color: rgba(var(--rgb-ink), .1);
  border: 1px dashed var(--border-hair-strong); width: 72px; height: 72px; border-radius: 18px;
  display: grid; place-items: center; margin: 0 auto 16px;
}
.empty p { margin: 0 0 16px; }

.set-grid { display: grid; grid-template-columns: 178px minmax(0, 1fr); gap: 26px; align-items: start; }

/* ===== 左侧分组导航 ===== */
.set-nav {
  position: sticky; top: 92px; display: flex; flex-direction: column; gap: 2px;
  border-left: 1px solid var(--border-hair); padding: 4px 0;
}
.nav-item {
  position: relative; background: none; border: none; text-align: left; cursor: pointer;
  padding: 9px 14px; border-radius: 0 8px 8px 0;
  font-family: var(--font-sans); font-size: 12px; letter-spacing: .06em; color: var(--text-faint);
  transition: color .35s var(--ease-quiet), background .35s var(--ease-quiet);
}
.nav-item:hover { color: var(--text-dim); background: var(--tint-soft); }
.nav-item.on { color: var(--glow-cyan); background: var(--glow-cyan-faint); }
.nav-item.on::before {
  content: ""; position: absolute; left: -1px; top: 8px; bottom: 8px; width: 2px; border-radius: 2px;
  background: var(--glow-cyan); box-shadow: 0 0 9px rgba(var(--rgb-glow), .6);
  animation: navRail .4s var(--ease-out) both;
}
@keyframes navRail { from { transform: scaleY(.15); opacity: 0; } to { transform: none; opacity: 1; } }

.set-main { display: flex; flex-direction: column; gap: 18px; }

/* ===== 卡片 ===== */
.set-card {
  padding: 22px 26px 20px; scroll-margin-top: 92px;
  animation: riseIn .6s var(--ease-out) both; animation-delay: calc(var(--i, 0) * 90ms);
  transition: border-color .4s var(--ease-quiet);
}
.set-card:hover { border-color: rgba(var(--rgb-glow), .16); }
.set-card.adv { border-color: rgba(var(--rgb-warn), .22); }
.set-card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding-bottom: 14px; border-bottom: 1px solid var(--border-hair); }
.set-card-head h3 { margin: 0; font-size: 15px; font-weight: 300; letter-spacing: .22em; color: var(--text-moon); }
.set-card-head p { margin: 7px 0 0; font-size: 11.5px; line-height: 1.7; color: var(--text-faint); max-width: 520px; }
.badge {
  flex: none; font-family: var(--font-mono); font-size: 9.5px; letter-spacing: .12em; color: var(--text-dim);
  border: 1px solid var(--border-hair); background: var(--chip-bg); padding: 3px 10px; border-radius: 999px;
}
.badge.ok { color: var(--st-ok); border-color: rgba(var(--rgb-ok), .32); background: rgba(var(--rgb-ok), .1); }
.badge.err { color: var(--st-error); border-color: rgba(var(--rgb-error), .34); background: rgba(var(--rgb-error), .09); }
.badge.warn { color: var(--st-warn); border-color: rgba(var(--rgb-warn), .32); background: rgba(var(--rgb-warn), .09); }
.badge.sm { font-size: 9px; padding: 2px 8px; }

.set-body { display: flex; flex-direction: column; padding-top: 6px; }
.set-row {
  display: flex; align-items: center; justify-content: space-between; gap: 22px;
  padding: 13px 0; border-bottom: 1px solid var(--border-hair); position: relative;
}
.set-row:last-child { border-bottom: 0; }
.set-row.col { flex-direction: column; align-items: stretch; gap: 10px; }
.set-row.col .lbl { margin-bottom: 2px; }
.lbl b { display: block; font-size: 12.5px; font-weight: 400; letter-spacing: .1em; color: var(--text-moon); }
.lbl span { display: block; margin-top: 4px; font-size: 11px; line-height: 1.7; color: var(--text-faint); }
.ctl { display: flex; align-items: center; gap: 10px; flex: none; }
.wide-ctl { flex: 1; max-width: 380px; }
.wide-ctl .el-input { width: 260px; }
.stepper-ctl .unit { font-style: normal; font-family: var(--font-mono); font-size: 10px; color: var(--text-faint); }
.slider-ctl { flex: 1; max-width: 420px; gap: 18px; }
.ctl-num { font-family: var(--font-mono); font-size: 19px; font-weight: 200; color: var(--glow-cyan); width: 34px; text-align: right; }
.th-slider { flex: 1; }
.row-none { padding: 14px 0; font-size: 11.5px; color: var(--text-faint); letter-spacing: .06em; }
.tip { margin: 14px 0 0; padding-top: 12px; border-top: 1px dashed var(--border-hair); font-size: 11px; line-height: 1.8; color: var(--text-faint); }
.tip b { font-family: var(--font-mono); font-weight: 400; color: var(--text-dim); }
/* 改过还没保存的行:左侧一个亮点 */
.set-row.dirty::after, .prov.dirty::after {
  content: ""; position: absolute; left: -12px; top: 50%; width: 5px; height: 5px; border-radius: 50%;
  margin-top: -2px; background: var(--st-warn); box-shadow: 0 0 8px rgba(var(--rgb-warn), .6);
  animation: dotPop .45s cubic-bezier(.34, 1.56, .64, 1) both;
}
@keyframes dotPop { from { transform: scale(0); } to { transform: scale(1); } }

/* ===== 服务商 ===== */
.prov {
  position: relative; border: 1px solid var(--border-hair); border-radius: 11px;
  background: var(--tint-soft); padding: 13px 15px; margin: 12px 0;
  transition: border-color .35s var(--ease-quiet);
}
.prov:hover { border-color: rgba(var(--rgb-glow), .24); }
.prov-top { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.prov-name { font-size: 12.5px; font-weight: 400; letter-spacing: .1em; color: var(--text-moon); }
.prov-top .badge + .badge { margin-left: 0; }
.probe { margin-left: auto; display: flex; align-items: center; gap: 9px; font-family: var(--font-mono); font-size: 10px; color: var(--text-faint); }
.ps-ok { color: var(--st-ok); }
.ps-bad { color: var(--st-error); }
.ps-run { color: var(--glow-cyan); animation: breathe 1.6s ease-in-out infinite; }
@keyframes breathe { 0%, 100% { opacity: .45; } 50% { opacity: 1; } }
.probe-btn {
  background: none; border: 1px solid var(--border-hair); color: var(--text-dim); cursor: pointer;
  font-family: var(--font-sans); font-size: 10px; letter-spacing: .1em; padding: 3px 9px; border-radius: 7px;
  transition: all .3s var(--ease-quiet);
}
.probe-btn:hover:not(:disabled) { color: var(--glow-cyan); border-color: rgba(var(--rgb-glow), .4); }
.probe-btn:disabled { opacity: .5; cursor: not-allowed; }
.prov-grid { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 12px; }
.pf { display: flex; flex-direction: column; gap: 5px; width: 190px; }
.pf.wide { flex: 1; min-width: 260px; }
.pf.check { width: auto; justify-content: flex-end; }
.pf > span { font-size: 10.5px; letter-spacing: .18em; color: var(--text-dim); }
.pf-remove {
  align-self: flex-end; background: none; border: none; cursor: pointer; padding: 4px 2px;
  font-size: 11px; letter-spacing: .1em; color: var(--text-faint); transition: color .3s var(--ease-quiet);
}
.pf-remove:hover { color: var(--st-error); }
.add-row {
  align-self: flex-start; background: none; border: 1px dashed var(--border-hair-strong); cursor: pointer;
  color: var(--glow-cyan-dim); font-size: 11px; letter-spacing: .16em; padding: 7px 14px; border-radius: 9px;
  margin-top: 4px; transition: all .35s var(--ease-quiet);
}
.add-row:hover { border-color: rgba(var(--rgb-glow), .45); color: var(--glow-cyan); }

.adv-toggle {
  align-self: flex-start; background: none; border: none; cursor: pointer; display: flex; align-items: center; gap: 8px;
  font-family: var(--font-mono); font-size: 10.5px; letter-spacing: .2em; color: var(--text-faint);
  padding: 2px 0; transition: color .3s var(--ease-quiet);
}
.adv-toggle:hover { color: var(--glow-cyan); }
.adv-toggle i { font-style: normal; }

@media (max-width: 900px) {
  .set-grid { grid-template-columns: 1fr; gap: 12px; }
  .set-nav { position: static; flex-direction: row; overflow-x: auto; border-left: 0; gap: 6px; }
  .nav-item { border-radius: 8px; white-space: nowrap; }
  .nav-item.on::before { left: 8px; right: 8px; top: auto; bottom: 2px; width: auto; height: 2px; }
  .set-row { flex-direction: column; align-items: stretch; }
  .ctl { margin-top: 8px; }
  .slider-ctl, .wide-ctl { max-width: none; }
  .wide-ctl .el-input { width: 100%; }
  .probe { margin-left: 0; width: 100%; justify-content: flex-end; }
}
</style>
