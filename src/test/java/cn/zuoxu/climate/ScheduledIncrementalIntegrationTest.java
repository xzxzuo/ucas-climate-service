package cn.zuoxu.climate;

import cn.zuoxu.climate.data.DatasetLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CLIMATE_IT",matches="true")
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "app.incremental.enabled=true","app.incremental.min-file-age-ms=0",
    "app.incremental.initial-delay-ms=1000","app.incremental.fixed-delay-ms=200"
})
class ScheduledIncrementalIntegrationTest {
    private static final Path INBOX=createDirectory();
    @Autowired JdbcTemplate jdbc;
    @Autowired DatasetLoader loader;
    static Path createDirectory() { try { return Files.createTempDirectory("climate-scheduled-test-"); } catch(Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) { registry.add("app.incremental.directory",INBOX::toString); }
    @Test void springScheduledActuallyImportsAndDoesNotReapplySuccessfulFile() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).endsWith("_test");
        for (String table:new String[]{"incremental_file_job","analysis_scope","region_year_stat","station_year_stat","region_daily_stat","daily_indicator","station_threshold","raw_observation"}) jdbc.update("DELETE FROM "+table);
        loader.load(Path.of("src/test/resources/fixture.csv"));
        long before=jdbc.queryForObject("SELECT revision FROM analysis_state",Long.class);
        Path part=INBOX.resolve("scheduled.part");
        Files.writeString(part,"STATION,DATE,NAME,TEMP,PRCP,HUMIDITY,MAX,MIN,FRSHTT,country,province,city,district\n00000000001,1993-01-03,A,30,12,0.1,100,0,0,中国,四川省,泸州市,江阳区\n");
        Files.move(part,INBOX.resolve("scheduled.csv"),StandardCopyOption.ATOMIC_MOVE);
        long deadline=System.nanoTime()+10_000_000_000L;
        while (System.nanoTime()<deadline && jdbc.queryForObject("SELECT COUNT(*) FROM incremental_file_job WHERE status='SUCCESS'",Integer.class)==0) Thread.sleep(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incremental_file_job WHERE status='SUCCESS'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_observation",Integer.class)).isEqualTo(15);
        Thread.sleep(600);
        assertThat(jdbc.queryForObject("SELECT revision FROM analysis_state",Long.class)).isEqualTo(before+1);
    }
}
