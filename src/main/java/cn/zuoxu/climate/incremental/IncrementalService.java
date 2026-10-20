package cn.zuoxu.climate.incremental;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class IncrementalService {
    public record RunResult(String trigger,boolean busy,String completedAt,List<IncrementalFileProcessor.Result> files,String error) {}
    private static final Logger log=LoggerFactory.getLogger(IncrementalService.class);
    private final IncrementalProperties properties;
    private final IncrementalFileProcessor processor;
    private final IncrementalJobStore jobs;
    private final AtomicBoolean running=new AtomicBoolean();
    private volatile RunResult lastRun;
    private int cursor;

    public IncrementalService(IncrementalProperties properties,IncrementalFileProcessor processor,IncrementalJobStore jobs) {
        this.properties=properties;this.processor=processor;this.jobs=jobs;
    }

    public Map<String,Object> status() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("enabled",properties.enabled());result.put("running",running.get());
        result.put("directory",properties.directory());result.put("fixedDelayMs",properties.fixedDelayMs());
        result.put("minFileAgeMs",properties.minFileAgeMs());result.put("maxFileBytes",properties.maxFileBytes());
        result.put("maxFilesPerRun",properties.maxFilesPerRun());result.put("maxInboxFiles",properties.maxInboxFiles());
        result.put("maxAttempts",properties.maxAttempts());result.put("retryDelaySeconds",properties.retryDelaySeconds());
        result.put("lastRun",lastRun);
        return result;
    }

    public RunResult runOnce(String trigger,boolean retryFailed) {
        if (!running.compareAndSet(false,true)) return new RunResult(trigger,true,Instant.now().toString(),List.of(),"ALREADY_RUNNING");
        var results=new ArrayList<IncrementalFileProcessor.Result>();
        String scanError=null;
        RunResult completed;
        try {
            Path inbox=Path.of(properties.directory()).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(inbox)) throw new IllegalArgumentException("Inbox must not be a symbolic link");
            Files.createDirectories(inbox);
            List<Path> inputs;
            try (var entries=Files.list(inbox)) {
                inputs=entries.filter(path->path.getFileName().toString().endsWith(".csv"))
                    .limit(properties.maxInboxFiles()+1L).sorted(Comparator.comparing(path->path.getFileName().toString())).toList();
            }
            if (inputs.size()>properties.maxInboxFiles()) throw new IllegalArgumentException("Inbox has too many CSV files; archive completed inputs");
            if (!inputs.isEmpty()) {
                int count=Math.min(properties.maxFilesPerRun(),inputs.size());
                for (int index=0;index<count;index++) {
                    Path input=inputs.get(Math.floorMod(cursor++,inputs.size()));
                    String fileName=input.getFileName().toString();
                    try {
                        if (Files.getLastModifiedTime(input,LinkOption.NOFOLLOW_LINKS).toMillis()>System.currentTimeMillis()-properties.minFileAgeMs()) {
                            results.add(new IncrementalFileProcessor.Result(fileName,"DEFERRED",null,0,null,"FILE_TOO_RECENT"));
                            continue;
                        }
                        try (var snapshot=FileSnapshot.capture(input,properties.maxFileBytes())) {
                            try { results.add(processor.process(snapshot,retryFailed)); }
                            catch (Exception exception) {
                                String code=exception.getClass().getSimpleName();
                                jobs.failure(snapshot,code);
                                results.add(new IncrementalFileProcessor.Result(fileName,"FAILED",snapshot.sha256(),0,null,code));
                                log.warn("incremental_file_failed file={} type={}",fileName,code);
                            }
                        }
                    } catch (Exception exception) {
                        results.add(new IncrementalFileProcessor.Result(fileName,"REJECTED",null,0,null,exception.getClass().getSimpleName()));
                        log.warn("incremental_input_rejected file={} type={}",fileName,exception.getClass().getSimpleName());
                    }
                }
            }
        } catch (Exception exception) {
            scanError=exception instanceof IllegalArgumentException?exception.getMessage():exception.getClass().getSimpleName();
            log.warn("incremental_scan_failed type={}",exception.getClass().getSimpleName());
        } finally {
            completed=new RunResult(trigger,false,Instant.now().toString(),List.copyOf(results),scanError);
            lastRun=completed;
            running.set(false);
        }
        log.info("incremental_run trigger={} files={} scanError={}",trigger,results.size(),scanError);
        return completed;
    }
}
