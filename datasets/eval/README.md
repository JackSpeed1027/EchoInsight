# 人工标注与独立评测准备

当前状态：DRAFT，真实标注 0 条。这里的空文件是评测基础设施，不是完成的金标集，也不能用当前查表 Mock 计算真实语义准确率。

## 怎样准备数据

1. 使用有权处理的数据，去除不必要的用户标识。授权材料以 permissionRef 指向私有授权记录，不在公开仓库放授权凭据和真实身份。
2. 一个作品只进入 dev 或 test；人工识别的改写、模板和近重复文本共用 nearDuplicateGroup。校验器能检查声明的簇及精确归一文本，不能自动识别全部语义近重复。
3. dev 用于调试 Prompt 和标签协议；test 冻结后不参与调优，测试答案不能进入分析服务或运行资源。
4. 首批目标为至少 300 条、6 个作品、3 类主题；dev 至少 100，test 至少 200。至少 20% 独立复标，保存分歧和裁决；校验器看到两个 ID 不等于现实中真的独立审核。

## 单条 JSONL 的字段

id 是标注身份；contentId 是作品身份；nearDuplicateGroup 是人工确认的近重复簇；topicArea 表示大类题材。sourceKind 为 SYNTHETIC 或 AUTHORIZED，后者需要 permissionRef。annotatorIds 使用去身份化的审核人标识。

labels.sentiment 使用 POSITIVE / NEGATIVE / NEUTRAL / UNKNOWN。混合情感时按已说明的讨论对象和上下文判断；不能确定时选 UNKNOWN 并解释。labels.reason 记录依据，evidenceSpans 使用 Unicode 字符下标，start 包含、end 不包含，quote 必须与原文切片完全一致。

示意（仅说明格式，不计入 dev/test）：

~~~json
{"id":"example-only","contentId":"example-work","nearDuplicateGroup":"example-cluster","topicArea":"Java教学","text":"例子清楚，但边界没讲。","language":"zh","sourceKind":"SYNTHETIC","annotatorIds":["example-annotator"],"labels":{"sentiment":"UNKNOWN","reason":"同时评价示例与边界，整体倾向无法唯一确定","evidenceSpans":[{"start":0,"end":11,"quote":"例子清楚，但边界没讲。"}]}}
~~~

整体倾向只是第一阶段标签，后续主题/对象级标注另做版本扩展，不强行用一个整体标签回答每个具体知识点。当前协议及校验器不生成 Macro-F1、主张支持率或模型成绩。

## 验证方式

在项目根目录执行：

~~~powershell
python tools/validate_eval.py
python tools/validate_eval.py --require-ready
python -m unittest discover -s tools -p test_validate_eval.py
~~~

普通检查允许合法的空 DRAFT，并明确打印未就绪。--require-ready 要求数量和结构达标、划分无冲突且 manifest.status 已声明 FROZEN；当前应失败，这是预期保护行为。

空状态不能改成 FROZEN 来绕过门槛。新增标注必须记录版本和来源；不要把两个现有重复模板 Demo 改名复制成独立测试集。
