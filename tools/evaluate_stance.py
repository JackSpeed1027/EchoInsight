"""文件职责：对人工提供的命题判断与实际产物做离线对照，不调用模型，不生成或冒充独立金标准。

标注身份/独立性仅是声明，本工具不认证标注者。引用身份正确不等于证据语义正确。
"""

import argparse
import json
import re
import unicodedata
from pathlib import Path

COVERAGES = {"CLAIMS", "QUESTION", "IRRELEVANT", "UNKNOWN"}
POSITIONS = {"SUPPORT", "OPPOSE", "QUALIFIED", "UNKNOWN"}


def evaluate(cases, predictions, require_reviewed=False):
    """1、校验样本/划分/标注；2、逐目标核对预测覆盖；3、用固定分母计分；4、分开工程与声明授权样本。"""
    if not isinstance(cases, list) or not isinstance(predictions, dict):
        return {"ok": False, "errors": [{"kind": "invalid-top-level-shape"}], "metrics": None}
    errors, scores = [], []
    ids, owners = set(), {}
    pending = 0
    for case in cases:
        if not isinstance(case, dict):
            errors.append({"kind": "invalid-case-shape"})
            continue
        identity = case.get("id")
        try:
            if not isinstance(identity, str) or not identity or identity in ids:
                raise ValueError("case-id")
            ids.add(identity)
            if case.get("split") not in {"dev", "test"} or case.get("sourceKind") not in {
                "SYNTHETIC",
                "AUTHORIZED",
            }:
                raise ValueError("split-or-source")
            if (
                not case.get("contentId")
                or not isinstance(case.get("proposition"), str)
                or not case["proposition"].strip()
            ):
                raise ValueError("content-or-proposition")
            previous = owners.setdefault(case["contentId"], case["split"])
            if previous != case["split"]:
                raise ValueError("cross-split-content-leakage")
            if case["sourceKind"] == "AUTHORIZED" and not case.get("permissionRef"):
                raise ValueError("authorization-reference-required")
            group = case.get("nearDuplicateGroup")
            if group and owners.setdefault(("group", group), case["split"]) != case["split"]:
                raise ValueError("cross-split-group-leakage")
            comments = case.get("comments", [])
            comment_ids = [comment["id"] for comment in comments]
            if (
                not comments
                or len(set(comment_ids)) != len(comments)
                or any(not comment.get("text") for comment in comments)
            ):
                raise ValueError("comment-identity")
            if any(
                comment.get("parentId") is not None and comment["parentId"] not in comment_ids
                for comment in comments
            ):
                raise ValueError("missing-parent")
            for comment in comments:
                normalized = re.sub(
                    r"\s+", "", unicodedata.normalize("NFKC", comment["text"])
                ).casefold()
                if (
                    len(normalized) >= 20
                    and owners.setdefault(("text", normalized), case["split"]) != case["split"]
                ):
                    raise ValueError("cross-split-text-leakage")
            gold = case.get("gold")
            if gold is None:
                pending += 1
                continue
            reviewers = gold.get("annotatorIds", [])
            if (
                gold.get("status") != "ADJUDICATED"
                or len(set(reviewers)) < 2
                or any(not isinstance(name, str) or not name.strip() for name in reviewers)
                or not gold.get("adjudicatorId")
                or gold["adjudicatorId"] in reviewers
            ):
                raise ValueError("review-declaration")
            labels = gold.get("decisions", [])
            if {label["commentId"] for label in labels} != set(comment_ids) or len(labels) != len(
                comment_ids
            ):
                raise ValueError("gold-coverage")
            prediction = predictions.get(identity, {})
            actual = prediction.get("decisions", [])
            if {row["commentId"] for row in actual} != set(comment_ids) or len(actual) != len(
                comment_ids
            ):
                raise ValueError("prediction-coverage")
            by_id = {row["commentId"]: row for row in actual}
            case_scores = []
            for label in labels:
                coverage, positions = label.get("coverage"), label.get("positions", [])
                if (
                    coverage not in COVERAGES
                    or not isinstance(positions, list)
                    or len(positions) > 2
                    or len(set(positions)) != len(positions)
                    or not set(positions) <= POSITIONS
                    or (coverage == "CLAIMS") != bool(positions)
                    or not label.get("reason")
                ):
                    raise ValueError("gold-label")
                row = by_id[label["commentId"]]
                claims = row.get("claims", [])
                if (
                    row.get("coverage") not in COVERAGES
                    or not isinstance(claims, list)
                    or len(claims) > 2
                    or (row["coverage"] == "CLAIMS") != bool(claims)
                ):
                    raise ValueError("prediction-label")
                actual_positions = set()
                for claim in claims:
                    evidence = claim.get("evidenceIds", [])
                    if (
                        claim.get("position") not in POSITIONS
                        or label["commentId"] not in evidence
                        or not set(evidence)
                        <= set(comment_ids) | set(case.get("backgroundEvidenceIds", []))
                    ):
                        raise ValueError("prediction-evidence")
                    actual_positions.add(claim["position"])
                case_scores.append(
                    {
                        "sourceKind": case["sourceKind"],
                        "split": case["split"],
                        "coverage": coverage == row["coverage"],
                        "positions": set(positions) == actual_positions,
                    }
                )
            scores.extend(case_scores)
        except (ValueError, KeyError, TypeError, AttributeError) as failure:
            errors.append({"caseId": identity, "kind": str(failure)})
    if set(predictions) - ids:
        errors.append({"kind": "unexpected-prediction-case"})
    if require_reviewed and (
        pending
        or not cases
        or errors
        or any(
            not isinstance(case, dict) or case.get("sourceKind") != "AUTHORIZED" for case in cases
        )
    ):
        errors.append({"kind": "reviewed-authorized-inputs-required"})
    valid = not errors
    return {
        "ok": valid,
        "status": "DRAFT"
        if not scores
        else "ENGINEERING_ONLY"
        if any(score["sourceKind"] == "SYNTHETIC" for score in scores)
        else "DECLARED_REVIEWED_COMPARISON",
        "cases": len(cases),
        "pendingCases": pending,
        "scoredComments": len(scores),
        "metrics": None
        if not valid or not scores
        else {
            "coverageExact": sum(score["coverage"] for score in scores) / len(scores),
            "positionSetExact": sum(score["positions"] for score in scores) / len(scores),
        },
        "splitMetrics": {
            split: {
                "scoredComments": len([s for s in scores if s["split"] == split]),
                "coverageExact": sum(s["coverage"] for s in scores if s["split"] == split)
                / len([s for s in scores if s["split"] == split]),
            }
            if valid and any(s["split"] == split for s in scores)
            else None
            for split in ["dev", "test"]
        },
        "independentQualityProven": False,
        "errors": errors,
        "note": (
            "分数仅对本文件已裁决目标，dev/test另列；"
            "不证明标注独立、理由正确或引用语义支持，不构成发布质量闸门。"
        ),
    }


def main():
    """预测从已发布产物或本机回放提取；空DRAFT只报告准备状态，不输出零或百分之百准确率。"""
    parser = argparse.ArgumentParser(description="Offline proposition comparison; no model calls")
    parser.add_argument("--cases", default="datasets/proposition/review-cases.jsonl")
    parser.add_argument("--predictions", help="JSON mapping case ID to actual {decisions: ...}")
    parser.add_argument("--require-reviewed", action="store_true")
    options = parser.parse_args()
    try:
        cases = [
            json.loads(line)
            for line in Path(options.cases).read_text(encoding="utf-8-sig").splitlines()
            if line.strip()
        ]
        predictions = (
            json.loads(Path(options.predictions).read_text(encoding="utf-8-sig"))
            if options.predictions
            else {}
        )
        result = evaluate(cases, predictions, options.require_reviewed)
    except (OSError, ValueError, TypeError) as failure:
        result = {"ok": False, "kind": type(failure).__name__, "metrics": None}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
