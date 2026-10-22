<!-- 文件职责：可选的原文先行人工核对工作台，手动保存版本，提交后才按需读取模型预测。 -->
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { readApi, evidenceSource, type Page } from './projectApi'
import UnsavedChangesNotice from './UnsavedChangesNotice.vue'
import {
  blankReviewLabels,
  reviewLabelComplete,
  reviewCoverageName,
  reviewPositionName,
  reviewComparisonName,
  type ReviewInput,
  type ReviewSummary,
  type ReviewRecord,
  type ReviewRecordSummary,
  type ReviewLabel,
  type ReviewComparison,
} from './qualityReview'

const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ dirty: [value: boolean] }>()
const inputs = ref<Page<ReviewSummary> | null>(null),
  inputPage = ref(1)
const requests = ref<Page<{ id: string; instruction: string; status: string }> | null>(null),
  requestPage = ref(1)
const requestId = ref(''),
  count = ref(12),
  seed = ref('review-v1')
const selected = ref<ReviewInput | null>(null),
  labels = ref<ReviewLabel[]>([])
const reviewer = ref(''),
  exposure = ref(''),
  saved = ref<ReviewRecord | null>(null)
const records = ref<Page<ReviewRecordSummary> | null>(null),
  recordPage = ref(1)
const comparison = ref<ReviewComparison | null>(null)
const loading = ref(false),
  busy = ref(false),
  comparing = ref(false),
  dirty = ref(false),
  editing = ref(true)
const error = ref(''),
  editorError = ref(''),
  notice = ref('')
const labelIndex = ref(0)
const activeLabel = computed(() => labels.value[labelIndex.value])
const currentComment = computed(() =>
  selected.value?.input.comments.find((c) => c.id === activeLabel.value?.commentId),
)
const complete = computed(() =>
  selected.value
    ? labels.value.filter((l) => reviewLabelComplete(l, selected.value!.input.mode)).length
    : 0,
)
const canSubmit = computed(
  () =>
    selected.value &&
    complete.value === selected.value.input.targetIds.length &&
    !!reviewer.value.trim() &&
    !!exposure.value &&
    editing.value,
)
let generation = 0,
  recordGeneration = 0
let createAttempt: { signature: string; key: string } | undefined
let saveAttempt: { signature: string; key: string } | undefined

/** URL只含项目/输入/版本身份，不把原文或评审署名放在浏览器地址栏。 */
function base() {
  return `/api/v1/projects/${encodeURIComponent(props.projectId)}/quality-review-inputs`
}
/** 未保存是明确状态，不在用户不知情时创建第二份浏览器“真相”。 */
function changed() {
  if (!editing.value) return
  dirty.value = true
  comparison.value = null
  notice.value = ''
}
const leavePending = ref(false)
let leaveDecision: ((allowed: boolean) => void) | undefined
/** 页面内异步确认，原文/意见保持在表单中；重复点击不会覆盖第一个待确认操作。 */
async function canLeave(): Promise<boolean> {
  if (!dirty.value) return true
  // 初次输入署名只是为了找回历史，此时尚无判断或暴露声明，不弹出丢弃意见提示。
  if (
    !saved.value &&
    !exposure.value &&
    labels.value.every(
      (l) =>
        !l.coverage &&
        !l.statement &&
        !l.condition &&
        !l.rationale &&
        !l.positions.length &&
        l.evidenceIds.length === 1,
    )
  )
    return true
  if (leavePending.value) return false
  leavePending.value = true
  return new Promise<boolean>((resolve) => {
    leaveDecision = resolve
  })
}
/** 两个按钮只完成当前待确认操作；取消不清空任何表单内容。 */
function decideLeave(allowed: boolean) {
  leavePending.value = false
  const resolve = leaveDecision
  leaveDecision = undefined
  resolve?.(allowed)
}
/** 分别分页读取原请求和已冻结核对输入，不读取模型报告。 */
async function load() {
  const version = ++generation
  loading.value = true
  error.value = ''
  try {
    const [list, history] = await Promise.all([
      readApi<Page<ReviewSummary>>(`${base()}?page=${inputPage.value}&size=8`),
      readApi<Page<{ id: string; instruction: string; status: string }>>(
        `/api/v1/projects/${encodeURIComponent(props.projectId)}/analysis-requests?page=${requestPage.value}&size=8`,
      ),
    ])
    if (version === generation) {
      inputs.value = list
      requests.value = history
    }
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '核对资料读取失败。'
  } finally {
    if (version === generation) loading.value = false
  }
}
/** 翻页用明确方法，避免模板内多条语句被格式化后误解析为表达式。 */
function turnRequests(next: number) {
  requestPage.value = next
  void load()
}
/** 原文样本分页只改变列表范围，当前未保存表单仍保留。 */
function turnInputs(next: number) {
  inputPage.value = next
  void load()
}
/** 生成固定抽样；重发保留原请求键，不调用模型、不改变原任务。 */
async function create() {
  if (busy.value || !requestId.value || !(await canLeave())) return
  const version = generation
  busy.value = true
  error.value = ''
  try {
    const body = { requestId: requestId.value, count: count.value, seed: seed.value.trim() }
    const signature = JSON.stringify(body)
    if (createAttempt?.signature !== signature)
      createAttempt = { signature, key: crypto.randomUUID() }
    const result = await readApi<ReviewInput>(base(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': createAttempt.key },
      body: signature,
    })
    if (version !== generation) return
    resetInput(result)
    createAttempt = undefined
    inputPage.value = 1
    await load()
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '样本冻结失败。'
  } finally {
    busy.value = false
  }
}
/** 只载入冻结原文，所有人工判断从空白开始；不会自动展示其他署名意见。 */
function resetInput(value: ReviewInput) {
  selected.value = value
  labels.value = blankReviewLabels(value)
  labelIndex.value = 0
  saved.value = null
  records.value = null
  comparison.value = null
  editing.value = true
  dirty.value = false
  exposure.value = ''
  editorError.value = ''
  notice.value = ''
  saveAttempt = undefined
  recordGeneration++
}
/** 明确选择一份原文包后读取，不追随其他材料版本。 */
async function open(id: string) {
  if (busy.value || !(await canLeave())) return
  const version = generation
  busy.value = true
  error.value = ''
  try {
    const value = await readApi<ReviewInput>(`${base()}/${encodeURIComponent(id)}`)
    if (version === generation) resetInput(value)
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '原文样本读取失败。'
  } finally {
    busy.value = false
  }
}
/** 当前目标的所有祖先按原顺序展示，语境评论不要求额外判断。 */
function ancestors(id: string) {
  if (!selected.value) return []
  const byId = new Map(selected.value.input.comments.map((c) => [c.id, c]))
  const result: NonNullable<typeof currentComment.value>[] = [],
    seen = new Set<string>()
  let parent = byId.get(id)?.parentId
  while (parent && !seen.has(parent)) {
    seen.add(parent)
    const item = byId.get(parent)
    if (!item) break
    result.unshift(item)
    parent = item.parentId
  }
  return result
}
/** 改覆盖时清除不适用立场，保留用户已经写的理由供其自己修订。 */
function coverageChanged(label: ReviewLabel) {
  if (label.coverage !== 'CLAIMS') label.positions = []
  changed()
}
/** 署名对应独立版本链，换名不把上一署名的previousId提交给后端。 */
function profileChanged() {
  records.value = null
  recordGeneration++
  changed()
}
/** 手动保存整份草稿/提交；失败保留输入和幂等键，冲突不自动覆盖服务端最新意见。 */
async function save(kind: 'DRAFT' | 'SUBMISSION') {
  if (!selected.value || busy.value || !editing.value) return
  if (!reviewer.value.trim() || !exposure.value) {
    editorError.value = '请先填写署名，并说明是否已经看过该报告。'
    return
  }
  const version = generation,
    id = selected.value.id
  busy.value = true
  editorError.value = ''
  try {
    const body = {
      reviewer: reviewer.value.trim(),
      exposure: exposure.value,
      kind,
      previousId: saved.value?.body.reviewer === reviewer.value.trim() ? saved.value.id : null,
      labels: labels.value,
    }
    const signature = JSON.stringify(body)
    if (saveAttempt?.signature !== signature) saveAttempt = { signature, key: crypto.randomUUID() }
    const result = await readApi<ReviewRecord>(`${base()}/${id}/records`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': saveAttempt.key },
      body: signature,
    })
    if (version !== generation || selected.value?.id !== id) return
    applyRecord(result)
    notice.value =
      kind === 'SUBMISSION'
        ? '已保存完整提交；现在可以按需打开原报告对照。'
        : '草稿已保存到本机后端，可以稍后按同一署名恢复。'
    saveAttempt = undefined
    await loadRecords(1)
  } catch (caught) {
    if (version === generation && selected.value?.id === id)
      editorError.value = caught instanceof Error ? caught.message : '意见保存失败。'
  } finally {
    busy.value = false
  }
}
/** 恢复明确版本；提交只读，后续编辑必须显式另起一版。 */
function applyRecord(value: ReviewRecord) {
  saved.value = value
  reviewer.value = value.body.reviewer
  exposure.value = value.body.exposure
  const byId = new Map(value.body.labels.map((l) => [l.commentId, l]))
  labels.value = blankReviewLabels(selected.value!).map((l) =>
    byId.has(l.commentId) ? structuredClone(byId.get(l.commentId)!) : l,
  )
  dirty.value = false
  editing.value = value.body.kind === 'DRAFT'
  comparison.value = null
}
/** 只取当前署名历史元数据，不读取其他人的观点作为提示。 */
async function loadRecords(page = recordPage.value) {
  if (!selected.value || !reviewer.value.trim()) {
    editorError.value = '请先填写署名。'
    return
  }
  const version = ++recordGeneration,
    id = selected.value.id,
    name = reviewer.value.trim()
  editorError.value = ''
  recordPage.value = page
  try {
    const result = await readApi<Page<ReviewRecordSummary>>(
      `${base()}/${id}/records?reviewer=${encodeURIComponent(name)}&page=${page}&size=6`,
    )
    if (version === recordGeneration && selected.value?.id === id && reviewer.value.trim() === name)
      records.value = result
  } catch (caught) {
    if (version === recordGeneration)
      editorError.value = caught instanceof Error ? caught.message : '历史读取失败。'
  }
}
/** 载入旧版前防止误丢未保存意见；服务端仍阻止以旧previousId覆盖新版本。 */
async function restore(id: string) {
  if (!selected.value || !(await canLeave())) return
  const version = generation,
    inputId = selected.value.id
  try {
    const value = await readApi<ReviewRecord>(`${base()}/${inputId}/records/${id}`)
    if (version === generation && selected.value?.id === inputId) applyRecord(value)
  } catch (caught) {
    editorError.value = caught instanceof Error ? caught.message : '意见版本读取失败。'
  }
}
/** 只有明确提交后用户主动点击才读取预测，不自动评定谁正确。 */
async function compare() {
  if (!selected.value || !saved.value || saved.value.body.kind !== 'SUBMISSION' || editing.value)
    return
  const version = generation,
    id = selected.value.id,
    recordId = saved.value.id
  comparing.value = true
  editorError.value = ''
  try {
    const value = await readApi<ReviewComparison>(`${base()}/${id}/records/${recordId}/comparison`)
    if (version === generation && selected.value?.id === id && saved.value?.id === recordId)
      comparison.value = value
  } catch (caught) {
    editorError.value = caught instanceof Error ? caught.message : '结果对照读取失败。'
  } finally {
    comparing.value = false
  }
}
/** 看过对照后再编辑应如实标记已看过；旧提交的原声明和判断保持不变。 */
function revise() {
  if (comparison.value?.items.length) exposure.value = 'VIEWED'
  editing.value = true
  comparison.value = null
  changed()
}
/** 标准刷新/关闭提示不自动删除或重置草稿；日常明确手动保存。 */
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
watch(dirty, (value) => emit('dirty', value))
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
watch(
  () => props.projectId,
  () => {
    selected.value = null
    dirty.value = false
    inputPage.value = 1
    requestPage.value = 1
    void load()
  },
  { immediate: true },
)
onUnmounted(() => {
  decideLeave(false)
  generation++
  recordGeneration++
  emit('dirty', false)
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <section class="quality-workbench">
    <UnsavedChangesNotice
      v-if="leavePending"
      @stay="decideLeave(false)"
      @leave="decideLeave(true)"
    />
    <header>
      <h2>质量验收 · 原文先行</h2>
      <p>
        这是可选的开发验收工具。先独立阅读原文并记录判断，完整提交后再对照模型；日常分析不需要人工审批。
      </p>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取核对输入…</p>
    <details class="surface quality-create" :open="!inputs?.total">
      <summary>准备一份无预测的原文样本</summary>
      <form @submit.prevent="create">
        <label class="field"
          >原分析请求<select v-model="requestId" required :disabled="busy">
            <option value="" disabled>请选择要核对的问题</option>
            <option v-for="item in requests?.items" :key="item.id" :value="item.id">
              {{ item.instruction }} · {{ item.id.slice(0, 8) }}
            </option>
          </select></label
        >
        <div v-if="requests && requests.totalPages > 1" class="pagination">
          <button
            type="button"
            :disabled="requestPage <= 1 || loading"
            @click="turnRequests(requestPage - 1)"
          >
            上一请求页</button
          ><span>{{ requestPage }} / {{ requests.totalPages }}</span
          ><button
            type="button"
            :disabled="requestPage >= requests.totalPages || loading"
            @click="turnRequests(requestPage + 1)"
          >
            下一请求页
          </button>
        </div>
        <div class="quality-fields">
          <label class="field"
            >目标数量<input
              v-model.number="count"
              type="number"
              min="1"
              max="20"
              required
              :disabled="busy" /></label
          ><label class="field"
            >固定抽样标记<input v-model="seed" maxlength="64" required :disabled="busy"
          /></label>
        </div>
        <p class="small">
          按来源身份和固定标记抽样，最多20个目标，父评论独立提供。不会按模型引用挑选，也不会调用模型。
        </p>
        <button class="primary" :disabled="busy || !requestId">
          {{ busy ? '正在保存…' : '冻结原文样本' }}
        </button>
      </form>
    </details>
    <section v-if="inputs?.total" class="surface">
      <div class="section-toolbar">
        <h3>已保存的核对输入</h3>
        <button class="quiet" :disabled="loading" @click="load">刷新</button>
      </div>
      <div class="quality-inputs">
        <button
          v-for="item in inputs.items"
          :key="item.id"
          :class="{ selected: selected?.id === item.id }"
          @click="open(item.id)"
        >
          <strong>{{ item.title }}</strong
          ><span>{{ item.instruction }}</span
          ><small>{{ item.targetCount }}个目标 · {{ item.createdAt }}</small>
        </button>
      </div>
      <div v-if="inputs.totalPages > 1" class="pagination">
        <button :disabled="inputPage <= 1 || loading" @click="turnInputs(inputPage - 1)">
          上一输入页</button
        ><span>{{ inputPage }} / {{ inputs.totalPages }}</span
        ><button
          :disabled="inputPage >= inputs.totalPages || loading"
          @click="turnInputs(inputPage + 1)"
        >
          下一输入页
        </button>
      </div>
    </section>
    <section v-if="selected" class="surface quality-editor">
      <header>
        <p class="eyebrow">SOURCE FIRST</p>
        <h3>{{ selected.input.title }}</h3>
        <p>{{ selected.input.instruction }}</p>
        <p v-if="selected.input.proposition">
          <strong>唯一命题：</strong>{{ selected.input.proposition }}
        </p>
      </header>
      <div class="scope-strip">
        <span
          >{{ selected.input.targetIds.length }}个抽样目标 /
          {{ selected.input.eligibleTargets }}个可选目标</span
        ><span
          >{{ selected.input.comments.length - selected.input.targetIds.length }}条额外父语境</span
        ><span>{{ dirty ? '有未保存的修改' : saved ? '已保存当前人工版本' : '尚未填写意见' }}</span>
      </div>
      <p class="small">
        请求抽样{{ selected.input.requestedTargets }}条，实际纳入{{
          selected.input.targetIds.length
        }}个目标；完整父语境与字符边界可能减少可选数，样本不代表总体。
      </p>
      <p class="small">
        原文可能包含剧情或角色台词，不能直接当作创作者意图。当前输入不含模型预测；若你在其他页面看过报告，请如实声明。切页前请点“保存草稿”。
      </p>
      <details>
        <summary>输入边界与明确背景（{{ selected.input.background.length }}段）</summary>
        <p v-for="note in selected.input.notes" :key="note" class="small">{{ note }}</p>
        <article v-for="source in selected.input.background" :key="source.id">
          <strong>{{ evidenceSource({ sourceRole: source.role, ...source }) }}</strong>
          <p class="quality-text">{{ source.text }}</p>
        </article>
      </details>
      <div class="quality-fields">
        <label class="field"
          >本机署名<input
            v-model="reviewer"
            maxlength="80"
            :disabled="!editing || busy"
            @input="profileChanged"
            placeholder="用于找回自己的核对记录" /></label
        ><label class="field"
          >此前是否看过这份模型报告<select
            v-model="exposure"
            :disabled="!editing || busy"
            @change="changed"
          >
            <option value="" disabled>请如实选择</option>
            <option value="NOT_VIEWED">尚未看过预测</option>
            <option value="VIEWED">已经看过预测</option>
            <option value="UNKNOWN">不确定</option>
          </select></label
        >
      </div>
      <div class="quality-history-actions">
        <button
          type="button"
          class="quiet"
          :disabled="!reviewer.trim() || busy"
          @click="loadRecords(1)"
        >
          读取此署名的历史记录</button
        ><small>署名不是登录身份，不保证独立评审。</small>
      </div>
      <div v-if="activeLabel && currentComment" class="quality-target">
        <div class="section-toolbar">
          <h3>目标 {{ labelIndex + 1 }} / {{ labels.length }}</h3>
          <span>{{ complete }} / {{ labels.length }} 已完成</span>
        </div>
        <details v-if="ancestors(currentComment.id).length" open>
          <summary>完整父语境（{{ ancestors(currentComment.id).length }}条）</summary>
          <blockquote v-for="item in ancestors(currentComment.id)" :key="item.id">
            <small>语境原文 · {{ item.sourceCommentId }}</small>
            <p class="quality-text">{{ item.text }}</p>
          </blockquote>
        </details>
        <blockquote class="quality-current">
          <small>本次判断目标 · {{ currentComment.sourceCommentId }}</small>
          <p class="quality-text">{{ currentComment.text }}</p>
        </blockquote>
        <label class="field"
          >这条原文相对本次问题属于什么<select
            v-model="activeLabel.coverage"
            :disabled="!editing || busy"
            @change="coverageChanged(activeLabel)"
          >
            <option value="" disabled>请自行判断，不预填未知</option>
            <option value="CLAIMS">包含主张</option>
            <option value="QUESTION">仅提问</option>
            <option value="IRRELEVANT">与本问题无关</option>
            <option value="UNKNOWN">语境不足，无法判断</option>
          </select></label
        >
        <fieldset
          v-if="selected.input.mode === 'STANCE' && activeLabel.coverage === 'CLAIMS'"
          :disabled="!editing || busy"
          class="quality-positions"
        >
          <legend>相对上述命题的立场（最多两种）</legend>
          <label v-for="position in ['SUPPORT', 'OPPOSE', 'QUALIFIED', 'UNKNOWN']" :key="position"
            ><input
              type="checkbox"
              v-model="activeLabel.positions"
              :value="position"
              :disabled="
                !activeLabel.positions.includes(position) && activeLabel.positions.length >= 2
              "
              @change="changed"
            />{{ reviewPositionName(position) }}</label
          >
        </fieldset>
        <label v-if="activeLabel.coverage === 'CLAIMS'" class="field"
          >用自己的话说明主张<textarea
            v-model="activeLabel.statement"
            maxlength="2000"
            rows="3"
            :disabled="!editing || busy"
            @input="changed"
          />
        </label>
        <label v-if="activeLabel.positions.includes('QUALIFIED')" class="field"
          >在哪些条件下成立<textarea
            v-model="activeLabel.condition"
            maxlength="1000"
            rows="2"
            :disabled="!editing || busy"
            @input="changed"
          />
        </label>
        <label class="field"
          >为什么这样判断，哪里仍缺语境<textarea
            v-model="activeLabel.rationale"
            maxlength="2000"
            rows="3"
            :disabled="!editing || busy"
            @input="changed"
          />
        </label>
        <p class="small">
          本条原文是固定核对依据；需要补充其他原文时可展开选择。依据存在不等于判断正确。
        </p>
        <details>
          <summary>补充原文依据（最多20条）</summary>
          <label class="quality-evidence" v-for="item in selected.input.comments" :key="item.id"
            ><input
              type="checkbox"
              v-model="activeLabel.evidenceIds"
              :value="item.id"
              :disabled="
                !editing ||
                busy ||
                item.id === activeLabel.commentId ||
                (!activeLabel.evidenceIds.includes(item.id) && activeLabel.evidenceIds.length >= 20)
              "
              @change="changed"
            /><span>{{ item.text }}</span></label
          ><label class="quality-evidence" v-for="item in selected.input.background" :key="item.id"
            ><input
              type="checkbox"
              v-model="activeLabel.evidenceIds"
              :value="item.id"
              :disabled="
                !editing ||
                busy ||
                (!activeLabel.evidenceIds.includes(item.id) && activeLabel.evidenceIds.length >= 20)
              "
              @change="changed"
            /><span>{{ item.title }} · {{ item.text }}</span></label
          >
        </details>
        <div class="pagination">
          <button :disabled="labelIndex <= 0" @click="labelIndex--">上一目标</button
          ><span>{{ labelIndex + 1 }} / {{ labels.length }}</span
          ><button :disabled="labelIndex >= labels.length - 1" @click="labelIndex++">
            下一目标
          </button>
        </div>
      </div>
      <p v-if="editorError" class="error" role="alert">{{ editorError }}</p>
      <p v-if="notice" role="status">{{ notice }}</p>
      <div class="quality-save-actions">
        <button v-if="editing" :disabled="busy" @click="save('DRAFT')">保存草稿</button
        ><button
          v-if="editing"
          class="primary"
          :disabled="busy || !canSubmit"
          @click="save('SUBMISSION')"
        >
          提交本次全部判断</button
        ><template v-else
          ><button class="primary" :disabled="comparing" @click="compare">
            {{ comparing ? '正在读取…' : '打开原模型结果对照' }}</button
          ><button @click="revise">在此提交后继续修订</button></template
        >
      </div>
      <nav class="report-downloads" aria-label="导出人工核对资料">
        <a :href="`${base()}/${selected.id}/export?format=input`" download>下载无预测原文包</a
        ><a
          v-if="saved"
          :href="`${base()}/${selected.id}/export?format=record&recordId=${saved.id}`"
          download
          >下载当前已保存意见</a
        ><a
          v-if="comparison && saved"
          :href="`${base()}/${selected.id}/export?format=comparison&recordId=${saved.id}`"
          download
          >下载本次对照</a
        >
      </nav>
      <details v-if="records">
        <summary>此署名的已保存版本（{{ records.total }}条）</summary>
        <button
          class="quality-record"
          v-for="item in records.items"
          :key="item.id"
          @click="restore(item.id)"
        >
          {{ item.kind === 'SUBMISSION' ? '完整提交' : '草稿' }} · {{ item.createdAt }} ·
          {{ item.id.slice(0, 8) }}
        </button>
        <p v-if="!records.total">没有此署名的记录。若已在另一个署名下保存，请填写原署名。</p>
        <div v-if="records.totalPages > 1" class="pagination">
          <button :disabled="recordPage <= 1" @click="loadRecords(recordPage - 1)">
            上一记录页</button
          ><span>{{ recordPage }} / {{ records.totalPages }}</span
          ><button
            :disabled="recordPage >= records.totalPages"
            @click="loadRecords(recordPage + 1)"
          >
            下一记录页
          </button>
        </div>
      </details>
    </section>
    <section v-if="comparison" class="surface quality-comparison">
      <h3>{{ reviewComparisonName(comparison.state) }}</h3>
      <p v-for="note in comparison.notes" :key="note" class="small">{{ note }}</p>
      <p v-if="comparison.items.length && selected?.input.mode === 'STANCE'">
        本样本{{ comparison.sampledTargets }}个目标：覆盖类型分歧{{
          comparison.coverageDisagreements
        }}条，立场集合分歧{{ comparison.positionDisagreements }}条。两种分歧可重叠，不是准确率。
      </p>
      <article
        v-for="item in comparison.items"
        :key="item.commentId"
        class="quality-comparison-item"
      >
        <blockquote class="quality-text">
          {{ selected?.input.comments.find((c) => c.id === item.commentId)?.text }}
        </blockquote>
        <div class="quality-fields">
          <div>
            <strong>你的初审</strong>
            <p>
              {{ reviewCoverageName(item.human.coverage)
              }}<span v-if="item.human.positions.length">
                · {{ item.human.positions.map(reviewPositionName).join('、') }}</span
              >
            </p>
            <p class="quality-text">{{ item.human.statement }}</p>
            <p class="quality-text">{{ item.human.rationale }}</p>
          </div>
          <div>
            <strong>原模型输出</strong
            ><template v-if="item.modelDecision"
              ><p>{{ reviewCoverageName(item.modelDecision.coverage) }}</p>
              <article v-for="(claim, index) in item.modelDecision.claims" :key="index">
                <strong>{{ reviewPositionName(claim.position) }}</strong>
                <p class="quality-text">{{ claim.statement }}</p>
                <p class="quality-text">{{ claim.reason }}</p>
              </article></template
            ><template v-else
              ><p v-for="(claim, index) in item.citedClaims" :key="index" class="quality-text">
                {{ claim.statement }}
              </p>
              <p v-if="!item.citedClaims.length">
                原报告没有显式引用此目标的主张，不能据此认定漏读。
              </p></template
            >
          </div>
        </div>
        <p class="small">{{ item.note }}</p>
      </article>
    </section>
  </section>
</template>

<style scoped>
.quality-workbench {
  display: grid;
  gap: 22px;
  min-width: 0;
}
.quality-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}
.quality-create form {
  display: grid;
  gap: 16px;
}
.quality-create .primary {
  justify-self: start;
}
.quality-inputs {
  display: grid;
  gap: 10px;
}
.quality-inputs button {
  display: grid;
  gap: 6px;
  text-align: left;
  white-space: normal;
}
.quality-inputs .selected {
  background: var(--accent-soft);
  border-color: var(--accent);
}
.quality-editor {
  display: grid;
  gap: 18px;
}
.quality-editor h3 {
  margin: 0;
}
.quality-history-actions,
.quality-save-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: center;
}
.quality-target {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 22px;
}
.quality-target .section-toolbar {
  margin: 0 0 18px;
}
.quality-target blockquote {
  margin: 12px 0;
  padding: 14px 16px;
  background: #f5f8f6;
  border-left: 3px solid #abc7b4;
  border-radius: 0 8px 8px 0;
}
.quality-target .quality-current {
  background: #edf5ef;
  border-left-color: var(--accent);
}
.quality-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.85;
}
.quality-positions {
  margin-top: 20px;
  border: 1px solid var(--line);
  border-radius: 9px;
  padding: 14px;
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
}
.quality-positions label {
  display: flex;
  gap: 6px;
  align-items: center;
}
.quality-evidence {
  display: flex;
  gap: 10px;
  padding-block: 10px;
  align-items: flex-start;
}
.quality-evidence span {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  min-width: 0;
}
.quality-record {
  display: block;
  white-space: normal;
  text-align: left;
  margin-top: 10px;
  width: 100%;
}
.quality-comparison-item {
  border-top: 1px solid var(--line);
  padding-block: 22px;
}
.quality-comparison-item blockquote {
  margin: 0 0 20px;
  padding: 14px;
  background: #f5f8f6;
}
.quality-comparison-item .quality-fields > div {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 10px;
}
@media (max-width: 700px) {
  .quality-fields {
    grid-template-columns: minmax(0, 1fr);
  }
  .quality-target {
    padding: 16px;
  }
  .quality-save-actions button {
    flex: 1;
    white-space: normal;
  }
}
</style>
