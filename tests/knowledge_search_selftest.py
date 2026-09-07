from pathlib import Path
import csv, re, sys

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app' / 'src' / 'main' / 'assets'
STOP = {"the","a","an","and","or","of","to","in","on","for","is","are","was","were","be","what","why","how","when","where","which","with","does","do","did","can","could","would","should","this","that","from"}
EXPAND = {
    'dcv': 'dcf discounted cash flow',
    'dcf': 'discounted cash flow valuation terminal value wacc free cash flow',
    'irr': 'internal rate of return cash flow npv zero',
    'waterfall': 'waterfall promote preferred return hurdle catch up residual split lp gp',
    'pmp': 'project management stakeholder process business environment',
    'leed': 'green associate decarbonization quality of life ecological conservation',
    'superheat': 'refrigeration evaporator suction line saturation temperature',
    'cpi': 'cost performance index earned value actual cost pmp',
    'spi': 'schedule performance index earned value planned value pmp',
    'evm': 'earned value management pmp pv ev ac cpi spi eac',
}

def norm(s): return re.sub(r'[^a-z0-9]+',' ',s.lower()).strip()
def words(s): return {x for x in norm(s).split() if len(x)>=2}
def initialism(s): return ''.join(w[0] for w in norm(s).split() if len(w)>=2 and w not in STOP)
def jaccard(a,b):
    A,B=words(a),words(b)
    return len(A&B)/len(A|B) if A and B else 0.0

def expand(q):
    low=norm(q)
    extra=[]
    for key,val in EXPAND.items():
        if re.search(rf'(^|\W){re.escape(key)}(\W|$)', low): extra.append(val)
    return q+' '+' '.join(extra)

def route(q):
    q=q.lower(); out=[]
    if any(x in q for x in ['hvac','refriger','compressor','evaporator','condenser','suction','superheat','subcool']): out.append('hvac/refrigeration')
    if any(x in q for x in ['irr','xirr','npv','dcf','dcv','waterfall','promote','preferred return','real estate']): out.append('real estate finance')
    if any(x in q for x in ['pmp','project management','stakeholder','earned value','critical path']): out.append('pmp 2026')
    if any(x in q for x in ['leed','green associate','decarbonization']): out.append('leed green associate v5')
    return out

rows=[]
for f in sorted(ASSETS.glob('knowledge_*.tsv')):
    with f.open(encoding='utf-8') as fh:
        r=csv.DictReader(fh, delimiter='\t')
        rows.extend(list(r))

assert len(rows) >= 400, f'expected >=400 records, got {len(rows)}'

def search(question, limit=5):
    q=expand(question)
    toks=sorted({t for t in norm(q).split() if len(t)>=2 and t not in STOP}, key=len, reverse=True)
    pref=route(q)
    scored=[]
    for row in rows:
        title,tags,body,cat=map(norm,[row['title'],row['tags'],row['body'],row['category']])
        tw,tagw,bw,cw=words(title),words(tags),words(body),words(cat)
        ini=initialism(title)
        s=0.0
        for t in toks:
            if t in tw: s+=5
            if t==ini and 2<=len(t)<=8: s+=12
            if t in tagw: s+=5
            if t in cw: s+=2
            if t in bw: s+=1
        if title and norm(q).find(title)>=0: s+=10
        if len(row['title'])>=6 and jaccard(q,title)>=.55: s+=6
        if any(cat.startswith(p) for p in pref): s+=4
        if s>=1: scored.append((s,row))
    scored.sort(key=lambda x:x[0], reverse=True)
    return scored[:limit]

CASES = [
    ('What is IRR in real estate?', 'IRR'),
    ('Explain a DCF valuation and WACC', 'DCF'),
    ('How does an equity waterfall preferred return and promote work?', 'waterfall'),
    ('What can cause low suction pressure in HVAC?', 'suction'),
    ('What is superheat?', 'Superheat'),
    ('Explain CPI and SPI for PMP earned value', 'CPI'),
    ('What is LEED Green Associate v5 about?', 'LEED'),
]

for q, expected in CASES:
    hits=search(q)
    if not hits:
        raise AssertionError(f'no hits for {q}')
    joined=' | '.join(f"{r['title']} [{r['category']}] ({s:.1f})" for s,r in hits[:3])
    if expected.lower() not in joined.lower():
        raise AssertionError(f"expected {expected!r} in top results for {q!r}; got {joined}")
    print(f'PASS: {q}\n  {joined}')
print(f'PASS: database loaded {len(rows)} records from {len(list(ASSETS.glob("knowledge_*.tsv")))} packs')
