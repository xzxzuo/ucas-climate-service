package cn.zuoxu.climate;

import cn.zuoxu.climate.bootstrap.SampleLoader;
import cn.zuoxu.climate.bootstrap.SampleAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

/** The retained V1 sample initializer is tested without resurrecting a legacy HTTP application. */
@EnabledIfEnvironmentVariable(named="CLIMATE_IT",matches="true")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class SampleBootstrapIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SampleLoader loader;
    @Autowired SampleAnalysis analysis;
    @Autowired PlatformTransactionManager transactions;
    final Path fixture=Path.of("src/test/resources/fixture.csv");

    @BeforeEach void prepare()throws Exception{
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).endsWith("_test");
        for(String table:new String[]{"region_year_stat","station_year_stat","region_daily_stat","daily_indicator","station_threshold","raw_observation"})jdbc.update("DELETE FROM "+table);
        loader.load(fixture);
    }
    @Test void computesThresholdsMagnitudesAndWeatherFlags(){
        var threshold=jdbc.queryForMap("SELECT * FROM station_threshold WHERE station_code='00000000001'");
        assertThat(threshold.get("temp10").toString()).isEqualTo("0.0000");assertThat(threshold.get("temp90").toString()).isEqualTo("10.0000");
        var daily=jdbc.queryForMap("SELECT * FROM daily_indicator WHERE station_code='00000000001' AND observation_date='1993-01-01'");
        assertThat(daily.get("htd").toString()).isEqualTo("10.0000000000");assertThat(daily.get("htd1").toString()).isEqualTo("5.0000000000");
        assertThat(((Number)daily.get("rain")).intValue()).isEqualTo(1);assertThat(((Number)daily.get("fog")).intValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT missing_threshold_rows FROM analysis_state",Long.class)).isEqualTo(1);
    }
    @Test void computesEvenMedianAndKeepsDaysSeparate(){
        var first=jdbc.queryForMap("SELECT * FROM region_daily_stat WHERE observation_date='1993-01-01' AND region_level='COUNTRY' AND metric='HTD'");
        assertThat(first.get("median_value").toString()).isEqualTo("25.0000000000");assertThat(((Number)first.get("valid_station_count")).intValue()).isEqualTo(4);
        var next=jdbc.queryForMap("SELECT * FROM region_daily_stat WHERE observation_date='1993-01-02' AND region_level='COUNTRY' AND metric='HTD'");
        assertThat((BigDecimal)next.get("median_value")).isEqualByComparingTo("0");assertThat(((Number)next.get("valid_station_count")).intValue()).isEqualTo(1);
    }
    @Test void computesOddMedian()throws Exception{
        jdbc.update("DELETE FROM raw_observation WHERE station_code='00000000004' AND observation_date='1993-01-01'");analysis.rebuild();
        assertThat(jdbc.queryForObject("SELECT median_value FROM region_daily_stat WHERE observation_date='1993-01-01' AND region_level='COUNTRY' AND metric='HTD'",BigDecimal.class)).isEqualByComparingTo("20");
    }
    @Test void excludesMissingValuesFromValidDays(){
        var annual=jdbc.queryForMap("SELECT * FROM station_year_stat WHERE station_code='00000000001' AND metric='HR'");
        assertThat(((Number)annual.get("valid_days")).intValue()).isEqualTo(1);assertThat(((Number)annual.get("nonzero_days")).intValue()).isEqualTo(1);
    }
    @Test void repeatedImportIsIdempotent()throws Exception{
        long revision=jdbc.queryForObject("SELECT revision FROM analysis_state",Long.class);loader.load(fixture);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_observation",Integer.class)).isEqualTo(14);
        assertThat(jdbc.queryForObject("SELECT revision FROM analysis_state",Long.class)).isEqualTo(revision+1);
    }
    @Test void conflictingMetadataPreservesCalculatedResults(){
        long revision=jdbc.queryForObject("SELECT revision FROM analysis_state",Long.class);
        jdbc.update("UPDATE raw_observation SET city='另一城市' WHERE station_code='00000000001' AND observation_date='1993-01-02'");
        assertThatThrownBy(()->analysis.rebuild()).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_indicator",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT revision FROM analysis_state",Long.class)).isEqualTo(revision);
    }
    @Test void outerFailureRollsBackInputResultsAndRevision(){
        long revision=jdbc.queryForObject("SELECT revision FROM analysis_state",Long.class);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            jdbc.update("UPDATE raw_observation SET temp_f=99 WHERE station_code='00000000001' AND observation_date='1993-01-01'");
            try{analysis.rebuild();}catch(Exception e){throw new RuntimeException(e);}throw new IllegalStateException("simulated failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT revision FROM analysis_state",Long.class)).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT temp_f FROM raw_observation WHERE station_code='00000000001' AND observation_date='1993-01-01'",BigDecimal.class)).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("SELECT htd FROM daily_indicator WHERE station_code='00000000001' AND observation_date='1993-01-01'",BigDecimal.class)).isEqualByComparingTo("10");
    }
}
