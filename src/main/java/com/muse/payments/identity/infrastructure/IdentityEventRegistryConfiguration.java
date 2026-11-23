package com.muse.payments.identity.infrastructure;

import com.muse.payments.identity.domain.FamilyRevokedEvent;
import com.muse.payments.identity.domain.FamilyStartedEvent;
import com.muse.payments.identity.domain.TokenRevokedEvent;
import com.muse.payments.identity.domain.TokenRotatedEvent;
import com.muse.payments.identity.domain.UserAnonymizationRequestedEvent;
import com.muse.payments.identity.domain.UserDisabledEvent;
import com.muse.payments.identity.domain.UserRegisteredEvent;
import com.muse.payments.shared.application.EventTypeRegistration;
import com.muse.payments.shared.application.PersonalDataFields;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
public class IdentityEventRegistryConfiguration {

    public static final String USER_STREAM_TYPE = "User";
    public static final String REFRESH_TOKEN_FAMILY_STREAM_TYPE = "RefreshTokenFamily";

    @Bean
    EventTypeRegistration userRegisteredRegistration() {
        return new EventTypeRegistration("identity.user-registered", 1, UserRegisteredEvent.class);
    }

    @Bean
    EventTypeRegistration userDisabledRegistration() {
        return new EventTypeRegistration("identity.user-disabled", 1, UserDisabledEvent.class);
    }

    @Bean
    EventTypeRegistration userAnonymizationRequestedRegistration() {
        return new EventTypeRegistration(
                "identity.user-anonymization-requested", 1, UserAnonymizationRequestedEvent.class);
    }

    @Bean
    PersonalDataFields userRegisteredPersonalData() {
        return new PersonalDataFields("identity.user-registered", "userId", Set.of("email", "displayName"));
    }

    @Bean
    EventTypeRegistration familyStartedRegistration() {
        return new EventTypeRegistration("identity.family-started", 1, FamilyStartedEvent.class);
    }

    @Bean
    EventTypeRegistration tokenRotatedRegistration() {
        return new EventTypeRegistration("identity.token-rotated", 1, TokenRotatedEvent.class);
    }

    @Bean
    EventTypeRegistration tokenRevokedRegistration() {
        return new EventTypeRegistration("identity.token-revoked", 1, TokenRevokedEvent.class);
    }

    @Bean
    EventTypeRegistration familyRevokedRegistration() {
        return new EventTypeRegistration("identity.family-revoked", 1, FamilyRevokedEvent.class);
    }
}
