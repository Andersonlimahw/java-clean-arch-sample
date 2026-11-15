package com.muse.payments.shared.infrastructure;

import com.muse.payments.shared.application.EventMetadata;
import com.muse.payments.shared.security.CurrentUser;
import com.muse.payments.shared.web.CorrelationContext;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class EventMetadataProvider {

    public EventMetadata current() {
        return current(resolveActorId());
    }

    public EventMetadata current(String actorId) {
        String correlationId = CorrelationContext.get();
        String traceparent = MDC.get("traceparent");
        return new EventMetadata(correlationId, null, traceparent, actorId);
    }

    private static String resolveActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof CurrentUser currentUser) {
            return currentUser.userId().toString();
        }
        return auth.getName();
    }
}
