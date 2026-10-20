#!/usr/bin/env python3
"""Publish a clearly synthetic incremental batch, never overwrite a real station."""
import argparse
import csv
import datetime
import os
from pathlib import Path
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=Path('data/incremental'))
    arguments = parser.parse_args()
    arguments.directory.mkdir(parents=True, exist_ok=True)
    target = arguments.directory / ('synthetic-demo-' + uuid.uuid4().hex[:8] + '.csv')
    part = target.with_suffix('.part')
    fields = ['STATION', 'DATE', 'NAME', 'TEMP', 'PRCP', 'HUMIDITY', 'MAX', 'MIN', 'FRSHTT', 'country', 'province', 'city', 'district']
    with part.open('x', newline='', encoding='utf-8') as stream:
        writer = csv.writer(stream)
        writer.writerow(fields)
        for day in range(30):
            observation_date = datetime.date(1973, 1, 1) + datetime.timedelta(days=day)
            writer.writerow(['99999000001', observation_date, 'SYNTHETIC INCREMENTAL DEMO', 30+day, 0.1, 0.5, 80, 20, '000000', '演示国', '', '', ''])
        writer.writerow(['99999000001', '2024-01-01', 'SYNTHETIC INCREMENTAL DEMO', 100, 1, 0.2, 110, 0, '010000', '演示国', '', '', ''])
        stream.flush()
        os.fsync(stream.fileno())
    part.rename(target)
    print('Published', target)
    print('Contains 31 SYNTHETIC rows for station 99999000001, not real climate observations.')
    print('Wait for the minimum file age before scanning. This intentionally adds demo data to the selected database.')


if __name__ == '__main__':
    main()
