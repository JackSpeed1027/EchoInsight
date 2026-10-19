<!-- 文件职责：统一显示失败影响、可执行建议与安全诊断码，显式保留发送后的费用未知。 -->
<script setup lang="ts">
import { computed } from 'vue'
import { describeFailure, safeFailureCode } from './publicFailures'
const props = defineProps<{
  code?: string | null
  causeCode?: string | null
  feeUncertain?: boolean
  title?: string
  taskId?: string
}>()
const detail = computed(() => describeFailure(props.causeCode || props.code))
</script>
<template>
  <section class="failure-notice" role="alert">
    <strong>{{ title || detail.title }}</strong>
    <p>{{ detail.message }}</p>
    <p>{{ detail.action }}</p>
    <p v-if="feeUncertain || code === 'MODEL_OUTCOME_UNKNOWN'" class="unknown-cost">
      调用已发送，费用或用量尚未确认；禁止自动重试，预留预算继续保留。
    </p>
    <details>
      <summary>诊断信息</summary>
      <p v-if="taskId">任务：{{ taskId }}</p>
      <p>状态原因：{{ safeFailureCode(code) }}</p>
      <p v-if="causeCode">调用原因：{{ safeFailureCode(causeCode) }}</p>
    </details>
    <slot />
  </section>
</template>
<style scoped>
.failure-notice {
  border: 1px solid #d8b9a4;
  background: #fff8f1;
  border-radius: 12px;
  padding: 1rem;
  margin: 1rem 0;
  overflow-wrap: anywhere;
}
.failure-notice p {
  margin: 0.6rem 0;
  line-height: 1.6;
}
.unknown-cost {
  font-weight: 600;
}
.failure-notice details {
  font-size: 0.85rem;
}
</style>
