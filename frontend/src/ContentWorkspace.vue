<!-- 文件职责：无评论文本/字幕的版本库、自然语言提问、范围确认及证据回看；复用原任务控制。 -->
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { readApi, type Page } from './projectApi'
import { budgetText, taskState } from './taskCenter'
import {
  contentRange,
  contentProgressText,
  contentLocation,
  type ContentMaterial,
  type ContentSegment,
  type ContentPreview,
  type ContentAnalysis,
} from './contentTypes'
import { describeFailure } from './publicFailures'
import TaskControlPanel from './TaskControlPanel.vue'
import ExecutionDetails from './ExecutionDetails.vue'
const props = defineProps<{ projectId: string; initialJobId?: string }>()
const emit = defineEmits<{ updated: [] }>()
const materials = ref<Page<ContentMaterial> | null>(null),
  materialPage = ref(1)
const selected = ref<ContentMaterial | null>(null),
  parent = ref<ContentMaterial | null>(null)
const title = ref(''),
  source = ref(''),
  file = ref<File | null>(null),
  uploading = ref(false)
const coverageMode = ref<'FULL' | 'PREFIX'>('FULL')
const question = ref('总结这份材料的主要内容，给出原文依据，并说明信息不足之处')
const from = ref<string | number>(''),
  to = ref<string | number>(''),
  consent = ref(false),
  preview = ref<ContentPreview | null>(null)
const active = ref<ContentAnalysis | null>(null),
  busy = ref(false),
  loading = ref(false),
  error = ref(''),
  questionError = ref(''),
  notice = ref('')
const raw = ref<Page<ContentSegment> | null>(null),
  rawPage = ref(1),
  search = ref('')
const history = ref<Page<{
  jobId: string
  materialId: string
  title: string
  instruction: string
  status: string
  createdAt: string
  coverageMode?: string
}> | null>(null)
const historyPage = ref(1)
let generation = 0,
  rawGeneration = 0,
  previewGeneration = 0,
  timer: number | undefined
let submission: { signature: string; key: string } | undefined,
  uploadAttempt: { signature: string; key: string } | undefined
const timed = computed(() => ['SRT', 'VTT'].includes(selected.value?.format || ''))
/** 所有请求限定本项目；不把文件路径、供应商或费用配置发送给页面。 */
function base() {
  return `/api/v1/projects/${encodeURIComponent(props.projectId)}`
}
/** 页面卸载/换项目后响应只可丢弃，不覆盖另一份材料或任务。 */
function stop() {
  if (timer !== undefined) window.clearTimeout(timer)
  timer = undefined
}
/** 只读刷新摘要及有限问题历史；不会因进入页面自动创建分析。 */
async function load() {
  const version = ++generation
  rawGeneration++
  previewGeneration++
  stop()
  loading.value = true
  error.value = ''
  questionError.value = ''
  active.value = null
  preview.value = null
  selected.value = null
  raw.value = null
  busy.value = false
  uploading.value = false
  parent.value = null
  from.value = ''
  to.value = ''
  consent.value = false
  submission = undefined
  uploadAttempt = undefined
  try {
    const [list, recent] = await Promise.all([
      readApi<Page<ContentMaterial>>(
        `${base()}/content-materials?page=${materialPage.value}&size=12`,
      ),
      readApi<typeof history.value>(`${base()}/content-analyses?page=${historyPage.value}&size=10`),
    ])
    if (version !== generation) return
    materials.value = list
    history.value = recent
    if (props.initialJobId) await open(props.initialJobId)
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '内容材料读取失败。'
  } finally {
    if (version === generation) loading.value = false
  }
}
/** 文件选择只保存在本页，上传才发送；修改后重新构造幂等请求。 */
function chooseFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] || null
  uploadAttempt = undefined
}
/** 用户明确选择父版本，上传修订会另存，原报告仍绑定旧版本。 */
function revise(item: ContentMaterial) {
  parent.value = item
  title.value = item.title
  source.value = item.sourceDescription
  notice.value = `下一次上传将作为“${item.title}”的修订，旧版本保留。`
}
/** 1、验证文件；2、复用相同上传重发键；3、追加版本；4、刷新列表并选择新材料，绝不自动分析。 */
async function upload() {
  if (uploading.value) return
  if (!file.value || !title.value.trim()) {
    error.value = '请选择文件并填写标题。'
    return
  }
  const version = generation
  uploading.value = true
  error.value = ''
  notice.value = ''
  try {
    const signature = JSON.stringify([
      file.value.name,
      file.value.size,
      file.value.lastModified,
      title.value.trim(),
      source.value.trim(),
      parent.value?.id || null,
    ])
    if (uploadAttempt?.signature !== signature)
      uploadAttempt = { signature, key: crypto.randomUUID() }
    const form = new FormData()
    form.set('file', file.value)
    form.set('title', title.value.trim())
    form.set('sourceDescription', source.value.trim())
    if (parent.value) form.set('previousMaterialId', parent.value.id)
    const result = await readApi<{ summary: ContentMaterial }>(`${base()}/content-materials`, {
      method: 'POST',
      headers: { 'Idempotency-Key': uploadAttempt.key },
      body: form,
    })
    if (version !== generation) return
    materialPage.value = 1
    materials.value = await readApi<Page<ContentMaterial>>(
      `${base()}/content-materials?page=1&size=12`,
    )
    if (version !== generation) return
    parent.value = null
    notice.value = '材料已另存，尚未执行分析。'
    emit('updated')
    await choose(result.summary)
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '上传失败，可保留原表单重试。'
  } finally {
    if (version === generation) uploading.value = false
  }
}
/** 明确选择材料后清除其他任务的临时表单范围，不更改服务端历史。 */
async function choose(item: ContentMaterial) {
  stop()
  selected.value = item
  active.value = null
  from.value = ''
  to.value = ''
  search.value = ''
  rawPage.value = 1
  invalidate()
  await loadRaw()
}
/** 只读分页/子串查找原文，原文直接转义展示；不能将它当语义检索结果。 */
async function loadRaw() {
  if (!selected.value) return
  const version = ++rawGeneration,
    id = selected.value.id,
    projectVersion = generation
  try {
    const params = new URLSearchParams({
      page: String(rawPage.value),
      size: '10',
      query: search.value.trim(),
    })
    const result = await readApi<Page<ContentSegment>>(
      `${base()}/content-materials/${encodeURIComponent(id)}/segments?${params}`,
    )
    if (version === rawGeneration && projectVersion === generation && selected.value?.id === id)
      raw.value = result
  } catch (caught) {
    if (version === rawGeneration && projectVersion === generation)
      error.value = caught instanceof Error ? caught.message : '原文读取失败。'
  }
}
/** 表单变更使旧范围同意失效，不能拿另一个问题的预检结果提交。 */
function invalidate() {
  questionError.value = ''
  preview.value = null
  consent.value = false
  previewGeneration++
}
/** 用户提供秒数只转换业务范围；后端用毫秒和原段重新冻结。 */
function body() {
  if (!selected.value) throw new Error('请先选择一份材料。')
  return {
    materialId: selected.value.id,
    instruction: question.value.trim(),
    ...contentRange(from.value, to.value),
    allowPartialInput: consent.value,
    coverageMode: coverageMode.value,
  }
}
/** 只读预检，清楚显示范围、遗漏和费用上界，没有模型调用。 */
async function inspect() {
  const version = ++previewGeneration,
    projectVersion = generation
  questionError.value = ''
  try {
    const result = await readApi<ContentPreview>(`${base()}/content-analyses/preview`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body()),
    })
    if (version !== previewGeneration || projectVersion !== generation) return false
    preview.value = result
    return true
  } catch (caught) {
    if (version === previewGeneration && projectVersion === generation)
      questionError.value = caught instanceof Error ? caught.message : '范围核对失败。'
    return false
  }
}
/** 先重新预检，再由服务器原子接受；同一表单重发复用原任务，改变输入生成新键。 */
async function submit() {
  if (busy.value) return
  busy.value = true
  const version = generation
  try {
    if (!(await inspect())) return
    if (preview.value?.requiresPartialConsent && !consent.value) {
      questionError.value = '请缩小范围，或明确同意仅处理当前选入原文。'
      return
    }
    const data = body(),
      signature = JSON.stringify(data)
    if (submission?.signature !== signature) submission = { signature, key: crypto.randomUUID() }
    const result = await readApi<ContentAnalysis>(`${base()}/content-analyses`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': submission.key },
      body: signature,
    })
    if (version !== generation) return
    active.value = result
    emit('updated')
    await refreshCurrent()
    await loadHistory()
  } catch (caught) {
    if (version === generation)
      questionError.value = caught instanceof Error ? caught.message : '分析未接受。'
  } finally {
    if (version === generation) busy.value = false
  }
}
/** 轮询原任务，成功/失败/取消均停止；关闭页面不会取消服务器任务。 */
async function refreshCurrent() {
  if (!active.value) return
  stop()
  const id = active.value.jobId,
    version = generation
  try {
    const value = await readApi<ContentAnalysis>(
      `${base()}/content-analyses/${encodeURIComponent(id)}`,
    )
    if (version !== generation || active.value?.jobId !== id) return
    active.value = value
    // 当前任务与历史卡片同步同一次响应，避免已完成报告旁仍显示等待执行。
    if (history.value)
      history.value.items = history.value.items.map((item) =>
        item.jobId === id ? { ...item, status: value.status } : item,
      )
    if (['PENDING', 'RUNNING'].includes(value.status))
      timer = window.setTimeout(() => void refreshCurrent(), 1200)
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '任务状态读取失败。'
  }
}
/** 打开历史只恢复明确任务与其冻结材料，不创建新请求。 */
async function open(id: string) {
  stop()
  const version = generation
  try {
    const value = await readApi<ContentAnalysis>(
      `${base()}/content-analyses/${encodeURIComponent(id)}`,
    )
    if (version !== generation) return
    active.value = value
    selected.value = value.input.material
    rawPage.value = 1
    search.value = ''
    invalidate()
    await loadRaw()
    await refreshCurrent()
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '历史任务读取失败。'
  }
}
/** 读取独立内容历史，不把它混进旧评论结果列表。 */
async function loadHistory() {
  const version = generation
  const page = await readApi<typeof history.value>(
    `${base()}/content-analyses?page=${historyPage.value}&size=10`,
  )
  if (version === generation) history.value = page
}
/** 材料列表翻页仍保留明确选中的版本，避免后台自动替换分析对象。 */
async function materialTurn(next: number) {
  materialPage.value = next
  const version = generation
  try {
    const page = await readApi<Page<ContentMaterial>>(
      `${base()}/content-materials?page=${next}&size=12`,
    )
    if (version === generation) materials.value = page
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '材料翻页失败。'
  }
}
/** 原文翻页不改变已冻结输入或报告。 */
function rawTurn(next: number) {
  rawPage.value = next
  void loadRaw()
}
/** 历史翻页失败明确保留提示，不让未处理的异步异常丢失。 */
async function historyTurn(next: number) {
  historyPage.value = next
  try {
    await loadHistory()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '历史翻页失败。'
  }
}
watch(
  () => [props.projectId, props.initialJobId],
  () => {
    materialPage.value = 1
    historyPage.value = 1
    void load()
  },
  { immediate: true },
)
onUnmounted(() => {
  generation++
  rawGeneration++
  previewGeneration++
  stop()
})
</script>

<template>
  <section class="content-workspace">
    <div class="section-toolbar">
      <div>
        <h2>内容材料与提问</h2>
        <p>没有评论也可以使用。上传文本或字幕，围绕原文提问，保留版本与证据。</p>
      </div>
    </div>
    <p class="scope-copy">
      目前支持UTF8 TXT、MD、SRT、VTT；没有视频画面、音频或自动转写。资料来源说明是用户声明。
    </p>
    <p v-if="loading" role="status">正在读取内容材料…</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <details class="surface content-upload" :open="!materials?.total">
      <summary>上传内容材料或追加修订</summary>
      <form @submit.prevent="upload">
        <label>材料标题<input v-model="title" maxlength="120" required /></label>
        <label
          >来源说明<input
            v-model="source"
            maxlength="200"
            placeholder="例如：自行整理的讲稿、授权字幕"
        /></label>
        <label
          >本机文件（最多2MiB）<input type="file" accept=".txt,.md,.srt,.vtt" @change="chooseFile"
        /></label>
        <p v-if="parent">
          父版本：{{ parent.title }}（第{{ parent.versionDepth }}层）<button
            type="button"
            class="quiet"
            @click="parent = null"
          >
            改为独立材料
          </button>
        </p>
        <p v-else>将保存为新的独立材料；修订已有材料可先在卡片点“上传此版修订”。</p>
        <button class="primary" :disabled="uploading">
          {{ uploading ? '正在保存…' : '保存新材料版本' }}
        </button>
      </form>
    </details>
    <div class="material-grid">
      <article v-for="item in materials?.items" :key="item.id" class="material-card">
        <span class="source-badge">{{ item.format }} · 版本深度{{ item.versionDepth }}</span>
        <h3>{{ item.title }}</h3>
        <p>{{ item.segmentCount }}段 · {{ item.textChars }}正文字符</p>
        <p class="small">{{ item.createdAt }} · {{ item.sourceDescription || '来源未说明' }}</p>
        <div class="material-actions">
          <button class="primary" @click="choose(item)">选择并提问</button
          ><button class="quiet" @click="revise(item)">上传此版修订</button>
        </div>
      </article>
    </div>
    <div v-if="materials && materials.totalPages > 1" class="pagination">
      <button :disabled="materialPage <= 1" @click="materialTurn(materialPage - 1)">
        上一材料页</button
      ><span>{{ materialPage }} / {{ materials.totalPages }}</span
      ><button
        :disabled="materialPage >= materials.totalPages"
        @click="materialTurn(materialPage + 1)"
      >
        下一材料页
      </button>
    </div>
    <section v-if="selected" class="surface content-question">
      <h3>向“{{ selected.title }}”提问</h3>
      <p>
        {{ selected.format }} ·
        {{ selected.segmentCount }}段，原材料版本固定。选择新版本不会改写旧任务。
      </p>
      <label
        >你想了解什么<textarea v-model="question" maxlength="1000" rows="3" @input="invalidate" />
      </label>
      <label
        >处理范围
        <select v-model="coverageMode" @change="invalidate">
          <option value="FULL">完整处理所选范围</option>
          <option value="PREFIX">仅分析前部（有限预览）</option>
        </select>
      </label>
      <div v-if="timed" class="content-range">
        <label
          >字幕起点（秒，可空）<input
            v-model="from"
            type="number"
            min="0"
            step="0.001"
            @input="invalidate" /></label
        ><label
          >字幕终点（秒，可空）<input
            v-model="to"
            type="number"
            min="0"
            step="0.001"
            @input="invalidate"
        /></label>
      </div>
      <p class="small" v-if="coverageMode === 'FULL'">
        完整方式按原段分片后汇总，最多24片，每片100段/12000字符；字幕起止时间限定本次范围。超出容量或预算会明确拒绝。
      </p>
      <p class="small" v-else>
        有限预览最多40完整段/12000字符。只处理前部，不将材料前缀当全篇理解。
      </p>
      <p v-if="questionError" role="alert" class="error">{{ questionError }}</p>
      <button type="button" class="quiet" :disabled="busy" @click="inspect">先核对处理范围</button>
      <div v-if="preview" class="content-preview">
        <p>
          材料共{{ preview.input.selection.totalSegments }}段，所选范围{{
            preview.input.selection.eligibleSegments
          }}段；本次选入{{ preview.input.selection.segments.length }}段 /
          {{ preview.input.selection.selectedChars }}字符，范围内尚有{{
            preview.input.selection.omittedSegments
          }}段未选入。
        </p>
        <p>
          {{
            preview.sourceMode === 'MOCK' ? '免费结构演示，不生成语义结论' : '将执行模型内容分析'
          }}
          · 本任务计划{{ preview.plannedCalls }}次调用，全部调用费用上界{{
            budgetText(preview.reservationUnits, preview.budgetUnit)
          }}
          <span v-if="preview.input.fullPlan"
            >（{{ preview.input.fullPlan.chunks.length }}次分片＋1次汇总；免费模式不出站）</span
          >
        </p>
        <p v-if="!preview.budgetSufficient" class="error">预算不足，不能提交。</p>
        <label v-if="preview.requiresPartialConsent"
          ><input
            v-model="consent"
            type="checkbox"
          />我知道这不是全篇分析，同意仅处理当前选入原文。</label
        >
      </div>
      <button type="button" class="primary" :disabled="busy" @click="submit">
        {{ busy ? '正在接受…' : '提交这个问题' }}
      </button>
      <details class="content-raw">
        <summary>回看材料原文与定位</summary>
        <form @submit.prevent="rawTurn(1)">
          <label>查找原文（子串，最多80字）<input v-model="search" maxlength="80" /></label
          ><button type="submit">查找原文</button>
        </form>
        <article v-for="segment in raw?.items" :key="segment.id">
          <strong>{{ contentLocation(segment) }}</strong>
          <p class="source-text">{{ segment.text }}</p>
        </article>
        <div v-if="raw && raw.totalPages > 1" class="pagination">
          <button :disabled="rawPage <= 1" @click="rawTurn(rawPage - 1)">上一原文页</button
          ><span>{{ rawPage }} / {{ raw.totalPages }}</span
          ><button :disabled="rawPage >= raw.totalPages" @click="rawTurn(rawPage + 1)">
            下一原文页
          </button>
        </div>
        <p v-if="raw && !raw.total">未找到包含该原文的段落，不代表语义上没有相关内容。</p>
      </details>
    </section>
    <section v-if="active" class="surface content-result">
      <h3>
        本次内容问题 · {{ active.input.fullPlan ? '完整范围' : '有限预览' }} ·
        {{ taskState(active.status) }}
      </h3>
      <p>
        <strong>{{ active.instruction }}</strong>
      </p>
      <p>
        绑定“{{ active.input.material.title }}”，选入{{
          active.input.selection.segments.length
        }}段，未选入{{ active.input.selection.omittedSegments }}段。
      </p>
      <p v-if="active.status === 'FAILED'" class="error">
        {{ describeFailure(active.errorCode).message }}
        {{ describeFailure(active.errorCode).action }}
      </p>
      <p v-if="active.progress" role="status">{{ contentProgressText(active.progress) }}</p>
      <button type="button" class="quiet" @click="refreshCurrent">重新查询状态</button>
      <TaskControlPanel
        v-if="active.status !== 'SUCCESS'"
        :project-id="projectId"
        kind="ANALYSIS"
        :task-id="active.jobId"
        @changed="refreshCurrent"
      />
      <details>
        <summary>查看费用与执行记录</summary>
        <ExecutionDetails :job-id="active.jobId" :status="active.status" />
      </details>
      <template v-if="active.artifact">
        <p v-if="active.artifact.sourceMode === 'STRUCTURAL_DEMO'" class="scope-copy">
          这是结构演示，只展示材料和范围，尚未执行语义分析。
        </p>
        <article
          v-for="(finding, index) in active.artifact.findings"
          :key="index"
          class="content-finding"
        >
          <h4>{{ finding.title }}</h4>
          <p class="small">
            {{
              finding.kind === 'OBSERVATION'
                ? '材料内归纳'
                : finding.kind === 'INTERPRETATION'
                  ? '条件性解释'
                  : '仍缺上下文'
            }}
          </p>
          <p class="source-text">{{ finding.explanation }}</p>
          <details>
            <summary>查看这项结论的原文依据（{{ finding.evidenceIds.length }}段）</summary>
            <blockquote v-for="id in finding.evidenceIds" :key="id">
              <template
                v-for="segment in active.artifact.evidence.filter((item) => item.id === id)"
                :key="segment.id"
                ><strong>{{ contentLocation(segment) }}</strong>
                <p class="source-text">{{ segment.text }}</p></template
              >
            </blockquote>
          </details>
        </article>
        <details v-if="active.artifact.processing?.chunks.length">
          <summary>查看全部分段归纳（{{ active.artifact.processing.chunks.length }}片）</summary>
          <p class="small">这是逐片生成的中间摘要，可能有遗漏或误读，不能替代原文。</p>
          <article
            v-for="chunk in active.artifact.processing.chunks"
            :key="chunk.index"
            class="content-finding"
          >
            <h4>第{{ chunk.index + 1 }}片</h4>
            <div v-for="(finding, index) in chunk.findings" :key="index">
              <strong>{{ finding.title }}</strong>
              <p class="source-text">{{ finding.explanation }}</p>
              <details>
                <summary>回看该片引用的原文</summary>
                <blockquote v-for="id in finding.evidenceIds" :key="id">
                  <template
                    v-for="segment in active.artifact.evidence.filter((item) => item.id === id)"
                    :key="segment.id"
                  >
                    <strong>{{ contentLocation(segment) }}</strong>
                    <p class="source-text">{{ segment.text }}</p>
                  </template>
                </blockquote>
              </details>
            </div>
            <ul>
              <li v-for="note in chunk.uncertainties" :key="note">{{ note }}</li>
            </ul>
          </article>
        </details>
        <h4>仍需核对的信息</h4>
        <ul>
          <li v-for="note in active.artifact.uncertainties" :key="note">{{ note }}</li>
        </ul>
        <details>
          <summary>本次纳入的原文及边界</summary>
          <ul>
            <li v-for="note in active.artifact.limitations" :key="note">{{ note }}</li>
          </ul>
          <article v-for="segment in active.artifact.evidence" :key="segment.id">
            <strong>{{ contentLocation(segment) }}</strong>
            <p class="source-text">{{ segment.text }}</p>
          </article>
        </details>
      </template>
    </section>
    <section v-if="history?.total" class="surface">
      <h3>内容问题历史</h3>
      <div class="content-history">
        <button
          v-for="item in history.items"
          :key="item.jobId"
          class="quiet"
          @click="open(item.jobId)"
        >
          {{ item.instruction }} · {{ taskState(item.status)
          }}<small
            >{{ item.coverageMode === 'FULL' ? '完整范围' : '有限预览' }} · {{ item.title }} ·
            {{ item.createdAt }}</small
          >
        </button>
      </div>
      <div v-if="history.totalPages > 1" class="pagination">
        <button :disabled="historyPage <= 1" @click="historyTurn(historyPage - 1)">
          上一历史页</button
        ><span>{{ historyPage }} / {{ history.totalPages }}</span
        ><button
          :disabled="historyPage >= history.totalPages"
          @click="historyTurn(historyPage + 1)"
        >
          下一历史页
        </button>
      </div>
    </section>
  </section>
</template>

<style scoped>
.content-workspace {
  display: grid;
  gap: 1rem;
}
.content-upload form,
.content-question {
  display: grid;
  gap: 1rem;
}
.content-workspace label {
  display: grid;
  gap: 0.45rem;
}
.content-workspace label:has(input[type='checkbox']) {
  display: flex;
  align-items: center;
}
.content-upload summary {
  cursor: pointer;
  font-weight: 600;
  margin-bottom: 1rem;
}
.content-range {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1rem;
}
.content-preview {
  background: #f0f5fa;
  padding: 1rem;
  border-radius: 10px;
}
.source-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.content-raw article,
.content-result article {
  padding: 1rem 0;
  border-top: 1px solid #e2e8f0;
}
.content-raw form {
  display: flex;
  align-items: end;
  gap: 1rem;
  margin: 1rem 0;
}
.content-history {
  display: grid;
  gap: 0.7rem;
}
.content-history button {
  text-align: left;
  white-space: normal;
}
.content-history small {
  display: block;
  margin-top: 0.4rem;
}
.content-finding blockquote {
  margin: 1rem 0;
  padding: 1rem;
  background: #f5f8fb;
  border-left: 3px solid #8ba4b8;
}
</style>
