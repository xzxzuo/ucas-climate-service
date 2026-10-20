package cn.zuoxu.climate.platform;

import cn.zuoxu.climate.data.Observation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class PlatformProcessor {
    private final PlatformStore store;
    private final PlatformAccess access;
    private final PlatformTasks tasks;
    private final PlatformDatasets datasets;
    private final PlatformQueries queries;
    private final PlatformProperties properties;
    private final ObjectMapper json;
    private final int minSamples,maxRows;
    private static final List<String> HEADER=List.of("STATION","DATE","NAME","TEMP","PRCP","HUMIDITY","MAX","MIN","FRSHTT","country","province","city","district");
    private static final String UPSERT="""
      INSERT INTO platform_raw_observation(dataset_id,station_code,observation_date,station_name,temp_f,precip_in,humidity_ratio,max_temp_f,min_temp_f,weather_flags,country,province,city,district)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE station_name=VALUES(station_name),temp_f=VALUES(temp_f),precip_in=VALUES(precip_in),humidity_ratio=VALUES(humidity_ratio),max_temp_f=VALUES(max_temp_f),min_temp_f=VALUES(min_temp_f),weather_flags=VALUES(weather_flags),country=VALUES(country),province=VALUES(province),city=VALUES(city),district=VALUES(district)
      """;
    public PlatformProcessor(PlatformStore store,PlatformAccess access,PlatformTasks tasks,PlatformDatasets datasets,PlatformQueries queries,PlatformProperties properties,ObjectMapper json,
            @Value("${app.min-baseline-samples}")int minSamples,@Value("${app.max-import-rows}")int maxRows){
        this.store=store;this.access=access;this.tasks=tasks;this.datasets=datasets;this.queries=queries;this.properties=properties;this.json=json;this.minSamples=minSamples;this.maxRows=maxRows;
    }
    @Transactional(rollbackFor=Exception.class,timeout=120)
    public void process(String id)throws Exception{
        var task=store.one("SELECT * FROM platform_task WHERE id=? AND status='RUNNING'",id);if(task==null)return;
        long dataset=PlatformStore.number(task,"dataset_id");String type=(String)task.get("task_type");
        var u=store.one("SELECT * FROM platform_user WHERE id=?",task.get("created_by"));
        if(u==null||!PlatformStore.flag(u,"enabled"))throw new IllegalArgumentException("Task submitter is no longer enabled");
        var actor=new PlatformUsers.Account(PlatformStore.number(u,"id"),(String)u.get("username"),(String)u.get("display_name"),(String)u.get("role"),true);
        access.dataset(actor,dataset,!type.equals("EXPORT"));
        // All platform data writes are serialized by the one platform worker, not by a long parent-row lock.
        // A parent-row X lock here would block staging a new upload through its dataset foreign key.
        checkInterrupted();
        switch(type){case "IMPORT"->importFile(id,dataset,(String)task.get("file_id"));case "ANALYZE"->analyze(id,dataset);case "EXPORT"->export(id,dataset,actor,task);default->throw new IllegalArgumentException("Unknown task type");}
    }
    private void importFile(String id,long dataset,String file)throws Exception{
        var record=store.one("SELECT * FROM platform_file WHERE id=? AND dataset_id=?",file,dataset);
        if(record==null)throw new IllegalArgumentException("Staged file not found");
        if(record.get("status").equals("IMPORTED")){tasks.success(id,PlatformStore.number(record,"imported_rows"),revision(dataset),null);return;}
        Path source=properties.upload(file);if(Files.size(source)>8*1024*1024)throw new IllegalArgumentException("Staged file exceeds 8 MiB");
        tasks.progress(id,"VALIDATING",0);int count=0;
        try(var reader=Files.newBufferedReader(source,StandardCharsets.UTF_8);CSVParser parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)){
            if(!parser.getHeaderNames().equals(HEADER))throw new IllegalArgumentException("CSV header must match the 13-column normalized template");
            List<Object[]> batch=new ArrayList<>(500);
            for(CSVRecord row:parser){
                checkInterrupted();if(++count>maxRows)throw new IllegalArgumentException("CSV exceeds max-import-rows="+maxRows);
                if(!row.isConsistent())throw new IllegalArgumentException("CSV record "+row.getRecordNumber()+" has an unexpected column count");
                try{Object[] values=Observation.from(row.toMap()).parameters(),bound=new Object[values.length+1];bound[0]=dataset;System.arraycopy(values,0,bound,1,values.length);batch.add(bound);}
                catch(RuntimeException e){throw new IllegalArgumentException("CSV record "+row.getRecordNumber()+": "+e.getMessage());}
                if(batch.size()==500){store.jdbc.batchUpdate(UPSERT,batch);batch.clear();tasks.progress(id,"IMPORTING",count);}
            }
            if(!batch.isEmpty())store.jdbc.batchUpdate(UPSERT,batch);
        }
        if(count==0)throw new IllegalArgumentException("CSV contains no observations");
        datasets.refreshCounts(dataset);store.jdbc.update("UPDATE platform_dataset SET raw_revision=raw_revision+1 WHERE id=?",dataset);
        store.jdbc.update("UPDATE platform_file SET status='IMPORTED',imported_rows=? WHERE id=?",count,file);
        tasks.success(id,count,revision(dataset),null);
    }
    private void analyze(String id,long dataset)throws Exception{
        var d=store.one("SELECT * FROM platform_dataset WHERE id=?",dataset);long raw=PlatformStore.number(d,"raw_rows"),version=PlatformStore.number(d,"next_result_version"),revision=PlatformStore.number(d,"raw_revision");
        if(raw==0)throw new IllegalArgumentException("Import observations before analysis");
        Integer conflicts=store.jdbc.queryForObject("""
          SELECT COUNT(*) FROM (SELECT station_code,YEAR(observation_date) y FROM platform_raw_observation
          WHERE dataset_id=? AND observation_date>='1993-01-01' GROUP BY station_code,YEAR(observation_date)
          HAVING COUNT(DISTINCT country,province,city,district)>1) c
          """,Integer.class,dataset);
        if(conflicts!=null&&conflicts>0)throw new IllegalArgumentException("Station geography changes within a year; resolve the source metadata before analysis");
        tasks.progress(id,"CALCULATING",0);
        String sql=new ClassPathResource("sql/platform-rebuild.sql").getContentAsString(StandardCharsets.UTF_8)
            .replace("__DATASET__",Long.toString(dataset)).replace("__VERSION__",Long.toString(version)).replace("__MIN_SAMPLES__",Integer.toString(minSamples));
        store.jdbc.execute((ConnectionCallback<Void>)connection->{ScriptUtils.executeSqlScript(connection,new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8));return null;});
        checkInterrupted();long rows=store.jdbc.queryForObject("SELECT COUNT(*) FROM platform_daily_indicator WHERE dataset_id=? AND result_version=?",Long.class,dataset,version);
        long missing=store.jdbc.queryForObject("""
          SELECT COUNT(*) FROM platform_raw_observation r LEFT JOIN platform_station_threshold t
          ON t.dataset_id=r.dataset_id AND t.station_code=r.station_code AND t.result_version=?
          WHERE r.dataset_id=? AND r.observation_date>='1993-01-01' AND t.station_code IS NULL
          """,Long.class,version,dataset);
        tasks.progress(id,"PUBLISHING",rows);
        store.jdbc.update("INSERT INTO platform_analysis_run(dataset_id,result_version,input_revision,formula_version,task_id,raw_rows,analysis_rows,missing_threshold_rows) VALUES(?,?,?,?,?,?,?,?)",dataset,version,revision,"nearest-rank-v1-n"+minSamples,id,raw,rows,missing);
        store.jdbc.update("UPDATE platform_dataset SET active_result_version=?,next_result_version=?,updated_at=NOW(6) WHERE id=?",version,version+1,dataset);
        tasks.success(id,rows,revision,version);
    }
    private void export(String id,long dataset,PlatformUsers.Account actor,Map<String,Object> task)throws Exception{
        long version=PlatformStore.number(task,"result_version");Map<String,String> parameters=json.readValue((String)task.get("payload"),new TypeReference<>(){});
        tasks.progress(id,"EXPORTING",0);var rows=queries.exportRows(actor,dataset,parameters,version);
        String export=UUID.randomUUID().toString();Path pending=properties.export(export,true),target=properties.export(export,false);Files.createDirectories(pending.getParent());
        List<String> columns=rows.isEmpty()?List.of():new ArrayList<>(rows.get(0).keySet());List<String> header=new ArrayList<>(List.of("datasetId","resultVersion"));header.addAll(columns);
        try{
            try(var writer=Files.newBufferedWriter(pending,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);var printer=new CSVPrinter(writer,CSVFormat.DEFAULT.builder().setHeader(header.toArray(String[]::new)).get())){
                int count=0;for(var row:rows){checkInterrupted();List<Object> output=new ArrayList<>(List.of(dataset,version));for(String column:columns)output.add(csvCell(row.get(column)));printer.printRecord(output);if(++count%500==0)tasks.progress(id,"EXPORTING",count);}
            }
            if(Files.size(pending)>32*1024*1024)throw new IllegalArgumentException("Export exceeds the 32 MiB artifact limit");
            try{Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(pending,target);}
            store.jdbc.update("INSERT INTO platform_export(id,task_id,dataset_id,result_version,row_count,file_size,expires_at) VALUES(?,?,?,?,?,?,?)",export,id,dataset,version,rows.size(),Files.size(target),Timestamp.from(Instant.now().plusSeconds(7*86400)));
            var run=store.one("SELECT input_revision FROM platform_analysis_run WHERE dataset_id=? AND result_version=?",dataset,version);
            tasks.success(id,rows.size(),PlatformStore.number(run,"input_revision"),version);
        }catch(Exception e){Files.deleteIfExists(pending);Files.deleteIfExists(target);throw e;}
    }
    static Object csvCell(Object value){if(value instanceof String text&&!text.isEmpty()&&"=+-@\t\r".indexOf(text.charAt(0))>=0)return "'"+text;return value;}
    private long revision(long dataset){return store.jdbc.queryForObject("SELECT raw_revision FROM platform_dataset WHERE id=?",Long.class,dataset);}
    private static void checkInterrupted()throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException("Task interrupted");}
}
