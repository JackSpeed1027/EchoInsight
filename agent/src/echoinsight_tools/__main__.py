"""文件职责：提供 EchoInsight 本地 LangGraph 命令入口，输出可保存的 JSON 验收记录。

run 创建新运行，show 只读已有图状态，resume 继续未完成节点。
本轮不启动新 HTTP 服务，Java 全部 API 的地址与关系仍维护在同一个接口文档。
"""

import argparse
import json
import sys
from pathlib import Path
from uuid import uuid4

from echoinsight_tools.runner import AgentRunner
from echoinsight_tools.tools import ToolError


def main() -> int:
    """解析显式任务，输出运行身份；失败也保留 runId，方便原地恢复而非重头创建。"""
    parser = argparse.ArgumentParser(description="EchoInsight LangGraph 本地报告工具")
    commands = parser.add_subparsers(dest="command", required=True)
    start = commands.add_parser("run", help="以新运行读取一个现有 Java 任务")
    start.add_argument("--job", required=True, help="成功的 Java 任务 UUID")
    start.add_argument("--task", choices=["overview", "evidence"], required=True)
    start.add_argument("--backend", default="http://127.0.0.1:8080")
    for name in ["show", "resume"]:
        command = commands.add_parser(name)
        command.add_argument("--run", required=True, help="之前输出的图运行 UUID")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    runner = AgentRunner(root / "runtime/agent")
    run_id = str(uuid4()) if args.command == "run" else args.run
    try:
        if args.command == "run":
            result = runner.start(args.job, args.task, args.backend, run_id)
        else:
            result = runner.existing(run_id, resume=args.command == "resume")
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except ToolError as error:
        print(
            json.dumps({"runId": run_id, "error": str(error)}, ensure_ascii=False), file=sys.stderr
        )
        return 2
    except Exception:
        # 原始异常可能带协议正文或底层路径，只公开稳定错误码；检查点保持原状。
        print(
            json.dumps(
                {"runId": run_id, "error": "GRAPH_FAILED：执行失败，可查看并恢复本运行。"},
                ensure_ascii=False,
            ),
            file=sys.stderr,
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
