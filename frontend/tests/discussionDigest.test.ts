// 文件职责：验证完整讨论的业务进度与内容窗口转换，避免复用/新调用和免费计划混为一谈。
import test from 'node:test'
import assert from 'node:assert/strict'
import { contentChoiceBody, digestProgressText, digestNodeName } from '../src/discussionDigest.ts'

test('清空内容选择不携带旧时间窗口', () => {
  assert.deepEqual(contentChoiceBody({ materialId: '', from: 2, to: 3 }), {})
})
test('内容选择保留0秒、精确毫秒，拒绝半个时间范围', () => {
  assert.deepEqual(contentChoiceBody({ materialId: 'material', from: 0, to: 1.25 }), {
    contentMaterialId: 'material',
    contentFromMs: 0,
    contentToMs: 1250,
  })
  assert.throws(() => contentChoiceBody({ materialId: 'material', from: 2, to: '' }))
  assert.throws(() => contentChoiceBody({ materialId: 'material', from: 2, to: 1 }))
})
test('免费完整讨论不虚报语义完成', () => {
  assert.match(
    digestProgressText({
      phase: 'STRUCTURAL_DEMO',
      plannedMaps: 125,
      completedMaps: 0,
      plannedReductions: 19,
      completedReductions: 0,
      reusedMaps: 0,
    }),
    /125片.*19次汇总.*未执行/,
  )
})
test('分片、复用和归并完成分别显示，完成不代表质量通过', () => {
  const state = {
    phase: 'COMPLETE',
    plannedMaps: 3,
    completedMaps: 3,
    plannedReductions: 1,
    completedReductions: 1,
    reusedMaps: 2,
  }
  assert.match(digestProgressText(state), /3\/3.*复用2片.*1\/1.*语义仍需核对/)
  assert.match(
    digestProgressText({ ...state, phase: 'MAP', completedMaps: 1 }),
    /全部节点成功后发布/,
  )
})
test('内部节点阶段展示为业务步骤，未知阶段不显示假完成', () => {
  assert.equal(digestNodeName('MAP'), '分片归纳')
  assert.equal(digestNodeName('REDUCE'), '分组汇总')
  assert.equal(digestNodeName('FINAL'), '最终汇总')
  assert.equal(digestNodeName('other'), '分析步骤')
})
