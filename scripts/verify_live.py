#!/usr/bin/env python3
"""Independent Decimal oracle: verify live Java/MySQL results against sampled raw observations."""
import argparse
import collections
import csv
import datetime
import io
import json
from decimal import Decimal, ROUND_CEILING, ROUND_HALF_UP
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from local_auth import reader_key, admin_key

MAGNITUDES=['ltd','htd','hr','ehr','lh','htd1','ctd1']
WEATHER=['fog','rain','snow','hail','thunder','tornado']
checks=0


def check(actual,expected,label):
    global checks
    checks+=1
    if expected is None:
        assert actual is None,(label,actual,expected)
    elif isinstance(expected,(Decimal,int)):
        assert actual is not None and abs(Decimal(str(actual))-expected)<Decimal('0.00000002'),(label,actual,expected)
    else:
        assert actual==expected,(label,actual,expected)


def q(values,p):
    values=sorted(v for v in values if v is not None)
    return values[max(0,int((Decimal(p)*len(values)).to_integral_value(rounding=ROUND_CEILING))-1)]


def positive(a,b):
    return max(Decimal(0),a-b) if a is not None and b is not None else None


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base',default='http://127.0.0.1:8087')
    p.add_argument('--sample',type=Path,default=Path('data/sample.csv'))
    p.add_argument('--report',type=Path,default=Path('docs/live-verification.json'))
    args=p.parse_args()
    read_key=reader_key()
    def get(path,parameters=None,status=200,key=read_key):
        target=args.base+path+('?' + urlencode(parameters) if parameters else '')
        try:
            request=Request(target,headers={'X-API-Key':key} if key else {})
            with urlopen(request,timeout=20) as r: code=r.status;headers=dict(r.headers);body=r.read()
        except HTTPError as r:code=r.code;headers=dict(r.headers);body=r.read()
        check(code,status,path+' status')
        return json.loads(body,parse_float=Decimal),headers
    rows=[]
    with args.sample.open(encoding='utf-8',newline='') as f:
        for row in csv.DictReader(f):
            for key,precision in [('TEMP',4),('PRCP',4),('HUMIDITY',10),('MAX',4),('MIN',4)]:
                row[key]=Decimal(row[key]).quantize(Decimal(10)**-precision,rounding=ROUND_HALF_UP) if row[key] else None
            rows.append(row)
    baselines=collections.defaultdict(list)
    for row in rows:
        if '1973-01-01'<=row['DATE']<'1993-01-01':baselines[row['STATION']].append(row)
    thresholds={}
    for station,base in baselines.items():
        t={'temp10':q([r['TEMP'] for r in base],'.1'),'temp90':q([r['TEMP'] for r in base],'.9'),
           'prcp95':q([r['PRCP'] for r in base],'.95'),'prcp99':q([r['PRCP'] for r in base],'.99'),
           'humidity5':q([r['HUMIDITY'] for r in base],'.05')}
        thresholds[station]=t
        actual,_=get('/api/stations/'+station+'/thresholds')
        for key,value in t.items():check(actual[key],value,station+' '+key)
    daily={};annual=collections.defaultdict(list);geos={}
    for r in rows:
        if r['DATE']<'1993-01-01':continue
        t=thresholds[r['STATION']]
        v=dict(zip(MAGNITUDES,[positive(t['temp10'],r['TEMP']),positive(r['TEMP'],t['temp90']),positive(r['PRCP'],t['prcp95']),
            positive(r['PRCP'],t['prcp99']),positive(t['humidity5'],r['HUMIDITY']),positive(r['MAX'],Decimal(95)),positive(Decimal(5),r['MIN'])]))
        flags=r['FRSHTT']
        v.update({k:int(flags[i]) if flags else None for i,k in enumerate(WEATHER)})
        daily[(r['STATION'],r['DATE'])]=v
        year=int(r['DATE'][:4]);geos[(r['STATION'],year)]=tuple(r[k] for k in ('country','province','city','district'))
        for k,value in v.items():annual[(r['STATION'],year,k)].append(value)
    for station in sorted(thresholds):
        dates=sorted(d for s,d in daily if s==station)
        # Verify first 200 daily observations of every station, including missing values.
        response,_=get('/api/stations/'+station+'/indicators',{'from':dates[0],'to':'2024-01-01','limit':200})
        for r in response['items']:
            for key,value in daily[(station,r['date'])].items():check(r[key],value,station+' '+r['date']+' '+key)
        for year in sorted({int(d[:4]) for d in dates}):
            actual,_=get('/api/stations/'+station+'/annual',{'year':year})
            check(len(actual),13,station+' annual metric count')
            for r in actual:
                values=annual[(station,year,r['metric'].lower())]
                check(r['nonzeroDays'],sum(v is not None and v>0 for v in values),'nonzeroDays')
                check(r['validDays'],sum(v is not None for v in values),'validDays')
    # Independent yearly regional medians and means across station counts.
    groups=collections.defaultdict(list)
    for (station,year,metric),values in annual.items():
        if not any(v is not None for v in values):continue
        geo=geos[(station,year)];count=sum(v is not None and v>0 for v in values)
        for depth,level in enumerate(['COUNTRY','PROVINCE','CITY','DISTRICT'],1):
            if any(not x for x in geo[:depth]) or (depth==4 and geo[0]!='中国'):continue
            groups[(year,level,geo[:depth],metric)].append(count)
    for (year,level,geo,metric),values in groups.items():
        params=dict(year=year,level=level,metric=metric.upper())
        params.update(zip(('country','province','city','district'),geo))
        response,_=get('/api/aggregates/annual',params)
        check(len(response),1,'regional annual row count');a=response[0];values=sorted(values);n=len(values)
        expected_median=Decimal(values[(n-1)//2]+values[n//2])/2
        check(a['minDays'],min(values),'region min');check(a['maxDays'],max(values),'region max')
        check(a['medianDays'],expected_median,'region median');check(a['stationCount'],n,'region stations')
        expected_mean=(Decimal(sum(values))/n).quantize(Decimal('.0001'),rounding=ROUND_HALF_UP)
        check(a['avgDays'],expected_mean,'region mean')
    # Check daily region stats against each selected station's first observed analysis day.
    for station in thresholds:
        date=min(d for s,d in daily if s==station);raw=next(r for r in rows if r['STATION']==station and r['DATE']==date)
        for metric in MAGNITUDES:
            values=sorted(v[metric] for (s,d),v in daily.items() if d==date and v[metric] is not None and geos[(s,int(d[:4]))][0]==raw['country'])
            if not values:continue
            actual,_=get('/api/aggregates/daily',dict(date=date,level='COUNTRY',metric=metric.upper(),country=raw['country']))
            check(len(actual),1,'daily region count');n=len(values)
            check(actual[0]['medianValue'],(values[(n-1)//2]+values[n//2])/2,'daily median')
            check(actual[0]['avgValue'],sum(values)/n,'daily mean')
    station=sorted(thresholds)[0]
    _,h1=get('/api/stations/'+station+'/thresholds');_,h2=get('/api/stations/'+station+'/thresholds')
    check(h2.get('X-Cache'),'HIT','real Redis hit')
    get('/api/stations/'+station+'/indicators',{'from':'1993-02-01','to':'1993-01-01'},400)
    get('/api/aggregates/annual',{'year':1993,'level':'COUNTRY','metric':'BAD'},400)
    injection,_=get('/api/aggregates/annual',{'year':1993,'level':'COUNTRY','metric':'HTD','country':"中国' OR 1=1 --"})
    check(injection,[],'country is a bound value, not SQL')
    get('/api/meta',status=401,key=None)
    get('/api/admin/cache',status=403)
    get('/api/admin/cache',key=admin_key())
    target=args.base+'/api/exports/daily.csv?'+urlencode({'station':station,'from':'1993-01-01','to':'1994-01-01'})
    with urlopen(Request(target,headers={'X-API-Key':read_key})) as r:exported=list(csv.DictReader(io.StringIO(r.read().decode('utf-8-sig'))))
    check(len(exported),sum(s==station and d.startswith('1993') for s,d in daily),'export row count')
    meta,_=get('/api/meta');check(meta['analysis']['rawRows'],len(rows),'import count');check(meta['analysis']['analysisRows'],len(daily),'daily count')
    report=dict(verified_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),status='PASS',assertions=checks,
                source_rows=len(rows),daily_rows=len(daily),stations=sorted(thresholds),
                checks=['all station thresholds','up to 200 daily records per station, 13 metrics',
                        'all station-year counts','all sampled region levels yearly mean/min/max/median',
                        'daily region statistics','real Redis HIT','validation and admin protection','bound SQL values','CSV row count'],
                warning='sample verification does not establish correctness or performance on the full 45 GB dataset')
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
