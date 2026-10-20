#!/usr/bin/env python3
"""Bounded byte-seek sampler for the user's year-blocked 45 GB CSV. Stdlib only."""
import argparse
import collections
import csv
import datetime as dt
import io
import json
import math
from pathlib import Path

CANONICAL = ['STATION', 'DATE', 'NAME', 'TEMP', 'PRCP', 'HUMIDITY', 'MAX', 'MIN',
             'FRSHTT', 'country', 'province', 'city', 'district']


def number(value, sentinel=None, lower=None, upper=None):
    try:
        n = float(value)
        if not math.isfinite(n) or (sentinel is not None and n == sentinel):
            return ''
        if (lower is not None and n < lower) or (upper is not None and n > upper):
            return ''
        return str(n)
    except (ValueError, TypeError):
        return ''


def numeric_or_empty(value):
    return not value or bool(number(value))


def detect_layout(row):
    if row[28] and not numeric_or_empty(row[28]):
        return 'geography_before_humidity'
    if number(row[28]):
        return 'humidity_before_geography'
    if number(row[32]):
        return 'geography_before_humidity'
    if row[32] and not numeric_or_empty(row[32]):
        return 'humidity_before_geography'
    return None


def normalize(row, layout_hint=None):
    if len(row) != 40 or row[0] == 'STATION':
        raise ValueError('expected a 40-column source observation')
    dt.date.fromisoformat(row[1])
    if not row[0].isdigit() or len(row[0]) > 11:
        raise ValueError('invalid station')
    # Early blocks: geography[28:32], humidity[32]. Later: humidity[28], geography[29:33].
    layout = layout_hint or detect_layout(row)
    if layout is None:
        raise ValueError('ambiguous column layout; infer from other rows in the same year block')
    if layout == 'humidity_before_geography':
        humidity, geo = row[28], row[29:33]
    else:
        humidity, geo = row[32], row[28:32]
    weather = row[27][:-2] if row[27].endswith('.0') else row[27]
    weather = weather.zfill(6) if weather and set(weather) <= {'0', '1'} and len(weather) <= 6 else ''
    result = [row[0].zfill(11), row[1], row[5], number(row[6], 9999.9),
              number(row[24], 99.99, lower=0), number(humidity, lower=0, upper=1),
              number(row[20], 9999.9), number(row[22], 9999.9), weather, *geo]
    # Source-derived LTD/HTD/... are intentionally ignored: recompute from observations.
    return dict(zip(CANONICAL, result)), layout


class BoundedSource:
    def __init__(self, path, budget):
        self.path = Path(path)
        self.file = self.path.open('rb')
        self.size = self.path.stat().st_size
        self.read_bytes = 0
        self.budget = budget

    def line(self):
        line = self.file.readline(8192)
        self.read_bytes += len(line)
        if self.read_bytes > self.budget:
            raise RuntimeError('read budget exceeded; stopped without scanning the full source')
        if len(line) == 8192 and not line.endswith(b'\n'):
            raise ValueError('source has an unexpectedly long CSV line')
        return line

    def probe(self, offset):
        self.file.seek(offset)
        if offset:
            self.line()
        start = self.file.tell()
        raw = self.line()
        row = next(csv.reader([raw.decode('utf-8-sig')]))
        if row[0] == 'STATION':
            start = self.file.tell()
            row = next(csv.reader([self.line().decode('utf-8')]))
        return start, int(row[1][:4])

    def blocks(self):
        probes = [(self.size * i // 512, self.probe(self.size * i // 512)[1]) for i in range(512)]
        starts = [(0, probes[0][1])]
        for (left, old_year), (right, new_year) in zip(probes, probes[1:]):
            if old_year == new_year:
                continue
            lo, hi = left, right
            while hi - lo > 2048:
                mid = (lo + hi) // 2
                _, year = self.probe(mid)
                if year not in (old_year, new_year):
                    raise ValueError('more than one year transition inside a probe bracket')
                if year == old_year:
                    lo = mid
                else:
                    hi = mid
            self.file.seek(lo)
            self.line()
            while True:
                start = self.file.tell()
                row = next(csv.reader([self.line().decode('utf-8')]))
                if row[0] != 'STATION' and int(row[1][:4]) != old_year:
                    starts.append((start, int(row[1][:4])))
                    break
        years = [y for _, y in starts]
        if len(years) != len(set(years)):
            raise ValueError('year appears in multiple blocks; this sampler requires one contiguous block per year')
        return [(year, offset, starts[i+1][0] if i+1 < len(starts) else self.size)
                for i, (offset, year) in enumerate(starts)]

    def prefix(self, start, end, limit):
        self.file.seek(start)
        source_rows, layouts = [], collections.Counter()
        while self.file.tell() < min(end, start + limit):
            raw = self.line()
            if not raw:
                break
            row = next(csv.reader([raw.decode('utf-8-sig')]))
            if row[0] == 'STATION':
                continue
            if len(row)!=40:
                raise ValueError('unsupported source width')
            source_rows.append(row)
        votes=collections.Counter(detect_layout(r) for r in source_rows)
        votes.pop(None, None)
        if not votes:
            raise ValueError('cannot infer year-block column layout')
        layout, count=votes.most_common(1)[0]
        if count/sum(votes.values())<0.95:
            raise ValueError('column order changes within a year block; explicit schema required')
        records=[normalize(r,layout)[0] for r in source_rows]
        layouts[layout]=len(records)
        # The final station may be cut off by the byte budget; don't use it.
        if self.file.tell() < end and records:
            last_station = records[-1]['STATION']
            records = [r for r in records if r['STATION'] != last_station]
        return records, layouts


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', type=Path, default=Path.home() / 'Desktop/new_data_deal/raw.csv')
    p.add_argument('--output', type=Path, default=Path('data/sample.csv'))
    p.add_argument('--stations', type=int, default=3)
    p.add_argument('--window-mib', type=int, default=2)
    p.add_argument('--max-read-mib', type=int, default=96)
    p.add_argument('--analysis-years', default='1993,1994,2022,2023')
    args = p.parse_args()
    requested = set(range(1973, 1993)) | {int(y) for y in args.analysis_years.split(',')}
    src = BoundedSource(args.source, args.max_read_mib * 1024**2)
    blocks = src.blocks()
    rows_by_year, layout_counts = {}, collections.Counter()
    for year, start, end in blocks:
        if year in requested:
            rows, layouts = src.prefix(start, end, args.window_mib * 1024**2)
            rows_by_year[year] = rows
            layout_counts.update(layouts)
    if set(rows_by_year) != requested:
        raise ValueError(f'missing requested years: {sorted(requested-set(rows_by_year))}')
    station_sets = [{r['STATION'] for r in rows if r['country']} for rows in rows_by_year.values()]
    common = set.intersection(*station_sets)
    selected = sorted(common)[:args.stations]
    if len(selected) < args.stations:
        raise ValueError(f'only {len(selected)} common stations; try a larger window within the read budget')
    data = sorted((r for rows in rows_by_year.values() for r in rows if r['STATION'] in selected),
                  key=lambda r: (r['STATION'], r['DATE']))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=CANONICAL)
        writer.writeheader()
        writer.writerows(data)
    manifest = dict(source=str(src.path), source_bytes=src.size, source_mtime=src.path.stat().st_mtime,
                    bytes_read=src.read_bytes, read_fraction=src.read_bytes/src.size,
                    requested_years=sorted(requested), stations=selected, rows=len(data),
                    layouts=dict(layout_counts), year_blocks=blocks,
                    station_year_counts={s: dict(sorted(collections.Counter(r['DATE'][:4] for r in data if r['STATION']==s).items())) for s in selected},
                    selection='first complete stations in bounded year-prefix windows; NOT a representative climate sample',
                    normalization='Fahrenheit, precipitation inches, humidity ratio; weather flags LEFT zero-padded; source derived metrics ignored')
    manifest_path = args.output.with_name(args.output.stem + '-manifest.json')
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k:manifest[k] for k in ('bytes_read','read_fraction','stations','rows','layouts')},ensure_ascii=False,indent=2))
    print(f'Written {args.output} and {manifest_path}')


if __name__ == '__main__':
    main()
