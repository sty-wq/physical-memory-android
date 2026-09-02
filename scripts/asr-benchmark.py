#!/usr/bin/env python3
"""Join actual ASR sessions with explicit human labels. Never infer which phrase was spoken."""
import argparse, csv, json, pathlib, re
p = argparse.ArgumentParser()
p.add_argument('--events', required=True, type=pathlib.Path)
p.add_argument('--labels', type=pathlib.Path)
p.add_argument('--out', required=True, type=pathlib.Path)
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=True)
reference_path = pathlib.Path(__file__).resolve().parent.parent / 'docs/asr-test-phrases.csv'
with reference_path.open() as f: references = {r['referenceId']: r for r in csv.DictReader(f)}
labels = {}
if a.labels:
    with a.labels.open() as f:
        for row in csv.DictReader(f):
            if row['sessionId'].strip():
                if row['sessionId'] in labels: raise ValueError('Duplicate session label')
                if row['referenceId'] not in references: raise ValueError('Unknown referenceId')
                labels[row['sessionId']] = row
events = [json.loads(line) for line in a.events.read_text().splitlines() if line.strip().startswith('{')]
sessions = {e['sessionId']: e for e in events if e.get('type') == 'session'}
commands = {e['sessionId']: e for e in events if e.get('type') == 'command'}
unknown = labels.keys() - sessions.keys()
if unknown: raise ValueError('Label points to missing sessions: ' + ','.join(unknown))
columns = ['sessionId','engine','mode','referenceId','referenceText','resultText','expectedItem','actualItem',
           'itemExact','expectedLocation','actualLocation','locationExact','expectedCommand','actualCommand','commandExact',
           'outcome','networkCondition','startupLatency','modelLoadMs','firstPartialLatency','finalLatency',
           'speechEndToFinalLatency','speechBoundarySource','modelLoadedPssKb','processPssKb','error','notes']
rows = []
for sid, event in sessions.items():
    command = commands.get(sid, {})
    label = labels.get(sid, {})
    ref = references.get(label.get('referenceId'), {})
    row = {key: event.get(key) for key in columns}
    row.update(referenceId=label.get('referenceId'), referenceText=ref.get('text'),
               expectedItem=ref.get('item'),actualItem=command.get('item'),
               expectedLocation=ref.get('location'),actualLocation=command.get('location'),
               expectedCommand=ref.get('command'),actualCommand=command.get('command'),outcome=command.get('outcome'),
               networkCondition=label.get('networkCondition','unobserved'),notes=label.get('notes','unlabeled session'))
    for field, expected, actual in [('itemExact','item','item'),('commandExact','command','command')]:
        row[field] = (ref[expected] == command.get(actual)) if ref else None
    row['locationExact'] = (ref['location'] == command.get('location')) if ref and ref['command'] == 'STORE' else None
    rows.append(row)
with (a.out / 'observed-sessions.csv').open('w', newline='') as f:
    writer = csv.DictWriter(f, fieldnames=columns); writer.writeheader(); writer.writerows(rows)
summary = {'sessionCount': len(rows), 'labeledCount': len(labels), 'evidence': 'Insufficient evidence' if len(labels) < 16 else 'Observed labeled sessions only',
           'note': 'No inferred accuracy. Missing timestamps remain null. Token end estimates are not acoustic end measurements.',
           'engines': {}}
for engine in sorted(set(r['engine'] for r in rows)):
    selected = [r for r in rows if r['engine'] == engine and r['referenceId']]
    summary['engines'][engine] = {'labeledSessions': len(selected), 'itemExactCount': sum(r['itemExact'] is True for r in selected),
                                  'storeLocationExactCount': sum(r['locationExact'] is True for r in selected),
                                  'errors': sum(bool(r['error']) for r in selected)}
(a.out / 'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
print(json.dumps(summary, ensure_ascii=False, indent=2))
