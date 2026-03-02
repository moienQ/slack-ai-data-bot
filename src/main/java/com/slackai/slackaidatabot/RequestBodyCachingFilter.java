package com.slackai.slackaidatabot;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

/**
 * Wraps every incoming request in a ContentCachingRequestWrapper so that
 * the raw request body can be read AFTER Spring has already parsed form params.
 * This is required for Slack signing-secret HMAC validation.
 */
@Component
public class RequestBodyCachingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpReq) {
            chain.doFilter(new ContentCachingRequestWrapper(httpReq), response);
        } else {
            chain.doFilter(request, response);
        }
    }
}
