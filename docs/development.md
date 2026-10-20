# EchoInsight 开发与运行（IDEA / Windows）

维护至第97轮。模块/业务/架构只看[总体基准](specs/EchoInsight-项目开发基准-v2.6.md)，URL只看[接口总览](api/接口总览.md)。本页集中运行、配置和排障，不按轮次堆叠历史状态。

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


## 13. 第78～87轮：独立内容的运行、调试与记录

当前343后端（153单元＋190 MySQL集成）、25前端行为、20离线工具测试，88业务API，V25/39张表。新增content包的材料/选择/冻结输入/模型协议/业务执行；内容提示和Spring AI适配独立版本，基础任务和预算仍是原模块。未增加Python媒体服务或Agent部署依赖，IDEA启动配置不变。

项目“内容材料”支持先上传TXT/MD/SRT/VTT再提问，不需要评论；菜单/首页数量区分内容材料和作品评论版本。原“智能分析”继续用于评论。第一次使用先看范围预检，再决定接受有限输入或缩小字幕时间。源码位置、业务原因、容量与限制见基准§18；全部URL和上下游见API§25。

V25允许CONTENT任务没有评论snapshot_id，旧外键保持；只追加content_material与content_analysis_input，后者不复制任务状态。Worker在旧ImportDocument读取之前分流CONTENT，既有评论任务/计划/JSON/快照不回填。纯内容产物只绑定原job_id，schemaVersion=content-artifact-v1；定位分字符和时间，不能交给旧评论立场报告解析。

本机日志runtime/round87-compile.log、round87-full-verify-final.log、round87-frontend-*-final.log、round87-tools-tests.log；汇总runtime/rounds78-87-delivery-summary.json。首轮合成字符串字数断言错误及修复日志另存，不覆盖测试历史。HTTP/UI验收用8081/5174与delivery-78-87范围，模型、收费、平台关闭；模型路径通过内存供应商测试原网关和账本，无DeepSeek质量成绩。

常见错误：文件400先查UTF8、格式、2MiB/200000字符；422查无正文、范围遗漏未同意或预算不足；409检查同键输入是否改动或任务状态；费用未决看原执行记录。MODEL_CONTENT_INVALID是内容结构/引用/用量校验拒绝，发送后仍为MODEL_OUTCOME_UNKNOWN并保留预留，不以无报告认定未收费。

日常仍只维护三份正文加导航，验收追加旧集中记录。按照既有授权审计后同步同一GitHub仓库，不生成源码ZIP；原真实采集/文件/凭据/候选标注/备份/旧说明留本机。独立质量、真实平台完整遍历、长内容全量归并、ASR/画面、备份恢复和团队仍待验收，不能由结构演示或CI通过替代。

页面实际验收发现数字秒数由Vue隐式转为number，原字符串trim预检报错；已统一数字/字符串空值处理，新增0秒/数字/清空混合值回归。并同步当前任务与历史卡片状态，内容保存/接受后只刷新项目数量，不卸载正在填写的子页面。前端最终25项；首轮失败与修复日志均保留。


## 14. 第88～97轮：完整内容分片的启动、调试与验收

IDEA启动配置和本机MySQL不变，无新部署依赖/迁移；仍V25/39表、88业务接口。新增ContentChunkPlan/ContentFullContract/ContentFullAnalysis和ContentFullPrompt，复用原任务、Worker、账本及Spring AI客户端。产品范围/每轮用途见基准§19，现有API增量见接口§26。

操作：项目→内容材料→选择版本→自然语言问题→“完整处理所选范围”→预检→提交→进度/证据。FULL逐片100完整段/12000字、最多24片；费用是N＋1次调用上界，单片也需一次汇总，原预算不自动增加。免费演示不调用模型，成功只表示结构演示完成，已执行语义片数0。PREFIX保持旧40段前缀，客户端省略模式仍用旧方式。

进度来自原model_batch；MAP为0～N−1、REDUCE=N。成功片和CONTENT草稿能恢复；UNKNOWN保留预留禁止重试。422类输入错误先看窗口/24片上限/全任务预算；400检查coverageMode仅FULL/PREFIX和格式，409核对同键是否换范围或方式。没有新增“强制继续收费”入口。

本机验收隔离delivery-88-97工作空间/execution-scope与8081/5174，真实模型/收费/平台关闭。正式服务在构建后恢复8080/5173。runtime/round97-*保留工程日志/页面证据，runtime/rounds88-97-delivery-summary.json记录实际测试数、Git/CI和最新进程身份。首轮夹具调用名写错、严格JSON尾随数据错误码断言修正的日志保留，不改变产品解析规则。日常仍三份正文＋导航，不创建额外小文档或源码ZIP。

最终本机回归378后端（173单元＋205 MySQL集成）、27前端行为、20离线工具测试；前端格式/类型/构建通过，仍88业务路由、V25/39表。完整后端日志round97-full-verify-release.log，前端round97-frontend-*-release.log，工具round97-tools-tests.log。构建和报告先备份后验证，首次失败记录保留。

HTTP验收发现内容容量422被通用提示遮蔽；已将固定的容量、窗口、模式、编码和全任务预算原因加入白名单，保留未知底层原因隐藏规则。新增HTTP超限原文提示与隐私边界回归，初次失败响应留在本机记录；不向页面反射任意异常正文。最终后端日志round97-full-verify-ui.log。

最终页面复核补充：内容历史返回coverageMode并明确区分完整范围/有限预览；当前报告同样标范围，费用/执行技术信息折叠以优先阅读业务结果。标记读取原冻结提示版本，无新状态表或新接口。

范围预检/提交失败在当前提问表单内显示，避免错误只出现在长页面顶部而看不到；上传/列表错误仍在各自原入口。该改动经实际超限页面复核。

页面最终版本前端日志为round97-frontend-tests-ui.log、round97-frontend-format-ui.log、round97-frontend-build-ui.log。完整报告和旧有限报告的历史标记经真实MySQL回归核对，均保留原任务身份。


## 15. 第98～107轮：修订后的增量内容分析

### 操作与验收顺序

在IDEA继续打开根工程，包名io.echoinsight、主类EchoInsightApplication和本机MySQL保持不变，无新增服务/迁移。前后端启动仍用原scripts入口；先按最新runtime/dev-processes记录核对端口归属，不重复启动。

1. “内容材料”上传TXT/MD/SRT/VTT，使用MODEL配置完成一次新版FULL语义报告；免费模式只检查表单、范围和任务流程，不具备语义缓存响应。
2. 点材料“上传此版修订”，保存直接子版本。保持问题、标题、来源、模型/费用计划不变，以便稳定局部输入可命中；不要拿两个没有父子关系的文件充当修订。
3. 选择新版本，从“分析方式”显式选择基准；原问题自动沿用且暂只读。基准候选按服务端分页，只列当前/直接父版本成功v3 MODEL/LOCAL_STUB。生产正常配置只会产生真实MODEL或免费MOCK；LOCAL_STUB是测试供应商。
4. 预检复用数和新调用数。原片数N、命中K，新调用N−K＋1；全部命中仍新汇总1次。上界是保守估计，实际模型用量/本任务结算看执行记录，历史费用看原任务。
5. 提交并查看逐片摘要、最终结论、当前原文位置和复用来源。想改问题/强制重新分析时先选“全部重新分析”。不可把结构演示当已完成语义分析，也不把缓存收益当质量提升。

### 实现位置与可维护边界

| 类／页面 | 职责及和原代码的关系 |
|---|---|
| ContentReuse | v3局部Payload、严格别名/哈希、完整计划指纹、冻结凭证、当前原文映射及出处投影 |
| ContentAnalysisStore | 预检/创建时验证基准，原项目锁/幂等事务冻结；历史分页可带目标材料筛候选 |
| ContentFullAnalysis | 仍为原CONTENT步骤，缓存片不建新账，变化片/每次汇总走原网关 |
| ContentFullContract / ContentFullPrompt | v2兼容不改；v3模型MAP实际输入与指纹依赖一致，严格引用闭包 |
| ConfiguredModelPlan / SpringAiContentProvider | 新FULL生成v3；供应商只接受成对提示/输出体系，禁工具/单次出站不放宽 |
| ContentWorkspace.vue / contentTypes.ts | 明确基准、沿用问题、预检和来源/进度；项目/材料切换丢弃迟到候选 |

没有新增状态表/缓存服务，复用凭证在既有冻结输入中。旧PREFIX v1和FULL v2按原计划恢复，不回填旧JSON；新请求不带基准时旧幂等指纹仍兼容，带基准则身份也包含baselineJobId。同键重发仍返回原任务，即使之后服务器配置改变。

### 错误、取消与恢复

- “暂无可选基准”：免费演示、旧v2、未完成任务或非当前/直接父版本会被排除。完成一次新版语义FULL后刷新候选。
- “问题不同／模型协议或费用计划已变化”：取消基准，全部重新分析；不自动修改原计划。
- “基准检查点不可用”：不静默执行补偿调用；不选基准重新提交，保留旧记录用于排查。
- 0片命中可以正常执行，常见原因是文本开头变化导致后文偏移、窗口/标题/来源改变或分片边界移动。不是系统承诺的全局语义相似缓存。
- 取消、继续和安全重试仍在任务中心；在途SENT/UNKNOWN不提供强行重试。复用片与新成功片均保留，最终发布仍受原token围栏保护。

回归运行mvn verify、pnpm --dir frontend test、format:check、build，以及python -m unittest discover -s tools -p "test_*.py"。增量测试类ContentReuseTest、ContentReuseWorkflowIT和SpringAiContentProviderTest；集成测试工作空间/执行范围content-reuse-it，真实模型/付费/平台关闭。首次tools/tests路径不存在的命令错误日志保留，正确命令按实际tools目录执行；不是测试逻辑失败。

本批UI/HTTP只用隔离delivery-98-107范围和合成资料；普通运行保持8080/5173，隔离8081/5174完成后按PID/启动时间/命令归属停止。具体测试数量、运行身份与GitHub CI见本机runtime/rounds98-107-delivery-summary.json，不将工程替身记为DeepSeek真实质量成绩。
