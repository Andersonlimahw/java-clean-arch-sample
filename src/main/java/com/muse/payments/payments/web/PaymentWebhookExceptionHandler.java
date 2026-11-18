package com.muse.payments.payments.web;

import com.muse.payments.payments.domain.psp.InvalidProviderPayloadException;
import com.muse.payments.payments.domain.psp.InvalidProviderSignatureException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Webhook-only error mapping. Signature failures are 400 with the RFC 7807 contract and
 * are logged without the body, the header, or the secret.
 */
@RestControllerAdvice
public class PaymentWebhookExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookExceptionHandler.class);

    @ExceptionHandler(InvalidProviderSignatureException.class)
    public ProblemDetail handleSignature(InvalidProviderSignatureException ex, HttpServletRequest request) {
        log.warn("Rejected provider callback at {}: {}", request.getRequestURI(), ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Provider Signature");
        problem.setType(URI.create("urn:problem-type:provider-signature"));
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    @ExceptionHandler(InvalidProviderPayloadException.class)
    public ProblemDetail handlePayload(InvalidProviderPayloadException ex, HttpServletRequest request) {
        log.warn("Rejected provider callback at {}: {}", request.getRequestURI(), ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Provider Payload");
        problem.setType(URI.create("urn:problem-type:provider-payload"));
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
