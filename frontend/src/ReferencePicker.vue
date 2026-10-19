<!-- 文件职责：分页选择最多3份参考版本，跨页保留选择；选择本身不创建或执行模型任务。 -->
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { readApi, type Page } from './projectApi'
const props = defineProps<{ projectId: string; referenceIds: string[] }>()
const emit = defineEmits<{ change: [ids: string[]] }>()
type Summary = { id: string; title: string; textChars: number }
const result = ref<Page<Summary> | null>(null)
const known = ref<Record<string, Summary>>({})
const page = ref(1)
const error = ref('')
let alive = true
let generation = 0
const chosen = computed(() =>
  props.referenceIds.map((id) => known.value[id]).filter((item): item is Summary => Boolean(item)),
)
const totalChars = computed(() => chosen.value.reduce((sum, item) => sum + item.textChars, 0))
/** 每次只读10份摘要；选择集合独立于页面，不因翻页丢失。 */
async function load() {
  const version = ++generation
  try {
    const value = await readApi<Page<Summary>>(
      `/api/v1/projects/${props.projectId}/reference-materials?page=${page.value}&size=10`,
    )
    if (!alive || version !== generation) return
    result.value = value
    value.items.forEach((item) => {
      known.value[item.id] = item
    })
  } catch (caught) {
    if (alive && version === generation)
      error.value = caught instanceof Error ? caught.message : '参考列表读取失败。'
  }
}
/** 历史请求可能引用较早版本，按明确身份补齐名称，不静默换成最近资料。 */
async function restore() {
  for (const id of props.referenceIds) {
    if (known.value[id]) continue
    try {
      const value = await readApi<{ summary: Summary }>(
        `/api/v1/projects/${props.projectId}/reference-materials/${id}`,
      )
      if (alive) known.value[id] = value.summary
    } catch (caught) {
      if (alive) error.value = caught instanceof Error ? caught.message : '旧参考版本读取失败。'
    }
  }
}
/** 前端限制便于操作，真正的成员归属和12000字总限仍由服务器强制检查。 */
function toggle(id: string) {
  const values = new Set(props.referenceIds)
  if (values.has(id)) values.delete(id)
  else if (values.size < 3) values.add(id)
  emit('change', [...values].sort())
}
/** 分页只改变浏览范围，不改变已选版本。 */
function turn(next: number) {
  page.value = next
  void load()
}
watch(() => props.referenceIds, restore, { immediate: true })
onMounted(load)
onUnmounted(() => {
  alive = false
  generation++
})
</script>
<template>
  <section class="reference-picker">
    <p class="small">
      最多选3份、合计12000字；不选也可分析评论。可到“添加资料 → 参考文本”保存新版本。
    </p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="result && !result.total">项目还没有参考文本。</p>
    <label v-for="item in result?.items" :key="item.id" class="reference-choice"
      ><input
        type="checkbox"
        :checked="referenceIds.includes(item.id)"
        :disabled="referenceIds.length >= 3 && !referenceIds.includes(item.id)"
        @change="toggle(item.id)"
      /><span>{{ item.title }} · {{ item.textChars }}字</span></label
    >
    <div v-if="result && result.totalPages > 1" class="pagination">
      <button type="button" :disabled="page <= 1" @click="turn(page - 1)">上一页</button
      ><span>{{ page }} / {{ result.totalPages }}</span
      ><button type="button" :disabled="page >= result.totalPages" @click="turn(page + 1)">
        下一页
      </button>
    </div>
    <p v-if="referenceIds.length" class="scope-copy">
      已选{{ referenceIds.length }}份 · 已知正文{{ totalChars }}字
    </p>
    <button
      v-for="item in chosen"
      :key="item.id"
      type="button"
      class="chosen-reference"
      @click="toggle(item.id)"
    >
      {{ item.title }} · 取消选择
    </button>
    <p v-if="totalChars > 12000" class="error">合计超过12000字，请减少参考资料后再提交。</p>
  </section>
</template>
<style scoped>
.reference-choice {
  display: flex;
  align-items: flex-start;
  gap: 0.6rem;
  padding: 0.5rem 0;
}
.reference-choice input {
  width: auto;
  margin-top: 0.3rem;
}
.chosen-reference {
  margin: 0.4rem 0.4rem 0.4rem 0;
}
</style>
