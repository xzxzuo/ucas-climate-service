package cn.zuoxu.climate;

import cn.zuoxu.climate.bootstrap.SampleLoader;
import cn.zuoxu.climate.platform.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@EnabledIfEnvironmentVariable(named="CLIMATE_IT", matches="true")
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformConcurrencyIntegrationTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.platform.directory", () -> storage.toString());
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired SampleLoader loader;
    @Autowired PlatformBootstrap bootstrap;
    @Autowired PlatformWorker worker;
    @Autowired PlatformProcessor processor;
    @Autowired PlatformStore store;
    @Autowired PlatformProperties properties;
    @Autowired PlatformExecutionSettings settings;
    @MockitoSpyBean PlatformTasks tasks;
    final ObjectMapper json = new ObjectMapper();
    Client admin, alice, bob;
    byte[] fixture;

    @BeforeEach void prepare() throws Exception {
        idle();
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_test");
        for (String table : List.of("platform_export", "platform_analysis_run", "platform_audit", "platform_task",
                "platform_file", "platform_member", "platform_region_year_stat", "platform_station_year_stat",
                "platform_region_daily_stat", "platform_daily_indicator", "platform_station_threshold",
                "platform_raw_observation", "platform_dataset", "platform_user")) jdbc.update("DELETE FROM " + table);
        for (String table : List.of("region_year_stat", "station_year_stat", "region_daily_stat", "daily_indicator",
                "station_threshold", "raw_observation")) jdbc.update("DELETE FROM " + table);
        loader.load(Path.of("src/test/resources/fixture.csv"));
        bootstrap.initialize();
        fixture = Files.readAllBytes(Path.of("src/test/resources/fixture.csv"));
        admin = new Client(); admin.login("admin", "test-platform-admin-password-2026");
        for (String name : List.of("alice", "bob")) {
            assertThat(admin.post("/admin/users", Map.of("username", name, "displayName", name,
                    "password", "test-user-password-2026", "role", "RESEARCHER")).statusCode()).isEqualTo(201);
        }
        alice = new Client(); alice.login("alice", "test-user-password-2026");
        bob = new Client(); bob.login("bob", "test-user-password-2026");
    }

    @AfterEach void finish() { idle(); jdbc.update("DELETE FROM platform_task WHERE status='QUEUED'"); }

    @Test void twoResearchersSubmitHttpTasksThatReallyOverlap() throws Exception {
        long a = cloneExample(alice), b = cloneExample(bob);
        var submitters = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        String first, second;
        try {
            Future<String> fa = submitters.submit(() -> { start.await(); return analyze(alice, a); });
            Future<String> fb = submitters.submit(() -> { start.await(); return analyze(bob, b); });
            first = fa.get(10, TimeUnit.SECONDS); second = fb.get(10, TimeUnit.SECONDS);
        } finally { submitters.shutdownNow(); }
        try (Gate gate = calculationGate(Set.of(first, second), 2)) {
            dispatch(); dispatch();
            assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.threads).hasSize(2).allMatch(name -> name.startsWith("climate-write-"));
            assertThat(status(first)).isEqualTo("RUNNING");
            assertThat(status(second)).isEqualTo("RUNNING");
            assertThat(worker.activeTaskCount()).isEqualTo(2);
            // Two real calculation transactions reached progress concurrently; neither is mocked out.
        }
        idle();
        assertThat(status(first)).isEqualTo("SUCCESS"); assertThat(status(second)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT active_result_version FROM platform_dataset WHERE id=?", Long.class, a)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT active_result_version FROM platform_dataset WHERE id=?", Long.class, b)).isEqualTo(2L);
        assertThat(bob.get("/tasks/" + first).statusCode()).isEqualTo(404);
    }

    @Test void busyDatasetPreservesWriteOrderWithoutBlockingAnotherDataset() throws Exception {
        long a = cloneExample(alice), b = cloneExample(bob);
        String first = analyze(alice, a), next = analyze(alice, a), independent = analyze(bob, b);
        try (Gate gate = calculationGate(Set.of(first), 1)) {
            dispatch(); assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            dispatch();
            await().atMost(Duration.ofSeconds(10)).until(() -> status(independent).equals("SUCCESS"));
            assertThat(status(first)).isEqualTo("RUNNING"); assertThat(status(next)).isEqualTo("QUEUED");
            assertThat(worker.runOnce()).isFalse();
        }
        idle(); dispatch(); idle();
        assertThat(status(next)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForList("SELECT result_version FROM platform_analysis_run WHERE dataset_id=? ORDER BY result_version", Long.class, a))
                .containsExactly(1L, 2L, 3L);
        assertThat(jdbc.queryForObject("SELECT next_result_version FROM platform_dataset WHERE id=?", Long.class, a)).isEqualTo(4L);
    }

    @Test void exportsProceedWhenAllWriterSlotsAreBusyAndKeepTheirPinnedVersion() throws Exception {
        long a = cloneExample(alice), b = cloneExample(bob), c = cloneExample(alice);
        String first = analyze(alice, a), second = analyze(bob, b);
        String waiting = analyze(alice, c);
        String export = task(alice.post("/datasets/" + a + "/exports", Map.of("kind", "THRESHOLDS", "version", "1")));
        try (Gate gate = calculationGate(Set.of(first, second), 2)) {
            dispatch(); dispatch(); assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            dispatch();
            await().atMost(Duration.ofSeconds(10)).until(() -> status(export).equals("SUCCESS"));
            assertThat(status(first)).isEqualTo("RUNNING"); assertThat(status(second)).isEqualTo("RUNNING");
            assertThat(status(waiting)).isEqualTo("QUEUED");
            var artifact = jdbc.queryForMap("SELECT * FROM platform_export WHERE task_id=?", export);
            assertThat(((Number) artifact.get("result_version")).longValue()).isEqualTo(1L);
            assertThat(alice.get("/exports/" + artifact.get("id") + "/download").body()).contains(a + ",1,");
        }
        idle();
        assertThat(status(first)).isEqualTo("SUCCESS"); assertThat(status(second)).isEqualTo("SUCCESS");
        dispatch(); idle(); assertThat(status(waiting)).isEqualTo("SUCCESS");
    }

    @Test void databaseGuardSerializesWritersEvenOutsideTheDispatcher() throws Exception {
        long dataset = cloneExample(alice);
        String first = analyze(alice, dataset), second = analyze(alice, dataset);
        jdbc.update("UPDATE platform_task SET status='RUNNING' WHERE id IN (?,?)", first, second);
        var callers = Executors.newFixedThreadPool(2);
        try (Gate gate = calculationGate(Set.of(first), 1)) {
            Future<?> one = callers.submit(() -> { processor.process(first); return null; });
            assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            var attempted = new CountDownLatch(1);
            Future<?> two = callers.submit(() -> { attempted.countDown(); processor.process(second); return null; });
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> two.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            gate.release.countDown();
            one.get(10, TimeUnit.SECONDS); two.get(10, TimeUnit.SECONDS);
        } finally { callers.shutdownNow(); callers.awaitTermination(10, TimeUnit.SECONDS); }
        assertThat(status(first)).isEqualTo("SUCCESS"); assertThat(status(second)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForList("SELECT result_version FROM platform_analysis_run WHERE dataset_id=? ORDER BY result_version", Long.class, dataset))
                .containsExactly(1L, 2L, 3L);
    }

    @Test void stagingAnUploadDoesNotWaitForTheDatasetCalculationLock() throws Exception {
        long dataset = cloneExample(alice); String analysis = analyze(alice, dataset);
        String imported;
        try (Gate gate = calculationGate(Set.of(analysis), 1)) {
            dispatch(); assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            var caller = Executors.newSingleThreadExecutor();
            try {
                var response = caller.submit(() -> alice.upload(dataset, fixture)).get(3, TimeUnit.SECONDS);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
                imported = json.readTree(response.body()).path("task").path("id").asText();
            } finally { caller.shutdownNow(); }
            assertThat(status(analysis)).isEqualTo("RUNNING");
            assertThat(status(imported)).isEqualTo("QUEUED");
            assertThat(worker.runOnce()).isFalse();
        }
        idle(); dispatch(); idle(); assertThat(status(imported)).isEqualTo("SUCCESS");
    }

    @Test void aFailedImportReleasesItsDatasetAndExecutionSlot() throws Exception {
        long dataset = create(alice);
        byte[] invalid = new String(fixture, StandardCharsets.UTF_8).replace(",A,20,12,0.1,", ",A,20,12,2.0,").getBytes(StandardCharsets.UTF_8);
        String failed = json.readTree(alice.upload(dataset, invalid).body()).path("task").path("id").asText();
        String valid = json.readTree(alice.upload(dataset, fixture).body()).path("task").path("id").asText();
        dispatch(); idle(); assertThat(status(failed)).isEqualTo("FAILED");
        dispatch(); idle(); assertThat(status(valid)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT raw_revision FROM platform_dataset WHERE id=?", Long.class, dataset)).isEqualTo(1L);
    }

    @Test void rejectedExecutionRestoresTheDurableQueuedTask() throws Exception {
        long dataset = cloneExample(alice); String id = analyze(alice, dataset);
        var rejecting = new PlatformWorker(store, processor, tasks, properties, settings);
        try {
            ((ThreadPoolExecutor) Objects.requireNonNull(ReflectionTestUtils.getField(rejecting, "writers"))).shutdown();
            assertThat(rejecting.runOnce()).isFalse();
            assertThat(rejecting.activeTaskCount()).isZero(); assertThat(status(id)).isEqualTo("QUEUED");
            assertThat(jdbc.queryForObject("SELECT started_at FROM platform_task WHERE id=?", java.sql.Timestamp.class, id)).isNull();
        } finally { rejecting.close(); }
        dispatch(); idle(); assertThat(status(id)).isEqualTo("SUCCESS");
    }

    @Test void simultaneousSubmittersCannotExceedTheGlobalBacklogLimit() throws Exception {
        long dataset = cloneExample(alice);
        assertThat(alice.post("/datasets/" + dataset + "/members", Map.of("username", "bob", "permission", "WRITE")).statusCode()).isEqualTo(200);
        long creator = jdbc.queryForObject("SELECT id FROM platform_user WHERE username='alice'", Long.class);
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < 99; i++) rows.add(new Object[]{UUID.randomUUID().toString(), dataset, creator});
        jdbc.batchUpdate("INSERT INTO platform_task(id,dataset_id,created_by,task_type,payload) VALUES(?,?,?,'ANALYZE','{}')", rows);
        var callers = Executors.newFixedThreadPool(2); var start = new CyclicBarrier(2);
        try {
            Future<Integer> one = callers.submit(() -> { start.await(); return alice.post("/datasets/" + dataset + "/analyze", null).statusCode(); });
            Future<Integer> two = callers.submit(() -> { start.await(); return bob.post("/datasets/" + dataset + "/analyze", null).statusCode(); });
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(202, 503);
        } finally { callers.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_task WHERE status IN ('QUEUED','RUNNING')", Integer.class)).isEqualTo(100);
    }

    @Test void shutdownDrainsActiveWorkAndLeavesWaitingTasksQueued() throws Exception {
        long dataset = cloneExample(alice); String first = analyze(alice, dataset), next = analyze(alice, dataset);
        var local = new PlatformWorker(store, processor, tasks, properties, settings);
        var closer = Executors.newSingleThreadExecutor();
        try (Gate gate = calculationGate(Set.of(first), 1)) {
            assertThat(local.runOnce()).isTrue(); assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> closing = closer.submit(local::close);
            assertThatThrownBy(() -> closing.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(local.runOnce()).isFalse();
            gate.release.countDown(); closing.get(10, TimeUnit.SECONDS);
            assertThat(status(first)).isEqualTo("SUCCESS"); assertThat(status(next)).isEqualTo("QUEUED");
        } finally { local.close(); closer.shutdownNow(); }
    }

    // Observe and pause real progress writes inside real transactions; calculation itself stays real.
    Gate calculationGate(Set<String> ids, int count) {
        Gate gate = new Gate(count);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (ids.contains(call.getArgument(0)) && "CALCULATING".equals(call.getArgument(1))) {
                gate.threads.add(Thread.currentThread().getName()); gate.entered.countDown();
                if (!gate.release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test calculation gate timed out");
            }
            return result;
        }).when(tasks).progress(anyString(), anyString(), anyLong());
        return gate;
    }
    static class Gate implements AutoCloseable {
        final CountDownLatch entered, release = new CountDownLatch(1);
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        Gate(int count) { entered = new CountDownLatch(count); }
        public void close() { release.countDown(); }
    }
    void dispatch() { await().pollInterval(Duration.ofMillis(20)).atMost(Duration.ofSeconds(5)).until(worker::runOnce); }
    void idle() { await().atMost(Duration.ofSeconds(20)).until(() -> worker.activeTaskCount() == 0); }
    String status(String id) { return jdbc.queryForObject("SELECT status FROM platform_task WHERE id=?", String.class, id); }
    String task(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202); return json.readTree(response.body()).path("id").asText();
    }
    String analyze(Client client, long dataset) throws Exception { return task(client.post("/datasets/" + dataset + "/analyze", null)); }
    long cloneExample(Client client) throws Exception {
        long example = jdbc.queryForObject("SELECT id FROM platform_dataset WHERE is_example=TRUE", Long.class);
        var response = client.post("/datasets/" + example + "/clone", Map.of("name", "并发测试"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201); return json.readTree(response.body()).path("id").asLong();
    }
    long create(Client client) throws Exception {
        var response = client.post("/datasets", Map.of("name", "并发导入测试"));
        assertThat(response.statusCode()).isEqualTo(201); return json.readTree(response.body()).path("id").asLong();
    }
    class Client {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrfHeader, csrfToken;
        HttpResponse<String> get(String path) throws Exception { return request(path, "GET", null, null); }
        HttpResponse<String> post(String path, Object body) throws Exception {
            return request(path, "POST", body == null ? new byte[0] : json.writeValueAsBytes(body), "application/json");
        }
        void csrf() throws Exception {
            JsonNode token = json.readTree(get("/csrf").body()); csrfHeader = token.path("headerName").asText(); csrfToken = token.path("token").asText();
        }
        void login(String username, String password) throws Exception {
            csrf(); var response = request("/login", "POST", ("username=" + username + "&password=" + password).getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200); csrf();
        }
        HttpResponse<String> request(String path, String method, byte[] body, String type) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/platform/api" + path)).timeout(Duration.ofSeconds(20));
            if (type != null) request.header("Content-Type", type);
            if (!method.equals("GET") && csrfToken != null) request.header(csrfHeader, csrfToken);
            return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> upload(long dataset, byte[] body) throws Exception {
            String boundary = "parallel-test-boundary";
            String csv = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"observations.csv\"\r\nContent-Type: text/csv\r\n\r\n"
                    + new String(body, StandardCharsets.UTF_8) + "\r\n--" + boundary + "--\r\n";
            return request("/datasets/" + dataset + "/files", "POST", csv.getBytes(StandardCharsets.UTF_8), "multipart/form-data; boundary=" + boundary);
        }
    }
}
