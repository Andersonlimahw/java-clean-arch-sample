package com.muse.payments.payments.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.payments.application.CancelPaymentCommand;
import com.muse.payments.payments.application.CancelPaymentCommandHandler;
import com.muse.payments.payments.application.ConfirmPaymentCommand;
import com.muse.payments.payments.application.ConfirmPaymentCommandHandler;
import com.muse.payments.payments.application.CreatePaymentCommand;
import com.muse.payments.payments.application.CreatePaymentCommandHandler;
import com.muse.payments.payments.application.CreatePaymentRequest;
import com.muse.payments.payments.application.GetPaymentByIdQuery;
import com.muse.payments.payments.application.GetPaymentByIdQueryHandler;
import com.muse.payments.payments.application.ListPaymentsQuery;
import com.muse.payments.payments.application.ListPaymentsQueryHandler;
import com.muse.payments.payments.application.PageResponse;
import com.muse.payments.payments.application.PaymentResponse;
import com.muse.payments.shared.domain.ValidationException;
import com.muse.payments.shared.infrastructure.idempotency.IdempotencyDecision;
import com.muse.payments.shared.infrastructure.idempotency.IdempotencyService;
import com.muse.payments.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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

    private final CreatePaymentCommandHandler createPaymentHandler;
    private final ConfirmPaymentCommandHandler confirmPaymentHandler;
    private final CancelPaymentCommandHandler cancelPaymentHandler;
    private final GetPaymentByIdQueryHandler getPaymentByIdHandler;
    private final ListPaymentsQueryHandler listPaymentsHandler;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public PaymentController(
            CreatePaymentCommandHandler createPaymentHandler,
            ConfirmPaymentCommandHandler confirmPaymentHandler,
            CancelPaymentCommandHandler cancelPaymentHandler,
            GetPaymentByIdQueryHandler getPaymentByIdHandler,
            ListPaymentsQueryHandler listPaymentsHandler,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper
    ) {
        this.createPaymentHandler = Objects.requireNonNull(createPaymentHandler);
        this.confirmPaymentHandler = Objects.requireNonNull(confirmPaymentHandler);
        this.cancelPaymentHandler = Objects.requireNonNull(cancelPaymentHandler);
        this.getPaymentByIdHandler = Objects.requireNonNull(getPaymentByIdHandler);
        this.listPaymentsHandler = Objects.requireNonNull(listPaymentsHandler);
        this.idempotencyService = Objects.requireNonNull(idempotencyService);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a new payment with idempotency guarantee")
    @Transactional(rollbackFor = Exception.class)
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

        String normalizedKey = idempotencyKey.trim();
        CreatePaymentRequest canonical = canonicalize(request);
        String requestPayloadJson = objectMapper.writeValueAsString(canonical);

        IdempotencyDecision decision = idempotencyService.evaluateOrAcquire(
                currentUser.userId(),
                normalizedKey,
                requestPayloadJson,
                IdempotencyService.DEFAULT_TTL
        );

        if (decision.isReplayed()) {
            return ResponseEntity.status(decision.responseCode())
                    .header(HEADER_IDEMPOTENT_REPLAYED, "true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(decision.responseBody());
        }

        CreatePaymentCommand command = new CreatePaymentCommand(
                currentUser.userId(),
                canonical.amount(),
                canonical.currency(),
                canonical.description()
        );

        PaymentResponse response;
        try {
            response = createPaymentHandler.handle(command);
        } catch (RuntimeException ex) {
            try {
                idempotencyService.releaseProcessing(
                        currentUser.userId(),
                        normalizedKey,
                        requestPayloadJson,
                        decision.leaseId()
                );
            } catch (RuntimeException releaseError) {
                ex.addSuppressed(releaseError);
            }
            throw ex;
        }
        String responseJson = objectMapper.writeValueAsString(response);

        idempotencyService.saveCompleted(
                currentUser.userId(),
                normalizedKey,
                requestPayloadJson,
                decision.leaseId(),
                HttpStatus.CREATED.value(),
                responseJson
        );

        return ResponseEntity
                .created(URI.create("/api/v1/payments/" + response.id()))
                .body(response);
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
    @Operation(
            summary = "List current user payments with offset pagination",
            description = "Owner-scoped offset page. Default page is 0 and default size is 20. "
                    + "page must be an integer >= 0. size must be an integer from 1 to 100. "
                    + "sort allowlist is createdAt,desc (default) and createdAt,asc. "
                    + "createdAt,desc ties break on id DESC; createdAt,asc ties break on id ASC. "
                    + "A negative page, a size outside 1..100, a non-integer page or size, "
                    + "and any other sort are HTTP 400 application/problem+json and are not clamped. "
                    + "Omitting page, size, and sort stays compatible with existing clients. "
                    + "The body adds sort and keeps items, page, size, totalElements, and totalPages. "
                    + "A page past the end is HTTP 200 with an empty items array."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of payments owned by the caller"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid page, size, or sort",
                    content = @Content(mediaType = "application/problem+json")
            )
    })
    public ResponseEntity<PageResponse<PaymentResponse>> listPayments(
            @Parameter(
                    description = "Zero-based page index. Default 0. Negative or non-integer values are rejected with 400.",
                    schema = @Schema(type = "integer", minimum = "0", defaultValue = "0")
            )
            @RequestParam(defaultValue = "0") int page,
            @Parameter(
                    description = "Page size. Default 20. Must be from 1 to 100 inclusive. Other values are rejected with 400.",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20")
            )
            @RequestParam(defaultValue = "20") int size,
            @Parameter(
                    description = "Allowlist: createdAt,desc (default) or createdAt,asc. Any other value is 400.",
                    schema = @Schema(allowableValues = {"createdAt,desc", "createdAt,asc"}, defaultValue = "createdAt,desc")
            )
            @RequestParam(required = false) String sort,
            CurrentUser currentUser
    ) {
        PageResponse<PaymentResponse> response = listPaymentsHandler.handle(
                new ListPaymentsQuery(currentUser.userId(), page, size, sort)
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm payment state transition from PENDING to COMPLETED")
    public ResponseEntity<PaymentResponse> confirmPayment(
            @PathVariable UUID id,
            CurrentUser currentUser
    ) {
        PaymentResponse response = confirmPaymentHandler.handle(new ConfirmPaymentCommand(id, currentUser.userId()));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel payment state transition from PENDING to CANCELLED")
    public ResponseEntity<PaymentResponse> cancelPayment(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "Customer requested cancellation") String reason,
            CurrentUser currentUser
    ) {
        PaymentResponse response = cancelPaymentHandler.handle(new CancelPaymentCommand(id, currentUser.userId(), reason));
        return ResponseEntity.ok(response);
    }
}
