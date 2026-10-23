package cn.zuoxu.climate.platform;

import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/platform/api")
public class PlatformController {
    private final PlatformUsers users;private final PlatformAccess access;private final PlatformStore store;
    private final PlatformDatasets datasets;private final PlatformTasks tasks;private final PlatformQueries queries;private final PlatformProperties properties;
    public PlatformController(PlatformUsers users,PlatformAccess access,PlatformStore store,PlatformDatasets datasets,PlatformTasks tasks,PlatformQueries queries,PlatformProperties properties){this.users=users;this.access=access;this.store=store;this.datasets=datasets;this.tasks=tasks;this.queries=queries;this.properties=properties;}
    record NewUser(String username,String displayName,String password,String role){}
    record NewDataset(String name,String description){}
    record Share(String username,String permission){}
    record Enabled(boolean enabled){}

    @GetMapping("/csrf") public Object csrf(CsrfToken token){return Map.of("headerName",token.getHeaderName(),"token",token.getToken());}
    @GetMapping("/me") public Object me(){return users.current();}
    @GetMapping("/admin/users") public Object userList(){return users.list();}
    @PostMapping("/admin/users") @ResponseStatus(HttpStatus.CREATED)
    public Object createUser(@RequestBody NewUser input){return users.create(input.username(),input.displayName(),input.password(),input.role());}
    @PatchMapping("/admin/users/{id}/enabled") public Object enable(@PathVariable long id,@RequestBody Enabled input){users.enable(id,input.enabled());return Map.of("updated",true);}

    @GetMapping("/datasets") public Object list(){return datasets.list(users.current());}
    @PostMapping("/datasets") @ResponseStatus(HttpStatus.CREATED)
    public Object create(@RequestBody NewDataset input){return datasets.create(users.current(),input.name(),input.description()==null?"":input.description());}
    @GetMapping("/datasets/{id}") public Object dataset(@PathVariable long id){var actor=users.current();return Map.of("dataset",access.dataset(actor,id,false),"versions",queries.versions(actor,id),"members",datasets.members(actor,id));}
    @PostMapping("/datasets/{id}/clone") @ResponseStatus(HttpStatus.CREATED)
    public Object cloneExample(@PathVariable long id,@RequestBody NewDataset input){return datasets.cloneExample(users.current(),id,input.name());}
    @PostMapping("/datasets/{id}/members") public Object share(@PathVariable long id,@RequestBody Share input){datasets.share(users.current(),id,input.username(),input.permission());return Map.of("updated",true);}
    @GetMapping("/datasets/{id}/files") public Object files(@PathVariable long id){return datasets.files(users.current(),id);}
    @PostMapping(value="/datasets/{id}/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.ACCEPTED)
    public Object upload(@PathVariable long id,@RequestParam("file")MultipartFile file)throws Exception{return datasets.upload(users.current(),id,file);}
    @PostMapping("/datasets/{id}/analyze") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object analyze(@PathVariable long id){return tasks.enqueue(users.current(),id,"ANALYZE",null,Map.of(),null,null);}
    @GetMapping("/tasks") public Object tasks(@RequestParam(required=false)Long datasetId,@RequestParam(defaultValue="50")int limit,@RequestParam(defaultValue="0")int offset){return tasks.list(users.current(),datasetId,limit,offset);}
    @GetMapping("/tasks/{id}") public Object task(@PathVariable String id){var task=tasks.find(users.current(),id);var export=store.one("SELECT * FROM platform_export WHERE task_id=?",id);if(export!=null)task.put("export",export);return task;}
    @PostMapping("/tasks/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object retry(@PathVariable String id){return tasks.retry(users.current(),id);}
    @GetMapping("/exports") public Object exports(@RequestParam(required=false)Long datasetId){
        var actor=users.current();if(datasetId!=null)access.dataset(actor,datasetId,false);
        return store.jdbc.queryForList("""
          SELECT e.*,d.name dataset_name,t.created_by FROM platform_export e JOIN platform_dataset d ON d.id=e.dataset_id JOIN platform_task t ON t.id=e.task_id
          WHERE t.status='SUCCESS' AND (? OR d.owner_id=? OR d.is_example=TRUE OR EXISTS(SELECT 1 FROM platform_member m WHERE m.dataset_id=d.id AND m.user_id=?))
          AND (? IS NULL OR d.id=?) ORDER BY e.created_at DESC LIMIT 100
          """,actor.admin(),actor.id(),actor.id(),datasetId,datasetId);
    }
    @GetMapping("/exports/{id}/download") public ResponseEntity<Resource> download(@PathVariable String id)throws Exception{
        var actor=users.current();var row=store.one("SELECT e.* FROM platform_export e JOIN platform_task t ON t.id=e.task_id WHERE e.id=? AND t.status='SUCCESS'",id);if(row==null)throw PlatformAccess.missing();
        long dataset=PlatformStore.number(row,"dataset_id");access.dataset(actor,dataset,false);
        if(((Timestamp)row.get("expires_at")).toInstant().isBefore(Instant.now()))throw new ResponseStatusException(HttpStatus.GONE,"Export expired; create a new export task");
        Path file=properties.export(id,false);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new ResponseStatusException(HttpStatus.GONE,"Export file is no longer available");
        store.audit(actor.id(),dataset,"DOWNLOAD_EXPORT",id);
        return ResponseEntity.ok().contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).contentLength(Files.size(file))
            .header("Content-Disposition",ContentDisposition.attachment().filename("dataset-"+dataset+"-v"+row.get("result_version")+".csv").build().toString()).header("Cache-Control","no-store").body(new FileSystemResource(file));
    }
    @GetMapping("/template.csv") public ResponseEntity<String> template(){users.current();return ResponseEntity.ok().contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).header("Content-Disposition","attachment; filename=climate-template.csv")
        .body("STATION,DATE,NAME,TEMP,PRCP,HUMIDITY,MAX,MIN,FRSHTT,country,province,city,district\r\n");}
    @GetMapping("/audit") public Object audit(@RequestParam(required=false)Long datasetId){
        var actor=users.current();if(datasetId!=null)access.dataset(actor,datasetId,false);
        return store.jdbc.queryForList("""
          SELECT a.*,u.display_name actor_name FROM platform_audit a JOIN platform_user u ON u.id=a.actor_id
          LEFT JOIN platform_dataset d ON d.id=a.dataset_id WHERE
          (? OR a.actor_id=? OR d.owner_id=? OR d.is_example=TRUE OR EXISTS(SELECT 1 FROM platform_member m WHERE m.dataset_id=d.id AND m.user_id=?))
          AND (? IS NULL OR a.dataset_id=?) ORDER BY a.id DESC LIMIT 100
          """,actor.admin(),actor.id(),actor.id(),actor.id(),datasetId,datasetId);
    }
}
