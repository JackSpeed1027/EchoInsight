"""文件职责：用 LangGraph 串联任务读取、报告读取和两条可审计的只读路径。

目前是确定性工具流程，没有 LLM Planner，不把关键词路由冒充自主 Agent。
未来动态计划也必须经过工具白名单和范围校验，Java 任务与模型预算仍是唯一业务账本。
"""

from typing import Any, Literal, TypedDict

from langgraph.graph import END, START, StateGraph

from echoinsight_tools.tools import JavaTools, JobReference, ReportDigest, ToolError, uuid_value

GRAPH_VERSION = "report-tools-v1"
Task = Literal["overview", "evidence"]


class AgentState(TypedDict, total=False):
    """各节点的可持久化输入输出，不包含密钥、评论正文或任意工具地址。

    run_id 是图运行身份，job_id 是 Java 业务任务身份，两者不能混用。
    backend 和 task 在首次启动时固定；恢复时读取原状态，不允许悄悄换数据源。
    """

    run_id: str
    graph_version: str
    backend: str
    job_id: str
    task: Task
    job: dict[str, Any]
    report: dict[str, Any]
    audit: dict[str, Any]
    result: dict[str, Any]


def build_graph(tools: JavaTools, checkpointer):
    """构建明确节点和条件边，并启用调用者提供的 SQLite 检查点。

    每个成功节点的输出由 LangGraph 保存。读取失败向外抛出受控异常，
    后续使用同一 run_id 恢复，只继续尚未完成的节点。
    """

    def validate_scope(state: AgentState) -> dict:
        """1、验证运行和任务身份；2、锁定图版本、工具地址及允许任务类型。"""
        uuid_value(state["run_id"])
        uuid_value(state["job_id"])
        if state["graph_version"] != GRAPH_VERSION or state["backend"] != tools.backend:
            raise ToolError("RUN_SCOPE_CHANGED：图版本或后端已改变。")
        if state["task"] not in {"overview", "evidence"}:
            raise ToolError("UNSUPPORTED_TASK：只支持报告概览或证据引用检查。")
        return {}

    def load_job(state: AgentState) -> dict:
        """从 Java 取得权威任务状态；未完成任务返回待补条件，不擅自重试或开始分析。"""
        job = tools.job(state["job_id"])
        if job.status != "SUCCESS" or job.reportId is None:
            return {
                "job": job.model_dump(),
                "result": {
                    "status": "NEEDS_INPUT",
                    "reason": "请先在工作台完成该任务，再以新的图运行读取最终报告。",
                    "jobId": job.id,
                    "jobStatus": job.status,
                    "mode": "FIXED_WORKFLOW",
                },
            }
        return {"job": job.model_dump()}

    def after_job(state: AgentState) -> str:
        """未准备好的业务任务直接结束；有报告时才允许进入报告读取节点。"""
        return "stop" if state.get("result", {}).get("status") == "NEEDS_INPUT" else "report"

    def load_report(state: AgentState) -> dict:
        """读取并持久化有限的报告摘要；关联检查在工具层完成，原文不进入检查点。"""
        report = tools.report(JobReference.model_validate(state["job"]))
        return {"report": report.model_dump()}

    def route_task(state: AgentState) -> str:
        """按用户明确选择的任务走不同路径，不调用模型猜测意图。"""
        return state["task"]

    def audit_evidence(state: AgentState) -> dict:
        """核对报告引用是否能取到原文；存在性检查不能证明语义支持或样本代表性。"""
        report = ReportDigest.model_validate(state["report"])
        expected = {item for finding in report.findings for item in finding.evidenceIds}
        found = tools.evidence(report.reportId) if expected else set()
        if found - expected:
            raise ToolError("SCOPE_MISMATCH：原文工具返回了报告未引用的身份。")
        missing = sorted(expected - found)
        return {
            "audit": {
                "requestedCount": len(expected),
                "foundCount": len(found),
                "missingIds": missing,
                "referenceStatus": "MISSING"
                if missing
                else "COMPLETE"
                if expected
                else "NO_REFERENCES",
                "semanticSupport": "NOT_EVALUATED",
            }
        }

    def compose(state: AgentState) -> dict:
        """组装可读结果并保留 MOCK 来源；不生成新语义判断、不改写 Java 报告。"""
        report = ReportDigest.model_validate(state["report"])
        result = {
            "status": "COMPLETE",
            "mode": "FIXED_WORKFLOW",
            "task": state["task"],
            "jobId": state["job_id"],
            "reportId": report.reportId,
            "snapshotId": report.snapshotId,
            "provenance": report.provenance.model_dump(),
            "coverage": report.coverage.model_dump(),
            "metrics": report.metrics.model_dump(),
            "limitations": [
                "本轮为 LangGraph 固定只读流程，没有模型规划或付费调用。",
                "引用存在不等于语义支持，语义判断请使用人工核验。",
                "当前范围是本机单用户，不具备多租户授权隔离。",
            ],
        }
        if "audit" in state:
            result["evidenceAudit"] = state["audit"]
        return {"result": result}

    builder = StateGraph(AgentState)
    builder.add_node("validate_scope", validate_scope)
    builder.add_node("load_job", load_job)
    builder.add_node("load_report", load_report)
    builder.add_node("audit_evidence", audit_evidence)
    builder.add_node("compose", compose)
    builder.add_edge(START, "validate_scope")
    builder.add_edge("validate_scope", "load_job")
    builder.add_conditional_edges("load_job", after_job, {"stop": END, "report": "load_report"})
    builder.add_conditional_edges(
        "load_report", route_task, {"overview": "compose", "evidence": "audit_evidence"}
    )
    builder.add_edge("audit_evidence", "compose")
    builder.add_edge("compose", END)
    return builder.compile(checkpointer=checkpointer)
