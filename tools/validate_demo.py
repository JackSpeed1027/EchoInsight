"""Validate Demo business invariants; not a general JSON Schema validator."""
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate(data, labels, report, manifest):
    comments = data['comments']
    ids = [c['id'] for c in comments]
    require(len(ids) == len(set(ids)), 'duplicate internal comment ID')
    source_ids = [c['sourceCommentId'] for c in comments]
    require(len(source_ids) == len(set(source_ids)), 'duplicate source comment ID')
    require(set(ids) == set(labels), 'labels must cover current snapshot exactly')
    require(set(labels.values()) <= {'POSITIVE', 'NEGATIVE', 'NEUTRAL', 'UNKNOWN'}, 'invalid label')
    for c in comments:
        require(c['text'].strip(), 'empty comment')
        require(c['parentId'] is None or c['parentId'] in ids, 'missing parent')
    for s in data['transcriptSegments']:
        require(0 <= s['startMs'] < s['endMs'], 'invalid transcript range')
    digest = hashlib.sha256(json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    require(digest == manifest['inputSha256'], 'input hash changed')
    require(manifest['synthetic'] is True, 'Demo must declare synthetic data')
    require(manifest['commentCount'] == len(comments), 'manifest comment count mismatch')
    require(manifest['transcriptSegmentCount'] == len(data['transcriptSegments']), 'manifest segment count mismatch')
    require(report['snapshotId'] == manifest['snapshotId'], 'wrong report snapshot')
    counts = Counter(labels.values())
    classified = sum(counts[k] for k in ['POSITIVE', 'NEGATIVE', 'NEUTRAL'])
    cov, metrics = report['coverage'], report['metrics']
    require(cov['importedComments'] == cov['eligibleComments'] == len(comments), 'coverage mismatch')
    require(cov['classifiedComments'] == classified, 'classified denominator mismatch')
    require(cov['unknown'] == counts['UNKNOWN'], 'unknown count mismatch')
    for label in ['POSITIVE', 'NEGATIVE', 'NEUTRAL']:
        require(metrics[label.lower()] == counts[label], 'sentiment count mismatch')
    ratio = counts['POSITIVE'] / classified if classified else None
    require(metrics['positiveShareAmongClassified'] == ratio, 'positive share mismatch')
    require(cov['sourceTotalComments'] is None and cov['sourceCoverage'] is None, 'unknown source coverage must be null')
    require(report['provenance']['mode'] == 'MOCK', 'expected report must declare Mock')
    for finding in report['findings']:
        require(finding['claim'].strip(), 'empty claim')
        require(finding['evidenceIds'], 'claim without evidence')
        require(set(finding['evidenceIds']) <= set(ids), 'evidence outside snapshot')
    require(report['limitations'], 'missing limitations')


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def main():
    from copy import deepcopy
    for name in ['threadpool', 'sql-index']:
        base = ROOT / 'datasets' / 'demo' / name
        data, labels, report, manifest = [load(base / p) for p in ['input.json', 'labels.expected.json', 'report.expected.json', 'manifest.json']]
        validate(data, labels, report, manifest)
        # Ensure validation actually rejects two consequential report defects.
        for defect in ['invalid-evidence', 'wrong-denominator']:
            broken = deepcopy(report)
            if defect == 'invalid-evidence':
                broken['findings'][0]['evidenceIds'] = ['missing-comment']
            else:
                broken['coverage']['classifiedComments'] += 1
            try:
                validate(data, labels, broken, manifest)
            except ValueError:
                pass
            else:
                raise ValueError(f'validator accepted {defect}')
        print(f'PASS {name}: 100 comments, 5 segments; counts, snapshot hash and evidence valid; 2 bad reports rejected')
    print('NOTE: business invariants only; full JSON Schema validation and application tests are pending.')


if __name__ == '__main__':
    main()
