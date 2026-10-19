<!-- 文件职责：把平台工具导出的作品与评论文件交给 Java 适配，成功后沿用快照分析入口。 -->
<script setup lang="ts">
import { ref } from 'vue'
import AnalysisPanel from './AnalysisPanel.vue'
const props = defineProps<{ projectId?: string }>()
const emit = defineEmits<{ imported: [] }>()

/** 复用标准导入回执，前端不能根据文件内容自行计算快照或修改来源身份。 */
type Receipt = {
  id: string
  snapshotId: string
  snapshotCommentCount: number
  duplicateComments: number
  newRevisions: number
  snapshotReused: boolean
}
/** 上传诊断针对当前文件，排除行不是平台删除，也不等于平台覆盖率。 */
type Result = {
  receipt: Receipt
  platform: string
  sourceContentId: string
  adapterVersion: string
  contentRows: number
  commentRows: number
  selectedCommentRows: number
  excludedContentRows: number
  excludedCommentRows: number
  contentFileSha256: string
  commentsFileSha256: string
  warnings: string[]
}
const platform = ref('BILIBILI')
const sourceContentId = ref('')
const contentsFile = ref<File | null>(null)
const commentsFile = ref<File | null>(null)
const busy = ref(false)
const result = ref<Result | null>(null)
const error = ref('')
const issues = ref<{ path: string; message: string }[]>([])

/** 仅保留当前用户选择的本地文件，不读取 Cookie、不访问平台链接。 */
function selectFile(event: Event, kind: 'contents' | 'comments') {
  const file = (event.target as HTMLInputElement).files?.[0] || null
  if (kind === 'contents') contentsFile.value = file
  else commentsFile.value = file
}

/** 1、校验文件；2、提交明确作品范围；3、显示真实回执与排除数；4、交由用户决定分析。 */
async function submit() {
  if (busy.value) return
  error.value = ''
  issues.value = []
  result.value = null
  if (!sourceContentId.value.trim() || !contentsFile.value || !commentsFile.value) {
    error.value = '请填写作品来源 ID，并选择作品、评论两份 JSON 文件。'
    return
  }
  for (const file of [contentsFile.value, commentsFile.value]) {
    if (
      !file.name.toLowerCase().endsWith('.json') ||
      file.size === 0 ||
      file.size > 2 * 1024 * 1024
    ) {
      error.value = '每份文件必须是非空 JSON，且不超过 2 MiB。'
      return
    }
  }
  busy.value = true
  try {
    const body = new FormData()
    body.append('platform', platform.value)
    if (props.projectId) body.append('projectId', props.projectId)
    body.append('sourceContentId', sourceContentId.value.trim())
    body.append('contentsFile', contentsFile.value)
    body.append('commentsFile', commentsFile.value)
    const response = await fetch('/api/v1/imports/mediacrawler', { method: 'POST', body })
    const data = await response.json()
    if (!response.ok) {
      error.value = data.message || '平台文件导入失败，请检查格式和作品范围。'
      issues.value = data.issues || []
      return
    }
    result.value = data
    if (props.projectId) emit('imported')
  } catch {
    error.value = '无法完成导入，请确认后端已启动后重试。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section id="platform-import" class="platform-import" aria-labelledby="platform-import-title">
    <h2 id="platform-import-title">导入 B站 / 小红书数据</h2>
    <p>
      已有 MediaCrawler 导出文件可直接接入分析，无需手工改成标准格式。这里不会登录平台或自动抓取。
    </p>
    <form @submit.prevent="submit">
      <div class="platform-fields">
        <label
          >数据平台
          <select v-model="platform" :disabled="busy" aria-label="数据平台">
            <option value="BILIBILI">B站</option>
            <option value="XHS">小红书</option>
          </select>
        </label>
        <label
          >作品来源 ID
          <input
            v-model="sourceContentId"
            :disabled="busy"
            maxlength="96"
            placeholder="填写导出文件的 video_id 或 note_id"
            aria-label="作品来源 ID"
          />
        </label>
      </div>
      <p>从文件中选定一个作品；B站使用 video_id（导出中的 av 数字 ID），不是 BV 号或视频链接。</p>
      <div class="platform-fields">
        <label
          >作品 JSON 文件
          <input
            type="file"
            accept=".json,application/json"
            :disabled="busy"
            aria-label="作品 JSON 文件"
            @change="selectFile($event, 'contents')"
          />
        </label>
        <label
          >评论 JSON 文件
          <input
            type="file"
            accept=".json,application/json"
            :disabled="busy"
            aria-label="评论 JSON 文件"
            @change="selectFile($event, 'comments')"
          />
        </label>
      </div>
      <p>
        每份最多 2 MiB，格式为 JSON
        数组。请保留所选作品的父评论；时间字段由原导出提供，不能用采集时间替代。
      </p>
      <button type="submit" :disabled="busy">
        {{ busy ? '正在转换并保存…' : '导入平台数据' }}
      </button>
    </form>
    <div v-if="error" role="alert">
      <strong>{{ error }}</strong>
      <ul>
        <li v-for="(issue, index) in issues" :key="index">{{ issue.path }}：{{ issue.message }}</li>
      </ul>
    </div>
    <div v-if="result" class="platform-success" role="status">
      <strong
        >平台数据已保存 · {{ result.platform === 'BILIBILI' ? 'B站' : '小红书' }} ·
        {{ result.sourceContentId }}</strong
      >
      <p>
        文件有 {{ result.contentRows }} 条作品记录、{{ result.commentRows }} 条评论记录； 选中
        {{ result.selectedCommentRows }} 条评论，快照去重后
        {{ result.receipt.snapshotCommentCount }} 条。
      </p>
      <p>
        排除其他作品记录 {{ result.excludedContentRows }} 条、其他作品评论
        {{ result.excludedCommentRows }} 条； 重复评论
        {{ result.receipt.duplicateComments }} 条，新增文本修订
        {{ result.receipt.newRevisions }} 条。
      </p>
      <p>
        {{ result.receipt.snapshotReused ? '复用了已有快照。' : '已建立新快照。' }} 快照：{{
          result.receipt.snapshotId
        }}
      </p>
      <ul>
        <li v-for="warning in result.warnings" :key="warning">{{ warning }}</li>
      </ul>
      <details>
        <summary>核对本次导入文件</summary>
        <p>转换版本：{{ result.adapterVersion }}；导入回执：{{ result.receipt.id }}</p>
        <p class="hash">
          作品文件 SHA-256：{{ result.contentFileSha256 }}<br />评论文件 SHA-256：{{
            result.commentsFileSha256
          }}
        </p>
        <p>指纹用于核对当前文件，本次转换诊断不属于持久采集档案。</p>
      </details>
      <AnalysisPanel :key="result.receipt.snapshotId" :snapshot-id="result.receipt.snapshotId" />
    </div>
  </section>
</template>

<style scoped>
.platform-import {
  background: #fff;
  border: 1px solid #dce5d9;
  border-radius: 12px;
  padding: 25px;
  margin-bottom: 36px;
}
h2 {
  font-size: 18px;
}
p,
label,
li,
summary {
  font-size: 12px;
  line-height: 1.9;
}
.platform-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin: 12px 0;
}
label {
  display: flex;
  flex-direction: column;
  gap: 5px;
  max-width: 100%;
  min-width: 0;
}
input,
select {
  padding: 8px;
  border: 1px solid #cbd9c5;
  border-radius: 5px;
  max-width: 100%;
  box-sizing: border-box;
}
input:not([type='file']) {
  width: 310px;
}
input[type='file'] {
  width: 340px;
}
.platform-success {
  margin-top: 20px;
  border-top: 1px solid #dce5d9;
  padding-top: 18px;
}
.hash {
  overflow-wrap: anywhere;
}
summary {
  cursor: pointer;
}
[role='alert'] {
  margin-top: 14px;
  color: #8a3d2c;
}
</style>
