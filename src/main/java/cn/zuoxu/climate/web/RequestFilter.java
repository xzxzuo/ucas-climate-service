package cn.zuoxu.climate.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestFilter extends OncePerRequestFilter {
    private static final Logger log=LoggerFactory.getLogger(RequestFilter.class);
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String requestId=UUID.randomUUID().toString();
        MDC.put("requestId",requestId);res.setHeader("X-Request-Id",requestId);
        long start=System.nanoTime();
        try {
            chain.doFilter(req,res);
        } finally {
            log.info("{} {} status={} elapsedMs={}",req.getMethod(),req.getRequestURI(),res.getStatus(),(System.nanoTime()-start)/1_000_000);
            MDC.clear();
        }
    }
}
