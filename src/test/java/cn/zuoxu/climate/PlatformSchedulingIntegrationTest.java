package cn.zuoxu.climate;

import cn.zuoxu.climate.platform.PlatformDatasets;
import cn.zuoxu.climate.platform.PlatformUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

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
