<!-- 文件职责：上传单作品 JSON，展示导入回执，并衔接持久化分析任务。 -->
<script setup lang="ts">
import { ref } from 'vue'
import AnalysisPanel from './AnalysisPanel.vue'
const props = defineProps<{ projectId?: string }>()
const emit = defineEmits<{ imported: [] }>()
/** 导入回执绑定已保存快照，分析任务只使用该快照 ID。 */
type Receipt = {
  id: string
  snapshotId: string
  status: string
  importedComments: number
  eligibleComments: number
  duplicateComments: number
  newRevisions: number
  snapshotReused: boolean
}
const busy = ref(false)
const result = ref<Receipt | null>(null)
const error = ref('')
const issues = ref<{ path: string; message: string }[]>([])
/** 1、校验文件；2、提交上传；3、展示回执或结构化错误。 */
async function submit(file: File) {
  error.value = ''
  issues.value = []
  result.value = null
  if (!file.name.toLowerCase().endsWith('.json')) {
    error.value = '请选择 JSON 文件。'
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    error.value = '文件最大 2 MiB。'
    return
  }
  busy.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    if (props.projectId) form.append('projectId', props.projectId)
    const response = await fetch('/api/v1/imports', { method: 'POST', body: form })
    const data = await response.json()
    if (!response.ok) {
      error.value = data.message || '导入失败，请检查文件。'
      issues.value = data.issues || []
      return
    }
    result.value = data
    if (props.projectId) emit('imported')
  } catch {
    error.value = '连接失败，请确认后端已经启动后重试。'
  } finally {
    busy.value = false
  }
}
/** 取出用户选择的文件并重置输入，允许再次选择同一文件。 */
function select(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) void submit(file)
  input.value = ''
}
</script>

<template>
  <section id="local-import" class="import-panel" aria-labelledby="import-title">
    <div class="section-title">
      <div>
        <h2 id="import-title">导入一个作品</h2>
        <p>上传有权使用的评论与字幕，保存可复核的数据快照。</p>
      </div>
      <span class="tag">JSON · 最大 2 MiB</span>
    </div>
    <label class="file-label" :class="{ disabled: busy }">
      {{ busy ? '正在校验并保存…' : '选择 JSON 文件'
      }}<input
        aria-label="选择 JSON 文件"
        type="file"
        accept=".json,application/json"
        :disabled="busy"
        @change="select"
    /></label>
    <p class="import-help">
      可先选择项目 datasets/demo/threadpool/input.json。每份文件代表一个作品的完整快照；导入不会调用
      AI。
    </p>
    <div v-if="error" role="alert" class="import-error">
      <strong>{{ error }}</strong>
      <ul v-if="issues.length">
        <li v-for="(issue, i) in issues" :key="i">{{ issue.path }}：{{ issue.message }}</li>
      </ul>
    </div>
    <div v-if="result" class="import-success" role="status">
      <strong>{{
        result.snapshotReused ? '导入成功，复用了已有快照' : '导入成功，已保存新快照'
      }}</strong>
      <p>
        读取 {{ result.importedComments }} 条 · 有效 {{ result.eligibleComments }} 条 · 文件内重复
        {{ result.duplicateComments }} 条 · 新评论修订 {{ result.newRevisions }} 条
      </p>
      <p>快照：{{ result.snapshotId }}</p>
      <a :href="'/api/v1/snapshots/' + result.snapshotId" target="_blank" rel="noopener"
        >查看已保存的原文快照 ↗</a
      >
      <AnalysisPanel v-if="!projectId" :snapshot-id="result.snapshotId" :key="result.snapshotId" />
    </div>
  </section>
</template>

<style scoped>
.import-panel {
  background: #fff;
  border: 1px solid #dce5d9;
  border-radius: 12px;
  padding: 25px;
  margin-bottom: 36px;
}
.file-label {
  display: inline-block;
  padding: 12px 20px;
  background: #244840;
  color: #fff;
  border-radius: 7px;
  cursor: pointer;
  font-size: 13px;
}
.file-label:focus-within {
  outline: 3px solid #8ca781;
  outline-offset: 3px;
}
.file-label.disabled {
  opacity: 0.5;
  cursor: wait;
}
input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}
.import-help {
  font-size: 11px;
  overflow-wrap: anywhere;
}
.import-error,
.import-success {
  padding: 18px;
  margin-top: 18px;
  border-radius: 8px;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.import-error {
  background: #fff1ed;
  color: #8b3d2f;
}
.import-success {
  background: #edf3e8;
  color: #32583b;
}
.import-success a {
  font-size: 12px;
  text-decoration: underline;
}
.import-error li {
  margin: 8px 0;
}
</style>
