package com.muse.payments.payments.web;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.muse.payments.payments.application.ListHeldPaymentsQuery;
import com.muse.payments.payments.application.ListHeldPaymentsQueryHandler;
import com.muse.payments.payments.application.ListPaymentsQuery;
import com.muse.payments.payments.application.ListPaymentsQueryHandler;
import com.muse.payments.payments.application.PageResponse;
import com.muse.payments.payments.application.PaymentResponse;
import com.muse.payments.shared.domain.ValidationException;
import com.muse.payments.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
    private final ListHeldPaymentsQueryHandler listHeldPaymentsHandler;

    public PaymentController(
            CreatePaymentUseCase createPaymentUseCase,
            ReviewPaymentCommandHandler reviewPaymentHandler,
            ConfirmPaymentCommandHandler confirmPaymentHandler,
            CancelPaymentCommandHandler cancelPaymentHandler,
            GetPaymentByIdQueryHandler getPaymentByIdHandler,
            ListPaymentsQueryHandler listPaymentsHandler,
            ListHeldPaymentsQueryHandler listHeldPaymentsHandler
    ) {
        this.createPaymentUseCase = Objects.requireNonNull(createPaymentUseCase);
        this.reviewPaymentHandler = Objects.requireNonNull(reviewPaymentHandler);
        this.confirmPaymentHandler = Objects.requireNonNull(confirmPaymentHandler);
        this.cancelPaymentHandler = Objects.requireNonNull(cancelPaymentHandler);
        this.getPaymentByIdHandler = Objects.requireNonNull(getPaymentByIdHandler);
        this.listPaymentsHandler = Objects.requireNonNull(listPaymentsHandler);
        this.listHeldPaymentsHandler = Objects.requireNonNull(listHeldPaymentsHandler);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a payment. Fraud screening runs outside the database transaction. An explicit decline is 422. If the provider does not answer, the payment is stored as UNDER_REVIEW and cannot be confirmed.")
    public ResponseEntity<?> createPayment(
            @RequestHeader(value = HEADER_IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request,
            @Parameter(hidden = true) CurrentUser currentUser,
            HttpServletRequest httpRequest
    ) throws JsonProcessingException {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ValidationException("Idempotency-Key header is required for payment creation");
        }
        if (idempotencyKey.trim().length() > 120) {
            throw new ValidationException("Idempotency-Key header must not exceed 120 characters");
        }
        CreatePaymentResult result = createPaymentUseCase.execute(
                currentUser.userId(), idempotencyKey.trim(), canonicalize(request));
        return respond(result, httpRequest);
    }

    private ResponseEntity<?> respond(CreatePaymentResult result, HttpServletRequest request) {
        return switch (result) {
            case CreatePaymentResult.Created created -> created(created.body(), false);
            case CreatePaymentResult.Declined declined -> declined(request, false);
            case CreatePaymentResult.Replayed replayed -> switch (replayed.outcome()) {
                case CreatePaymentResult.Created created -> created(created.body(), true);
                case CreatePaymentResult.Declined declined -> declined(request, true);
                case CreatePaymentResult.Replayed nested -> throw new IllegalStateException("Stored replay cannot nest");
            };
        };
    }

    private static ResponseEntity<PaymentResponse> created(PaymentResponse body, boolean replay) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.created(URI.create("/api/v1/payments/" + body.id()))
                .contentType(MediaType.APPLICATION_JSON);
        if (replay) {
            builder.header(HEADER_IDEMPOTENT_REPLAYED, "true");
        }
        return builder.body(body);
    }

    private static ResponseEntity<ProblemDetail> declined(HttpServletRequest request, boolean replay) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (replay) {
            builder.header(HEADER_IDEMPOTENT_REPLAYED, "true");
        }
        return builder.body(PaymentExceptionHandler.decline(request));
    }

    private static CreatePaymentRequest canonicalize(CreatePaymentRequest request) {
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        String currency = request.currency().trim().toUpperCase(Locale.ROOT);
        return new CreatePaymentRequest(amount, currency, request.description());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get payment details by ID")
    @ApiResponse(
            responseCode = "400",
            description = "id is not a UUID",
            content = @Content(
                    mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)
            )
    )
    public ResponseEntity<PaymentResponse> getPaymentById(
            @PathVariable UUID id,
            @Parameter(hidden = true) CurrentUser currentUser
    ) {
        PaymentResponse response = getPaymentByIdHandler.handle(
                new GetPaymentByIdQuery(id, currentUser.userId(), currentUser.isAdmin() || currentUser.hasRole("FRAUD_REVIEWER"))
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(
            summary = "List current user payments with offset pagination",
            description = "Owner-scoped offset page. Default page is 0 and default size is 20. "
                    + "page must be an integer >= 0 and page * size must be <= 10000. "
                    + "size must be an integer from 1 to 100. "
                    + "sort is case-sensitive: createdAt,desc (default) or createdAt,asc. "
                    + "createdAt,DESC and a bare createdAt are 400. "
                    + "createdAt,desc ties break on id DESC; createdAt,asc ties break on id ASC. "
                    + "Breaking change: a negative page, a size outside 1..100, a non-integer page or size, "
                    + "an offset above 10000, and any other sort are HTTP 400 and are not clamped. "
                    + "Omitting page, size, and sort stays compatible. "
                    + "Order is stable for a static dataset. A concurrent insert or delete can shift later "
                    + "offset pages (duplicates or gaps) and can make totalElements disagree with items, "
                    + "because the page query and the count are two reads. "
                    + "The body keeps items, page, size, totalElements, and totalPages, and adds sort. "
                    + "A page past the end, still within the offset cap, is HTTP 200 with an empty items array."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of payments owned by the caller"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid page, size, or sort",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(
                                    name = "offset-too-large",
                                    value = "{\"type\":\"urn:problem-type:400\",\"title\":\"Validation Error\",\"status\":400,\"detail\":\"page is too large; page * size must be <= 10000\"}"
                            )
                    )
            )
    })
    public ResponseEntity<PageResponse<PaymentResponse>> listPayments(
            @Parameter(
                    description = "Zero-based page index. Default 0. Must be an integer >= 0. There is no fixed maximum on page alone: page * size must be <= 10000. page=10000 with size=1 is valid; page=101 with size=100 is 400. A product above 10000, including one past Integer.MAX_VALUE, is 400.",
                    schema = @Schema(type = "integer", minimum = "0", defaultValue = "0")
            )
            @RequestParam(defaultValue = "0") int page,
            @Parameter(
                    description = "Page size. Default 20. Must be from 1 to 100 inclusive. Other values are rejected with 400.",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20")
            )
            @RequestParam(defaultValue = "20") int size,
            @Parameter(
                    description = "Case-sensitive allowlist: createdAt,desc (default) or createdAt,asc. createdAt,DESC is 400.",
                    schema = @Schema(allowableValues = {"createdAt,desc", "createdAt,asc"}, defaultValue = "createdAt,desc")
            )
            @RequestParam(required = false) String sort,
            @Parameter(hidden = true) CurrentUser currentUser
    ) {
        PageResponse<PaymentResponse> response = listPaymentsHandler.handle(
                new ListPaymentsQuery(currentUser.userId(), page, size, sort)
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/under-review")
    @Operation(summary = "List payments held for fraud review. ADMIN or FRAUD_REVIEWER. Same page, size, and sort rules as the owner list.")
    public ResponseEntity<PageResponse<PaymentResponse>> listHeldPayments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort
    ) {
        return ResponseEntity.ok(listHeldPaymentsHandler.handle(new ListHeldPaymentsQuery(page, size, sort)));
    }

    @PostMapping("/{id}/review")
    @Operation(summary = "ADMIN or FRAUD_REVIEWER only. The reviewer cannot own the payment. APPROVE moves UNDER_REVIEW to PENDING. REJECT cancels it. Replaying the original Idempotency-Key still returns the stored 201 UNDER_REVIEW body.")
    public ResponseEntity<PaymentResponse> reviewPayment(
            @PathVariable UUID id,
            @RequestParam ReviewDecision decision,
            @Parameter(hidden = true) CurrentUser currentUser
    ) {
        PaymentResponse response = reviewPaymentHandler.handle(new ReviewPaymentCommand(id, currentUser.userId(), decision));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm a PENDING payment. A payment held UNDER_REVIEW was not approved and cannot be confirmed.")
    public ResponseEntity<PaymentResponse> confirmPayment(
            @PathVariable UUID id,
            @Parameter(hidden = true) CurrentUser currentUser
    ) {
        PaymentResponse response = confirmPaymentHandler.handle(new ConfirmPaymentCommand(id, currentUser.userId()));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a PENDING or UNDER_REVIEW payment")
    public ResponseEntity<PaymentResponse> cancelPayment(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "Customer requested cancellation") String reason,
            @Parameter(hidden = true) CurrentUser currentUser
    ) {
        PaymentResponse response = cancelPaymentHandler.handle(new CancelPaymentCommand(id, currentUser.userId(), reason));
        return ResponseEntity.ok(response);
    }
}
