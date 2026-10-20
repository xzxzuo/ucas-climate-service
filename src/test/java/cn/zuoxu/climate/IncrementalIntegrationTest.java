package cn.zuoxu.climate;

import cn.zuoxu.climate.data.DatasetLoader;
import cn.zuoxu.climate.incremental.*;
import cn.zuoxu.climate.service.AnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CLIMATE_IT",matches="true")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.incremental.min-file-age-ms=0","app.incremental.max-files-per-run=20"})
class IncrementalIntegrationTest {
    private static final String HEADER="STATION,DATE,NAME,TEMP,PRCP,HUMIDITY,MAX,MIN,FRSHTT,country,province,city,district\n";
    private static final String NEW_ROW="00000000001,1993-01-03,A,30,12,0.1,100,0,0,中国,四川省,泸州市,江阳区\n";
    static final Path INBOX=createDirectory();
    @Autowired JdbcTemplate jdbc;
    @Autowired DatasetLoader loader;
    @Autowired AnalysisService analysis;
    @Autowired IncrementalService incremental;
    @Autowired IncrementalJobStore jobs;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) { registry.add("app.incremental.directory",INBOX::toString); }
    private static Path createDirectory() { try { return Files.createTempDirectory("climate-incremental-test-"); } catch(Exception e) { throw new IllegalStateException(e); } }

    @BeforeEach void prepare() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).endsWith("_test");
        try (var files=Files.list(INBOX)) { for (Path path:files.toList()) Files.delete(path); }
        for (String table:new String[]{"incremental_file_job","analysis_scope","region_year_stat","station_year_stat","region_daily_stat","daily_indicator","station_threshold","raw_observation"}) jdbc.update("DELETE FROM "+table);
        loader.load(Path.of("src/test/resources/fixture.csv"));
    }
    private void publish(String name,String body) throws Exception { Files.writeString(INBOX.resolve(name),HEADER+body); }
    private long revision() { return jdbc.queryForObject("SELECT revision FROM analysis_state",Long.class); }

    @Test void importsNewDateAndSkipsSameContentAcrossRenames() throws Exception {
        long before=revision();
        publish("new.csv",NEW_ROW);
        var first=incremental.runOnce("TEST",false);
        assertThat(first.error()).isNull();
        assertThat(first.files()).extracting(IncrementalFileProcessor.Result::status).containsExactly("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_observation",Integer.class)).isEqualTo(15);
        assertThat(revision()).isEqualTo(before+1);
        assertThat(jdbc.queryForObject("SELECT htd FROM daily_indicator WHERE station_code='00000000001' AND observation_date='1993-01-03'",BigDecimal.class)).isEqualByComparingTo("20");
        Files.move(INBOX.resolve("new.csv"),INBOX.resolve("renamed.csv"));
        assertThat(incremental.runOnce("TEST",false).files()).extracting(IncrementalFileProcessor.Result::status).containsExactly("SKIPPED");
        assertThat(revision()).isEqualTo(before+1);
        assertThat(jobs.latest(10,0)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_scope",Integer.class)).isZero();
    }

    @Test void baselineCorrectionRecomputesAllHistoryForThatStation() throws Exception {
        var untouched=jdbc.queryForMap("SELECT * FROM daily_indicator WHERE station_code='00000000002'");
        publish("baseline-correction.csv","00000000001,1973-01-02,A,100,10,0.8,20,0,0,中国,四川省,泸州市,江阳区\n");
        assertThat(incremental.runOnce("TEST",false).files().get(0).status()).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT temp90 FROM station_threshold WHERE station_code='00000000001'",BigDecimal.class)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT htd FROM daily_indicator WHERE station_code='00000000001' AND observation_date='1993-01-01'",BigDecimal.class)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForMap("SELECT * FROM daily_indicator WHERE station_code='00000000002'")).isEqualTo(untouched);
        var partial=jdbc.queryForList("SELECT * FROM region_daily_stat ORDER BY observation_date,region_level,country,province,city,district,metric");
        var partialYear=jdbc.queryForList("SELECT * FROM region_year_stat ORDER BY observation_year,region_level,country,province,city,district,metric");
        analysis.rebuild();
        assertThat(jdbc.queryForList("SELECT * FROM region_daily_stat ORDER BY observation_date,region_level,country,province,city,district,metric")).isEqualTo(partial);
        assertThat(jdbc.queryForList("SELECT * FROM region_year_stat ORDER BY observation_year,region_level,country,province,city,district,metric")).isEqualTo(partialYear);
    }

    @Test void invalidCsvRollsBackAlreadyFlushedBatchesAndAuditsFailure() throws Exception {
        long before=revision();
        StringBuilder body=new StringBuilder();
        for (int index=0;index<501;index++) body.append(NEW_ROW);
        body.append("INVALID,1993-01-04,A,30,12,0.1,100,0,0,中国,四川省,泸州市,江阳区\n");
        publish("invalid.csv",body.toString());
        assertThat(incremental.runOnce("TEST",false).files().get(0).status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_observation",Integer.class)).isEqualTo(14);
        assertThat(revision()).isEqualTo(before);
        assertThat(jobs.latest(10,0).get(0).get("status")).isEqualTo("FAILED");
        assertThat(incremental.runOnce("TEST",false).files().get(0).status()).isEqualTo("DEFERRED");
        assertThat(((Number)jobs.latest(10,0).get(0).get("attempts")).intValue()).isEqualTo(1);
        assertThat(incremental.runOnce("TEST",true).files().get(0).status()).isEqualTo("FAILED");
        assertThat(((Number)jobs.latest(10,0).get(0).get("attempts")).intValue()).isEqualTo(2);
        incremental.runOnce("TEST",true);
        jdbc.update("UPDATE incremental_file_job SET next_retry_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))");
        assertThat(incremental.runOnce("TEST",false).files().get(0).status()).isEqualTo("DEFERRED");
        assertThat(((Number)jobs.latest(10,0).get(0).get("attempts")).intValue()).isEqualTo(3);
    }

    @Test void calculationFailureRollsBackRawObservationAndRevision() throws Exception {
        long before=revision();
        publish("conflict.csv",NEW_ROW.replace("泸州市","另一城市"));
        assertThat(incremental.runOnce("TEST",false).files().get(0).status()).isEqualTo("FAILED");
        assertThat(revision()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_observation",Integer.class)).isEqualTo(14);
    }

    @Test void changedContentAtSameFilenameCreatesANewJobAndUpserts() throws Exception {
        publish("correction.csv",NEW_ROW);
        incremental.runOnce("TEST",false);
        publish("correction.csv",NEW_ROW.replace(",30,12,",",40,12,"));
        assertThat(incremental.runOnce("TEST",false).files().get(0).status()).isEqualTo("SUCCESS");
        assertThat(jobs.latest(10,0)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_observation",Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT htd FROM daily_indicator WHERE station_code='00000000001' AND observation_date='1993-01-03'",BigDecimal.class)).isEqualByComparingTo("30");
    }
}
