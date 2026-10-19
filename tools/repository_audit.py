"""文件职责：检查Git待上传文件的边界、可疑密钥、文档链接，并可生成可核验的源码包；不提交或上传。

检查不替代人工审阅或GitHub密钥扫描；所有诊断只报告文件/行号/分类，不输出匹配到的秘密。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

PUBLIC_DOCS = {
    "docs/README.md",
    "docs/development.md",
    "docs/specs/EchoInsight-项目开发基准-v2.6.md",
    "docs/api/接口总览.md",
}
TOKEN_PATTERNS = {
    "private-key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "github-token": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b"),
    "model-key": re.compile(r"\bsk-[A-Za-z0-9_-]{28,}\b"),
    "aws-access-key": re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
}
ASSIGNMENT = re.compile(
    r"(?im)^[ \t]*[\w.-]*(?:api[-_.]?key|password|secret|sessdata|authorization)"
    r"[ \t]*[:=][ \t]*([^\r\n#]*)"
)
SAFE_EXAMPLES = {"", "false", "null", "none", "changeme", "your-api-key", "ci-test-password"}


def public_path(name: str) -> bool:
    """判断可公开的源码路径；本机数据/依赖/备份即使误被Git跟踪也不能进入导出包。"""
    parts = Path(name).parts
    if not parts or any(
        p
        in {
            ".git",
            "runtime",
            ".idea",
            "node_modules",
            "dist",
            "__pycache__",
            ".venv",
            "venv",
            ".ruff_cache",
            ".pytest_cache",
        }
        or p.startswith("target")
        or p.endswith(".egg-info")
        for p in parts
    ):
        return False
    if name == "AGENTS.md" or (parts[-1].startswith(".env") and name != ".env.example"):
        return False
    if name.startswith("docs/"):
        return name in PUBLIC_DOCS or (name.startswith("docs/api/") and name.endswith(".json"))
    if Path(name).suffix.lower() in {
        ".exe",
        ".jar",
        ".zip",
        ".7z",
        ".log",
        ".db",
        ".sqlite",
        ".sqlite3",
        ".pem",
        ".key",
        ".p12",
        ".pfx",
        ".docx",
        ".pdf",
        ".png",
        ".jpg",
        ".jpeg",
    }:
        return False
    return True


def secret_findings(name: str, text: str) -> list[dict]:
    """只返回命中位置，不回显令牌；空配置、环境占位和CI专用一次性密码可识别。"""
    found = []
    for kind, pattern in TOKEN_PATTERNS.items():
        for match in pattern.finditer(text):
            found.append(
                {"path": name, "line": text.count("\n", 0, match.start()) + 1, "kind": kind}
            )
    if (
        Path(name).suffix.lower() in {".properties", ".env", ".yml", ".yaml"}
        or name == ".env.example"
    ):
        for match in ASSIGNMENT.finditer(text):
            value = match.group(1).strip().strip("\"'")
            if value.lower() in SAFE_EXAMPLES or "${" in value:
                continue
            found.append(
                {
                    "path": name,
                    "line": text.count("\n", 0, match.start()) + 1,
                    "kind": "nonempty-credential-setting",
                }
            )
    return found


def candidates(root: Path) -> list[str]:
    """读取已跟踪和未忽略文件；不暂存、不修改Git配置、索引或提交历史。"""
    command = [
        "git",
        "--git-dir=" + str(root / ".git"),
        "--work-tree=" + str(root),
        "ls-files",
        "--cached",
        "--others",
        "--exclude-standard",
        "-z",
    ]
    result = subprocess.run(command, cwd=root, check=True, capture_output=True)
    return sorted(set(n for n in result.stdout.decode("utf-8").split("\0") if n))


def audit(root: Path, names: list[str]) -> dict:
    """1、核对公开边界/文件身份；2、扫描可疑密钥；3、检查公开文档链接；4、生成源文件哈希。"""
    findings = []
    files = []
    included = set(names)
    for name in names:
        path = root / name
        if not public_path(name):
            findings.append({"path": name, "kind": "private-or-generated-path"})
            continue
        if (
            path.is_symlink()
            or not path.resolve().is_relative_to(root.resolve())
            or not path.is_file()
        ):
            findings.append({"path": name, "kind": "unsafe-file-boundary"})
            continue
        data = path.read_bytes()
        if len(data) > 5 * 1024 * 1024:
            findings.append({"path": name, "kind": "oversized-source-file"})
        try:
            text = data.decode("utf-8-sig")
        except UnicodeDecodeError:
            findings.append({"path": name, "kind": "unexpected-binary-file"})
            continue
        findings.extend(secret_findings(name, text))
        if name.endswith(".md"):
            for link in re.findall(r"\]\(([^)]+)\)", text):
                href = link.split("#", 1)[0].strip("<>")
                if not href or "://" in href or href.startswith("mailto:"):
                    continue
                target = (path.parent / href).resolve()
                if (
                    not target.is_relative_to(root.resolve())
                    or target.relative_to(root.resolve()).as_posix() not in included
                ):
                    findings.append(
                        {"path": name, "kind": "unpublished-local-link", "target": href}
                    )
        files.append({"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    return {
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "fileCount": len(files),
        "totalBytes": sum(f["bytes"] for f in files),
        "findings": findings,
        "files": files,
    }


def export_source(root: Path, report: dict) -> Path:
    """仅导出审查通过的文件，逐个校验ZIP内容；不删除、不移动或覆盖本机原文件。"""
    if report["findings"]:
        raise ValueError("Repository audit must pass before export")
    destination = root / "runtime" / "github"
    destination.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%f")
    output = destination / ("echoinsight-source-" + stamp + ".zip")
    with ZipFile(output, "x", ZIP_DEFLATED) as archive:
        for item in report["files"]:
            data = (root / item["path"]).read_bytes()
            if hashlib.sha256(data).hexdigest() != item["sha256"]:
                raise ValueError("Source changed during export: " + item["path"])
            archive.writestr("echoinsight/" + item["path"], data)
        archive.writestr(
            "echoinsight/source-manifest.json", json.dumps(report, ensure_ascii=False, indent=2)
        )
    with ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise ValueError("ZIP CRC validation failed")
        for item in report["files"]:
            if (
                hashlib.sha256(archive.read("echoinsight/" + item["path"])).hexdigest()
                != item["sha256"]
            ):
                raise ValueError("ZIP source hash mismatch")
    (destination / (output.stem + ".audit.json")).write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return output


def main() -> int:
    """默认只检查；--export额外输出源码包，不运行git add/commit/push或创建GitHub仓库。"""
    parser = argparse.ArgumentParser(
        description="Check publishable EchoInsight files without printing secrets"
    )
    parser.add_argument(
        "--export",
        action="store_true",
        help="Create a verified source ZIP under ignored runtime/github",
    )
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    try:
        report = audit(root, candidates(root))
    except (OSError, subprocess.CalledProcessError) as failure:
        print(json.dumps({"status": "ERROR", "kind": type(failure).__name__}))
        return 2
    summary = {k: report[k] for k in ["fileCount", "totalBytes", "findings"]}
    summary["status"] = "PASS" if not report["findings"] else "REVIEW_REQUIRED"
    if not report["findings"] and options.export:
        summary["sourceZip"] = str(export_source(root, report))
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 1 if report["findings"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
