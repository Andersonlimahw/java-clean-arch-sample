package com.muse.payments.payments.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.payments.domain.Money;
import com.muse.payments.payments.domain.PaymentDeclinedException;
import com.muse.payments.shared.application.IdempotencyDecision;
import com.muse.payments.shared.application.IdempotencyPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

@Service
public class CreatePaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreatePaymentUseCase.class);

    private final IdempotencyPort idempotencyPort;
    private final FraudScreeningPort fraudScreeningPort;
    private final ScreenedPaymentRecorder recorder;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public CreatePaymentUseCase(
            IdempotencyPort idempotencyPort,
            FraudScreeningPort fraudScreeningPort,
            ScreenedPaymentRecorder recorder,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper
    ) {
        this.idempotencyPort = Objects.requireNonNull(idempotencyPort);
        this.fraudScreeningPort = Objects.requireNonNull(fraudScreeningPort);
        this.recorder = Objects.requireNonNull(recorder);
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public CreatePaymentResult execute(UUID userId, String idempotencyKey, CreatePaymentRequest canonical) throws JsonProcessingException {
        String payloadJson = objectMapper.writeValueAsString(canonical);
        IdempotencyDecision decision = idempotencyPort.acquireProcessing(userId, idempotencyKey, payloadJson);
        if (decision.isReplayed()) {
            return new CreatePaymentResult.Replayed(decision.responseCode(), decision.responseBody());
        }
        UUID paymentId = UUID.randomUUID();
        UUID providerIdempotencyKey = PaymentIds.forIdempotencyKey(userId, idempotencyKey);
        try {
            Money money = Money.of(canonical.amount(), canonical.currency());
            FraudScreeningResult screening = fraudScreeningPort.screen(
                    new FraudScreeningRequest(paymentId, userId, money, providerIdempotencyKey));
            if (screening.outcome() == FraudScreeningOutcome.DECLINED) {
                log.info("Fraud screening declined payment {} for user {}: {}", paymentId, userId, screening.reason());
                idempotencyPort.saveCompleted(
                        userId,
                        idempotencyKey,
                        payloadJson,
                        decision.leaseId(),
                        422,
                        declinedBody()
                );
                throw new PaymentDeclinedException();
            }
            if (screening.outcome() == FraudScreeningOutcome.UNDER_REVIEW) {
                log.info("Fraud screening held payment {} for user {}: {}", paymentId, userId, screening.reason());
            }
            ScreenedPayment screened = new ScreenedPayment(
                    paymentId, userId, money, canonical.description(), screening.outcome(), screening.reason());
            PaymentResponse response = transactionTemplate.execute(status -> {
                PaymentResponse body = recorder.persist(screened);
                try {
                    idempotencyPort.saveCompleted(
                            userId,
                            idempotencyKey,
                            payloadJson,
                            decision.leaseId(),
                            201,
                            objectMapper.writeValueAsString(body)
                    );
                } catch (JsonProcessingException ex) {
                    throw new IllegalStateException("Could not store the idempotent payment response", ex);
                }
                return body;
            });
            return new CreatePaymentResult.Created(response);
        } catch (PaymentDeclinedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            try {
                idempotencyPort.releaseProcessing(userId, idempotencyKey, payloadJson, decision.leaseId());
            } catch (RuntimeException releaseError) {
                ex.addSuppressed(releaseError);
            }
            throw ex;
        }
    }

    private static String declinedBody() {
        return """
                {"type":"urn:problem-type:422","title":"Payment Declined","status":422,"detail":"%s"}
                """.formatted(PaymentDeclinedException.PUBLIC_MESSAGE).trim();
    }
}
