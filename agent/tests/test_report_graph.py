"""文件职责：验证真实 LangGraph、SQLite 及回环 HTTP 的工具边界与进程恢复。

测试只使用唯一合成身份和本机服务，不连接 MySQL、不调用模型；
生成的检查点保留在 runtime/agent-tests，避免清理历史记录。
"""

import json
import os
import subprocess
import sys
import threading
import unittest
from collections import Counter
from copy import deepcopy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from uuid import uuid4

from echoinsight_tools.runner import AgentRunner, run_lock
from echoinsight_tools.tools import JavaTools, ToolError, backend_address, uuid_value

ROOT = Path(__file__).resolve().parents[2]


class ReportGraphTest(unittest.TestCase):
    """每个案例创建独立回环 HTTP 服务与状态目录，断言实际工具调用次数。"""

    def setUp(self):
        """准备任务、报告和引用；服务端计数用于确认成功节点没有被重复执行。"""
        self.job_id = str(uuid4())
        self.report_id = str(uuid4())
        self.snapshot_id = str(uuid4())
        self.counts = Counter()
        self.fail_reports = 0
        self.redirect = False
        self.job = {
            "id": self.job_id,
            "snapshotId": self.snapshot_id,
            "status": "SUCCESS",
            "reportId": self.report_id,
        }
        self.report = {
            "reportId": self.report_id,
            "snapshotId": self.snapshot_id,
            "provenance": {"mode": "MOCK", "modelVersion": "fixture-test"},
            "coverage": {"eligibleComments": 3, "classifiedComments": 2, "unknown": 1},
            "metrics": {
                "positive": 1,
                "negative": 1,
                "neutral": 0,
                "positiveShareAmongClassified": 0.5,
            },
            "findings": [{"claim": "PRIVATE_CLAIM_SENTINEL", "evidenceIds": ["q1", "q2"]}],
        }
        self.evidence = [
            {"commentId": "q1", "text": "PRIVATE_COMMENT_SENTINEL"},
            {"commentId": "q2", "text": "另一条合成原文"},
        ]
        owner = self

        class Handler(BaseHTTPRequestHandler):
            """只提供三条合成只读路径，其他路径返回 404。"""

            def log_message(self, format, *args):
                """关闭 HTTP 默认访问输出，不把测试正文混入验收日志。"""

            def do_GET(self):
                """记录路径并按测试设定返回正常、失败或跳转响应。"""
                owner.counts[self.path] += 1
                status = 200
                if owner.redirect:
                    self.send_response(302)
                    self.send_header("Location", "http://example.invalid/private")
                    self.end_headers()
                    return
                if self.path == f"/api/v1/analysis-jobs/{owner.job_id}":
                    body = owner.job
                elif self.path == f"/api/v1/reports/{owner.report_id}":
                    if owner.fail_reports:
                        owner.fail_reports -= 1
                        status, body = 503, {}
                    else:
                        body = owner.report
                elif self.path == f"/api/v1/reports/{owner.report_id}/evidence":
                    body = owner.evidence
                else:
                    status, body = 404, {}
                raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                try:
                    self.wfile.write(raw)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.backend = f"http://127.0.0.1:{self.server.server_port}"
        self.directory = ROOT / "runtime/agent-tests" / str(uuid4())
        self.runner = AgentRunner(self.directory)

    def tearDown(self):
        """只关闭本测试创建的 HTTP 服务，保留所有图状态文件。"""
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def start(self, task="overview", run_id=None):
        """以当前合成任务启动图，减少重复的调用参数，不隐藏断言。"""
        return self.runner.start(self.job_id, task, self.backend, run_id)

    def test_overview_reads_only_job_and_report_and_preserves_mock(self):
        """概览只使用两个工具，保留 UNKNOWN 与 MOCK，不引入语义判断。"""
        output = self.start()
        self.assertEqual(output["next"], [])
        self.assertEqual(output["result"]["provenance"]["mode"], "MOCK")
        self.assertEqual(output["result"]["coverage"]["unknown"], 1)
        self.assertEqual(sum(self.counts.values()), 2)

    def test_evidence_checks_presence_without_claiming_semantic_support(self):
        """存在性齐全和缺失均给出可解释状态，不能声称语义已经验证。"""
        complete = self.start("evidence")["result"]["evidenceAudit"]
        self.assertEqual(complete["referenceStatus"], "COMPLETE")
        self.assertEqual(complete["semanticSupport"], "NOT_EVALUATED")
        self.evidence = self.evidence[:1]
        missing = self.start("evidence")["result"]["evidenceAudit"]
        self.assertEqual(missing["missingIds"], ["q2"])
        self.assertEqual(missing["referenceStatus"], "MISSING")

    def test_pending_job_requests_input_without_report_call(self):
        """图不代替用户派发未完成任务，返回 NEEDS_INPUT 后结束。"""
        self.job.update(status="RUNNING", reportId=None)
        result = self.start()["result"]
        self.assertEqual(result["status"], "NEEDS_INPUT")
        self.assertEqual(sum(self.counts.values()), 1)

    def test_scope_mismatch_is_rejected_and_resume_skips_completed_job_node(self):
        """错误快照被拒绝，修复后从报告节点恢复，成功的任务读取仅执行一次。"""
        run_id = str(uuid4())
        self.report["snapshotId"] = str(uuid4())
        with self.assertRaisesRegex(ToolError, "SCOPE_MISMATCH"):
            self.start(run_id=run_id)
        state = self.runner.existing(run_id, resume=False)
        self.assertEqual(state["next"], ["load_report"])
        self.report["snapshotId"] = self.snapshot_id
        restored = AgentRunner(self.directory).existing(run_id, resume=True)
        self.assertEqual(restored["result"]["status"], "COMPLETE")
        self.assertEqual(self.counts[f"/api/v1/analysis-jobs/{self.job_id}"], 1)
        self.assertEqual(self.counts[f"/api/v1/reports/{self.report_id}"], 2)

    def test_completed_run_is_not_reexecuted_or_overwritten(self):
        """重新打开 SQLite 或重复恢复均不再次请求 Java，同一 run 不能覆盖原输入。"""
        first = self.start("evidence")
        before = deepcopy(self.counts)
        second = AgentRunner(self.directory).existing(first["runId"], resume=True)
        self.assertEqual(second["result"], first["result"])
        self.assertEqual(self.counts, before)
        with self.assertRaisesRegex(ToolError, "RUN_EXISTS"):
            self.start(run_id=first["runId"])
        self.assertEqual(self.counts, before)

    def test_real_process_exit_and_resume_use_persisted_checkpoint(self):
        """实际启动两个 Python 进程：第一次读取失败退出，第二次恢复同一 run。"""
        self.fail_reports = 1
        environment = {**os.environ, "PYTHONIOENCODING": "utf-8"}
        first = subprocess.run(
            [
                sys.executable,
                "-m",
                "echoinsight_tools",
                "run",
                "--job",
                self.job_id,
                "--task",
                "evidence",
                "--backend",
                self.backend,
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            env=environment,
            timeout=30,
        )
        self.assertEqual(first.returncode, 2, first.stderr)
        run_id = json.loads(first.stderr)["runId"]
        second = subprocess.run(
            [sys.executable, "-m", "echoinsight_tools", "resume", "--run", run_id],
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            env=environment,
            timeout=30,
        )
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(json.loads(second.stdout)["result"]["status"], "COMPLETE")
        self.assertEqual(self.counts[f"/api/v1/analysis-jobs/{self.job_id}"], 1)
        self.assertEqual(self.counts[f"/api/v1/reports/{self.report_id}"], 2)

    def test_checkpoint_does_not_copy_raw_claim_or_comment_text(self):
        """检查点只含引用和统计摘要，不复制结论正文与评论正文。"""
        self.start("evidence")
        raw = (self.directory / "checkpoints.sqlite").read_bytes()
        self.assertNotIn(b"PRIVATE_COMMENT_SENTINEL", raw)
        self.assertNotIn(b"PRIVATE_CLAIM_SENTINEL", raw)

    def test_invalid_counts_ids_and_extra_evidence_are_rejected(self):
        """拒绝统计漂移、路径身份伪造、越界原文，不将错误工具结果呈现为成功。"""
        with self.assertRaises(ToolError):
            uuid_value("../reports/anything")
        self.report["coverage"]["classifiedComments"] = 9
        with self.assertRaisesRegex(ToolError, "INVALID_METRICS"):
            self.start()
        self.report["coverage"]["classifiedComments"] = 2
        self.evidence.append({"commentId": "outside", "text": "不属于本报告"})
        with self.assertRaisesRegex(ToolError, "SCOPE_MISMATCH"):
            self.start("evidence")

    def test_remote_redirect_and_invalid_backend_are_rejected(self):
        """工具拒绝域名、非 HTTP、凭据、路径，以及从回环服务跳转到外部站点。"""
        for address in [
            "https://127.0.0.1:8080",
            "http://example.com:8080",
            "http://localhost:8080",
            "http://user:pass@127.0.0.1:8080",
            "http://127.0.0.1:8080/api",
            "http://127.0.0.1:8080?x=y",
        ]:
            with self.subTest(address=address), self.assertRaises(ToolError):
                backend_address(address)
        self.redirect = True
        with self.assertRaisesRegex(ToolError, "JAVA_HTTP_302"):
            JavaTools(self.backend).job(self.job_id)

    def test_same_run_cannot_be_advanced_concurrently(self):
        """同一运行的第二个本机操作者被明确拒绝，不覆盖在途检查点。"""
        run_id = str(uuid4())
        with run_lock(self.directory, run_id), self.assertRaisesRegex(ToolError, "RUN_BUSY"):
            self.start(run_id=run_id)


if __name__ == "__main__":
    unittest.main()
