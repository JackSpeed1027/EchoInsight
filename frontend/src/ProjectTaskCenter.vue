<!-- 文件职责：以一个项目任务页汇总分析与采集、错误与费用，列表只查询，明确操作后才控制原任务。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { readApi, type Page } from './projectApi'
import { taskState, type ProjectTask, type TaskCounts } from './taskCenter'
import { describeFailure } from './publicFailures'
import TaskControlPanel from './TaskControlPanel.vue'
const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{
  request: [snapshotId: string, requestId: string]
  analysis: [snapshotId: string, jobId: string]
  materials: []
}>()
const result = ref<(Page<ProjectTask> & { counts: TaskCounts }) | null>(null)
const kind = ref('ALL'),
  status = ref('ALL'),
  page = ref(1)
const selected = ref<ProjectTask | null>(null)
const loading = ref(false),
  error = ref('')
let generation = 0
/** 一次读取有界列表和全项目计数；不自动选中、取消或启动任何任务。 */
async function load() {
  const version = ++generation
  loading.value = true
  error.value = ''
  try {
    const data = await readApi<Page<ProjectTask> & { counts: TaskCounts }>(
      `/api/v1/projects/${encodeURIComponent(props.projectId)}/tasks?kind=${kind.value}&status=${status.value}&page=${page.value}&size=20`,
    )
    if (version === generation) result.value = data
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '任务列表读取失败。'
  } finally {
    if (version === generation) loading.value = false
  }
}
/** 改筛选回第一页，统计仍保持全项目；成功操作后刷新但不自动进入新报告。 */
function filter() {
  page.value = 1
  void load()
}
/** 翻页只读服务端列表，不改变任务或全局统计。 */
function turn(next: number) {
  page.value = next
  void load()
}
/** 明确携带原快照和原请求进入报告，采集成功则回资料列表。 */
function open(task: ProjectTask) {
  if (task.kind === 'COLLECTION') emit('materials')
  else if (task.snapshotId && task.requestId) emit('request', task.snapshotId, task.requestId)
  else if (task.snapshotId) emit('analysis', task.snapshotId, task.id)
}
watch(
  () => props.projectId,
  () => {
    result.value = null
    selected.value = null
    page.value = 1
    void load()
  },
  { immediate: true },
)
onUnmounted(() => {
  generation++
})
</script>

<template>
  <section class="task-center surface">
    <div class="section-toolbar">
      <div>
        <h2>任务中心</h2>
        <p>采集和分析持续保存在项目中，关闭页面不会取消。操作作用于原任务。</p>
      </div>
      <button type="button" :disabled="loading" @click="load">刷新任务列表</button>
    </div>
    <div v-if="result" class="task-counts" aria-label="全项目任务统计">
      <span
        >全部 <b>{{ result.counts.total }}</b></span
      ><span
        >等待 <b>{{ result.counts.pending }}</b></span
      ><span
        >运行 <b>{{ result.counts.running }}</b></span
      ><span
        >完成 <b>{{ result.counts.success }}</b></span
      ><span
        >失败 <b>{{ result.counts.failed }}</b></span
      ><span
        >取消 <b>{{ result.counts.cancelled }}</b></span
      ><span
        >费用未决 <b>{{ result.counts.feeUncertain }}</b></span
      >
    </div>
    <div class="task-filters">
      <label
        >任务类型<select v-model="kind" @change="filter">
          <option value="ALL">全部类型</option>
          <option value="ANALYSIS">分析</option>
          <option value="COLLECTION">采集</option>
        </select></label
      >
      <label
        >任务状态<select v-model="status" @change="filter">
          <option value="ALL">全部状态</option>
          <option value="ACTIVE">等待或运行</option>
          <option value="SUCCESS">已完成</option>
          <option value="FAILED">失败</option>
          <option value="CANCELLED">已取消</option>
        </select></label
      >
    </div>
    <p v-if="loading" role="status">正在读取任务…</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="result && !result.total">
      当前筛选下没有任务。添加资料或提交问题后，任务会显示在这里。
    </p>
    <div class="task-layout">
      <div class="task-list">
        <article
          v-for="item in result?.items"
          :key="item.kind + item.id"
          class="task-card"
          :class="{ chosen: selected?.id === item.id && selected?.kind === item.kind }"
        >
          <p class="small">
            {{ item.kind === 'ANALYSIS' ? '分析任务' : '采集任务' }} · {{ item.createdAt }}
          </p>
          <h3>{{ item.title }}</h3>
          <p v-if="item.instruction" class="task-question">{{ item.instruction }}</p>
          <p>
            <strong>{{ taskState(item.status) }}</strong
            ><span v-if="item.feeUncertain"> · 费用未决</span>
          </p>
          <p v-if="item.status === 'FAILED'">{{ describeFailure(item.errorCode).message }}</p>
          <div class="material-actions">
            <button type="button" class="quiet" @click="selected = item">查看详情与恢复</button
            ><button
              v-if="item.kind === 'ANALYSIS' || item.status === 'SUCCESS'"
              type="button"
              class="quiet"
              @click="open(item)"
            >
              {{ item.kind === 'ANALYSIS' ? '打开原分析' : '查看项目资料' }}
            </button>
          </div>
        </article>
      </div>
      <aside v-if="selected" class="task-detail">
        <h3>选中任务</h3>
        <p>{{ selected.instruction || selected.title }}</p>
        <TaskControlPanel
          :project-id="projectId"
          :kind="selected.kind"
          :task-id="selected.id"
          @changed="load"
        />
      </aside>
    </div>
    <div v-if="result && result.totalPages > 1" class="pagination">
      <button :disabled="page <= 1 || loading" @click="turn(page - 1)">上一任务页</button
      ><span>{{ page }} / {{ result.totalPages }}（筛选{{ result.total }}条）</span
      ><button :disabled="page >= result.totalPages || loading" @click="turn(page + 1)">
        下一任务页
      </button>
    </div>
  </section>
</template>

<style scoped>
.task-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 0.7rem 1.2rem;
  padding: 1rem;
  background: #eef4fa;
  border-radius: 10px;
}
.task-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  margin-block: 1rem;
}
.task-filters label {
  display: grid;
  gap: 0.4rem;
  min-width: 10rem;
}
.task-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 1rem;
}
.task-card {
  padding: 1rem;
  margin-block: 0.8rem;
  border: 1px solid #e0e6ef;
  border-radius: 10px;
}
.task-card.chosen {
  border-color: #5078b9;
  background: #f5f8ff;
}
.task-card h3,
.task-question,
.task-detail p {
  overflow-wrap: anywhere;
}
.task-detail {
  padding: 1rem;
  border-radius: 10px;
  background: #f1f5f9;
}
@media (min-width: 1200px) {
  .task-layout:has(.task-detail) {
    grid-template-columns: minmax(0, 1fr) minmax(340px, 0.9fr);
    align-items: start;
  }
  .task-detail {
    position: sticky;
    top: 1rem;
  }
}
</style>
