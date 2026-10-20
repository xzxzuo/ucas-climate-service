package cn.zuoxu.climate;

import cn.zuoxu.climate.incremental.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IncrementalServiceTest {
    @TempDir Path inbox;
    private IncrementalProperties properties(int perRun,int maxInbox,long minimumAge) {
        return new IncrementalProperties(false,inbox.toString(),60000,30000,minimumAge,128,perRun,maxInbox,3,60);
    }
    @Test void rotatesBoundedWorkInsteadOfStarvingLaterFiles() throws Exception {
        Files.writeString(inbox.resolve("a.csv"),"a");Files.writeString(inbox.resolve("b.csv"),"b");
        var processor=mock(IncrementalFileProcessor.class);
        var names=new ArrayList<String>();
        when(processor.process(any(),eq(false))).thenAnswer(invocation->{
            FileSnapshot input=invocation.getArgument(0);names.add(input.fileName());
            return new IncrementalFileProcessor.Result(input.fileName(),"SUCCESS",input.sha256(),1,1L,null);
        });
        var service=new IncrementalService(properties(1,10,0),processor,mock(IncrementalJobStore.class));
        assertThat(service.runOnce("TEST",false).files()).hasSize(1);
        assertThat(service.runOnce("TEST",false).files()).hasSize(1);
        assertThat(names).containsExactly("a.csv","b.csv");
    }
    @Test void ignoresUnpublishedFilesAndDefersRecentlyWrittenCsv() throws Exception {
        Files.writeString(inbox.resolve("new.csv"),"a");Files.writeString(inbox.resolve("unfinished.part"),"b");
        var processor=mock(IncrementalFileProcessor.class);
        var service=new IncrementalService(properties(5,10,60000),processor,mock(IncrementalJobStore.class));
        var results=service.runOnce("TEST",false).files();
        assertThat(results).hasSize(1);assertThat(results.get(0).status()).isEqualTo("DEFERRED");
        verifyNoInteractions(processor);
    }
    @Test void refusesAnOverfullInboxWithoutPartialSilentScanning() throws Exception {
        Files.writeString(inbox.resolve("a.csv"),"a");Files.writeString(inbox.resolve("b.csv"),"b");
        var processor=mock(IncrementalFileProcessor.class);
        var service=new IncrementalService(properties(1,1,0),processor,mock(IncrementalJobStore.class));
        assertThat(service.runOnce("TEST",false).error()).contains("too many");
        verifyNoInteractions(processor);
    }
    @Test void overlappingManualAndScheduledRunsAreNotExecutedTwice() throws Exception {
        Files.writeString(inbox.resolve("a.csv"),"a");
        var processor=mock(IncrementalFileProcessor.class);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(processor.process(any(),eq(false))).thenAnswer(invocation->{
            entered.countDown();assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();
            return new IncrementalFileProcessor.Result("a.csv","SUCCESS",null,1,1L,null);
        });
        var service=new IncrementalService(properties(1,10,0),processor,mock(IncrementalJobStore.class));
        var executor=Executors.newSingleThreadExecutor();
        try {
            var first=executor.submit(()->service.runOnce("SCHEDULED",false));
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            assertThat(service.runOnce("MANUAL",false).busy()).isTrue();
            release.countDown();assertThat(first.get(3,TimeUnit.SECONDS).files()).hasSize(1);
            assertThat(service.status().get("running")).isEqualTo(false);
            verify(processor,times(1)).process(any(),eq(false));
        } finally { release.countDown();executor.shutdownNow(); }
    }
}
