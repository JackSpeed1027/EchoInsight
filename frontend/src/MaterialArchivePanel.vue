<!-- 文件职责：明确下载原始资料、先校验再恢复到新项目；不覆盖原项目或触发分析。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { readApi } from './projectApi'
import {
  archiveMaxBytes,
  archiveCandidate,
  materialRestoreKey,
  type MaterialArchive,
  type ArchiveOverview,
  type ArchivePreview,
  type ArchiveReceipt,
} from './materialArchive'
const props = defineProps<{ projectId?: string }>()
const emit = defineEmits<{ open: [id: string] }>()
const overview = ref<ArchiveOverview | null>(null),
  archive = ref<MaterialArchive | null>(null)
const name = ref(''),
  goal = ref(''),
  filename = ref('')
const preview = ref<ArchivePreview | null>(null),
  receipt = ref<ArchiveReceipt | null>(null)
const busy = ref(false),
  loading = ref(false),
  confirmed = ref(false)
const error = ref(''),
  notice = ref('')
let generation = 0,
  overviewGeneration = 0
/** 容量摘要没有正文；失败仍保留原项目，不自动下载或缩小导出范围。 */
async function loadOverview() {
  if (!props.projectId) return
  const version = ++overviewGeneration
  loading.value = true
  error.value = ''
  try {
    const result = await readApi<ArchiveOverview>(
      `/api/v1/projects/${encodeURIComponent(props.projectId)}/material-archive/overview`,
    )
    if (version === overviewGeneration) overview.value = result
  } catch (caught) {
    if (version === overviewGeneration)
      error.value = caught instanceof Error ? caught.message : '归档范围读取失败。'
  } finally {
    if (version === overviewGeneration) loading.value = false
  }
}
/** 下载前服务端完整校验；收到错误显示在页面，不把错误JSON保存成所谓“成功备份”。 */
async function download() {
  if (!props.projectId || busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  const version = generation
  try {
    const result = await readApi<MaterialArchive>(
      `/api/v1/projects/${encodeURIComponent(props.projectId)}/material-archive`,
    )
    if (version !== generation) return
    const blob = new Blob([JSON.stringify(result)], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob),
      link = document.createElement('a')
    link.href = url
    link.download = 'echoinsight-materials.json'
    link.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 10000)
    notice.value = '资料下载已发起，请确认本机文件保存成功。文件含原文，请妥善保管。'
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '导出失败，未生成可用归档。'
  } finally {
    if (version === generation) busy.value = false
  }
}
/** 只读取用户明确选择的JSON；文件不自动上传，换文件清除旧预检及确认。 */
async function choose(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  const version = ++generation
  archive.value = null
  preview.value = null
  receipt.value = null
  confirmed.value = false
  error.value = ''
  notice.value = ''
  filename.value = file?.name || ''
  if (!file) return
  if (!file.size || file.size > archiveMaxBytes) {
    error.value = '资料JSON为1字节～14MiB，请勿选择压缩包或源码。'
    return
  }
  busy.value = true
  try {
    const candidate = archiveCandidate(JSON.parse(await file.text()))
    if (version !== generation) return
    archive.value = candidate
    name.value = candidate.data.project.name.slice(0, 116) + ' · 恢复'
    goal.value = candidate.data.project.analysisGoal
  } catch (caught) {
    if (version === generation)
      error.value =
        caught instanceof SyntaxError
          ? '资料JSON格式错误，请保留原文件重新导出。'
          : caught instanceof Error
            ? caught.message
            : '文件不是可读取的资料JSON。'
  } finally {
    if (version === generation) busy.value = false
  }
}
/** 文件哈希与新项目要求一同发送；每次预检/恢复都重新提交原包给服务器完整核验。 */
function body() {
  return {
    name: name.value.trim(),
    analysisGoal: goal.value.trim(),
    expectedSha256: archive.value!.sha256,
    archive: archive.value,
  }
}
async function check() {
  if (!archive.value || busy.value) return
  busy.value = true
  error.value = ''
  preview.value = null
  confirmed.value = false
  const version = generation
  try {
    const result = await readApi<ArchivePreview>('/api/v1/material-archives/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body()),
    })
    if (version === generation) preview.value = result
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '归档校验失败，没有创建项目。'
  } finally {
    if (version === generation) busy.value = false
  }
}
/** 只有明确勾选恢复范围后执行；相同文件/项目要求保持稳定幂等键，断网后原样重试。 */
async function restore() {
  if (!archive.value || !preview.value || !confirmed.value || busy.value || receipt.value) return
  busy.value = true
  error.value = ''
  const version = generation
  try {
    const key = await materialRestoreKey(archive.value.sha256, name.value, goal.value)
    const result = await readApi<ArchiveReceipt>('/api/v1/material-archives/restore', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
      body: JSON.stringify(body()),
    })
    if (version === generation) receipt.value = result
  } catch (caught) {
    if (version === generation)
      error.value =
        caught instanceof Error
          ? caught.message
          : '恢复结果暂未确认，请保留文件并原样重试；不要先改名重复恢复。'
  } finally {
    if (version === generation) busy.value = false
  }
}
/** 修改目的要求后必须重新预检；同名同目标重发返回原项目，另取名字才能明确另存一份。 */
watch([name, goal], () => {
  preview.value = null
  confirmed.value = false
  receipt.value = null
})
watch(
  () => props.projectId,
  () => {
    generation++
    overviewGeneration++
    overview.value = null
    void loadOverview()
  },
  { immediate: true },
)
onUnmounted(() => {
  generation++
  overviewGeneration++
})
</script>

<template>
  <section class="material-archive" aria-label="资料归档与恢复">
    <div class="section-toolbar">
      <div>
        <p class="eyebrow">MATERIAL ARCHIVE</p>
        <h2>资料归档与恢复</h2>
      </div>
    </div>
    <p>把评论、文本和字幕保存到本机，校验后恢复到新项目继续研究。原项目和历史记录保持保留。</p>
    <p class="notice">
      这是原始资料的可携带归档，包含全部已挂载资料版本。任务、报告、费用、人工意见、集合与采集检查点仍留在原项目；整库灾备须另行备份MySQL。
    </p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <section v-if="projectId" class="archive-block">
      <h3>保存这个项目的原文</h3>
      <p v-if="loading">正在核对资料范围…</p>
      <p v-if="overview">
        {{ overview.snapshots }}份评论快照 · {{ overview.contents }}份内容版本 ·
        {{ overview.references }}份参考文本 · {{ overview.subtitles }}份关联字幕
      </p>
      <p v-if="overview && !overview.withinBounds" class="error">
        资料超出当前归档容量，不会只导出其中一部分。请使用开发文档中的整库备份方案，或建立明确的小范围资料项目。
      </p>
      <div class="archive-actions">
        <button :disabled="busy || !overview?.withinBounds" @click="download">
          {{ busy ? '正在处理…' : '下载资料JSON' }}</button
        ><button class="quiet" :disabled="busy || loading" @click="loadOverview">
          重新核对范围
        </button>
      </div>
    </section>
    <section class="archive-block">
      <h3>从本机归档恢复到新项目</h3>
      <p>
        单包最多14MiB、60份评论快照及各100份内容、参考、字幕。先核对文件和版本链，再明确创建新项目；不会自动分析或收费。
      </p>
      <label class="archive-field"
        >选择资料JSON<input
          type="file"
          accept="application/json,.json"
          :disabled="busy"
          @change="choose"
      /></label>
      <p v-if="filename">已选择：{{ filename }}</p>
      <div v-if="archive" class="archive-fields">
        <label class="archive-field"
          >恢复后的项目名称<input v-model="name" maxlength="120" :disabled="busy"
        /></label>
        <label class="archive-field"
          >恢复后的研究目标<textarea v-model="goal" maxlength="1000" rows="3" :disabled="busy" />
        </label>
        <p>
          同一文件、名称和目标重复恢复会返回同一项目；如需另一份副本，请明确使用不同名称。资料校验成功不代表来源真实或平台采集完整。
        </p>
        <button :disabled="busy || !name.trim() || !goal.trim()" @click="check">
          校验归档与恢复范围
        </button>
      </div>
      <section v-if="preview" class="archive-preview" aria-label="恢复预检结果">
        <h4>{{ receipt ? '本次归档校验结果' : '归档校验通过，尚未恢复' }}</h4>
        <p>
          {{ preview.snapshots }}份评论快照（{{ preview.comments }}条版本内评论，非去重观众数），{{
            preview.contents
          }}份内容版本，{{ preview.references }}份参考，{{ preview.subtitles }}份字幕。
        </p>
        <details>
          <summary>恢复范围与完整性指纹</summary>
          <p class="archive-hash">SHA-256 {{ preview.archiveSha256 }}</p>
          <p v-for="note in preview.notes" :key="note">{{ note }}</p>
        </details>
        <template v-if="!receipt"
          ><label class="archive-confirm"
            ><input
              v-model="confirmed"
              type="checkbox"
              :disabled="busy"
            />我已确认：仅恢复原始资料到新项目，不恢复旧报告、账本或平台完整性。</label
          ><button :disabled="busy || !confirmed" @click="restore">
            {{ busy ? '正在恢复…' : '确认恢复到新项目' }}
          </button></template
        >
      </section>
      <section v-if="receipt" class="archive-preview" role="status">
        <h4>资料已恢复，可继续研究</h4>
        <p>原项目和已保存历史保留；新项目没有自动创建分析任务。</p>
        <button @click="emit('open', receipt.projectId)">打开恢复后的项目</button>
        <details>
          <summary>查看旧身份与新身份映射</summary>
          <pre>{{
            JSON.stringify(
              {
                snapshots: receipt.snapshots,
                contents: receipt.contents,
                references: receipt.references,
                subtitles: receipt.subtitles,
              },
              null,
              2,
            )
          }}</pre>
        </details>
      </section>
    </section>
  </section>
</template>

<style scoped>
.material-archive {
  max-width: 1100px;
}
.material-archive p {
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.archive-block {
  margin-top: 24px;
  padding: 24px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 12px;
}
.archive-field {
  display: grid;
  gap: 8px;
  margin-block: 18px;
}
.archive-field input,
.archive-field textarea {
  box-sizing: border-box;
  width: 100%;
  max-width: 100%;
}
.archive-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.archive-preview {
  margin-top: 20px;
  padding: 20px;
  background: #f1f7f3;
  border-radius: 10px;
}
.archive-hash,
pre {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  word-break: break-all;
}
.archive-confirm {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  margin-block: 20px;
  line-height: 1.8;
}
@media (max-width: 700px) {
  .archive-block {
    padding: 16px;
  }
  .material-archive button {
    white-space: normal;
  }
}
</style>
