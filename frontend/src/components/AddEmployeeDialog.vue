<template>
  <el-dialog v-model="show" title="点亮一格" width="460px" :append-to-body="true">
    <div class="form">
      <div class="field">
        <label>岗位名</label>
        <el-input v-model="form.name" placeholder="如:数据分析师" />
      </div>
      <div class="field">
        <label>一句话职责</label>
        <el-input v-model="form.title" placeholder="如:数据清洗与报表" />
      </div>
      <div class="field">
        <label>所属部门</label>
        <el-select v-model="form.departmentId" style="width: 100%">
          <el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
      </div>
      <div class="field">
        <label>层级</label>
        <el-select v-model="form.level" style="width: 100%">
          <el-option label="执行岗位 · 负责具体交付" value="STAFF" />
          <el-option label="部门负责人 · 拆活与验收" value="LEAD" />
        </el-select>
      </div>
      <div class="field">
        <label>岗位说明书(系统提示词)</label>
        <el-input v-model="form.systemPrompt" type="textarea" :rows="5" resize="none"
                  placeholder="告诉这位 AI:它是谁、负责什么、产出要符合什么标准……" />
      </div>
    </div>
    <template #footer>
      <el-button @click="show = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">点亮</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const props = defineProps({
  modelValue: Boolean,
  departments: { type: Array, default: () => [] },
  presetDept: { type: Number, default: null }
})
const emit = defineEmits(['update:modelValue', 'created'])

const show = computed({
  get: () => props.modelValue,
  set: v => emit('update:modelValue', v)
})

const form = ref({ name: '', title: '', departmentId: null, systemPrompt: '', level: 'STAFF' })
const saving = ref(false)

watch(() => props.modelValue, (open) => {
  if (open) {
    form.value = {
      name: '', title: '',
      departmentId: props.presetDept || props.departments[0]?.id || null,
      systemPrompt: '',
      level: 'STAFF'
    }
  }
})

async function save() {
  if (!form.value.name.trim()) { ElMessage.warning('请填写岗位名'); return }
  saving.value = true
  try {
    await api.createEmployee(form.value)
    ElMessage({ message: '一格已点亮', type: 'success', plain: true })
    show.value = false
    emit('created')
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '创建失败')
  } finally { saving.value = false }
}
</script>

<style scoped>
.form { display: flex; flex-direction: column; gap: 14px; }
.field label {
  display: block; font-size: 12px; color: var(--text-dim);
  letter-spacing: .2em; margin-bottom: 8px;
}
</style>
