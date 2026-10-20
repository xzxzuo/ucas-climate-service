package cn.zuoxu.climate.platform;

import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import java.nio.channels.*;
import java.nio.file.*;

@Component
public class PlatformWorker {
    private static final Logger log=LoggerFactory.getLogger(PlatformWorker.class);
    private final PlatformStore store;private final PlatformProcessor processor;private final PlatformTasks tasks;private final PlatformProperties properties;
    private volatile boolean ready;private FileChannel channel;private FileLock lock;
    public PlatformWorker(PlatformStore store,PlatformProcessor processor,PlatformTasks tasks,PlatformProperties properties){this.store=store;this.processor=processor;this.tasks=tasks;this.properties=properties;}
    @Bean("platformTaskScheduler") static ThreadPoolTaskScheduler scheduler(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("climate-platform-");scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(30);return scheduler;}
    @EventListener(ApplicationReadyEvent.class) @Order(100)
    public void start()throws Exception{
        if(!properties.workerEnabled)return;Files.createDirectories(properties.directory);
        channel=FileChannel.open(properties.directory.resolve("worker.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try{lock=channel.tryLock();if(lock==null)throw new IllegalStateException("Another platform worker uses this data directory");}
        catch(Exception e){channel.close();throw e;}
        recover();ready=true;
    }
    public void recover(){store.jdbc.update("UPDATE platform_task SET status='INTERRUPTED',phase='INTERRUPTED',error_code='SERVICE_RESTARTED',error_message='Service restarted while this task was running; retry manually.',finished_at=NOW(6) WHERE status='RUNNING'");}
    @Scheduled(scheduler="platformTaskScheduler",fixedDelayString="${app.platform.poll-delay-ms:750}")
    public void poll(){if(!ready)return;try{runOnce();}catch(Exception e){log.warn("Platform dispatch paused: {}",e.getClass().getSimpleName());}}
    public synchronized boolean runOnce(){
        var row=store.one("SELECT id FROM platform_task WHERE status='QUEUED' ORDER BY created_at,id LIMIT 1");if(row==null)return false;
        String id=(String)row.get("id");if(store.jdbc.update("UPDATE platform_task SET status='RUNNING',phase='STARTING',started_at=NOW(6) WHERE id=? AND status='QUEUED'",id)!=1)return true;
        try{processor.process(id);log.info("Platform task {} completed",id);}
        catch(Exception e){
            if(e instanceof InterruptedException||Thread.currentThread().isInterrupted())store.jdbc.update("UPDATE platform_task SET status='INTERRUPTED',phase='INTERRUPTED',error_code='SERVICE_SHUTDOWN',finished_at=NOW(6) WHERE id=?",id);
            else{boolean invalid=e instanceof IllegalArgumentException;String message=invalid?e.getMessage():"Task processing failed ("+e.getClass().getSimpleName()+"); consult the server log with task ID";tasks.fail(id,invalid?"INVALID_DATA":"PROCESSING_FAILED",message==null?"Task failed":message);log.warn("Platform task {} failed: {}",id,e.getClass().getSimpleName());}
        }return true;
    }
    @PreDestroy public void close(){ready=false;try{if(lock!=null)lock.release();if(channel!=null)channel.close();}catch(java.io.IOException ignored){}}
}
