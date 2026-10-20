#!/usr/bin/env python3
"""Export concrete examples of every analysis stage from the running local service."""
import argparse
import json
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request,urlopen
from local_auth import reader_key


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base',default='http://127.0.0.1:8087')
    p.add_argument('--out',type=Path,default=Path('data/examples'))
    args=p.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    key=reader_key()
    requests={
        'task1_thresholds.csv':('/api/exports/thresholds.csv',{}),
        'task2_daxian_1993.csv':('/api/exports/daily.csv',dict(station='57328099999',**{'from':'1993-01-01','to':'1994-01-01'})),
        'task3_country_htd_19930101.csv':('/api/exports/region-daily.csv',dict(date='1993-01-01',level='COUNTRY',metric='HTD',country='中国')),
        'task4_station_daxian_1993.csv':('/api/exports/station-annual.csv',dict(station='57328099999',year=1993)),
        'task4_country_htd_1993.csv':('/api/exports/region-annual.csv',dict(year=1993,level='COUNTRY',metric='HTD',country='中国')),
    }
    for name,(path,params) in requests.items():
        url=args.base+path+('?' + urlencode(params) if params else '')
        with urlopen(Request(url,headers={'X-API-Key':key}),timeout=20) as r:
            assert 'text/csv' in r.headers.get('Content-Type',''),url
            content=r.read()
        (args.out/name).write_bytes(content)
        print(name,len(content),'bytes')
    (args.out/'README.txt').write_text('These are bounded query examples exported from the reconstructed sample database, not full historical research outputs.\n',encoding='utf-8')


if __name__=='__main__':main()
