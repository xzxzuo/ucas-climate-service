package cn.zuoxu.climate.service;

import cn.zuoxu.climate.data.ClimateMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.io.Writer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ExportService {
    private static final String[] HEADERS={"stationCode","date","ltd","htd","hr","ehr","lh","htd1","ctd1","fog","rain","snow","hail","thunder","tornado","country","province","city","district"};
    private final ClimateMapper mapper;
    public ExportService(ClimateMapper mapper) { this.mapper=mapper; }
    @Transactional(readOnly=true)
    public void thresholds(Writer writer) throws IOException {
        writeRows(mapper.thresholds(),new String[]{"stationCode","temp10","temp90","prcp95","prcp99","humidity5","tempSamples","prcpSamples","humiditySamples"},writer);
    }
    @Transactional(readOnly=true)
    public void stationAnnual(String station,int year,Writer writer) throws IOException {
        writeRows(mapper.stationAnnual(station,year),new String[]{"stationCode","year","metric","nonzeroDays","validDays","country","province","city","district"},writer);
    }
    @Transactional(readOnly=true)
    public void region(Map<String,Object> parameters,boolean annual,Writer writer) throws IOException {
        writeRows(annual?mapper.regionAnnual(parameters):mapper.regionDaily(parameters),annual?
            new String[]{"year","level","country","province","city","district","metric","avgDays","minDays","maxDays","medianDays","stationCount"}:
            new String[]{"date","level","country","province","city","district","metric","avgValue","minValue","maxValue","medianValue","validStationCount"},writer);
    }
    private void writeRows(List<Map<String,Object>> rows,String[] headers,Writer writer) throws IOException {
        writer.write('\uFEFF');
        try(CSVPrinter csv=new CSVPrinter(writer,CSVFormat.DEFAULT.builder().setHeader(headers).get())) {
            for(var row:rows) {
                var values=new ArrayList<Object>();
                for(String key:headers) values.add(safe(row.get(key)));
                csv.printRecord(values);
            }
        }
    }
    private Object safe(Object value) {
        if(value instanceof String s && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0))>=0) return "'"+s;
        return value;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public void write(String station,LocalDate from,LocalDate to,Writer writer) throws IOException {
        ClimateQueryService.validateDates(from,to);
        long total=mapper.countDaily(station,from,to);
        if (total>20_000) throw new IllegalArgumentException("Export exceeds 20,000 rows; narrow the date range");
        writer.write('\uFEFF');
        try(CSVPrinter csv=new CSVPrinter(writer,CSVFormat.DEFAULT.builder().setHeader(HEADERS).get())) {
            for (int offset=0;offset<total;offset+=500) {
                var page=mapper.daily(station,from,to,500,offset);
                for (var row:page) {
                    var values=new ArrayList<Object>();
                    for (String key:HEADERS) {
                        values.add(safe(row.get(key)));
                    }
                    csv.printRecord(values);
                }
            }
        }
    }
}
