<!-- 文件职责：在项目内创建并观察 B站 URL 采集批次，明确展示范围、完整性和可恢复失败。 -->
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { readApi, type Page } from './projectApi'
import FailureNotice from './FailureNotice.vue'

const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ imported: [] }>()
type Batch = {
  id: string
  canonicalUrl: string
  requestedSourceId: string
  collectAll: boolean
  requestedRootLimit: number
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'
  completeness: string | null
  sourceReportedComments: number | null
  collectedRootCount: number | null
  collectedCommentCount: number | null
  partialThreadCount: number | null
  snapshotId: string | null
  attempts: number
  lastErrorCode: string | null
  createdAt: string
}
type Result = {
  batch: Batch
  scopeNotes: string[]
  progress: {
    completedPages: number
    storedComments: number
    roots: number
    sourceReportedComments: number | null
    rootTraversalEnded: boolean
    nextPhase: string
  } | null
}

const url = ref('')
const rootLimit = ref(50)
const collectAll = ref(true)
const current = ref<Result | null>(null)
const history = ref<Result[]>([])
const error = ref('')
const submitting = ref(false)
let timer: number | undefined
let alive = true
let pendingRequest: { url: string; limit: number; all: boolean; key: string } | undefined
const busy = computed(
  () => submitting.value || ['PENDING', 'RUNNING'].includes(current.value?.batch.status || ''),
)

const statusText: Record<string, string> = {
  PENDING: '等待采集',
  RUNNING: '正在采集',
  SUCCESS: '已生成项目资料',
  FAILED: '采集失败',
  CANCELLED: '已取消，成功页保留',
}
const completenessText: Record<string, string> = {
  VISIBLE_EXHAUSTED: '当前可见评论已遍历并核对数量（不含隐藏或删除内容）',
  NOT_PROVEN: '未证明平台全量',
  TRUNCATED: '达到根评论上限',
  PARTIAL_FAILED: '部分回复线程未完整返回',
}
/** 创建请求只发送 URL 和根评论上限，工具路径、Cookie、速率与命令均由服务器固定。 */
async function submit() {
  if (busy.value) return
  submitting.value = true
  error.value = ''
  stopPolling()
  try {
    if (
      !pendingRequest ||
      pendingRequest.url !== url.value ||
      pendingRequest.limit !== rootLimit.value ||
      pendingRequest.all !== collectAll.value
    ) {
      pendingRequest = {
        url: url.value,
        limit: rootLimit.value,
        all: collectAll.value,
        key: crypto.randomUUID(),
      }
    }
    current.value = await readApi<Result>(
      '/api/v1/projects/' + encodeURIComponent(props.projectId) + '/collections/bilibili',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': pendingRequest.key,
        },
        body: JSON.stringify(
          collectAll.value
            ? { url: url.value, collectAll: true }
            : { url: url.value, rootLimit: rootLimit.value },
        ),
      },
    )
    await refreshCurrent()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '无法创建采集任务。'
  } finally {
    submitting.value = false
  }
}

/** 服务端状态是唯一事实来源；成功后直接返回项目资料，失败时保留批次供重试。 */
async function refreshCurrent() {
  if (!alive || !current.value) return
  stopPolling()
  try {
    const result = await readApi<Result>(
      '/api/v1/projects/' +
        encodeURIComponent(props.projectId) +
        '/collections/' +
        current.value.batch.id,
    )
    if (!alive) return
    current.value = result
    const status = result.batch.status
    if (status === 'SUCCESS') {
      await loadHistory()
      pendingRequest = undefined
      emit('imported')
    } else if (status === 'PENDING' || status === 'RUNNING') {
      timer = window.setTimeout(refreshCurrent, 1000)
    }
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '采集状态读取失败。'
  }
}

/** 历史列表让页面刷新后仍能找到批次，不把后台任务生命周期绑在浏览器页面上。 */
async function loadHistory() {
  try {
    const page = await readApi<Page<Result>>(
      '/api/v1/projects/' + encodeURIComponent(props.projectId) + '/collections?page=1&size=10',
    )
    if (alive) history.value = page.items
  } catch {
    // 主表单仍可使用；历史读取失败不覆盖当前采集的明确状态。
  }
}

/** 刷新页面后恢复最近尚未结束的批次，用户仍可从同一项目继续观察。 */
async function restore() {
  await loadHistory()
  const running = history.value.find((item) => ['PENDING', 'RUNNING'].includes(item.batch.status))
  if (alive && running) {
    current.value = running
    await refreshCurrent()
  }
}

/** 只有失败批次可重试，沿用同一批次和审计记录，不伪造一次全新的成功。 */
async function retry(item: Result) {
  if (busy.value) return
  stopPolling()
  submitting.value = true
  error.value = ''
  try {
    current.value = await readApi<Result>(
      '/api/v1/projects/' +
        encodeURIComponent(props.projectId) +
        '/collections/' +
        item.batch.id +
        '/retry',
      { method: 'POST' },
    )
    await refreshCurrent()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '重试失败。'
  } finally {
    submitting.value = false
  }
}

function stopPolling() {
  if (timer !== undefined) window.clearTimeout(timer)
  timer = undefined
}

onMounted(restore)
onUnmounted(() => {
  alive = false
  stopPolling()
})
</script>

<template>
  <section class="surface url-import">
    <div class="url-import-heading">
      <div>
        <span class="source-badge">B站公开内容</span>
        <h3>从视频链接采集评论</h3>
        <p>粘贴官方视频链接，后台采集公开评论并保存为资料，后续更新会保留旧版本。</p>
      </div>
      <span class="boundary-badge">无需 Cookie</span>
    </div>
    <form @submit.prevent="submit" class="url-import-form">
      <label class="field">
        <span>B站视频链接</span>
        <input
          v-model.trim="url"
          required
          maxlength="500"
          type="url"
          placeholder="https://www.bilibili.com/video/BV..."
        />
      </label>
      <label class="field compact-field">
        <span>采集范围</span>
        <select v-model="collectAll">
          <option :value="true">尽量采齐公开评论</option>
          <option :value="false">限量试采</option>
        </select>
      </label>
      <label v-if="!collectAll" class="field compact-field">
        <span>最多根评论</span>
        <input v-model.number="rootLimit" required min="1" max="200" type="number" />
      </label>
      <button class="primary" :disabled="busy || !url">
        {{ submitting ? '正在创建…' : '开始采集并加入项目' }}
      </button>
    </form>
    <p class="scope-copy">
      采齐模式逐页遍历根评论和楼中楼，每页保存后再继续；失败重试会复用成功页。最多5000条、512页，达到容量边界会说明原因，不冒充完成。平台隐藏或已删除评论无法保证返回。
    </p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <article v-if="current" class="collection-current" aria-live="polite">
      <div>
        <strong>{{ statusText[current.batch.status] }}</strong>
        <span
          >{{ current.batch.requestedSourceId }} · 第 {{ current.batch.attempts || 1 }} 次执行</span
        >
      </div>
      <p v-if="current.batch.status === 'SUCCESS'">
        已采集 {{ current.batch.collectedRootCount }} 条根评论、{{
          current.batch.collectedCommentCount
        }}
        条评论及回复；
        {{ completenessText[current.batch.completeness || ''] || current.batch.completeness }}。
      </p>
      <FailureNotice
        v-else-if="current.batch.status === 'FAILED'"
        :code="current.batch.lastErrorCode"
        :task-id="current.batch.id"
      />
      <p v-else>后台任务已持久化，离开页面或重启服务后仍可继续恢复。</p>
      <p v-if="current.progress">
        已保存 {{ current.progress.completedPages }} 页、{{
          current.progress.storedComments
        }}
        条评论，其中 {{ current.progress.roots }} 根；平台开始时声明
        {{ current.progress.sourceReportedComments ?? '待获取' }} 条。{{
          current.batch.status === 'SUCCESS' ? '分页已结束' : '成功页会保留，可继续恢复'
        }}。
      </p>
      <p v-if="current.batch.status === 'SUCCESS' && current.batch.sourceReportedComments !== null">
        平台声明 {{ current.batch.sourceReportedComments }} 条，实际保存
        {{ current.batch.collectedCommentCount }} 条；未对齐时只代表已保存范围，不能推断全部观众。
      </p>
      <p v-if="current.batch.status === 'CANCELLED'">
        批次已取消，尚未发布资料。可在项目“任务中心”核对检查点并继续原任务。
      </p>
      <button v-if="current.batch.status === 'FAILED'" @click="retry(current)">重试此批次</button>
      <button
        v-if="['PENDING', 'RUNNING'].includes(current.batch.status)"
        class="quiet"
        @click="refreshCurrent"
      >
        刷新状态
      </button>
    </article>

    <details v-if="history.length" class="collection-history">
      <summary>最近采集记录（{{ history.length }}）</summary>
      <div v-for="item in history" :key="item.batch.id" class="collection-history-row">
        <div>
          <strong>{{ item.batch.requestedSourceId }}</strong>
          <span>{{ item.batch.createdAt }} · {{ statusText[item.batch.status] }}</span>
        </div>
        <span v-if="item.batch.completeness" class="state-badge">
          {{ completenessText[item.batch.completeness] || item.batch.completeness }}
        </span>
        <span v-if="item.progress"
          >{{ item.progress.completedPages }} 页 · {{ item.progress.storedComments }} 条已保存</span
        >
        <details v-if="item.batch.status === 'FAILED'">
          <summary>查看失败原因</summary>
          <FailureNotice :code="item.batch.lastErrorCode" :task-id="item.batch.id" />
        </details>
        <button v-if="item.batch.status === 'FAILED'" class="quiet" @click="retry(item)">
          重试
        </button>
      </div>
    </details>
  </section>
</template>
