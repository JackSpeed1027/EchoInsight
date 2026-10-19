<!-- 文件职责：选择原报告评论并预检追问、接受新任务、浏览持久分支，不把浏览器聊天记录当事实存储。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { readApi, jobStatus, type Page } from './projectApi'
const props = defineProps<{ projectId: string; requestId: string }>()
const emit = defineEmits<{ open: [requestId: string] }>()
type Anchor = { commentId: string; text: string; eligible: boolean }
type Link = {
  requestId: string
  parentRequestId: string | null
  depth: number
  status: string
  instruction: string
}
type Preview = {
  savedComments: number
  selectedComments: number
  requiresPartialConsent: boolean
  depth: number
  notes: string[]
  context: { selection: { textChars: number; history: { parentRequestId: string } } }
}
const anchors = ref<Anchor[]>([])
const links = ref<Link[]>([])
const totalLinks = ref(0)
const historyPage = ref(1)
const anchor = ref('')
const question = ref('')
const consent = ref(false)
const preview = ref<Preview | null>(null)
const busy = ref(false)
const error = ref('')
let generation = 0
let alive = true
let submission: { identity: string; key: string } | undefined
const base = () => `/api/v1/projects/${props.projectId}/analysis-requests/${props.requestId}`

/** 用户展开才读取候选；迟到响应不能把另一个报告的评论放入当前表单。 */
async function load() {
  const version = ++generation
  busy.value = true
  error.value = ''
  try {
    const [options, history] = await Promise.all([
      readApi<Anchor[]>(base() + '/follow-up-options'),
      readApi<Page<Link>>(base() + `/conversation?page=${historyPage.value}&size=20`),
    ])
    if (!alive || version !== generation) return
    anchors.value = options
    links.value = history.items
    totalLinks.value = history.total
  } catch (caught) {
    if (alive && version === generation)
      error.value = caught instanceof Error ? caught.message : '追问资料读取失败。'
  } finally {
    if (alive && version === generation) busy.value = false
  }
}

/** 预检与提交使用同一输入；修改问题后必须重新预检，不能沿用旧范围。 */
async function check() {
  if (!anchor.value || !question.value.trim() || busy.value) return
  const version = ++generation
  busy.value = true
  error.value = ''
  try {
    const result = await readApi<Preview>(base() + '/follow-ups/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ anchorCommentId: anchor.value, question: question.value.trim() }),
    })
    if (alive && version === generation) preview.value = result
  } catch (caught) {
    if (alive && version === generation)
      error.value = caught instanceof Error ? caught.message : '追问预检失败。'
  } finally {
    if (alive && version === generation) busy.value = false
  }
}

/** 创建原分析请求而不是前端伪造回答；同一输入的网络重发复用请求键。 */
async function submit() {
  if (!preview.value || busy.value || (preview.value.requiresPartialConsent && !consent.value))
    return
  const version = ++generation
  const identity = JSON.stringify([
    props.projectId,
    props.requestId,
    anchor.value,
    question.value.trim(),
  ])
  if (!submission || submission.identity !== identity)
    submission = { identity, key: crypto.randomUUID() }
  busy.value = true
  error.value = ''
  try {
    const result = await readApi<{ id: string }>(base() + '/follow-ups', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': submission.key },
      body: JSON.stringify({
        anchorCommentId: anchor.value,
        question: question.value.trim(),
        allowPartialInput: consent.value,
      }),
    })
    if (alive && version === generation) emit('open', result.id)
  } catch (caught) {
    if (alive && version === generation)
      error.value = caught instanceof Error ? caught.message : '追问提交失败。'
  } finally {
    if (alive && version === generation) busy.value = false
  }
}

/** 分支历史只是导航，切换页不重跑模型。 */
function moveHistory(delta: number) {
  historyPage.value += delta
  void load()
}

/** 原生details展开事件不依赖截图或DOM顺序。 */
function expanded(event: Event) {
  if ((event.target as HTMLDetailsElement).open) void load()
}
watch([anchor, question], () => {
  preview.value = null
  consent.value = false
})
watch(
  () => [props.projectId, props.requestId],
  () => {
    generation++
    anchors.value = []
    links.value = []
    preview.value = null
    anchor.value = ''
    question.value = ''
    busy.value = false
  },
)
onUnmounted(() => {
  alive = false
  generation++
})
</script>
<template>
  <details class="surface continuation-panel" @toggle="expanded">
    <summary>围绕一条评论继续追问</summary>
    <p>
      沿用这次资料与已冻结背景；只携带所选评论相关的历史判断。每次追问是新任务，启用模型时会单独产生调用费用。
    </p>
    <p>
      可问“为什么这样判断”“还缺哪些前提”。重新指定命题或要求全部评论分类，请回到上方新建分析。最多连续八层。
    </p>
    <label
      >追问哪条评论
      <select v-model="anchor" :disabled="busy">
        <option value="" disabled>请选择原报告评论</option>
        <option
          v-for="item in anchors"
          :key="item.commentId"
          :value="item.commentId"
          :disabled="!item.eligible"
        >
          {{ item.text }}{{ item.eligible ? '' : '（超出当前有界输入）' }}
        </option>
      </select>
    </label>
    <label
      >继续问什么
      <textarea
        v-model="question"
        maxlength="1000"
        :disabled="busy"
        placeholder="这条判断依赖哪些前提？请结合父评论解释理由。"
      />
    </label>
    <button :disabled="busy || !anchor || !question.trim()" @click="check">先核对追问范围</button>
    <section v-if="preview" aria-label="追问范围说明">
      <p>
        同一资料保存{{ preview.savedComments }}条；本次有界输入{{
          preview.selectedComments
        }}条；第{{ preview.depth }}层追问；背景{{ preview.context.selection.textChars }}字。
      </p>
      <ul>
        <li v-for="note in preview.notes" :key="note">{{ note }}</li>
      </ul>
      <label v-if="preview.requiresPartialConsent"
        ><input
          v-model="consent"
          type="checkbox"
          :disabled="busy"
        />我接受仅处理上述有限输入，不据此推断全部评论。</label
      >
      <button :disabled="busy || (preview.requiresPartialConsent && !consent)" @click="submit">
        提交追问并创建新任务
      </button>
    </section>
    <p v-if="error" role="alert">{{ error }}</p>
    <h4>这组问题的历史分支</h4>
    <p v-if="!links.length">展开后读取；已保存的历史不会因关页面丢失。</p>
    <ul>
      <li v-for="link in links" :key="link.requestId">
        <button
          :disabled="busy || link.requestId === requestId"
          @click="emit('open', link.requestId)"
        >
          第{{ link.depth }}层 · {{ link.instruction }} · {{ jobStatus(link.status) }}
        </button>
      </li>
    </ul>
    <nav v-if="totalLinks > 20" aria-label="追问历史分页">
      <button :disabled="busy || historyPage === 1" @click="moveHistory(-1)">上一页</button
      ><span>第{{ historyPage }}页 · 共{{ totalLinks }}条</span
      ><button :disabled="busy || historyPage * 20 >= totalLinks" @click="moveHistory(1)">
        下一页
      </button>
    </nav>
  </details>
</template>

<style scoped>
/* 追问表单独立分行，让长评论与多行问题保持可读；历史导航不挤进输入区。 */
.continuation-panel {
  margin-top: 20px;
}
.continuation-panel > label {
  display: grid;
  gap: 8px;
  margin: 16px 0;
  font-weight: 600;
}
.continuation-panel select,
.continuation-panel textarea {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  padding: 10px 12px;
  border: 1px solid #d8e3dc;
  border-radius: 8px;
  background: #fff;
  font: inherit;
  font-weight: 400;
}
.continuation-panel textarea {
  min-height: 100px;
  resize: vertical;
}
.continuation-panel > ul {
  list-style: none;
  padding: 0;
}
.continuation-panel > ul li {
  margin: 8px 0;
}
.continuation-panel > ul button {
  text-align: left;
  line-height: 1.6;
}
</style>
