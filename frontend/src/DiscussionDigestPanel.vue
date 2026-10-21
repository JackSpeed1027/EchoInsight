<!-- 文件职责：完整讨论归并概览、分页中间摘要、原文证据和物理收费出处，不触发模型调用。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { readApi, evidenceSource } from './projectApi'
import { digestNodeName, type DigestProcessing } from './discussionDigest'
const props = defineProps<{
  projectId: string
  requestId: string
  processing: DigestProcessing
  sourceMode: string
}>()
type Finding = {
  id: string
  question?: string
  summary?: string
  statement?: string
  evidenceIds: string[]
}
type Node = {
  index: number
  phase: string
  children: number[]
  targetIds: string[]
  summary: { issues: Finding[]; claims: Finding[] }
}
type Evidence = {
  id: string
  text: string
  sourceRole?: string
  title?: string
  startMs?: number | null
  endMs?: number | null
  startChar?: number | null
  endChar?: number | null
}
type NodePage = {
  items: Node[]
  total: number
  page: number
  totalPages: number
  evidence: Evidence[]
}
const view = ref<NodePage | null>(null),
  loading = ref(false),
  error = ref('')
let generation = 0
/** 仅用户展开后读取有限节点和引用，首屏不下载完整中间产物。 */
async function load(page = 1) {
  const version = ++generation
  loading.value = true
  error.value = ''
  try {
    const value = await readApi<NodePage>(
      `/api/v1/projects/${encodeURIComponent(props.projectId)}/analysis-requests/${encodeURIComponent(props.requestId)}/synthesis-nodes?page=${page}&size=3`,
    )
    if (version === generation) view.value = value
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '归纳过程读取失败。'
  } finally {
    if (version === generation) loading.value = false
  }
}
/** 展开不创建任务或新模型调用。 */
function opened(event: Event) {
  if ((event.target as HTMLDetailsElement).open && !view.value && !loading.value) void load()
}
/** 原文来自本页引用和祖先，摘要文字不是新来源。 */
function quotes(ids: string[]) {
  return view.value?.evidence.filter((e) => ids.includes(e.id)) || []
}
watch(
  () => props.requestId,
  () => {
    generation++
    view.value = null
    error.value = ''
  },
)
onUnmounted(() => generation++)
</script>
<template>
  <section class="surface digest-summary">
    <h3>完整讨论分析</h3>
    <p v-if="sourceMode === 'STRUCTURAL_DEMO'">
      已规划{{ processing.mapCount }}片和{{
        processing.reductionCount
      }}次汇总；免费演示未生成语义结论。
    </p>
    <p v-else>
      已处理{{ processing.targetComments }}条目标评论，完成{{ processing.completedMaps }}片（复用{{
        processing.reuse?.reusedChunks || 0
      }}片）和{{ processing.completedReductions }}次汇总。
    </p>
    <p class="small">
      完整针对当前快照；摘要可能遗漏或误读，不能据此计算观众支持率或认证作者意图。
    </p>
    <details v-if="processing.reuse?.reusedChunks">
      <summary>复用来源与费用归属（{{ processing.reuse.reusedChunks }}片）</summary>
      <p class="small">
        基准 {{ processing.reuse.baselineRequestId }}。历史费用保留在原任务，本次只记录实际新调用。
      </p>
      <p v-for="origin in processing.reuse.origins" :key="origin.index" class="small">
        当前第{{ origin.index + 1 }}片 ← 原任务 {{ origin.sourceJobId }} 第{{
          origin.sourceBatchIndex + 1
        }}片
      </p>
    </details>
    <details v-if="sourceMode !== 'STRUCTURAL_DEMO'" @toggle="opened">
      <summary>回看分片归纳与汇总依据</summary>
      <p v-if="loading" role="status">正在读取已保存结果…</p>
      <p v-if="error" role="alert" class="error">
        {{ error }} <button @click="load(view?.page || 1)">重新读取</button>
      </p>
      <article v-for="node in view?.items" :key="node.index" class="digest-node">
        <span class="source-badge">{{ digestNodeName(node.phase) }} · {{ node.index + 1 }}</span>
        <p class="small">
          {{
            node.phase === 'MAP'
              ? `${node.targetIds.length}条归属目标`
              : `汇总${node.children.length}个子片`
          }}；这是生成摘要，需回看原文。
        </p>
        <div v-for="issue in node.summary.issues" :key="issue.id">
          <h4>{{ issue.question }}</h4>
          <p>{{ issue.summary }}</p>
          <details>
            <summary>原文依据</summary>
            <blockquote v-for="quote in quotes(issue.evidenceIds)" :key="quote.id">
              <small>{{ evidenceSource(quote) }}</small>
              <p>{{ quote.text }}</p>
            </blockquote>
          </details>
        </div>
        <details v-if="node.summary.claims.length">
          <summary>该步骤整理的观点（{{ node.summary.claims.length }}项）</summary>
          <p v-for="claim in node.summary.claims" :key="claim.id">{{ claim.statement }}</p>
        </details>
      </article>
      <div v-if="view && view.totalPages > 1" class="pagination">
        <button :disabled="view.page <= 1 || loading" @click="load(view.page - 1)">
          上一归纳页</button
        ><span>{{ view.page }} / {{ view.totalPages }}</span
        ><button :disabled="view.page >= view.totalPages || loading" @click="load(view.page + 1)">
          下一归纳页
        </button>
      </div>
    </details>
  </section>
</template>
<style scoped>
.digest-node {
  padding: 1rem 0;
  border-top: 1px solid var(--line, #e1e7e4);
  margin-top: 1rem;
}
.digest-node blockquote {
  margin: 1rem 0;
  padding: 1rem;
  background: var(--soft, #f5f8f7);
  border-left: 3px solid var(--accent, #28705c);
}
.digest-summary details {
  margin-top: 1rem;
}
</style>
