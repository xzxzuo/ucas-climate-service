package cn.zuoxu.climate.incremental;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.incremental")
public record IncrementalProperties(
    boolean enabled,
    @NotBlank String directory,
    @Min(100) long fixedDelayMs,
    @Min(0) long initialDelayMs,
    @Min(0) long minFileAgeMs,
    @Min(1) @Max(67108864) long maxFileBytes,
    @Min(1) @Max(50) int maxFilesPerRun,
    @Min(1) @Max(5000) int maxInboxFiles,
    @Min(1) @Max(20) int maxAttempts,
    @Min(1) int retryDelaySeconds
) {}
