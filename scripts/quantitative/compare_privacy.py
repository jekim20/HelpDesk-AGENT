"""Produce a comparison from two runs of PrivacyCorpusTest."""
import argparse
import csv
import json
from pathlib import Path


def load(path):
    rows = json.loads(path.read_text())
    cases = {(row['corpus'], row['id']): row for row in rows}
    if len(cases) != len(rows):
        raise ValueError(f'Duplicate corpus and case IDs in {path}')
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('before', type=Path)
    parser.add_argument('after', type=Path)
    parser.add_argument('output_prefix', type=Path)
    args = parser.parse_args()
    before = load(args.before)
    after = load(args.after)
    if before.keys() != after.keys():
        raise ValueError('The two runs must contain the same cases')

    rows = []
    summary = {}
    for key, first in before.items():
        corpus, case_id = key
        original, expected = first['blocked'], first['expectedBlocked']
        current = after[key]['blocked']
        if expected != after[key]['expectedBlocked']:
            raise ValueError(f'Expected label differs between runs for {key}')
        rows.append(dict(corpus=corpus, id=case_id, expectedBlocked=expected,
                         beforeBlocked=original, afterBlocked=current))
        group = summary.setdefault(corpus, {})
        for phase, actual in [('before', original), ('after', current)]:
            counts = group.setdefault(phase, dict(TP=0, FP=0, FN=0, TN=0))
            counts[('T' if expected == actual else 'F') + ('P' if actual else 'N')] += 1

    for group in summary.values():
        for counts in group.values():
            counts['detectionRate'] = counts['TP'] / (counts['TP'] + counts['FN'])
            counts['falsePositiveRate'] = counts['FP'] / (counts['FP'] + counts['TN'])

    with args.output_prefix.with_suffix('.csv').open('x', newline='') as output:
        writer = csv.DictWriter(output, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)
    with args.output_prefix.with_suffix('.json').open('x') as output:
        json.dump(summary, output, indent=2)
        output.write('\n')


if __name__ == '__main__':
    main()
