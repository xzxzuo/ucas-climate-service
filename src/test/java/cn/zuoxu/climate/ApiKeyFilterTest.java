package cn.zuoxu.climate;

import cn.zuoxu.climate.security.ApiKeyFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class ApiKeyFilterTest {
    private static final String READER="test-reader-key-at-least-32-characters";
    private static final String ADMIN="test-admin-key-at-least-32-characters";
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void requiresDifferentSufficientlyLongKeys() {
        assertThatThrownBy(()->new ApiKeyFilter("short",ADMIN,new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ApiKeyFilter(ADMIN,ADMIN,new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsMultipleCredentialHeadersBeforeTheController() throws Exception {
        var request=new MockHttpServletRequest();request.setServletPath("/api/meta");
        request.addHeader("X-API-Key",READER);request.addHeader("X-API-Key",ADMIN);
        var response=new MockHttpServletResponse();var called=new AtomicBoolean();
        new ApiKeyFilter(READER,ADMIN,new ObjectMapper()).doFilter(request,response,(a,b)->called.set(true));
        assertThat(response.getStatus()).isEqualTo(401);assertThat(called.get()).isFalse();
    }
    @Test void readerCannotAcquireAnAdminAuthorityFromAClientHeader() throws Exception {
        var request=new MockHttpServletRequest();request.setServletPath("/api/meta");
        request.addHeader("X-API-Key",READER);request.addHeader("X-Role","ADMIN");
        new ApiKeyFilter(READER,ADMIN,new ObjectMapper()).doFilter(request,new MockHttpServletResponse(),(a,b)->{
            var authentication=SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication.getName()).isEqualTo("reader-service");
            assertThat(authentication.getCredentials()).isNull();
            assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_READER");
        });
    }
}
