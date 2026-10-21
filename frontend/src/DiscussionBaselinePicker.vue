<!-- 文件职责：只列同作品成功新版语义报告，显式选择才复用；预检仍核对问题/背景/模型计划。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { readApi, type Page } from './projectApi'
const props = defineProps<{
  projectId: string
  snapshotId: string
  modelValue: string
  disabled?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [id: string]
  chosen: [item: { id: string; instruction: string }]
}>()
type Candidate = { id: string; instruction: string; createdAt: string; snapshotId: string }
const items = ref<Page<Candidate> | null>(null),
  page = ref(1),
  loading = ref(false),
  error = ref('')
let generation = 0
/** 基准分页由服务器限定项目/作品，不下载所有报告也不自动选最近一份。 */
async function load(next = 1) {
  const version = ++generation
  loading.value = true
  error.value = ''
  if (!props.snapshotId) {
    items.value = null
    loading.value = false
    return
  }
  try {
    const query = new URLSearchParams({
      baselineForSnapshotId: props.snapshotId,
      page: String(next),
      size: '6',
    })
    const result = await readApi<Page<Candidate>>(
      `/api/v1/projects/${encodeURIComponent(props.projectId)}/analysis-requests?${query}`,
    )
    if (version === generation) {
      items.value = result
      page.value = next
    }
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '基准读取失败。'
  } finally {
    if (version === generation) loading.value = false
  }
}
/** 用户明确选择后沿用原问题，不在后台悄悄改变分析目标。 */
function choose(event: Event) {
  const id = (event.target as HTMLSelectElement).value
  emit('update:modelValue', id)
  const item = items.value?.items.find((x) => x.id === id)
  if (item) emit('chosen', item)
}
watch(
  () => [props.projectId, props.snapshotId],
  () => {
    items.value = null
    void load()
  },
  { immediate: true },
)
onUnmounted(() => generation++)
</script>
<template>
  <section class="baseline-picker">
    <label class="field"
      >更新分析方式
      <select :value="modelValue" :disabled="disabled || loading" @change="choose">
        <option value="">全部重新分析</option>
        <option
          v-if="modelValue && !items?.items.some((item) => item.id === modelValue)"
          :value="modelValue"
        >
          已选基准 · {{ modelValue.slice(0, 8) }}
        </option>
        <option v-for="item in items?.items" :key="item.id" :value="item.id">
          {{ item.instruction }} · {{ item.snapshotId === snapshotId ? '当前版本' : '其他版本' }} ·
          {{ item.createdAt }} · {{ item.id.slice(0, 8) }}
        </option>
      </select>
    </label>
    <p class="small">
      可选同作品的新版完整报告。仅相同问题、背景和模型计划下的相同分片可复用，全部汇总仍重新执行；复用会保留旧误读。
    </p>
    <p v-if="items && !items.total" class="small">暂无合格基准；免费结构演示和旧协议不能复用。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="pagination">
      <button type="button" class="quiet" :disabled="loading || disabled" @click="load(page)">
        刷新基准</button
      ><template v-if="items && items.totalPages > 1"
        ><button type="button" :disabled="page <= 1 || loading || disabled" @click="load(page - 1)">
          上一基准页</button
        ><span>{{ page }} / {{ items.totalPages }}</span
        ><button
          type="button"
          :disabled="page >= items.totalPages || loading || disabled"
          @click="load(page + 1)"
        >
          下一基准页
        </button></template
      >
    </div>
  </section>
</template>
