package cn.zuoxu.climate;

import cn.zuoxu.climate.platform.PlatformDatasets;
import cn.zuoxu.climate.platform.PlatformUsers;
import cn.zuoxu.climate.platform.PlatformTasks;
import cn.zuoxu.climate.platform.PlatformWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfEnvironmentVariable(named="CLIMATE_IT",matches="true")
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "app.platform.worker-enabled=true","app.platform.poll-delay-ms=100",
    "app.platform.inbox-enabled=true","app.platform.inbox-min-age-ms=0","app.platform.inbox-delay-ms=200"
})
class PlatformSchedulingIntegrationTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry){registry.add("app.platform.directory",()->storage.toString());}
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformDatasets datasets;
    @Autowired PlatformWorker worker;
    @MockitoSpyBean PlatformTasks tasks;

    @Test void realDispatcherRunsTwoDatasetCalculationsAtOnce()throws Exception{
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).endsWith("_test");
        long admin=jdbc.queryForObject("SELECT id FROM platform_user WHERE username='admin'",Long.class);
        var actor=new PlatformUsers.Account(admin,"admin","测试管理员","ADMIN",true);
        long a=((Number)datasets.create(actor,"并行定时器 A "+UUID.randomUUID(),"").get("id")).longValue();
        long b=((Number)datasets.create(actor,"并行定时器 B "+UUID.randomUUID(),"").get("id")).longValue();
        byte[] fixture=Files.readAllBytes(Path.of("src/test/resources/fixture.csv"));
        datasets.upload(actor,a,new MockMultipartFile("file","a.csv","text/csv",fixture));
        datasets.upload(actor,b,new MockMultipartFile("file","b.csv","text/csv",fixture));
        await().atMost(Duration.ofSeconds(15)).until(()->jdbc.queryForObject(
            "SELECT COUNT(*) FROM platform_task WHERE dataset_id IN (?,?) AND status='SUCCESS'",Integer.class,a,b)==2);
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);Set<String> threads=ConcurrentHashMap.newKeySet();
        doAnswer(call->{
            Object result=call.callRealMethod();
            if("CALCULATING".equals(call.getArgument(1))){
                threads.add(Thread.currentThread().getName());entered.countDown();
                if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Parallel scheduler test timed out");
            }
            return result;
        }).when(tasks).progress(anyString(),anyString(),anyLong());
        String first=(String)tasks.enqueue(actor,a,"ANALYZE",null,Map.of(),null,null).get("id");
        String second=(String)tasks.enqueue(actor,b,"ANALYZE",null,Map.of(),null,null).get("id");
        try{
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(threads).hasSize(2).allMatch(name->name.startsWith("climate-write-"));
            assertThat(worker.activeTaskCount()).isEqualTo(2);
        }finally{release.countDown();}
        await().atMost(Duration.ofSeconds(15)).until(()->worker.activeTaskCount()==0);
        assertThat(jdbc.queryForObject("SELECT status FROM platform_task WHERE id=?",String.class,first)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT status FROM platform_task WHERE id=?",String.class,second)).isEqualTo("SUCCESS");
    }

    @Test void realSpringTimersDiscoverImportAndDeduplicateWithoutManualDispatch()throws Exception{
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).endsWith("_test");
        long admin=jdbc.queryForObject("SELECT id FROM platform_user WHERE username='admin'",Long.class);
        var actor=new PlatformUsers.Account(admin,"admin","测试管理员","ADMIN",true);
        long dataset=((Number)datasets.create(actor,"真实定时器 "+UUID.randomUUID(),"隔离的自动调度测试").get("id")).longValue();
        Path directory=storage.resolve("inbox").resolve(Long.toString(dataset));Path pending=directory.resolve("scheduled.part");
        Files.copy(Path.of("src/test/resources/fixture.csv"),pending);Files.move(pending,directory.resolve("scheduled.csv"),StandardCopyOption.ATOMIC_MOVE);
        long deadline=System.nanoTime()+15_000_000_000L;
        while(System.nanoTime()<deadline&&jdbc.queryForObject("SELECT COUNT(*) FROM platform_task WHERE dataset_id=? AND status='SUCCESS'",Integer.class,dataset)==0)Thread.sleep(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_raw_observation WHERE dataset_id=?",Integer.class,dataset)).isEqualTo(14);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_audit WHERE dataset_id=? AND action='AUTO_IMPORT_AS_OWNER'",Integer.class,dataset)).isEqualTo(1);
        Thread.sleep(600);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_task WHERE dataset_id=?",Integer.class,dataset)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT raw_revision FROM platform_dataset WHERE id=?",Long.class,dataset)).isEqualTo(1);
    }
}
