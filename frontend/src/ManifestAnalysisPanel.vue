<!-- 文件职责：对一份冻结集合提交相同自然语言目标，观察分作品状态并按需回看原文证据。 -->
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import FailureNotice from './FailureNotice.vue'
import ManifestEvidencePanel from './ManifestEvidencePanel.vue'
import { readApi, evidenceSource, type Page } from './projectApi'

const props = defineProps<{ projectId: string; manifestId: string; itemCount: number }>()
type Item = {
  ordinal: number
  title: string
  snapshotId: string
  requestId: string
  jobId: string
  status: string
  artifactId: string | null
  lastErrorCode: string | null
}
type Run = {
  id: string
  instruction: string
  status: string
  sourceMode: string
  aggregateBudgetUsd: string
  succeeded: number
  failed: number
  pending: number
  cancelled: number
  createdAt: string
  items: Item[]
  limitations: string[]
}
type Artifact = {
  sourceMode: string
  scope: { totalComments: number; truncated: boolean }
  issues: Array<{ id: string; question: string; summary: string; evidenceIds: string[] }>
  assessments: Array<{ issueId: string; conclusion: string; reason: string; evidenceIds: string[] }>
  evidence: Array<{
    id: string
    text: string
    sourceRole?: string | null
    title?: string | null
    startMs?: number | null
    endMs?: number | null
  }>
  limitations: string[]
}
const instruction = ref('总结各作品下面的讨论逻辑，整理主要观点并比较理由')
const current = ref<Run | null>(null)
const history = ref<Run[]>([])
const artifacts = ref<Record<string, Artifact>>({})
const error = ref('')
const busy = ref(false)
const preview = ref<{ mode: string; taskBudgetUsd: string } | null>(null)
let timer: number | undefined
let alive = true
let submission: { instruction: string; key: string } | undefined
const running = computed(() => ['PENDING', 'RUNNING'].includes(current.value?.status || ''))

/** 页面只显示费用上限与业务范围，模型身份、工具与价格不作为必填设置。 */
async function load() {
  try {
    const [page, status] = await Promise.all([
      readApi<Page<Run>>(
        `/api/v1/projects/${props.projectId}/manifest-analyses?manifestId=${props.manifestId}&size=5`,
      ),
      readApi<{ mode: string; taskBudgetUsd: string }>('/api/v1/model/status'),
    ])
    if (!alive) return
    history.value = page.items
    preview.value = status
    current.value = page.items[0] || null
    if (current.value) await poll()
  } catch (caught) {
    if (alive) error.value = caught instanceof Error ? caught.message : '集合分析读取失败。'
  }
}

/** 同一要求重发复用请求键；用户点击后才接受可能收费的分作品执行。 */
async function submit() {
  if (busy.value || running.value) return
  busy.value = true
  error.value = ''
  const clean = instruction.value.trim()
  if (submission?.instruction !== clean)
    submission = { instruction: clean, key: crypto.randomUUID() }
  try {
    current.value = await readApi<Run>(`/api/v1/projects/${props.projectId}/manifest-analyses`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': submission!.key },
      body: JSON.stringify({ manifestId: props.manifestId, instruction: clean }),
    })
    history.value = [
      current.value,
      ...history.value.filter((item) => item.id !== current.value?.id),
    ].slice(0, 5)
    submission = undefined
    await poll()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '集合分析提交失败。'
  } finally {
    busy.value = false
  }
}

/** 定时读取派生状态，不按已过时间估算成功；组件切换时停止轮询。 */
async function poll() {
  stop()
  if (!alive || !current.value) return
  const id = current.value.id
  try {
    const run = await readApi<Run>(`/api/v1/projects/${props.projectId}/manifest-analyses/${id}`)
    if (!alive || current.value?.id !== id) return
    current.value = run
    if (['PENDING', 'RUNNING'].includes(run.status)) timer = window.setTimeout(poll, 1500)
  } catch (caught) {
    if (alive) error.value = caught instanceof Error ? caught.message : '状态读取失败，可刷新重试。'
  }
}

/** 成功子请求按需读取独立产物，证据仍显示自己的快照原文，不能跨作品借用。 */
async function show(item: Item) {
  try {
    artifacts.value[item.requestId] = await readApi<Artifact>(
      `/api/v1/projects/${props.projectId}/analysis-requests/${item.requestId}/artifact`,
    )
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '结果读取失败。'
  }
}

/** 失败子任务沿用既有重试接口，结果未知时由后端拒绝盲目重发。 */
async function retry(item: Item) {
  if (busy.value) return
  busy.value = true
  try {
    await readApi(`/api/v1/analysis-jobs/${item.jobId}/retry`, { method: 'POST' })
    await poll()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '无法重试该任务。'
  } finally {
    busy.value = false
  }
}

/** 点击历史只切换已保存运行，不发起新模型任务。 */
function open(run: Run) {
  stop()
  current.value = run
  void poll()
}
function stop() {
  if (timer !== undefined) window.clearTimeout(timer)
  timer = undefined
}
function statusName(status: string) {
  const labels: Record<string, string> = {
    PENDING: '等待执行',
    RUNNING: '正在执行',
    SUCCESS: '全部完成',
    PARTIAL_FAILED: '部分失败',
    FAILED: '失败',
    CANCELLED: '全部已取消',
  }
  return labels[status] || status
}
onMounted(load)
onUnmounted(() => {
  alive = false
  stop()
})
</script>

<template>
  <section class="manifest-diff">
    <form class="insight-composer" @submit.prevent="submit">
      <h3>向这份集合提问</h3>
      <p class="small">同一要求会对每个作品独立分析，最后集中回看。跨作品语义归并尚未实现。</p>
      <label class="field"
        >你的分析要求<textarea v-model="instruction" maxlength="1000" rows="4" required />
      </label>
      <p v-if="preview?.mode === 'MODEL'" class="scope-copy">
        真实模型已启用。本集合{{ itemCount }}个作品，按当前配置总预算上限约 ${{
          (Number(preview.taskBudgetUsd) * itemCount).toFixed(2)
        }}；创建后以保存计划为准。
      </p>
      <p v-else class="scope-copy">当前为免费结构演示，不能视为语义分析结果。</p>
      <p v-if="itemCount > 5" class="error">本轮一次最多分析5个作品，请建立较小的集合。</p>
      <div class="composer-actions">
        <button class="primary" :disabled="busy || running || itemCount > 5 || !instruction.trim()">
          {{ busy ? '正在提交…' : '按本次要求分析集合' }}
        </button>
      </div>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <section v-if="current" class="surface">
      <div class="section-toolbar">
        <div>
          <h3>{{ statusName(current.status) }}</h3>
          <p>{{ current.instruction }}</p>
        </div>
        <button @click="poll">刷新状态</button>
      </div>
      <p class="scope-copy">
        成功{{ current.succeeded }} · 失败{{ current.failed }} · 已取消 {{ current.cancelled }} ·
        未完成{{ current.pending }} · 保存的总预算上限 ${{ current.aggregateBudgetUsd }}
      </p>
      <article
        v-for="item in current.items"
        :key="item.requestId"
        class="issue-card manifest-run-item"
      >
        <div class="section-toolbar">
          <strong>{{ item.title }}</strong
          ><span class="state-badge">{{ statusName(item.status) }}</span>
        </div>
        <button v-if="item.status === 'SUCCESS'" @click="show(item)">查看作品摘要与原文</button>
        <a
          v-if="item.status === 'SUCCESS'"
          class="back-link"
          :href="`?project=${encodeURIComponent(projectId)}&tab=insight&snapshot=${encodeURIComponent(item.snapshotId)}&request=${encodeURIComponent(item.requestId)}`"
          >打开完整观点分析</a
        >
        <button
          v-if="item.status === 'FAILED' && item.lastErrorCode !== 'MODEL_OUTCOME_UNKNOWN'"
          :disabled="busy"
          @click="retry(item)"
        >
          重试这份作品
        </button>
        <details v-if="item.lastErrorCode" class="metadata-detail">
          <summary>查看失败原因</summary>
          <FailureNotice :code="item.lastErrorCode" :task-id="item.jobId" />
        </details>
        <template v-if="artifacts[item.requestId]">
          <p class="scope-copy">
            {{ artifacts[item.requestId].sourceMode === 'MODEL' ? '模型分析' : '结构演示' }} ·
            当前快照{{ artifacts[item.requestId].scope.totalComments }}条；平台全量仍未证明。
          </p>
          <section
            v-for="issue in artifacts[item.requestId].issues"
            :key="issue.id"
            class="claim-item"
          >
            <h4>{{ issue.question }}</h4>
            <p>{{ issue.summary }}</p>
            <details>
              <summary>查看原文依据</summary>
              <blockquote
                v-for="evidence in artifacts[item.requestId].evidence.filter((row) =>
                  issue.evidenceIds.includes(row.id),
                )"
                :key="evidence.id"
              >
                <small>{{ evidenceSource(evidence) }}</small>
                <p>{{ evidence.text }}</p>
              </blockquote>
            </details>
            <div
              v-for="assessment in artifacts[item.requestId].assessments.filter(
                (row) => row.issueId === issue.id,
              )"
              :key="assessment.issueId"
              class="assessment-box"
            >
              <strong>{{ assessment.conclusion }}</strong>
              <p>{{ assessment.reason }}</p>
            </div>
          </section>
          <details class="analysis-limits">
            <summary>结果限制</summary>
            <p v-for="note in artifacts[item.requestId].limitations" :key="note" class="small">
              {{ note }}
            </p>
          </details>
        </template>
      </article>
      <ManifestEvidencePanel
        v-if="current.pending === 0"
        :key="current.id + ':' + current.status"
        :project-id="projectId"
        :run-id="current.id"
      />
      <p v-for="note in current.limitations" :key="note" class="scope-copy">{{ note }}</p>
    </section>
    <details v-if="history.length" class="collection-history">
      <summary>最近集合分析记录</summary>
      <button v-for="run in history" :key="run.id" class="request-history-item" @click="open(run)">
        <span>{{ run.instruction }}</span
        ><small>{{ run.createdAt }} · {{ statusName(run.status) }}</small>
      </button>
    </details>
  </section>
</template>
