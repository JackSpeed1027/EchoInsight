<!-- 文件职责：把一个项目的资料、导入、历史和报告拆成明确页面，分析绑定指定资料版本。 -->
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import {
  readApi,
  sourceName,
  jobStatus,
  coverageName,
  type Project,
  type ProjectSnapshot,
  type Page,
} from './projectApi'
import ImportPanel from './ImportPanel.vue'
import MediaCrawlerImport from './MediaCrawlerImport.vue'
import BilibiliUrlImport from './BilibiliUrlImport.vue'
import CreatorWorkflow from './CreatorWorkflow.vue'
import ReferenceLibrary from './ReferenceLibrary.vue'
import TranscriptLibrary from './TranscriptLibrary.vue'
import ManifestPanel from './ManifestPanel.vue'
import AnalysisPanel from './AnalysisPanel.vue'
import JobHistory from './JobHistory.vue'
import IntelligentAnalysis from './IntelligentAnalysis.vue'
import ProjectTaskCenter from './ProjectTaskCenter.vue'
import ContentWorkspace from './ContentWorkspace.vue'
const props = defineProps<{
  projectId: string
  tab: string
  snapshotId?: string
  jobId?: string
  requestId?: string
  fresh?: boolean
}>()
const emit = defineEmits<{
  back: []
  tab: [tab: string]
  analysis: [snapshotId: string, jobId?: string, fresh?: boolean]
  insight: [snapshotId: string]
  request: [snapshotId: string, requestId: string]
  content: [jobId: string]
}>()
const project = ref<Project | null>(null)
const materials = ref<Page<ProjectSnapshot> | null>(null)
const selected = ref<ProjectSnapshot | null>(null)
const error = ref('')
const summaryError = ref('')
const loading = ref(false)
const page = ref(1)
const importKind = ref('url')
let generation = 0

/** 先读取项目资格，分析页另核对指定快照成员，避免 URL 把其他资料混入当前项目。 */
async function load() {
  const version = ++generation
  loading.value = true
  error.value = ''
  summaryError.value = ''
  selected.value = null
  try {
    const summary = await readApi<Project>(
      '/api/v1/projects/' + encodeURIComponent(props.projectId),
    )
    if (version !== generation) return
    project.value = summary
    if (props.tab === 'analysis') {
      let snapshot = props.snapshotId
      if (props.jobId) {
        const job = await readApi<{ snapshotId: string }>(
          '/api/v1/analysis-jobs/' + encodeURIComponent(props.jobId),
        )
        snapshot = job.snapshotId
      }
      if (!snapshot) throw new Error('请先从项目中选择一份资料。')
      const member = await readApi<ProjectSnapshot>(
        '/api/v1/projects/' +
          encodeURIComponent(props.projectId) +
          '/snapshots/' +
          encodeURIComponent(snapshot),
      )
      if (version === generation) selected.value = member
    } else if (props.tab === 'materials') {
      const result = await readApi<Page<ProjectSnapshot>>(
        '/api/v1/projects/' +
          encodeURIComponent(props.projectId) +
          '/snapshots?page=' +
          page.value +
          '&size=12',
      )
      if (version === generation) materials.value = result
    }
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '项目读取失败。'
  } finally {
    if (version === generation) loading.value = false
  }
}
/** 内容另存/接受后只刷新项目摘要，不卸载子页面，也不清空正在填写的问题。 */
async function refreshSummary() {
  const version = generation
  summaryError.value = ''
  try {
    const value = await readApi<Project>('/api/v1/projects/' + encodeURIComponent(props.projectId))
    if (version === generation) project.value = value
  } catch (caught) {
    if (version === generation)
      summaryError.value = caught instanceof Error ? caught.message : '项目数量更新失败，请重试。'
  }
}
/** 新导入已由服务端事务归档，返回资料列表即可，不再在表单下堆一套报告。 */
function imported() {
  page.value = 1
  emit('tab', 'materials')
}
/** 选择补充页面后收起菜单，保留原浏览器路由并避免菜单挡住报告。 */
function secondaryTab(next: string, event: Event) {
  const target = event.currentTarget as HTMLElement
  const menu = target.closest('details')
  if (menu) menu.open = false
  emit('tab', next)
}
/** 页码只改变当前项目读取范围，不改变项目成员或历史版本。 */
function turn(next: number) {
  page.value = next
  void load()
}
watch(
  () => [props.projectId, props.tab, props.snapshotId, props.jobId, props.fresh],
  () => {
    page.value = 1
    void load()
  },
  { immediate: true },
)
onUnmounted(() => {
  generation++
})
</script>

<template>
  <section>
    <button class="back-link" @click="emit('back')">← 我的项目</button>
    <div class="page-heading" v-if="project">
      <div>
        <p class="eyebrow">PROJECT</p>
        <h1>{{ project.name }}</h1>
        <p class="goal-preview">{{ project.analysisGoal }}</p>
      </div>
      <button
        class="primary"
        v-if="tab !== 'import' && tab !== 'analysis'"
        @click="emit('tab', 'import')"
      >
        ＋ 添加资料
      </button>
    </div>
    <div v-if="project" class="project-summary">
      <span
        ><b>{{ project.workCount }}</b> 个作品</span
      ><span
        ><b>{{ project.snapshotCount }}</b> 份资料版本</span
      >
      <span
        ><b>{{ project.jobCount }}</b> 次分析</span
      ><span class="summary-hint">资料按版本保存</span>
      <span
        ><b>{{ project.contentMaterialCount }}</b> 份内容材料</span
      >
    </div>
    <p v-if="summaryError" role="alert" class="error">
      {{ summaryError }} <button @click="refreshSummary">刷新项目数量</button>
    </p>
    <!-- 主导航只保留日常路径；集合与早期反馈报告按需展开，旧URL仍可直接访问。 -->
    <div class="project-navigation">
      <nav class="project-tabs" aria-label="项目页面">
        <button
          :class="{ selected: tab === 'materials' || tab === 'import' }"
          :aria-current="tab === 'materials' || tab === 'import' ? 'page' : undefined"
          @click="emit('tab', 'materials')"
        >
          资料与作品
        </button>
        <button
          :class="{ selected: tab === 'insight' }"
          :aria-current="tab === 'insight' ? 'page' : undefined"
          @click="emit('tab', 'insight')"
        >
          智能分析
        </button>
        <button
          :class="{ selected: tab === 'content' }"
          :aria-current="tab === 'content' ? 'page' : undefined"
          @click="emit('tab', 'content')"
        >
          文本与字幕
        </button>
        <button
          :class="{ selected: tab === 'tasks' }"
          :aria-current="tab === 'tasks' ? 'page' : undefined"
          @click="emit('tab', 'tasks')"
        >
          任务中心
        </button>
      </nav>
      <details class="project-more">
        <summary>
          更多 <span v-if="['sets', 'history', 'goal', 'analysis'].includes(tab)">· 当前</span>
        </summary>
        <nav aria-label="项目补充页面">
          <button :class="{ selected: tab === 'sets' }" @click="secondaryTab('sets', $event)">
            资料集合
          </button>
          <button
            :class="{ selected: tab === 'history' || tab === 'analysis' }"
            @click="secondaryTab('history', $event)"
          >
            早期反馈报告
          </button>
          <button :class="{ selected: tab === 'goal' }" @click="secondaryTab('goal', $event)">
            项目目标
          </button>
        </nav>
      </details>
    </div>
    <p v-if="loading" role="status">正在读取项目…</p>
    <p v-if="error" role="alert" class="error">{{ error }} <button @click="load">重试</button></p>
    <template v-if="!loading && !error && project">
      <template v-if="tab === 'materials'">
        <div class="section-toolbar">
          <h2>资料与作品</h2>
          <button class="quiet" @click="load">刷新资料</button>
        </div>
        <div v-if="!materials?.total" class="empty-state">
          <div class="empty-symbol">＋</div>
          <h2>先为项目添加一份资料</h2>
          <p>添加 B站链接或评论导出文件，再围绕问题分析；没有评论也可以上传文本与字幕。</p>
          <button class="primary" @click="emit('tab', 'import')">添加第一份资料</button>
          <button class="quiet" @click="emit('tab', 'content')">没有评论，上传文本或字幕</button>
        </div>
        <div class="material-grid" v-else>
          <article class="material-card" v-for="item in materials.items" :key="item.snapshotId">
            <div class="material-top">
              <span class="source-badge">{{ sourceName(item.sourceType) }}</span>
              <span
                class="state-badge"
                v-if="item.latestJobStatus"
                :class="item.latestJobStatus?.toLowerCase()"
                >历史反馈：{{ jobStatus(item.latestJobStatus) }}</span
              >
            </div>
            <h3>{{ item.title }}</h3>
            <p>
              {{ item.commentCount }} 条评论 · 保存于
              {{ item.snapshotCreatedAt }}
            </p>
            <p v-if="item.collectionId" class="scope-copy">
              {{ coverageName(item.sourceCompleteness) }} · 平台声明
              {{ item.sourceReportedComments ?? '未知' }} 条（仅供参考）
            </p>
            <details class="metadata-detail">
              <summary>资料版本信息</summary>
              <p>
                来源作品：{{ item.sourceContentId }}<br />快照：{{ item.snapshotId
                }}<br />加入项目：{{ item.addedAt }}
              </p>
            </details>
            <div class="material-actions">
              <button class="primary" @click="emit('insight', item.snapshotId)">
                向这份资料提问
              </button>
              <button
                v-if="item.latestJobId"
                class="quiet"
                @click="emit('analysis', item.snapshotId, item.latestJobId)"
              >
                查看历史反馈报告
              </button>
            </div>
          </article>
        </div>
        <div class="pagination" v-if="materials && materials.totalPages > 1">
          <button :disabled="page <= 1" @click="turn(page - 1)">上一资料页</button
          ><span>{{ page }} / {{ materials.totalPages }}</span>
          <button :disabled="page >= materials.totalPages" @click="turn(page + 1)">
            下一资料页
          </button>
        </div>
      </template>
      <ContentWorkspace
        v-else-if="tab === 'content'"
        :project-id="projectId"
        :initial-job-id="jobId"
        @updated="refreshSummary"
      />
      <ProjectTaskCenter
        v-else-if="tab === 'tasks'"
        :project-id="projectId"
        @request="(snapshot, request) => emit('request', snapshot, request)"
        @analysis="(snapshot, job) => emit('analysis', snapshot, job)"
        @materials="emit('tab', 'materials')"
        @content="(job) => emit('content', job)"
      />
      <JobHistory
        v-else-if="tab === 'history'"
        :project-id="projectId"
        @open="(item) => emit('analysis', item.snapshotId, item.id)"
      />
      <IntelligentAnalysis
        v-else-if="tab === 'insight'"
        :project-id="projectId"
        :preferred-snapshot-id="snapshotId"
        :initial-request-id="requestId"
      />
      <ManifestPanel v-else-if="tab === 'sets'" :key="projectId" :project-id="projectId" />
      <section v-else-if="tab === 'goal'" class="surface goal-surface">
        <span class="source-badge">复盘目标</span>
        <h2>{{ project.name }}</h2>
        <p>{{ project.analysisGoal }}</p>
        <p class="small">每次分析保留明确的资料版本。后续补充资料不会改写旧报告。</p>
      </section>
      <section v-else-if="tab === 'import'">
        <div class="section-toolbar">
          <div>
            <h2>添加资料</h2>
            <p>导入后自动保存在当前项目。</p>
          </div>
          <button class="quiet" @click="emit('tab', 'materials')">返回资料</button>
        </div>
        <div class="import-choices" role="group" aria-label="资料导入方式">
          <button :class="{ selected: importKind === 'url' }" @click="importKind = 'url'">
            <strong>B站视频链接</strong><span>输入 URL，自动采集公开评论</span>
          </button>
          <button :class="{ selected: importKind === 'creator' }" @click="importKind = 'creator'">
            <strong>UP主作品</strong><span>按关键词找作品，再批量采集讨论</span>
          </button>
          <button :class="{ selected: importKind === 'platform' }" @click="importKind = 'platform'">
            <strong>平台导出文件</strong><span>B站 / 小红书作品和评论 JSON</span>
          </button>
          <button
            :class="{ selected: importKind === 'reference' }"
            @click="importKind = 'reference'"
          >
            <strong>参考文本</strong><span>背景笔记 / UTF8文本，独立于评论保存</span>
          </button>
          <button
            :class="{ selected: importKind === 'transcript' }"
            @click="importKind = 'transcript'"
          >
            <strong>字幕文件</strong><span>SRT / VTT，保留原文和视频时间</span>
          </button>
          <button :class="{ selected: importKind === 'local' }" @click="importKind = 'local'">
            <strong>本地标准资料</strong><span>已有标准格式的评论与字幕 JSON</span>
          </button>
        </div>
        <BilibiliUrlImport
          v-if="importKind === 'url'"
          :project-id="projectId"
          @imported="imported"
        />
        <CreatorWorkflow
          v-else-if="importKind === 'creator'"
          :key="projectId"
          :project-id="projectId"
          @frozen="emit('tab', 'sets')"
        />
        <MediaCrawlerImport
          v-else-if="importKind === 'platform'"
          :project-id="projectId"
          @imported="imported"
        />
        <ReferenceLibrary
          v-else-if="importKind === 'reference'"
          :key="projectId"
          :project-id="projectId"
          @analyze="emit('tab', 'insight')"
        />
        <TranscriptLibrary
          v-else-if="importKind === 'transcript'"
          :project-id="projectId"
          :snapshot-id="snapshotId"
        />
        <ImportPanel v-else :project-id="projectId" @imported="imported" />
      </section>
      <section v-else-if="tab === 'analysis' && selected">
        <div class="section-toolbar">
          <div>
            <span class="source-badge">{{ sourceName(selected.sourceType) }}</span>
            <h2 class="analysis-title">{{ selected.title }}</h2>
          </div>
          <button class="primary" @click="emit('insight', selected.snapshotId)">
            使用智能分析提问
          </button>
          <button @click="emit('tab', 'materials')">返回项目资料</button>
        </div>
        <AnalysisPanel
          :key="selected.snapshotId + ':' + (jobId || '') + ':' + fresh"
          :snapshot-id="selected.snapshotId"
          :initial-job-id="jobId"
          :fresh="fresh"
        />
      </section>
    </template>
  </section>
</template>
