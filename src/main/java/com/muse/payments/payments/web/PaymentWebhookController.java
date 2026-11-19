package com.muse.payments.payments.web;

import com.muse.payments.payments.application.PaymentWebhookResponse;
import com.muse.payments.payments.application.ProcessProviderWebhookUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Signed PSP callbacks. The route is not behind JWT: the provider signature is the
 * credential. The body is read as bytes because the provider signs the exact bytes.
 */
@RestController
@RequestMapping("/api/v1/webhooks/payments")
@Tag(name = "Payment webhooks", description = "Signed PSP callbacks. Authenticated by the provider signature, not by a bearer token.")
public class PaymentWebhookController {

    private final ProcessProviderWebhookUseCase processWebhook;

    public PaymentWebhookController(ProcessProviderWebhookUseCase processWebhook) {
        this.processWebhook = Objects.requireNonNull(processWebhook);
    }

    @PostMapping(
            path = "/{provider}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Receive a signed payment callback",
            description = "Verifies the provider signature, deduplicates by provider event id, and settles the payment. "
                    + "A known but unmappable event is acknowledged as IGNORED; a duplicate delivery is DUPLICATE. "
                    + "Signature, timestamp, or payload problems are 400 and unknown providers are 404."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Delivery accepted, duplicate, or ignored"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid signature, stale timestamp, or malformed payload",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Provider is not registered",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public ResponseEntity<PaymentWebhookResponse> receive(
            @Parameter(description = "Registered provider id, for example stripe")
            @PathVariable String provider,
            @RequestBody(required = false) byte[] body,
            @RequestHeader HttpHeaders headers
    ) {
        byte[] rawBody = body == null ? new byte[0] : body;
        return ResponseEntity.ok(processWebhook.process(provider, rawBody, headers.toSingleValueMap()));
    }
}
