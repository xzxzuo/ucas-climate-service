package cn.zuoxu.climate.platform;

import cn.zuoxu.climate.incremental.FileSnapshot;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Optional trusted-operator inbox; uses exactly the same staging and task path as browser uploads. */
@Component
public class PlatformInbox {
    private static final Logger log=LoggerFactory.getLogger(PlatformInbox.class);
    private final PlatformStore store;private final PlatformDatasets datasets;private final PlatformProperties properties;
    private final boolean enabled;private final long minAge;private int cursor;
    private final Map<Long,Integer> fileCursors=new HashMap<>();
    public PlatformInbox(PlatformStore store,PlatformDatasets datasets,PlatformProperties properties,
            @Value("${app.platform.inbox-enabled:false}")boolean enabled,@Value("${app.platform.inbox-min-age-ms:30000}")long minAge){
        this.store=store;this.datasets=datasets;this.properties=properties;this.enabled=enabled;this.minAge=Math.max(0,minAge);
    }
    @Scheduled(scheduler="platformTaskScheduler",fixedDelayString="${app.platform.inbox-delay-ms:10000}")
    public void poll(){if(enabled&&properties.workerEnabled)try{scanOnce();}catch(Exception e){log.warn("Platform inbox deferred: {}",e.getClass().getSimpleName());}}
    public synchronized int scanOnce()throws Exception{
        var owners=store.jdbc.queryForList("SELECT d.id,u.id actor_id,u.username,u.display_name,u.role FROM platform_dataset d JOIN platform_user u ON u.id=d.owner_id WHERE d.is_example=FALSE AND u.enabled=TRUE AND u.role<>'VIEWER' ORDER BY d.id");
        if(owners.isEmpty())return 0;int submitted=0,visited=0,limit=Math.min(20,owners.size());
        for(int i=0;i<limit;i++){
            var row=owners.get(Math.floorMod(cursor++,owners.size()));long dataset=PlatformStore.number(row,"id");
            Path directory=properties.directory.resolve("inbox").resolve(Long.toString(dataset));
            if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))continue;
            var actor=new PlatformUsers.Account(PlatformStore.number(row,"actor_id"),(String)row.get("username"),(String)row.get("display_name"),(String)row.get("role"),true);
            List<Path> entries=new ArrayList<>();
            try(var files=Files.newDirectoryStream(directory,"*.csv")){
                for(Path file:files){entries.add(file);if(entries.size()>500)break;}
            }
            if(entries.size()>500){log.warn("Platform inbox dataset={} exceeds 500 files; archive processed inputs before continuing",dataset);continue;}
            entries.sort(Comparator.comparing(p->p.getFileName().toString()));int start=fileCursors.getOrDefault(dataset,0);
            for(int j=0;j<entries.size();j++){
                    if(visited>=100||submitted>=5)return submitted;
                    Path file=entries.get(Math.floorMod(start+j,entries.size()));fileCursors.put(dataset,start+j+1);
                    visited++;
                    if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||System.currentTimeMillis()-Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toMillis()<minAge)continue;
                    try(FileSnapshot snapshot=FileSnapshot.capture(file,8L*1024*1024)){
                        var result=datasets.upload(actor,dataset,new SnapshotUpload(snapshot));
                        if(Boolean.FALSE.equals(result.get("duplicate"))){submitted++;@SuppressWarnings("unchecked")var task=(Map<String,Object>)result.get("task");store.audit(actor.id(),dataset,"AUTO_IMPORT_AS_OWNER",(String)task.get("id"));}
                    }catch(Exception e){log.warn("Platform inbox file deferred dataset={}: {}",dataset,e.getClass().getSimpleName());}
            }
        }return submitted;
    }
    private record SnapshotUpload(FileSnapshot snapshot)implements MultipartFile{
        public String getName(){return "file";}public String getOriginalFilename(){return snapshot.fileName();}public String getContentType(){return "text/csv";}
        public boolean isEmpty(){return snapshot.size()==0;}public long getSize(){return snapshot.size();}
        public byte[] getBytes()throws IOException{return Files.readAllBytes(snapshot.path());}
        public InputStream getInputStream()throws IOException{return Files.newInputStream(snapshot.path());}
        public void transferTo(File target)throws IOException{Files.copy(snapshot.path(),target.toPath());}
    }
}
