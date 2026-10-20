package cn.zuoxu.climate.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.Path;

@Component
public class PlatformProperties {
    public final Path directory;
    public final boolean workerEnabled;
    public final String bootstrapPassword;
    public PlatformProperties(@Value("${app.platform.directory:.runtime/platform}") String directory,
            @Value("${app.platform.worker-enabled:true}") boolean enabled,
            @Value("${app.platform.admin-password:}") String password) {
        this.directory=Path.of(directory).toAbsolutePath().normalize();this.workerEnabled=enabled;this.bootstrapPassword=password;
    }
    public Path upload(String id) { return child("uploads",id,".csv"); }
    public Path export(String id, boolean pending) { return child("exports",id,pending?".part":".csv"); }
    private Path child(String folder,String id,String suffix) {
        if (!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new IllegalArgumentException("Invalid file ID");
        return directory.resolve(folder).resolve(id+suffix);
    }
}
