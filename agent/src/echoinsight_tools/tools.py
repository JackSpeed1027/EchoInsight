"""文件职责：把 Java 已有接口封装为三个只读工具，限制地址、身份和响应大小。

只允许读取任务、该任务的报告、该报告的原文。模型或评论不能传入 URL、SQL 或文件路径。
接口地址集中定义在本模块；字段约束负责尽早暴露前后端协议偏差，不替代 Java 授权。
"""

import json
import math
from typing import Annotated, Any, Literal
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, ValidationError

Count = Annotated[int, Field(strict=True, ge=0)]
Identifier = Annotated[str, Field(min_length=1, max_length=128)]


class ToolError(RuntimeError):
    """可向用户展示的受控错误；不包含响应正文、凭据或底层连接异常文本。"""


class JavaRecord(BaseModel):
    """严格检查已使用字段，忽略 Java 响应中暂未使用的历史和进度字段。"""

    model_config = ConfigDict(strict=True, extra="ignore")


class JobReference(JavaRecord):
    """任务身份由 Java 返回；只有成功任务才能进一步读取报告。"""

    id: Identifier
    snapshotId: Identifier
    status: Literal["PENDING", "RUNNING", "SUCCESS", "FAILED"]
    reportId: Identifier | None


class Coverage(JavaRecord):
    """限定统计分母；UNKNOWN 表示未分类，不能被自动算成负面或零分。"""

    eligibleComments: Count
    classifiedComments: Count
    unknown: Count


class Metrics(JavaRecord):
    """复用 Java 的确定性统计结果，Python 不再建立第二套报告计算逻辑。"""

    positive: Count
    negative: Count
    neutral: Count
    positiveShareAmongClassified: float | None


class Provenance(JavaRecord):
    """保留结果模式和模型版本，避免把 MOCK 报告包装为真实模型分析。"""

    mode: Identifier
    modelVersion: Identifier


class FindingReferences(JavaRecord):
    """只保存引用身份，不把结论正文或评论文本复制进图状态。"""

    evidenceIds: list[Identifier] = Field(max_length=100)


class ReportDigest(JavaRecord):
    """图状态使用的报告摘要；完整报告及人工审核仍保存在 Java 业务库。"""

    reportId: Identifier
    snapshotId: Identifier
    provenance: Provenance
    coverage: Coverage
    metrics: Metrics
    findings: list[FindingReferences] = Field(max_length=100)


class EvidenceReference(JavaRecord):
    """只使用引用标识和非空文本校验，不返回或持久化评论正文。"""

    commentId: Identifier
    text: Annotated[str, Field(min_length=1)]


class NoRedirects(HTTPRedirectHandler):
    """拒绝跳转，防止本机入口通过 302 将工具请求转发到外部地址。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """返回 None 让 urllib 报错；所有 HTTP 错误由上层转换为受控错误码。"""
        return None


def uuid_value(value: str) -> str:
    """仅接受规范 UUID，避免把路径片段、查询参数或模型生成的地址当作业务 ID。"""
    try:
        parsed = str(UUID(value))
    except (ValueError, AttributeError, TypeError) as error:
        raise ToolError("INVALID_ID：需要规范 UUID。") from error
    if value != parsed:
        raise ToolError("INVALID_ID：需要小写规范 UUID。")
    return parsed


def backend_address(value: str) -> str:
    """只允许显式回环 HTTP 根地址；不接受域名、凭据、路径、查询和片段。"""
    try:
        parts = urlsplit(value)
        valid = (
            parts.scheme == "http"
            and parts.hostname in {"127.0.0.1", "::1"}
            and parts.username is None
            and parts.password is None
            and parts.path in {"", "/"}
            and not parts.query
            and not parts.fragment
            and parts.port is not None
            and 1 <= parts.port <= 65535
        )
    except ValueError as error:
        raise ToolError("INVALID_BACKEND：地址格式错误。") from error
    if not valid:
        raise ToolError("INVALID_BACKEND：只允许带端口的本机回环 HTTP 根地址。")
    return value.rstrip("/")


class JavaTools:
    """本地单用户的只读 Java 工具客户端，超时后不自动重试。

    地址由启动参数固定，后续节点只能传入 UUID。关闭环境代理和自动跳转，
    使三个白名单工具都停留在指定本机后端。每个响应最多 1 MiB、等待最多 4 秒。
    """

    MAX_BYTES = 1024 * 1024

    def __init__(self, backend: str):
        """验证后端地址并创建专用客户端；不读取任何模型密钥或数据库配置。"""
        self.backend = backend_address(backend)
        self.opener = build_opener(ProxyHandler({}), NoRedirects())

    def _get(self, path: str) -> Any:
        """1、只发 GET；2、有界读取；3、解析 JSON；4、屏蔽原始错误内容。"""
        request = Request(self.backend + path, headers={"Accept": "application/json"}, method="GET")
        try:
            with self.opener.open(request, timeout=4) as response:
                raw = response.read(self.MAX_BYTES + 1)
            if len(raw) > self.MAX_BYTES:
                raise ToolError("RESPONSE_TOO_LARGE：后端响应超过 1 MiB。")
            return json.loads(raw)
        except HTTPError as error:
            raise ToolError(
                f"JAVA_HTTP_{error.code}：Java 工具未成功，请检查任务及后端。"
            ) from None
        except (URLError, TimeoutError, OSError):
            raise ToolError(
                "JAVA_UNAVAILABLE：无法读取本机 Java 服务，可稍后恢复当前流程。"
            ) from None
        except (ValueError, UnicodeError):
            raise ToolError("INVALID_RESPONSE：Java 工具未返回合法 JSON。") from None

    def job(self, job_id: str) -> JobReference:
        """读取用户指定任务，并核对返回身份；不创建、派发或重试任务。"""
        try:
            result = JobReference.model_validate(
                self._get(f"/api/v1/analysis-jobs/{uuid_value(job_id)}")
            )
            if result.id != job_id:
                raise ToolError("SCOPE_MISMATCH：返回任务与请求不一致。")
            uuid_value(result.snapshotId)
            if result.reportId is not None:
                uuid_value(result.reportId)
            return result
        except ValidationError:
            raise ToolError("INVALID_RESPONSE：任务字段不符合约定。") from None

    def report(self, job: JobReference) -> ReportDigest:
        """只能读取成功任务给出的报告，并检查报告与任务的快照关系和统计约束。"""
        if job.status != "SUCCESS" or job.reportId is None:
            raise ToolError("REPORT_NOT_READY：任务尚无成功报告。")
        try:
            report = ReportDigest.model_validate(
                self._get(f"/api/v1/reports/{uuid_value(job.reportId)}")
            )
        except ValidationError:
            raise ToolError("INVALID_RESPONSE：报告字段不符合约定。") from None
        if report.reportId != job.reportId or report.snapshotId != job.snapshotId:
            raise ToolError("SCOPE_MISMATCH：报告与原任务的身份或快照不一致。")
        coverage = report.coverage
        metrics = report.metrics
        if (
            coverage.classifiedComments + coverage.unknown != coverage.eligibleComments
            or metrics.positive + metrics.negative + metrics.neutral != coverage.classifiedComments
        ):
            raise ToolError("INVALID_METRICS：报告计数与分母不一致。")
        share = metrics.positiveShareAmongClassified
        expected = (
            metrics.positive / coverage.classifiedComments
            if coverage.classifiedComments > 0
            else None
        )
        if (expected is None and share is not None) or (
            expected is not None
            and (share is None or not math.isfinite(share) or abs(share - expected) > 1e-8)
        ):
            raise ToolError("INVALID_METRICS：比例与计数不一致。")
        return report

    def evidence(self, report_id: str) -> set[str]:
        """读取绑定报告的原文引用；只返回身份集合，不保存评论正文。"""
        data = self._get(f"/api/v1/reports/{uuid_value(report_id)}/evidence")
        if not isinstance(data, list) or len(data) > 1000:
            raise ToolError("INVALID_RESPONSE：原文列表格式或条数不符合约定。")
        try:
            identifiers = [EvidenceReference.model_validate(item).commentId for item in data]
        except ValidationError:
            raise ToolError("INVALID_RESPONSE：原文缺少有效身份或非空文本。") from None
        if len(identifiers) != len(set(identifiers)):
            raise ToolError("INVALID_RESPONSE：原文引用身份重复。")
        return set(identifiers)
