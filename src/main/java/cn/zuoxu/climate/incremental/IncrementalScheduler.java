package cn.zuoxu.climate.incremental;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IncrementalScheduler {
    private static final Logger log=LoggerFactory.getLogger(IncrementalScheduler.class);
    private final IncrementalService service;
    private final IncrementalProperties properties;
    public IncrementalScheduler(IncrementalService service, IncrementalProperties properties) {
        this.service=service; this.properties=properties;
    }

    @Scheduled(fixedDelayString="${app.incremental.fixed-delay-ms}", initialDelayString="${app.incremental.initial-delay-ms}")
    public void tick() {
        if (!properties.enabled()) return;
        try { service.runOnce("SCHEDULED",false); }
        catch (Exception exception) { log.warn("Incremental poll failed type={}",exception.getClass().getSimpleName()); }
    }
}

@Configuration
@EnableScheduling
@EnableConfigurationProperties(IncrementalProperties.class)
class IncrementalSchedulingConfiguration {}
