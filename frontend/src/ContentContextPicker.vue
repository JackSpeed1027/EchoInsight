<!-- 文件职责：分页选择本项目独立内容作为讨论背景，显式关联不冒充作品/作者认证。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { readApi, type Page } from './projectApi'
import type { ContentMaterial } from './contentTypes'
import type { ContentChoice } from './discussionDigest'
const props = defineProps<{ projectId: string; modelValue: ContentChoice; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: ContentChoice] }>()
const items = ref<Page<ContentMaterial> | null>(null),
  page = ref(1),
  loading = ref(false),
  error = ref('')
let generation = 0
/** 候选只读分页；迟到响应不得覆盖另一个项目或材料页。 */
async function load(next = 1) {
  const version = ++generation
  loading.value = true
  error.value = ''
  try {
    const value = await readApi<Page<ContentMaterial>>(
      `/api/v1/projects/${encodeURIComponent(props.projectId)}/content-materials?page=${next}&size=8`,
    )
    if (version === generation) {
      items.value = value
      page.value = next
    }
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '内容材料读取失败。'
  } finally {
    if (version === generation) loading.value = false
  }
}
/** 更换版本清除旧时间，避免将另一份字幕窗口附到新文本。 */
function choose(event: Event) {
  const id = (event.target as HTMLSelectElement).value,
    item = items.value?.items.find((x) => x.id === id)
  emit('update:modelValue', { materialId: id, from: '', to: '', format: item?.format })
}
/** 起止秒数保留0与空值，提交时再次转换并由服务端核验。 */
function range(side: 'from' | 'to', event: Event) {
  emit('update:modelValue', {
    ...props.modelValue,
    [side]: (event.target as HTMLInputElement).value,
  })
}
watch(
  () => props.projectId,
  () => {
    items.value = null
    void load()
  },
  { immediate: true },
)
onUnmounted(() => generation++)
</script>
<template>
  <section class="content-context-picker">
    <label class="field"
      >内容原文对照（可选）
      <select :value="modelValue.materialId" :disabled="disabled || loading" @change="choose">
        <option value="">仅使用评论及已选背景</option>
        <option
          v-if="
            modelValue.materialId && !items?.items.some((item) => item.id === modelValue.materialId)
          "
          :value="modelValue.materialId"
        >
          已选择内容版本 · {{ modelValue.materialId.slice(0, 8) }}
        </option>
        <option v-for="item in items?.items" :key="item.id" :value="item.id">
          {{ item.title }} · {{ item.format }} · 修订{{ item.versionDepth }} · {{ item.createdAt }}
        </option>
      </select>
    </label>
    <p class="small">
      选取你认为相关的文本或字幕。来源和关联由你声明；大材料按问题字词及邻域取完整原段，不代表已读全片。
    </p>
    <div
      v-if="
        modelValue.materialId &&
        (['SRT', 'VTT'].includes(modelValue.format || '') ||
          modelValue.from !== '' ||
          modelValue.to !== '')
      "
      class="content-range"
    >
      <label class="field"
        >内容起点（秒，可空）<input
          :value="modelValue.from"
          type="number"
          min="0"
          step="0.001"
          :disabled="disabled"
          @change="range('from', $event)"
      /></label>
      <label class="field"
        >内容终点（秒，可空）<input
          :value="modelValue.to"
          type="number"
          min="0"
          step="0.001"
          :disabled="disabled"
          @change="range('to', $event)"
      /></label>
    </div>
    <p v-if="error" role="alert" class="error">
      {{ error }} <button type="button" @click="load(page)">重新读取</button>
    </p>
    <p v-if="items && !items.total" class="small">
      还没有独立内容。先到“内容材料”保存文本或字幕，再回到这里选择。
    </p>
    <div v-if="items && items.totalPages > 1" class="pagination">
      <button type="button" :disabled="page <= 1 || loading || disabled" @click="load(page - 1)">
        上一内容页</button
      ><span>{{ page }} / {{ items.totalPages }}</span
      ><button
        type="button"
        :disabled="page >= items.totalPages || loading || disabled"
        @click="load(page + 1)"
      >
        下一内容页
      </button>
    </div>
  </section>
</template>
