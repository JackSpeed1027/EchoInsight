"""文件职责：用任务自建临时数据验证划分泄漏和空金标拦截，不读取用户私有材料。"""
import json
from pathlib import Path
import tempfile
import unittest
from validate_eval import validate


class EvaluationValidationTest(unittest.TestCase):
    """合成数据只验证校验器契约，不是产品分类评测。"""

    def sample(self,identifier,content,text='很清楚',group=None):
        """生成仅用于校验器测试的短标注。"""
        return {'id':identifier,'contentId':content,'nearDuplicateGroup':group or identifier,'topicArea':'测试',
            'text':text,'language':'zh','sourceKind':'SYNTHETIC','annotatorIds':['test-annotator'],
            'labels':{'sentiment':'POSITIVE','reason':'校验器合成样例','evidenceSpans':[{'start':0,'end':len(text),'quote':text}]}}

    def check(self,dev,test,strict=False):
        """临时目录只含本测试新建数据，由 TemporaryDirectory 回收。"""
        with tempfile.TemporaryDirectory(prefix='echoinsight-eval-test-') as directory:
            root=Path(directory)
            (root/'manifest.json').write_text(json.dumps({'schemaVersion':'1.0','status':'DRAFT','splits':{'dev':'dev.jsonl','test':'test.jsonl'}}),encoding='utf-8')
            for name,rows in [('dev',dev),('test',test)]:
                (root/(name+'.jsonl')).write_text('\n'.join(json.dumps(row,ensure_ascii=False) for row in rows),encoding='utf-8')
            return validate(root/'manifest.json',strict)

    def test_empty_draft_is_not_a_ready_gold_set(self):
        """空草稿可检查通过，但不能用于正式评测。"""
        self.assertTrue(self.check([],[])['ok'])
        self.assertFalse(self.check([],[])['readyForFreeze'])
        self.assertFalse(self.check([],[],True)['ok'])

    def test_same_work_cannot_cross_splits(self):
        """作品相同而文字不同仍属于数据泄漏。"""
        result=self.check([self.sample('a','same','讲解清楚')],[self.sample('b','same','例子充分')])
        self.assertTrue(any('contentId' in item for item in result['errors']))

    def test_declared_near_duplicates_cannot_cross_splits(self):
        """改写文本按人工近重复簇阻止跨划分。"""
        result=self.check([self.sample('a','one','讲解清楚','cluster')],[self.sample('b','two','解释明白','cluster')])
        self.assertTrue(any('nearDuplicateGroup' in item for item in result['errors']))

    def test_normalized_exact_duplicates_cannot_cross_splits(self):
        """空格和大小写变化不能绕过精确文本泄漏检查。"""
        result=self.check([self.sample('a','one','Java 好')],[self.sample('b','two','java好')])
        self.assertTrue(any('normalizedText' in item for item in result['errors']))

    def test_invalid_quote_is_rejected(self):
        """证据区间存在但原文不匹配时不能通过。"""
        row=self.sample('a','one');row['labels']['evidenceSpans'][0]['quote']='不存在'
        self.assertFalse(self.check([row],[])['ok'])


if __name__=='__main__':unittest.main()
