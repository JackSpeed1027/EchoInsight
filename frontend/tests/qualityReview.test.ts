// 文件职责：验证未填写、明确未知、命题条件和工程替身标识不会被页面混淆。
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  blankReviewLabels,
  reviewLabelComplete,
  reviewComparisonName,
  type ReviewInput,
  type ReviewLabel,
} from '../src/qualityReview.ts'

const input = {
  input: {
    targetIds: ['child'],
    comments: [
      { id: 'parent', target: false },
      { id: 'child', target: true },
    ],
  },
} as unknown as ReviewInput
const label = (changes: Partial<ReviewLabel> = {}): ReviewLabel => ({
  commentId: 'child',
  coverage: 'UNKNOWN',
  positions: [],
  statement: '',
  condition: '',
  rationale: '缺少明确指代，因此保留未知。',
  evidenceIds: ['child'],
  ...changes,
})

test('空判断不预填未知，父语境不增加目标数', () => {
  const labels = blankReviewLabels(input)
  assert.equal(labels.length, 1)
  assert.equal(labels[0].coverage, '')
  assert.deepEqual(labels[0].evidenceIds, ['child'])
  assert.equal(reviewLabelComplete(labels[0], 'DISCUSSION'), false)
})
test('明确未知需要自己的原文和理由', () => {
  assert.equal(reviewLabelComplete(label(), 'DISCUSSION'), true)
  assert.equal(reviewLabelComplete(label({ rationale: '' }), 'DISCUSSION'), false)
  assert.equal(reviewLabelComplete(label({ evidenceIds: ['parent'] }), 'DISCUSSION'), false)
})
test('有条件成立必须补条件，不把省略条件算完成', () => {
  const qualified = label({
    coverage: 'CLAIMS',
    positions: ['QUALIFIED'],
    statement: '该因果解释只有在这一前提下成立',
  })
  assert.equal(reviewLabelComplete(qualified, 'STANCE'), false)
  assert.equal(
    reviewLabelComplete({ ...qualified, condition: '必须先接受其时间线前提' }, 'STANCE'),
    true,
  )
})
test('普通讨论不能套入无命题的支持反对轴', () => {
  assert.equal(
    reviewLabelComplete(
      label({ coverage: 'CLAIMS', statement: '解释原文主张', positions: ['SUPPORT'] }),
      'DISCUSSION',
    ),
    false,
  )
  assert.equal(
    reviewLabelComplete(label({ coverage: 'CLAIMS', statement: '解释原文主张' }), 'DISCUSSION'),
    true,
  )
  assert.equal(
    reviewLabelComplete(label({ coverage: 'QUESTION', positions: ['OPPOSE'] }), 'STANCE'),
    false,
  )
})
test('结构演示与工程替身保持非真实语义成绩标识', () => {
  assert.match(reviewComparisonName('NO_SEMANTIC_PREDICTION'), /没有语义预测/)
  assert.match(reviewComparisonName('ENGINEERING_ONLY'), /不是真实质量成绩/)
  assert.match(reviewComparisonName('HUMAN_FIRST_REVIEW'), /分歧仍需复查/)
})
