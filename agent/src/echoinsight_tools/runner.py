"""文件职责：保存本地图运行、查询状态和恢复失败节点，不清理历史检查点。

SQLite 仅保存 LangGraph 执行摘要，不迁移 MySQL。单个 run 使用文件锁，
防止两个本机命令同时推进同一运行；这是本地开发方式，不声称分布式调度完成。
"""

from contextlib import contextmanager
from pathlib import Path
from uuid import uuid4

from langgraph.checkpoint.sqlite import SqliteSaver

from echoinsight_tools.graph import GRAPH_VERSION, AgentState, build_graph
from echoinsight_tools.tools import JavaTools, ToolError, uuid_value


@contextmanager
def run_lock(directory: Path, run_id: str):
    """在本机为一个 run 取得非阻塞互斥锁，结束只解锁，保留锁文件便于审计。"""
    import msvcrt

    lock_path = directory / f"{uuid_value(run_id)}.lock"
    with lock_path.open("a+b") as stream:
        if stream.tell() == 0:
            stream.write(b"\0")
            stream.flush()
        stream.seek(0)
        try:
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            raise ToolError("RUN_BUSY：该运行正在被另一个本机命令处理。") from None
        try:
            yield
        finally:
            stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)


class AgentRunner:
    """为单个 SQLite 文件提供运行生命周期，所有历史数据默认保留。"""

    def __init__(self, directory: Path):
        """创建独立状态目录；调用者仅应传入项目 runtime 内的专用目录。"""
        self.directory = directory.resolve()
        self.directory.mkdir(parents=True, exist_ok=True)
        self.database = self.directory / "checkpoints.sqlite"

    def start(self, job_id: str, task: str, backend: str, run_id: str | None = None) -> dict:
        """1、生成并固定身份；2、写入初始状态；3、执行图；已有运行不可被覆盖。"""
        run_id = uuid_value(run_id or str(uuid4()))
        tools = JavaTools(backend)
        state: AgentState = {
            "run_id": run_id,
            "graph_version": GRAPH_VERSION,
            "backend": tools.backend,
            "job_id": uuid_value(job_id),
            "task": task,
        }
        config = {"configurable": {"thread_id": run_id}, "recursion_limit": 12}
        with (
            run_lock(self.directory, run_id),
            SqliteSaver.from_conn_string(str(self.database)) as saver,
        ):
            graph = build_graph(tools, saver)
            if graph.get_state(config).values:
                raise ToolError("RUN_EXISTS：运行已存在，请使用 resume 或 show。")
            graph.invoke(state, config, durability="sync")
            return self._view(run_id, graph.get_state(config))

    def existing(self, run_id: str, resume: bool) -> dict:
        """读取旧状态；恢复时复用原地址与原输入，已完成运行只返回结果不再调用工具。"""
        run_id = uuid_value(run_id)
        config = {"configurable": {"thread_id": run_id}, "recursion_limit": 12}
        with (
            run_lock(self.directory, run_id),
            SqliteSaver.from_conn_string(str(self.database)) as saver,
        ):
            checkpoint = saver.get_tuple(config)
            if checkpoint is None:
                raise ToolError("RUN_NOT_FOUND：找不到该运行。")
            values = checkpoint.checkpoint["channel_values"]
            if values.get("graph_version") != GRAPH_VERSION:
                raise ToolError("RUN_VERSION_CHANGED：请先处理旧版本运行，不能静默换图。")
            graph = build_graph(JavaTools(values["backend"]), saver)
            snapshot = graph.get_state(config)
            if resume and snapshot.next:
                graph.invoke(None, config, durability="sync")
                snapshot = graph.get_state(config)
            return self._view(run_id, snapshot)

    @staticmethod
    def _view(run_id: str, snapshot) -> dict:
        """返回业务结果、下一节点和有限错误码；不输出图内原始异常或完整检查点。"""
        return {
            "runId": run_id,
            "graphVersion": GRAPH_VERSION,
            "next": list(snapshot.next),
            "result": snapshot.values.get("result"),
            "recovery": "存在下一节点时可使用 resume；完成运行不会重新读取 Java。",
        }
