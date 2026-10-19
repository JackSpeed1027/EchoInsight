<!-- 文件职责：为已发布讨论产物提供可选核验记录，原始分析不会被人工意见覆盖。 -->
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { readApi, evidenceSource, type Page } from './projectApi'
const props = defineProps<{
  projectId: string
  requestId: string
  artifact: {
    issues: Array<{ id: string; question: string }>
    claims: Array<{ id: string; statement: string }>
    assessments: Array<{ issueId: string; conclusion: string }>
    relations: Array<{ fromClaimId: string; toClaimId: string; type: string }>
    evidence: Array<{
      id: string
      text: string
      sourceRole?: string | null
      title?: string | null
      startMs?: number | null
      endMs?: number | null
    }>
  }
}>()
type Input = {
  targetKind: string
  targetId: string
  verdict: string
  reviewer: string
  rationale: string
  evidenceIds: string[]
}
type Review = { id: string; input: Input; createdAt: string }
const target = ref('')
const verdict = ref('UNCERTAIN')
const reviewer = ref('')
const rationale = ref('')
const selected = ref<string[]>([])
const history = ref<Review[]>([])
const page = ref(1)
const pages = ref(0)
const busy = ref(false)
const error = ref('')
let alive = true
let submission: { content: string; key: string } | undefined
const options = computed(() => [
  ...props.artifact.issues.map((i) => ({ value: 'ISSUE:' + i.id, label: '议题：' + i.question })),
  ...props.artifact.claims.map((i) => ({ value: 'CLAIM:' + i.id, label: '观点：' + i.statement })),
  ...props.artifact.assessments.map((i) => ({
    value: 'ASSESSMENT:' + i.issueId,
    label: '评议：' + i.conclusion,
  })),
  ...props.artifact.relations.map((i, n) => ({
    value: 'RELATION:' + n,
    label: '关系：' + i.fromClaimId + ' → ' + i.toClaimId + ' · ' + i.type,
  })),
])
/** 分页读取只追加的核验历史；组件以requestId为key重新挂载以隔离上下文。 */
async function load() {
  try {
    const result = await readApi<Page<Review>>(
      `/api/v1/projects/${props.projectId}/analysis-requests/${props.requestId}/semantic-reviews?page=${page.value}&size=10`,
    )
    if (alive) {
      history.value = result.items
      pages.value = result.totalPages
    }
  } catch (caught) {
    if (alive) error.value = caught instanceof Error ? caught.message : '核验历史读取失败。'
  }
}
/** 新意见使用新键，网络不确定则保留原键；提交不调用模型，也不会把判断当成金标准。 */
async function submit() {
  if (busy.value) return
  const split = target.value.indexOf(':')
  const input: Input = {
    targetKind: target.value.slice(0, split),
    targetId: target.value.slice(split + 1),
    verdict: verdict.value,
    reviewer: reviewer.value.trim(),
    rationale: rationale.value.trim(),
    evidenceIds: [...selected.value].sort(),
  }
  const content = JSON.stringify(input)
  if (submission?.content !== content) submission = { content, key: crypto.randomUUID() }
  busy.value = true
  error.value = ''
  try {
    await readApi(
      `/api/v1/projects/${props.projectId}/analysis-requests/${props.requestId}/semantic-reviews`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': submission!.key },
        body: content,
      },
    )
    if (!alive) return
    submission = undefined
    rationale.value = ''
    page.value = 1
    await load()
  } catch (caught) {
    if (alive) error.value = caught instanceof Error ? caught.message : '核验保存失败。'
  } finally {
    if (alive) busy.value = false
  }
}
/** 分页读取同一请求的核验历史，不产生新意见或模型任务。 */
function turn(next: number) {
  page.value = next
  void load()
}
/** 展示核验判断的中文含义，未识别值保留以便排查版本差异。 */
function name(value: string) {
  return (
    (
      { SUPPORTED: '有依据', UNSUPPORTED: '依据不充分', UNCERTAIN: '暂无法判断' } as Record<
        string,
        string
      >
    )[value] || value
  )
}
onMounted(load)
onUnmounted(() => {
  alive = false
})
</script>
<template>
  <details class="surface semantic-review">
    <summary>核对结论与原文（可选）</summary>
    <p class="small">
      这里保存你的阅读意见，原分析不变。署名是本机记录，不等于独立评测或已认证评审人；不影响正常使用。
    </p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <form @submit.prevent="submit">
      <label class="field"
        >要核对的内容<select v-model="target" required>
          <option value="" disabled>选择议题、观点、关系或评议</option>
          <option v-for="item in options" :key="item.value" :value="item.value">
            {{ item.label }}
          </option>
        </select></label
      >
      <label class="field"
        >你的判断<select v-model="verdict">
          <option value="UNCERTAIN">暂无法判断</option>
          <option value="SUPPORTED">有依据</option>
          <option value="UNSUPPORTED">依据不充分</option>
        </select></label
      >
      <details>
        <summary>选择作为核对依据的原文（最多20条）</summary>
        <label v-for="quote in artifact.evidence" :key="quote.id" class="review-quote"
          ><input
            v-model="selected"
            type="checkbox"
            :value="quote.id"
            :disabled="selected.length >= 20 && !selected.includes(quote.id)"
          /><span
            ><small>{{ evidenceSource(quote) }}</small
            ><span>{{ quote.text }}</span></span
          ></label
        >
      </details>
      <label class="field">署名<input v-model="reviewer" maxlength="80" required /></label>
      <label class="field"
        >为什么这样判断<textarea v-model="rationale" rows="3" maxlength="2000" required />
      </label>
      <button :disabled="busy || !target || !reviewer.trim() || !rationale.trim()">
        保存一条核验意见
      </button>
    </form>
    <article v-for="item in history" :key="item.id" class="issue-card">
      <strong>{{ item.input.reviewer }} · {{ name(item.input.verdict) }}</strong>
      <p>{{ item.input.rationale }}</p>
      <small>{{ item.input.targetKind }} / {{ item.input.targetId }} · {{ item.createdAt }}</small>
    </article>
    <div v-if="pages > 1" class="pagination">
      <button :disabled="page <= 1" @click="turn(page - 1)">上一页</button
      ><span>{{ page }} / {{ pages }}</span
      ><button :disabled="page >= pages" @click="turn(page + 1)">下一页</button>
    </div>
  </details>
</template>
<style scoped>
.semantic-review {
  margin-top: 1.25rem;
}
.review-quote {
  display: flex;
  gap: 0.7rem;
  padding: 0.6rem 0;
}
.review-quote input {
  width: auto;
}
</style>
