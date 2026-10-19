<!-- 文件职责：分页检查原报告的逐条评论与已保存标签，支持字面搜索及直接父评论上下文。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'

/** 直接父评论只作为上下文，不表示模型已取得完整对话。 */
type Parent = { commentId: string; sourceCommentId: string; text: string }
/** 原始标签与结论引用分别展示，人工意见不会改写这些字段。 */
type Comment = {
  commentId: string
  sourceCommentId: string
  text: string
  occurredAt: string
  label: string
  parent: Parent | null
  findingIds: string[]
}
/** 全快照计数与筛选匹配总数区分，避免用当前页当成报告统计分母。 */
type Page = {
  reportId: string
  snapshotId: string
  jobId: string
  sourceMode: string
  snapshotCommentCount: number
  labelCounts: { positive: number; negative: number; neutral: number; unknown: number }
  label: string
  query: string
  total: number
  page: number
  size: number
  totalPages: number
  items: Comment[]
}
const props = defineProps<{ reportId: string }>()
const view = ref<Page | null>(null)
const loading = ref(false)
const error = ref('')
const draftLabel = ref('ALL')
const draftQuery = ref('')
const appliedLabel = ref('ALL')
const appliedQuery = ref('')
const page = ref(1)
let generation = 0

/** 显示规则固定，UNKNOWN 是分类不确定，不是任务处理失败。 */
function labelName(label: string) {
  return (
    (
      {
        ALL: '全部标签',
        POSITIVE: '正面',
        NEGATIVE: '负面',
        NEUTRAL: '中立',
        UNKNOWN: '无法判断',
      } as Record<string, string>
    )[label] || label
  )
}

/** 只根据报告自身来源解释原标签，不读取今天的新任务模型开关。 */
function sourceName(mode: string) {
  return (
    (
      {
        MOCK: 'Mock 演示',
        LOCAL_STUB: '本机替身验证',
        MODEL: '模型分析',
        REAL: '模型分析',
      } as Record<string, string>
    )[mode] || '来源待核对'
  )
}

/**
 * 1、固定已应用筛选；2、只读查询；3、核对响应所属报告；4、忽略切换报告后的迟到响应。
 * 切页只用上一次提交的条件，尚未点击查询的输入不会悄悄改变分页范围。
 */
async function load() {
  const version = ++generation
  const reportId = props.reportId
  loading.value = true
  view.value = null
  error.value = ''
  const params = new URLSearchParams({
    page: String(page.value),
    size: '20',
    label: appliedLabel.value,
    q: appliedQuery.value,
  })
  try {
    const response = await fetch(
      '/api/v1/reports/' + encodeURIComponent(reportId) + '/comments?' + params,
    )
    if (!response.ok)
      throw new Error(
        response.status === 404
          ? '报告不存在，或尚未发布。'
          : response.status === 400
            ? '查询条件有误，请检查标签、关键词和分页。'
            : '明细暂不可用，请稍后重新查询。',
      )
    const body: Page = await response.json()
    if (version !== generation) return
    if (body.reportId !== reportId) throw new Error('明细与当前报告不一致，请重新读取。')
    view.value = body
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '明细读取失败。'
  } finally {
    if (version === generation) loading.value = false
  }
}

/** 新查询从第一页开始；字符上限按 Unicode 码点检查，避免把一个表情算成两个字符。 */
function search() {
  if (loading.value) return
  if (Array.from(draftQuery.value).length > 100) {
    error.value = '关键词最多 100 个字符。'
    return
  }
  appliedLabel.value = draftLabel.value
  appliedQuery.value = draftQuery.value
  page.value = 1
  void load()
}

/** 保留已应用条件翻页；不重新创建分析、核验或模型调用。 */
function turn(next: number) {
  if (loading.value || next < 1) return
  page.value = next
  void load()
}

/** 切换历史报告时重置条件，旧请求失效，避免上一份报告标签混入当前页。 */
watch(
  () => props.reportId,
  () => {
    generation++
    draftLabel.value = 'ALL'
    draftQuery.value = ''
    appliedLabel.value = 'ALL'
    appliedQuery.value = ''
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
  <section class="report-comments" aria-label="评论分类明细">
    <h3>逐条评论与分类</h3>
    <p>检查本报告全部评论的原始标签，包括未被引用和“无法判断”的样本。人工核验意见独立保存。</p>
    <form class="filters" @submit.prevent="search">
      <label
        >原始分类标签
        <select v-model="draftLabel" :disabled="loading">
          <option value="ALL">全部标签</option>
          <option value="POSITIVE">正面</option>
          <option value="NEGATIVE">负面</option>
          <option value="NEUTRAL">中立</option>
          <option value="UNKNOWN">无法判断</option>
        </select>
      </label>
      <label
        >评论原文关键词
        <input
          v-model="draftQuery"
          :disabled="loading"
          maxlength="200"
          placeholder="最多 100 字，仅搜索评论正文"
        />
      </label>
      <button type="submit" :disabled="loading">查询明细</button>
    </form>
    <p v-if="loading" role="status">正在读取已保存明细…</p>
    <p v-if="error" role="alert">
      {{ error }} <button @click="load" :disabled="loading">重新读取明细</button>
    </p>
    <template v-if="view">
      <p>
        {{ sourceName(view.sourceMode) }} · 整份快照 {{ view.snapshotCommentCount }} 条 · 正面
        {{ view.labelCounts.positive }} / 负面 {{ view.labelCounts.negative }} / 中立
        {{ view.labelCounts.neutral }} / 无法判断 {{ view.labelCounts.unknown }}
      </p>
      <p>
        当前条件：{{ labelName(view.label)
        }}{{ view.query ? ' · 原文包含“' + view.query + '”' : '' }} · 匹配
        {{ view.total }} 条。上方标签计数始终针对整份快照。
      </p>
      <p v-if="!view.items.length">
        {{
          view.total === 0
            ? '没有符合条件的评论，可调整标签或关键词。'
            : '当前页没有评论，可返回上一页。'
        }}
      </p>
      <article v-for="item in view.items" :key="item.commentId" class="comment-item">
        <strong>{{ labelName(item.label) }}</strong>
        <span class="reference">{{
          item.findingIds.length
            ? '被 ' + item.findingIds.length + ' 条报告结论引用'
            : '未被选作报告引用'
        }}</span>
        <p class="comment-text">{{ item.text }}</p>
        <p class="metadata">
          评论 ID：{{ item.commentId }} · 来源 ID：{{ item.sourceCommentId
          }}<br />导入时提供的时间：{{ item.occurredAt }}
        </p>
        <details v-if="item.parent">
          <summary>查看直接父评论上下文</summary>
          <p class="comment-text">{{ item.parent.text }}</p>
          <p class="metadata">
            父评论 ID：{{ item.parent.commentId }} · 来源 ID：{{ item.parent.sourceCommentId }}
          </p>
        </details>
      </article>
      <div v-if="view.totalPages" class="pages">
        <button @click="turn(view.page - 1)" :disabled="loading || view.page <= 1">
          上一评论页
        </button>
        <span>第 {{ view.page }} 页 / 共 {{ view.totalPages }} 页</span>
        <button @click="turn(view.page + 1)" :disabled="loading || view.page >= view.totalPages">
          下一评论页
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.report-comments {
  border: 1px solid #dce5d9;
  border-radius: 8px;
  padding: 16px;
  margin-top: 20px;
}
.filters {
  display: flex;
  align-items: end;
  flex-wrap: wrap;
  gap: 12px;
}
.filters label {
  max-width: 100%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
}
.filters input,
.filters select {
  font: inherit;
  max-width: 100%;
  padding: 8px;
  border: 1px solid #cbd9c5;
  border-radius: 5px;
}
.filters input {
  width: 260px;
  box-sizing: border-box;
}
.report-comments p {
  font-size: 12px;
  line-height: 1.9;
}
.comment-item {
  border-top: 1px solid #dce5d9;
  padding: 14px 0;
}
.comment-item strong {
  font-size: 13px;
}
.reference {
  font-size: 11px;
  margin-left: 12px;
  color: #486553;
}
.comment-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.metadata {
  color: #63716a;
  overflow-wrap: anywhere;
}
.pages {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
}
summary {
  cursor: pointer;
  font-size: 12px;
}
</style>
