package cn.zuoxu.climate.platform;

import org.slf4j.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Component
public class PlatformBootstrap {
    private static final Logger log=LoggerFactory.getLogger(PlatformBootstrap.class);
    private final PlatformStore store;private final PlatformUsers users;private final PlatformProperties properties;private final PlatformDatasets datasets;
    public PlatformBootstrap(PlatformStore store,PlatformUsers users,PlatformProperties properties,PlatformDatasets datasets){this.store=store;this.users=users;this.properties=properties;this.datasets=datasets;}
    @EventListener(ApplicationReadyEvent.class) @Order(0) @Transactional
    public void initialize(){
        var admin=store.one("SELECT id FROM platform_user WHERE username='admin'");
        if(admin==null){
            if(properties.bootstrapPassword.isBlank()){log.warn("Platform bootstrap skipped: configure CLIMATE_PLATFORM_ADMIN_PASSWORD using scripts/local_auth.py");return;}
            long id=users.createInternal("admin","平台管理员",properties.bootstrapPassword,"ADMIN");admin=Map.of("id",id);
        }
        if(store.jdbc.queryForObject("SELECT COUNT(*) FROM platform_dataset WHERE is_example=TRUE",Integer.class)>0)return;
        long count=store.jdbc.queryForObject("SELECT COUNT(*) FROM raw_observation",Long.class);
        if(count==0||count>200000){log.info("Example snapshot skipped: legacy observation count={}",count);return;}
        store.jdbc.queryForObject("SELECT id FROM analysis_state WHERE id=1 FOR UPDATE",Integer.class);
        long dataset=store.insert("INSERT INTO platform_dataset(name,description,owner_id,is_example,raw_revision) VALUES(?,?,?,TRUE,1)","真实气象样本 · 只读示例","旧查询服务的一次性只读快照；三个站点、非连续分析年份，不代表完整全球数据。",PlatformStore.number(admin,"id"));
        store.jdbc.update("INSERT INTO platform_dataset_write_guard(dataset_id) VALUES(?)",dataset);
        store.jdbc.update("INSERT INTO platform_raw_observation SELECT ?,r.* FROM raw_observation r",dataset);
        var state=store.one("SELECT * FROM analysis_state WHERE id=1");
        for(String table:PlatformDatasets.RESULTS)store.jdbc.update("INSERT INTO platform_"+table+" SELECT ?,1,t.* FROM "+table+" t",dataset);
        store.jdbc.update("INSERT INTO platform_analysis_run(dataset_id,result_version,input_revision,formula_version,raw_rows,analysis_rows,missing_threshold_rows) VALUES(?,1,1,'legacy-snapshot-nearest-rank-v1',?,?,?)",dataset,state.get("raw_rows"),state.get("analysis_rows"),state.get("missing_threshold_rows"));
        datasets.refreshCounts(dataset);store.jdbc.update("UPDATE platform_dataset SET active_result_version=1,next_result_version=2 WHERE id=?",dataset);
        store.audit(PlatformStore.number(admin,"id"),dataset,"BOOTSTRAP_EXAMPLE",Long.toString(dataset));
        log.info("Platform administrator and read-only example ready; credentials are never logged");
    }
}
