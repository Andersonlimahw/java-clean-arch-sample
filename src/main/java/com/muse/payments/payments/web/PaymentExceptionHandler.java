package com.muse.payments.payments.web;

import com.muse.payments.payments.domain.PaymentDeclinedException;
import com.muse.payments.shared.web.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PaymentExceptionHandler {

    @ExceptionHandler(PaymentDeclinedException.class)
    public ProblemDetail handleDeclined(PaymentDeclinedException ex, HttpServletRequest request) {
        return GlobalExceptionHandler.createProblemDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, "Payment Declined", ex.getMessage(), request);
    }
}
