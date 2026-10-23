package cn.zuoxu.climate;

import cn.zuoxu.climate.io.FileSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class FileSnapshotTest {
    @TempDir Path directory;
    @Test void hashesExactSnapshotAndRemovesOnlyTheTemporaryCopy() throws Exception {
        Path source=directory.resolve("data.csv");Files.writeString(source,"abc");
        Path copy;
        try (var snapshot=FileSnapshot.capture(source,16)) {
            copy=snapshot.path();
            assertThat(snapshot.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            assertThat(Files.readString(copy)).isEqualTo("abc");
        }
        assertThat(copy).doesNotExist();assertThat(source).exists();
    }
    @Test void refusesOversizedInputs() throws Exception {
        Path source=directory.resolve("large.csv");Files.writeString(source,"too large");
        assertThatThrownBy(()->FileSnapshot.capture(source,2)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void refusesSymlinksAndDirectories() throws Exception {
        Path source=directory.resolve("data.csv");Files.writeString(source,"abc");
        Path link=directory.resolve("link.csv");Files.createSymbolicLink(link,source);
        assertThatThrownBy(()->FileSnapshot.capture(link,16)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->FileSnapshot.capture(directory,16)).isInstanceOf(IllegalArgumentException.class);
    }
}
