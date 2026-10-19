<!-- 文件职责：只展示一页分析摘要，点击后打开独立分析页，不在历史下方堆叠报告。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { readApi, jobStatus, sourceName, type Page } from './projectApi'
type Item = {
  id: string
  snapshotId: string
  title: string
  sourceType: string
  status: string
  createdAt: string
  reportId: string | null
}
const props = defineProps<{ projectId?: string }>()
const emit = defineEmits<{ open: [item: Item] }>()
const history = ref<Page<Item> | null>(null)
const page = ref(1),
  status = ref('ALL'),
  loading = ref(false),
  error = ref('')
let generation = 0
/** 只读服务端分页；项目参数由当前页面明确传入，不在浏览器猜测归属。 */
async function load() {
  const version = ++generation
  loading.value = true
  error.value = ''
  const query = new URLSearchParams({ page: String(page.value), size: '10', status: status.value })
  if (props.projectId) query.set('projectId', props.projectId)
  try {
    const result = await readApi<Page<Item>>('/api/v1/analysis-jobs?' + query)
    if (version === generation) history.value = result
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '读取失败。'
  } finally {
    if (version === generation) loading.value = false
  }
}
/** 改条件回到第一页，避免停留在不存在的末页。 */
function filter() {
  page.value = 1
  void load()
}
/** 翻页仅变读取范围，不创建、恢复或重试任务。 */
function turn(next: number) {
  page.value = next
  void load()
}
watch(
  () => props.projectId,
  () => {
    page.value = 1
    status.value = 'ALL'
    void load()
  },
  { immediate: true },
)
onUnmounted(() => {
  generation++
})
</script>
<template>
  <section class="history-page" aria-label="分析记录">
    <div class="section-toolbar">
      <h2>历史反馈记录</h2>
      <button class="quiet" @click="load" :disabled="loading">刷新记录</button>
    </div>
    <p class="scope-copy">
      这是旧版反馈情感任务的历史；新的讨论分析及失败状态在项目“智能分析”的请求记录中查看。
    </p>
    <div class="history-filter">
      <label
        >分析状态
        <select v-model="status" @change="filter">
          <option value="ALL">全部状态</option>
          <option value="SUCCESS">分析完成</option>
          <option value="RUNNING">正在分析</option>
          <option value="PENDING">等待分析</option>
          <option value="FAILED">分析失败</option>
          <option value="CANCELLED">已取消</option>
        </select></label
      >
      <span class="small">{{ history?.total || 0 }} 条记录</span>
    </div>
    <p v-if="error" role="alert" class="error">
      {{ error }} <button @click="load">重新读取</button>
    </p>
    <p v-if="loading" role="status">正在读取分析记录…</p>
    <div v-if="history && !history.total && !loading" class="empty-state">
      <h2>还没有分析记录</h2>
      <p>此页保留旧情感报告。自然语言讨论请求请到项目“智能分析”查看。</p>
    </div>
    <div class="history-list" v-else>
      <article class="history-row" v-for="item in history?.items || []" :key="item.id">
        <div>
          <span class="source-badge">{{ sourceName(item.sourceType) }}</span>
          <h3>{{ item.title }}</h3>
          <p>{{ item.createdAt }}</p>
        </div>
        <span class="state-badge" :class="item.status.toLowerCase()">{{
          jobStatus(item.status)
        }}</span>
        <button @click="emit('open', item)">
          {{ item.status === 'SUCCESS' ? '查看报告' : '查看分析' }}
        </button>
      </article>
    </div>
    <div v-if="history && history.totalPages > 1" class="pagination">
      <button :disabled="loading || page <= 1" @click="turn(page - 1)">上一页</button
      ><span>{{ page }} / {{ history.totalPages }}</span
      ><button :disabled="loading || page >= history.totalPages" @click="turn(page + 1)">
        下一页
      </button>
    </div>
  </section>
</template>
