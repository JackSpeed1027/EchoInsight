<!-- 文件职责：以项目卡片和明确的新建入口取代长页面，让用户先选择长期复盘主题。 -->
<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { readApi, type Page, type Project } from './projectApi'
const emit = defineEmits<{ open: [id: string] }>()
const data = ref<Page<Project> | null>(null)
const error = ref('')
const loading = ref(false)
const page = ref(1)
const dialog = ref<HTMLDialogElement | null>(null)
const name = ref('')
const goal = ref('')
const creating = ref(false)
const createError = ref('')
let key = crypto.randomUUID()
let generation = 0

/** 分页只读取当前服务器范围的项目，不拉取所有历史任务或正文。 */
async function load(next = page.value) {
  const version = ++generation
  page.value = next
  loading.value = true
  error.value = ''
  try {
    const result = await readApi<Page<Project>>('/api/v1/projects?page=' + next + '&size=12')
    if (version === generation) data.value = result
  } catch (caught) {
    if (version === generation)
      error.value = caught instanceof Error ? caught.message : '项目读取失败。'
  } finally {
    if (version === generation) loading.value = false
  }
}
/** 原生模态框提供焦点和 Escape 支持，创建时防止关闭导致重复逻辑请求。 */
function openCreate() {
  name.value = ''
  goal.value = ''
  key = crypto.randomUUID()
  createError.value = ''
  dialog.value?.showModal()
}
/** 1、验证；2、幂等创建；3、进入项目；失败保留表单及请求键供重试。 */
async function create() {
  if (creating.value) return
  if (!name.value.trim() || !goal.value.trim()) {
    createError.value = '请填写项目名称和你想解决的问题。'
    return
  }
  creating.value = true
  createError.value = ''
  try {
    const result = await readApi<Project>('/api/v1/projects', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
      body: JSON.stringify({ name: name.value.trim(), analysisGoal: goal.value.trim() }),
    })
    dialog.value?.close()
    emit('open', result.id)
  } catch (caught) {
    createError.value = caught instanceof Error ? caught.message : '创建失败。'
  } finally {
    creating.value = false
  }
}
/** 用户修改逻辑内容后使用新请求键；网络失败后原样重试仍能复用旧结果。 */
watch([name, goal], () => {
  key = crypto.randomUUID()
})
onMounted(() => {
  void load()
})
onUnmounted(() => {
  generation++
})
</script>

<template>
  <section aria-labelledby="projects-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">WORKSPACE</p>
        <h1 id="projects-title">我的项目</h1>
        <p>把相关作品和资料放在一起，保留每一次分析与证据。</p>
      </div>
      <button class="primary" @click="openCreate">＋ 新建项目</button>
    </div>
    <div class="workspace-note">
      <div class="note-icon">↗</div>
      <div>
        <strong>从一个具体问题开始</strong>
        <p>例如“这个合集的观众在讨论什么？” 创建项目后添加资料，再查看分析。</p>
      </div>
      <span class="badge">本机工作空间</span>
    </div>
    <div class="section-toolbar">
      <h2>
        项目 <span v-if="data" class="count">{{ data.total }}</span>
      </h2>
      <button class="quiet" @click="load()" :disabled="loading">刷新项目</button>
    </div>
    <p v-if="error" role="alert" class="error">
      {{ error }} <button @click="load()">重新读取</button>
    </p>
    <p v-if="loading && !data" role="status">正在读取项目…</p>
    <div v-else-if="data && !data.total" class="empty-state">
      <div class="empty-symbol">▦</div>
      <h2>开始你的第一个复盘项目</h2>
      <p>项目会保存资料版本和分析记录。你不需要先了解任务编号或执行步骤。</p>
      <button class="primary" @click="openCreate">创建项目</button>
    </div>
    <div v-else class="project-grid">
      <button
        v-for="item in data?.items || []"
        :key="item.id"
        class="project-card"
        @click="emit('open', item.id)"
      >
        <div class="project-card-top">
          <span class="project-icon">▦</span><span class="card-arrow">↗</span>
        </div>
        <h3>{{ item.name }}</h3>
        <p class="project-goal">{{ item.analysisGoal }}</p>
        <div class="project-card-stats">
          <span
            ><b>{{ item.workCount }}</b> 个作品</span
          >
          <span
            ><b>{{ item.snapshotCount }}</b> 份资料版本</span
          ><span
            ><b>{{ item.jobCount }}</b> 次分析</span
          >
        </div>
        <div class="project-card-bottom">
          <span>创建于 {{ item.createdAt.slice(0, 10) }}</span
          ><strong>进入项目 →</strong>
        </div>
      </button>
    </div>
    <div v-if="data && data.totalPages > 1" class="pagination">
      <button :disabled="loading || page <= 1" @click="load(page - 1)">上一页</button>
      <span>{{ page }} / {{ data.totalPages }}</span>
      <button :disabled="loading || page >= data.totalPages" @click="load(page + 1)">下一页</button>
    </div>
    <dialog ref="dialog" class="project-dialog" @cancel="creating && $event.preventDefault()">
      <form @submit.prevent="create">
        <div class="dialog-heading">
          <div>
            <p class="eyebrow">NEW PROJECT</p>
            <h2>新建项目</h2>
          </div>
          <button
            type="button"
            class="quiet"
            :disabled="creating"
            @click="dialog?.close()"
            aria-label="关闭新建项目"
          >
            ×
          </button>
        </div>
        <p>项目负责长期保存资料和分析，之后可以继续补充新版本。</p>
        <label class="field"
          >项目名称<input
            v-model="name"
            maxlength="120"
            autofocus
            :disabled="creating"
            placeholder="例如：命运石之门0 · 观众讨论复盘"
        /></label>
        <label class="field"
          >这次想了解什么？<textarea
            v-model="goal"
            maxlength="1000"
            rows="4"
            :disabled="creating"
            placeholder="例如：整理观众反复讨论的剧情问题，找到值得补充说明的片段。"
          />
        </label>
        <p v-if="createError" role="alert" class="error">{{ createError }}</p>
        <div class="dialog-actions">
          <button type="button" :disabled="creating" @click="dialog?.close()">取消</button>
          <button class="primary" :disabled="creating">
            {{ creating ? '正在创建…' : '创建并进入项目' }}
          </button>
        </div>
      </form>
    </dialog>
  </section>
</template>
