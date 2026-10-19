<!-- 文件职责：展示原报告结论和人工核验修订，提交不改变模型原报告。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'

/** 每条核验是独立事件，时间由服务器保存为 UTC。 */
type Review = {
  id: string
  revision: number
  verdict: string
  reason: string
  correctedEvidenceIds: string[]
  createdAt: string
}
/** 原始引用与最新替代引用均来自绑定快照。 */
type Finding = {
  findingId: string
  claim: string
  evidence: { commentId: string; text: string }[]
  latestCorrectedEvidence: { commentId: string; text: string }[]
  reviewCount: number
  history: Review[]
}
/** 浏览器使用服务器返回的历史版本，防止旧页面覆盖较新的意见。 */
type Form = {
  verdict: string
  reason: string
  ids: string
  busy: boolean
  error: string
  key: string
  payload: string
}
const props = defineProps<{ reportId: string }>()
const findings = ref<Finding[]>([]),
  forms = ref<Record<string, Form>>({})
const loading = ref(false),
  error = ref(''),
  notice = ref('')
let generation = 0

/** 将标签转换为阅读名称，保留“不确定”选项以避免强迫给出判断。 */
function name(value: string) {
  return (
    (
      {
        SUPPORTED: '支持结论',
        PARTIAL: '部分支持',
        UNSUPPORTED: '不支持结论',
        UNCERTAIN: '暂不确定',
      } as Record<string, string>
    )[value] || value
  )
}

/** 返回可解释的错误；网络中断时保留请求键，用户重试不会重复追加。 */
async function read<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, options)
  if (!response.ok) {
    const messages: Record<number, string> = {
      400: '请检查理由、标签和输入长度。',
      404: '报告或结论不存在。',
      409: '记录版本或请求键冲突，请刷新核验记录后重试。',
      422: '替代证据 ID 重复或不属于本报告快照。',
    }
    throw new Error(
      messages[response.status] || '核验请求失败（' + response.status + '），请稍后重试。',
    )
  }
  return response.json()
}

/** 1、读取稳定目录和历史；2、重建编辑表单；3、忽略旧报告的迟到响应。 */
async function load() {
  const version = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await read<{ findings: Finding[] }>(
      '/api/v1/reports/' + props.reportId + '/reviews',
    )
    if (version !== generation) return
    findings.value = result.findings
    forms.value = Object.fromEntries(
      result.findings.map((f) => [
        f.findingId,
        {
          verdict: f.history[0]?.verdict || 'UNCERTAIN',
          reason: f.history[0]?.reason || '',
          ids: f.history[0]?.correctedEvidenceIds.join(', ') || '',
          busy: false,
          error: '',
          key: '',
          payload: '',
        },
      ]),
    )
  } catch (e) {
    if (version === generation) error.value = e instanceof Error ? e.message : '核验读取失败。'
  } finally {
    if (version === generation) loading.value = false
  }
}

/** 1、带当前版本提交；2、同载荷复用请求键；3、保存成功后重新读取完整核验状态。 */
async function save(finding: Finding) {
  const form = forms.value[finding.findingId]
  if (!form || form.busy) return
  const version = generation
  const payload = JSON.stringify({
    verdict: form.verdict,
    reason: form.reason,
    correctedEvidenceIds: form.ids.split(/[,，\s]+/u).filter(Boolean),
    expectedRevision: finding.reviewCount,
  })
  if (form.payload !== payload) {
    form.key = crypto.randomUUID()
    form.payload = payload
  }
  form.busy = true
  form.error = ''
  notice.value = ''
  try {
    await read(
      '/api/v1/reports/' + props.reportId + '/findings/' + finding.findingId + '/reviews',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': form.key },
        body: payload,
      },
    )
    if (version !== generation) return
    notice.value = '核验已保存为新修订，原报告保持不变。'
    await load()
  } catch (e) {
    if (version === generation) form.error = e instanceof Error ? e.message : '保存失败，请重试。'
  } finally {
    if (version === generation) form.busy = false
  }
}

/** 切换报告时清空旧视图，卸载后不再接收旧请求。 */
watch(
  () => props.reportId,
  () => {
    findings.value = []
    forms.value = {}
    notice.value = ''
    void load()
  },
  { immediate: true },
)
onUnmounted(() => {
  generation++
})
</script>

<template>
  <section class="review-panel" aria-label="人工核验">
    <h3>人工核验</h3>
    <p>当前审核身份：本机用户。请结合原文判断结论是否有依据；修改意见会追加修订，不改写原报告。</p>
    <button @click="load" :disabled="loading || Object.values(forms).some((f) => f.busy)">
      刷新核验记录
    </button>
    <p v-if="loading">正在读取核验记录…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="!loading && !error && !findings.length">这份报告没有可核验的结论。</p>
    <article v-for="finding in findings" :key="finding.findingId" class="finding-review">
      <h4>{{ finding.claim }}</h4>
      <details v-for="quote in finding.evidence" :key="quote.commentId">
        <summary>核查原文 {{ quote.commentId }}</summary>
        <blockquote>{{ quote.text }}</blockquote>
      </details>
      <form v-if="forms[finding.findingId]" @submit.prevent="save(finding)">
        <label
          >核验结论
          <select
            v-model="forms[finding.findingId]!.verdict"
            :disabled="forms[finding.findingId]!.busy"
          >
            <option value="SUPPORTED">支持结论</option>
            <option value="PARTIAL">部分支持</option>
            <option value="UNSUPPORTED">不支持结论</option>
            <option value="UNCERTAIN">暂不确定</option>
          </select></label
        >
        <label
          >核验理由
          <textarea
            v-model="forms[finding.findingId]!.reason"
            maxlength="1000"
            required
            :disabled="forms[finding.findingId]!.busy"
            placeholder="说明原文支持或不支持哪些内容"
          />
        </label>
        <label
          >替代证据 ID（选填，最多 10 个）<input
            v-model="forms[finding.findingId]!.ids"
            :disabled="forms[finding.findingId]!.busy"
            placeholder="填写本快照中的评论 ID，用逗号分隔"
        /></label>
        <small
          >留空表示不提出替代证据；替代引用只作为核验意见保存。刷新记录会用已保存意见重建表单。</small
        >
        <button type="submit" :disabled="forms[finding.findingId]!.busy || loading">
          {{ forms[finding.findingId]!.busy ? '正在保存…' : '保存为新修订' }}
        </button>
        <p v-if="forms[finding.findingId]!.error" role="alert">
          {{ forms[finding.findingId]!.error }}
        </p>
      </form>
      <p>已保存 {{ finding.reviewCount }} 个修订，展示最近 {{ finding.history.length }} 个。</p>
      <details v-if="finding.history.length">
        <summary>查看核验历史</summary>
        <ol>
          <li v-for="review in finding.history" :key="review.id">
            <strong>第 {{ review.revision }} 版 · {{ name(review.verdict) }}</strong>
            <p>{{ review.reason }}</p>
            <small
              >{{ review.createdAt }} · 替代证据：{{
                review.correctedEvidenceIds.join('、') || '未提出'
              }}</small
            >
          </li>
        </ol>
      </details>
      <details
        v-for="quote in finding.latestCorrectedEvidence"
        :key="'corrected:' + quote.commentId"
      >
        <summary>查看最新替代证据 {{ quote.commentId }}</summary>
        <blockquote>{{ quote.text }}</blockquote>
      </details>
    </article>
  </section>
</template>

<style scoped>
.review-panel {
  border-top: 1px solid #ccd9c5;
  margin-top: 22px;
  padding-top: 12px;
  overflow-wrap: anywhere;
}
.review-panel p,
small,
summary,
label {
  font-size: 12px;
  line-height: 1.8;
}
.finding-review {
  border: 1px solid #dce5d9;
  border-radius: 8px;
  margin-top: 16px;
  padding: 16px;
}
h4 {
  font-size: 14px;
  line-height: 1.8;
}
label {
  display: block;
  margin: 12px 0;
}
select,
input,
textarea {
  display: block;
  box-sizing: border-box;
  width: 100%;
  padding: 8px;
  border: 1px solid #cbd9c5;
  border-radius: 5px;
  background: #fff;
  color: #25482e;
  font: inherit;
}
textarea {
  min-height: 86px;
  resize: vertical;
}
button {
  margin: 10px 0;
}
small {
  color: #667b68;
}
blockquote {
  margin: 10px 0;
  padding: 10px;
  background: #f5f8f1;
}
li {
  margin-bottom: 12px;
}
</style>
