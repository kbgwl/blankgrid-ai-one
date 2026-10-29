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
                <label class="pf"><span>默认模型
                  <button class="fetch-btn" :disabled="!p.baseUrl || !p.name || fetching === i"
                          :title="p.name ? '从服务商拉取可用模型' : '先保存一次,拿到名称后再拉取'"
                          @click="fetchModels(i)">{{ fetching === i ? '拉取中' : '拉取' }}</button>
                </span>
                  <el-select v-model="p.model" size="small" filterable allow-create default-first-option
                             :loading="fetching === i"
                             :placeholder="p.baseUrl ? '点「拉取」选模型,也可直接手填' : '先填 Base URL'">
                    <el-option v-for="m in (modelOptions[p.baseUrl] || [])" :key="m" :label="m" :value="m" />
                  </el-select>
                </label>
                <label class="pf"><span>API Key</span><el-input v-model="p.apiKey" placeholder="留空则不修改" size="small" show-password /></label>
                <label class="pf check"><span>设为主用</span><el-switch v-model="p.primary" @change="onlyPrimary(i)" /></label>
                <button class="pf-remove" @click="removeProvider(i)">移除</button>
              </div>
            </div>
            <button class="add-row" @click="addProvider">+ 添加服务商</button>

            <!-- CEO 决策模型:巡视拍板与目标拆解走这个槽位,和岗位执行分开 -->
            <div class="decision-slot" :class="{ dirty: dirty('decisionProvider') || dirty('decisionModel') }">
              <div class="ds-head">
                <b>CEO 决策模型</b>
                <span class="ds-sub">只影响 CEO 的巡视拍板与目标拆解;岗位执行仍用主用服务商</span>
                <span class="badge sm" :class="dsState.ok === null ? '' : (dsState.ok ? 'ok' : 'err')">{{ dsState.label }}</span>
              </div>
              <div class="prov-grid">
                <label class="pf"><span>服务商</span>
                  <el-select v-model="form.decisionProvider" size="small" placeholder="跟随主用">
                    <el-option label="跟随主用服务商" value="" />
                    <el-option v-for="(p, i) in form.providers" :key="i" :label="p.name || '未命名服务商'" :value="p.name" />
                  </el-select>
                </label>
                <label class="pf"><span>模型名
                  <button class="fetch-btn" :disabled="!dsResolved || !dsResolved.baseUrl || !dsResolved.name || dsFetching"
                          title="从该服务商拉取可用模型"
                          @click="fetchDecisionModels">{{ dsFetching ? '拉取中' : '拉取' }}</button>
                </span>
                  <el-select v-model="form.decisionModel" size="small" filterable allow-create clearable
                             :placeholder="decisionModelHint">
                    <el-option v-for="m in (modelOptions[(dsResolved || {}).baseUrl] || [])" :key="m" :label="m" :value="m" />
                  </el-select>
                </label>
                <label class="pf check"><span></span>
                  <el-button size="small" plain :loading="dsState.run" @click="testDecision">测一次</el-button>
                </label>
              </div>
              <p class="ds-now">当前生效:<b>{{ decisionEffective }}</b>
                <span v-if="dsState.detail" class="ds-detail" :class="dsState.ok ? 'ok' : 'err'">{{ dsState.detail }}</span>
              </p>
            </div>

            <!-- 省钱模式:唯一出口拦一道,防止任何一处写死的贵模型偷偷烧钱 -->
            <div class="decision-slot cheap-slot" :class="{ dirty: dirty('enforceCheap') || dirty('cheapModel') }">
              <div class="ds-head">
                <b>省钱模式</b>
                <span class="ds-sub">所有调用统一拦一道:员工档案、任务记录、决策槽位里写的贵模型,发请求前都换成省钱模型</span>
                <span class="badge sm" :class="form.enforceCheap ? 'ok' : 'warn'">{{ form.enforceCheap ? '已开启' : '已关闭' }}</span>
              </div>
              <div class="prov-grid">
                <label class="pf check"><span>强制走便宜模型</span><el-switch v-model="form.enforceCheap" /></label>
                <label class="pf check"><span>关闭模型思考</span><el-switch v-model="form.thinkingOff" /></label>
                <label class="pf"><span>省钱模型</span>
                  <el-select v-model="form.cheapModel" size="small" filterable allow-create default-first-option
                             :placeholder="(form.providers[0] || {}).model || 'deepseek-flash'">
                    <el-option v-for="m in cheapModelOptions" :key="m" :label="m" :value="m" />
                  </el-select>
                </label>
              </div>
              <p class="ds-now">
                名字里带 <code>pro / reasoner / thinking / max / opus / gpt-4 / gpt-5</code> 的一律换成
                <b>{{ form.cheapModel || 'deepseek-flash' }}</b>;<code>flash / mini / lite</code> 这类便宜档原样保留。
                <span class="ds-detail">贵模型单价常是便宜档的 4~8 倍,一次长报告就能吃掉一天的预算。</span>
                <br />
                关掉「模型思考」省得更多:DeepSeek 默认开思考且力度 high,推理过程按输出 token 计费,
                长任务里能占一半以上,还常把输出上限吃光导致正文为空、任务失败重来。写文案、写方案不需要它。
              </p>
            </div>
          </div>
          <p class="tip">「检测连通」只从浏览器确认网关地址能否访问;「测一次」由后端真发一条最小请求,验证密钥与模型名确实可用,密钥不出本机、不回显。</p>
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

        <!-- ===== 成果落盘 ===== -->
        <section id="output" class="set-card glass" :style="{ '--i': 2 }">
          <div class="set-card-head">
            <div>
              <h3>成果落盘</h3>
              <p>成果不只存在数据库里。指定一个目录,通过评审的成果会写成真正的 md 文件,能直接拿走用。</p>
            </div>
            <span class="badge" :class="exportInfo.writable ? 'ok' : (exportInfo.checked ? 'err' : '')">
              {{ !exportInfo.checked ? '目录待检测' : (exportInfo.writable ? '目录可写' : '目录不可写') }}
            </span>
          </div>
          <div class="set-body">
            <div class="set-row" :class="{ dirty: dirty('exportDir') }">
              <div class="lbl"><b>落盘目录</b><span>留空则写进程序目录下的 exports/;可填绝对路径,如 E:\交付\空白格</span></div>
              <div class="ctl wide-ctl">
                <el-input v-model="form.exportDir" placeholder="留空 = ./exports" size="small" clearable />
              </div>
            </div>
            <div class="set-row" :class="{ dirty: dirty('autoExportArtifacts') }">
              <div class="lbl"><b>通过评审后自动落盘</b><span>每份成果过审即写入目录;关闭后仍可在成果库、项目页手动落盘</span></div>
              <div class="ctl"><el-switch v-model="form.autoExportArtifacts" /></div>
            </div>
            <p class="path-line">当前解析到:<code>{{ exportInfo.dir || '读取中…' }}</code></p>
          </div>
        </section>

        <!-- ===== 成本与格式 ===== -->
        <section id="cost" class="set-card glass" :style="{ '--i': 2 }">
          <div class="set-card-head">
            <div>
              <h3>成本核算与导出格式</h3>
              <p>「今日烧了 60 万 token」没法决策,「今天花了 ¥4.20」才能。单价按元 / 百万 token 计,改了就按你改的算。</p>
            </div>
            <span class="badge ok">今日 {{ money(costInfo.today) }} · 累计 {{ money(costInfo.total) }}</span>
          </div>
          <div class="set-body">
            <div class="set-row" :class="{ dirty: dirty('pdfFontPath') }">
              <div class="lbl"><b>PDF 中文字体</b><span>留空自动找系统字体(微软雅黑 / 黑体 / Noto);找不到时 PDF 里的中文会变方块</span></div>
              <div class="ctl wide-ctl">
                <el-input v-model="form.pdfFontPath" placeholder="留空 = 自动" size="small" clearable />
                <span class="font-hint">{{ exportInfo.pdfFont ? '已找到:' + exportInfo.pdfFont : '未找到中文字体' }}</span>
              </div>
            </div>
            <div class="price-head">
              <div>
                <b>模型单价</b>
                <span>元 / 百万 token。token 账本只记总量,按输入:输出 = 3:1 折算。</span>
              </div>
              <el-button size="small" text @click="resetPrices">恢复参考价</el-button>
            </div>
            <div class="price-list">
              <div class="price-row price-title"><span>模型</span><span>输入</span><span>输出</span></div>
              <div class="price-row" v-for="p in priceRows" :key="p.model">
                <span class="pm">{{ p.model }}</span>
                <el-input-number v-model="p.input" :min="0" :step="0.5" :precision="2" size="small"
                                 controls-position="right" @change="touchPrices" />
                <el-input-number v-model="p.output" :min="0" :step="0.5" :precision="2" size="small"
                                 controls-position="right" @change="touchPrices" />
              </div>
            </div>
            <p class="path-line">已记录模型:<code>{{ costModelLine }}</code></p>
          </div>
        </section>

        <!-- ===== 公司与权限 ===== -->
        <section id="company" class="set-card glass" :style="{ '--i': 3 }">
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
              <div class="lbl"><b>每日 token 预算</b>
                <span>到顶自动暂停 CEO 自动巡视,0 = 不限 · 今日已用 {{ fmtTokens(tokensToday) }} token(≈{{ money(costInfo.today) }})</span>
              </div>
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
        <section id="data" class="set-card glass" :style="{ '--i': 4 }">
          <div class="set-card-head">
            <div><h3>数据与备份</h3><p>数据库与设置都在本机 data/ 目录。</p></div>
          </div>
          <div class="set-body">
            <div class="set-row">
              <div class="lbl"><b>立即备份</b><span>启动与每 6 小时自动备份,保留最近 7 份</span></div>
              <div class="ctl"><el-button size="small" :loading="backing" @click="doBackup">备份一次</el-button></div>
            </div>
            <div class="set-row col backup-row">
              <div class="lbl">
                <b>已有备份</b>
                <span>恢复不会在运行中动数据库:先暂存,重启后替换,原库另存一份可回退</span>
              </div>
              <div class="backup-list">
                <div v-if="!backups.length" class="backup-none">还没有备份记录。</div>
                <div v-for="b in backups" :key="b.name" class="backup-item">
                  <span class="bi-name">{{ b.name }}</span>
                  <span class="bi-size">{{ fmtSize(b.bytes) }}</span>
                  <span class="bi-at">{{ fmtAt(b.at) }}</span>
                  <button class="fetch-btn" :disabled="restoring" @click="restoreBackup(b.name)">恢复</button>
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- ===== 高级模式 ===== -->
        <section v-if="showAdvanced" id="advanced" class="set-card glass adv" :style="{ '--i': 5 }">
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
import { ElMessage, ElMessageBox } from 'element-plus'
import { motionOk } from '../utils/motion'
import { api } from '../api'

const defaults = {
  providers: [], knowledgeBase: '', concurrency: 4, dailyTokenBudget: 0,
  ceoIntervalMinutes: 15, password: '', qualityBar: '',
  reviewThreshold: 85, maxReviewRounds: 3, autoStartGoals: true,
  decisionProvider: '', decisionModel: '',
  // 省钱模式:默认开。贵模型单价是便宜档的数倍,先开着,真要高质量再关
  enforceCheap: true, cheapModel: 'deepseek-flash', thinkingOff: true,
  exportDir: '', autoExportArtifacts: true,
  // null = 请求里不带这个字段,后端保留原值;用户真改了单价才变成数组
  pricing: null, pdfFontPath: ''
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
const exportInfo = reactive({ dir: '', writable: false, checked: false, pdfFont: '' })
const costInfo = reactive({ today: 0, total: 0, models: [] })
const priceRows = ref([])
const showAdvanced = ref(false)
const activeSection = ref('models')
const probes = reactive({})

const navItems = [
  { id: 'models', label: '模型服务' },
  { id: 'quality', label: '质量与目标' },
  { id: 'output', label: '成果落盘' },
  { id: 'cost', label: '成本与导出' },
  { id: 'company', label: '公司与权限' },
  { id: 'data', label: '数据与备份' }
]

/* ===== 成本:token 折算成钱 ===== */
const costModelLine = computed(() => {
  const list = costInfo.models || []
  if (!list.length) return '还没有模型调用记录'
  return list.map(m => `${m.model} ${fmtTokens(m.tokensTotal)} token / ${money(m.costTotal)}`).join(' · ')
})
function fmtTokens(n) {
  n = Number(n || 0)
  return n >= 10000 ? (n / 10000).toFixed(1) + ' 万' : String(n)
}
const tokensToday = computed(() => (costInfo.models || []).reduce((n, m) => n + Number(m.tokensToday || 0), 0))
function money(v) {
  const n = Number(v || 0)
  if (n <= 0) return '¥0'
  return '¥' + (n < 10 ? n.toFixed(2) : n.toFixed(1))
}
/** 用户动了单价才把整张表提交上去,否则保留后端原值 */
function touchPrices() {
  form.value.pricing = priceRows.value.map(p => ({ model: p.model, input: Number(p.input) || 0, output: Number(p.output) || 0 }))
}
function resetPrices() {
  loadCost()
  form.value.pricing = []
  ElMessage({ message: '已恢复参考价,保存后生效', type: 'info', plain: true })
}
async function loadCost() {
  try {
    const r = await api.cost()
    costInfo.today = r.today || 0
    costInfo.total = r.total || 0
    costInfo.models = r.models || []
    priceRows.value = (r.prices || []).map(p => ({ model: p.model, input: p.input, output: p.output }))
  } catch (e) { /* 成本拉不到不影响设置页 */ }
}

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

/* ===== 模型下拉:从服务商拉真实可用的模型名 =====
   手敲模型名写错时接口返回 400,生产线整段停摆,排查成本比多按一次按钮高得多。
   只对已保存的服务商生效——密钥存后端,这里只传服务商名,密钥不回浏览器。 */
const modelOptions = reactive({})

/** 省钱模型的候选:常见便宜档 + 各服务商已拉取到的模型名 */
const cheapModelOptions = computed(() => {
  const set = new Set(['deepseek-flash', 'deepseek-chat', 'glm-4-flash', 'qwen-plus', 'gpt-4o-mini'])
  Object.values(modelOptions).forEach(list => (list || []).forEach(m => set.add(m)))
  form.value.providers.forEach(p => { if (p.model) set.add(p.model) })
  return [...set]
})
const fetching = ref(-1)
const dsFetching = ref(false)

async function fetchModelsFor(p, mark) {
  if (!p || !p.baseUrl || !p.name) return
  mark(true)
  try {
    const r = await api.settingsModels(p.name)
    if (r.ok && r.models && r.models.length) {
      modelOptions[p.baseUrl] = r.models
      ElMessage({ message: `${p.name} 可用模型 ${r.models.length} 个`, type: 'success', plain: true })
    } else {
      ElMessage({ message: r.message || '没拿到模型列表,可以直接手填', type: 'warning', plain: true })
    }
  } catch (e) {
    ElMessage.error('拉取失败,可以直接手填模型名')
  } finally { mark(false) }
}

function fetchModels(i) {
  if (fetching.value >= 0) return
  fetchModelsFor(form.value.providers[i], (on) => { fetching.value = on ? i : -1 })
}
function fetchDecisionModels() {
  fetchModelsFor(dsResolved.value, (on) => { dsFetching.value = on })
}
function addProvider() {
  form.value.providers.push({ name: '', baseUrl: '', apiKey: '', model: '', primary: !form.value.providers.length })
}
function removeProvider(i) { form.value.providers.splice(i, 1) }

/* ===== CEO 决策模型槽位 ===== */
const dsState = reactive({ run: false, ok: null, label: '未检测', detail: '' })
/* 服务商没点名就跟随主用,和后端 decisionProvider() 的解析口径保持一致 */
const dsResolved = computed(() => {
  const list = form.value.providers || []
  const named = list.find(p => p.name && p.name === form.value.decisionProvider)
  return named || list.find(p => p.primary) || list[0] || null
})
const decisionModelHint = computed(() => {
  const p = dsResolved.value
  return p && p.model ? `默认用 ${p.model}` : '留空则用该服务商默认模型'
})
const decisionEffective = computed(() => {
  const p = dsResolved.value
  if (!p) return '还没有任何服务商,CEO 无法决策'
  const model = String(form.value.decisionModel || '').trim() || p.model || '未填模型名'
  const follow = p.name && p.name === form.value.decisionProvider ? '' : '(槽位未点名,跟随主用)'
  return `${p.name || '未命名服务商'}${follow} · ${model}`
})
async function testDecision() {
  dsState.run = true
  dsState.label = '后端实测中'
  dsState.detail = ''
  try {
    const r = await api.settingsTest({ scope: 'decision', model: String(form.value.decisionModel || '').trim() })
    dsState.ok = !!r.ok
    // 徽章只留一句结论,模型回了什么全文挪到下一行,免得长回复把徽章撑成长条
    dsState.label = r.ok ? `${r.ms}ms · 模型已应答` : '调用失败'
    dsState.detail = r.message || ''
  } catch (e) {
    dsState.ok = false
    dsState.label = '检测请求没发出去'
    dsState.detail = e?.message || '网络异常'
  } finally {
    dsState.run = false
  }
}

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
  await loadExportInfo()
  await nextTick()
  observeSections()
}

async function loadExportInfo() {
  try {
    const r = await api.exportStatus()
    exportInfo.dir = r.dir || ''
    exportInfo.writable = !!r.writable
    exportInfo.pdfFont = r.pdfFont || ''
    exportInfo.checked = true
  } catch (e) {
    exportInfo.checked = true
    exportInfo.writable = false
  }
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
    loadExportInfo()
  } catch (e) {
    ElMessage.error('保存失败')
  } finally { saving.value = false }
}

async function doBackup() {
  backing.value = true
  try {
    const r = await api.backupNow()
    ElMessage({ message: r.ok ? ('已备份:' + r.file) : '备份失败', type: r.ok ? 'success' : 'error', plain: true })
    if (r.ok) await loadBackups()
  } finally { backing.value = false }
}

/* ===== 备份清单与恢复 ===== */
const backups = ref([])
const restoring = ref(false)

async function loadBackups() {
  try {
    const r = await api.backups()
    backups.value = r.items || []
  } catch (e) { /* 清单拉不到不影响其它设置 */ }
}

function fmtSize(bytes) {
  const n = Number(bytes) || 0
  if (n > 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB'
  return Math.max(1, Math.round(n / 1024)) + ' KB'
}
function fmtAt(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}

async function restoreBackup(name) {
  try {
    await ElMessageBox.confirm(
      `将把数据恢复到「${name}」的状态。恢复在下次启动时生效,当前库会另存一份,可回退。继续?`,
      '恢复备份', { confirmButtonText: '准备恢复', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) { return }
  restoring.value = true
  try {
    const r = await api.restoreBackup(name)
    if (r.ok) {
      ElMessage({ message: r.message || '已准备好,重启后生效', type: 'success', plain: true, duration: 5200 })
    } else {
      ElMessage.error(r.message || '恢复失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '恢复失败')
  } finally { restoring.value = false }
}

async function clearPassword() {
  form.value.password = '__CLEAR__'
  await save()
  form.value.password = ''
}

onMounted(async () => {
  await load()
  loadBackups()
  loadCost()
})
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
/* 备份清单:让人看得见"确实存下来了",而不是只有一个按钮 */
.backup-list { display: flex; flex-direction: column; gap: 6px; }
.backup-item {
  display: grid; grid-template-columns: 1fr 74px 108px 52px; align-items: center; gap: 10px;
  padding: 7px 10px; border: 1px solid var(--border-hair); border-radius: 7px;
  font-family: var(--font-mono); font-size: 11.5px; color: var(--text-dim);
}
.backup-item .bi-size, .backup-item .bi-at { color: var(--text-faint); }
.backup-none { font-size: 12px; color: var(--text-faint); }
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
.path-line { margin: 14px 0 0; padding-top: 12px; border-top: 1px dashed var(--border-hair); font-size: 11px; color: var(--text-faint); }
.path-line code { font-family: var(--font-mono); font-size: 11px; color: var(--glow-cyan-dim); word-break: break-all; }

/* ===== 成本与导出 ===== */
.font-hint { font-size: 10.5px; color: var(--text-faint); letter-spacing: .06em; white-space: nowrap; }
.price-head {
  display: flex; align-items: flex-end; justify-content: space-between; gap: 12px;
  margin: 18px 0 8px; padding-top: 14px; border-top: 1px dashed var(--border-hair);
}
.price-head b { display: block; font-size: 12.5px; font-weight: 500; color: var(--text-dim); }
.price-head span { display: block; margin-top: 4px; font-size: 10.5px; color: var(--text-faint); }
.price-list { display: flex; flex-direction: column; gap: 6px; }
.price-row {
  display: grid; grid-template-columns: 1fr 118px 118px; align-items: center; gap: 10px;
}
.price-row .pm { font-family: var(--font-mono); font-size: 11.5px; color: var(--text-dim); }
.price-title span {
  font-size: 10px; letter-spacing: .16em; color: var(--text-faint);
}
.price-title .pm { font-family: var(--font-sans); }
.price-row .el-input-number { width: 100%; }
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
/* CEO 决策模型槽位:比服务商多一道左侧光边,标明这是决策中枢而不是一条普通配置 */
.decision-slot {
  position: relative; border: 1px solid var(--border-hair); border-left: 2px solid rgba(var(--rgb-glow), .55);
  border-radius: 11px; background: var(--tint-soft); padding: 13px 15px; margin: 16px 0 2px;
  transition: border-color .35s var(--ease-quiet);
}
.decision-slot:hover { border-color: rgba(var(--rgb-glow), .3); border-left-color: var(--glow-cyan); }
.decision-slot.dirty { border-left-color: rgba(var(--rgb-warn), .8); }
.ds-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.ds-head b { font-size: 12.5px; font-weight: 400; letter-spacing: .1em; color: var(--text-moon); }
.ds-head .badge { margin-left: auto; max-width: 60%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ds-sub { font-size: 10.5px; letter-spacing: .04em; color: var(--text-faint); }
.ds-now { margin: 12px 0 0; font-family: var(--font-mono); font-size: 10.5px; color: var(--text-faint); }
.ds-now b { font-weight: 400; color: var(--glow-cyan); }
.ds-detail { display: block; margin-top: 5px; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ds-detail.ok { color: var(--st-ok); opacity: .78; }
.ds-detail.err { color: var(--st-error); }
.pf { display: flex; flex-direction: column; gap: 5px; width: 190px; }
.pf.wide { flex: 1; min-width: 260px; }
.pf.check { width: auto; justify-content: flex-end; }
.pf > span { font-size: 10.5px; letter-spacing: .18em; color: var(--text-dim); }
/* 模型下拉旁的「拉取」:次要动作,不能抢主按钮的注意力 */
.fetch-btn {
  margin-left: 6px; padding: 0 5px; border: 1px solid var(--border-hair); border-radius: 4px;
  background: transparent; color: var(--text-faint); font-size: 10px; letter-spacing: .06em;
  cursor: pointer; transition: color .15s, border-color .15s;
}
.fetch-btn:hover:not(:disabled) { color: var(--accent); border-color: var(--accent); }
.fetch-btn:disabled { opacity: .35; cursor: not-allowed; }
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
