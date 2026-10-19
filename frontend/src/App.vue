<!-- 文件职责：项目化工作台壳层与浏览器导航；主流程、全局高级记录和旧报告链接分开。 -->
<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import ProjectHome from './ProjectHome.vue'
import ProjectWorkspace from './ProjectWorkspace.vue'
import StandaloneAnalysis from './StandaloneAnalysis.vue'
import JobHistory from './JobHistory.vue'
import ImportPanel from './ImportPanel.vue'
import MediaCrawlerImport from './MediaCrawlerImport.vue'

/** URL 只保存导航身份，不保存正文、凭据、目标配置或完整分析状态。 */
type Route = {
  view: string
  project?: string
  tab: string
  snapshot?: string
  job?: string
  request?: string
  fresh: boolean
}
/** 后端公开的安全运行摘要；响应刻意不包含密钥、供应商地址或账户余额。 */
type ModelStatus = {
  mode: string
  providerId: string
  modelVersion: string
  unit: string
  taskBudgetUnits: number
  taskBudgetUsd: string
}
/** 1、读取项目与任务身份；2、限定可用页面；3、恢复已有导航，不据URL自动发起分析。 */
function readRoute(): Route {
  const p = new URLSearchParams(window.location.search)
  const project = p.get('project') || undefined
  const job = p.get('job') || undefined
  const view = project
    ? 'project'
    : job
      ? 'report'
      : p.get('view') === 'archive'
        ? 'archive'
        : p.get('view') === 'guide'
          ? 'guide'
          : 'projects'
  const tab = p.get('tab') || 'materials'
  return {
    view,
    project,
    job,
    request: p.get('request') || undefined,
    tab: [
      'materials',
      'sets',
      'insight',
      'tasks',
      'content',
      'history',
      'goal',
      'import',
      'analysis',
    ].includes(tab)
      ? tab
      : 'materials',
    snapshot: p.get('snapshot') || undefined,
    fresh: p.get('fresh') === '1',
  }
}
const route = ref<Route>(readRoute())
const connected = ref(false),
  checking = ref(false),
  mode = ref(''),
  modelStatus = ref<ModelStatus | null>(null)
const legacyTab = ref('history'),
  legacyKind = ref('platform')

/** 新页面记录浏览器历史，前进/返回恢复同一项目或报告，不创建新的业务任务。 */
function navigate(values: Record<string, string | undefined>) {
  const url = new URL(window.location.href)
  url.search = ''
  url.hash = ''
  for (const [key, value] of Object.entries(values)) if (value) url.searchParams.set(key, value)
  window.history.pushState(null, '', url)
  route.value = readRoute()
  window.scrollTo({ top: 0 })
}
/** 选择项目默认打开资料页面，UUID 不作为主要导航文本。 */
function openProject(id: string) {
  navigate({ project: id })
}
/** 项目标签保留项目身份，分析是独立视图。 */
function openTab(tab: string) {
  navigate({ project: route.value.project, tab })
}
/** 指定资料和可选已有任务；新建分析明确忽略旧浏览器任务缓存。 */
function openAnalysis(snapshot: string, job?: string, fresh?: boolean) {
  navigate({
    project: route.value.project,
    tab: 'analysis',
    snapshot,
    job,
    fresh: fresh ? '1' : undefined,
  })
}
/** 从资料卡片进入自然语言入口，明确带上本次分析对象。 */
function openInsight(snapshot: string) {
  navigate({ project: route.value.project, tab: 'insight', snapshot })
}
/** 从任务中心恢复明确的原请求；浏览器返回仍可回到任务列表。 */
function openTaskRequest(snapshot: string, request: string) {
  navigate({ project: route.value.project, tab: 'insight', snapshot, request })
}
/** 从任务中心打开独立内容任务，job仍为原任务身份，不能走评论报告分支。 */
function openContent(job: string) {
  navigate({ project: route.value.project, tab: 'content', job })
}
/** 兼容早期全局记录，不让旧分享链接失效，也不自动归到一个项目。 */
function openLegacy(item: { id: string }) {
  navigate({ job: item.id })
}
/** 连接状态缩成页头提示；分析模式只代表新任务，历史报告仍读自己的来源。 */
async function check() {
  if (checking.value) return
  checking.value = true
  try {
    const [health, status] = await Promise.all([
      fetch('/actuator/health'),
      fetch('/api/v1/model/status'),
    ])
    connected.value = health.ok && (await health.json()).status === 'UP'
    modelStatus.value = status.ok ? ((await status.json()) as ModelStatus) : null
    mode.value = modelStatus.value?.mode || ''
  } catch {
    connected.value = false
    mode.value = ''
    modelStatus.value = null
  } finally {
    checking.value = false
  }
}
/** 将后端精确小数压缩成页面金额；非法值保留原文，避免前端臆造预算。 */
function budgetUsd(value: string) {
  const amount = Number(value)
  return Number.isFinite(amount) ? amount.toString() : value
}
/** 浏览器返回不追加新的历史项，旧请求由子页面卸载保护。 */
function pop() {
  route.value = readRoute()
}
onMounted(() => {
  window.addEventListener('popstate', pop)
  void check()
})
onUnmounted(() => window.removeEventListener('popstate', pop))
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="/" @click.prevent="navigate({})"
        ><span class="brand-mark">E</span><span>EchoInsight</span></a
      >
      <div class="workspace-identity">
        <span class="avatar">本</span>
        <div><strong>个人工作空间</strong><small>本机资料 · 持续复盘</small></div>
      </div>
      <p class="nav-label">工作台</p>
      <nav aria-label="主导航">
        <a
          href="/"
          :class="{
            active: route.view === 'projects' || route.view === 'project',
          }"
          @click.prevent="navigate({})"
          ><span>▦</span> 我的项目</a
        >
        <a
          href="?view=archive"
          :class="{ active: route.view === 'archive' }"
          @click.prevent="navigate({ view: 'archive' })"
          ><span>▤</span> 历史与高级</a
        >
        <a
          href="?view=guide"
          :class="{ active: route.view === 'guide' }"
          @click.prevent="navigate({ view: 'guide' })"
          ><span>?</span> 模型与说明</a
        >
      </nav>
      <div class="sidebar-bottom">
        <strong>资料有版本，结论有依据。</strong>
        <p>从一个项目开始，逐步整理反馈。</p>
        <span>EchoInsight · 本地工作台</span>
      </div>
    </aside>
    <main class="app-main">
      <header class="app-header">
        <div class="breadcrumb">
          工作台 <span>/</span>
          {{
            route.view === 'archive'
              ? '历史与高级'
              : route.view === 'guide'
                ? '模型与说明'
                : route.view === 'report'
                  ? '分析详情'
                  : route.view === 'project'
                    ? '项目空间'
                    : '我的项目'
          }}
        </div>
        <div class="header-status">
          <span class="mode-badge">{{
            mode === 'MOCK' ? '免费演示' : mode === 'MODEL' ? '模型已启用' : '分析模式待确认'
          }}</span>
          <button class="connection-mini" @click="check" :disabled="checking">
            <i :class="{ online: connected }"></i>{{ connected ? '服务已连接' : '检查连接' }}
          </button>
        </div>
      </header>
      <div class="page-content">
        <ProjectHome v-if="route.view === 'projects'" @open="openProject" />
        <ProjectWorkspace
          v-else-if="route.view === 'project' && route.project"
          :key="route.project"
          :project-id="route.project"
          :tab="route.tab"
          :snapshot-id="route.snapshot"
          :job-id="route.job"
          :request-id="route.request"
          :fresh="route.fresh"
          @back="navigate({})"
          @tab="openTab"
          @analysis="openAnalysis"
          @insight="openInsight"
          @request="openTaskRequest"
          @content="openContent"
        />
        <StandaloneAnalysis
          v-else-if="route.view === 'report' && route.job"
          :key="route.job"
          :job-id="route.job"
          @home="navigate({})"
        />
        <section v-else-if="route.view === 'archive'">
          <div class="page-heading">
            <div>
              <p class="eyebrow">ARCHIVE</p>
              <h1>历史与高级</h1>
              <p>早期全局记录和独立导入保留在这里，日常分析建议从项目进入。</p>
            </div>
          </div>
          <div class="workspace-note archive-note">
            <div>
              <strong>这里包含演示和验收记录</strong>
              <p>
                这些历史数据没有删除，也不会自动加入你的项目。项目内只显示明确添加的资料与相关分析。
              </p>
            </div>
          </div>
          <nav class="project-tabs" aria-label="高级功能">
            <button :class="{ selected: legacyTab === 'history' }" @click="legacyTab = 'history'">
              全局分析记录</button
            ><button :class="{ selected: legacyTab === 'import' }" @click="legacyTab = 'import'">
              独立导入
            </button>
          </nav>
          <JobHistory v-if="legacyTab === 'history'" @open="openLegacy" />
          <template v-else
            ><div class="import-choices">
              <button
                :class="{ selected: legacyKind === 'platform' }"
                @click="legacyKind = 'platform'"
              >
                平台导出文件</button
              ><button :class="{ selected: legacyKind === 'local' }" @click="legacyKind = 'local'">
                标准 JSON
              </button>
            </div>
            <MediaCrawlerImport v-if="legacyKind === 'platform'" /><ImportPanel v-else
          /></template>
        </section>
        <section v-else class="guide-page">
          <div class="page-heading">
            <div>
              <p class="eyebrow">QUICK START</p>
              <h1>让复盘有清楚的起点</h1>
              <p>先确定问题，再组织资料和分析结果。</p>
            </div>
            <button class="primary" @click="navigate({})">回到我的项目</button>
          </div>
          <div class="guide-grid">
            <article class="surface">
              <span class="step-number">01</span>
              <h2>创建项目</h2>
              <p>
                用一个主题组织相关作品，例如某个合集或一段时间的内容复盘。填写你真正想了解的问题。
              </p>
            </article>
            <article class="surface">
              <span class="step-number">02</span>
              <h2>添加资料</h2>
              <p>
                导入 B站/小红书作品与评论 JSON，或已有的本地标准
                JSON。新版本继续加进项目，旧版本保留。
              </p>
            </article>
            <article class="surface">
              <span class="step-number">03</span>
              <h2>分析与回看</h2>
              <p>
                从资料卡片开始分析或打开报告。原文、执行细节、人工核验按需展开，项目内保留历次记录。
              </p>
            </article>
          </div>
          <div class="surface guide-limit">
            <h2>当前可用范围</h2>
            <p>
              自然语言讨论分析已经可用：系统会绑定项目资料、整理议题和观点、比较回应关系，并让结论回到原评论依据。未启用模型时只做结构演示，不会伪装成语义结论。
            </p>
            <p>
              当前稳定入口是 B站/小红书导出 JSON 与标准 JSON。URL 在线采集、视频/PDF/Word
              等资料和自动增量仍在后续开发中。
            </p>
          </div>
          <section class="surface model-settings" aria-labelledby="model-settings-title">
            <div class="model-settings-heading">
              <div>
                <p class="eyebrow">MODEL &amp; API</p>
                <h2 id="model-settings-title">模型与 API</h2>
              </div>
              <span
                class="state-badge"
                :class="modelStatus?.mode === 'MODEL' ? 'success' : 'pending'"
              >
                {{ modelStatus?.mode === 'MODEL' ? '已启用真实模型' : '当前为免费演示' }}
              </span>
            </div>
            <div v-if="modelStatus" class="model-status-grid">
              <div>
                <small>运行模式</small><strong>{{ modelStatus.mode }}</strong>
              </div>
              <div>
                <small>模型供应商</small><strong>{{ modelStatus.providerId }}</strong>
              </div>
              <div>
                <small>模型版本</small><strong>{{ modelStatus.modelVersion }}</strong>
              </div>
              <div>
                <small>单任务预算上限</small>
                <strong>{{
                  modelStatus.mode === 'MODEL'
                    ? '$' + budgetUsd(modelStatus.taskBudgetUsd)
                    : '不产生模型费用'
                }}</strong>
              </div>
            </div>
            <p v-else class="model-status-empty">连接后可读取当前模型运行状态。</p>
            <div class="security-note">
              <strong>为什么这里没有密钥输入框？</strong>
              <p>
                API Key 属于后端凭据，只保存在本机的
                <code>runtime/application-local.properties</code>。
                浏览器只读取不含密钥的运行摘要，避免密钥进入页面源码、浏览器缓存或网络调试记录。
              </p>
            </div>
            <button class="quiet" type="button" :disabled="checking" @click="check">
              {{ checking ? '正在读取…' : '刷新模型状态' }}
            </button>
          </section>
        </section>
      </div>
      <footer class="app-footer">
        <span>EchoInsight</span><span>每次分析都能回到原始资料。</span>
      </footer>
    </main>
  </div>
</template>
