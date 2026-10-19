# LangGraph 可选只读实验

此目录只用于学习固定图/检查点，不属于EchoInsight主部署，也不是自主Agent。Java后端、Vue工作台不依赖Python环境；核心技术边界见[产品基准](../docs/specs/EchoInsight-项目开发基准-v2.6.md#72-agentic-workflow的具体边界)。

该实验通过类型化只读HTTP工具读取已有报告，不直接操作MySQL、不采集平台、不执行付费模型或改报告。安装与具体参数以`pyproject.toml`及`python -m echoinsight_tools --help`为准；不要为运行主项目安装此目录。

测试可在独立虚拟环境安装后运行`python -m unittest discover -s tests`。检查点与日志仅保存到本机runtime，禁止作为缓存擅自删除。Python依赖固定在requirements.lock.txt，许可证由各依赖自行声明。
