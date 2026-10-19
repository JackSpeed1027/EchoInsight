<!-- 文件职责：展示指定命题、多主张、独立分母与原文语境，不把模型判断当事实认证。 -->
<script setup lang="ts">
import { computed, ref, watch, onUnmounted } from 'vue'
import { decisionPage, parentContext, stanceName, type StanceResult } from './stanceView'
import { evidenceSource, readApi } from './projectApi'
const props = defineProps<{
  projectId?: string
  requestId?: string
  result: StanceResult
  evidence: Array<{
    id: string
    parentCommentId: string | null
    text: string
    sourceRole?: string | null
    title?: string | null
    startMs?: number | null
    endMs?: number | null
  }>
}>()
const page = ref(1)
type Evidence = (typeof props.evidence)[number]
type RemotePage = {
  items: StanceResult['decisions']
  total: number
  page: number
  size: number
  totalPages: number
  evidence: Evidence[]
}
const remote = ref<RemotePage | null>(null)
const filter = ref('ALL')
const query = ref('')
const loading = ref(false)
const error = ref('')
let generation = 0
const visible = computed(() =>
  remote.value
    ? { ...remote.value, pages: remote.value.totalPages }
    : decisionPage(props.result.decisions, page.value),
)
const pageEvidence = computed(() => remote.value?.evidence || props.evidence)
/** 服务端返回当前页决策与完整父链，旧迟到响应不能覆盖新筛选；只读不创建任务。 */
async function loadPage() {
  const version = ++generation
  remote.value = null
  error.value = ''
  if (!props.projectId || !props.requestId || !props.result.analyzed) return
  loading.value = true
  try {
    const params = new URLSearchParams({
      page: String(page.value),
      size: '20',
      filter: filter.value,
      query: query.value,
    })
    const result = await readApi<RemotePage>(
      `/api/v1/projects/${props.projectId}/analysis-requests/${props.requestId}/decisions?${params}`,
    )
    if (version === generation) remote.value = result
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '判断列表读取失败。'
  } finally {
    if (version === generation) loading.value = false
  }
}
watch(
  () => [
    props.projectId,
    props.requestId,
    props.result.analyzed,
    page.value,
    filter.value,
    query.value,
  ],
  loadPage,
  { immediate: true },
)
onUnmounted(() => {
  generation++
})
watch(
  () => props.result,
  () => {
    page.value = 1
  },
)
/** 只读取本次产物所携带证据，不查询项目最新文本替换旧语境。 */
function quotes(ids: string[]) {
  return pageEvidence.value.filter((item) => ids.includes(item.id))
}
</script>

<template>
  <section class="stance-result" aria-label="命题立场分析结果">
    <h3>这次判断针对的命题</h3>
    <blockquote>{{ result.specification.proposition }}</blockquote>
    <p>支持与反对都以这句话为标准；分析评论表达，不判断命题是否真实。</p>
    <p v-if="!result.analyzed" role="status">
      命题已保存。当前为结构演示，没有进行立场判断，暂无统计。
    </p>
    <p v-if="result.completion?.coverageVerified" class="small">
      已完整处理本快照 {{ result.completion.completedComments }}/{{
        result.completion.targetComments
      }}
      条目标评论， 共
      {{ result.completion.batchCount }} 批；重复父语境不重复计数。此范围不是平台全量。
    </p>
    <template v-if="result.analyzed && result.statistics">
      <p>
        本次选入 {{ result.statistics.selectedComments }} 条，逐条记录
        {{ result.statistics.coveredComments }} 条。 其中
        {{ result.statistics.claimComments }} 条提取出 {{ result.statistics.totalClaims }} 条主张。
      </p>
      <div class="stance-counts">
        <span>支持：{{ result.statistics.supportClaims }} 条主张</span>
        <span>反对：{{ result.statistics.opposeClaims }} 条主张</span>
        <span>有限定条件：{{ result.statistics.qualifiedClaims }} 条主张</span>
        <span>无法判断：{{ result.statistics.unknownClaims }} 条主张</span>
      </div>
      <p class="small">
        按评论去重：支持 {{ result.statistics.supportComments }} 条、反对
        {{ result.statistics.opposeComments }} 条、 有条件
        {{ result.statistics.qualifiedComments }} 条；其中
        {{ result.statistics.mixedComments }} 条同时含支持与反对。
        各项有重叠，不能相加当人数或总体支持率。
      </p>
      <p>
        其余评论：仅提问 {{ result.statistics.questionComments }} 条、无关
        {{ result.statistics.irrelevantComments }} 条、 无法判断
        {{ result.statistics.unknownComments }} 条。未选入评论不计入这些统计。
      </p>
      <div v-if="projectId && requestId" class="stance-counts">
        <label
          >筛选评论<select v-model="filter" @change="page = 1">
            <option value="ALL">全部已判断</option>
            <option value="SUPPORT">支持命题</option>
            <option value="OPPOSE">反对命题</option>
            <option value="QUALIFIED">有条件</option>
            <option value="MIXED">同时支持和反对</option>
            <option value="QUESTION">仅提问</option>
            <option value="IRRELEVANT">无关</option>
            <option value="UNKNOWN">无法判断</option>
          </select></label
        >
        <label
          >在结果及原文中查找<input v-model.lazy="query" maxlength="80" @change="page = 1"
        /></label>
      </div>
      <p v-if="loading" role="status">正在读取判断与原文…</p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <p v-if="remote">
        筛选结果 {{ remote.total }} 条；上面的统计仍涵盖原全部已处理评论，各筛选可能重叠。
      </p>
      <article
        v-for="(decision, index) in visible.items"
        :key="decision.commentId"
        class="decision-card"
      >
        <h4>
          评论 {{ (visible.page - 1) * 20 + index + 1 }} · {{ stanceName(decision.coverage) }}
        </h4>
        <p>{{ decision.reason }}</p>
        <blockquote v-for="quote in quotes([decision.commentId])" :key="quote.id">
          {{ quote.text }}
        </blockquote>
        <details v-if="parentContext(decision.commentId, pageEvidence).length">
          <summary>查看父评论语境</summary>
          <blockquote
            v-for="quote in parentContext(decision.commentId, pageEvidence)"
            :key="quote.id"
          >
            {{ quote.text }}
          </blockquote>
        </details>
        <section
          v-for="(claim, claimIndex) in decision.claims"
          :key="claimIndex"
          class="stance-claim"
        >
          <strong>{{ stanceName(claim.position) }}：{{ claim.statement }}</strong>
          <p v-if="claim.condition">成立条件：{{ claim.condition }}</p>
          <p>判断依据：{{ claim.reason }}</p>
          <details>
            <summary>查看原文依据与来源</summary>
            <blockquote v-for="quote in quotes(claim.evidenceIds)" :key="quote.id">
              <small>{{ evidenceSource(quote) }}</small>
              <p>{{ quote.text }}</p>
            </blockquote>
          </details>
        </section>
      </article>
      <nav v-if="visible.pages > 1" class="stance-counts" aria-label="评论判断分页">
        <button type="button" :disabled="visible.page === 1" @click="page = visible.page - 1">
          上一页
        </button>
        <span
          >第 {{ visible.page }} / {{ visible.pages }} 页 · 每页20条，统计涵盖全部已处理评论</span
        >
        <button
          type="button"
          :disabled="visible.page === visible.pages"
          @click="page = visible.page + 1"
        >
          下一页
        </button>
      </nav>
    </template>
  </section>
</template>

<style scoped>
.stance-result {
  margin: 1.25rem 0;
  padding: 1.25rem;
  background: var(--surface, #fff);
  border: 1px solid #dbe2ed;
  border-radius: 16px;
}
.stance-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  padding: 1rem 0;
  font-weight: 600;
}
.decision-card {
  border-top: 1px solid #dbe2ed;
  padding: 1rem 0;
}
.stance-claim {
  padding: 0.8rem;
  margin-top: 0.5rem;
  background: #f6f8fc;
  border-radius: 8px;
}
blockquote {
  margin: 0.8rem 0;
  padding: 0.7rem 1rem;
  border-left: 3px solid #9eadd0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.small,
small {
  color: #526078;
}
</style>
