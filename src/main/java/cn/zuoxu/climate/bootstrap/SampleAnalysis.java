package cn.zuoxu.climate.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class SampleAnalysis {
    private static final Logger log=LoggerFactory.getLogger(SampleAnalysis.class);
    private final JdbcTemplate jdbc;
    private final int minSamples;
    public SampleAnalysis(JdbcTemplate jdbc,@Value("${app.min-baseline-samples}") int minSamples) {
        if (minSamples<1) throw new IllegalArgumentException("min-baseline-samples must be positive");
        this.jdbc=jdbc;this.minSamples=minSamples;
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> rebuild() throws Exception {
        long start=System.nanoTime();
        jdbc.queryForObject("SELECT id FROM analysis_state WHERE id=1 FOR UPDATE",Integer.class);
        Integer conflicts=jdbc.queryForObject("""
            SELECT COUNT(*) FROM (SELECT station_code,YEAR(observation_date) y FROM raw_observation
            WHERE observation_date>='1993-01-01' GROUP BY station_code,YEAR(observation_date)
            HAVING COUNT(DISTINCT country,province,city,district)>1) conflicts
            """,Integer.class);
        if (conflicts!=null && conflicts>0) throw new IllegalArgumentException("Station geography changes within a year; resolve metadata before computing annual counts");
        String sql=new ClassPathResource("sql/rebuild.sql").getContentAsString(StandardCharsets.UTF_8)
            .replace("__MIN_SAMPLES__",Integer.toString(minSamples))
            .replace("__STATION_FILTER__","TRUE").replace("__DAILY_FILTER__","TRUE");
        jdbc.execute((ConnectionCallback<Void>) connection->{
            ScriptUtils.executeSqlScript(connection,new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8));return null;
        });
        Map<String,Object> state=jdbc.queryForMap("SELECT * FROM analysis_state WHERE id=1");
        long revision=((Number)state.get("revision")).longValue();
        log.info("Analysis complete revision={} raw={} daily={} elapsedMs={}",revision,state.get("raw_rows"),state.get("analysis_rows"),(System.nanoTime()-start)/1_000_000);
        return state;
    }
}
