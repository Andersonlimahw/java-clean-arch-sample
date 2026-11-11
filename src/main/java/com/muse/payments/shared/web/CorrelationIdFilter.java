package com.muse.payments.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String incomingId = request.getHeader(CorrelationContext.CORRELATION_ID_HEADER);
        String correlationId;

        if (StringUtils.hasText(incomingId) && SAFE_CORRELATION_ID.matcher(incomingId.trim()).matches()) {
            correlationId = incomingId.trim();
        } else {
            correlationId = UUID.randomUUID().toString();
        }

        try {
            CorrelationContext.set(correlationId);
            response.setHeader(CorrelationContext.CORRELATION_ID_HEADER, correlationId);
            filterChain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }
}
