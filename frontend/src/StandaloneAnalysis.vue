<!-- 文件职责：兼容旧报告分享链接，用独立页面展示分析，不再加载全部历史表格。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import AnalysisPanel from './AnalysisPanel.vue'
import { readApi } from './projectApi'
const props = defineProps<{ jobId: string }>()
const emit = defineEmits<{ home: [] }>()
const snapshot = ref(''),
  title = ref(''),
  error = ref('')
let generation = 0
/** 旧链接先恢复任务绑定的快照与标题，不重新创建任务或改写浏览器历史。 */
async function load() {
  const version = ++generation
  snapshot.value = ''
  error.value = ''
  try {
    const job = await readApi<{ snapshotId: string }>(
      '/api/v1/analysis-jobs/' + encodeURIComponent(props.jobId),
    )
    const data = await readApi<{ document: { content: { title: string } } }>(
      '/api/v1/snapshots/' + encodeURIComponent(job.snapshotId),
    )
    if (version === generation) {
      snapshot.value = job.snapshotId
      title.value = data.document.content.title
    }
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '报告读取失败。'
  }
}
watch(
  () => props.jobId,
  () => void load(),
  { immediate: true },
)
onUnmounted(() => generation++)
</script>
<template>
  <section>
    <button class="back-link" @click="emit('home')">← 我的项目</button>
    <div class="page-heading">
      <div>
        <p class="eyebrow">ANALYSIS</p>
        <h1>{{ title || '分析详情' }}</h1>
        <p>保留原资料版本的分析记录。</p>
      </div>
    </div>
    <p v-if="error" role="alert" class="error">
      {{ error }} <button @click="load">重新读取</button>
    </p>
    <p v-if="!snapshot && !error" role="status">正在读取报告…</p>
    <AnalysisPanel v-if="snapshot" :snapshot-id="snapshot" :initial-job-id="jobId" />
  </section>
</template>
