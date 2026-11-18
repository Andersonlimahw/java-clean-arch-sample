package com.muse.payments.payments.infrastructure.psp.stripe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.payments.domain.psp.PaymentProviderStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Arrays;

@Configuration
@EnableConfigurationProperties(StripeProviderProperties.class)
public class StripeProviderConfiguration {

    @Bean
    @ConditionalOnProperty(name = "app.psp.stripe.enabled", havingValue = "true", matchIfMissing = true)
    PaymentProviderStrategy stripePaymentProvider(
            StripeProviderProperties properties,
            ObjectMapper objectMapper,
            Environment environment
    ) {
        if (isProd(environment) && StripeProviderProperties.DEV_SECRET.equals(properties.webhookSecret())) {
            throw new IllegalStateException(
                    "STRIPE_WEBHOOK_SECRET must be set to the endpoint secret when the prod profile is active");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
        return new StripePaymentProviderAdapter(
                restClient,
                objectMapper,
                properties.apiKey(),
                properties.webhookSecret(),
                properties.webhookTolerance(),
                Clock.systemUTC());
    }

    private static boolean isProd(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equals);
    }
}
