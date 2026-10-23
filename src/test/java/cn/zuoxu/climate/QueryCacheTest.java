package cn.zuoxu.climate;

import cn.zuoxu.climate.service.QueryCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.util.Map;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryCacheTest {
    @Test void redisOutageFallsBackToDatabase() {
        var redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> ops=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        var cache=new QueryCache(redis,new ObjectMapper(),true,30,"test");
        var result=cache.readScoped(1,1,"THRESHOLDS","station",()->Map.of("temp10",10));
        assertThat(result.status()).isEqualTo("UNAVAILABLE");assertThat(result.data()).isEqualTo(Map.of("temp10",10));
    }
    @Test void scopedCacheIsolatesDatasetVersionPaginationAndPreservesDecimal() {
        var redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> ops=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);var stored=new HashMap<String,String>();
        when(ops.get(anyString())).thenAnswer(i->stored.get(i.getArgument(0)));
        doAnswer(i->{stored.put(i.getArgument(0),i.getArgument(1));return null;}).when(ops).set(anyString(),anyString(),any(java.time.Duration.class));
        var cache=new QueryCache(redis,new ObjectMapper(),true,1800,"scoped-test");var reads=new AtomicInteger();
        java.util.function.Supplier<?> load=()->{reads.incrementAndGet();return Map.of("value",new BigDecimal("123.12345678901234567890"));};
        assertThat(cache.readScoped(1,1,"THRESHOLDS","limit=1",load).status()).isEqualTo("MISS");
        var hit=cache.readScoped(1,1,"THRESHOLDS","limit=1",load);assertThat(hit.status()).isEqualTo("HIT");
        assertThat(((Map<?,?>)hit.data()).get("value")).isEqualTo(new BigDecimal("123.12345678901234567890"));
        assertThat(cache.readScoped(2,1,"THRESHOLDS","limit=1",load).status()).isEqualTo("MISS");
        assertThat(cache.readScoped(1,2,"THRESHOLDS","limit=1",load).status()).isEqualTo("MISS");
        assertThat(cache.readScoped(1,1,"THRESHOLDS","limit=2",load).status()).isEqualTo("MISS");
        assertThat(reads.get()).isEqualTo(4);assertThat(stored).hasSize(4);
    }
    @Test void scopedCacheFallsBackOnReadAndWriteFailureAndCanBeDisabled() {
        var redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> ops=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        var cache=new QueryCache(redis,new ObjectMapper(),true,30,"scoped-test");
        assertThat(cache.readScoped(1,1,"THRESHOLDS","",()->Map.of("ok",true)).status()).isEqualTo("UNAVAILABLE");
        doReturn(null).when(ops).get(anyString());doThrow(new RedisConnectionFailureException("offline")).when(ops).set(anyString(),anyString(),any(java.time.Duration.class));
        var result=cache.readScoped(1,1,"THRESHOLDS","",()->Map.of("ok",true));
        assertThat(result.status()).isEqualTo("UNAVAILABLE");assertThat(result.data()).isEqualTo(Map.of("ok",true));
        reset(redis);var disabled=new QueryCache(redis,new ObjectMapper(),false,30,"scoped-test");
        assertThat(disabled.readScoped(1,1,"THRESHOLDS","",()->42).status()).isEqualTo("BYPASS");verifyNoInteractions(redis);
    }
}
