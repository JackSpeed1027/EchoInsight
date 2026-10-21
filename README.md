# EchoInsight

面向个人创作者/研究者的内容与讨论智能分析工作台，以Java后端为主。把平台作品和自己的资料保存成长期项目版本，用自然语言整理议题、观点、理由及原文证据。

当前新增无评论的独立内容材料与提问、统一任务中心与安全取消/继续、项目/快照、B站URL与UP候选接入、平台文件导入、参考背景、讨论/明确命题多主张、完整快照分片恢复、集合差分、模型预算/调用账本、同作品字幕版本及时间背景、来源覆盖冻结、报告筛选/分页/导出、评论追问分支、版本对照及待评测候选和安全错误说明。单URL新增逐页公开采集恢复；独立语义质量、真实完整采集、视频理解、动态补检和团队鉴权仍有缺口。

## 当前交付与文档入口

**已交付至第137轮，并完成UI整体优化**：普通讨论可完整分片、逐层汇总；可显式绑定内容原文对照，同作品更新可复用完全一致的成功片段。项目导航、问题表单、报告/证据、状态与窄屏布局已统一整理。本机448后端（204单元＋244集成）、34前端行为及20工具测试通过，90条业务API；CI和实际运行事实见本机runtime/rounds108-137-delivery-summary.json。独立语义质量仍DRAFT，真实完整采集、DeepSeek受控质量、ASR/画面和团队鉴权仍需验收。

| 想了解什么 | 入口 |
|---|---|
| 功能、业务逻辑、整体架构、每轮交付与未完成项 | [产品与架构基准](docs/specs/EchoInsight-项目开发基准-v2.6.md#21-第108137轮完整讨论内容对照保守增量与ui整体优化) |
| IDEA配置、启动、测试与排错 | [开发操作](docs/development.md) |
| 全部90条接口的URL、用途、实现类和关系 | [接口总览](docs/api/接口总览.md) |

## GitHub仓库与上传范围

代码仓库：[JackSpeed1027 / EchoInsight](https://github.com/JackSpeed1027/EchoInsight)。本轮按用户授权直接提交并推送Git工作区，不生成源码压缩包。

`runtime`、本机配置和凭据、真实采集、截图、备份、目标简历和原始说明排除上传，仍保留本机。公开内容为源码、合成样本、三份主文档与导航。后续提交前运行：

```powershell
python tools/repository_audit.py
git status --short
```

不要使用`git add -f`强行加入忽略文件。开发库与正式运行配置均不在仓库；克隆后按下方步骤配置。GitHub Actions实际状态以仓库页面为准，本机回归通过不等于远程CI已通过。

采用Java21 / Spring Boot / MySQL模块化单体、持久工作流和Spring AI语义节点，前端Vue3/TypeScript。动态Agent只在局部补检确有收益后引入，不作为功能完成的前提。

日常文档已合并为[产品与架构基准](docs/specs/EchoInsight-项目开发基准-v2.6.md)、[开发操作](docs/development.md)、[全部接口](docs/api/接口总览.md)三份正文；[文档导航](docs/README.md)给出阅读顺序和模块位置。历史文档及备份保留，不再平行维护。

IDEA打开工程根目录，使用 **EchoInsight Backend**，主类`io.echoinsight.EchoInsightApplication`。物理路径`D:\Work\Project\InsightFlow`；保留本机MySQL5.7数据库`insightflow`和原凭据。

```powershell
.\scripts\start-backend.ps1
.\scripts\start-frontend.ps1
```

前端 http://127.0.0.1:5173/ ，后端 http://127.0.0.1:8080/ 。已有进程先核对`runtime/dev-processes-*.json`，不要重复启动或误停其他服务。最新[验收与边界](docs/specs/EchoInsight-项目开发基准-v2.6.md#21-第108137轮完整讨论内容对照保守增量与ui整体优化)说明实际验证与限制。

进入项目“文本与字幕”保存独立原文并提问；“智能分析”面向评论，也可显式选择内容背景。四个常用入口和“更多”分层组织，打开报告后输入表单收起，原文/费用按需展开。完整操作、架构取舍、每轮验收与后续闸门见开发基准§21。

## 模块与目录

| 目录 | 作用 |
|---|---|
| backend | 业务API、接入/版本、持久工作流、模型预算、分析和证据 |
| frontend | 项目工作台、自然语言入口、任务进度和结果 |
| config / scripts | 无密钥模板与Windows启动/验证脚本 |
| tools / datasets | 离线桥接、上传检查、合成样本和DRAFT评测准备 |
| agent | 不参与主部署的可选只读LangGraph实验 |
| docs | 产品/架构、开发、API三份正文及导航 |

## 克隆后运行

安装JDK21、Maven3.9、Node24、pnpm11.25及MySQL5.7开发库。将`config/application-local.example.properties`复制到忽略的`runtime/application-local.properties`并填写自己的数据库连接；不要覆盖已有配置。模型和采集默认关闭，无需密钥即可跑合成演示。

```shell
pnpm --dir frontend install --frozen-lockfile
mvn -pl backend spring-boot:run
# 另开终端
pnpm --dir frontend dev
```

验证：`mvn verify`、`pnpm --dir frontend test`、`pnpm --dir frontend format:check`、`pnpm --dir frontend build`。GitHub Actions已随仓库提交运行，最新结果以仓库Checks和本机交付汇总核对。

上传前运行`python tools/repository_audit.py`检查Git待上传范围；直接通过Git提交/推送，不生成源码压缩包。本机运行资料、密钥、旧验收、目标简历、原说明及截图保留在磁盘并排除上传。开源许可待选择，[第三方来源](THIRD_PARTY_NOTICES.txt)单列。
