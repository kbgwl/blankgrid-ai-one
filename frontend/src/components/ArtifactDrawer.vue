<template>
  <el-drawer v-model="show" size="min(880px, 94vw)" :with-header="false" append-to-body>
    <div class="artifact" v-if="art">
      <header class="art-head">
        <div class="art-top">
          <div class="art-eyebrow">
            <span class="art-type">{{ art.type || '文档' }}</span>
            <span class="art-ver">v{{ art.version }}</span>
            <span class="art-status" :class="'as-' + (art.status || '').toLowerCase()">{{ statusText }}</span>
          </div>
          <el-button size="small" text class="dl" @click="toggleEdit">{{ editing ? '取消编辑' : '编辑正文' }}</el-button>
          <el-button v-if="canAdopt" size="small" text class="dl" :loading="adopting" @click="adopt">定为最终版</el-button>
          <el-button size="small" text class="dl" @click="saveToDir">存到落盘目录</el-button>
          <el-button size="small" text class="dl" @click="download">下载 Markdown</el-button>
        </div>
        <h2 class="art-title">{{ art.title }}</h2>
        <div class="art-meta">
          <span v-if="authorName">作者 · {{ authorName }}<template v-if="art.authorTitle"> · {{ art.authorTitle }}</template></span>
          <span v-if="art.score">评审 {{ art.score }} 分</span>
          <span v-if="art.manualEdit" class="manual-tag">人工修订</span>
          <span>{{ fmt(art.createdAt) }}</span>
        </div>
      </header>

      <section v-if="art.reviewNotes" class="review-note glass">
        <div class="review-label">评审意见</div>
        <p>{{ art.reviewNotes }}</p>
      </section>

      <div v-if="editing" class="art-edit">
        <el-input v-model="draft" type="textarea" :rows="24" resize="vertical" />
        <div class="art-edit-actions">
          <span class="hint">保存后这份成果会标记为「人工修订」,评审分只作历史参考;要让它成为最终版,保存后再点「定为最终版」。</span>
          <div class="btns">
            <el-button size="small" @click="toggleEdit">取消</el-button>
            <el-button type="primary" size="small" :loading="saving" :disabled="!draft.trim()" @click="saveEdit">保存</el-button>
          </div>
        </div>
      </div>
      <article v-else class="md-content" :key="art.id" v-html="html"></article>
    </div>
    <div v-else class="art-loading">载入中……</div>
  </el-drawer>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps({
  modelValue: Boolean,
  artifactId: { type: [Number, String], default: null }
})
const emit = defineEmits(['update:modelValue'])

const show = computed({
  get: () => props.modelValue,
  set: v => emit('update:modelValue', v)
})

const art = ref(null)
// 抽屉已展示标题,正文首个 H1 通常是同一标题,去掉避免重复
const html = computed(() => renderMarkdown((art.value?.content || '').replace(/^\s*#\s+[^\n]*(?:\n+|$)/, '')))
const authorName = computed(() => {
  const a = art.value?.author
  return a && typeof a === 'object' ? a.name : a
})

const statusText = computed(() => ({
  DRAFT: '草稿', REVIEW: '待评审', APPROVED: '已通过', REJECTED: '未通过'
}[art.value?.status] || art.value?.status || ''))

/* ===== 人工修订:模型写的差一口气时直接改,不用整轮重跑 ===== */
const editing = ref(false)
const draft = ref('')
const saving = ref(false)
const adopting = ref(false)
const canAdopt = computed(() => !!art.value && art.value.status !== 'APPROVED')

function toggleEdit() {
  if (!art.value) return
  editing.value = !editing.value
  if (editing.value) draft.value = art.value.content || ''
  else draft.value = ''
}

async function saveEdit() {
  if (!art.value) return
  saving.value = true
  try {
    const r = await api.editArtifact(art.value.id, { content: draft.value })
    art.value = { ...art.value, ...r }
    editing.value = false
    ElMessage({ message: '已保存为人工修订版', type: 'success', plain: true })
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '保存失败')
  } finally { saving.value = false }
}

async function adopt() {
  if (!art.value) return
  adopting.value = true
  try {
    const r = await api.adoptArtifact(art.value.id)
    art.value = { ...art.value, ...r }
    ElMessage({ message: '已定为最终版,对应交付物标记完成', type: 'success', plain: true })
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '操作失败')
  } finally { adopting.value = false }
}

watch(() => [props.modelValue, props.artifactId], async ([open, id]) => {
  if (!open || !id) return
  art.value = null
  editing.value = false
  try { art.value = await api.artifact(id) } catch (e) { ElMessage.error('成果载入失败') }
})

function fmt(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}

async function download() {
  try { await api.downloadArtifact(art.value.id, art.value.title) } catch (e) { ElMessage.error('下载失败') }
}

async function saveToDir() {
  try {
    const r = await api.exportArtifactToDir(art.value.id)
    if (r.ok) ElMessage({ message: '已写入 ' + r.file, type: 'success', plain: true, duration: 4600 })
    else ElMessage.error(r.error || '写入失败')
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '写入失败')
  }
}
</script>

<style scoped>
.artifact { max-width: 780px; margin: 0 auto; padding: 8px 6px 40px; }
.art-head { position: relative; padding: 6px 4px 20px; border-bottom: 1px solid var(--border-hair); }
.art-top { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.art-eyebrow { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.art-type { font-size: 10.5px; letter-spacing: .24em; color: var(--glow-cyan); background: var(--glow-cyan-faint); border-radius: 999px; padding: 3px 11px; }
.art-ver { font-family: var(--font-mono); font-size: 11px; color: var(--text-faint); }
.art-status { font-size: 10.5px; letter-spacing: .12em; padding: 3px 10px; border-radius: 999px; background: var(--chip-bg); color: var(--text-dim); }
.as-approved { color: var(--st-ok); background: rgba(var(--rgb-ok), .1); }
.as-rejected { color: var(--st-error); background: rgba(var(--rgb-error), .1); }
.as-review { color: var(--st-warn); background: rgba(var(--rgb-warn), .1); }
.art-title { margin: 14px 0 0; font-size: 27px; font-weight: 300; letter-spacing: .06em; line-height: 1.4; }
.art-meta { display: flex; gap: 18px; margin-top: 10px; font-size: 11.5px; color: var(--text-faint); font-family: var(--font-mono); flex-wrap: wrap; }
.dl { color: var(--glow-cyan-dim); letter-spacing: .12em; flex: none; }
.review-note { margin: 18px 0 8px; padding: 14px 18px; border-color: rgba(var(--rgb-warn), .28); }
.review-label { font-size: 10.5px; letter-spacing: .22em; color: var(--st-warn); margin-bottom: 6px; }
.review-note p { margin: 0; font-size: 12.5px; line-height: 1.8; color: var(--text-dim); white-space: pre-wrap; }
.art-loading { color: var(--text-faint); text-align: center; padding: 120px 0; letter-spacing: .3em; }
/* 人工修订:正文变成可写的稿纸,不再只是展示 */
.manual-tag { color: var(--st-warn); letter-spacing: .12em; }
.art-edit { margin-top: 20px; }
.art-edit :deep(.el-textarea__inner) {
  background: var(--surface-2, rgba(255,255,255,.02)); color: var(--text-main);
  font-family: var(--font-mono); font-size: 13px; line-height: 1.9;
  border-color: var(--border-hair); box-shadow: none;
}
.art-edit-actions { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 12px; }
.art-edit-actions .hint { font-size: 11.5px; line-height: 1.7; color: var(--text-faint); }
.art-edit-actions .btns { display: flex; gap: 8px; flex: none; }
/* 翻开一份成果:正文像纸一样翻上来 */
.md-content { animation: pageTurn .55s var(--ease-out) both; }
.art-head { animation: riseIn .5s var(--ease-out) both; }
</style>
