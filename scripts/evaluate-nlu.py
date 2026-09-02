#!/usr/bin/env python3
"""Exact extraction scores, including failures in every applicable denominator."""
import argparse,json,statistics
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('results',type=Path);p.add_argument('--cases',type=Path,default=Path(__file__).resolve().parents[1]/'docs/nlu_benchmark_cases.json');p.add_argument('--output',type=Path);p.add_argument('--split',choices=['all','calibration','evaluation'],default='all');a=p.parse_args()
cases={c['id']:c for c in json.loads(a.cases.read_text())};rows=[json.loads(x) for x in a.results.read_text().splitlines() if x.strip()]
rows=[r for r in rows if a.split=='all' or cases[r['id']]['split']==a.split]
try:
 import jsonschema
 schema=json.loads((a.cases.parent/'nlu_schema_v1.json').read_text());validator=jsonschema.Draft202012Validator(schema)
except ImportError:raise SystemExit('Install jsonschema in a local venv to validate Draft 2020-12.')
counts={k:[0,0] for k in ['Action','Item','Location','Count','UnitLabel','Expiry','JSONValid','FullResult']};failures=[]
fields={'Action':'action','Item':'item','Location':'location','Count':'count','UnitLabel':'unit_label','Expiry':'default_expiry'}
def normalize(v):
 if isinstance(v,dict):return {k:(sorted(x) if k=='issues' else normalize(x)) for k,x in v.items()}
 if isinstance(v,list):return [normalize(x) for x in v]
 return v
for r in rows:
 gold=cases[r['id']]['expected'];actual=None;valid=False
 try:
  actual=json.loads(r.get('raw',''));validator.validate(actual);valid=not r.get('error')
 except Exception:pass
 for metric,field in fields.items():
  if field in gold:
   counts[metric][1]+=1;counts[metric][0]+=int(valid and actual.get(field,object())==gold[field])
 counts['JSONValid'][1]+=1;counts['JSONValid'][0]+=int(valid)
 exact=valid and normalize(actual)==normalize(gold)
 counts['FullResult'][1]+=1;counts['FullResult'][0]+=int(exact)
 if not exact:failures.append({'id':r['id'],'text':cases[r['id']]['text'],'expected':gold,'actual':actual,'error':r.get('error')})
def stats(key):
 x=sorted(r[key] for r in rows if isinstance(r.get(key),(int,float)))
 return {'n':len(x),'min':min(x),'median':statistics.median(x),'p95':x[max(0,__import__('math').ceil(len(x)*.95)-1)],'max':max(x)} if x else None
out={'rows':len(rows),'unique_cases':len({r['id'] for r in rows}),'metrics':{k:{'correct':v[0],'total':v[1],'accuracy':v[0]/v[1] if v[1] else None} for k,v in counts.items()},'timing_ms':{k:stats(k) for k in ['modelLoadMs','prefillMs','ttftMs','decodeMs','totalNluMs']},'token_stats':{k:stats(k) for k in ['promptTokens','cachedPromptTokens','generatedTokens']},'peakPssKb':max((r.get('peakPssKb',0) for r in rows),default=0),'failures':failures}
serialized=json.dumps(out,ensure_ascii=False,indent=2)
if a.output:a.output.write_text(serialized+'\n')
print(json.dumps({k:v for k,v in out.items() if k!='failures'},ensure_ascii=False,indent=2))
