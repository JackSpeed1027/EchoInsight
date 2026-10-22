<!-- 文件职责：应用内未保存修改提示，使用明确按钮而非阻塞浏览器的原生确认框。 -->
<script setup lang="ts">
import { onMounted, ref } from 'vue'
const emit = defineEmits<{ stay: []; leave: [] }>()
const stay = ref<HTMLButtonElement | null>(null)
/** 提示出现时优先聚焦安全的返回操作，键盘Escape也只取消离开。 */
onMounted(() => stay.value?.focus())
</script>

<template>
  <aside class="unsaved-notice" role="alert" @keydown.esc.prevent="emit('stay')">
    <strong>当前核对意见尚未保存</strong>
    <p>返回表单后可先保存草稿。继续离开只放弃本次未保存的修改，已保存版本仍保留。</p>
    <div>
      <button ref="stay" class="quiet" type="button" @click="emit('stay')">返回继续填写</button>
      <button type="button" @click="emit('leave')">放弃修改并继续</button>
    </div>
  </aside>
</template>

<style scoped>
.unsaved-notice {
  position: fixed;
  bottom: 24px;
  right: 24px;
  z-index: 100;
  width: min(440px, calc(100vw - 48px));
  box-sizing: border-box;
  padding: 20px;
  border: 1px solid #b6c9bc;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 12px 40px #183b2929;
}
.unsaved-notice p {
  line-height: 1.7;
}
.unsaved-notice div {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
</style>
