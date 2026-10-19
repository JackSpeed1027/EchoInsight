# EchoInsight 开发与运行（IDEA / Windows）

维护至第77轮。模块/业务/架构只看[总体基准](specs/EchoInsight-项目开发基准-v2.6.md)，URL只看[接口总览](api/接口总览.md)。本页集中运行、配置和排障，不按轮次堆叠历史状态。

## 2. 已配置的环境

| 工具             | 实际配置                                               |
| ---------------- | ------------------------------------------------------ |
| Java             | Temurin 21.0.12.1，D:\dev\java\jdk-21.0.12.1           |
| Maven            | 3.9.11，D:\dev\maven\apache-maven-3.9.11，沿用原有镜像 |
| Node / npm       | 24.22.0 / 11.19.1，D:\dev\node\node-v24.22.0-win-x64   |
| pnpm             | 11.25.0，安装在上述独立 Node 目录                      |
| MySQL            | 沿用本机 5.7.44，127.0.0.1:3306                        |
| 项目数据库       | insightflow，沿用原专用账号，权限限于此库        |
| Redis / RabbitMQ | 沿用现有环境，当前骨架无需连接                         |
| 后端 / 前端      | 127.0.0.1:8080 / 127.0.0.1:5173                        |

Node 已加入用户 PATH，原始值备份在 runtime/backups/user-path-before-node.json。重启 IDEA 后，新终端才能继承；也可加载 scripts/env.ps1，仅对当前终端生效。

按用户最新决定使用现有 MySQL，不安装 Docker/WSL。此前新增的 MySQL 8.4 文件保留在 runtime/unused-tools/mysql-8.4.10-winx64，数据位于 runtime/mysql/data；3309 实例已停止，项目脚本不会启动它，也不会被旧环境按版本扫描的脚本选中。旧 MySQL、其他项目库和账号未被替换。

## 3. IDEA 打开及调试

1. File → Open → 打开 D:\Work\Project\InsightFlow 根目录，按 Maven 工程导入，不要只打开 backend。
2. Project Structure → Project SDK 选现有 **temurin-21**，Language level 21。已写入项目 SDK 配置。
3. Settings → Build Tools → Maven：Maven home 选 D:\dev\maven\apache-maven-3.9.11。Importer 与 Runner 的 JDK 都使用 Project JDK 21。沿用已有 settings.xml。
4. Maven 面板 Reload All Maven Projects，等待依赖索引完成。
5. 右上角选择 **EchoInsight Backend** → Run/Debug。若共享配置尚未识别，打开 backend/src/main/java/io/echoinsight/EchoInsightApplication.java，点 main 方法旁运行按钮，工作目录设为项目根目录。
6. 密码自动从 runtime/application-local.properties 读取，不需要放进启动参数。
7. IDEA Terminal 运行前端：

```powershell
. .\scripts\env.ps1
cd frontend
pnpm dev
```

浏览器打开 http://127.0.0.1:5173 ，看到“我的项目”和页头“服务已连接”，即前端 → 后端 → MySQL 已连通；旧示例／历史保留在高级入口。

如 IDEA 提供 Node.js 配置页，Node interpreter 选 D:\dev\node\node-v24.22.0-win-x64\node.exe，package manager 选同目录 pnpm。没有 JavaScript 插件时也可在 Terminal 运行前端。

## 4. 启动和停止

先确保现有 MySQL 正在运行。在项目根目录的两个终端分别执行：

```powershell
# 终端一
.\scripts\start-backend.ps1

# 终端二
.\scripts\start-frontend.ps1
```

在对应终端按 Ctrl+C 停止；IDEA 启动的程序用 IDEA Stop。脚本不会管理已有 MySQL、Redis、RabbitMQ 服务。

构建后也可运行 .\scripts\start-dev.ps1 在后台启动前后端；它输出进程记录路径。停止时运行：

```powershell
.\scripts\stop-dev.ps1 -RecordPath '实际输出的进程记录完整路径'
```

停止脚本核对启动时间和进程命令行，防止误停其他项目；不会删日志或停止数据库。端口被占用时启动脚本直接报错，不强杀进程。

## 5. 构建与回归

先停止属于本项目的后台后端，避免Windows锁定正在运行的JAR；保留原JAR/测试报告备份，不执行clean或删除历史。

```powershell
. .\scripts\env.ps1
mvn -pl backend -am verify
cd frontend
pnpm test
pnpm format:check
pnpm build
```

后端集成测试使用既有本机MySQL、隔离workspace和合成数据，关闭真实模型/平台自动Worker。前端build包含类型检查。测试数量及通过事实见[最新验收](specs/EchoInsight-项目开发基准-v2.6.md#16-第5867轮评论追问版本对照与待评测资料)，不把旧轮次计数当当前结果。IDEA/Maven名称可运行`scripts/check-project-naming.ps1`核对。

## 6. 配置与数据保护

`runtime/application-local.properties`保存本机覆盖配置/模型密钥；`runtime/mysql-app.cnf`供本机CLI读取，不输出或提交凭据。`backend/src/main/resources/application.yml`为默认值。旧数据库`insightflow`、账号、MySQL实例不因产品改名迁移；Flyway只追加迁移，当前新增V20分页检查点表及批次范围列。

新HTTP公开分页使用固定域/路径、时间排序、串行每秒至多一次、单页总期限20秒、响应5MiB；不登录、不读用户Cookie、无自动重试；只保留平台公开握手返回的临时匿名buvid令牌于内存。原CLI仍服务旧限量和UP发现，默认60秒/租约180秒。所有平台任务共用原单线程有界调度器。

文件更新先核对SHA并备份，存档只追加；不删除日志、用户资料、历史任务、分析结果或备份。不自动清理数据库/目录。无认证授权前仅本机使用。

## 7. 采集与分析排障

| 现象 | 实际含义／处理 |
|---|---|
| 8080/5173占用 | 先读进程记录并比对PID、启动时间、命令行；不强杀陌生进程 |
| B站失败 | 页面看安全原因和批次ID。公开遍历成功页在`source_collection_page`；retry继续下一页，旧限量任务仍按整个命令重试 |
| 平台风险/429 | 停止本次执行，保留检查点；不自动换IP/身份/并发或循环重试 |
| PARTIAL_FAILED | 回复计数、父引用或平台总数有缺口；只代表已保存范围，不能宣称所有观众 |
| VISIBLE_EXHAUSTED | 根/回复分页到终止，计数/父关系核对通过；仍不证明包含隐藏/删除内容 |
| 5000条/512页/16MiB页存储/5MiB最终适配边界 | 保留成功页并明确失败；目前无自动跨快照大规模拆分，不把容量停止标为完成 |
| 同ID正文变化 | 新建采集批次形成新版本；不在旧检查点静默混入修订 |
| 模型UNKNOWN | 外部已发送后结果/费用不确定；保留预算，不自动付费重试 |
| 完整命题无法提交 | 看缺父/单条语境超界/费用确认/预算或模型配置，不能通过改常量跳过 |

公开分页任务元数据→根评论→逐根回复→发布，每次提交一页后短暂回PENDING是正常调度；attempts为实际认领次数，不再等于整批从头抓取次数。completedPages才是已提交页数。进度未完成不会生成半份项目资料。

## 8. 工程位置与验收证据

`backend/src/main/java/io/echoinsight`按业务包组织；`frontend/src`为工作台；`tools`为格式桥接；`scripts`为本机运行；`datasets`区分DRAFT和工程合成样本；`runtime/collections`为追加存档，`runtime/backups`为校验备份。

本轮代码、迁移、恢复与HTTP证据在[验收与边界](specs/EchoInsight-项目开发基准-v2.6.md#16-第5867轮评论追问版本对照与待评测资料)。真实平台探测若被拒绝，只记拒绝，不以工程替身推断真实全量或模型效果。

## 9. 新机器克隆与GitHub准备

需要JDK21、Maven3.9、Node24、pnpm11.25和独立开发用MySQL5.7库。先安装这些工具并配置JAVA_HOME/PATH；PowerShell脚本优先现有环境，只在可用时采用原本机D盘路径，不修改系统配置。

1. 把`config/application-local.example.properties`复制到`runtime/application-local.properties`，填写自己的开发库连接；已有配置请手动合并，不能覆盖。`.env.example`是变量说明，不会自动加载。
2. 在frontend执行`pnpm install --frozen-lockfile`，后端在项目根执行`mvn -pl backend spring-boot:run`；另一个终端执行`pnpm --dir frontend dev`。模型和在线采集默认关闭，先用合成资料演示。
3. 开启公开采集需服务器配置enabled/worker-enabled；新分页模式不需要安装CLI，旧限量/UP发现仍需固定CLI。租约至少90秒，默认180秒；每次HTTP响应含体总期限20秒，首次匿名握手/nav可使一次页处理涉及多个HTTP请求。
4. 运行`python tools/repository_audit.py`检查Git公开范围、疑似密钥和文档链接；输出不回显密钥。当前只做检查，按用户要求不生成源码ZIP；直接使用IDEA Commit/Push或Git命令上传。扫描不是绝对无泄漏保证，上传前仍浏览待提交差异。
5. 确认检查通过后再由你执行git add/commit、添加自己的远程和push；本轮没有替你提交或发布。GitHub CI已准备，但远程运行状态须以上传后的Actions结果为准。

注释规范：文件/类/方法解释职责、输入输出和约束，复杂逻辑按1、2、3、4写步骤；幂等、事务、租约、预算和未知费用说明原因。后续修改同步总体基准和唯一API总览的对应章节，不再平行增加业务/架构说明。


## 10. 第48～57轮：当前构件与免费验收

当前Flyway V22，35张表（含历史表）；261后端测试（118单元＋143真实MySQL集成）、15前端行为测试、17离线工具测试，68业务API。迁移只追加独立字幕与请求覆盖表，未改本机连接/凭据。后端完整日志`runtime/round57-full-verify.log`；本轮汇总`runtime/rounds48-57-delivery-summary.json`，记录源码/JAR/服务/包身份。前端首次Vue编译失败与后续修复日志同时保留，不删除旧证据。

资料页“字幕文件”上传UTF-8 SRT/VTT；需先有该作品的评论资料，标题/来源是阅读信息，上传不会自动分析。提问时显式选版本，预检展示词法邻域与来源覆盖。无字幕命中应补材料/调整问题，不能因为上传成功就声称视频已理解；最大字幕时间也不是实际视频时长。报告筛选/导出均只读、不触发模型。

离线工具（项目根目录）：

```powershell
python -m unittest discover -s tools -p 'test_*.py'
python tools/evaluate_stance.py
# 发布闸门检查，当前空DRAFT预期返回非0，不能删除该闸门来通过
python tools/evaluate_stance.py --require-reviewed
```

`datasets/proposition/review-cases.jsonl`有意为空；字段/流程见review-plan-v6.json和工具说明。真实样本需来源授权、命题、评论/父链、dev/test及独立裁决声明，预测从实际产物提取；同作品、重复文本和指定近重复组禁止跨划分。未裁决、遗漏预测、坏证据不能当好成绩，工具不认证标注者独立性或语义正确性，不是独立质量通过证明。

HTTP/UI验收在独立workspace和8081/5174端口，关闭模型/付费/平台连接器，Worker只处理指定合成execution-scope。正式8080可能已显式配置DeepSeek，不在正式服务上提交测试请求。临时服务只在PID/启动时间/命令标记吻合后停止；正式8080/5173保留供用户测试，最新runtime/dev-processes记录给出身份。不要按端口盲目杀进程。

本轮未访问平台、未新增付费模型调用；真实412、视频/ASR、独立语义质量与跨版本复用缺口见基准§15。GitHub远程CI仍未运行，源码包使用repository_audit.py核验导出，不能作为数据库/历史备份。


## 11. 第58～67轮运行与验收（入口见根目录README）

当前V23/36张表，286后端测试（130单元＋156 MySQL集成）、15前端行为测试、20离线工具测试、75业务API。原本机库和凭据不改；分析任务/预算/检查点沿用原表，只追加analysis_continuation关系表。后端最终日志runtime/round67-full-verify-release.log，首次参数错误和别名集成失败日志另存，不覆盖证据。前端runtime/round67-frontend-*.log，工具runtime/round67-tools-tests.log，最终汇总runtime/rounds58-67-delivery-summary.json。

成功报告下展开追问或版本对照，操作、字段和上限见基准§16。追问提交为新任务，模型启用时独立计费；先看预检，不把历史结构演示当语义结果。版本对照纯只读，不因前后选取而重跑模型。

待评测包含原文、背景及预测，尚未匿名，下载到忽略的runtime或仓库外私有目录。示例：

```powershell
python tools/prepare_review.py runtime/evaluation-candidate.json runtime/private-review-new
python tools/evaluate_stance.py --cases runtime/private-review-new/review-cases.jsonl --predictions runtime/private-review-new/actual-predictions.json
```

输出目录必须不存在，工具不会覆盖旧标注。gold仍为空；真实来源默认UNVERIFIED，须确认权限、隐藏预测独立标注并裁决，再用require-reviewed检查；未经标注不会生成成绩。原报告别名映射随provenance保存，便于核对判断身份，正文不变。

本轮HTTP/UI在独立workspace、delivery-58-67执行范围、8081/5174端口，强制关闭真实模型/收费/平台；MODEL回放来自集成测试内存供应商。正式8080/5173保留，临时按PID/时间/命令核对停止。包检查仍使用repository_audit.py，未远程执行GitHub CI或提交推送。


## 12. 第68～77轮：任务中心、取消与Git上传

当前V24/37张表（含历史表），309后端（136单元＋173 MySQL集成）、20前端行为、20离线工具测试，80业务API。新增TaskPolicy/ProjectTaskQuery/TaskControlService/ProjectTaskController及TaskControlPanel/ProjectTaskCenter；TaskControlWorkflowIT使用task-control-it工作空间与execution-scope、内存平台页/模型账本模拟，不连接真实平台或付费供应商。

运行方式不变。在项目导航进入“任务中心”，读取分析/采集详情后明确取消、继续或安全重试。自然语言分析当前任务也显示控制面板；取消后旧轮询终止。取消不等于供应商不计费；SENT/UNKNOWN仍保留预留，禁止直接继续。成功模型片、原步骤、采集页和输入版本不清理。完整规则和每轮作用见基准§17，全部URL/类/关系见API§24。

工程日志：runtime/round77-full-verify-final.log；round77-frontend-tests.log、round77-frontend-format.log、round77-frontend-build-release.log、round77-tools-tests.log；集中汇总runtime/rounds68-77-delivery-summary.json。初次格式/模板/类型错误及修正日志保留，历史记录不覆盖。HTTP/UI在独立delivery-68-77范围8081/5174验收，强制关闭付费/平台；取消操作不在真实项目试做。完成后仅按登记PID/时间/命令停止临时服务，正式8080/5173保留。

用户本轮明确授权提交并推送[JackSpeed1027/EchoInsight](https://github.com/JackSpeed1027/EchoInsight)，覆盖§9中旧的“用户自行上传”操作范围。上传前运行repository_audit.py且核对实际暂存清单：仅源码、合成样本、无密钥模板、CI与三份正文/导航。runtime/真实采集/候选评测/凭据/备份/截图/历史文档和AGENTS留本机、Git忽略，不使用--export或git add -f。不改项目自身许可证、不强推或替换远程历史；实际提交与远程校验记录见本机交付汇总。

任务中心没有账号认证，供本地单人使用；未知账单对账、独立质量裁决、真实容量和数据库备份恢复演练仍待验收。不能因本机MySQL集成通过就声称生产规模或语义准确率已证明。


浏览器验收发现MySQL5.7在JSON标题与ASCII采集URL的UNION中将中文降为问号；原快照UTF8正文/哈希不变，查询两侧显式CONVERT USING utf8mb4修正，并在MySQL集成断言中文标题原文。首次构建通过不掩盖这个界面缺陷，修复后回归日志另存round77-full-verify-unicode.log。


第77轮首次GitHub Linux CI发现CLI输出超限后，终止子进程导致另一管道Stream closed，遮蔽已确认的TOOL_OUTPUT_LIMIT。使用每次执行共享的原子标记，在终止前记录超限并优先保留该原因；普通流失败/超时规则不放宽。新增stderr超限与双管道并发超限子进程回归，最终309后端（136单元＋173集成）；失败日志round77-ci-initial-backend.log保留，修复不跳过原测试。
