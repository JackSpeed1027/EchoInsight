"""文件职责：验证真实数据桥接的来源隔离、父引用、时间轴及不完整采集标记。"""
import unittest
from prepare_bilibili_cli_export import prepare


class BilibiliBridgeTest(unittest.TestCase):
    """全部使用最小合成输入，保护真实数据不会被错误归属或覆盖。"""
    def source(self):
        return {"code": 0, "data": {"aid": 42, "bvid": "BV-test", "title": "合成",
                "owner": {"mid": 1}, "pubdate": 1760000000,
                "pages": [{"cid": 3, "duration": 10}]} }

    def root(self):
        return {"rpid": 11, "oid": 42, "type": 1, "parent": 0, "content": "原文",
                "ctime": 1760000001, "reply_count": 49, "replies": []}

    def test_marks_partial_threads_and_never_claims_platform_coverage(self):
        value = prepare(self.source(), [self.root()], [])
        self.assertEqual(value["manifest.json"]["commentsCompleteness"], "NOT_PROVEN")
        self.assertIsNone(value["manifest.json"]["sourceCoverage"])
        self.assertEqual(value["manifest.json"]["partiallyReturnedThreads"][0]["reportedReplyCount"], 49)

    def test_rejects_foreign_content_and_boolean_identity(self):
        for field, value in [("oid", 99), ("rpid", True)]:
            row = self.root()
            row[field] = value
            with self.assertRaises(ValueError):
                prepare(self.source(), [row], [])

    def test_rejects_missing_parent_and_conflicting_duplicate(self):
        row = self.root()
        row["parent"] = 999
        with self.assertRaises(ValueError):
            prepare(self.source(), [row], [])
        revised = self.root()
        revised["content"] = "改文"
        with self.assertRaises(ValueError):
            prepare(self.source(), [self.root(), revised], [])

    def test_danmaku_has_cid_and_offset_and_never_becomes_comment(self):
        dm = {"dmid": 1, "progress_ms": 9000, "ctime": 1760000001, "content": "弹幕"}
        result = prepare(self.source(), [self.root()], [dm])
        self.assertEqual(len(result["comments.json"]), 1)
        self.assertEqual(result["danmaku.json"][0]["cid"], "3")
        dm["progress_ms"] = 10001
        with self.assertRaises(ValueError):
            prepare(self.source(), [self.root()], [dm])

    def test_multiple_parts_require_explicit_scope_and_never_guess_cid(self):
        view = self.source()
        view["data"]["pages"].append({"cid": 4, "duration": 10})
        with self.assertRaises(ValueError):
            prepare(view, [self.root()], [])


if __name__ == "__main__":
    unittest.main()
