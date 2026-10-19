"""文件职责：用合成目录验证上传边界、密钥不回显和源码包哈希，不操作真实Git/用户资料。"""

import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from repository_audit import audit, export_source, public_path, secret_findings


class RepositoryAuditTest(unittest.TestCase):
    """公开入口只保留维护中的说明；本地私有/生成文件不能误入导出包。"""

    def test_private_and_generated_paths_are_rejected(self):
        """即使Git意外跟踪了文件，第二层路径检查仍拒绝。"""
        for name in [
            "runtime/application-local.properties",
            "backend/target-echoinsight/a.class",
            "docs/resume/private.md",
            "docs/acceptance/images/user.png",
            ".env.local",
            "private.pem",
        ]:
            self.assertFalse(public_path(name), name)
        for name in [
            "README.md",
            ".env.example",
            "docs/api/接口总览.md",
            "backend/src/main/java/Example.java",
        ]:
            self.assertTrue(public_path(name), name)

    def test_secrets_report_positions_without_echoing_values(self):
        """密钥只能输出分类/行号，不能因排查再次泄露。"""
        token = "sk-" + "x" * 32
        finding = secret_findings("config/example.properties", "api-key=" + token)
        self.assertTrue(finding)
        self.assertNotIn(token, json.dumps(finding))
        self.assertFalse(secret_findings(".env.example", "DB_PASSWORD=\n"))
        self.assertFalse(
            secret_findings("config/example.properties", "api-key=\nworker.enabled=false\n")
        )
        self.assertFalse(
            secret_findings(".github/workflows/ci.yml", "DB_PASSWORD: ci-test-password\n")
        )

    def test_unpublished_link_is_detected_and_verified_zip_contains_only_audited_files(self):
        """本地存在但未上传的目标也算坏链；通过后ZIP只含清单及被审查的源码。"""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "README.md").write_text("[missing](private.md)", encoding="utf-8")
            report = audit(root, ["README.md"])
            self.assertEqual(report["findings"][0]["kind"], "unpublished-local-link")
            (root / "README.md").write_text("Synthetic public source", encoding="utf-8")
            report = audit(root, ["README.md"])
            self.assertFalse(report["findings"])
            output = export_source(root, report)
            with ZipFile(output) as archive:
                self.assertEqual(
                    set(archive.namelist()),
                    {"echoinsight/README.md", "echoinsight/source-manifest.json"},
                )


if __name__ == "__main__":
    unittest.main()
