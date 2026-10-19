<!-- 文件职责：创建或打开已有任务，查询持久化步骤，并展示演示报告与原文证据。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import ReportReview from './ReportReview.vue'
import ReportComments from './ReportComments.vue'
import ExecutionDetails from './ExecutionDetails.vue'
import { loadTask } from './taskStorage'
import { readApi } from './apiClient'
import FailureNotice from './FailureNotice.vue'
/** 任务概要只展示服务端已保存的状态，不在浏览器推算完成时间。 */
type Job = {
  id: string
  status: string
  progress: number
  attempts: number
  lastErrorCode: string | null
  reportId: string | null
  steps: { key: string; status: string }[]
  history: { attempt: number; status: string; errorCode: string | null }[]
}
/** 报告与证据分别读取，避免将评论正文当作页面 HTML 执行。 */
type Report = {
  snapshotId: string
  provenance: { mode: string; modelVersion: string }
  coverage: { eligibleComments: number; classifiedComments: number; unknown: number }
  metrics: {
    positive: number
    negative: number
    neutral: number
    positiveShareAmongClassified: number | null
  }
  findings: { claim: string; evidenceIds: string[] }[]
  limitations: string[]
}
/** 保存可回溯到快照的评论原文。 */
type Evidence = { commentId: string; sourceCommentId: string; text: string }
const props = defineProps<{ snapshotId: string; initialJobId?: string; fresh?: boolean }>()
const job = ref<Job | null>(null),
  report = ref<Report | null>(null),
  evidence = ref<Evidence[]>([])
/** 来源名称从已发布报告读取，重启或修改配置都不会改变历史标签。 */
function modeName(mode: string) {
  return (
    (
      { MOCK: 'Mock 演示', LOCAL_STUB: '本机替身验证', MODEL: '模型分析' } as Record<string, string>
    )[mode] || mode
  )
}
const includeExportReviews = ref(true)
const error = ref(''),
  busy = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0
/** 停止旧快照的轮询，避免后返回的请求覆盖新快照页面。 */
function clearTimer() {
  if (timer) clearTimeout(timer)
  timer = undefined
}
/** 统一处理 HTTP 错误，向用户说明冲突、服务不可达等情况。 */
async function read<T>(url: string, options?: RequestInit): Promise<T> {
  return readApi<T>(url, options)
}
/** 1、读取任务；2、完成后取报告和证据；3、未完成则继续轮询。 */
async function poll(id: string, version: number) {
  try {
    const next = await read<Job>('/api/v1/analysis-jobs/' + id)
    if (version !== generation) return
    job.value = next
    if (next.status === 'SUCCESS' && next.reportId) {
      const [body, quotes] = await Promise.all([
        read<Report>('/api/v1/reports/' + next.reportId),
        read<Evidence[]>('/api/v1/reports/' + next.reportId + '/evidence'),
      ])
      if (version !== generation) return
      report.value = body
      evidence.value = quotes
      busy.value = false
    } else if (next.status === 'FAILED' || next.status === 'CANCELLED') {
      busy.value = false
    } else timer = setTimeout(() => void poll(id, version), 1000)
  } catch (e) {
    if (version === generation) {
      error.value = e instanceof Error ? e.message : '任务查询失败。'
      busy.value = false
    }
  }
}
/** 已完成步骤由后端复用，前端只请求重试当前失败任务。 */
async function retry() {
  if (!job.value || busy.value) return
  busy.value = true
  error.value = ''
  const version = generation
  try {
    await read('/api/v1/analysis-jobs/' + job.value.id + '/retry', { method: 'POST' })
    void poll(job.value.id, version)
  } catch (e) {
    if (version === generation) {
      error.value = e instanceof Error ? e.message : '重试失败。'
      busy.value = false
    }
  }
}
/**
 * 下载已保存报告的只读副本；原文来自旧快照，核验附录由后端读取最新意见。
 * 格式只能由两个固定入口选择，不把用户输入拼接成任意下载路径。
 */
function exportUrl(format: 'markdown' | 'json') {
  const reportId = job.value?.reportId
  if (!reportId) return '#'
  return (
    '/api/v1/reports/' +
    encodeURIComponent(reportId) +
    '/export?format=' +
    format +
    '&includeReviews=' +
    includeExportReviews.value
  )
}
/** 将任务、步骤和尝试状态翻译成页面可读名称。 */
function statusName(status: string) {
  const names: Record<string, string> = {
    PENDING: '等待处理',
    RUNNING: '处理中',
    SUCCESS: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
    READY: '待执行',
    SUCCEEDED: '已完成',
    ABANDONED: '租约过期',
  }
  return names[status] || status
}
/** 查询中断时只恢复查询，不创建新任务。 */
function resume() {
  const id = job.value?.id || props.initialJobId
  if (id) {
    error.value = ''
    busy.value = true
    clearTimer()
    void poll(id, generation)
  }
}
/** 切换或卸载组件时释放计时器，旧请求通过版本号失效。 */
watch(
  () => [props.snapshotId, props.initialJobId, props.fresh],
  () => {
    // 1、停止旧轮询；2、重置当前视图；3、按快照恢复已创建任务。
    generation++
    clearTimer()
    job.value = null
    report.value = null
    evidence.value = []
    busy.value = false
    error.value = ''
    try {
      const saved = props.initialJobId || (props.fresh ? undefined : loadTask(props.snapshotId))
      if (saved) {
        busy.value = true
        void poll(saved, generation)
      }
    } catch {
      /* 存储不可用时由用户主动创建任务。 */
    }
  },
  { immediate: true },
)
onUnmounted(() => {
  generation++
  clearTimer()
})
</script>

<template>
  <div class="analysis-panel">
    <template v-if="!job && !props.initialJobId">
      <h2>旧版反馈情感报告</h2>
      <p>此页保留历史兼容。新问题请使用项目的“智能分析”，按自然语言目标整理观点和理由。</p>
    </template>
    <p v-if="props.initialJobId && !job && busy" role="status">正在读取已有分析…</p>
    <div v-if="job" aria-live="polite">
      <div class="run-status">
        <strong>{{
          job.status === 'SUCCESS' ? '分析已完成' : '分析状态：' + statusName(job.status)
        }}</strong>
        <span class="small" v-if="job.status !== 'SUCCESS'">步骤进度 {{ job.progress }}%</span>
      </div>
      <progress
        v-if="job.status !== 'SUCCESS'"
        :value="job.progress"
        max="100"
        aria-label="已持久化步骤进度"
      ></progress>
      <ol v-if="job.status !== 'SUCCESS'">
        <li v-for="step in job.steps" :key="step.key">
          {{ step.key === 'CLASSIFY' ? '评论分类' : '校验并发布报告' }}：{{
            statusName(step.status)
          }}
        </li>
      </ol>
      <p v-if="job.status === 'CANCELLED'">
        任务已取消；资料和成功检查点保留。请在项目任务中心查看费用和继续条件。
      </p>
      <FailureNotice v-if="job.status === 'FAILED'" :code="job.lastErrorCode" :task-id="job.id">
        <button
          v-if="job.lastErrorCode !== 'MODEL_OUTCOME_UNKNOWN'"
          @click="retry"
          :disabled="busy"
        >
          重试失败步骤
        </button>
      </FailureNotice>
    </div>
    <p v-if="error" role="alert">
      {{ error }} <button v-if="job || props.initialJobId" @click="resume">重新查询</button
      ><span v-else>请返回项目使用智能分析。</span>
    </p>
    <section v-if="report" class="report" aria-label="反馈分析报告">
      <h2>
        历史反馈情感报告 <span class="source-badge">{{ modeName(report.provenance.mode) }}</span>
      </h2>
      <p v-if="report.provenance.mode === 'MOCK'" class="mock-note">
        这份报告使用免费演示模式，尚未进行真实语义判断。下方数量只说明本次材料范围，不能作为分析准确率。
      </p>
      <div class="report-metrics">
        <div>
          <b>{{ report.coverage.eligibleComments }}</b
          ><span>本次评论</span>
        </div>
        <div>
          <b>{{ report.coverage.classifiedComments }}</b
          ><span>已获得分类</span>
        </div>
        <div>
          <b>{{ report.coverage.unknown }}</b
          ><span>无法判断</span>
        </div>
      </div>
      <p class="scope-copy">
        以下正负面来自旧版固定情感协议，没有绑定你本次指定的命题，不能解释成支持或反对某个观点。
      </p>
      <div v-if="report.findings.length" class="surface">
        <h3>主要发现</h3>
        <p>
          正面 {{ report.metrics.positive }} · 负面 {{ report.metrics.negative }} · 中立
          {{ report.metrics.neutral }}
        </p>
        <p>
          已分类评论的正面占比：{{
            report.metrics.positiveShareAmongClassified === null
              ? '暂无可用分母'
              : (report.metrics.positiveShareAmongClassified * 100).toFixed(1) + '%'
          }}
        </p>
        <article v-for="(finding, index) in report.findings" :key="index">
          <p>{{ finding.claim }}</p>
          <details v-for="id in finding.evidenceIds" :key="id">
            <summary>查看引用原文</summary>
            <blockquote>
              {{ evidence.find((e) => e.commentId === id)?.text || '证据暂不可用' }}
            </blockquote>
          </details>
        </article>
      </div>
      <p v-else>当前没有可用的语义结论。可以先展开原文，核对本次资料是否完整。</p>
      <details class="report-detail">
        <summary>评论原文与分类（{{ report.coverage.eligibleComments }} 条）</summary>
        <ReportComments v-if="job?.reportId" :report-id="job.reportId" />
      </details>
      <details class="report-detail">
        <summary>人工核验与补充意见（可选）</summary>
        <ReportReview v-if="job?.reportId" :report-id="job.reportId" />
      </details>
      <details class="export-actions">
        <summary>导出这份报告</summary>
        <p>下载已保存的报告与引用原文，不重新分析。</p>
        <label><input type="checkbox" v-model="includeExportReviews" />附上最新人工核验</label>
        <p>
          <a class="export-link" :href="exportUrl('markdown')" download>下载 Markdown</a
          ><a class="export-link" :href="exportUrl('json')" download>下载 JSON</a>
        </p>
      </details>
      <details class="technical-detail">
        <summary>资料范围与分析限制</summary>
        <ul>
          <li v-for="limit in report.limitations" :key="limit">{{ limit }}</li>
        </ul>
      </details>
    </section>
    <details v-if="job" class="technical-detail">
      <summary>执行与费用明细</summary>
      <ExecutionDetails :job-id="job.id" :status="job.status" />
    </details>
    <details v-if="job" class="technical-detail">
      <summary>任务与版本信息</summary>
      <p>任务：{{ job.id }} · 尝试 {{ job.attempts }} 次</p>
      <p v-if="report">
        模型版本：{{ report.provenance.modelVersion }}<br />快照：{{ report.snapshotId }}
      </p>
      <p v-for="attempt in job.history" :key="attempt.attempt">
        第 {{ attempt.attempt }} 次：{{ statusName(attempt.status) }} {{ attempt.errorCode || '' }}
      </p>
    </details>
  </div>
</template>
<style scoped>
.export-actions {
  border: 1px solid #dce5d9;
  padding: 12px;
  margin: 12px 0;
  border-radius: 8px;
}
.export-actions label {
  font-size: 12px;
}
.export-link {
  display: inline-block;
  border: 1px solid #cbd9c5;
  border-radius: 6px;
  padding: 8px 12px;
  margin-right: 10px;
  color: #285b46;
  font-size: 12px;
  text-decoration: none;
}
.analysis-panel {
  border-top: 1px solid #cbd9c5;
  margin-top: 18px;
  padding-top: 14px;
  overflow-wrap: anywhere;
}
.analysis-panel p,
.analysis-panel li {
  font-size: 12px;
  line-height: 1.9;
}
.analysis-panel strong {
  font-size: 13px;
}
progress {
  display: block;
  width: 100%;
  height: 12px;
  margin: 15px 0;
  accent-color: #50764d;
}
details {
  padding: 8px 0;
  font-size: 12px;
}
summary {
  cursor: pointer;
}
blockquote {
  margin: 12px;
  padding: 10px;
  border-left: 3px solid #a9be90;
}
.report {
  border-top: 1px solid #ccd9c5;
  margin-top: 20px;
  padding-top: 10px;
}
.report article {
  padding: 14px;
  margin-top: 14px;
  background: #f7faf3;
}
.report ul {
  padding-left: 20px;
}
button {
  margin: 6px 0;
}
</style>
