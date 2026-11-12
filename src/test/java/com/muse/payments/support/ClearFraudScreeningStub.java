package com.muse.payments.support;

import com.muse.payments.payments.application.FraudScreeningPort;
import com.muse.payments.payments.application.FraudScreeningRequest;
import com.muse.payments.payments.application.FraudScreeningResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Full-context tests do not run a fraud provider. This stub keeps payment creation on the
 * previous cleared path. It is not the production fallback: the HTTP adapter returns
 * UNDER_REVIEW when the provider does not answer, and it never clears a failed call.
 */
@Component
@Primary
public class ClearFraudScreeningStub implements FraudScreeningPort {

    @Override
    public FraudScreeningResult screen(FraudScreeningRequest request) {
        return FraudScreeningResult.clear();
    }
}
