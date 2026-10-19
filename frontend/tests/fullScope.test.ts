// 文件职责：验证长结果分页不更改分母，异常页码不会丢页或卡住界面。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { decisionPage } from '../src/stanceView.ts'
test('25条判断分两页且没有重复或丢失', () => {
  const rows = Array.from({ length: 25 }, (_, n) => n)
  const a = decisionPage(rows, 1),
    b = decisionPage(rows, 2)
  assert.equal(a.pages, 2)
  assert.equal(a.items.length, 20)
  assert.equal(b.items.length, 5)
  assert.deepEqual([...a.items, ...b.items], rows)
  assert.equal(rows.length, 25)
})
test('空列表与越界页码有稳定返回', () => {
  assert.deepEqual(decisionPage([], 1), { items: [], page: 1, pages: 1 })
  assert.equal(decisionPage([1, 2], 999).page, 1)
  assert.equal(decisionPage([1, 2], NaN).page, 1)
})
test('不合法页大小不会创建无穷页或一次渲染所有结果', () => {
  assert.equal(
    decisionPage(
      Array.from({ length: 5000 }, (_, n) => n),
      1,
      0,
    ).items.length,
    20,
  )
  assert.equal(
    decisionPage(
      Array.from({ length: 5000 }, (_, n) => n),
      1,
      99999,
    ).items.length,
    20,
  )
})
