<!-- 文件职责：上传/浏览与作品绑定的字幕版本，或在提问时明确选择字幕；不创建分析或自动联网。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { readApi, type Page, type ProjectSnapshot } from './projectApi'
const props = defineProps<{
  projectId: string
  snapshotId?: string
  selectedId?: string
  picker?: boolean
}>()
const emit = defineEmits<{ select: [id: string] }>()
type Summary = {
  id: string
  title: string
  segmentCount: number
  durationMs: number
  createdAt: string
  sourceDescription: string
}
type Segment = { id: string; startMs: number; endMs: number; text: string }
const snapshots = ref<ProjectSnapshot[]>([])
const selectedSnapshot = ref('')
const items = ref<Summary[]>([])
const selected = ref<{ summary: Summary; segments: Segment[] } | null>(null)
const title = ref('')
const source = ref('')
const file = ref<File | null>(null)
const busy = ref(false)
const error = ref('')
let generation = 0
let alive = true
let submission: { input: string; key: string } | undefined
/** 只列所选作品的字幕，迟到响应不能污染另一个项目或作品。 */
async function load() {
  const current = ++generation
  error.value = ''
  selected.value = null
  items.value = []
  try {
    if (!props.picker) {
      const response = await readApi<Page<ProjectSnapshot>>(
        `/api/v1/projects/${props.projectId}/snapshots?page=1&size=50`,
      )
      if (!alive || generation !== current) return
      snapshots.value = response.items
      if (!response.items.some((item) => item.snapshotId === selectedSnapshot.value))
        selectedSnapshot.value =
          props.snapshotId || (response.items.length === 1 ? response.items[0].snapshotId : '')
    }
    const snapshot = props.picker ? props.snapshotId : selectedSnapshot.value
    if (!snapshot) return
    const response = await readApi<Page<Summary>>(
      `/api/v1/projects/${props.projectId}/transcripts?snapshotId=${encodeURIComponent(snapshot)}&size=50`,
    )
    if (alive && generation === current) items.value = response.items
  } catch (caught) {
    if (alive && generation === current)
      error.value = caught instanceof Error ? caught.message : '字幕列表读取失败。'
  }
}
/** 文件内容哈希进入幂等输入身份，重复网络提交不会生成第二份版本。 */
async function save() {
  if (!file.value || !selectedSnapshot.value || busy.value) return
  const uploading = file.value
  if (uploading.size > 2 * 1024 * 1024) {
    error.value = '字幕最多2MiB。'
    return
  }
  busy.value = true
  error.value = ''
  const version = generation
  try {
    const hash = Array.from(
      new Uint8Array(await crypto.subtle.digest('SHA-256', await uploading.arrayBuffer())),
    )
      .map((n) => n.toString(16).padStart(2, '0'))
      .join('')
    const identity = JSON.stringify([
      props.projectId,
      selectedSnapshot.value,
      title.value.trim(),
      source.value.trim(),
      uploading.name,
      hash,
    ])
    if (!submission || submission.input !== identity)
      submission = { input: identity, key: crypto.randomUUID() }
    const body = new FormData()
    body.set('snapshotId', selectedSnapshot.value)
    body.set('title', title.value.trim())
    body.set('sourceDescription', source.value.trim())
    body.set('file', uploading)
    const response = await readApi<{ summary: Summary; segments: Segment[] }>(
      `/api/v1/projects/${props.projectId}/transcripts`,
      { method: 'POST', headers: { 'Idempotency-Key': submission.key }, body },
    )
    if (!alive || version !== generation) return
    submission = undefined
    await load()
    if (alive) selected.value = response
  } catch (caught) {
    if (alive) error.value = caught instanceof Error ? caught.message : '字幕上传失败。'
  } finally {
    if (alive) busy.value = false
  }
}
/** 详情只读取不可变段，避免用户将新版本误当原分析依据。 */
async function open(id: string) {
  const version = generation
  try {
    const result = await readApi<{ summary: Summary; segments: Segment[] }>(
      `/api/v1/projects/${props.projectId}/transcripts/${id}`,
    )
    if (alive && version === generation) selected.value = result
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '字幕详情读取失败。'
  }
}
watch(() => [props.projectId, props.snapshotId], load, { immediate: true })
onUnmounted(() => {
  alive = false
  generation++
})
</script>
<template>
  <section :class="{ surface: !picker }">
    <template v-if="picker">
      <label class="field"
        >本问题使用的上传字幕
        <select
          :value="selectedId || ''"
          @change="emit('select', ($event.target as HTMLSelectElement).value)"
        >
          <option value="">不选上传字幕</option>
          <option
            v-if="selectedId && !items.some((item) => item.id === selectedId)"
            :value="selectedId"
          >
            使用原请求的历史字幕版本（冻结原文见报告）
          </option>
          <option v-for="item in items" :key="item.id" :value="item.id">
            {{ item.title }} · {{ item.segmentCount }}段 · {{ item.createdAt }}
          </option>
        </select>
      </label>
      <p class="small">
        上传入口在“添加资料→字幕文件”。选中后按本问题的字词命中及邻段取证，不是全片理解。
      </p>
    </template>
    <template v-else>
      <h3>为作品添加字幕证据</h3>
      <p>上传UTF8的SRT/VTT，保留每段原文和原片时间；修订创建新版本，不改变旧分析。</p>
      <form @submit.prevent="save">
        <label class="field"
          >对应作品或评论资料版本<select
            v-model="selectedSnapshot"
            required
            :disabled="busy"
            @change="load"
          >
            <option value="" disabled>请选择作品</option>
            <option v-for="item in snapshots" :key="item.snapshotId" :value="item.snapshotId">
              {{ item.title }} · {{ item.snapshotCreatedAt }}
            </option>
          </select></label
        >
        <label class="field"
          >字幕标题<input v-model="title" required maxlength="120" :disabled="busy"
        /></label>
        <label class="field"
          >来源说明<input
            v-model="source"
            maxlength="200"
            placeholder="例如：自己制作的字幕、平台导出或ASR转写（未认证）"
            :disabled="busy"
        /></label>
        <label class="field"
          >字幕文件<input
            type="file"
            accept=".srt,.vtt"
            required
            :disabled="busy"
            @change="file = ($event.target as HTMLInputElement).files?.[0] || null"
        /></label>
        <button class="primary" :disabled="busy || !selectedSnapshot || !file || !title.trim()">
          {{ busy ? '正在保存…' : '保存字幕版本' }}
        </button>
      </form>
      <p class="small">
        最多2MiB、5000段、单段10000字、时间≤24小时。当前需要已有作品；独立视频分析、自动ASR和视觉尚未提供。列表显示最近50份版本。
      </p>
      <article v-for="item in items" :key="item.id">
        <button class="quiet" @click="open(item.id)">
          {{ item.title }} · {{ item.segmentCount }}段 · {{ item.createdAt }}
        </button>
      </article>
      <section v-if="selected">
        <h4>{{ selected.summary.title }}</h4>
        <p>{{ selected.summary.sourceDescription || '未提供来源说明' }}（用户声明，未认证）</p>
        <blockquote v-for="segment in selected.segments.slice(0, 10)" :key="segment.id">
          {{ segment.startMs }}～{{ segment.endMs }}毫秒：{{ segment.text }}
        </blockquote>
        <p v-if="selected.segments.length > 10">此处仅预览前10段，原文已完整保存。</p>
      </section>
    </template>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
</template>
