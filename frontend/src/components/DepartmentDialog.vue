<template>
  <el-dialog v-model="show" title="新建部门" width="440px" :append-to-body="true">
    <div class="form">
      <div class="field">
        <label>部门名称</label>
        <el-input v-model="form.name" maxlength="12" placeholder="如:法务部" @keyup.enter="save" />
      </div>
      <div class="field">
        <label>部门配色</label>
        <div class="swatches">
          <button v-for="c in colors" :key="c.key" type="button" class="swatch"
                  :class="{ on: form.colorKey === c.key }" :style="{ '--c': `var(--dept-${c.key})` }"
                  @click="form.colorKey = c.key">
            <i></i>
            <span>{{ c.label }}</span>
          </button>
        </div>
      </div>
      <p class="hint">新部门会出现在组织图与 CEO 的拆解范围中;系统会自动补一位负责人,再由你点亮具体岗位。</p>
    </div>
    <template #footer>
      <el-button @click="show = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!form.name.trim()" @click="save">创建部门</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const props = defineProps({
  modelValue: Boolean
})
const emit = defineEmits(['update:modelValue', 'created'])

const show = computed({
  get: () => props.modelValue,
  set: v => emit('update:modelValue', v)
})

const colors = [
  { key: 'product', label: '墨紫' },
  { key: 'tech', label: '天青' },
  { key: 'design', label: '藕粉' },
  { key: 'market', label: '暖砂' },
  { key: 'ops', label: '苔绿' }
]
const form = ref({ name: '', colorKey: 'tech' })
const saving = ref(false)

watch(() => props.modelValue, open => {
  if (open) form.value = { name: '', colorKey: 'tech' }
})

async function save() {
  const name = form.value.name.trim()
  if (!name) return
  saving.value = true
  try {
    await api.createDept({ name, colorKey: form.value.colorKey })
    ElMessage({ message: `「${name}」已成立,负责人待就位`, type: 'success', plain: true })
    show.value = false
    emit('created')
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '创建失败')
  } finally { saving.value = false }
}
</script>

<style scoped>
.form { display: flex; flex-direction: column; gap: 16px; }
.field label {
  display: block; font-size: 12px; color: var(--text-dim);
  letter-spacing: .2em; margin-bottom: 8px;
}
.swatches { display: grid; grid-template-columns: repeat(auto-fit, minmax(72px, 1fr)); gap: 8px; }
.swatch {
  display: flex; align-items: center; justify-content: center; gap: 7px;
  padding: 8px 6px; border-radius: 9px; cursor: pointer;
  background: var(--tint-soft);
  border: 1px solid var(--border-hair);
  color: var(--text-dim); font-family: var(--font-sans); font-size: 11.5px;
  transition: all .3s var(--ease-quiet);
}
.swatch i { width: 9px; height: 9px; border-radius: 50%; background: var(--c); box-shadow: 0 0 8px color-mix(in srgb, var(--c) 55%, transparent); }
.swatch:hover { border-color: color-mix(in srgb, var(--c) 45%, transparent); color: var(--text-moon); }
.swatch.on { border-color: color-mix(in srgb, var(--c) 70%, transparent); color: var(--c); background: color-mix(in srgb, var(--c) 9%, transparent); }
.hint { margin: 0; font-size: 11.5px; line-height: 1.8; color: var(--text-faint); letter-spacing: .03em; }
</style>
