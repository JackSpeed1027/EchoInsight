<!-- 文件职责：让用户冻结多作品资料范围、回看不可变成员，并为版本比较及分作品分析提供入口。 -->
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import ManifestAnalysisPanel from './ManifestAnalysisPanel.vue'
import { readApi, sourceName, coverageName, type Page, type ProjectSnapshot } from './projectApi'

const props = defineProps<{ projectId: string }>()
type Summary = { id: string; name: string; itemCount: number; createdAt: string }
type Item = {
  snapshotId: string
  title: string
  sourceType: string
  sourceContentId: string
  commentCount: number
  collectionId: string | null
  sourceCompleteness: string | null
  documentSha256: string
}
type Manifest = { summary: Summary; items: Item[] }
const materials = ref<ProjectSnapshot[]>([])
const history = ref<Summary[]>([])
const selected = ref<string[]>([])
const active = ref<Manifest | null>(null)
const name = ref('本次分析资料')
const error = ref('')
const busy = ref(false)
const mode = ref<'browse' | 'create' | 'compare'>('browse')
const moreMaterials = ref(false)
type Comparison = {
  id: string
  createdAt: string
  result: {
    algorithmVersion: string
    limitations: string[]
    works: Array<{
      sourceType: string
      sourceContentId: string
      title: string
      status: string
      metadataChanged: boolean
      collectionScopeChanged: boolean
      comments: {
        added: number
        revised: number
        unknownMissing: number
        unchanged: number
        contextInvalidated: number
        contextStableCandidates: number
      } | null
    }>
  }
}
const comparison = ref<Comparison | null>(null)
const comparisons = ref<Comparison[]>([])
const baseId = ref('')
const targetId = ref('')
let comparisonSubmission: { input: string; key: string } | undefined

/** 翻译比较状态，避免把平台未返回误写为已删除。 */
function workStatus(status: string) {
  const labels: Record<string, string> = {
    ADDED_WORK: '新增作品',
    UNKNOWN_MISSING_WORK: '本次未包含该作品，去向未知',
    UNCHANGED: '输入未变',
    REVISED_WORK: '版本或采集范围已更新',
  }
  return labels[status] || status
}

/** 前后集合身份明确后计算差异；重发沿用键，比较不触发模型。 */
async function compare() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  const input = `${baseId.value}:${targetId.value}`
  if (comparisonSubmission?.input !== input)
    comparisonSubmission = { input, key: crypto.randomUUID() }
  try {
    comparison.value = await readApi<Comparison>(
      `/api/v1/projects/${props.projectId}/manifest-comparisons`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': comparisonSubmission!.key,
        },
        body: JSON.stringify({ baseManifestId: baseId.value, targetManifestId: targetId.value }),
      },
    )
    comparisons.value = [
      comparison.value,
      ...comparisons.value.filter((item) => item.id !== comparison.value?.id),
    ].slice(0, 5)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '比较失败。'
  } finally {
    busy.value = false
  }
}
let submitted: { fingerprint: string; key: string } | undefined

/** 分页拉取项目资料摘要，避免前50份以外的资料在选择界面静默消失。 */
async function load() {
  error.value = ''
  try {
    const all: ProjectSnapshot[] = []
    for (let page = 1; page <= 20; page++) {
      const result = await readApi<Page<ProjectSnapshot>>(
        `/api/v1/projects/${props.projectId}/snapshots?page=${page}&size=50`,
      )
      all.push(...result.items)
      if (page === 20 && result.totalPages > 20) moreMaterials.value = true
      if (page >= result.totalPages) break
    }
    materials.value = all
    const result = await readApi<Page<Summary>>(
      `/api/v1/projects/${props.projectId}/manifests?page=1&size=50`,
    )
    history.value = result.items
    const comparisonPage = await readApi<Page<Comparison>>(
      `/api/v1/projects/${props.projectId}/manifest-comparisons?page=1&size=5`,
    )
    comparisons.value = comparisonPage.items
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '资料集合读取失败。'
  }
}

/** 明确选择后才冻结；网络重发同一请求键，新增材料不会改变已有清单。 */
async function freeze() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  const fingerprint = JSON.stringify([name.value.trim(), [...selected.value].sort()])
  if (submitted?.fingerprint !== fingerprint) submitted = { fingerprint, key: crypto.randomUUID() }
  try {
    active.value = await readApi<Manifest>(`/api/v1/projects/${props.projectId}/manifests`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': submitted!.key },
      body: JSON.stringify({ name: name.value.trim(), snapshotIds: selected.value }),
    })
    await load()
    mode.value = 'browse'
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '冻结范围失败。'
  } finally {
    busy.value = false
  }
}

/** 历史清单读冻结字段，不按当前最新采集重新解释。 */
async function open(id: string) {
  mode.value = 'browse'
  error.value = ''
  try {
    active.value = await readApi<Manifest>(`/api/v1/projects/${props.projectId}/manifests/${id}`)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '集合读取失败。'
  }
}
onMounted(load)

/** 返回集合列表时卸载当前分析视图，停止其页面轮询，后台业务继续执行。 */
function browse() {
  mode.value = 'browse'
  active.value = null
}
</script>

<template>
  <section class="insight-workbench">
    <div class="section-toolbar">
      <div>
        <h2>资料集合</h2>
        <p>把本次选中的作品版本固定下来，方便分析、对比和复现。</p>
      </div>
      <button @click="load">刷新</button>
    </div>
    <nav class="project-tabs" aria-label="集合操作">
      <button :class="{ selected: mode === 'browse' }" @click="browse">我的集合</button>
      <button :class="{ selected: mode === 'create' }" @click="mode = 'create'">新建集合</button>
      <button :class="{ selected: mode === 'compare' }" @click="mode = 'compare'">更新对比</button>
    </nav>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <form v-if="mode === 'create'" class="surface manifest-composer" @submit.prevent="freeze">
      <label class="field">集合名称<input v-model="name" maxlength="120" required /></label>
      <p class="small">选择1～20份资料。同一作品只选一个版本；比较更新时分别建立前后两份集合。</p>
      <div class="manifest-options">
        <label v-for="item in materials" :key="item.snapshotId" class="manifest-option">
          <input v-model="selected" :value="item.snapshotId" type="checkbox" />
          <span
            ><strong>{{ item.title }}</strong
            ><small
              >{{ sourceName(item.sourceType) }} · {{ item.commentCount }}条 ·
              {{ item.snapshotCreatedAt
              }}<template v-if="item.collectionId">
                · {{ coverageName(item.sourceCompleteness) }}</template
              ></small
            ></span
          >
        </label>
      </div>
      <p v-if="materials.length === 0">项目还没有资料，请先添加链接或文件。</p>
      <p v-if="moreMaterials" class="small">
        当前只显示前1000个版本，请缩小项目范围后选择；不会替你自动选择。
      </p>
      <div class="composer-actions">
        <span>已选 {{ selected.length }} / 20</span
        ><button class="primary" :disabled="busy || !selected.length || selected.length > 20">
          冻结本次范围
        </button>
      </div>
    </form>
    <section v-if="mode === 'browse' && active" class="surface">
      <h3>{{ active.summary.name }}</h3>
      <p class="small">
        {{ active.items.length }}个作品 ·
        {{ active.summary.createdAt }}保存。此清单不自动追随最新资料。
      </p>
      <article v-for="item in active.items" :key="item.snapshotId" class="collection-history-row">
        <div>
          <strong>{{ item.title }}</strong
          ><span>{{ item.commentCount }}条评论 · {{ coverageName(item.sourceCompleteness) }}</span>
          <details class="metadata-detail">
            <summary>版本与来源</summary>
            <p>
              平台：{{ sourceName(item.sourceType) }}<br />来源作品：{{ item.sourceContentId
              }}<br />快照：{{ item.snapshotId }}<br />原文指纹：{{ item.documentSha256 }}
            </p>
          </details>
        </div>
      </article>
    </section>
    <ManifestAnalysisPanel
      v-if="mode === 'browse' && active"
      :key="active.summary.id"
      :project-id="projectId"
      :manifest-id="active.summary.id"
      :item-count="active.items.length"
    />
    <section v-if="mode === 'browse' && !active" class="surface">
      <h3>最近保存的集合</h3>
      <p class="small">显示最近50份。打开一份集合即可提问；新建集合只冻结范围，不触发模型。</p>
      <button
        v-for="item in history"
        :key="item.id"
        class="request-history-item"
        @click="open(item.id)"
      >
        <span>{{ item.name }} · {{ item.itemCount }}个作品</span><small>{{ item.createdAt }}</small>
      </button>
      <p v-if="!history.length" class="small">还没有冻结的集合。</p>
    </section>
    <section v-if="mode === 'compare'" class="surface manifest-composer">
      <h3>比较资料更新</h3>
      <p class="small">
        分别选择以前和现在的集合，查看新增、修订与未知缺失；不自动删除或重跑模型。
      </p>
      <form class="url-import-form" @submit.prevent="compare">
        <label class="field"
          >以前<select v-model="baseId" required>
            <option value="">选择原集合</option>
            <option v-for="item in history" :key="item.id" :value="item.id">
              {{ item.name }} · {{ item.createdAt }}
            </option>
          </select></label
        ><label class="field"
          >现在<select v-model="targetId" required>
            <option value="">选择目标集合</option>
            <option v-for="item in history" :key="item.id" :value="item.id">
              {{ item.name }} · {{ item.createdAt }}
            </option>
          </select></label
        ><button :disabled="busy || !baseId || !targetId">计算更新差异</button>
      </form>
      <div v-if="comparison" class="manifest-diff">
        <article
          v-for="work in comparison.result.works"
          :key="work.sourceType + work.sourceContentId"
          class="issue-card"
        >
          <strong>{{ work.title }}</strong>
          <p>{{ workStatus(work.status) }}</p>
          <p v-if="work.comments">
            新增 {{ work.comments.added }} · 修订 {{ work.comments.revised }} · 未知缺失
            {{ work.comments.unknownMissing }} · 自身未变 {{ work.comments.unchanged }} ·
            其中父上下文失效 {{ work.comments.contextInvalidated }}
          </p>
          <p v-if="work.metadataChanged" class="small">
            作品元数据或字幕发生变化，整体结果需重新核对。
          </p>
          <p v-if="work.collectionScopeChanged" class="small">采集来源或完整性声明发生变化。</p>
        </article>
        <details class="analysis-limits">
          <summary>如何理解这些差异</summary>
          <p v-for="note in comparison.result.limitations" :key="note" class="small">{{ note }}</p>
        </details>
      </div>
      <details v-if="comparisons.length" class="collection-history">
        <summary>最近比较记录</summary>
        <button
          v-for="item in comparisons"
          :key="item.id"
          class="request-history-item"
          @click="comparison = item"
        >
          <span>{{ item.createdAt }}</span
          ><small>{{ item.result.works.length }}个来源作品</small>
        </button>
      </details>
    </section>
  </section>
</template>
