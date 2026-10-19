"""文件职责：用合成标签验证评测分母和拒绝规则；测试标签不是独立语义金标。"""

import unittest
from copy import deepcopy

from evaluate_stance import evaluate


class StanceEvaluationTest(unittest.TestCase):
    """空样本不出成绩，缺失预测不缩小分母，跨作品划分和证据身份可复核。"""

    def sample(self):
        """合成双署名只是协议夹具，不认证人物存在或独立。"""
        return {
            "id": "fixture",
            "contentId": "synthetic-work",
            "split": "test",
            "sourceKind": "SYNTHETIC",
            "proposition": "合成命题",
            "comments": [{"id": "c1", "text": "合成原文", "parentId": None}],
            "gold": {
                "status": "ADJUDICATED",
                "annotatorIds": ["fixture-a", "fixture-b"],
                "adjudicatorId": "fixture-c",
                "decisions": [
                    {
                        "commentId": "c1",
                        "coverage": "CLAIMS",
                        "positions": ["SUPPORT"],
                        "reason": "合成标注",
                    }
                ],
            },
        }

    def prediction(self):
        """只给匹配样本的一条实际判断形状，供工程计算用。"""
        return {
            "fixture": {
                "decisions": [
                    {
                        "commentId": "c1",
                        "coverage": "CLAIMS",
                        "claims": [{"position": "SUPPORT", "evidenceIds": ["c1"]}],
                    }
                ]
            }
        }

    def test_empty_draft_has_no_quality_score(self):
        self.assertIsNone(evaluate([], {})["metrics"])
        self.assertFalse(evaluate([], {}, True)["ok"])
        self.assertFalse(evaluate([None], {}, True)["ok"])

    def test_synthetic_scores_never_claim_independent_quality(self):
        result = evaluate([self.sample()], self.prediction())
        self.assertEqual(result["metrics"]["positionSetExact"], 1)
        self.assertEqual(result["status"], "ENGINEERING_ONLY")
        self.assertFalse(result["independentQualityProven"])
        self.assertFalse(evaluate([self.sample()], self.prediction(), True)["ok"])

    def test_missing_prediction_or_wrong_evidence_is_not_silent_exclusion(self):
        self.assertFalse(evaluate([self.sample()], {})["ok"])
        prediction = self.prediction()
        prediction["fixture"]["decisions"][0]["claims"][0]["evidenceIds"] = ["invented"]
        result = evaluate([self.sample()], prediction)
        self.assertFalse(result["ok"])
        self.assertIsNone(result["metrics"])

    def test_same_work_cannot_leak_across_splits(self):
        other = deepcopy(self.sample())
        other.update(id="other", split="dev")
        self.assertFalse(evaluate([self.sample(), other], self.prediction())["ok"])
