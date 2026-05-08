package com.tcc.common.failure;

import com.tcc.common.headers.TccHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class FailureInjectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FailureInjectionFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/tcc/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String sleepHeader = request.getHeader(TccHeaders.SLEEP_MILLIS);
        if (sleepHeader != null && !sleepHeader.isBlank()) {
            try {
                long ms = Long.parseLong(sleepHeader.trim());
                if (ms > 0) {
                    log.warn("[failure-injection] sleeping {} ms before {} {}", ms, request.getMethod(), request.getRequestURI());
                    Thread.sleep(ms);
                }
            } catch (NumberFormatException ignored) {
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
                return;
            }
        }

        String failAtHeader = request.getHeader(TccHeaders.FAIL_AT);
        if (failAtHeader != null && !failAtHeader.isBlank()) {
            FailAt requested;
            try {
                requested = FailAt.valueOf(failAtHeader.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                chain.doFilter(request, response);
                return;
            }
            FailAt currentPhase = FailAt.fromHttpMethod(request.getMethod());
            if (requested == currentPhase) {
                log.warn("[failure-injection] failing {} {} (X-Fail-At={})", request.getMethod(), request.getRequestURI(), requested);
                response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"injected failure at " + requested + "\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }
}
