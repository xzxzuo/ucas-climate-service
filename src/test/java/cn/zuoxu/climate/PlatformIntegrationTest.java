package cn.zuoxu.climate;

import cn.zuoxu.climate.data.DatasetLoader;
import cn.zuoxu.climate.platform.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CLIMATE_IT",matches="true")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformIntegrationTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("app.platform.directory",()->storage.toString());registry.add("app.platform.inbox-min-age-ms",()->"0");}
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformBootstrap bootstrap;
    @Autowired PlatformWorker worker;
    @Autowired PlatformProcessor processor;
    @Autowired PlatformInbox inbox;
    @Autowired DatasetLoader loader;
    final ObjectMapper json=new ObjectMapper();
    Client admin,alice,bob,viewer;
    byte[] fixture;

    @BeforeEach void setup()throws Exception{
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).endsWith("_test");
        for(String table:List.of("platform_export","platform_analysis_run","platform_audit","platform_task","platform_file","platform_member","platform_region_year_stat","platform_station_year_stat","platform_region_daily_stat","platform_daily_indicator","platform_station_threshold","platform_raw_observation","platform_dataset","platform_user"))jdbc.update("DELETE FROM "+table);
        for(String table:List.of("region_year_stat","station_year_stat","region_daily_stat","daily_indicator","station_threshold","raw_observation"))jdbc.update("DELETE FROM "+table);
        loader.load(Path.of("src/test/resources/fixture.csv"));bootstrap.initialize();fixture=Files.readAllBytes(Path.of("src/test/resources/fixture.csv"));
        admin=new Client();admin.login("admin","test-platform-admin-password-2026");
        for(String name:List.of("alice","bob","viewer"))assertThat(admin.post("/admin/users",Map.of("username",name,"displayName",name,"password","test-user-password-2026","role",name.equals("viewer")?"VIEWER":"RESEARCHER")).statusCode()).isEqualTo(201);
        alice=new Client();alice.login("alice","test-user-password-2026");bob=new Client();bob.login("bob","test-user-password-2026");viewer=new Client();viewer.login("viewer","test-user-password-2026");
    }
    long create(Client client,String name)throws Exception{return node(client.post("/datasets",Map.of("name",name))).path("id").asLong();}
    long example(){return jdbc.queryForObject("SELECT id FROM platform_dataset WHERE is_example=TRUE",Long.class);}
    long cloneExample(Client client)throws Exception{return node(client.post("/datasets/"+example()+"/clone",Map.of("name","独立测试数据集"))).path("id").asLong();}
    String task(HttpResponse<String> response)throws Exception{assertThat(response.statusCode()).isEqualTo(202);return node(response).path("id").asText();}
    String upload(Client client,long dataset,byte[] data)throws Exception{var response=client.upload(dataset,"observations.csv",data);assertThat(response.statusCode()).isEqualTo(202);return node(response).path("task").path("id").asText();}
    JsonNode node(HttpResponse<String> response)throws Exception{return json.readTree(response.body());}
    String status(String id){return jdbc.queryForObject("SELECT status FROM platform_task WHERE id=?",String.class,id);}
    void run(){assertThat(worker.runOnce()).isTrue();}

    @Test void cookieSessionCsrfAndLegacyCredentialsAreSeparated()throws Exception{
        Client anonymous=new Client();assertThat(anonymous.get("/me").statusCode()).isEqualTo(401);
        assertThat(alice.request("/datasets","POST","{}".getBytes(),"application/json",false).statusCode()).isEqualTo(403);
        assertThat(alice.get("/me").body()).contains("alice").doesNotContain("password","hash");
        assertThat(alice.client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/meta")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(alice.post("/logout",null).statusCode()).isEqualTo(200);assertThat(alice.get("/me").statusCode()).isEqualTo(401);
    }
    @Test void datasetOwnershipSharingAndRevocationProtectEveryEntryPoint()throws Exception{
        long id=create(alice,"私人研究");assertThat(bob.get("/datasets/"+id).statusCode()).isEqualTo(404);
        assertThat(bob.post("/datasets/"+id+"/analyze",null).statusCode()).isEqualTo(404);
        assertThat(alice.post("/datasets/"+id+"/members",Map.of("username","bob","permission","READ")).statusCode()).isEqualTo(200);
        assertThat(bob.get("/datasets/"+id).statusCode()).isEqualTo(200);assertThat(bob.post("/datasets/"+id+"/analyze",null).statusCode()).isEqualTo(403);
        alice.post("/datasets/"+id+"/members",Map.of("username","bob","permission","WRITE"));String job=task(bob.post("/datasets/"+id+"/analyze",null));
        alice.post("/datasets/"+id+"/members",Map.of("username","bob","permission","REMOVE"));assertThat(bob.get("/tasks/"+job).statusCode()).isEqualTo(404);
        run();assertThat(status(job)).isEqualTo("FAILED");assertThat(admin.get("/datasets/"+id).statusCode()).isEqualTo(200);
    }
    @Test void sameStationDateAndSameFileAreIsolatedAcrossDatasets()throws Exception{
        long a=create(alice,"A"),b=create(alice,"B");String ia=upload(alice,a,fixture),ib=upload(alice,b,fixture);run();run();assertThat(status(ia)).isEqualTo("SUCCESS");assertThat(status(ib)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_raw_observation WHERE dataset_id=?",Integer.class,a)).isEqualTo(14);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_raw_observation WHERE dataset_id=?",Integer.class,b)).isEqualTo(14);
        byte[] updated=new String(fixture,StandardCharsets.UTF_8).replace(",A,20,12,",",A,99,12,").getBytes(StandardCharsets.UTF_8);upload(alice,b,updated);run();
        task(alice.post("/datasets/"+a+"/analyze",null));run();task(alice.post("/datasets/"+b+"/analyze",null));run();
        String query="/analysis?kind=DAILY&station=00000000001&from=1993-01-01&to=1993-01-02";
        assertThat(node(alice.get("/datasets/"+a+query)).path("items").get(0).path("htd").asDouble()).isEqualTo(10);
        assertThat(node(alice.get("/datasets/"+b+query)).path("items").get(0).path("htd").asDouble()).isEqualTo(89);
    }
    @Test void duplicateUploadReturnsSameTaskAndDoesNotAdvanceRevision()throws Exception{
        long d=create(alice,"去重");String id=upload(alice,d,fixture);run();var duplicate=alice.upload(d,"renamed.csv",fixture);
        assertThat(node(duplicate).path("duplicate").asBoolean()).isTrue();assertThat(node(duplicate).path("task").path("id").asText()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT raw_revision FROM platform_dataset WHERE id=?",Long.class,d)).isEqualTo(1L);assertThat(worker.runOnce()).isFalse();
    }
    @Test void invalidRecordAfterBatchFlushRollsBackAllRowsAndRetryRemainsExplicit()throws Exception{
        long d=create(alice,"回滚");StringBuilder csv=new StringBuilder(new String(fixture,StandardCharsets.UTF_8));
        for(int i=0;i<510;i++)csv.append("00000000001,1993-01-01,A,20,12,0.1,100,0,0,中国,四川省,泸州市,江阳区\n");
        csv.append("00000000001,1993-01-02,A,20,12,2.0,100,0,0,中国,四川省,泸州市,江阳区\n");
        String id=upload(alice,d,csv.toString().getBytes(StandardCharsets.UTF_8));run();assertThat(status(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_raw_observation WHERE dataset_id=?",Integer.class,d)).isZero();
        assertThat(jdbc.queryForObject("SELECT raw_revision FROM platform_dataset WHERE id=?",Long.class,d)).isZero();
        String retry=task(alice.post("/tasks/"+id+"/retry",null));assertThat(retry).isNotEqualTo(id);run();assertThat(status(retry)).isEqualTo("FAILED");
    }
    @Test void failedRecalculationKeepsPublishedVersionAndOldResults()throws Exception{
        long d=cloneExample(alice);String original=alice.get("/datasets/"+d+"/analysis?kind=THRESHOLDS&version=1").body();
        String csv="STATION,DATE,NAME,TEMP,PRCP,HUMIDITY,MAX,MIN,FRSHTT,country,province,city,district\n00000000001,1993-01-02,A,0,0,0.2,10,-10,0,中国,四川省,另一城市,江阳区\n";
        upload(alice,d,csv.getBytes(StandardCharsets.UTF_8));run();String analysis=task(alice.post("/datasets/"+d+"/analyze",null));run();
        assertThat(status(analysis)).isEqualTo("FAILED");assertThat(jdbc.queryForObject("SELECT active_result_version FROM platform_dataset WHERE id=?",Long.class,d)).isEqualTo(1L);
        assertThat(alice.get("/datasets/"+d+"/analysis?kind=THRESHOLDS&version=1").body()).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_analysis_run WHERE dataset_id=?",Integer.class,d)).isEqualTo(1);
    }
    @Test void formulaMatchesLegacyThresholdsMediansAndFrequency()throws Exception{
        long d=create(alice,"公式核对");upload(alice,d,fixture);run();String analysis=task(alice.post("/datasets/"+d+"/analyze",null));run();assertThat(status(analysis)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT temp90 FROM platform_station_threshold WHERE dataset_id=? AND station_code='00000000001'",java.math.BigDecimal.class,d)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT median_value FROM platform_region_daily_stat WHERE dataset_id=? AND observation_date='1993-01-01' AND region_level='COUNTRY' AND metric='HTD'",java.math.BigDecimal.class,d)).isEqualByComparingTo("25");
        assertThat(jdbc.queryForObject("SELECT valid_days FROM platform_station_year_stat WHERE dataset_id=? AND station_code='00000000001' AND metric='HR'",Integer.class,d)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT missing_threshold_rows FROM platform_analysis_run WHERE dataset_id=?",Long.class,d)).isEqualTo(1);
    }
    @Test void exportPinsOldVersionAndDownloadRechecksPermissionsAndExpiry()throws Exception{
        long d=cloneExample(alice);String export=task(alice.post("/datasets/"+d+"/exports",Map.of("kind","THRESHOLDS","version","1")));
        String analysis=task(alice.post("/datasets/"+d+"/analyze",null));jdbc.update("UPDATE platform_task SET status='RUNNING' WHERE id=?",analysis);processor.process(analysis);run();
        assertThat(jdbc.queryForObject("SELECT active_result_version FROM platform_dataset WHERE id=?",Long.class,d)).isEqualTo(2);
        var record=jdbc.queryForMap("SELECT * FROM platform_export WHERE task_id=?",export);String path="/exports/"+record.get("id")+"/download";
        assertThat(((Number)record.get("result_version")).longValue()).isEqualTo(1);assertThat(alice.get(path).body()).startsWith("datasetId,resultVersion,").contains(d+",1,");
        assertThat(bob.get(path).statusCode()).isEqualTo(404);alice.post("/datasets/"+d+"/members",Map.of("username","bob","permission","READ"));assertThat(bob.get(path).statusCode()).isEqualTo(200);
        alice.post("/datasets/"+d+"/members",Map.of("username","bob","permission","REMOVE"));assertThat(bob.get(path).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE platform_export SET expires_at=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE id=?",record.get("id"));assertThat(alice.get(path).statusCode()).isEqualTo(410);
    }
    @Test void readOnlyExampleViewerAndDisabledAccountCannotWrite()throws Exception{
        assertThat(alice.post("/datasets/"+example()+"/analyze",null).statusCode()).isEqualTo(403);
        assertThat(admin.post("/datasets/"+example()+"/analyze",null).statusCode()).isEqualTo(403);
        assertThat(viewer.post("/datasets",Map.of("name","forbidden")).statusCode()).isEqualTo(403);
        assertThat(alice.get("/admin/users").statusCode()).isEqualTo(403);
        long id=jdbc.queryForObject("SELECT id FROM platform_user WHERE username='alice'",Long.class);admin.patch("/admin/users/"+id+"/enabled",Map.of("enabled",false));assertThat(alice.get("/me").statusCode()).isEqualTo(401);
    }
    @Test void recoveryPreservesQueuedAndRequiresManualRetryForInterrupted()throws Exception{
        long d=cloneExample(alice);String job=task(alice.post("/datasets/"+d+"/analyze",null));jdbc.update("UPDATE platform_task SET status='RUNNING' WHERE id=?",job);worker.recover();assertThat(status(job)).isEqualTo("INTERRUPTED");
        String retry=task(alice.post("/tasks/"+job+"/retry",null));run();assertThat(status(retry)).isEqualTo("SUCCESS");assertThat(status(job)).isEqualTo("INTERRUPTED");
    }
    @Test void validationRejectsTraversalAndSqlLikeInputsAndWeakPasswords()throws Exception{
        long d=cloneExample(alice);assertThat(alice.upload(d,"../escape.csv",fixture).statusCode()).isEqualTo(400);
        assertThat(alice.get("/datasets/"+d+"/analysis?kind=DROP_TABLE").statusCode()).isEqualTo(400);
        assertThat(alice.get("/datasets/"+d+"/analysis?kind=DAILY&station=bad&from=x&to=y").statusCode()).isEqualTo(400);
        assertThat(admin.post("/admin/users",Map.of("username","weak","displayName","weak","password","short","role","RESEARCHER")).statusCode()).isEqualTo(400);
        assertThat(admin.post("/admin/users",Map.of("username","alice","displayName","again","password","test-user-password-2026","role","RESEARCHER")).statusCode()).isEqualTo(409);
    }
    @Test void scheduledInboxUsesSameDurableImportAndDeduplicationPath()throws Exception{
        long d=create(alice,"自动增量导入");Path file=storage.resolve("inbox").resolve(Long.toString(d)).resolve("batch.csv");Files.write(file,fixture);
        assertThat(inbox.scanOnce()).isEqualTo(1);run();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_raw_observation WHERE dataset_id=?",Integer.class,d)).isEqualTo(14);
        assertThat(inbox.scanOnce()).isZero();assertThat(worker.runOnce()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_audit WHERE dataset_id=? AND action='AUTO_IMPORT_AS_OWNER'",Integer.class,d)).isEqualTo(1);
    }
    @Test void inboxRotatesPastAlreadyProcessedFilesWithoutStarvingNewContent()throws Exception{
        long d=create(alice,"收件箱轮转");Path directory=storage.resolve("inbox").resolve(Long.toString(d));
        for(int i=0;i<100;i++)Files.write(directory.resolve(String.format("%03d.csv",i)),fixture);
        Files.write(directory.resolve("100.csv"),new String(fixture,StandardCharsets.UTF_8).replace(",A,20,12,",",A,21,12,").getBytes(StandardCharsets.UTF_8));
        assertThat(inbox.scanOnce()).isEqualTo(1);assertThat(inbox.scanOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_task WHERE dataset_id=?",Integer.class,d)).isEqualTo(2);
    }

    class Client {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        String csrfHeader,csrfToken;
        void csrf()throws Exception{JsonNode response=node(get("/csrf"));csrfHeader=response.path("headerName").asText();csrfToken=response.path("token").asText();}
        void login(String username,String password)throws Exception{csrf();var response=request("/login","POST",("username="+URLEncoder.encode(username,StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8),"application/x-www-form-urlencoded",true);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);csrf();}
        HttpResponse<String> get(String path)throws Exception{return request(path,"GET",null,null,false);}
        HttpResponse<String> post(String path,Object body)throws Exception{return request(path,"POST",body==null?new byte[0]:json.writeValueAsBytes(body),"application/json",true);}
        HttpResponse<String> patch(String path,Object body)throws Exception{return request(path,"PATCH",json.writeValueAsBytes(body),"application/json",true);}
        HttpResponse<String> request(String path,String method,byte[] data,String type,boolean token)throws Exception{
            var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/platform/api"+path)).timeout(Duration.ofSeconds(30));
            if(type!=null)b.header("Content-Type",type);if(token&&csrfToken!=null)b.header(csrfHeader,csrfToken);
            return client.send(b.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(data)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        HttpResponse<String> upload(long dataset,String name,byte[] data)throws Exception{
            String boundary="test-boundary-2026";byte[] head=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8),tail=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] payload=new byte[head.length+data.length+tail.length];System.arraycopy(head,0,payload,0,head.length);System.arraycopy(data,0,payload,head.length,data.length);System.arraycopy(tail,0,payload,head.length+data.length,tail.length);
            return request("/datasets/"+dataset+"/files","POST",payload,"multipart/form-data; boundary="+boundary,true);
        }
    }
}
