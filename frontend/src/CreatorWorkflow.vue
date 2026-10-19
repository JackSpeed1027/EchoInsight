<!-- 文件职责：按“找作品→选作品→采集→确认范围”展示UP主资料入口，不自动提交付费分析。 -->
<script setup lang="ts">
import FailureNotice from './FailureNotice.vue'
import { describeFailure } from './publicFailures'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { coverageName, readApi, type Page } from './projectApi'

const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ frozen: [] }>()
type Candidate = { bvid: string; title: string; url: string }
type Discovery = {
  id: string
  status: string
  ownerMid: string
  keyword: string
  lastErrorCode: string | null
  result: { candidates: Candidate[]; completeness: string; limitations: string[] } | null
}
type Batch = {
  id: string
  status: string
  completeness: string | null
  collectedCommentCount: number | null
  lastErrorCode: string | null
}
type Group = {
  rootLimit: number
  id: string
  status: string
  succeeded: number
  failed: number
  pending: number
  cancelled: number
  items: Array<{ bvid: string; title: string; batch: Batch }>
}
const url = ref('')
const keyword = ref('')
const candidateLimit = ref(10)
const rootLimit = ref(20)
const name = ref('UP主讨论资料')
const discovery = ref<Discovery | null>(null)
const group = ref<Group | null>(null)
const discoveries = ref<Discovery[]>([])
const groups = ref<Group[]>([])
const selected = ref<string[]>([])
const successes = ref<string[]>([])
const error = ref('')
const busy = ref(false)
const loading = ref(true)
const step = ref<'find' | 'choose' | 'collect'>('find')
let refreshVersion = 0
let alive = true
let timer: number | undefined
const keys = new Map<string, string>()
const active = computed(() =>
  [discovery.value?.status, group.value?.status].some((s) => s === 'PENDING' || s === 'RUNNING'),
)
/** 网络结果未知时沿用相同输入的键，接受成功后仍避免双击重复创建。 */
function post<T>(path: string, input: unknown): Promise<T> {
  const identity = path + JSON.stringify(input)
  if (!keys.has(identity)) keys.set(identity, crypto.randomUUID())
  return readApi<T>(`/api/v1/projects/${props.projectId}/${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': keys.get(identity)! },
    body: JSON.stringify(input),
  })
}
/** 首屏只读历史；恢复最近任务，进入页面不会重新采集或分析。 */
async function load() {
  try {
    const [found, collected] = await Promise.all([
      readApi<Page<Discovery>>(`/api/v1/projects/${props.projectId}/creator-discoveries`),
      readApi<Page<Group>>(`/api/v1/projects/${props.projectId}/collection-groups`),
    ])
    if (!alive) return
    discoveries.value = found.items
    groups.value = collected.items
    discovery.value = found.items[0] || null
    group.value = collected.items[0] || null
    successes.value =
      group.value?.items.filter((i) => i.batch.status === 'SUCCESS').map((i) => i.batch.id) || []
    step.value = group.value ? 'collect' : discovery.value ? 'choose' : 'find'
    await refresh()
  } catch (caught) {
    if (alive) error.value = message(caught)
  } finally {
    if (alive) loading.value = false
  }
}
/** 候选发现与评论采集分开，避免一句模糊要求触发未知数量的平台请求。 */
async function discover() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await post<Discovery>('creator-discoveries', {
      url: url.value.trim(),
      keyword: keyword.value.trim(),
      candidateLimit: candidateLimit.value,
    })
    if (!alive) return
    discovery.value = result
    selected.value = []
    step.value = 'choose'
    discoveries.value = [result, ...discoveries.value.filter((d) => d.id !== result.id)].slice(
      0,
      10,
    )
    await refresh()
  } catch (caught) {
    if (alive) error.value = message(caught)
  } finally {
    if (alive) busy.value = false
  }
}
/** 只提交明确勾选的1～5个候选，前后端均限制上限。 */
async function collect() {
  if (busy.value || !discovery.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await post<Group>('collection-groups', {
      discoveryId: discovery.value.id,
      bvids: [...selected.value].sort(),
      rootLimit: rootLimit.value,
    })
    if (!alive) return
    group.value = result
    successes.value = []
    step.value = 'collect'
    groups.value = [result, ...groups.value.filter((g) => g.id !== result.id)].slice(0, 10)
    await refresh()
  } catch (caught) {
    if (alive) error.value = message(caught)
  } finally {
    if (alive) busy.value = false
  }
}
/** 全部任务结束后显式确认成功子集；冻结不触发模型，下一页再填写分析要求。 */
async function freeze() {
  if (busy.value || !group.value) return
  busy.value = true
  error.value = ''
  try {
    await post(`collection-groups/${group.value.id}/manifest`, {
      name: name.value.trim(),
      collectionIds: [...successes.value].sort(),
    })
    if (alive) emit('frozen')
  } catch (caught) {
    if (alive) error.value = message(caught)
  } finally {
    if (alive) busy.value = false
  }
}
/** 快速切换历史时校验身份，旧请求不能覆盖新选定的发现或批次。 */
async function refresh() {
  stop()
  const version = ++refreshVersion
  const discoveryId = discovery.value?.id,
    groupId = group.value?.id
  try {
    const [found, collected] = await Promise.all([
      discoveryId
        ? readApi<Discovery>(
            `/api/v1/projects/${props.projectId}/creator-discoveries/${discoveryId}`,
          )
        : null,
      groupId
        ? readApi<Group>(`/api/v1/projects/${props.projectId}/collection-groups/${groupId}`)
        : null,
    ])
    if (!alive || version !== refreshVersion) return
    if (found && discovery.value?.id === discoveryId) discovery.value = found
    if (collected && group.value && group.value.id === groupId) {
      const wasPending = group.value.pending > 0
      group.value = collected
      if (wasPending && collected.pending === 0)
        successes.value = collected.items
          .filter((i) => i.batch.status === 'SUCCESS')
          .map((i) => i.batch.id)
    }
    if (active.value) timer = window.setTimeout(refresh, 2000)
  } catch (caught) {
    if (alive) error.value = message(caught)
  }
}
/** 再次检索相同范围须由用户明确发起，成功历史不会被替换或删除。 */
function newSearch() {
  keys.clear()
  step.value = 'find'
  discovery.value = null
  selected.value = []
  error.value = ''
  void refresh()
}
/** 切换到明确历史候选，不重新访问平台；清空上一候选勾选以免串用。 */
function openDiscovery(value: Discovery) {
  discovery.value = value
  step.value = 'choose'
  selected.value = []
  void refresh()
}
/** 恢复历史采集组和成功子集，实际状态仍以后台刷新为准。 */
function openGroup(value: Group) {
  group.value = value
  step.value = 'collect'
  successes.value = value.items.filter((i) => i.batch.status === 'SUCCESS').map((i) => i.batch.id)
  void refresh()
}
/** 只停止本组件创建的轮询计时器，退出页面不取消持久任务。 */
function stop() {
  if (timer !== undefined) window.clearTimeout(timer)
  timer = undefined
}
/** 将未知异常收敛为安全提示，不把底层响应正文展示给用户。 */
function message(caught: unknown) {
  return caught instanceof Error ? caught.message : '操作失败，请刷新状态后重试。'
}
/** 对用户解释业务结果，原始安全错误码只作为排障辅助保留。 */
function failureText(code: string) {
  const detail = describeFailure(code)
  return detail.message + ' ' + detail.action
}
/** 把持久状态翻译成业务名称，部分失败与全部成功保持区别。 */
function statusName(status: string) {
  const names: Record<string, string> = {
    PENDING: '等待执行',
    RUNNING: '正在执行',
    SUCCESS: '已完成',
    PARTIAL_FAILED: '部分失败',
    FAILED: '失败',
    CANCELLED: '全部已取消',
  }
  return names[status] || status
}
onMounted(load)
onUnmounted(() => {
  alive = false
  stop()
})
</script>

<template>
  <section class="creator-workflow">
    <p v-if="loading" role="status">正在恢复采集记录…</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <nav class="workflow-steps" aria-label="UP主资料采集步骤">
      <button :class="{ selected: step === 'find' }" @click="step = 'find'">1 找作品</button>
      <button
        :class="{ selected: step === 'choose' }"
        :disabled="!discovery"
        @click="step = 'choose'"
      >
        2 选作品
      </button>
      <button
        :class="{ selected: step === 'collect' }"
        :disabled="!group"
        @click="step = 'collect'"
      >
        3 确认采集范围
      </button>
    </nav>
    <form v-if="step === 'find'" class="surface insight-composer" @submit.prevent="discover">
      <h3>1. 找到UP主的作品</h3>
      <p class="small">先按平台关键词找候选，再选择要分析的作品。此处不调用付费模型。</p>
      <label class="field"
        >UP主空间链接<input
          v-model="url"
          type="url"
          required
          maxlength="500"
          placeholder="https://space.bilibili.com/你的UID"
      /></label>
      <label class="field"
        >作品关键词（可留空）<input
          v-model="keyword"
          maxlength="80"
          placeholder="例如：命运石之门；留空查看最新投稿"
      /></label>
      <label class="field"
        >最多候选作品数<input
          v-model.number="candidateLimit"
          type="number"
          min="1"
          max="20"
          required
      /></label>
      <div class="composer-actions">
        <button class="primary" :disabled="loading || busy || active">查找作品</button
        ><button type="button" :disabled="busy || active" @click="newSearch">开始一次新检索</button>
      </div>
    </form>
    <section v-if="discovery && step === 'choose'" class="surface">
      <div class="section-toolbar">
        <h3>2. 选择作品 · {{ statusName(discovery.status) }}</h3>
        <button @click="refresh">刷新状态</button>
      </div>
      <p class="small">
        本次UP主 {{ discovery.ownerMid }} · 关键词：{{ discovery.keyword || '未设置' }}
      </p>
      <p v-if="discovery.lastErrorCode" class="error">
        {{ failureText(discovery.lastErrorCode) }} 可新建一次检索；失败不表示没有匹配作品。
      </p>
      <template v-if="discovery.result">
        <p class="scope-copy">
          {{ coverageName(discovery.result.completeness) }} · 返回
          {{ discovery.result.candidates.length }} 个候选。关键词匹配不等于语义相关性判断。
        </p>
        <p v-if="!discovery.result.candidates.length">
          本次没有返回候选作品，可调整关键词；不代表完整空间为空。
        </p>
        <label
          v-for="candidate in discovery.result.candidates"
          :key="candidate.bvid"
          class="creator-candidate"
          ><input
            v-model="selected"
            type="checkbox"
            :value="candidate.bvid"
            :disabled="busy || (selected.length >= 5 && !selected.includes(candidate.bvid))"
          /><span
            >{{ candidate.title
            }}<a :href="candidate.url" target="_blank" rel="noreferrer">查看原视频 ↗</a></span
          ></label
        >
        <label class="field"
          >每作品最多根评论数<input
            v-model.number="rootLimit"
            type="number"
            min="1"
            max="200"
            required
        /></label>
        <button
          class="primary"
          :disabled="
            busy ||
            selected.length < 1 ||
            selected.length > 5 ||
            active ||
            rootLimit < 1 ||
            rootLimit > 200
          "
          @click="collect"
        >
          采集所选 {{ selected.length }} 个作品
        </button>
      </template>
    </section>
    <section v-if="group && step === 'collect'" class="surface">
      <div class="section-toolbar">
        <h3>3. 采集与确认范围 · {{ statusName(group.status) }}</h3>
        <button @click="refresh">刷新状态</button>
      </div>
      <p>
        成功 {{ group.succeeded }} · 失败 {{ group.failed }} · 已取消 {{ group.cancelled }} · 未结束
        {{ group.pending }}
      </p>
      <p class="scope-copy">
        本次每作品最多 {{ group.rootLimit }} 根评论，回复随评论树展开；最终条数可能更高。
      </p>
      <article v-for="item in group.items" :key="item.batch.id" class="issue-card">
        <label class="creator-candidate"
          ><input
            v-model="successes"
            type="checkbox"
            :value="item.batch.id"
            :disabled="group.pending > 0 || item.batch.status !== 'SUCCESS' || busy"
          /><span>{{ item.title }} · {{ statusName(item.batch.status) }}</span></label
        >
        <p v-if="item.batch.status === 'SUCCESS'" class="scope-copy">
          取得 {{ item.batch.collectedCommentCount }} 条评论 ·
          {{ coverageName(item.batch.completeness) }}
        </p>
        <FailureNotice
          v-if="item.batch.lastErrorCode"
          :code="item.batch.lastErrorCode"
          :task-id="item.batch.id"
        />
      </article>
      <form v-if="group.pending === 0 && group.succeeded > 0" @submit.prevent="freeze">
        <label class="field">集合名称<input v-model="name" maxlength="120" required /></label>
        <p class="small">
          已勾选
          {{ successes.length }} 个成功作品。失败或未勾选作品不进入这份集合；后续分析范围以此为准。
        </p>
        <button class="primary" :disabled="busy || !successes.length || !name.trim()">
          确认范围，进入资料集合
        </button>
      </form>
    </section>
    <details v-if="discoveries.length || groups.length" class="collection-history">
      <summary>查看最近检索与批量采集记录</summary>
      <h4>候选检索</h4>
      <button
        v-for="item in discoveries"
        :key="item.id"
        class="request-history-item"
        @click="openDiscovery(item)"
      >
        UP主 {{ item.ownerMid }} · {{ item.keyword || '最新投稿' }} · {{ statusName(item.status) }}
      </button>
      <h4>批量采集</h4>
      <button
        v-for="item in groups"
        :key="item.id"
        class="request-history-item"
        @click="openGroup(item)"
      >
        {{ item.items.length }} 个作品 · {{ statusName(item.status) }}
      </button>
    </details>
  </section>
</template>

<style scoped>
.workflow-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 0.6rem;
}
.workflow-steps .selected {
  background: #e7f0e9;
  border-color: #6c987c;
}
.creator-workflow {
  display: grid;
  gap: 1.25rem;
}
.creator-candidate {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
  padding: 0.8rem 0;
}
.creator-candidate input {
  width: auto;
  margin-top: 0.3rem;
}
.creator-candidate span {
  display: grid;
  gap: 0.3rem;
  overflow-wrap: anywhere;
}
.creator-candidate a {
  color: var(--accent, #22675b);
  font-size: 0.85rem;
}
</style>
