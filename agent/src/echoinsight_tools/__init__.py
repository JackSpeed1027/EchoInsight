"""EchoInsight 编排模块：只调用本机 Java 只读工具，保存本地执行状态，不调用模型。

Java 是业务事实的权威来源；此模块不连接 MySQL、不修改报告、不管理模型账单。
本地开发关闭自动云端追踪，避免运行环境中的追踪变量意外上传报告摘要。
"""

import os

os.environ["LANGSMITH_TRACING"] = "false"
os.environ["LANGCHAIN_TRACING_V2"] = "false"
