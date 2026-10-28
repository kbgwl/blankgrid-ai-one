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
          <el-button size="small" text class="dl" @click="download">下载 Markdown</el-button>
        </div>
        <h2 class="art-title">{{ art.title }}</h2>
        <div class="art-meta">
          <span v-if="authorName">作者 · {{ authorName }}<template v-if="art.authorTitle"> · {{ art.authorTitle }}</template></span>
          <span v-if="art.score">评审 {{ art.score }} 分</span>
          <span>{{ fmt(art.createdAt) }}</span>
        </div>
      </header>

      <section v-if="art.reviewNotes" class="review-note glass">
        <div class="review-label">评审意见</div>
        <p>{{ art.reviewNotes }}</p>
      </section>

      <article class="md-content" :key="art.id" v-html="html"></article>
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

watch(() => [props.modelValue, props.artifactId], async ([open, id]) => {
  if (!open || !id) return
  art.value = null
  try { art.value = await api.artifact(id) } catch (e) { ElMessage.error('成果载入失败') }
})

function fmt(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}

async function download() {
  try { await api.downloadArtifact(art.value.id, art.value.title) } catch (e) { ElMessage.error('下载失败') }
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
/* 翻开一份成果:正文像纸一样翻上来 */
.md-content { animation: pageTurn .55s var(--ease-out) both; }
.art-head { animation: riseIn .5s var(--ease-out) both; }
</style>
