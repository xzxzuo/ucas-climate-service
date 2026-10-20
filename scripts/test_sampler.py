import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('sampler',Path(__file__).with_name('sample_source.py'))
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class SamplerTests(unittest.TestCase):
    def row(self):
        r=['']*40
        r[0]='7027099999';r[1]='1973-01-01';r[5]='Station';r[6]='42';r[20]='9999.9';r[22]='30';r[24]='99.99';r[27]='10000'
        r[28:33]=['中国','四川省','泸州市','江阳区','0.6']
        return r
    def test_both_column_layouts_produce_identical_records(self):
        a=self.row();b=a[:28]+[a[32]]+a[28:32]+a[33:]
        self.assertEqual(module.normalize(a)[0],module.normalize(b)[0])
        self.assertEqual(module.normalize(b)[1],'humidity_before_geography')
    def test_missing_sentinels_and_left_padding(self):
        r,_=module.normalize(self.row())
        self.assertEqual(r['STATION'],'07027099999');self.assertEqual(r['FRSHTT'],'010000')
        self.assertEqual(r['PRCP'],'');self.assertEqual(r['MAX'],'')
    def test_reader_enforces_byte_budget(self):
        reader=module.BoundedSource(Path(__file__).with_name('test_sampler.py'),1)
        try:
            with self.assertRaises(RuntimeError):reader.line()
        finally:reader.file.close()
    def test_missing_humidity_uses_block_schema(self):
        a=self.row();b=a[:28]+['']+a[28:32]+a[33:];b[32]=''
        r,_=module.normalize(b,'humidity_before_geography')
        self.assertEqual(r['country'],'中国');self.assertEqual(r['HUMIDITY'],'')

if __name__=='__main__':unittest.main()
