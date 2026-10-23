package cn.zuoxu.climate.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import java.io.IOException;
import java.util.Map;

/** JSON errors shared by the platform login and access-denied handlers. */
public final class SecurityErrors {
    private SecurityErrors() {}
    public static void write(HttpServletResponse response,ObjectMapper json,int status,String error,String message)throws IOException{
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","no-store");
        json.writeValue(response.getWriter(),Map.of("error",error,"message",message,"requestId",String.valueOf(MDC.get("requestId"))));
    }
}
