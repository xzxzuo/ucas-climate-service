package cn.zuoxu.climate.incremental;

import cn.zuoxu.climate.data.ImportService;
import cn.zuoxu.climate.service.AnalysisService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

@Service
public class IncrementalFileProcessor {
    public record Result(String fileName,String status,String sha256,int importedRows,Long revision,String errorCode) {}
    private final JdbcTemplate jdbc;
    private final ImportService importer;
    private final AnalysisService analysis;
    private final IncrementalJobStore jobs;
    private final IncrementalProperties properties;

    public IncrementalFileProcessor(JdbcTemplate jdbc,ImportService importer,AnalysisService analysis,IncrementalJobStore jobs,IncrementalProperties properties) {
        this.jdbc=jdbc;this.importer=importer;this.analysis=analysis;this.jobs=jobs;this.properties=properties;
    }

    @Transactional(rollbackFor=Exception.class,timeout=120)
    public Result process(FileSnapshot input,boolean retryFailed) throws Exception {
        // All mutations (CLI/full rebuild/incremental) serialize through this same row.
        jdbc.queryForObject("SELECT id FROM analysis_state WHERE id=1 FOR UPDATE",Integer.class);
        Map<String,Object> existing=jobs.find(input.sha256());
        if (existing!=null && "SUCCESS".equals(existing.get("status"))) {
            return new Result(input.fileName(),"SKIPPED",input.sha256(),0,((Number)existing.get("revision")).longValue(),null);
        }
        if (existing!=null && !retryFailed) {
            int attempts=((Number)existing.get("attempts")).intValue();
            Timestamp retry=(Timestamp)existing.get("next_retry_at");
            if (attempts>=properties.maxAttempts() || (retry!=null && retry.toInstant().isAfter(Instant.now()))) {
                return new Result(input.fileName(),"DEFERRED",input.sha256(),0,null,"RETRY_LIMIT_OR_BACKOFF");
            }
        }
        jobs.begin(input);
        var imported=importer.importDetailed(input.path());
        var state=analysis.rebuildStations(imported.stations());
        long revision=((Number)state.get("revision")).longValue();
        jobs.success(input,imported.rows(),imported.stations().size(),revision);
        return new Result(input.fileName(),"SUCCESS",input.sha256(),imported.rows(),revision,null);
    }
}
