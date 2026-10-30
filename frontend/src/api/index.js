import axios from 'axios'
import { Client } from '@stomp/stompjs'

const http = axios.create({ baseURL: '/api' })

// 列表类接口超过 12 秒就当成没响应,让页面进入可重试的失败态而不是一直白屏
const LIST_TIMEOUT = 12000

// 口令登录:自动附带 token;401 时广播 auth-required
const TOKEN_KEY = 'aione_token'
export const authToken = {
  get: () => localStorage.getItem(TOKEN_KEY) || '',
  set: (t) => localStorage.setItem(TOKEN_KEY, t),
  clear: () => localStorage.removeItem(TOKEN_KEY)
}
http.interceptors.request.use(cfg => {
  const t = authToken.get()
  if (t) cfg.headers.Authorization = t
  return cfg
})
http.interceptors.response.use(r => r, err => {
  if (err.response?.status === 401 && !err.config?.url?.includes('/auth/login')) {
    bus.emit('auth-required')
  }
  return Promise.reject(err)
})

export async function login(password) {
  const r = await axios.post('/api/auth/login', { password })
  if (r.data.token) authToken.set(r.data.token)
  return r.data
}

export const api = {
  org: () => http.get('/org').then(r => r.data),
  createEmployee: (body) => http.post('/org/employees', body).then(r => r.data),
  updateEmployee: (id, body) => http.put(`/org/employees/${id}`, body).then(r => r.data),
  deleteEmployee: (id) => http.delete(`/org/employees/${id}`).then(r => r.data),
  createDept: (body) => http.post('/org/departments', body).then(r => r.data),
  updateDept: (id, body) => http.put(`/org/departments/${id}`, body).then(r => r.data),
  deleteDept: (id) => http.delete(`/org/departments/${id}`).then(r => r.data),

  submitTask: (body) => http.post('/tasks', body).then(r => r.data),
  tasks: (employeeId) => http.get('/tasks', { params: employeeId ? { employeeId } : {} }).then(r => r.data),
  taskDetail: (id) => http.get(`/tasks/${id}`).then(r => r.data),
  cancelTask: (id) => http.post(`/tasks/${id}/cancel`).then(r => r.data),

  stats: () => http.get('/stats').then(r => r.data),
  settings: () => http.get('/settings').then(r => r.data),
  saveSettings: (s) => http.put('/settings', s).then(r => r.data),
  exportStatus: () => http.get('/settings/export').then(r => r.data),
  // 自检是真发一次模型请求,给它 25 秒上限:后端没起来时要失败而不是把按钮挂死
  settingsTest: (body) => http.post('/settings/test', body || {}, { timeout: 25000 }).then(r => r.data),
  // 拉服务商的可用模型:手敲模型名写错会让整条生产线 400,这里列出真实可用的
  settingsModels: (provider) => http.get('/settings/models', {
    params: provider ? { provider } : {}, timeout: 25000
  }).then(r => r.data),
  // 已注册的工具清单(名字、是否可用、缺什么)。设置页用它告诉用户"还差一步就能用"
  tools: () => http.get('/settings/tools').then(r => r.data),
  // 工具自主调用:清单(带当前策略)、待批调用、批准/拒绝、REA 自检
  toolsList: () => http.get('/tools').then(r => r.data),
  toolApprovals: (scope) => http.get('/tools/approvals', { params: { scope: scope || 'pending' } }).then(r => r.data),
  // 导航角标每 30 秒问一次这个,不要为了一个数字拉整张列表
  toolApprovalCount: () => http.get('/tools/approvals/count').then(r => r.data),
  approveTool: (id) => http.post(`/tools/approvals/${id}/approve`).then(r => r.data),
  batchApproveTools: (ids) => http.post('/tools/approvals/batch-approve', { ids }).then(r => r.data),
  rejectTool: (id) => http.post(`/tools/approvals/${id}/reject`).then(r => r.data),
  // 拒绝带理由:理由会进公司动态,模型下一轮才知道"为什么不行"
  rejectToolWithReason: (id, reason) => http.post(`/tools/approvals/${id}/reject`, { reason }).then(r => r.data),
  // 工具调用审计:谁调的、花了多久、成没成
  toolCalls: (hours) => http.get('/tools/calls', { params: hours ? { hours } : {} }).then(r => r.data),
  // REA 自检要真起一次外部进程,npx 首次还要下载包,给足 3 分钟
  reaTest: () => http.post('/tools/rea/test', {}, { timeout: 180000 }).then(r => r.data),
  // 电商联盟连通性:真去搜一条,比"保存成功"有用 —— 密钥错/没备案都是保存时不报错、干活才 403
  ecomTest: () => http.post('/settings/ecom/test', {}, { timeout: 60000 }).then(r => r.data),

  opsFeed: () => http.get('/ops/feed').then(r => r.data),
  // 决策档案:CEO 每轮巡视"查了什么、怎么拍的板"。列表不带证据原文,详情才带
  opsTraces: (limit) => http.get('/ops/traces', { params: limit ? { limit } : {} }).then(r => r.data),
  opsTrace: (id) => http.get(`/ops/traces/${id}`, { timeout: 20000 }).then(r => r.data),
  setGoal: (content) => http.post('/ops/goal', { content }).then(r => r.data),
  setFeedback: (content) => http.post('/ops/feedback', { content }).then(r => r.data),
  ackActivity: (id) => http.post(`/ops/${id}/ack`).then(r => r.data),
  ceoCycle: () => http.post('/ops/ceo-cycle').then(r => r.data),
  backupNow: () => http.post('/ops/backup').then(r => r.data),
  backups: () => http.get('/ops/backups').then(r => r.data),
  restoreBackup: (file) => http.post('/ops/restore', { file }).then(r => r.data),

  goals: () => http.get('/goals', { timeout: LIST_TIMEOUT }).then(r => r.data),
  goal: (id) => http.get(`/goals/${id}`, { timeout: LIST_TIMEOUT }).then(r => r.data),
  createGoal: (body) => http.post('/goals', body).then(r => r.data),
  startGoal: (id) => http.post(`/goals/${id}/start`).then(r => r.data),
  pauseGoal: (id) => http.post(`/goals/${id}/pause`).then(r => r.data),
  resumeGoal: (id) => http.post(`/goals/${id}/resume`).then(r => r.data),
  acceptGoal: (id) => http.post(`/goals/${id}/accept`).then(r => r.data),
  replanGoal: (id) => http.post(`/goals/${id}/replan`).then(r => r.data),
  retryWorkstream: (goalId, wsId) => http.post(`/goals/${goalId}/workstreams/${wsId}/retry`).then(r => r.data),
  retryDeliverable: (goalId, dId) => http.post(`/goals/${goalId}/deliverables/${dId}/retry`).then(r => r.data),
  // 一次性把受阻/挂起的交付物全部推回队列:充值、换模型、重启之后不必逐条点重试
  retryBlocked: (goalId) => http.post(`/goals/${goalId}/retry-blocked`).then(r => r.data),
  provideInputs: (goalId, facts) => http.post(`/goals/${goalId}/inputs`, { facts }).then(r => r.data),
  addGoalBudget: (goalId, tokens) => http.post(`/goals/${goalId}/budget`, { tokens }).then(r => r.data),

  artifacts: (params) => http.get('/artifacts', { params, timeout: LIST_TIMEOUT }).then(r => r.data),
  artifact: (id) => http.get(`/artifacts/${id}`, { timeout: LIST_TIMEOUT }).then(r => r.data),
  // 人工修订与定稿:模型写的差一口气时,人可以直接改,不用重跑一轮
  editArtifact: (id, body) => http.put(`/artifacts/${id}`, body).then(r => r.data),
  adoptArtifact: (id) => http.post(`/artifacts/${id}/adopt`).then(r => r.data),

  // 落盘:写进设置页指定的目录,返回真实路径而不是让浏览器再下载一份
  // format: md / docx / pptx / pdf / all(默认 all,四种格式都写)
  exportGoalToDir: (id, format) => http.post(`/goals/${id}/export-to-dir`, {}, { params: format ? { format } : {}, timeout: 60000 }).then(r => r.data),
  exportArtifactToDir: (id, format) => http.post(`/artifacts/${id}/export-to-dir`, {}, { params: format ? { format } : {}, timeout: 60000 }).then(r => r.data),

  // 多格式下载:不传 format 就是原来的 zip / md
  downloadGoal: async (id, title, format) => {
    const q = format ? `?format=${format}` : ''
    const r = await http.get(`/goals/${id}/export${q}`, { responseType: 'blob', timeout: 60000 })
    saveBlob(r.data, format ? `${safeName(title)}-全书.${format}` : `${safeName(title)}.zip`)
  },
  downloadArtifact: async (id, title, format) => {
    const ext = format || 'md'
    const r = await http.get(`/artifacts/${id}/download?format=${ext}`, { responseType: 'blob', timeout: 60000 })
    saveBlob(r.data, `${safeName(title)}.${ext}`)
  },

  // 成本:token 折算成钱
  cost: () => http.get('/stats/cost').then(r => r.data)
}

function safeName(s) {
  return (s || 'artifact').replace(/[\\/:*?"<>|\s]+/g, '-').slice(0, 60)
}

function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 2000)
}

/** 全局事件总线:后端 WebSocket 事件分发给各视图 */
const listeners = new Set()
export const bus = {
  on(fn) { listeners.add(fn); return () => listeners.delete(fn) },
  emit(evt) { listeners.forEach(fn => fn(evt)) }
}

let client = null
export function connectWs() {
  if (client) return
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  client = new Client({
    brokerURL: `${proto}://${location.host}/ws`,
    connectHeaders: authToken.get() ? { Authorization: authToken.get() } : {},
    reconnectDelay: 4000,
    onConnect: () => {
      client.subscribe('/topic/events', (msg) => {
        try { bus.emit(JSON.parse(msg.body)) } catch (e) { /* 忽略坏帧 */ }
      })
    }
  })
  client.activate()
}
