/* 文件职责：验证入口金额、输入切换和轮询边界，防止浏览器自动推进收费任务。 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { intakeBody, intakeCost, intakePhase, intakeShouldPoll } from '../src/analysisIntake.ts'
test('链接优先时清除旧下拉身份，原问题内部换行保持', () => {
  const body = intakeBody({
    url: ' https://www.bilibili.com/video/av1 ',
    snapshotId: 'old',
    instruction: ' 总结\n观点 ',
    proposition: ' 标准 ',
    analysisScope: 'FULL',
  })
  assert.equal(body.snapshotId, null)
  assert.equal(body.instruction, '总结\n观点')
  assert.equal(body.proposition, '标准')
})
test('本地资料保持明确身份，空可选值统一null', () => {
  assert.deepEqual(
    intakeBody({
      url: ' ',
      snapshotId: 'selected',
      instruction: '总结观点',
      proposition: '',
      analysisScope: 'PREVIEW',
    }),
    {
      url: null,
      snapshotId: 'selected',
      instruction: '总结观点',
      proposition: null,
      analysisScope: 'PREVIEW',
    },
  )
})
test('金额使用BigInt保留纳美元及超安全整数精度', () => {
  assert.equal(intakeCost('9007199254740993', 'USD_NANOS'), '$9007199.254740993')
  assert.equal(intakeCost('1', 'USD_NANOS'), '$0.000000001')
  assert.equal(intakeCost('1230000000', 'USD_NANOS'), '$1.23')
  assert.equal(intakeCost('0', 'USD_NANOS'), '$0')
})
test('错误金额不猜测为零费用，演示单位不冒充美元', () => {
  for (const value of ['NaN', '-1', '1.2', ''])
    assert.equal(intakeCost(value, 'USD_NANOS'), '费用暂不可用')
  assert.equal(intakeCost('0', 'SIMULATED_UNITS'), '不产生模型费用')
  assert.equal(intakeCost('10', 'SIMULATED_UNITS'), '10 SIMULATED_UNITS')
})
test('只有采集中可轮询，终态不自动绑定或分析', () => {
  assert.equal(intakeShouldPoll({ phase: 'COLLECTING' }), true)
  for (const phase of [
    'READY_TO_BIND',
    'COLLECTION_FAILED',
    'COLLECTION_CANCELLED',
    'READY_FOR_ANALYSIS',
    'ANALYSIS_ACCEPTED',
    'UNKNOWN',
  ])
    assert.equal(intakeShouldPoll({ phase }), false)
})
test('未知阶段不呈现为成功，支持反对不映射为情绪', () => {
  assert.equal(intakePhase('UNKNOWN'), 'UNKNOWN')
  assert.match(intakePhase('READY_TO_BIND'), /确认资料/)
})
