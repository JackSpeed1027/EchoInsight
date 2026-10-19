<!-- 文件职责：在预检和报告中说明采集与分析不同分母，旧报告回溯诊断显式标记。 -->
<script setup lang="ts">
import { coverageSummary, type CoverageView } from './sourceCoverage'
defineProps<{ view: CoverageView }>()
</script>
<template>
  <section class="surface" aria-label="来源覆盖说明">
    <h3>这份结果依据了多少材料</h3>
    <p>{{ coverageSummary(view.coverage) }}</p>
    <p v-if="view.coverage.missingParentLinks">
      有 {{ view.coverage.missingParentLinks }} 处父评论缺失，需结合上下文限制阅读。
    </p>
    <p class="small">
      {{
        view.frozen ? '来源诊断随本次请求冻结。' : '当前为预检或历史回溯诊断，不是接受时冻结记录。'
      }}
    </p>
    <details>
      <summary>统计口径和缺口</summary>
      <ul>
        <li v-for="note in view.coverage.notes" :key="note">{{ note }}</li>
      </ul>
    </details>
  </section>
</template>
