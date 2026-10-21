<!-- 文件职责：提供项目内自然语言分析入口、请求历史和证据化讨论结果，不暴露模型或工具选择。 -->
<script setup lang="ts">
import TaskControlPanel from './TaskControlPanel.vue'
import ContentContextPicker from './ContentContextPicker.vue'
import DiscussionBaselinePicker from './DiscussionBaselinePicker.vue'
import DiscussionDigestPanel from './DiscussionDigestPanel.vue'
import {
  contentChoiceBody,
  digestProgressText,
  type ContentChoice,
  type ContentBinding,
  type DigestProgress,
  type DigestProcessing,
  type DigestReceipt,
} from './discussionDigest'
import { computed, onUnmounted, ref, watch } from 'vue'
import StanceResults from './StanceResults.vue'
import ContinuationPanel from './ContinuationPanel.vue'
import RevisionComparisonPanel from './RevisionComparisonPanel.vue'
import type { StanceResult } from './stanceView'
import FailureNotice from './FailureNotice.vue'
import ExecutionDetails from './ExecutionDetails.vue'
import SemanticReviewPanel from './SemanticReviewPanel.vue'
import ReferencePicker from './ReferencePicker.vue'
import TranscriptLibrary from './TranscriptLibrary.vue'
import SourceCoverageNotice from './SourceCoverageNotice.vue'
import type { CoverageView, SourceCoverage } from './sourceCoverage'
import {
  readApi,
  sourceName,
  coverageName,
  evidenceSource,
  type Page,
  type ProjectSnapshot,
} from './projectApi'

const props = defineProps<{
  projectId: string
  preferredSnapshotId?: string
  initialRequestId?: string
}>()
type RequestItem = {
  id: string
  snapshotId: string
  instruction: string
  status: string
  jobId: string
  artifactId: string | null
  lastErrorCode: string | null
  createdAt: string
}
type Artifact = {
  artifactId: string
  processing?: DigestProcessing | null
  instruction: string
  title: string
  sourceType: string
  sourceMode: string
  scope: {
    totalComments: number
    rootComments: number
    replyComments: number
    selectedThreads: number
    truncated: boolean
    selectedComments?: number | null
    selectionVersion?: string | null
  }
  issues: Array<{
    id: string
    question: string
    summary: string
    evidenceIds: string[]
  }>
  claims: Array<{
    id: string
    issueId: string
    statement: string
    reasoningStatus: string
    evidenceIds: string[]
  }>
  relations: Array<{
    fromClaimId: string
    toClaimId: string
    type: string
    semanticStatus: string
    evidenceIds?: string[]
  }>
  assessments: Array<{
    issueId: string
    verdict: string
    conclusion: string
    reason: string
    evidenceIds: string[]
  }>
  evidence: Array<{
    id: string
    sourceCommentId: string
    parentCommentId: string | null
    text: string
    sourceRole?: string | null
    title?: string | null
    startMs?: number | null
    endMs?: number | null
    startChar?: number | null
    endChar?: number | null
  }>
  context?: {
    sentToModel: boolean
    referenceDocuments: number
    totalTranscriptSegments: number
    selectedTranscriptSegments: number
    transcriptTruncated: boolean
    textChars: number
  } | null
  stance?: StanceResult | null
  limitations: string[]
}

type ContextView = {
  fingerprint: string | null
  selection: {
    retrieval?: { materialId: string; strategy: string; matchedSegments: number } | null
    content?: ContentBinding | null
    transcriptRequested: boolean
    references: Array<{ id: string; title: string }>
    sources: Array<{
      id: string
      role: string
      title: string
      text: string
      startMs: number | null
      endMs: number | null
      startChar?: number | null
      endChar?: number | null
    }>
  } | null
}
type Goal = {
  stanceRequested: boolean
  needsProposition: boolean
  specification: { proposition: string; origin: string } | null
  explanation: string
}
type FullScope = {
  mode: string
  available: boolean
  reasonCode: string | null
  targetComments: number
  plannedBatches: number
  contextOccurrences: number
  maximumUnits: number
  budgetLimitUnits: number
  unit: string
  requiresConfirmation: boolean
}
type Progress = {
  mode: string
  plannedBatches: number
  completedBatches: number
  completedComments: number
  targetComments: number
  published: boolean
  processing?: DigestProgress | null
}
type Preview = {
  sourceCoverage: SourceCoverage
  fullScope: FullScope | null
  goal: Goal | null
  mode: string
  savedComments: number
  selectedComments: number
  omittedComments: number
  missingParentCount: number
  requiresPartialConsent: boolean
  fullAnalysisAvailable: boolean
  notes: string[]
  content?: ContentBinding | null
  reuse?: DigestReceipt | null
}
type FailureDetail = {
  errorCode: string | null
  causeCode: string | null
  feeUncertain: boolean
  canRetry: boolean
}
const contentChoice = ref<ContentChoice>({ materialId: '', from: '', to: '' })
const baselineRequestId = ref('')
const analysisScope = ref('FULL')
const confirmFullScope = ref(false)
const progress = ref<Progress | null>(null)
const proposition = ref('')
const preview = ref<Preview | null>(null)
const previewError = ref('')
const previewLoading = ref(false)
const partialConsent = ref(false)
const failure = ref<FailureDetail | null>(null)
let previewGeneration = 0
/** 只读预检每次核对当前选择，迟到响应不会覆盖其他资料；不提交用户指令或创建任务。 */
async function loadPreview() {
  const version = ++previewGeneration
  if (!snapshotId.value) {
    preview.value = null
    previewLoading.value = false
    previewError.value = ''
    return false
  }
  previewLoading.value = true
  previewError.value = ''
  try {
    const value = await readApi<Preview>(
      `/api/v1/projects/${encodeURIComponent(props.projectId)}/analysis-requests/preview`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          snapshotId: snapshotId.value,
          instruction: instruction.value.trim() || undefined,
          proposition: proposition.value.trim() || undefined,
          analysisScope: analysisScope.value === 'AUTO' ? undefined : analysisScope.value,
          includeTranscript: includeTranscript.value,
          transcriptMaterialId: transcriptMaterialId.value || undefined,
          referenceIds: referenceIds.value,
          ...contentChoiceBody(contentChoice.value),
          baselineRequestId: baselineRequestId.value || undefined,
        }),
      },
    )
    if (version !== previewGeneration) return false
    preview.value = value
    return true
  } catch (caught) {
    if (version === previewGeneration) {
      preview.value = null
      previewError.value = caught instanceof Error ? caught.message : '范围预检失败。'
    }
    return false
  } finally {
    if (version === previewGeneration) previewLoading.value = false
  }
}
/** 只在无未决出站调用且后台允许时重试原任务，不新建另一份请求。 */
async function retryActive() {
  if (!active.value || !failure.value?.canRetry || submitting.value) return
  submitting.value = true
  const version = generation,
    id = active.value.id
  try {
    await readApi(`/api/v1/analysis-jobs/${encodeURIComponent(active.value.jobId)}/retry`, {
      method: 'POST',
    })
    if (version === generation && active.value?.id === id) await refreshActive()
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '重试未接受。'
  } finally {
    if (version === generation) submitting.value = false
  }
}

const referenceIds = ref<string[]>([])
const includeTranscript = ref(false)
const transcriptMaterialId = ref('')
const sourceCoverage = ref<CoverageView | null>(null)
const activeContext = ref<ContextView | null>(null)
let submission: { input: string; key: string } | undefined
const materials = ref<ProjectSnapshot[]>([])
const history = ref<RequestItem[]>([])
const instruction = ref('总结这个视频下面用户讨论问题的逻辑，整理主要观点，并判断谁的理由更充分')
const snapshotId = ref('')
const active = ref<RequestItem | null>(null)
const artifact = ref<Artifact | null>(null)
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
/** 独立字幕与快照字幕互斥，用户选择只改变下一次请求。 */
function selectTranscript(id: string) {
  transcriptMaterialId.value = id
  if (id) includeTranscript.value = false
}
/** 用户改动判断标准后取消旧范围同意，并重新做只读核对。 */
function goalChanged() {
  partialConsent.value = false
  confirmFullScope.value = false
  void loadPreview()
}
/** 显式基准沿用原问题，取消选择后才允许换问题，服务器仍重新核验。 */
function baselineChosen(item: { id: string; instruction: string }) {
  instruction.value = item.instruction
  proposition.value = ''
  analysisScope.value = 'FULL'
  goalChanged()
}
/** 清空基准需要重新预检；选择时由chosen回调一次性沿用问题，避免旧问题多发一次预检。 */
function baselineChanged(id: string) {
  baselineRequestId.value = id
  if (!id) goalChanged()
}
/** 背景改动重新核对，不自动取消基准以免悄悄变成一次全新付费分析。 */
function contentChanged(value: ContentChoice) {
  contentChoice.value = value
  goalChanged()
}
/** 范围改为有限或命题时取消普通讨论基准。 */
function scopeChanged() {
  if (analysisScope.value !== 'FULL') baselineRequestId.value = ''
  goalChanged()
}
/** 示例只填入问题并做只读范围预检；不会提交任务，已选基准时禁止覆盖原问题。 */
function useExample(value: string) {
  if (baselineRequestId.value || submitting.value) return
  instruction.value = value
  instructionChanged()
}
/** 原话改变后先清除上次补充标准，避免旧命题与新命题冲突导致页面无法纠正。 */
function instructionChanged() {
  proposition.value = ''
  goalChanged()
}
let generation = 0
let pollTimer: number | undefined

const selectedMaterial = computed(() =>
  materials.value.find((item) => item.snapshotId === snapshotId.value),
)

/** 1、读取有限资料列表；2、恢复首选或唯一资料；3、读取自然语言请求历史；4、不会因进入页面自动发起分析。 */
async function load() {
  const version = ++generation
  loading.value = true
  error.value = ''
  submitting.value = false
  active.value = null
  artifact.value = null
  activeContext.value = null
  failure.value = null
  preview.value = null
  previewGeneration++
  proposition.value = ''
  analysisScope.value = 'FULL'
  confirmFullScope.value = false
  progress.value = null
  referenceIds.value = []
  includeTranscript.value = false
  transcriptMaterialId.value = ''
  contentChoice.value = { materialId: '', from: '', to: '' }
  baselineRequestId.value = ''
  sourceCoverage.value = null
  stopPolling()
  try {
    const [materialPage, requestPage] = await Promise.all([
      readApi<Page<ProjectSnapshot>>(
        '/api/v1/projects/' + encodeURIComponent(props.projectId) + '/snapshots?page=1&size=50',
      ),
      readApi<Page<RequestItem>>(
        '/api/v1/projects/' +
          encodeURIComponent(props.projectId) +
          '/analysis-requests?page=1&size=10',
      ),
    ])
    if (version !== generation) return
    materials.value = materialPage.items
    history.value = requestPage.items
    const preferred = props.preferredSnapshotId
    if (preferred && materialPage.items.some((item) => item.snapshotId === preferred)) {
      snapshotId.value = preferred
    } else if (materialPage.items.length === 1) {
      snapshotId.value = materialPage.items[0].snapshotId
    } else if (!materialPage.items.some((item) => item.snapshotId === snapshotId.value)) {
      snapshotId.value = ''
    }
    // 读取集合子请求的明确身份，旧资料即使不在首页50条中也核对成员后再展示。
    if (props.initialRequestId) {
      const request = await readApi<RequestItem>(requestUrl(props.initialRequestId))
      if (version !== generation) return
      if (!materials.value.some((item) => item.snapshotId === request.snapshotId)) {
        const member = await readApi<ProjectSnapshot>(
          `/api/v1/projects/${encodeURIComponent(props.projectId)}/snapshots/${encodeURIComponent(request.snapshotId)}`,
        )
        if (version !== generation) return
        materials.value = [member, ...materials.value]
      }
      await openRequest(request)
    }
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '智能分析页面读取失败。'
  } finally {
    if (version === generation) loading.value = false
  }
}

/** 提交用户原话；页面只选业务资料，内部配方、模型和工具都由后端确定。 */
async function submit() {
  if (submitting.value) return
  if (!instruction.value.trim()) {
    error.value = '请先写下你想了解的问题。'
    return
  }
  if (materials.value.length > 1 && !snapshotId.value) {
    error.value = '这个项目有多份资料，请先选择本次要分析的作品。'
    return
  }
  const version = generation
  submitting.value = true
  error.value = ''
  const ready = await loadPreview()
  if (version !== generation) return
  if (!ready) {
    submitting.value = false
    return
  }
  if (preview.value?.goal?.needsProposition) {
    error.value = preview.value.goal.explanation
    submitting.value = false
    return
  }
  if (preview.value?.fullScope && (!preview.value.fullScope.available || !confirmFullScope.value)) {
    error.value = '请核对完整范围可用性，并确认本次范围与费用估算上界。'
    submitting.value = false
    return
  }
  if (preview.value?.requiresPartialConsent && !partialConsent.value) {
    error.value = '部分评论不会选入本次分析，请先确认有限输入范围；可改选全部已保存评论并核对费用。'
    submitting.value = false
    return
  }
  failure.value = null
  artifact.value = null
  active.value = null
  progress.value = null
  activeContext.value = null
  stopPolling()
  try {
    const body: {
      transcriptMaterialId?: string
      contentMaterialId?: string
      contentFromMs?: number
      contentToMs?: number
      baselineRequestId?: string
      instruction: string
      snapshotId?: string
      includeTranscript: boolean
      referenceIds: string[]
      allowPartialInput: boolean
      proposition?: string
      analysisScope?: string
      confirmFullScope: boolean
    } = {
      transcriptMaterialId: transcriptMaterialId.value || undefined,
      ...contentChoiceBody(contentChoice.value),
      baselineRequestId: baselineRequestId.value || undefined,
      instruction: instruction.value.trim(),
      includeTranscript: includeTranscript.value,
      referenceIds: [...referenceIds.value].sort(),
      allowPartialInput: partialConsent.value,
      proposition: proposition.value.trim() || undefined,
      analysisScope: analysisScope.value === 'AUTO' ? undefined : analysisScope.value,
      confirmFullScope: confirmFullScope.value,
    }
    if (snapshotId.value) body.snapshotId = snapshotId.value
    const encoded = JSON.stringify(body)
    if (submission?.input !== encoded) submission = { input: encoded, key: crypto.randomUUID() }
    const created = await readApi<RequestItem>(
      '/api/v1/projects/' + encodeURIComponent(props.projectId) + '/analysis-requests',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': submission!.key,
        },
        body: encoded,
      },
    )
    if (version !== generation) return
    submission = undefined
    active.value = created
    history.value = [created, ...history.value.filter((item) => item.id !== created.id)]
    await refreshActive()
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '分析请求提交失败。'
  } finally {
    if (version === generation) submitting.value = false
  }
}

/** 状态完全以服务端为准；完成后读取不可变产物，失败时保留原请求便于诊断。 */
async function refreshActive() {
  if (!active.value) return
  stopPolling()
  const requestId = active.value.id
  const version = generation
  try {
    const state = await readApi<RequestItem>(requestUrl(requestId))
    if (version !== generation || active.value?.id !== requestId) return
    const latestProgress = await readApi<Progress>(requestUrl(requestId) + '/progress')
    if (version !== generation || active.value?.id !== requestId) return
    progress.value = latestProgress
    active.value = state
    history.value = history.value.map((item) => (item.id === state.id ? state : item))
    if (state.status === 'SUCCESS') {
      const [result, context] = await Promise.all([
        readApi<{ artifact: Artifact; sourceCoverage: CoverageView }>(
          requestUrl(state.id) + '/report',
        ),
        readApi<ContextView>(requestUrl(state.id) + '/context'),
      ])
      if (version === generation && active.value?.id === requestId) {
        artifact.value = result.artifact
        sourceCoverage.value = result.sourceCoverage
        activeContext.value = context
      }
    } else if (state.status === 'CANCELLED') {
      failure.value = null
      artifact.value = null
    } else if (state.status === 'FAILED') {
      failure.value = null
      const detail = await readApi<FailureDetail>(requestUrl(state.id) + '/failure')
      if (version === generation && active.value?.id === requestId) failure.value = detail
    } else if (state.status === 'PENDING' || state.status === 'RUNNING') {
      failure.value = null
      pollTimer = window.setTimeout(() => void refreshActive(), 1000)
    }
  } catch (caught) {
    if (version === generation && active.value?.id === requestId)
      error.value = caught instanceof Error ? caught.message : '读取分析结果失败。'
  }
}

/** 打开历史请求只读结果；进行中的请求继续短轮询，不重新创建任务。 */
async function openRequest(item: RequestItem) {
  stopPolling()
  failure.value = null
  progress.value = null
  active.value = item
  instruction.value = item.instruction
  snapshotId.value = item.snapshotId
  artifact.value = null
  activeContext.value = null
  const version = generation
  try {
    const [context, spec, scopeProgress] = await Promise.all([
      readApi<ContextView>(requestUrl(item.id) + '/context'),
      readApi<{ specification: { proposition: string; origin: string } | null }>(
        requestUrl(item.id) + '/specification',
      ),
      readApi<Progress>(requestUrl(item.id) + '/progress'),
    ])
    if (version !== generation || active.value?.id !== item.id) return
    proposition.value =
      spec.specification?.origin === 'USER_CLARIFICATION' ? spec.specification.proposition : ''
    analysisScope.value = scopeProgress.mode === 'FULL' ? 'FULL' : 'PREVIEW'
    progress.value = scopeProgress
    confirmFullScope.value = false
    transcriptMaterialId.value = context.selection?.retrieval?.materialId || ''
    includeTranscript.value =
      !transcriptMaterialId.value && (context.selection?.transcriptRequested || false)
    referenceIds.value = context.selection?.references.map((note) => note.id) || []
    activeContext.value = context
    contentChoice.value = {
      materialId: context.selection?.content?.materialId || '',
      from:
        context.selection?.content?.fromMs == null ? '' : context.selection.content.fromMs / 1000,
      to: context.selection?.content?.toMs == null ? '' : context.selection.content.toMs / 1000,
    }
    baselineRequestId.value = ''
    await loadPreview()
    if (version !== generation || active.value?.id !== item.id) return
    await refreshActive()
  } catch (caught) {
    if (version === generation && active.value?.id === item.id)
      error.value = caught instanceof Error ? caught.message : '原请求背景读取失败。'
  }
}

/** 项目和请求身份由固定路径构造，用户指令不会进入 URL。 */
function requestUrl(requestId: string) {
  return (
    '/api/v1/projects/' +
    encodeURIComponent(props.projectId) +
    '/analysis-requests/' +
    encodeURIComponent(requestId)
  )
}

/** 当前组件卸载或切换请求时停止浏览器计时器，服务端任务仍安全继续。 */
function stopPolling() {
  if (pollTimer !== undefined) window.clearTimeout(pollTimer)
  pollTimer = undefined
}

/** 追问历史导航只读取原请求；接受子请求后仍沿用原轮询与报告组件。 */
async function openRelatedRequest(requestId: string) {
  try {
    const item = await readApi<RequestItem>(requestUrl(requestId))
    await openRequest(item)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '关联请求读取失败。'
  }
}

/** 将证据 ID 转成当前快照原文；缺失引用不由页面猜测补齐。 */
function evidenceText(ids: string[]) {
  if (!artifact.value) return []
  const expected = new Set(ids)
  return artifact.value.evidence.filter((item) => expected.has(item.id))
}

/** 只读展示当前议题内有明确两端的关系，顺序固定为“前一观点对后一观点”，不猜缺失观点。 */
function relationsForIssue(issueId: string) {
  if (!artifact.value) return []
  const claims = new Map(artifact.value.claims.map((claim) => [claim.id, claim]))
  return artifact.value.relations.flatMap((relation) => {
    const from = claims.get(relation.fromClaimId),
      to = claims.get(relation.toClaimId)
    return from?.issueId === issueId && to?.issueId === issueId
      ? [{ ...relation, from: from.statement, to: to.statement }]
      : []
  })
}
/** “回复”仅是结构；明确表达/条件推断/未知分别呈现，不能把模型推断写成事实。 */
function relationName(type: string, status: string) {
  const types: Record<string, string> = {
    SUPPORTS: '支持',
    REBUTS: '反驳',
    CLARIFIES: '澄清',
    QUESTIONS: '质疑',
    REPLY_TO: '回复',
  }
  const statuses: Record<string, string> = {
    EXPLICIT: '原文明确表达',
    INFERRED: '模型推断，需核对',
    UNKNOWN: '语义关系待核对',
  }
  return {
    type: types[type] || '关系待核对',
    status:
      type === 'REPLY_TO'
        ? '只表示回复结构，不代表支持或反对'
        : statuses[status] || '语义关系待核对',
  }
}

/** 页面只翻译可控状态，不显示内部步骤名或供应商异常正文。 */
function requestStatus(status: string) {
  const names: Record<string, string> = {
    PENDING: '等待分析',
    RUNNING: '正在分析',
    SUCCESS: '分析完成',
    FAILED: '分析失败',
    CANCELLED: '已取消',
  }
  return names[status] || status
}

/** 将评议强度翻译成用户可读边界，避免把证据不足展示成胜负判断。 */
function assessmentName(verdict: string) {
  const names: Record<string, string> = {
    MORE_SUPPORTED: '一方理由更充分',
    MIXED: '双方各有依据',
    INSUFFICIENT: '证据不足，暂不比较',
  }
  return names[verdict] || verdict
}

watch(
  () => [props.projectId, snapshotId.value, includeTranscript.value, referenceIds.value.join(',')],
  () => {
    partialConsent.value = false
    confirmFullScope.value = false
    void loadPreview()
  },
)
watch(
  snapshotId,
  () => {
    transcriptMaterialId.value = ''
    contentChoice.value = { materialId: '', from: '', to: '' }
    baselineRequestId.value = ''
    sourceCoverage.value = null
  },
  { flush: 'sync' },
)
watch(() => [props.projectId, props.preferredSnapshotId, props.initialRequestId], load, {
  immediate: true,
})
onUnmounted(() => {
  generation++
  previewGeneration++
  stopPolling()
})
</script>

<template>
  <section class="insight-workbench">
    <div class="insight-hero">
      <div>
        <p class="eyebrow">INTELLIGENT ANALYSIS</p>
        <h2>直接说出你想了解什么</h2>
        <p>你负责提出问题，系统负责绑定资料、安排分析并给出可核查的原文依据。</p>
      </div>
      <span class="source-badge">问题驱动 · 原文可查</span>
    </div>

    <p v-if="loading" role="status">正在准备项目资料…</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <div v-if="!loading && !materials.length" class="empty-state">
      <h2>还没有可以分析的资料</h2>
      <p>先到“资料与作品”导入一份评论资料，再回来直接提问。</p>
    </div>
    <p class="analysis-intro">
      围绕问题整理观点、回应与理由。支持或反对必须有明确命题，默认不贴正负面标签。
    </p>
    <details
      v-if="!loading &amp;&amp; materials.length"
      class="composer-disclosure"
      :open="!artifact"
    >
      <summary>
        {{ artifact ? '再提一个问题' : '新建分析' }}<span>选择资料 · 描述问题 · 确认范围</span>
      </summary>
      <form class="insight-composer" @submit.prevent="submit">
        <label class="field" v-if="materials.length > 1">
          本次分析哪份资料
          <select v-model="snapshotId" required>
            <option value="" disabled>请选择作品或资料版本</option>
            <option v-for="item in materials" :key="item.snapshotId" :value="item.snapshotId">
              {{ sourceName(item.sourceType) }} · {{ item.title }} · {{ item.commentCount }} 条评论
            </option>
          </select>
        </label>
        <div class="selected-material" v-else-if="selectedMaterial">
          <span class="source-badge">{{ sourceName(selectedMaterial.sourceType) }}</span>
          <strong>{{ selectedMaterial.title }}</strong>
          <small>{{ selectedMaterial.commentCount }} 条评论</small>
        </div>
        <SourceCoverageNotice
          v-if="preview?.sourceCoverage"
          :view="{ frozen: false, coverage: preview.sourceCoverage }"
        />
        <label class="field command-field">
          <p v-if="selectedMaterial?.collectionId" class="scope-copy">
            本次资料：{{
              coverageName(selectedMaterial.sourceCompleteness)
            }}。分析只对已保存评论负责。
          </p>
          你的分析要求
          <textarea
            v-model="instruction"
            @change="instructionChanged"
            :readonly="!!baselineRequestId"
            maxlength="1000"
            rows="5"
            placeholder="例如：总结这个视频下面大家讨论问题的逻辑，整理主要观点，并判断谁的理由更充分。"
          ></textarea>
        </label>
        <div class="question-examples" aria-label="分析问题示例">
          <span>试试这样问</span>
          <button
            type="button"
            :disabled="submitting || !!baselineRequestId"
            @click="useExample('总结评论中的主要分歧，分别说明理由和仍缺的证据')"
          >
            主要分歧与理由
          </button>
          <button
            type="button"
            :disabled="submitting || !!baselineRequestId"
            @click="
              useExample('围绕命题「这段剧情的因果关系自洽」，分析支持、反对与有条件成立的主张')
            "
          >
            围绕命题看立场
          </button>
        </div>
        <details class="background-options">
          <summary>补充背景（可选）：字幕与参考资料</summary>
          <label class="background-checkbox"
            ><input
              v-model="includeTranscript"
              type="checkbox"
              :disabled="submitting || !!transcriptMaterialId"
            /><span>使用当前资料快照已有的字幕（不联网抓取；没有字幕会明确显示0段）</span></label
          >
          <TranscriptLibrary
            v-if="snapshotId"
            :project-id="projectId"
            :snapshot-id="snapshotId"
            :selected-id="transcriptMaterialId"
            picker
            @select="selectTranscript"
          />
          <ContentContextPicker
            v-if="!preview?.goal?.stanceRequested"
            :key="'content-' + projectId"
            :project-id="projectId"
            :model-value="contentChoice"
            :disabled="submitting"
            @update:model-value="contentChanged"
          />
          <ReferencePicker
            :key="projectId"
            :project-id="projectId"
            :reference-ids="referenceIds"
            @change="referenceIds = $event"
          />
          <p class="small">
            提交时固定本次背景；字幕/参考不是评论。纯评论请求维持原流程，免费演示不会理解背景。
          </p>
        </details>
        <section
          v-if="preview?.goal?.stanceRequested"
          class="scope-preview"
          aria-label="确认判断标准"
        >
          <h3>先明确支持或反对哪句话</h3>
          <p>{{ preview.goal.explanation }}</p>
          <p v-if="preview.goal.specification?.origin === 'USER_INSTRUCTION'">
            本次命题：{{ preview.goal.specification.proposition }}
          </p>
          <label v-else class="field"
            >请补充一个具体命题
            <input
              v-model="proposition"
              maxlength="300"
              placeholder="例如：这段剧情的因果关系自洽"
              @change="goalChanged"
              :disabled="submitting"
            />
          </label>
          <p class="small">
            也可直接写：围绕命题「这段剧情的因果关系自洽」，分析哪些主张支持、反对或有条件成立。
          </p>
          <button type="button" :disabled="previewLoading || submitting" @click="loadPreview">
            核对目标与范围
          </button>
        </section>
        <label class="field"
          >本次评论范围
          <select v-model="analysisScope" @change="scopeChanged" :disabled="submitting">
            <option value="AUTO">按原话识别范围</option>
            <option value="PREVIEW">先看少量评论预览</option>
            <option value="FULL">全部已保存评论</option>
          </select>
        </label>
        <details
          class="reuse-options"
          v-if="snapshotId &amp;&amp; analysisScope === 'FULL' &amp;&amp; !preview?.goal?.stanceRequested"
          :open="!!baselineRequestId"
        >
          <summary>沿用已有分析（可选）<span v-if="baselineRequestId"> · 已选择</span></summary>
          <DiscussionBaselinePicker
            v-if="snapshotId && analysisScope === 'FULL' && !preview?.goal?.stanceRequested"
            :project-id="projectId"
            :snapshot-id="snapshotId"
            :model-value="baselineRequestId"
            :disabled="submitting"
            @chosen="baselineChosen"
            @update:model-value="baselineChanged"
          />
          <p v-if="baselineRequestId" class="small">
            已沿用基准问题；要换问题，请先选择“全部重新分析”。
          </p>
        </details>
        <section class="scope-preview" aria-label="本次分析范围">
          <h3>本次处理范围</h3>
          <p v-if="previewLoading" role="status">正在核对资料范围，不会调用模型…</p>
          <p v-if="previewError" class="error" role="alert">
            {{ previewError }} <button type="button" @click="loadPreview">重新核对</button>
          </p>
          <template v-if="preview">
            <p v-if="preview.mode === 'STRUCTURAL_DEMO'" class="mock-note">
              当前是结构演示：只核对输入与处理计划，不调用模型、不产生模型费用，也不生成语义结论。
            </p>
            <p>
              已保存{{ preview.savedComments }}条；模型规则可选{{
                preview.selectedComments
              }}条；未选入{{ preview.omittedComments }}条；缺失父引用{{
                preview.missingParentCount
              }}条。
            </p>
            <p v-if="preview.reuse" class="small">
              本次确认可复用{{ preview.reuse.reusedChunks }}片；汇总仍重新执行，费用属于各自原任务。
            </p>
            <section v-if="preview.content" class="content-binding-summary">
              <strong>对照“{{ preview.content.title }}”</strong>
              <p>
                所选范围{{ preview.content.eligibleSegments }}段，纳入{{
                  preview.content.selectedSegments
                }}段 / {{ preview.content.selectedChars }}字符。
              </p>
              <p v-if="!preview.content.selectedSegments" class="error">
                本次没有可用内容原段；可调整问题或字幕范围，不能据此判断作者意图。
              </p>
              <details>
                <summary>内容选择方式与缺口</summary>
                <p v-for="note in preview.content.notes" :key="note" class="small">{{ note }}</p>
              </details>
            </section>
            <details>
              <summary>处理规则与资料边界</summary>
              <p v-for="note in preview.notes" :key="note" class="small">{{ note }}</p>
            </details>
            <section v-if="preview.fullScope" aria-label="完整范围与费用核对">
              <h4>全部已保存评论的处理计划</h4>
              <p>
                {{ preview.fullScope.targetComments }} 条目标 ·
                {{ preview.fullScope.plannedBatches }}
                {{
                  preview.mode === 'STRUCTURAL_DEMO'
                    ? '个计划节点（演示不执行）'
                    : '次计划新调用（含汇总）'
                }}；父语境只供理解，不重复统计。
              </p>
              <p v-if="preview.fullScope.unit === 'USD_NANOS'">
                本地保守费用上界约
                {{ (Math.ceil(preview.fullScope.maximumUnits / 1000) / 1000000).toFixed(6) }} USD；
                是按固定单价和每片Token上限估算，不是供应商账单。
              </p>
              <FailureNotice
                v-if="!preview.fullScope.available"
                title="当前无法新建完整范围请求"
                :code="preview.fullScope.reasonCode"
              />
              <label v-else class="background-checkbox"
                ><input v-model="confirmFullScope" type="checkbox" :disabled="submitting" />
                {{
                  preview.mode === 'STRUCTURAL_DEMO'
                    ? '我已确认本次结构演示范围；不执行语义分析。'
                    : '我已确认本次全部范围及费用估算上界；只覆盖当前已保存快照。'
                }}</label
              >
            </section>
            <label v-if="preview.requiresPartialConsent" class="background-checkbox"
              ><input
                type="checkbox"
                v-model="partialConsent"
                :disabled="submitting"
              />我接受仅分析上述有限输入；本次不代表全部评论。</label
            >
          </template>
        </section>
        <div class="composer-actions">
          <span>{{ instruction.trim().length }} / 1000</span>
          <button
            class="primary"
            type="submit"
            :disabled="
              submitting ||
              !instruction.trim() ||
              previewLoading ||
              !preview ||
              preview.goal?.needsProposition ||
              (preview.fullScope && (!preview.fullScope.available || !confirmFullScope)) ||
              (preview.requiresPartialConsent && !partialConsent)
            "
          >
            {{ submitting ? '正在提交…' : '按上述范围分析' }}
          </button>
        </div>
      </form>
    </details>

    <section v-if="active" class="analysis-progress" aria-live="polite">
      <div>
        <span class="state-badge" :class="active.status.toLowerCase()">{{
          requestStatus(active.status)
        }}</span>
        <strong>{{ active.instruction }}</strong>
      </div>
      <button
        v-if="active.status === 'PENDING' || active.status === 'RUNNING'"
        class="quiet"
        @click="refreshActive"
      >
        刷新状态
      </button>
    </section>

    <section
      v-if="active && progress?.mode === 'FULL'"
      class="scope-preview"
      aria-label="完整分析进度"
      aria-live="polite"
    >
      <h3>全部已保存评论的处理进度</h3>
      <p v-if="progress.processing">{{ digestProgressText(progress.processing) }}</p>
      <p v-else>
        已完成 {{ progress.completedComments }} / {{ progress.targetComments }} 条目标评论，
        {{ progress.completedBatches }} / {{ progress.plannedBatches }} 批。
      </p>
      <p>
        {{
          progress.published
            ? artifact?.sourceMode === 'STRUCTURAL_DEMO'
              ? '结构演示已完成，未执行语义分析。'
              : '结果已发布；完整输入处理不等于独立语义质量通过。'
            : '进度来自持久检查点，全部成功后才发布；关闭页面不会停止后台任务。'
        }}
      </p>
    </section>
    <TaskControlPanel
      v-if="active?.jobId && active.status !== 'SUCCESS'"
      :project-id="projectId"
      kind="ANALYSIS"
      :task-id="active.jobId"
      @changed="refreshActive"
    />
    <FailureNotice
      v-if="active?.status === 'FAILED'"
      :code="active.lastErrorCode"
      :cause-code="failure?.causeCode"
      :fee-uncertain="failure?.feeUncertain"
      :task-id="active.jobId"
    >
      <button type="button" @click="refreshActive">重新查询状态</button>
      <button v-if="failure?.canRetry" type="button" :disabled="submitting" @click="retryActive">
        重试原任务
      </button>
    </FailureNotice>
    <details v-if="active" class="metadata-detail">
      <summary>执行与费用记录（只读）</summary>
      <ExecutionDetails :key="active.jobId" :job-id="active.jobId" :status="active.status" />
    </details>

    <section v-if="artifact" class="artifact-view">
      <header class="artifact-heading">
        <div>
          <p class="eyebrow">ANALYSIS RESULT</p>
          <h2>{{ artifact.title }}</h2>
          <p>{{ artifact.instruction }}</p>
        </div>
        <span class="mode-badge">{{
          artifact.sourceMode === 'STRUCTURAL_DEMO'
            ? '结构演示 · 未做语义判断'
            : artifact.sourceMode === 'LOCAL_STUB'
              ? '工程替身 · 非真实语义成绩'
              : '模型分析'
        }}</span>
      </header>
      <div class="scope-strip">
        <span v-if="artifact.sourceType === 'BILIBILI'">资料范围不等于平台全部评论</span>
        <span
          ><b>{{ artifact.scope.totalComments }}</b> 条输入评论</span
        >
        <span
          ><b>{{ artifact.scope.selectedThreads }}</b> 个讨论串</span
        >
        <span
          ><b>{{ artifact.stance?.statistics?.totalClaims ?? artifact.claims.length }}</b>
          条主张</span
        >
        <span v-if="artifact.scope.truncated">当前为有限展示</span>
        <span v-if="artifact.scope.selectedComments != null"
          >{{ artifact.processing ? '已处理范围（含复用）' : '已向模型发送' }}
          {{ artifact.scope.selectedComments }} 条（含父上下文）</span
        >
      </div>
      <SourceCoverageNotice v-if="sourceCoverage" :view="sourceCoverage" />
      <nav v-if="active" class="report-downloads" aria-label="导出本次报告">
        <a :href="requestUrl(active.id) + '/report/export?format=markdown'" download>下载报告</a
        ><a :href="requestUrl(active.id) + '/report/export?format=json'" download
          >结构化数据与证据</a
        >
        <a :href="requestUrl(active.id) + '/report/export?format=review-json'" download
          >复核资料包</a
        >
      </nav>
      <DiscussionDigestPanel
        v-if="artifact.processing && active"
        :key="'digest-' + active.id"
        :project-id="projectId"
        :request-id="active.id"
        :processing="artifact.processing"
        :source-mode="artifact.sourceMode"
      />
      <details v-if="artifact.context" class="surface context-scope">
        <summary>本次背景与来源</summary>
        <h3>本次背景范围</h3>
        <p>
          {{
            artifact.context.sentToModel
              ? '已作为独立背景送入模型'
              : artifact.sourceMode === 'MODEL'
                ? '本次没有可用背景，模型仅分析评论'
                : '已冻结，但当前结构演示未使用模型理解背景'
          }}
          · 参考{{ artifact.context.referenceDocuments }}份 · 字幕选取{{
            artifact.context.selectedTranscriptSegments
          }}/{{ artifact.context.totalTranscriptSegments }}段 · {{ artifact.context.textChars }}字
        </p>
        <p v-if="artifact.context.transcriptTruncated" class="scope-copy">
          {{
            activeContext?.selection?.retrieval
              ? '上传字幕只选本问题字词命中及邻域，未选部分不能推断。'
              : '字幕只使用有界完整段前缀，未处理部分仍保留在快照中。'
          }}
        </p>
        <details v-if="activeContext?.selection">
          <summary>查看这次固定的背景原文与角色</summary>
          <section v-for="source in activeContext.selection.sources" :key="source.id">
            <h4>
              {{
                evidenceSource({
                  sourceRole: source.role,
                  title: source.title,
                  startMs: source.startMs,
                  endMs: source.endMs,
                  startChar: source.startChar,
                  endChar: source.endChar,
                })
              }}
            </h4>
            <blockquote class="frozen-context-text">{{ source.text }}</blockquote>
          </section>
          <p v-if="!activeContext.selection.sources.length">
            本次没有可用背景原文；没有自动补入其他资料。
          </p>
        </details>
      </details>
      <StanceResults
        v-if="artifact.stance"
        :project-id="projectId"
        :request-id="active?.id"
        :result="artifact.stance"
        :evidence="artifact.evidence"
      />
      <article class="issue-card" v-for="issue in artifact.issues" :key="issue.id">
        <span class="issue-index">议题 {{ issue.id.replace('issue-', '') }}</span>
        <h3>{{ issue.question }}</h3>
        <p>{{ issue.summary }}</p>
        <div class="claim-list">
          <div
            class="claim-item"
            v-for="claim in artifact.claims.filter((item) => item.issueId === issue.id)"
            :key="claim.id"
          >
            <span>回复观点</span>
            <p>{{ claim.statement }}</p>
            <details>
              <summary>查看原文依据</summary>
              <blockquote v-for="quote in evidenceText(claim.evidenceIds)" :key="quote.id">
                <small>{{ evidenceSource(quote) }}</small>
                <p>{{ quote.text }}</p>
              </blockquote>
            </details>
          </div>
        </div>
        <details v-if="relationsForIssue(issue.id).length" class="argument-relations">
          <summary>观点之间如何回应（{{ relationsForIssue(issue.id).length }}组）</summary>
          <article
            v-for="(relation, index) in relationsForIssue(issue.id)"
            :key="index"
            class="argument-relation"
          >
            <p>{{ relation.from }}</p>
            <strong>{{ relationName(relation.type, relation.semanticStatus).type }} ↓</strong>
            <p>{{ relation.to }}</p>
            <small>{{ relationName(relation.type, relation.semanticStatus).status }}</small>
            <details v-if="relation.evidenceIds?.length">
              <summary>关系的原文依据</summary>
              <blockquote v-for="quote in evidenceText(relation.evidenceIds)" :key="quote.id">
                <small>{{ evidenceSource(quote) }}</small>
                <p>{{ quote.text }}</p>
              </blockquote>
            </details>
          </article>
        </details>
        <div
          class="assessment-box"
          v-for="assessment in artifact.assessments.filter((item) => item.issueId === issue.id)"
          :key="assessment.issueId"
        >
          <span class="assessment-verdict">{{ assessmentName(assessment.verdict) }}</span>
          <strong>理由比较：{{ assessment.conclusion }}</strong>
          <p>{{ assessment.reason }}</p>
          <details v-if="assessment.evidenceIds.length">
            <summary>查看评议依据</summary>
            <blockquote v-for="quote in evidenceText(assessment.evidenceIds)" :key="quote.id">
              {{ quote.text }}
            </blockquote>
          </details>
        </div>
      </article>
      <ContinuationPanel
        v-if="active?.status === 'SUCCESS'"
        :key="'followup-' + active.id"
        :project-id="projectId"
        :request-id="active.id"
        @open="openRelatedRequest"
      />
      <RevisionComparisonPanel
        v-if="active?.status === 'SUCCESS'"
        :key="'revision-' + active.id"
        :project-id="projectId"
        :request-id="active.id"
        :analyzed="Boolean(artifact.stance?.analyzed)"
      />
      <SemanticReviewPanel
        v-if="active?.status === 'SUCCESS' && !artifact.stance"
        :key="active.id"
        :project-id="projectId"
        :request-id="active.id"
        :artifact="artifact"
      />
      <details class="analysis-limits">
        <summary>当前结果能说明什么</summary>
        <ul>
          <li v-for="item in artifact.limitations" :key="item">{{ item }}</li>
        </ul>
      </details>
    </section>

    <section class="request-history" v-if="history.length">
      <div class="section-toolbar">
        <h2>最近的智能分析</h2>
        <button class="quiet" @click="load">刷新</button>
      </div>
      <button
        class="request-history-item"
        v-for="item in history"
        :key="item.id"
        @click="openRequest(item)"
      >
        <span>{{ item.instruction }}</span>
        <small>{{ item.createdAt }} · {{ requestStatus(item.status) }}</small>
      </button>
    </section>
  </section>
</template>

<style scoped>
.background-options {
  padding: 1rem 0;
}
.background-checkbox {
  display: flex;
  align-items: flex-start;
  gap: 0.7rem;
  padding: 0.7rem 0;
}
.background-checkbox input {
  width: auto;
  margin-top: 0.25rem;
}
.context-scope {
  margin: 1rem 0;
}
.frozen-context-text {
  white-space: pre-wrap;
}
</style>
