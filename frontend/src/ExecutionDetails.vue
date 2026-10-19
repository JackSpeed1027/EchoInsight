<!-- 文件职责：解释分析怎样分批、哪些结果已保存、预算和失败分别意味着什么。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { describeFailure } from './publicFailures'
/** 摘要不包含评论请求体、端点或私有配置。 */
type Call = {
  id: string
  state: string
  reservedUnits: number
  settledUnits: number | null
  inputTokens: number | null
  outputTokens: number | null
  errorCode: string | null
}
/** 每批有独立检查点与最近三次调用。 */
type Batch = { index: number; status: string; attempts: number; recentCalls: Call[] }
/** 根据持久化单位解释历史费用；美元字段是本地估算，不冒充供应商账单。 */
type View = {
  plan: { providerId: string; batchSize: number; timeoutMs: number }
  budget: { limitUnits: number; reservedUnits: number; settledUnits: number; unit: string }
  total: number
  page: number
  size: number
  items: Batch[]
}
const props = defineProps<{ jobId: string; status: string }>()
const view = ref<View | null>(null),
  loading = ref(false),
  error = ref(''),
  page = ref(1)
let generation = 0
/** 将状态翻译为可理解的处理进度。 */
function state(value: string) {
  return (
    (
      {
        READY: '等待调用',
        RUNNING: '处理中',
        RESERVED: '已预留，尚未发送',
        SENT: '已发送，等待响应',
        SUCCEEDED: '已保存结果',
        RELEASED: '未发送，预留已释放',
        OUTCOME_UNKNOWN: '已发出但结果未确认',
      } as Record<string, string>
    )[value] || value
  )
}
/** 错误描述说明业务影响，不把超时当成对方未处理。 */
function failure(value: string | null) {
  return value ? describeFailure(value).message : ''
}
/** 只查询已保存记录，切换任务后忽略迟到响应。 */
async function load() {
  const version = ++generation
  loading.value = true
  error.value = ''
  try {
    const response = await fetch(
      '/api/v1/analysis-jobs/' + props.jobId + '/execution?page=' + page.value + '&size=10',
    )
    if (!response.ok)
      throw new Error(
        response.status === 404
          ? '执行记录暂不可用；若后端仍是旧版本，请在 IDEA 重启后端。'
          : '执行记录读取失败，请稍后刷新。',
      )
    const body: View = await response.json()
    if (version === generation) view.value = body
  } catch (e) {
    if (version === generation) error.value = e instanceof Error ? e.message : '执行记录读取失败。'
  } finally {
    if (version === generation) loading.value = false
  }
}
/** 翻页不重新发出模型请求。 */
function turn(next: number) {
  page.value = next
  void load()
}
/** 任务状态改变时刷新；运行中可手动读取最新检查点。 */
watch(
  () => [props.jobId, props.status],
  (next, previous) => {
    if (!previous || next[0] !== previous[0]) {
      page.value = 1
      view.value = null
    }
    void load()
  },
  { immediate: true },
)
onUnmounted(() => {
  generation++
})
</script>
<template>
  <section class="execution-details" aria-label="分析执行说明">
    <h3>这次分析如何执行</h3>
    <p>
      执行计划及成功检查点持久保存；不同任务的批次含义不同，讨论计划的一个批次不代表只分析一条评论。
    </p>
    <button @click="load" :disabled="loading">刷新执行记录</button>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="view">
      <p>
        当前供应商：{{ view.plan.providerId }} · 计划批大小
        {{ view.plan.batchSize }}（分类为评论数，讨论为一次调用） · 单次等待上限
        {{ view.plan.timeoutMs / 1000 }} 秒。
      </p>
      <p v-if="view.budget.unit !== 'USD_NANOS'">
        <strong>当前是模拟账本，不代表真实费用。</strong> 预算上限 {{ view.budget.limitUnits }} ·
        已预留 {{ view.budget.reservedUnits }} · 已结算 {{ view.budget.settledUnits }}（模拟单位）。
      </p>
      <p v-if="view.budget.unit === 'USD_NANOS'">
        <strong>本地费用估算，非供应商账单。</strong>
        单任务上限 ${{ (view.budget.limitUnits / 1e9).toFixed(9) }} · 已预留 ${{
          (view.budget.reservedUnits / 1e9).toFixed(9)
        }}
        · 已记账估算 ${{ (view.budget.settledUnits / 1e9).toFixed(9) }}。
        不区分缓存与峰谷折扣；未知调用仍占用预留，尚无账单对账入口。
      </p>
      <details>
        <summary>这些额度和状态是什么意思？</summary>
        <ul>
          <li>已预留：调用前暂时占用的额度，防止并发超额。</li>
          <li>已结算：收到并确认结果后，按返回用量记录消耗；内置 Mock 为零。</li>
          <li>结果未确认：请求可能已经处理，不能视为免费失败，先保留记录和额度。</li>
          <li>未发送：没有进入供应商调用，可以释放预留。</li>
        </ul>
      </details>
      <p v-if="!view.total">目前没有分批记录。旧任务的报告仍可查看；新任务开始处理后会记录批次。</p>
      <details v-else open>
        <summary>已保存 {{ view.total }} 个批次记录</summary>
        <article v-for="batch in view.items" :key="batch.index">
          <strong>第 {{ batch.index + 1 }} 批：{{ state(batch.status) }}</strong>
          <p>调用尝试 {{ batch.attempts }} 次，展示最近 {{ batch.recentCalls.length }} 次。</p>
          <p v-for="call in batch.recentCalls" :key="call.id">
            {{ state(call.state) }} · 输入 Token {{ call.inputTokens ?? '未知' }} / 输出 Token
            {{ call.outputTokens ?? '未知' }} · {{ failure(call.errorCode) }}
          </p>
        </article>
        <div class="execution-pages">
          <button @click="turn(page - 1)" :disabled="loading || page <= 1">上一批次页</button
          ><span>第 {{ page }} 页</span
          ><button @click="turn(page + 1)" :disabled="loading || page * 10 >= view.total">
            下一批次页
          </button>
        </div>
      </details>
    </template>
  </section>
</template>
<style scoped>
.execution-details {
  border: 1px solid #dce5d9;
  border-radius: 8px;
  padding: 16px;
  margin-top: 18px;
  background: #f8faf5;
}
.execution-details p,
.execution-details li,
summary {
  font-size: 12px;
  line-height: 1.9;
}
h3 {
  font-size: 15px;
}
article {
  padding: 10px;
  margin-top: 10px;
  background: white;
  border: 1px solid #e1e8dc;
  border-radius: 5px;
}
strong {
  font-size: 12px;
}
summary {
  cursor: pointer;
  margin: 8px 0;
}
.execution-pages {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
button {
  margin: 6px 0;
}
</style>
