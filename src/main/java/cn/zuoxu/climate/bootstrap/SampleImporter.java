package cn.zuoxu.climate.bootstrap;

import cn.zuoxu.climate.data.Observation;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class SampleImporter {
    private final JdbcTemplate jdbc;
    private final int maxRows;
    private static final String UPSERT="""
        INSERT INTO raw_observation(station_code,observation_date,station_name,temp_f,precip_in,humidity_ratio,
          max_temp_f,min_temp_f,weather_flags,country,province,city,district) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON DUPLICATE KEY UPDATE station_name=VALUES(station_name),temp_f=VALUES(temp_f),precip_in=VALUES(precip_in),
          humidity_ratio=VALUES(humidity_ratio),max_temp_f=VALUES(max_temp_f),min_temp_f=VALUES(min_temp_f),
          weather_flags=VALUES(weather_flags),country=VALUES(country),province=VALUES(province),
          city=VALUES(city),district=VALUES(district)
        """;
    public SampleImporter(JdbcTemplate jdbc,@Value("${app.max-import-rows}") int maxRows) { this.jdbc=jdbc;this.maxRows=maxRows; }

    @Transactional(rollbackFor=Exception.class)
    public int importFile(Path path) throws IOException {
        if (Files.size(path)>64L*1024*1024) throw new IllegalArgumentException("Import accepts normalized samples <=64 MiB; run sample_source.py first");
        jdbc.queryForObject("SELECT id FROM analysis_state WHERE id=1 FOR UPDATE",Integer.class);
        int count=0;
        try (var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8);
             CSVParser parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            if (!parser.getHeaderNames().equals(List.of("STATION","DATE","NAME","TEMP","PRCP","HUMIDITY","MAX","MIN","FRSHTT","country","province","city","district")))
                throw new IllegalArgumentException("Expected the normalized sample header, not the original 40-column CSV");
            List<Object[]> batch=new ArrayList<>(500);
            for (var row:parser) {
                if (++count>maxRows) throw new IllegalArgumentException("Sample exceeds max-import-rows="+maxRows);
                if (!row.isConsistent()) throw new IllegalArgumentException("CSV record "+row.getRecordNumber()+" has unexpected column count");
                try {
                    Observation observation=Observation.from(row.toMap());
                    batch.add(observation.parameters());
                }
                catch (RuntimeException e) { throw new IllegalArgumentException("CSV record "+row.getRecordNumber()+": "+e.getMessage(),e); }
                if (batch.size()==500) { jdbc.batchUpdate(UPSERT,batch);batch.clear(); }
            }
            if (!batch.isEmpty()) jdbc.batchUpdate(UPSERT,batch);
        }
        if (count==0) throw new IllegalArgumentException("CSV must contain at least one observation");
        return count;
    }
}
