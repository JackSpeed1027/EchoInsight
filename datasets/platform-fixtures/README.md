# 平台导出格式样本（全部合成）

这些文件按本轮核对的 MediaCrawler JSON 字段构造，不是平台采集记录，不能用作真实 AI 质量分数或声称抓取成功。来源字段故意设为平台类型以验证适配路径；标题明确标为合成，Mock 结果应全部 UNKNOWN。

| 平台 | sourceContentId | 选择的两份文件 |
| --- | --- | --- |
| BILIBILI | 990000000001 | bilibili/contents.json、bilibili/comments.json |
| XHS | fixture-xhs-001 | xhs/contents.json、xhs/comments.json |

每组含 2 条作品元数据、3 条评论。选中目标作品后匹配 2 条评论，排除 1 条其他作品、1 条其他评论；第二条评论的父评论是第一条。前端选择相应平台、填本表 ID、上传两文件并点击导入，之后可选择创建免费 Mock 分析。

时间：B站字段为秒，小红书字段为毫秒。正式文件请用有权处理的真实导出替换，不修改这些合成样本来伪造采集证据。真实私有导出放在项目外或 Git 忽略的 runtime 中，不提交仓库。
