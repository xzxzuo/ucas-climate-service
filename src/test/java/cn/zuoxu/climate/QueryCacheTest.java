package cn.zuoxu.climate;

import cn.zuoxu.climate.service.QueryCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryCacheTest {
    @Test void redisOutageFallsBackToDatabase() {
        var redis=mock(StringRedisTemplate.class);var jdbc=mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> ops=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        var cache=new QueryCache(redis,new ObjectMapper(),jdbc,true,30,"test");cache.setRevision(1);
        var result=cache.read("threshold","station",()->Map.of("temp10",10));
        assertThat(result.status()).isEqualTo("UNAVAILABLE");assertThat(result.data()).isEqualTo(Map.of("temp10",10));
        assertThat(cache.stats().get("redisFailures")).isEqualTo(1L);
    }
}
