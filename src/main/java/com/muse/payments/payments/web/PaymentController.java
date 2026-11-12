package com.muse.payments.payments.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.payments.application.CancelPaymentCommand;
import com.muse.payments.payments.application.CancelPaymentCommandHandler;
import com.muse.payments.payments.application.ConfirmPaymentCommand;
import com.muse.payments.payments.application.ConfirmPaymentCommandHandler;
import com.muse.payments.payments.application.CreatePaymentRequest;
import com.muse.payments.payments.application.CreatePaymentResult;
import com.muse.payments.payments.application.CreatePaymentUseCase;
import com.muse.payments.payments.application.ReviewDecision;
import com.muse.payments.payments.application.ReviewPaymentCommand;
import com.muse.payments.payments.application.ReviewPaymentCommandHandler;
import com.muse.payments.payments.application.GetPaymentByIdQuery;
import com.muse.payments.payments.application.GetPaymentByIdQueryHandler;
import com.muse.payments.payments.application.ListPaymentsQuery;
import com.muse.payments.payments.application.ListPaymentsQueryHandler;
import com.muse.payments.payments.application.PageResponse;
import com.muse.payments.payments.application.PaymentResponse;
import com.muse.payments.shared.domain.ValidationException;
import com.muse.payments.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Payment management. Requires role USER or ADMIN. ADMIN may read any payment by id; list, confirm, and cancel stay owner-scoped. Another user's payment is 404.")
@SecurityRequirement(name = "BearerAuth")
public class PaymentController {

    public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String HEADER_IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final CreatePaymentUseCase createPaymentUseCase;
    private final ReviewPaymentCommandHandler reviewPaymentHandler;
    private final ConfirmPaymentCommandHandler confirmPaymentHandler;
    private final CancelPaymentCommandHandler cancelPaymentHandler;
    private final GetPaymentByIdQueryHandler getPaymentByIdHandler;
    private final ListPaymentsQueryHandler listPaymentsHandler;
    private final ObjectMapper objectMapper;

    public PaymentController(
            CreatePaymentUseCase createPaymentUseCase,
            ReviewPaymentCommandHandler reviewPaymentHandler,
            ConfirmPaymentCommandHandler confirmPaymentHandler,
            CancelPaymentCommandHandler cancelPaymentHandler,
            GetPaymentByIdQueryHandler getPaymentByIdHandler,
            ListPaymentsQueryHandler listPaymentsHandler,
            ObjectMapper objectMapper
    ) {
        this.createPaymentUseCase = Objects.requireNonNull(createPaymentUseCase);
        this.reviewPaymentHandler = Objects.requireNonNull(reviewPaymentHandler);
        this.confirmPaymentHandler = Objects.requireNonNull(confirmPaymentHandler);
        this.cancelPaymentHandler = Objects.requireNonNull(cancelPaymentHandler);
        this.getPaymentByIdHandler = Objects.requireNonNull(getPaymentByIdHandler);
        this.listPaymentsHandler = Objects.requireNonNull(listPaymentsHandler);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a payment. Fraud screening runs outside the database transaction. An explicit decline is 422. If the provider does not answer, the payment is stored as UNDER_REVIEW and cannot be confirmed.")
    public ResponseEntity<?> createPayment(
            @RequestHeader(value = HEADER_IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request,
            CurrentUser currentUser
    ) throws JsonProcessingException {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ValidationException("Idempotency-Key header is required for payment creation");
        }
        if (idempotencyKey.trim().length() > 120) {
            throw new ValidationException("Idempotency-Key header must not exceed 120 characters");
        }
        CreatePaymentResult result = createPaymentUseCase.execute(
                currentUser.userId(), idempotencyKey.trim(), canonicalize(request));
        if (result instanceof CreatePaymentResult.Replayed replay) {
            MediaType replayType = replay.status() == HttpStatus.UNPROCESSABLE_ENTITY.value()
                    ? MediaType.APPLICATION_PROBLEM_JSON
                    : MediaType.APPLICATION_JSON;
            return ResponseEntity.status(replay.status())
                    .header(HEADER_IDEMPOTENT_REPLAYED, "true")
                    .contentType(replayType)
                    .body(replay.body());
        }
        PaymentResponse created = ((CreatePaymentResult.Created) result).body();
        return ResponseEntity.created(URI.create("/api/v1/payments/" + created.id())).body(created);
    }

    private static CreatePaymentRequest canonicalize(CreatePaymentRequest request) {
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        String currency = request.currency().trim().toUpperCase(Locale.ROOT);
        return new CreatePaymentRequest(amount, currency, request.description());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get payment details by ID")
    public ResponseEntity<PaymentResponse> getPaymentById(
            @PathVariable UUID id,
            CurrentUser currentUser
    ) {
        PaymentResponse response = getPaymentByIdHandler.handle(
                new GetPaymentByIdQuery(id, currentUser.userId(), currentUser.isAdmin())
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(summary = "List current user payments with pagination")
    public ResponseEntity<PageResponse<PaymentResponse>> listPayments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            CurrentUser currentUser
    ) {
        PageResponse<PaymentResponse> response = listPaymentsHandler.handle(
                new ListPaymentsQuery(currentUser.userId(), page, size)
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/review")
    @Operation(summary = "ADMIN or FRAUD_REVIEWER only. The reviewer cannot own the payment. APPROVE moves UNDER_REVIEW to PENDING. REJECT cancels it. Replaying the original Idempotency-Key still returns the stored 201 UNDER_REVIEW body.")
    public ResponseEntity<PaymentResponse> reviewPayment(
            @PathVariable UUID id,
            @RequestParam ReviewDecision decision,
            CurrentUser currentUser
    ) {
        PaymentResponse response = reviewPaymentHandler.handle(new ReviewPaymentCommand(id, currentUser.userId(), decision));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm a PENDING payment. A payment held UNDER_REVIEW was not approved and cannot be confirmed.")
    public ResponseEntity<PaymentResponse> confirmPayment(
            @PathVariable UUID id,
            CurrentUser currentUser
    ) {
        PaymentResponse response = confirmPaymentHandler.handle(new ConfirmPaymentCommand(id, currentUser.userId()));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a PENDING or UNDER_REVIEW payment")
    public ResponseEntity<PaymentResponse> cancelPayment(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "Customer requested cancellation") String reason,
            CurrentUser currentUser
    ) {
        PaymentResponse response = cancelPaymentHandler.handle(new CancelPaymentCommand(id, currentUser.userId(), reason));
        return ResponseEntity.ok(response);
    }
}
