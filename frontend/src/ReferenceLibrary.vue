<!-- 文件职责：追加不可变的参考文本，支持粘贴或读取本地UTF8文本，不解析任意网页或Word/PDF。 -->
<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { readApi, type Page } from './projectApi'
const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ analyze: [] }>()
type Summary = {
  id: string
  title: string
  sourceDescription: string
  textChars: number
  createdAt: string
}
type Material = { summary: Summary; text: string }
const title = ref('')
const text = ref('')
const source = ref('')
const history = ref<Page<Summary> | null>(null)
const selected = ref<Material | null>(null)
const page = ref(1)
const mode = ref<'browse' | 'create'>('browse')
const busy = ref(false)
const error = ref('')
let alive = true
let readVersion = 0
let submission: { input: string; key: string } | undefined

/** 分页只读项目摘要，进入页面不新建资料或分析。 */
async function load() {
  const version = ++readVersion
  error.value = ''
  try {
    const result = await readApi<Page<Summary>>(
      `/api/v1/projects/${props.projectId}/reference-materials?page=${page.value}&size=10`,
    )
    if (alive && version === readVersion) history.value = result
  } catch (caught) {
    if (alive && version === readVersion) error.value = message(caught)
  }
}
/** 保存新版本，不覆盖原文；网络不确定时同一输入沿用请求键。 */
async function save() {
  if (busy.value) return
  const input = JSON.stringify({
    title: title.value.trim(),
    text: text.value,
    sourceDescription: source.value.trim(),
  })
  if (submission?.input !== input) submission = { input, key: crypto.randomUUID() }
  busy.value = true
  error.value = ''
  try {
    const result = await readApi<Material>(
      `/api/v1/projects/${props.projectId}/reference-materials`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': submission!.key },
        body: input,
      },
    )
    if (!alive) return
    selected.value = result
    mode.value = 'browse'
    page.value = 1
    submission = undefined
    title.value = ''
    text.value = ''
    source.value = ''
    await load()
  } catch (caught) {
    if (alive) error.value = message(caught)
  } finally {
    if (alive) busy.value = false
  }
}
/** 文件在浏览器本地解码为原文，正文上限与后端一致；非法编码明确拒绝。 */
async function chooseFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    if (!/\.(txt|md)$/i.test(file.name) || file.size > 48000)
      throw new Error('请选择48KB以内的UTF8 .txt/.md文本。')
    const content = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer())
    if (!content.trim() || content.length > 12000)
      throw new Error('正文需要1～12000字，请缩小文件或粘贴需要的部分。')
    if (!alive) return
    text.value = content
    if (!title.value.trim()) title.value = file.name.slice(0, 120)
  } catch (caught) {
    if (alive) error.value = caught instanceof Error ? caught.message : '文件不是可读取的UTF8文本。'
  } finally {
    input.value = ''
  }
}
/** 正文按明确资料身份读取；快速切换时旧响应不能覆盖新选择。 */
async function open(item: Summary) {
  const version = ++readVersion
  try {
    const result = await readApi<Material>(
      `/api/v1/projects/${props.projectId}/reference-materials/${item.id}`,
    )
    if (alive && version === readVersion) selected.value = result
  } catch (caught) {
    if (alive && version === readVersion) error.value = message(caught)
  }
}
/** 分页不改变任何历史版本或已有请求的背景。 */
function turn(next: number) {
  page.value = next
  void load()
}
/** 对用户只展示明确原因，不输出服务器私有响应。 */
function message(caught: unknown) {
  return caught instanceof Error ? caught.message : '参考资料操作失败。'
}
onMounted(load)
onUnmounted(() => {
  alive = false
  readVersion++
})
</script>
<template>
  <section>
    <div class="section-toolbar">
      <div>
        <h3>项目参考资料</h3>
        <p class="small">
          用来解释剧情背景、术语或对照内容，独立于观众评论。保存后需在智能分析中明确选用。
        </p>
      </div>
      <button @click="mode = mode === 'browse' ? 'create' : 'browse'">
        {{ mode === 'browse' ? '添加参考文本' : '返回资料列表' }}
      </button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <form v-if="mode === 'create'" class="surface insight-composer" @submit.prevent="save">
      <label class="field">参考资料标题<input v-model="title" maxlength="120" required /></label>
      <label class="field"
        >来源说明（可选）<input
          v-model="source"
          maxlength="200"
          placeholder="例如：自己整理的剧情笔记；仅保存说明，不自动访问链接"
      /></label>
      <label class="field"
        >从本机UTF8文本读取<input
          type="file"
          accept=".txt,.md,text/plain,text/markdown"
          @change="chooseFile"
      /></label>
      <label class="field"
        >原文<textarea v-model="text" rows="10" maxlength="12000" required />
      </label>
      <p class="small">
        {{ text.length }} /
        12000字。资料中的文字按内容处理；来源说明不表示系统已验证事实。修订时新增一份，旧分析保留原版。
      </p>
      <button class="primary" :disabled="busy || !title.trim() || !text.trim()">
        保存参考资料
      </button>
    </form>
    <template v-else>
      <p v-if="history && !history.total" class="empty-state">
        还没有参考文本。单纯分析观众讨论时可以不添加。
      </p>
      <button
        v-for="item in history?.items"
        :key="item.id"
        class="request-history-item"
        @click="open(item)"
      >
        <strong>{{ item.title }}</strong
        ><small>{{ item.textChars }}字 · {{ item.createdAt }}</small>
      </button>
      <div v-if="history && history.totalPages > 1" class="pagination">
        <button :disabled="page <= 1" @click="turn(page - 1)">上一页</button
        ><span>{{ page }} / {{ history.totalPages }}</span
        ><button :disabled="page >= history.totalPages" @click="turn(page + 1)">下一页</button>
      </div>
      <section v-if="selected" class="surface">
        <h4>{{ selected.summary.title }}</h4>
        <p class="small">
          用户提供背景 · {{ selected.summary.sourceDescription || '未填写来源说明' }} ·
          {{ selected.summary.textChars }}字
        </p>
        <details>
          <summary>查看保存的原文</summary>
          <pre class="reference-text">{{ selected.text }}</pre>
        </details>
        <button @click="emit('analyze')">去智能分析选择这份背景</button>
      </section>
    </template>
  </section>
</template>
<style scoped>
.reference-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
}
</style>
