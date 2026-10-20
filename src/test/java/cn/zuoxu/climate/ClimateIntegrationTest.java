package cn.zuoxu.climate;

import cn.zuoxu.climate.data.DatasetLoader;
import cn.zuoxu.climate.service.AnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.*;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CLIMATE_IT",matches="true")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClimateIntegrationTest {
    private static final String READER="test-reader-key-at-least-32-characters";
    private static final String ADMIN="test-admin-key-at-least-32-characters";
    @Autowired JdbcTemplate jdbc;
    @Autowired DatasetLoader loader;
    @Autowired AnalysisService analysis;
    @Autowired TestRestTemplate http;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactions;
    private final Path fixture=Path.of("src/test/resources/fixture.csv");
    @BeforeEach void prepare() throws Exception {
        http.getRestTemplate().setInterceptors(List.of((request,body,execution)->{
            if (!request.getHeaders().containsKey("X-API-Key")) request.getHeaders().set("X-API-Key",READER);
            return execution.execute(request,body);
        }));
        String database=jdbc.queryForObject("SELECT DATABASE()",String.class);
        assertThat(database).endsWith("_test");
        for(String t:new String[]{"region_year_stat","station_year_stat","region_daily_stat","daily_indicator","station_threshold","raw_observation"}) jdbc.update("DELETE FROM "+t);
        loader.load(fixture);
    }
    @Test void computesThresholdsMagnitudesAndWeatherFlags() {
        var t=jdbc.queryForMap("SELECT * FROM station_threshold WHERE station_code='00000000001'");
        assertThat(t.get("temp10").toString()).isEqualTo("0.0000");assertThat(t.get("temp90").toString()).isEqualTo("10.0000");
        var d=jdbc.queryForMap("SELECT * FROM daily_indicator WHERE station_code='00000000001' AND observation_date='1993-01-01'");
        assertThat(d.get("htd").toString()).isEqualTo("10.0000000000");assertThat(d.get("htd1").toString()).isEqualTo("5.0000000000");
        assertThat(((Number)d.get("rain")).intValue()).isEqualTo(1);assertThat(((Number)d.get("fog")).intValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT missing_threshold_rows FROM analysis_state",Long.class)).isEqualTo(1);
    }
    @Test void computesEvenMedianAndKeepsDailyGroupsSeparate() {
        var a=jdbc.queryForMap("SELECT * FROM region_daily_stat WHERE observation_date='1993-01-01' AND region_level='COUNTRY' AND metric='HTD'");
        assertThat(a.get("median_value").toString()).isEqualTo("25.0000000000");assertThat(((Number)a.get("valid_station_count")).intValue()).isEqualTo(4);
        var b=jdbc.queryForMap("SELECT * FROM region_daily_stat WHERE observation_date='1993-01-02' AND region_level='COUNTRY' AND metric='HTD'");
        assertThat((BigDecimal)b.get("median_value")).isEqualByComparingTo(BigDecimal.ZERO);assertThat(((Number)b.get("valid_station_count")).intValue()).isEqualTo(1);
    }
    @Test void computesOddMedian() throws Exception {
        jdbc.update("DELETE FROM raw_observation WHERE station_code='00000000004' AND observation_date='1993-01-01'");
        analysis.rebuild();
        assertThat(jdbc.queryForObject("SELECT median_value FROM region_daily_stat WHERE observation_date='1993-01-01' AND region_level='COUNTRY' AND metric='HTD'",BigDecimal.class)).isEqualByComparingTo("20");
    }
    @Test void excludesMissingValuesFromValidDays() {
        var s=jdbc.queryForMap("SELECT * FROM station_year_stat WHERE station_code='00000000001' AND metric='HR'");
        assertThat(((Number)s.get("valid_days")).intValue()).isEqualTo(1);assertThat(((Number)s.get("nonzero_days")).intValue()).isEqualTo(1);
    }
    @Test void repeatedImportIsIdempotentAndAdvancesRevision() throws Exception {
        Long before=jdbc.queryForObject("SELECT revision FROM analysis_state",Long.class);
        loader.load(fixture);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_observation",Integer.class)).isEqualTo(14);
        assertThat(jdbc.queryForObject("SELECT revision FROM analysis_state",Long.class)).isEqualTo(before+1);
    }
    @Test void apiValidatesParametersAndProtectsAdmin() {
        assertThat(http.getForEntity("/api/stations/00000000001/indicators?from=1993-02-01&to=1993-01-01",Map.class).getStatusCode().value()).isEqualTo(400);
        assertThat(http.getForEntity("/api/stations/00000000001/indicators?from=1993-01-01&to=1993-02-01&limit=999",Map.class).getStatusCode().value()).isEqualTo(400);
        assertThat(http.getForEntity("/api/aggregates/annual?year=1993&level=COUNTRY&metric=BAD",Map.class).getStatusCode().value()).isEqualTo(400);
        assertThat(http.getForEntity("/api/admin/cache",Map.class).getStatusCode().value()).isEqualTo(403);
        assertThat(http.getForEntity("/api/stations/00000000999/thresholds",Map.class).getStatusCode().value()).isEqualTo(404);
        assertThat(http.getForEntity("/api/no-such-resource",Map.class).getStatusCode().value()).isEqualTo(404);
    }
    @Test void apiKeysAuthenticateAndAuthorizeAllBusinessEndpoints() {
        var anonymous=new TestRestTemplate();
        String root=http.getRootUri();
        for (String path:new String[]{"/api/meta","/api/stations","/api/exports/thresholds.csv","/api/admin/incremental/status"}) {
            var response=anonymous.getForEntity(root+path,Map.class);
            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        }
        assertThat(anonymous.getForEntity(root+"/actuator/health",Map.class).getStatusCode().value()).isEqualTo(200);
        assertThat(anonymous.getForEntity(root+"/",String.class).getStatusCode().value()).isEqualTo(200);
        var invalid=new HttpHeaders();invalid.set("X-API-Key","invalid-key");
        assertThat(http.exchange("/api/meta",HttpMethod.GET,new HttpEntity<>(invalid),Map.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.postForEntity("/api/admin/incremental/run",null,Map.class).getStatusCode().value()).isEqualTo(403);
        var admin=new HttpHeaders();admin.set("X-API-Key",ADMIN);
        assertThat(http.exchange("/api/admin/incremental/status",HttpMethod.GET,new HttpEntity<>(admin),Map.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.exchange("/api/meta",HttpMethod.GET,new HttpEntity<>(admin),Map.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/api/meta?apiKey="+ADMIN,Map.class).getStatusCode().value()).isEqualTo(200);
        // A query-string key alone is deliberately not an authentication mechanism.
        assertThat(anonymous.getForEntity(root+"/api/meta?apiKey="+ADMIN,Map.class).getStatusCode().value()).isEqualTo(401);
    }
    @Test void dailyRegionApiReturnsMeanAndMedian() {
        var response=http.getForEntity("/api/aggregates/daily?date=1993-01-01&level=COUNTRY&metric=HTD",List.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<?,?> row=(Map<?,?>)response.getBody().get(0);
        assertThat(((Number)row.get("medianValue")).doubleValue()).isEqualTo(25);
        assertThat(((Number)row.get("maxValue")).doubleValue()).isEqualTo(40);
    }
    @Test void cacheHitsAndRebuildUsesNewNamespace() throws Exception {
        String url="/api/stations/00000000001/thresholds";
        assertThat(http.getForEntity(url,Map.class).getHeaders().getFirst("X-Cache")).isEqualTo("MISS");
        assertThat(http.getForEntity(url,Map.class).getHeaders().getFirst("X-Cache")).isEqualTo("HIT");
        analysis.rebuild();
        assertThat(http.getForEntity(url,Map.class).getHeaders().getFirst("X-Cache")).isEqualTo("MISS");
    }
    @Test void conflictingMetadataDoesNotReplacePublishedResults() {
        Long revision=jdbc.queryForObject("SELECT revision FROM analysis_state",Long.class);
        jdbc.update("UPDATE raw_observation SET city='另一城市' WHERE station_code='00000000001' AND observation_date='1993-01-02'");
        assertThatThrownBy(()->analysis.rebuild()).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_indicator",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT revision FROM analysis_state",Long.class)).isEqualTo(revision);
    }
    @Test void exceptionAfterCalculationRollsBackRawResultsAndRevision() {
        Long before=jdbc.queryForObject("SELECT revision FROM analysis_state",Long.class);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            jdbc.update("UPDATE raw_observation SET temp_f=99 WHERE station_code='00000000001' AND observation_date='1993-01-01'");
            try { analysis.rebuild(); } catch(Exception e) { throw new RuntimeException(e); }
            throw new IllegalStateException("simulated failure before outer transaction commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT revision FROM analysis_state",Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT temp_f FROM raw_observation WHERE station_code='00000000001' AND observation_date='1993-01-01'",BigDecimal.class)).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("SELECT htd FROM daily_indicator WHERE station_code='00000000001' AND observation_date='1993-01-01'",BigDecimal.class)).isEqualByComparingTo("10");
    }
    @Test void exportIsCsvAndUsesSameDailyData() {
        var r=http.getForEntity("/api/exports/daily.csv?station=00000000001&from=1993-01-01&to=1993-01-03",String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);assertThat(r.getHeaders().getContentType().toString()).contains("text/csv");
        assertThat(r.getBody()).contains("1993-01-01","1993-01-02","stationCode");
    }
    @Test void exportsAllFourAnalysisStages() {
        for(String path:new String[]{"/api/exports/thresholds.csv","/api/exports/station-annual.csv?station=00000000001&year=1993",
            "/api/exports/region-daily.csv?date=1993-01-01&level=COUNTRY&metric=HTD",
            "/api/exports/region-annual.csv?year=1993&level=COUNTRY&metric=RAIN"}) {
            var response=http.getForEntity(path,String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getHeaders().getContentType().toString()).contains("text/csv");
            assertThat(response.getBody()).contains("\r\n");
        }
    }
}
