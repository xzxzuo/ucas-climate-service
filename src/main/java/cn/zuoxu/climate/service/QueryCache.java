package cn.zuoxu.climate.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

@Service
public class QueryCache {
    public record Result(Object data,String status) {}
    private static final Logger log=LoggerFactory.getLogger(QueryCache.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final int ttl;
    private final String namespace;
    private final AtomicLong revision=new AtomicLong(-1);
    private final LongAdder hits=new LongAdder(),misses=new LongAdder(),failures=new LongAdder();
    public QueryCache(StringRedisTemplate redis,ObjectMapper json,JdbcTemplate jdbc,
                      @Value("${app.cache-enabled}") boolean enabled,
                      @Value("${app.cache-ttl-seconds}") int ttl,
                      @Value("${app.cache-namespace:climate-rebuild}") String namespace) {
        this.redis=redis;this.json=json;this.jdbc=jdbc;this.enabled=enabled;this.ttl=ttl;this.namespace=namespace;
    }
    public void setRevision(long n) { revision.accumulateAndGet(n,Math::max); }
    private long revision() {
        if (revision.get()<0) revision.compareAndSet(-1,jdbc.queryForObject("SELECT revision FROM analysis_state WHERE id=1",Long.class));
        return revision.get();
    }
    public Result read(String kind,String parameters,Supplier<?> database) {
        if (!enabled) return new Result(database.get(),"BYPASS");
        String key=namespace+":"+revision()+":"+kind+":"+digest(parameters);
        return cached(key,database);
    }
    /** The caller must check dataset access before invoking this method, including on cache hits. */
    public Result readScoped(long dataset,long version,String kind,String parameters,Supplier<?> database) {
        if (!enabled) return new Result(database.get(),"BYPASS");
        // Independent from the legacy global revision and isolated from other app/test namespaces.
        String key=namespace+":platform:"+dataset+":"+version+":"+kind+":"+digest(parameters);
        return cached(key,database);
    }
    private Result cached(String key,Supplier<?> database) {
        boolean unavailable=false;
        try {
            String value=redis.opsForValue().get(key);
            if (value!=null) { Object result=json.readerFor(new TypeReference<Object>(){}).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(value);hits.increment();return new Result(result,"HIT"); }
        } catch (Exception e) { failures.increment();unavailable=true;log.debug("Redis read unavailable: {}",e.getClass().getSimpleName()); }
        misses.increment();
        Object result=database.get();
        if (!unavailable) {
            try { redis.opsForValue().set(key,json.writeValueAsString(result),Duration.ofSeconds(ttl)); }
            catch (Exception e) { failures.increment();unavailable=true; }
        }
        return new Result(result,unavailable?"UNAVAILABLE":"MISS");
    }
    public Map<String,Object> stats() { return Map.of("hits",hits.sum(),"misses",misses.sum(),"redisFailures",failures.sum(),"revision",revision(),"enabled",enabled); }
    private static String digest(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
