<!-- 文件职责：明确选择比较方向，查看资料差异/可比标签及双侧原文，不将输入变化当观点迁移。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { readApi, type Page } from './projectApi'
const props = defineProps<{ projectId: string; requestId: string; analyzed: boolean }>()
type Request = { id: string; instruction: string; status: string; createdAt: string }
type Comment = { id: string; parentId: string | null; text: string }
type Decision = {
  reason: string
  claims: { statement: string; condition: string; reason: string }[]
}
type Row = {
  sourceCommentId: string
  category: string
  beforeComment: Comment | null
  afterComment: Comment | null
  beforeLabel: string
  afterLabel: string
  beforeDecision: Decision | null
  afterDecision: Decision | null
  labelChanged: boolean
}
type Result = {
  compatibility: { comparable: boolean; reasons: string[] }
  counts: Record<string, number>
  transitions: Record<string, number>
  changedLabels: number
  total: number
  page: number
  totalPages: number
  items: Row[]
  limitations: string[]
  beforeEvidence: { items: Comment[]; truncated: boolean }
  afterEvidence: { items: Comment[]; truncated: boolean }
}
const requests = ref<Request[]>([])
const before = ref('')
const filter = ref('ALL')
const page = ref(1)
const result = ref<Result | null>(null)
const busy = ref(false)
const error = ref('')
let generation = 0
let alive = true
const names: Record<string, string> = {
  ALL: '全部差异',
  ADDED: '新版本新增',
  UNKNOWN_MISSING: '新版本未见（未知缺失）',
  REVISED: '评论自身修订',
  CONTEXT_CHANGED: '父语境变化',
  COMPARABLE: '可比评论',
  NOT_COMPARABLE: '当前条件不可比',
  LABEL_CHANGED: '可比评论中标签不同',
}
const base = () => `/api/v1/projects/${props.projectId}/analysis-requests/${props.requestId}`

/** 展开才加载最近50条成功报告；作品资格由服务器再次校验。 */
async function loadOptions(event: Event) {
  if (!(event.target as HTMLDetailsElement).open) return
  const version = ++generation
  busy.value = true
  error.value = ''
  try {
    const history = await readApi<Page<Request>>(
      `/api/v1/projects/${props.projectId}/analysis-requests?size=50`,
    )
    if (alive && version === generation)
      requests.value = history.items.filter((r) => r.status === 'SUCCESS')
  } catch (caught) {
    if (alive && version === generation)
      error.value = caught instanceof Error ? caught.message : '比较候选读取失败。'
  } finally {
    if (alive && version === generation) busy.value = false
  }
}

/** 结果从原不可变产物投影；过滤/翻页只改变列表，完整统计不变。 */
async function compare() {
  if (!before.value || busy.value) return
  const version = ++generation
  busy.value = true
  error.value = ''
  try {
    const query = new URLSearchParams({
      beforeRequestId: before.value,
      filter: filter.value,
      page: String(page.value),
      size: '20',
    })
    const view = await readApi<Result>(base() + '/comparison?' + query)
    if (alive && version === generation) result.value = view
  } catch (caught) {
    if (alive && version === generation) {
      result.value = null
      error.value = caught instanceof Error ? caught.message : '比较读取失败。'
    }
  } finally {
    if (alive && version === generation) busy.value = false
  }
}

/** 改方向清空旧结果，避免导出新选择却显示旧对比。 */
watch(before, () => {
  generation++
  result.value = null
  page.value = 1
  busy.value = false
})
watch(filter, () => {
  page.value = 1
  void compare()
})
/** 界面用业务语言解释覆盖/立场，保留“未判断”和“立场未知”的区别。 */
function judgmentLabel(label: string) {
  if (label === 'NOT_ANALYZED') return '未有逐条判断'
  const coverages: Record<string, string> = {
    QUESTION: '仅提问',
    IRRELEVANT: '无关',
    UNKNOWN: '无法判断',
  }
  if (coverages[label]) return coverages[label]
  const positions: Record<string, string> = {
    SUPPORT: '支持命题',
    OPPOSE: '反对命题',
    QUALIFIED: '有条件成立',
    UNKNOWN: '立场无法判断',
  }
  if (label.startsWith('CLAIMS:'))
    return label
      .slice(7)
      .split('+')
      .map((value) => positions[value] || '未知标签')
      .join('、')
  return '标签无法识别，请看原判断'
}

/** 转移计数只翻译展示，不改变后台原标签及计数。 */
function transitionLabel(value: string) {
  return value.split(' → ').map(judgmentLabel).join(' → ')
}

/** 分页只读，不改变比较条件。 */
function move(delta: number) {
  page.value += delta
  void compare()
}
onUnmounted(() => {
  alive = false
  generation++
})
</script>
<template>
  <details class="surface revision-panel" @toggle="loadOptions">
    <summary>和另一份报告对照版本变化</summary>
    <p>
      当前报告作为“之后”，所选报告作为“之前”；方向由你指定，不自动猜日历先后。只比较同一作品。候选展示最近50条成功报告。
    </p>
    <label
      >之前的报告<select v-model="before" :disabled="busy">
        <option value="" disabled>请选择对照报告</option>
        <option v-for="item in requests" :key="item.id" :value="item.id">
          {{ item.instruction }} · {{ item.createdAt
          }}{{ item.id === requestId ? '（当前报告自身）' : '' }}
        </option>
      </select></label
    >
    <button :disabled="busy || !before" @click="compare">对照两份报告</button>
    <p v-if="error" role="alert">{{ error }}</p>
    <section v-if="result" aria-label="报告版本比较结果">
      <p>
        {{
          result.compatibility.comparable
            ? '比较条件一致；仅对原文和全部父语境未变的评论记录标签差异。'
            : '当前只展示资料差异，不统计立场标签转移。'
        }}
      </p>
      <ul>
        <li v-for="reason in result.compatibility.reasons" :key="reason">{{ reason }}</li>
      </ul>
      <p>
        <span v-for="(count, category) in result.counts" :key="category"
          >{{ names[category] || category }}：{{ count }}；
        </span>
      </p>
      <p>
        可比评论中标签不同{{ result.changedLabels }}条；这不证明观众改变观点，也不代表模型判断正确。
      </p>
      <label
        >查看哪类变化<select v-model="filter" :disabled="busy">
          <option v-for="(name, key) in names" :key="key" :value="key">{{ name }}</option>
        </select></label
      >
      <a
        :href="base() + '/comparison/export?beforeRequestId=' + encodeURIComponent(before)"
        download
        >下载完整版本对照JSON（含原文）</a
      >
      <article v-for="row in result.items" :key="row.sourceCommentId" class="issue-card">
        <h4>{{ names[row.category] }} · {{ row.sourceCommentId }}</h4>
        <p>
          之前：{{ judgmentLabel(row.beforeLabel) }}；之后：{{ judgmentLabel(row.afterLabel)
          }}{{ row.labelChanged ? '（标签不同）' : '' }}
        </p>
        <blockquote>之前原文：{{ row.beforeComment?.text ?? '这份资料未见该评论' }}</blockquote>
        <blockquote>
          之后原文：{{ row.afterComment?.text ?? '这份资料未见该评论，原因未知' }}
        </blockquote>
        <details>
          <summary>查看两侧判断理由</summary>
          <p>之前：{{ row.beforeDecision?.reason ?? '未判断' }}</p>
          <p v-for="(claim, index) in row.beforeDecision?.claims" :key="'before-' + index">
            {{ claim.statement }}；条件：{{ claim.condition || '无明确条件' }}；{{ claim.reason }}
          </p>
          <p>之后：{{ row.afterDecision?.reason ?? '未判断' }}</p>
          <p v-for="(claim, index) in row.afterDecision?.claims" :key="'after-' + index">
            {{ claim.statement }}；条件：{{ claim.condition || '无明确条件' }}；{{ claim.reason }}
          </p>
        </details>
      </article>
      <nav v-if="result.totalPages > 1" aria-label="版本对照分页">
        <button :disabled="busy || page === 1" @click="move(-1)">上一页</button
        ><span>{{ page }}/{{ result.totalPages }}页 · 当前筛选{{ result.total }}条</span
        ><button :disabled="busy || page >= result.totalPages" @click="move(1)">下一页</button>
      </nav>
      <details>
        <summary>查看本页双侧父语境</summary>
        <p v-if="result.beforeEvidence.truncated || result.afterEvidence.truncated">
          至少一侧存在缺父或有界截断，不能声称完整语境。
        </p>
        <h4>之前</h4>
        <blockquote v-for="item in result.beforeEvidence.items" :key="item.id">
          {{ item.text }}
        </blockquote>
        <h4>之后</h4>
        <blockquote v-for="item in result.afterEvidence.items" :key="item.id">
          {{ item.text }}
        </blockquote>
      </details>
      <details>
        <summary>可比标签转移计数与限制</summary>
        <p v-for="(count, key) in result.transitions" :key="key">
          {{ transitionLabel(String(key)) }}：{{ count }}
        </p>
        <ul>
          <li v-for="note in result.limitations" :key="note">{{ note }}</li>
        </ul>
      </details>
    </section>
    <details v-if="analyzed">
      <summary>导出待人工评测候选（可选）</summary>
      <p>
        包含原文与原预测，尚未匿名；金标准保持为空，真实来源的授权状态待确认。不要直接公开到仓库。需隐藏预测给独立人员标注，之后裁决和检查作品级dev/test泄漏。
      </p>
      <a :href="base() + '/evaluation-package?split=dev&limit=50'" download
        >下载dev候选包（最多50条，保留父链）</a
      >
    </details>
  </details>
</template>

<style scoped>
/* 新旧方向和过滤字段各占一行，避免长报告标题挤压原文阅读区。 */
.revision-panel {
  margin-top: 20px;
}
.revision-panel > label,
.revision-panel > section > label {
  display: grid;
  gap: 8px;
  margin: 16px 0;
  font-weight: 600;
}
.revision-panel select {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  padding: 10px 12px;
  border: 1px solid #d8e3dc;
  border-radius: 8px;
  background: #fff;
  font: inherit;
  font-weight: 400;
}
.revision-panel article {
  margin-top: 16px;
}
.revision-panel a {
  display: inline-block;
  margin: 12px 0;
}
</style>
