package cn.zuoxu.climate.incremental;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

@Service
public class IncrementalJobStore {
    private final JdbcTemplate jdbc;
    private final IncrementalProperties properties;
    public IncrementalJobStore(JdbcTemplate jdbc,IncrementalProperties properties) { this.jdbc=jdbc;this.properties=properties; }

    public Map<String,Object> find(String digest) {
        var rows=jdbc.queryForList("SELECT * FROM incremental_file_job WHERE sha256=?",digest);
        return rows.isEmpty()?null:rows.get(0);
    }

    public List<Map<String,Object>> latest(int limit,int offset) {
        return jdbc.queryForList("SELECT * FROM incremental_file_job ORDER BY completed_at DESC,id DESC LIMIT ? OFFSET ?",limit,offset);
    }

    public void begin(FileSnapshot input) {
        jdbc.update("""
            INSERT INTO incremental_file_job(sha256,file_name,file_size,status) VALUES(?,?,?,'RUNNING')
            ON DUPLICATE KEY UPDATE status='RUNNING',attempts=attempts+1,error_code=NULL,next_retry_at=NULL
            """,input.sha256(),input.fileName(),input.size());
    }

    public void success(FileSnapshot input,int rows,int stations,long revision) {
        jdbc.update("""
            UPDATE incremental_file_job SET status='SUCCESS',imported_rows=?,affected_stations=?,revision=?,
              error_code=NULL,next_retry_at=NULL,completed_at=CURRENT_TIMESTAMP(6) WHERE sha256=?
            """,rows,stations,revision,input.sha256());
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void failure(FileSnapshot input,String errorCode) {
        // A concurrent successful commit must never be downgraded by a failure audit write.
        jdbc.update("""
            INSERT INTO incremental_file_job(sha256,file_name,file_size,status,error_code,next_retry_at)
            VALUES(?,?,?,'FAILED',?,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)))
            ON DUPLICATE KEY UPDATE
              error_code=IF(status='SUCCESS',error_code,VALUES(error_code)),
              next_retry_at=IF(status='SUCCESS',next_retry_at,VALUES(next_retry_at)),
              attempts=IF(status='SUCCESS',attempts,attempts+1),
              completed_at=IF(status='SUCCESS',completed_at,CURRENT_TIMESTAMP(6)),
              status=IF(status='SUCCESS',status,'FAILED')
            """,input.sha256(),input.fileName(),input.size(),errorCode,properties.retryDelaySeconds());
    }
}
