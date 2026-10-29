package cn.zuoxu.climate.platform;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Reserve database connections for progress transactions and HTTP requests. */
@Component
public record PlatformExecutionSettings(int writeThreads, int exportThreads) {
    @Autowired
    public PlatformExecutionSettings(
            @Value("${app.platform.write-threads:2}") int writeThreads,
            @Value("${app.platform.export-threads:1}") int exportThreads,
            @Value("${spring.datasource.hikari.maximum-pool-size:10}") int connections) {
        this(writeThreads, exportThreads);
        if (connections < 2 * (writeThreads + exportThreads) + 2) {
            throw new IllegalArgumentException("Database pool must be at least 2 * (write-threads + export-threads) + 2");
        }
    }

    public PlatformExecutionSettings {
        if (writeThreads < 1 || writeThreads > 8 || exportThreads < 1 || exportThreads > 8) {
            throw new IllegalArgumentException("Platform write/export thread counts must be between 1 and 8");
        }
    }
}
