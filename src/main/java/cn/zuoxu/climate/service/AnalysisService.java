package cn.zuoxu.climate.service;

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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AnalysisService {
    private static final Logger log=LoggerFactory.getLogger(AnalysisService.class);
    private final JdbcTemplate jdbc;
    private final QueryCache cache;
    private final int minSamples;
    public AnalysisService(JdbcTemplate jdbc,QueryCache cache,@Value("${app.min-baseline-samples}") int minSamples) {
        if (minSamples<1) throw new IllegalArgumentException("min-baseline-samples must be positive");
        this.jdbc=jdbc;this.cache=cache;this.minSamples=minSamples;
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> rebuild() throws Exception {
        return calculate(null);
    }

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> rebuildStations(Set<String> stations) throws Exception {
        if (stations==null || stations.isEmpty() || stations.stream().anyMatch(s->!s.matches("[0-9]{11}"))) {
            throw new IllegalArgumentException("A nonempty set of normalized station codes is required");
        }
        return calculate(stations);
    }

    private Map<String,Object> calculate(Set<String> stations) throws Exception {
        long start=System.nanoTime();
        jdbc.queryForObject("SELECT id FROM analysis_state WHERE id=1 FOR UPDATE",Integer.class);
        Integer conflicts=jdbc.queryForObject("""
            SELECT COUNT(*) FROM (SELECT station_code,YEAR(observation_date) y FROM raw_observation
            WHERE observation_date>='1993-01-01' GROUP BY station_code,YEAR(observation_date)
            HAVING COUNT(DISTINCT country,province,city,district)>1) conflicts
            """,Integer.class);
        if (conflicts!=null && conflicts>0) throw new IllegalArgumentException("Station geography changes within a year; resolve metadata before computing annual counts");
        String scope=UUID.randomUUID().toString();
        String stationFilter="TRUE",dailyFilter="TRUE";
        if (stations!=null) {
            jdbc.batchUpdate("INSERT INTO analysis_scope(scope_id,station_code) VALUES(?,?)",
                stations.stream().map(s->new Object[]{scope,s}).toList());
            // Only a server-generated UUID enters this SQL template; station values are bound above.
            String selected="(SELECT station_code FROM analysis_scope WHERE scope_id='"+scope+"')";
            stationFilter="station_code IN "+selected;
            dailyFilter="d.station_code IN "+selected;
        }
        String sql=new ClassPathResource("sql/rebuild.sql").getContentAsString(StandardCharsets.UTF_8)
            .replace("__MIN_SAMPLES__",Integer.toString(minSamples))
            .replace("__STATION_FILTER__",stationFilter).replace("__DAILY_FILTER__",dailyFilter);
        jdbc.execute((ConnectionCallback<Void>) connection->{
            ScriptUtils.executeSqlScript(connection,new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8));return null;
        });
        if (stations!=null) jdbc.update("DELETE FROM analysis_scope WHERE scope_id=?",scope);
        Map<String,Object> state=jdbc.queryForMap("SELECT * FROM analysis_state WHERE id=1");
        long revision=((Number)state.get("revision")).longValue();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit() { cache.setRevision(revision); }
        });
        log.info("Analysis complete revision={} raw={} daily={} elapsedMs={}",revision,state.get("raw_rows"),state.get("analysis_rows"),(System.nanoTime()-start)/1_000_000);
        return state;
    }
}
