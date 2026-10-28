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
  // 自检是真发一次模型请求,给它 25 秒上限:后端没起来时要失败而不是把按钮挂死
  settingsTest: (body) => http.post('/settings/test', body || {}, { timeout: 25000 }).then(r => r.data),

  opsFeed: () => http.get('/ops/feed').then(r => r.data),
  setGoal: (content) => http.post('/ops/goal', { content }).then(r => r.data),
  setFeedback: (content) => http.post('/ops/feedback', { content }).then(r => r.data),
  ackActivity: (id) => http.post(`/ops/${id}/ack`).then(r => r.data),
  ceoCycle: () => http.post('/ops/ceo-cycle').then(r => r.data),
  backupNow: () => http.post('/ops/backup').then(r => r.data),

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

  artifacts: (params) => http.get('/artifacts', { params, timeout: LIST_TIMEOUT }).then(r => r.data),
  artifact: (id) => http.get(`/artifacts/${id}`, { timeout: LIST_TIMEOUT }).then(r => r.data),

  downloadGoal: async (id, title) => {
    const r = await http.get(`/goals/${id}/export`, { responseType: 'blob' })
    saveBlob(r.data, `${safeName(title)}.zip`)
  },
  downloadArtifact: async (id, title) => {
    const r = await http.get(`/artifacts/${id}/download`, { responseType: 'blob' })
    saveBlob(r.data, `${safeName(title)}.md`)
  }
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
