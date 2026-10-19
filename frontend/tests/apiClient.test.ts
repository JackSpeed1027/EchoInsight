// 文件职责：使用内存Response验证错误安全和保留诊断，不触发浏览器或真实网络。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readApi, ApiError } from '../src/apiClient.ts'
import { describeFailure, safeFailureCode } from '../src/publicFailures.ts'
const original = globalThis.fetch
test.afterEach(() => {
  globalThis.fetch = original
})
test('显示安全字段约束和具体上限，不丢失HTTP状态', async () => {
  globalThis.fetch = async () =>
    new Response(
      JSON.stringify({
        code: 'INVALID_FIELDS',
        message: '修正字段',
        issues: [{ path: 'rootLimit', message: '最多200条' }],
      }),
      { status: 400, headers: { 'content-type': 'application/json' } },
    )
  await assert.rejects(
    readApi('/test'),
    (error: unknown) =>
      error instanceof ApiError &&
      error.status === 400 &&
      error.message.includes('rootLimit：最多200条'),
  )
})
test('500即使有合法错误code也不反射私有异常', async () => {
  globalThis.fetch = async () =>
    new Response(JSON.stringify({ code: 'REQUEST_REJECTED', message: 'secret-private-token' }), {
      status: 500,
      headers: { 'content-type': 'application/json' },
    })
  await assert.rejects(
    readApi('/test'),
    (error: unknown) => error instanceof Error && !error.message.includes('secret-private'),
  )
})
test('非契约JSON和HTML不能变成用户提示', async () => {
  for (const response of [
    new Response('<html>private SQL</html>', { status: 400 }),
    new Response(JSON.stringify({ message: 'private SQL' }), {
      status: 400,
      headers: { 'content-type': 'application/json' },
    }),
  ]) {
    globalThis.fetch = async () => response
    await assert.rejects(
      readApi('/test'),
      (error: unknown) => error instanceof Error && !error.message.includes('private SQL'),
    )
  }
})
test('网络失败保留任务可能仍在运行的说明', async () => {
  globalThis.fetch = async () => {
    throw new Error('private-network-detail')
  }
  await assert.rejects(
    readApi('/test'),
    (error: unknown) =>
      error instanceof Error &&
      error.message.includes('任务不一定停止') &&
      !error.message.includes('private-network'),
  )
})
test('已知费用未知不建议盲目重试，旧错误不编造根因', () => {
  assert.match(describeFailure('MODEL_OUTCOME_UNKNOWN').action, /不能直接重试/)
  assert.match(describeFailure('MODEL_INVALID_RESPONSE').message, /旧记录没有更细/)
  assert.equal(safeFailureCode('<script>private</script>'), 'UNRECOGNIZED_FAILURE')
})
test('成功JSON不改变请求方法/正文或创建额外网络请求', async () => {
  let calls = 0
  globalThis.fetch = async (_url, options) => {
    calls++
    assert.equal(options?.method, 'POST')
    assert.equal(options?.body, '{}')
    return new Response('{"saved":121}')
  }
  assert.deepEqual(await readApi('/preview', { method: 'POST', body: '{}' }), { saved: 121 })
  assert.equal(calls, 1)
})
