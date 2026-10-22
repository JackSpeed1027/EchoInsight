<!-- 文件职责：保存链接与原问题，明确选择资料、确认采集和分析，恢复原任务。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { readApi, jobStatus, type Page, type ProjectSnapshot } from './projectApi'
import {
  intakeBody,
  intakeCost,
  intakePhase,
  intakeShouldPoll,
  type IntakeDraft,
  type IntakePlan,
  type IntakeView,
  type IntakeQuote,
  type IntakeSummary,
} from './analysisIntake'
import TaskControlPanel from './TaskControlPanel.vue'
import SourceCoverageNotice from './SourceCoverageNotice.vue'
const props = defineProps<{ projectId: string; initialIntakeId?: string }>()
const emit = defineEmits<{
  saved: [id: string]
  request: [snapshot: string, request: string]
  tasks: []
  advanced: []
}>()
const draft = ref<IntakeDraft>({
  url: '',
  snapshotId: '',
  instruction: '总结用户讨论的主要观点、理由和分歧',
  proposition: '',
  analysisScope: 'FULL',
})
const plan = ref<IntakePlan | null>(null),
  current = ref<IntakeView | null>(null),
  quote = ref<IntakeQuote | null>(null)
const sources = ref<Page<ProjectSnapshot> | null>(null),
  history = ref<Page<IntakeSummary> | null>(null),
  candidates = ref<Page<ProjectSnapshot> | null>(null)
const busy = ref(false),
  error = ref(''),
  notice = ref(''),
  collectAll = ref(true),
  rootLimit = ref(50),
  collectConfirmed = ref(false),
  analysisConfirmed = ref(false),
  partialConfirmed = ref(false)
let generation = 0,
  previewGeneration = 0,
  timer: ReturnType<typeof setTimeout> | undefined
let attempt: { signature: string; key: string } | null = null
/** API地址始终跟随当前项目，旧项目的迟到响应由generation拒绝。 */
function base() {
  return '/api/v1/projects/' + encodeURIComponent(props.projectId)
}
/** 所有失败保留后端安全原因；表单与已保存问题不因错误丢失。 */
function failed(caught: unknown) {
  error.value = caught instanceof Error ? caught.message : '操作失败，请重试。'
}
/** 每次修改都使旧预览失效；请求键按实际内容区分，网络重发保持原键。 */
function edited() {
  previewGeneration++
  plan.value = null
  error.value = ''
  notice.value = ''
}
/** 分页只取有限元数据，不自动选择第一份或最新资料。 */
async function loadLists(sourcePage = 1, historyPage = 1) {
  const version = generation
  try {
    const [s, h] = await Promise.all([
      readApi<Page<ProjectSnapshot>>(base() + '/snapshots?page=' + sourcePage + '&size=10'),
      readApi<Page<IntakeSummary>>(base() + '/analysis-intakes?page=' + historyPage + '&size=8'),
    ])
    if (version !== generation) return
    sources.value = s
    history.value = h
  } catch (caught) {
    if (version === generation) failed(caught)
  }
}
/** 只读解析链接、业务目标和命题，不建立任务或访问平台。 */
async function preview() {
  const version = generation,
    sequence = ++previewGeneration
  busy.value = true
  error.value = ''
  try {
    const result = await readApi<IntakePlan>(base() + '/analysis-intakes/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(intakeBody(draft.value)),
    })
    if (version === generation && sequence === previewGeneration) plan.value = result
  } catch (caught) {
    if (version === generation && sequence === previewGeneration) failed(caught)
  } finally {
    if (version === generation) busy.value = false
  }
}
/** 先保存原话；相同内容的失败重发沿用同一幂等键，不触发采集或模型。 */
async function save() {
  const signature = JSON.stringify(intakeBody(draft.value)),
    version = generation
  if (!attempt || attempt.signature !== signature) attempt = { signature, key: crypto.randomUUID() }
  busy.value = true
  error.value = ''
  try {
    const value = await readApi<IntakeView>(base() + '/analysis-intakes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': attempt.key },
      body: signature,
    })
    if (version !== generation) return
    current.value = value
    emit('saved', value.id)
    notice.value = '原问题已保存，可随时从本页历史或当前地址恢复。'
    await loadCandidates()
    await loadLists()
    schedule()
  } catch (caught) {
    if (version === generation) failed(caught)
  } finally {
    if (version === generation) busy.value = false
  }
}
/** 停止只读轮询，不改变后台任务状态。 */
function stopPolling() {
  if (timer) clearTimeout(timer)
  timer = undefined
}
/** 只在采集中读取状态；成功后停在资料确认，不自动提交后续分析。 */
function schedule() {
  stopPolling()
  if (current.value && intakeShouldPoll(current.value))
    timer = setTimeout(() => {
      void refresh()
    }, 2500)
}
/** 页面刷新恢复明确入口身份，避免将其他历史任务当作本次。 */
async function open(id: string) {
  const version = ++generation
  stopPolling()
  busy.value = true
  error.value = ''
  quote.value = null
  current.value = null
  candidates.value = null
  resetConfirmations()
  try {
    const value = await readApi<IntakeView>(base() + '/analysis-intakes/' + encodeURIComponent(id))
    if (version !== generation) return
    current.value = value
    emit('saved', id)
    await loadCandidates()
    await loadLists()
    schedule()
  } catch (caught) {
    if (version === generation) failed(caught)
  } finally {
    if (version === generation) busy.value = false
  }
}
/** 状态刷新无业务写入，网络失败停止轮询并允许手动恢复。 */
async function refresh() {
  if (!current.value) return
  const version = generation,
    id = current.value.id
  try {
    const value = await readApi<IntakeView>(base() + '/analysis-intakes/' + encodeURIComponent(id))
    if (version !== generation || current.value?.id !== id) return
    current.value = value
    error.value = ''
    schedule()
  } catch (caught) {
    if (version === generation) {
      stopPolling()
      failed(caught)
    }
  }
}
/** 候选必须来自服务端来源资格查询，BV与av映射不在浏览器猜测。 */
async function loadCandidates(page = 1) {
  if (!current.value || current.value.source || current.value.collection) return
  const version = generation,
    id = current.value.id
  try {
    const value = await readApi<Page<ProjectSnapshot>>(
      base() +
        '/analysis-intakes/' +
        encodeURIComponent(id) +
        '/candidates?page=' +
        page +
        '&size=8',
    )
    if (version === generation && current.value?.id === id) candidates.value = value
  } catch (caught) {
    if (version === generation) failed(caught)
  }
}
/** 采集和绑定均需明确动作，后端一次关联及事务校验是最终依据。 */
async function mutate(path: string, body: unknown) {
  if (!current.value) return
  const version = generation,
    id = current.value.id
  busy.value = true
  error.value = ''
  try {
    const value = await readApi<IntakeView>(
      base() + '/analysis-intakes/' + encodeURIComponent(id) + path,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      },
    )
    if (version !== generation || current.value?.id !== id) return
    current.value = value
    quote.value = null
    resetConfirmations()
    schedule()
  } catch (caught) {
    if (version === generation) failed(caught)
  } finally {
    if (version === generation) busy.value = false
  }
}
/** 每次核对重新读取原预检，金额是保守上界，尚未调用模型。 */
async function prepareAnalysis() {
  if (!current.value) return
  const version = generation,
    id = current.value.id
  busy.value = true
  error.value = ''
  quote.value = null
  analysisConfirmed.value = false
  partialConfirmed.value = false
  try {
    const value = await readApi<IntakeQuote>(
      base() + '/analysis-intakes/' + encodeURIComponent(id) + '/analysis-preview',
      { method: 'POST' },
    )
    if (version === generation && current.value?.id === id) quote.value = value
  } catch (caught) {
    if (version === generation) failed(caught)
  } finally {
    if (version === generation) busy.value = false
  }
}
/** 1、提交当前指纹与明确范围同意；2、后端接受一次；3、打开原请求进度和结果。 */
async function analyze() {
  if (!current.value || !quote.value || !analysisConfirmed.value) return
  const version = generation,
    id = current.value.id
  busy.value = true
  error.value = ''
  try {
    const value = await readApi<{ id: string; snapshotId: string }>(
      base() + '/analysis-intakes/' + encodeURIComponent(id) + '/analysis',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          quoteSha256: quote.value.sha256,
          allowPartialInput: partialConfirmed.value,
          confirmFullScope: current.value.input.analysisScope === 'FULL',
        }),
      },
    )
    if (version === generation && current.value?.id === id)
      emit('request', value.snapshotId, value.id)
  } catch (caught) {
    if (version === generation) failed(caught)
  } finally {
    if (version === generation) busy.value = false
  }
}
/** 明确同意不能从上一份资料或旧问题继承。 */
function resetConfirmations() {
  collectConfirmed.value = false
  analysisConfirmed.value = false
  partialConfirmed.value = false
}
/** 新建沿用问题副本，旧输入、资料与任务仍完整保留。 */
function newQuestion() {
  const value = current.value?.input
  if (value)
    draft.value = {
      url: value.canonicalUrl || '',
      snapshotId: value.requestedSnapshotId || '',
      instruction: value.instruction,
      proposition: value.proposition || '',
      analysisScope: value.analysisScope,
    }
  generation++
  stopPolling()
  current.value = null
  quote.value = null
  candidates.value = null
  plan.value = null
  attempt = null
  busy.value = false
  error.value = ''
  notice.value = ''
  resetConfirmations()
  emit('saved', '')
}
/** 切换项目清除旧响应资格；原问题只有点击保存才持久化。 */
watch(
  () => props.projectId,
  () => {
    generation++
    current.value = null
    sources.value = null
    history.value = null
    plan.value = null
    quote.value = null
    draft.value = {
      url: '',
      snapshotId: '',
      instruction: '总结用户讨论的主要观点、理由和分歧',
      proposition: '',
      analysisScope: 'FULL',
    }
    void loadLists()
  },
  { immediate: true },
)
watch(
  () => props.initialIntakeId,
  (id) => {
    if (id && id !== current.value?.id) void open(id)
  },
  { immediate: true },
)
onUnmounted(() => {
  generation++
  stopPolling()
})
</script>

<template>
  <section class="intake-workbench">
    <div class="section-toolbar">
      <div>
        <p class="eyebrow">智能分析</p>
        <h2>给一个链接，说出你想了解什么</h2>
      </div>
      <button class="quiet" @click="emit('advanced')">资料分析与背景设置</button>
    </div>
    <p class="muted">
      原问题贯穿采集与分析。已有评论文件可从项目资料选择；文本、字幕可从「文本与字幕」分析。
    </p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div v-if="!current" class="surface intake-form">
      <label
        >视频链接（当前支持 B站官方单视频 URL）<input
          v-model="draft.url"
          placeholder="https://www.bilibili.com/video/BV…"
          @input="edited"
          :disabled="busy"
      /></label>
      <template v-if="!draft.url.trim()"
        ><label
          >或选择项目内已保存评论资料<select
            v-model="draft.snapshotId"
            @change="edited"
            :disabled="busy"
          >
            <option value="">请选择一份明确资料</option>
            <option v-for="item in sources?.items" :key="item.snapshotId" :value="item.snapshotId">
              {{ item.title }} · {{ item.commentCount }}条 · {{ item.snapshotCreatedAt }}
            </option>
          </select></label
        >
        <div class="intake-actions" v-if="sources && sources.totalPages > 1">
          <button :disabled="busy || sources.page <= 1" @click="loadLists(sources!.page - 1)">
            上一页资料</button
          ><span>{{ sources.page }}/{{ sources.totalPages }}</span
          ><button
            :disabled="busy || sources.page >= sources.totalPages"
            @click="loadLists(sources!.page + 1)"
          >
            下一页资料
          </button>
        </div></template
      >
      <label
        >你想了解什么？<textarea
          v-model="draft.instruction"
          maxlength="1000"
          rows="4"
          @input="edited"
          :disabled="busy"
          placeholder="例如：总结主要观点和理由，并比较哪种说法更有依据"
        />
      </label>
      <label
        >评论处理范围<select v-model="draft.analysisScope" @change="edited" :disabled="busy">
          <option value="FULL">全部已保存评论</option>
          <option value="PREVIEW">有界预览，先了解讨论</option>
        </select></label
      >
      <label v-if="plan?.input.goal.needsProposition || draft.proposition"
        >支持或反对的具体命题<input
          v-model="draft.proposition"
          maxlength="300"
          @input="edited"
          :disabled="busy"
          placeholder="例如：作品结局表达了对宿命论的否定"
      /></label>
      <p v-if="plan?.input.goal.needsProposition" role="status">
        请补充判断标准，再重新核对。系统不会把个人观点直接标为正面或负面。
      </p>
      <div class="intake-actions">
        <button @click="preview" :disabled="busy">
          {{ busy ? '正在处理…' : '核对链接与问题' }}</button
        ><button
          v-if="plan && !plan.input.goal.needsProposition"
          class="primary"
          @click="save"
          :disabled="busy"
        >
          保存问题并继续
        </button>
      </div>
      <p class="muted">只有点击保存才保留本次输入。核对与保存不会采集，也不会调用模型。</p>
    </div>
    <template v-else>
      <div class="surface intake-form">
        <div class="section-toolbar">
          <strong>{{ intakePhase(current.phase) }}</strong
          ><button :disabled="busy" @click="refresh">刷新状态</button>
        </div>
        <blockquote>{{ current.input.instruction }}</blockquote>
        <p v-if="current.input.proposition">判断命题：{{ current.input.proposition }}</p>
        <p v-if="current.input.canonicalUrl" class="intake-url">{{ current.input.canonicalUrl }}</p>
        <p>
          处理范围：{{ current.input.analysisScope === 'FULL' ? '全部已保存评论' : '有界预览' }}
        </p>
        <button class="quiet" @click="newQuestion" :disabled="busy">沿用问题，另建一次</button>
      </div>
      <div v-if="current.phase === 'NEEDS_SOURCE'" class="surface intake-form">
        <h3>选择已有版本，或重新采集</h3>
        <p class="muted">不会自动替你选择最新版本。选择已有资料可以直接核对分析范围。</p>
        <div v-for="item in candidates?.items" :key="item.snapshotId" class="intake-candidate">
          <div>
            <strong>{{ item.title }}</strong
            ><small>{{ item.commentCount }}条评论 · {{ item.snapshotCreatedAt }}</small>
          </div>
          <button :disabled="busy" @click="mutate('/source', { snapshotId: item.snapshotId })">
            使用这份资料
          </button>
        </div>
        <p v-if="candidates && !candidates.total">项目内没有能确认属于此视频的资料。</p>
        <div v-if="candidates && candidates.totalPages > 1" class="intake-actions">
          <button
            :disabled="busy || candidates.page <= 1"
            @click="loadCandidates(candidates!.page - 1)"
          >
            上一页</button
          ><span>{{ candidates.page }}/{{ candidates.totalPages }}</span
          ><button
            :disabled="busy || candidates.page >= candidates.totalPages"
            @click="loadCandidates(candidates!.page + 1)"
          >
            下一页
          </button>
        </div>
        <template v-if="current.input.canonicalUrl"
          ><h3>采集这条视频的评论</h3>
          <label class="intake-check"
            ><input
              type="checkbox"
              v-model="collectAll"
              :disabled="busy"
            />遍历当前可见评论（仍受平台可见性与系统容量限制）</label
          ><label v-if="!collectAll"
            >根评论数量（1～200）<input
              type="number"
              v-model.number="rootLimit"
              min="1"
              max="200"
              :disabled="busy"
          /></label>
          <p v-if="!current.collectionEnabled">本机尚未启用 B站采集，可先导入已有文件。</p>
          <p v-else-if="!current.collectionWorkerRunning">
            采集执行器当前关闭，确认后会排队；问题会保留。
          </p>
          <label class="intake-check"
            ><input
              type="checkbox"
              v-model="collectConfirmed"
              :disabled="busy"
            />确认创建本次采集任务，完成后再核对资料与分析费用。</label
          ><button
            class="primary"
            :disabled="busy || !collectConfirmed || !current.collectionEnabled"
            @click="
              mutate('/collection', {
                collectAll,
                rootLimit: collectAll ? null : rootLimit,
                confirmed: true,
              })
            "
          >
            开始采集并保留问题
          </button></template
        >
      </div>
      <div v-if="current.collection" class="surface intake-form">
        <h3>原采集任务</h3>
        <p v-if="!current.collectionWorkerRunning && current.phase === 'COLLECTING'" role="status">
          采集执行器当前关闭，原任务正在排队；问题已保存，不会自动开始模型分析。
        </p>
        <TaskControlPanel
          :project-id="projectId"
          kind="COLLECTION"
          :task-id="current.collection.id"
          @changed="refresh"
        /><button class="quiet" @click="emit('tasks')">打开任务中心</button
        ><button
          v-if="current.phase === 'READY_TO_BIND'"
          class="primary"
          :disabled="busy"
          @click="mutate('/source', {})"
        >
          确认使用本次采集资料
        </button>
      </div>
      <div v-if="current.source" class="surface intake-form">
        <h3>本次固定资料</h3>
        <strong>{{ current.source.title }}</strong>
        <p>{{ current.source.commentCount }}条评论 · {{ current.source.snapshotCreatedAt }}</p>
        <p class="muted">
          后续新增资料不会自动扩大本次范围。字幕、参考和增量对照可从「资料分析与背景设置」选择。
        </p>
        <button v-if="!current.analysis" @click="prepareAnalysis" :disabled="busy">
          核对分析范围与费用
        </button>
      </div>
      <div v-if="quote && !current.analysis" class="surface intake-form">
        <h3>分析前核对</h3>
        <SourceCoverageNotice :view="{ frozen: false, coverage: quote.preview.sourceCoverage }" />
        <p>
          已保存 {{ quote.preview.savedComments }}条，本次选择
          {{ quote.preview.selectedComments }}条，遗漏 {{ quote.preview.omittedComments }}条。
        </p>
        <p v-if="quote.cost.mode !== 'MODEL'">当前为结构演示，不产生语义结论，也不调用真实模型。</p>
        <p v-else>
          本地保守费用上界 {{ intakeCost(quote.cost.maximumUnits, quote.cost.unit) }}；任务预算
          {{ intakeCost(quote.cost.budgetLimitUnits, quote.cost.unit) }}。该金额不是供应商账单。
        </p>
        <p>计划处理节点 {{ quote.cost.plannedCalls }}个。全部已保存评论不代表平台所有评论。</p>
        <p v-if="!quote.available" class="error">
          当前范围不能完整执行：{{
            quote.preview.fullScope.reasonCode
          }}。请沿用问题另建预览，或调整资料后重试。
        </p>
        <ul>
          <li v-for="note in quote.preview.notes" :key="note">{{ note }}</li>
        </ul>
        <label v-if="quote.preview.requiresPartialConsent" class="intake-check"
          ><input
            type="checkbox"
            v-model="partialConfirmed"
          />我了解本次存在裁剪或缺失语境，同意按已显示范围处理。</label
        ><label class="intake-check"
          ><input
            type="checkbox"
            v-model="analysisConfirmed"
          />确认当前资料、问题、处理范围及费用上界。</label
        ><button
          class="primary"
          :disabled="
            busy ||
            !quote.available ||
            !analysisConfirmed ||
            (quote.preview.requiresPartialConsent && !partialConfirmed)
          "
          @click="analyze"
        >
          确认并开始分析
        </button>
      </div>
      <div v-if="current.analysis" class="surface intake-form">
        <h3>{{ jobStatus(current.analysis.status) }}</h3>
        <p>本次已关联原分析请求，刷新不会另建任务。</p>
        <button
          class="primary"
          @click="emit('request', current.analysis.snapshotId, current.analysis.id)"
        >
          打开原分析进度与结果
        </button>
      </div>
      <details class="surface">
        <summary>当前入口范围说明</summary>
        <ul>
          <li v-for="note in current.notes" :key="note">{{ note }}</li>
        </ul>
      </details>
    </template>
    <details class="surface intake-history">
      <summary>已保存的问题（{{ history?.total || 0 }}）</summary>
      <div v-for="item in history?.items" :key="item.id" class="intake-candidate">
        <div>
          <strong>{{ item.instruction }}</strong
          ><small>{{ item.createdAt }}</small>
        </div>
        <button @click="open(item.id)" :disabled="busy">恢复</button>
      </div>
      <div v-if="history && history.totalPages > 1" class="intake-actions">
        <button
          :disabled="busy || history.page <= 1"
          @click="loadLists(sources?.page || 1, history!.page - 1)"
        >
          上一页问题</button
        ><span>{{ history.page }}/{{ history.totalPages }}</span
        ><button
          :disabled="busy || history.page >= history.totalPages"
          @click="loadLists(sources?.page || 1, history!.page + 1)"
        >
          下一页问题
        </button>
      </div>
    </details>
  </section>
</template>

<style scoped>
.intake-workbench {
  display: grid;
  gap: 18px;
  max-width: 1100px;
}
.intake-form {
  display: grid;
  gap: 14px;
  padding: 24px;
}
.intake-form label {
  display: grid;
  gap: 8px;
  font-weight: 600;
}
.intake-form input,
.intake-form select,
.intake-form textarea {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
}
.intake-form .intake-check {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  font-weight: 400;
}
.intake-check input {
  width: auto;
  flex: 0 0 auto;
  margin-top: 4px;
}
.intake-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.intake-candidate {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 0;
  border-bottom: 1px solid var(--border, #e4e7ec);
}
.intake-candidate div {
  min-width: 0;
  overflow-wrap: anywhere;
}
.intake-candidate small {
  display: block;
  color: var(--muted, #687386);
  margin-top: 6px;
}
.intake-candidate button {
  flex-shrink: 0;
}
.intake-url,
blockquote {
  overflow-wrap: anywhere;
}
blockquote {
  margin: 0;
  border-left: 3px solid #6a78d1;
  padding: 10px 16px;
  white-space: pre-wrap;
  background: #f5f6fc;
}
.intake-history {
  padding: 20px 24px;
}
@media (max-width: 600px) {
  .intake-form {
    padding: 16px;
  }
  .intake-candidate {
    align-items: flex-start;
    flex-direction: column;
  }
  .section-toolbar {
    align-items: flex-start;
    flex-wrap: wrap;
  }
}
</style>
