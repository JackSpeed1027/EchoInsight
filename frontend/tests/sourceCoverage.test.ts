/** 文件职责：核对未知/数量相等/反向差异三种来源口径，禁止从计数推出全量。 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { coverageSummary, type SourceCoverage } from '../src/sourceCoverage.ts'
const input: SourceCoverage = {
  savedComments: 12,
  sourceReportedComments: null,
  declaredDifference: null,
  missingParentLinks: 0,
  visibleTraversalReconciled: false,
  completeness: null,
  notes: [],
}
test('未知平台声明不能显示成零缺失', () => assert.match(coverageSummary(input), /总数未知/))
test('数量相同且无分页证据不能声称全量', () =>
  assert.match(coverageSummary({ ...input, sourceReportedComments: 12 }), /不能证明全部/))
test('保存数高于声明数时保留未知原因', () =>
  assert.match(coverageSummary({ ...input, sourceReportedComments: 10 }), /数量未对齐，原因未知/))
