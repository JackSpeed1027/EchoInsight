<!-- 文件职责：读取原任务安全详情，在明确影响后执行取消/继续/安全重试，防止迟到响应跨项目覆盖。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { readApi } from './apiClient'
import {
  taskState,
  actionCopy,
  budgetText,
  type TaskKind,
  type TaskAction,
  type TaskDetail,
} from './taskCenter'
const props = defineProps<{ projectId: string; kind: TaskKind; taskId: string }>()
const emit = defineEmits<{ changed: [detail: TaskDetail] }>()
const detail = ref<TaskDetail | null>(null)
const pending = ref<TaskAction | null>(null)
const busy = ref(false),
  loading = ref(false),
  error = ref('')
let generation = 0
/** 将固定控制动作翻译为中文，未知历史值保留原值。 */
function actionName(action: string) {
  return (
    ({ CANCEL: '取消', RESUME: '继续', RETRY: '安全重试' } as Record<string, string>)[action] ||
    action
  )
}
/** 路径完全来自当前项目和类型身份，正文、密钥、工作空间不进入URL。 */
function endpoint() {
  return `/api/v1/projects/${encodeURIComponent(props.projectId)}/tasks/${props.kind}/${encodeURIComponent(props.taskId)}`
}
/** 查询详情不派工；响应必须匹配当前身份，切换任务时不能沿用旧按钮资格。 */
async function load() {
  const version = ++generation
  loading.value = true
  error.value = ''
  pending.value = null
  try {
    const result = await readApi<TaskDetail>(endpoint())
    if (version === generation) detail.value = result
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '任务详情读取失败。'
  } finally {
    if (version === generation) loading.value = false
  }
}
/** 1、显示影响并确认；2、提交原任务操作；3、采用服务端返回状态；4、通知外层重查列表/报告。 */
async function confirm() {
  const action = pending.value
  if (!action || busy.value || !detail.value) return
  const version = generation
  const url = endpoint()
  busy.value = true
  error.value = ''
  try {
    const result = await readApi<TaskDetail>(`${url}/${action}`, { method: 'POST' })
    if (version !== generation) return
    detail.value = result
    pending.value = null
    emit('changed', result)
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '操作未被接受，请重新查询状态。'
  } finally {
    if (version === generation) busy.value = false
  }
}
watch(
  () => [props.projectId, props.kind, props.taskId],
  () => {
    detail.value = null
    busy.value = false
    void load()
  },
  { immediate: true },
)
onUnmounted(() => {
  generation++
})
</script>

<template>
  <section class="task-control" aria-label="任务控制与恢复">
    <div class="section-toolbar">
      <h3>任务控制与恢复</h3>
      <button type="button" class="quiet" :disabled="loading || busy" @click="load">
        刷新任务详情
      </button>
    </div>
    <p v-if="loading" role="status">正在核对任务和费用状态…</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <template v-if="detail">
      <p>
        <strong>{{ taskState(detail.task.status) }}</strong> · 已执行 {{ detail.task.attempts }} 次
      </p>
      <p v-if="detail.task.feeUncertain" class="task-warning" role="status">
        存在已经发出、结果与费用未确认的模型调用。预留继续保留，暂不能继续或直接重试。
      </p>
      <p v-if="detail.task.status === 'CANCELLED'">
        任务已取消，原资料和成功检查点已保留。取消不代表已退款或来源采齐。
      </p>
      <p v-if="kind === 'COLLECTION'">
        已保存 {{ detail.completedPages }} 个分页检查点（{{ detail.checkpointBytes }}
        字节）；旧限量任务没有分页检查点。
      </p>
      <div v-if="detail.model" class="task-budget">
        <p>已结算：{{ budgetText(detail.model.budget.settledUnits, detail.model.budget.unit) }}</p>
        <p>
          未结清预留：{{ budgetText(detail.model.budget.reservedUnits, detail.model.budget.unit) }}
        </p>
        <p>
          原任务预算：{{ budgetText(detail.model.budget.limitUnits, detail.model.budget.unit) }}
        </p>
        <p>模型批次 {{ detail.model.total }} 个；此处最多展示前20批摘要。</p>
      </div>
      <div class="material-actions">
        <button
          v-if="detail.task.canCancel"
          type="button"
          :disabled="busy || loading"
          @click="pending = 'cancel'"
        >
          取消此任务
        </button>
        <button
          v-if="detail.task.canResume"
          type="button"
          :disabled="busy || loading"
          @click="pending = 'resume'"
        >
          继续原任务
        </button>
        <button
          v-if="detail.task.canRetry"
          type="button"
          :disabled="busy || loading"
          @click="pending = 'retry'"
        >
          安全重试原任务
        </button>
      </div>
      <div v-if="pending" class="task-confirm" role="group" aria-label="确认任务操作">
        <p>{{ actionCopy(kind, pending) }}</p>
        <button type="button" class="primary" :disabled="busy" @click="confirm">
          {{
            busy
              ? '正在提交…'
              : '确认' +
                (pending === 'cancel' ? '取消' : pending === 'resume' ? '继续' : '安全重试')
          }}
        </button>
        <button type="button" class="quiet" :disabled="busy" @click="pending = null">返回</button>
      </div>
      <details>
        <summary>
          恢复边界与控制历史（最近{{ detail.recentEvents.length }} / {{ detail.eventCount }}条）
        </summary>
        <ul>
          <li v-for="note in detail.notes" :key="note">{{ note }}</li>
        </ul>
        <p v-if="!detail.eventCount">尚无此入口的控制记录；旧操作不会补写为历史。</p>
        <ol>
          <li v-for="(event, index) in detail.recentEvents" :key="index">
            {{ event.createdAt }} · {{ actionName(event.action) }}：{{
              taskState(event.previousStatus)
            }}
            → {{ taskState(event.nextStatus) }}
          </li>
        </ol>
      </details>
    </template>
  </section>
</template>

<style scoped>
.task-control {
  margin-top: 1rem;
  padding: 1rem;
  border: 1px solid #dce4ec;
  border-radius: 12px;
  background: #f8fafc;
}
.task-control h3 {
  margin: 0;
}
.task-budget {
  font-size: 0.9rem;
  color: #475569;
}
.task-confirm,
.task-warning {
  padding: 1rem;
  border-radius: 8px;
  background: #fff4dc;
  margin-block: 1rem;
}
.task-confirm button {
  margin-right: 0.6rem;
}
.task-control details {
  margin-top: 1rem;
}
.task-control li {
  margin-block: 0.5rem;
  overflow-wrap: anywhere;
}
</style>
