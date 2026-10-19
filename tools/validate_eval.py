"""文件职责：校验人工标注集及跨划分泄漏；空目录只表示准备中，不冒充金标。"""
from pathlib import Path
import argparse
import json
import re
import unicodedata


def validate(manifest_path, require_ready=False):
    """1、读取范围内文件；2、检查标签和证据；3、检测泄漏；4、报告真实准备进度。"""
    path=Path(manifest_path).resolve()
    manifest=json.loads(path.read_text(encoding='utf-8'))
    errors=[]
    rows=[]
    if manifest.get('schemaVersion')!='1.0': errors.append('schemaVersion 必须为 1.0')
    ids=set()
    owners={kind:{} for kind in ['contentId','nearDuplicateGroup','normalizedText']}
    for split in ['dev','test']:
        relative=manifest.get('splits',{}).get(split)
        if not isinstance(relative,str): errors.append('缺少 '+split+' 路径');continue
        source=(path.parent/relative).resolve()
        if not source.is_relative_to(path.parent):errors.append('标注路径超出数据目录');continue
        if not source.is_file():errors.append('文件不存在：'+relative);continue
        for number,line in enumerate(source.read_text(encoding='utf-8').splitlines(),1):
            if not line.strip():continue
            location=f'{split}:{number}'
            try:
                row=json.loads(line)
                if not isinstance(row,dict): raise ValueError('记录必须为对象')
                for field in ['id','contentId','nearDuplicateGroup','topicArea','text','language']:
                    if not isinstance(row.get(field),str) or not row[field].strip():raise ValueError('缺少 '+field)
                if row['id'] in ids:raise ValueError('标注 id 重复')
                ids.add(row['id'])
                if row.get('sourceKind') not in ['SYNTHETIC','AUTHORIZED']:raise ValueError('来源类型无效')
                if row['sourceKind']=='AUTHORIZED' and not row.get('permissionRef'):raise ValueError('授权数据须填写私有授权记录引用')
                reviewers=row.get('annotatorIds')
                if not isinstance(reviewers,list) or not reviewers or any(not isinstance(x,str) or not x.strip() for x in reviewers) or len(set(reviewers))!=len(reviewers):raise ValueError('标注者列表无效')
                labels=row.get('labels')
                if not isinstance(labels,dict) or labels.get('sentiment') not in ['POSITIVE','NEGATIVE','NEUTRAL','UNKNOWN']:raise ValueError('整体倾向标签无效')
                if not isinstance(labels.get('reason'),str) or not labels['reason'].strip():raise ValueError('须填写标注理由')
                spans=labels.get('evidenceSpans')
                if not isinstance(spans,list) or not spans:raise ValueError('须标出支持标签的原文区间')
                for span in spans:
                    start,end=span.get('start'),span.get('end')
                    if type(start)!=int or type(end)!=int or not 0<=start<end<=len(row['text']):raise ValueError('原文区间越界')
                    if span.get('quote')!=row['text'][start:end]:raise ValueError('原文区间与摘录不符')
                normalized=re.sub(r'\s+','',unicodedata.normalize('NFKC',row['text'])).casefold()
                values={'contentId':row['contentId'],'nearDuplicateGroup':row['nearDuplicateGroup'],'normalizedText':normalized}
                for kind,value in values.items():
                    previous=owners[kind].setdefault(value,split)
                    if previous!=split:errors.append(location+' 跨划分泄漏：'+kind)
                rows.append((split,row))
            except (ValueError,TypeError,KeyError,AttributeError) as exc:errors.append(location+' '+str(exc))
    counts={split:sum(s==split for s,_ in rows) for split in ['dev','test']}
    work_count=len({r['contentId'] for _,r in rows})
    topic_count=len({r['topicArea'] for _,r in rows})
    double_count=sum(len(r['annotatorIds'])>=2 for _,r in rows)
    ready=counts['dev']>=100 and counts['test']>=200 and work_count>=6 and topic_count>=3 and double_count>=0.2*len(rows) and not errors
    declared=manifest.get('status')
    if declared not in ['DRAFT','FROZEN']:errors.append('status 必须为 DRAFT 或 FROZEN')
    if declared=='FROZEN' and not ready:errors.append('不能将未达准备条件的数据声明为 FROZEN')
    if require_ready and (not ready or declared!='FROZEN'):errors.append('尚未形成达标且声明冻结的评测集')
    return {'ok':not errors,'declaredStatus':declared,'readyForFreeze':ready,'counts':counts,
        'works':work_count,'topicAreas':topic_count,'doubleAnnotated':double_count,'errors':errors,
        'note':'只校验结构、精确归一文本与人工提供的近重复簇；不证明标签正确或标注者独立。'}


def main():
    """普通检查允许空草稿；正式评测使用 --require-ready 阻止空数据冒充成绩。"""
    parser=argparse.ArgumentParser()
    parser.add_argument('manifest',nargs='?',default=str(Path(__file__).resolve().parents[1]/'datasets/eval/manifest.json'))
    parser.add_argument('--require-ready',action='store_true')
    args=parser.parse_args()
    try:result=validate(args.manifest,args.require_ready)
    except (OSError,ValueError) as exc:result={'ok':False,'errors':[str(exc)]}
    print(json.dumps(result,ensure_ascii=True,indent=2))
    raise SystemExit(0 if result['ok'] else 1)


if __name__=='__main__':main()
