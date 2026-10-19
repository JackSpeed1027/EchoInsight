"""文件职责：校验本机候选包身份后拆分待标注样本与真实预测；不调用模型、不生成金标、不覆盖旧文件。"""

import argparse
import hashlib
import json
from pathlib import Path


def unpack(bundle, output):
    """1、校验格式/哈希；2、核对未裁决状态与预测覆盖；3、仅在新目录独占创建文件。"""
    if bundle.get("schemaVersion") != "evaluation-candidate-package-v1":
        raise ValueError("unsupported-package")
    values = {}
    for name in ["case", "prediction"]:
        raw = bundle.get(name + "Json")
        if not isinstance(raw, str) or hashlib.sha256(raw.encode()).hexdigest() != bundle.get(
            name + "Sha256"
        ):
            raise ValueError("package-hash-mismatch")
        values[name] = json.loads(raw)
    case, prediction = values["case"], values["prediction"]
    if case.get("gold") is not None or case.get("split") not in {"dev", "test"}:
        raise ValueError("expected-unreviewed-candidate")
    if case.get("sourceKind") not in {"SYNTHETIC", "UNVERIFIED"}:
        raise ValueError("source-authorization-must-be-reviewed")
    ids = [comment["id"] for comment in case["comments"]]
    if not ids or len(set(ids)) != len(ids):
        raise ValueError("invalid-comment-identities")
    predicted = [decision["commentId"] for decision in prediction["decisions"]]
    if len(predicted) != len(ids) or set(predicted) != set(ids):
        raise ValueError("prediction-coverage-mismatch")
    if any(c.get("parentId") is not None and c["parentId"] not in ids for c in case["comments"]):
        raise ValueError("missing-parent-context")
    # 先完成所有校验，已有目录一律拒绝；失败时保留已创建资料，不用清理操作掩盖。
    output = Path(output)
    output.mkdir(exist_ok=False)
    files = {
        "review-cases.jsonl": json.dumps(case, ensure_ascii=False) + "\n",
        "actual-predictions.json": json.dumps(
            {case["id"]: prediction}, ensure_ascii=False, indent=2
        ),
        "provenance.json": json.dumps(
            {
                key: value
                for key, value in bundle.items()
                if key not in {"caseJson", "predictionJson"}
            },
            ensure_ascii=False,
            indent=2,
        ),
    }
    for name, content in files.items():
        with (output / name).open("x", encoding="utf-8") as handle:
            handle.write(content)
    return {"createdFiles": list(files), "goldGenerated": False, "rawTextIncluded": True}


def main():
    """新目录由用户指定；默认不把私人候选写入仓库datasets。"""
    parser = argparse.ArgumentParser(description="Unpack local unreviewed evaluation candidates")
    parser.add_argument("package")
    parser.add_argument("output_directory")
    options = parser.parse_args()
    try:
        bundle = json.loads(Path(options.package).read_text(encoding="utf-8-sig"))
        result = unpack(bundle, options.output_directory)
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as failure:
        result = {"ok": False, "kind": type(failure).__name__}
        print(json.dumps(result))
        return 1
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
