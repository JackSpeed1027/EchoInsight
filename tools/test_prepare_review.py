"""文件职责：验证候选包拆分的身份、覆盖和文件保护，所有输入是本测试合成数据。"""

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from prepare_review import unpack


class PrepareReviewTest(unittest.TestCase):
    """候选不是金标准；坏身份不得输出，已有路径不得覆盖。"""

    def bundle(self):
        """真实来源保留UNVERIFIED，不把测试署名当授权证明。"""
        case = {
            "id": "case",
            "split": "dev",
            "sourceKind": "UNVERIFIED",
            "gold": None,
            "comments": [{"id": "c", "parentId": None, "text": "合成"}],
        }
        prediction = {"decisions": [{"commentId": "c"}]}
        result = {"schemaVersion": "evaluation-candidate-package-v1"}
        for name, value in [("case", case), ("prediction", prediction)]:
            raw = json.dumps(value, ensure_ascii=False)
            result[name + "Json"] = raw
            result[name + "Sha256"] = hashlib.sha256(raw.encode()).hexdigest()
        return result

    def test_unpacks_without_inventing_gold_or_permission(self):
        """只在本任务新建临时目录检查文件，TemporaryDirectory不包含用户资料。"""
        with tempfile.TemporaryDirectory(prefix="echoinsight-review-test-") as temporary:
            output = Path(temporary) / "new"
            result = unpack(self.bundle(), output)
            self.assertFalse(result["goldGenerated"])
            case = json.loads((output / "review-cases.jsonl").read_text(encoding="utf-8"))
            self.assertIsNone(case["gold"])
            self.assertEqual(case["sourceKind"], "UNVERIFIED")
            self.assertEqual(len(list(output.iterdir())), 3)

    def test_tamper_or_missing_prediction_never_creates_directory(self):
        """哈希/覆盖拒绝均发生在创建输出目录之前。"""
        with tempfile.TemporaryDirectory(prefix="echoinsight-review-test-") as temporary:
            output = Path(temporary) / "rejected"
            bundle = self.bundle()
            bundle["caseJson"] += " "
            with self.assertRaises(ValueError):
                unpack(bundle, output)
            self.assertFalse(output.exists())
            bundle = self.bundle()
            bundle["predictionJson"] = '{"decisions":[]}'
            bundle["predictionSha256"] = hashlib.sha256(
                bundle["predictionJson"].encode()
            ).hexdigest()
            with self.assertRaises(ValueError):
                unpack(bundle, output)
            self.assertFalse(output.exists())

    def test_existing_directory_is_never_overwritten(self):
        """已有文件即使同名也保留，不尝试自动清理或替换。"""
        with tempfile.TemporaryDirectory(prefix="echoinsight-review-test-") as temporary:
            output = Path(temporary)
            source = output / "review-cases.jsonl"
            source.write_text("retained", encoding="utf-8")
            with self.assertRaises(FileExistsError):
                unpack(self.bundle(), output)
            self.assertEqual(source.read_text(encoding="utf-8"), "retained")


if __name__ == "__main__":
    unittest.main()
