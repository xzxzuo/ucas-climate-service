package cn.zuoxu.climate.platform;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

@Service
public class PlatformDatasets {
    private final PlatformStore store;
    private final PlatformAccess access;
    private final PlatformTasks tasks;
    private final PlatformProperties properties;
    static final List<String> RESULTS=List.of("station_threshold","daily_indicator","station_year_stat","region_daily_stat","region_year_stat");
    public PlatformDatasets(PlatformStore store,PlatformAccess access,PlatformTasks tasks,PlatformProperties properties){this.store=store;this.access=access;this.tasks=tasks;this.properties=properties;}
    public List<Map<String,Object>> list(PlatformUsers.Account user){
        var rows=store.jdbc.queryForList("""
          SELECT d.*,u.display_name owner_name,m.permission member_permission
          FROM platform_dataset d JOIN platform_user u ON u.id=d.owner_id
          LEFT JOIN platform_member m ON m.dataset_id=d.id AND m.user_id=?
          WHERE ? OR d.owner_id=? OR d.is_example=TRUE OR m.user_id IS NOT NULL ORDER BY d.id DESC LIMIT 100
          """,user.id(),user.admin(),user.id());
        for(var row:rows){boolean owner=PlatformStore.number(row,"owner_id")==user.id(),example=PlatformStore.flag(row,"is_example");
            row.put("can_write",!example&&user.canWrite()&&(user.admin()||owner||"WRITE".equals(row.get("member_permission"))));row.put("can_manage",!example&&(user.admin()||owner));}
        return rows;
    }
    @Transactional public Map<String,Object> create(PlatformUsers.Account user,String name,String description){
        if(!user.canWrite())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Viewer cannot create datasets");
        if(name==null||name.isBlank()||name.length()>120||description==null||description.length()>1000)throw new IllegalArgumentException("Name is required (max 120); description max 1000");
        if(store.jdbc.queryForObject("SELECT COUNT(*) FROM platform_dataset WHERE owner_id=?",Integer.class,user.id())>=30)throw new ResponseStatusException(HttpStatus.CONFLICT,"Local demo limit: 30 datasets per owner");
        long id=store.insert("INSERT INTO platform_dataset(name,description,owner_id) VALUES(?,?,?)",name.strip(),description.strip(),user.id());
        store.jdbc.update("INSERT INTO platform_dataset_write_guard(dataset_id) VALUES(?)",id);
        try{Files.createDirectories(properties.directory.resolve("inbox").resolve(Long.toString(id)));}catch(IOException e){throw new IllegalStateException("Cannot create dataset inbox",e);}
        store.audit(user.id(),id,"CREATE_DATASET",Long.toString(id));return access.dataset(user,id,false);
    }
    @Transactional public Map<String,Object> cloneExample(PlatformUsers.Account user,long source,String name){
        Map<String,Object> original=access.dataset(user,source,false);
        if(!PlatformStore.flag(original,"is_example"))throw new IllegalArgumentException("Only the read-only example can be cloned");
        long target=PlatformStore.number(create(user,name,"从只读真实样本复制；可独立导入与计算"),"id");
        store.jdbc.update("INSERT INTO platform_raw_observation SELECT ?,station_code,observation_date,station_name,temp_f,precip_in,humidity_ratio,max_temp_f,min_temp_f,weather_flags,country,province,city,district FROM platform_raw_observation WHERE dataset_id=?",target,source);
        Long version=original.get("active_result_version")==null?null:PlatformStore.number(original,"active_result_version");
        if(version!=null){
            for(String table:RESULTS)copyResults(table,target,source,version);
            store.jdbc.update("INSERT INTO platform_analysis_run(dataset_id,result_version,input_revision,formula_version,raw_rows,analysis_rows,missing_threshold_rows) SELECT ?,1,1,formula_version,raw_rows,analysis_rows,missing_threshold_rows FROM platform_analysis_run WHERE dataset_id=? AND result_version=?",target,source,version);
        }
        refreshCounts(target);
        store.jdbc.update("UPDATE platform_dataset SET raw_revision=1,active_result_version=?,next_result_version=? WHERE id=?",version==null?null:1,version==null?1:2,target);
        store.audit(user.id(),target,"CLONE_EXAMPLE",Long.toString(source));return access.dataset(user,target,false);
    }
    private void copyResults(String table,long target,long source,long version){
        // Column names come from database metadata, never from a request; table is a fixed internal allowlist.
        var columns=store.jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION",String.class,"platform_"+table);
        String rest=columns.stream().filter(c->!c.equals("dataset_id")&&!c.equals("result_version")).map(c->"`"+c+"`").collect(java.util.stream.Collectors.joining(","));
        store.jdbc.update("INSERT INTO platform_"+table+" SELECT ?,1,"+rest+" FROM platform_"+table+" WHERE dataset_id=? AND result_version=?",target,source,version);
    }
    @Transactional public void share(PlatformUsers.Account actor,long dataset,String username,String permission){
        Map<String,Object> row=access.dataset(actor,dataset,false);
        if(!Boolean.TRUE.equals(row.get("can_manage")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only owner or administrator can share a dataset");
        var user=store.one("SELECT id,role,enabled FROM platform_user WHERE username=?",username);
        if(user==null||!PlatformStore.flag(user,"enabled"))throw new IllegalArgumentException("Enabled user not found");
        long target=PlatformStore.number(user,"id");if(target==PlatformStore.number(row,"owner_id"))throw new IllegalArgumentException("Owner permissions cannot be changed here");
        if(permission==null||!Set.of("READ","WRITE","REMOVE").contains(permission))throw new IllegalArgumentException("Permission must be READ, WRITE or REMOVE");
        if(permission.equals("WRITE")&&user.get("role").equals("VIEWER"))throw new IllegalArgumentException("Viewer accounts cannot receive write access");
        if(permission.equals("REMOVE"))store.jdbc.update("DELETE FROM platform_member WHERE dataset_id=? AND user_id=?",dataset,target);
        else store.jdbc.update("INSERT INTO platform_member(dataset_id,user_id,permission) VALUES(?,?,?) ON DUPLICATE KEY UPDATE permission=VALUES(permission)",dataset,target,permission);
        store.audit(actor.id(),dataset,"SHARE_"+permission,Long.toString(target));
    }
    public List<Map<String,Object>> members(PlatformUsers.Account user,long dataset){
        access.dataset(user,dataset,false);return store.jdbc.queryForList("SELECT u.username,u.display_name,m.permission FROM platform_member m JOIN platform_user u ON u.id=m.user_id WHERE m.dataset_id=? ORDER BY u.id",dataset);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class) public Map<String,Object> upload(PlatformUsers.Account user,long dataset,MultipartFile file)throws Exception{
        access.dataset(user,dataset,true);String name=file.getOriginalFilename();
        if(name==null||name.length()>160||!name.toLowerCase(Locale.ROOT).endsWith(".csv")||name.contains("/")||name.contains("\\")||name.chars().anyMatch(c->c<32))throw new IllegalArgumentException("Upload a CSV basename of at most 160 characters");
        if(file.isEmpty()||file.getSize()>8*1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"CSV must be nonempty and at most 8 MiB");
        String id=UUID.randomUUID().toString();Path path=properties.upload(id);Files.createDirectories(path.getParent());
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;
        try(InputStream in=file.getInputStream();OutputStream out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)){
            byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){bytes+=n;if(bytes>8*1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"CSV exceeds 8 MiB");digest.update(buffer,0,n);out.write(buffer,0,n);}
        }catch(Exception e){Files.deleteIfExists(path);throw e;}
        String sha=HexFormat.of().formatHex(digest.digest());
        var existing=store.one("SELECT * FROM platform_file WHERE dataset_id=? AND sha256=?",dataset,sha);
        if(existing!=null){Files.deleteIfExists(path);var task=store.one("SELECT * FROM platform_task WHERE file_id=? ORDER BY created_at DESC LIMIT 1",existing.get("id"));return Map.of("duplicate",true,"file",existing,"task",task);}
        // The staged file is kept if commit becomes uncertain; no uncommitted file is downloadable.
        store.jdbc.update("INSERT INTO platform_file(id,dataset_id,uploader_id,original_name,sha256,file_size) VALUES(?,?,?,?,?,?)",id,dataset,user.id(),name,sha,bytes);
        var task=tasks.enqueue(user,dataset,"IMPORT",id,Map.of(),null,null);
        return Map.of("duplicate",false,"file",store.one("SELECT * FROM platform_file WHERE id=?",id),"task",task);
    }
    public List<Map<String,Object>> files(PlatformUsers.Account user,long dataset){access.dataset(user,dataset,false);return store.jdbc.queryForList("SELECT * FROM platform_file WHERE dataset_id=? ORDER BY created_at DESC LIMIT 100",dataset);}
    void refreshCounts(long dataset){store.jdbc.update("""
      UPDATE platform_dataset SET raw_rows=(SELECT COUNT(*) FROM platform_raw_observation WHERE dataset_id=?),
      station_count=(SELECT COUNT(DISTINCT station_code) FROM platform_raw_observation WHERE dataset_id=?),
      first_date=(SELECT MIN(observation_date) FROM platform_raw_observation WHERE dataset_id=?),
      last_date=(SELECT MAX(observation_date) FROM platform_raw_observation WHERE dataset_id=?),updated_at=NOW(6) WHERE id=?
      """,dataset,dataset,dataset,dataset,dataset);}
}
