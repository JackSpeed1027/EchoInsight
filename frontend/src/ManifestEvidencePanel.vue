<!-- 文件职责：展示已结束集合运行的跨作品证据对照，保持原文及各自结论来源清晰。 -->
<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { coverageName, readApi, evidenceSource } from './projectApi'
const props = defineProps<{ projectId: string; runId: string }>()
type Report = {
  dependencySha256: string
  status: string
  succeeded: number
  failed: number
  limitations: string[]
  topics: Array<{
    question: string
    workCount: number
    sources: Array<{
      requestId: string
      snapshotId: string
      title: string
      sourceMode: string
      sourceCompleteness: string | null
      scope: { totalComments: number; selectedComments: number | null; truncated: boolean }
      issue: { id: string; summary: string }
      claims: Array<{ id: string; statement: string }>
      assessments: Array<{ issueId: string; conclusion: string; reason: string }>
      evidence: Array<{
        id: string
        text: string
        sourceRole?: string | null
        title?: string | null
        startMs?: number | null
        endMs?: number | null
      }>
    }>
  }>
}
const report = ref<Report | null>(null)
const error = ref('')
const busy = ref(false)
let alive = true
/** 用户主动展开才读取对照，不会新建模型任务；失败不伪造空报告。 */
async function load() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await readApi<Report>(
      `/api/v1/projects/${props.projectId}/manifest-analyses/${props.runId}/evidence-report`,
    )
    if (alive) report.value = result
  } catch (caught) {
    if (alive) error.value = caught instanceof Error ? caught.message : '对照读取失败。'
  } finally {
    if (alive) busy.value = false
  }
}
onUnmounted(() => {
  alive = false
})
</script>
<template>
  <section class="evidence-report surface">
    <div class="section-toolbar">
      <div>
        <h3>跨作品议题与证据对照</h3>
        <p class="small">按相同问题并列原结果，便于复核；不会自动宣布跨作品共识或赢家。</p>
      </div>
      <button :disabled="busy" @click="load">
        {{ busy ? '读取中…' : report ? '刷新对照' : '打开证据对照' }}
      </button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <template v-if="report">
      <p class="scope-copy">
        成功作品 {{ report.succeeded }} · 失败作品
        {{ report.failed }}。相同问题的不同措辞尚不会自动合并。
      </p>
      <a
        class="back-link"
        :href="`/api/v1/projects/${projectId}/manifest-analyses/${runId}/evidence-report/export`"
        download="echoinsight-evidence-report.json"
        >下载当前证据对照 JSON</a
      >
      <article v-for="(topic, index) in report.topics" :key="index" class="issue-card">
        <h4>
          {{ topic.question }} <small>· {{ topic.workCount }} 个作品</small>
        </h4>
        <div class="evidence-sources">
          <section
            v-for="source in topic.sources"
            :key="source.requestId + ':' + source.issue.id"
            class="evidence-source"
          >
            <strong>{{ source.title }}</strong>
            <p class="scope-copy">
              {{ source.sourceMode === 'MODEL' ? '模型分析' : '结构演示 · 非语义结论' }} ·
              {{ coverageName(source.sourceCompleteness) }} · 快照
              {{ source.scope.totalComments }} 条<span v-if="source.scope.selectedComments != null">
                · 模型输入 {{ source.scope.selectedComments }} 条</span
              >
            </p>
            <p>{{ source.issue.summary }}</p>
            <p v-for="claim in source.claims" :key="claim.id">观点：{{ claim.statement }}</p>
            <div
              v-for="assessment in source.assessments"
              :key="assessment.issueId"
              class="assessment-box"
            >
              <strong>{{ assessment.conclusion }}</strong>
              <p>{{ assessment.reason }}</p>
            </div>
            <details>
              <summary>查看该作品原文依据</summary>
              <blockquote v-for="quote in source.evidence" :key="quote.id">
                <small>{{ evidenceSource(quote) }}</small>
                <p>{{ quote.text }}</p>
              </blockquote>
            </details>
            <a
              class="back-link"
              :href="`?project=${encodeURIComponent(projectId)}&tab=insight&snapshot=${encodeURIComponent(source.snapshotId)}&request=${encodeURIComponent(source.requestId)}`"
              >打开完整分析与核验记录</a
            >
          </section>
        </div>
      </article>
      <p v-if="!report.topics.length">当前没有成功议题可供对照，请检查失败作品。</p>
      <details>
        <summary>范围、规则与依赖身份</summary>
        <p v-for="note in report.limitations" :key="note" class="small">{{ note }}</p>
        <code class="dependency">{{ report.dependencySha256 }}</code>
      </details>
    </template>
  </section>
</template>
<style scoped>
.evidence-report {
  margin-top: 1.25rem;
}
.evidence-sources {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 300px), 1fr));
  gap: 1rem;
}
.evidence-source {
  padding: 1rem;
  background: #f5f7f5;
  border-radius: 10px;
  overflow-wrap: anywhere;
}
.dependency {
  overflow-wrap: anywhere;
}
</style>
