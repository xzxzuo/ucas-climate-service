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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class PlatformWorker {
    private static final Logger log=LoggerFactory.getLogger(PlatformWorker.class);
    private final PlatformStore store;private final PlatformProcessor processor;private final PlatformTasks tasks;private final PlatformProperties properties;
    private final PlatformExecutionSettings settings;
    private final ThreadPoolExecutor writers,exporters;
    // Only scheduling and bookkeeping hold this monitor, never the slow task processor.
    private final Map<Long,String> writingDatasets=new HashMap<>();
    private final Set<String> activeTasks=new HashSet<>();
    private int activeWrites,activeExports;
    private boolean closing;
    private volatile boolean ready;private FileChannel channel;private FileLock lock;
    public PlatformWorker(PlatformStore store,PlatformProcessor processor,PlatformTasks tasks,
                          PlatformProperties properties,PlatformExecutionSettings settings){
        this.store=store;this.processor=processor;this.tasks=tasks;this.properties=properties;this.settings=settings;
        writers=executor(settings.writeThreads(),"climate-write-");
        exporters=executor(settings.exportThreads(),"climate-export-");
    }
    private static ThreadPoolExecutor executor(int size,String prefix){
        var sequence=new AtomicInteger();
        return new ThreadPoolExecutor(size,size,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),
            action->new Thread(action,prefix+sequence.incrementAndGet()),new ThreadPoolExecutor.AbortPolicy());
    }
    @Bean("platformTaskScheduler") static ThreadPoolTaskScheduler scheduler(){return scheduler("climate-dispatch-");}
    @Bean("platformInboxScheduler") static ThreadPoolTaskScheduler inboxScheduler(){return scheduler("climate-inbox-");}
    private static ThreadPoolTaskScheduler scheduler(String prefix){
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix(prefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(30);return scheduler;
    }
    @EventListener(ApplicationReadyEvent.class) @Order(100)
    public void start()throws Exception{
        if(!properties.workerEnabled)return;Files.createDirectories(properties.directory);
        channel=FileChannel.open(properties.directory.resolve("worker.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try{lock=channel.tryLock();if(lock==null)throw new IllegalStateException("Another platform worker uses this data directory");recover();ready=true;}
        catch(Exception e){channel.close();throw e;}
    }
    public synchronized void recover(){
        if(!activeTasks.isEmpty())throw new IllegalStateException("Cannot recover while local tasks are active");
        store.jdbc.update("UPDATE platform_task SET status='INTERRUPTED',phase='INTERRUPTED',error_code='SERVICE_RESTARTED',error_message='Service restarted while this task was running; retry manually.',finished_at=NOW(6) WHERE status='RUNNING'");
    }
    @Scheduled(scheduler="platformTaskScheduler",fixedDelayString="${app.platform.poll-delay-ms:750}")
    public void poll(){
        if(!ready)return;
        try{while(runOnce()){ /* Fill free execution slots; waiting tasks stay in MySQL. */ }}
        catch(Exception e){log.warn("Platform dispatch paused: {}",e.getClass().getSimpleName());}
    }
    /** Dispatch one task. A true result means accepted for execution, not completed. */
    public synchronized boolean runOnce(){
        if(closing||(activeWrites>=settings.writeThreads()&&activeExports>=settings.exportThreads()))return false;
        var queued=store.jdbc.queryForList("SELECT id,dataset_id,task_type FROM platform_task WHERE status='QUEUED' ORDER BY created_at,id LIMIT 100");
        for(var row:queued){
            String id=(String)row.get("id");long dataset=PlatformStore.number(row,"dataset_id");boolean export="EXPORT".equals(row.get("task_type"));
            // Skip blocked datasets and full pools, so they cannot starve independent work.
            if(export?activeExports>=settings.exportThreads():activeWrites>=settings.writeThreads()||writingDatasets.containsKey(dataset))continue;
            if(store.jdbc.update("UPDATE platform_task SET status='RUNNING',phase='STARTING',started_at=NOW(6) WHERE id=? AND status='QUEUED'",id)!=1)continue;
            activeTasks.add(id);if(export)activeExports++;else{activeWrites++;writingDatasets.put(dataset,id);}
            try{(export?exporters:writers).execute(()->execute(id,dataset,export));return true;}
            catch(RejectedExecutionException rejected){
                // A zero-queue pool can reject during thread hand-off or shutdown. Preserve the job.
                release(id,dataset,export);
                store.jdbc.update("UPDATE platform_task SET status='QUEUED',phase='WAITING',started_at=NULL WHERE id=? AND status='RUNNING'",id);
                return false;
            }
        }
        return false;
    }
    private void execute(String id,long dataset,boolean export){
        log.info("Platform task {} started dataset={} lane={}",id,dataset,export?"EXPORT":"WRITE");
        try{processor.process(id);log.info("Platform task {} completed",id);}
        catch(Exception e){
            if(e instanceof InterruptedException||Thread.currentThread().isInterrupted()||isClosing())store.jdbc.update("UPDATE platform_task SET status='INTERRUPTED',phase='INTERRUPTED',error_code='SERVICE_SHUTDOWN',finished_at=NOW(6) WHERE id=? AND status='RUNNING'",id);
            else{boolean invalid=e instanceof IllegalArgumentException;String message=invalid?e.getMessage():"Task processing failed ("+e.getClass().getSimpleName()+"); consult the server log with task ID";tasks.fail(id,invalid?"INVALID_DATA":"PROCESSING_FAILED",message==null?"Task failed":message);log.warn("Platform task {} failed: {}",id,e.getClass().getSimpleName());}
        }finally{release(id,dataset,export);}
    }
    private synchronized boolean isClosing(){return closing;}
    public synchronized int activeTaskCount(){return activeTasks.size();}
    private synchronized void release(String id,long dataset,boolean export){
        if(!activeTasks.remove(id))return;
        if(export)activeExports--;else{activeWrites--;writingDatasets.remove(dataset,id);}
        if(closing&&activeTasks.isEmpty())releaseFileLock();
    }
    @PreDestroy public void close(){
        synchronized(this){closing=true;ready=false;}
        writers.shutdown();exporters.shutdown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        try{
            writers.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            exporters.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        finally{
            writers.shutdownNow();exporters.shutdownNow();
            synchronized(this){if(activeTasks.isEmpty())releaseFileLock();else log.warn("Platform shutdown interrupted {} tasks; worker lock retained until they exit",activeTasks.size());}
        }
    }
    private void releaseFileLock(){
        try{if(lock!=null&&lock.isValid())lock.release();if(channel!=null&&channel.isOpen())channel.close();}
        catch(java.io.IOException e){log.warn("Could not release platform worker lock: {}",e.getClass().getSimpleName());}
    }
}
