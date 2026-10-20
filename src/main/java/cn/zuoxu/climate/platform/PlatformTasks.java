package cn.zuoxu.climate.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class PlatformTasks {
    private final PlatformStore store;
    private final PlatformAccess access;
    private final ObjectMapper json;
    public PlatformTasks(PlatformStore store,PlatformAccess access,ObjectMapper json){this.store=store;this.access=access;this.json=json;}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> enqueue(PlatformUsers.Account user,long dataset,String type,String file,Map<String,String> payload,Long version,String retry) {
        access.dataset(user,dataset,!type.equals("EXPORT"));
        store.jdbc.queryForObject("SELECT id FROM platform_queue_guard WHERE id=1 FOR UPDATE",Integer.class);
        // RC + the admission guard sees committed submissions without locking a running worker's task row.
        int count=store.jdbc.queryForObject("SELECT COUNT(*) FROM platform_task WHERE status IN ('QUEUED','RUNNING')",Integer.class);
        if(count>=100)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Task backlog is full; retry later");
        String id=UUID.randomUUID().toString();
        try {store.jdbc.update("INSERT INTO platform_task(id,dataset_id,created_by,task_type,file_id,payload,result_version,retry_of) VALUES(?,?,?,?,?,?,?,?)",id,dataset,user.id(),type,file,json.writeValueAsString(payload),version,retry);}
        catch(java.io.IOException e){throw new IllegalArgumentException("Invalid task parameters");}
        store.audit(user.id(),dataset,"SUBMIT_"+type,id);return find(user,id);
    }
    public Map<String,Object> find(PlatformUsers.Account user,String id){
        Map<String,Object> task=store.one("SELECT * FROM platform_task WHERE id=?",id);if(task==null)throw PlatformAccess.missing();
        access.dataset(user,PlatformStore.number(task,"dataset_id"),false);return task;
    }
    public List<Map<String,Object>> list(PlatformUsers.Account user,Long dataset,int limit,int offset){
        if(limit<1||limit>100||offset<0||offset>100000)throw new IllegalArgumentException("Invalid pagination");
        if(dataset!=null)access.dataset(user,dataset,false);
        return store.jdbc.queryForList("""
          SELECT t.*,d.name dataset_name,u.display_name submitted_by FROM platform_task t
          JOIN platform_dataset d ON d.id=t.dataset_id JOIN platform_user u ON u.id=t.created_by
          WHERE (? OR d.owner_id=? OR d.is_example=TRUE OR EXISTS(SELECT 1 FROM platform_member m WHERE m.dataset_id=d.id AND m.user_id=?))
          AND (? IS NULL OR d.id=?) ORDER BY t.created_at DESC,t.id DESC LIMIT ? OFFSET ?
          """,user.admin(),user.id(),user.id(),dataset,dataset,limit,offset);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> retry(PlatformUsers.Account user,String id){
        Map<String,Object> task=find(user,id);
        if(!Set.of("FAILED","INTERRUPTED").contains(task.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only failed or interrupted tasks can be retried");
        store.jdbc.queryForObject("SELECT id FROM platform_queue_guard WHERE id=1 FOR UPDATE",Integer.class);
        if(store.jdbc.queryForObject("SELECT COUNT(*) FROM platform_task WHERE retry_of=? AND status IN ('QUEUED','RUNNING')",Integer.class,id)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"A retry is already pending");
        try {
            Map<String,String> args=json.readValue((String)task.get("payload"),new com.fasterxml.jackson.core.type.TypeReference<>(){});
            return enqueue(user,PlatformStore.number(task,"dataset_id"),(String)task.get("task_type"),(String)task.get("file_id"),args,
                task.get("result_version")==null?null:PlatformStore.number(task,"result_version"),id);
        }catch(java.io.IOException e){throw new IllegalArgumentException("Invalid saved task parameters");}
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void progress(String id,String phase,long rows){store.jdbc.update("UPDATE platform_task SET phase=?,processed_rows=? WHERE id=? AND status='RUNNING'",phase,rows,id);}
    public void success(String id,long rows,Long revision,Long version){
        store.jdbc.update("UPDATE platform_task SET status='SUCCESS',phase='COMPLETE',processed_rows=?,input_revision=?,result_version=COALESCE(?,result_version),finished_at=NOW(6) WHERE id=? AND status='RUNNING'",rows,revision,version,id);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void fail(String id,String code,String message){
        store.jdbc.update("UPDATE platform_task SET status='FAILED',phase='FAILED',error_code=?,error_message=?,finished_at=NOW(6) WHERE id=? AND status='RUNNING'",code,message.substring(0,Math.min(600,message.length())),id);
        store.jdbc.update("UPDATE platform_file f JOIN platform_task t ON t.file_id=f.id SET f.status='FAILED' WHERE t.id=? AND f.status<>'IMPORTED'",id);
    }
}
