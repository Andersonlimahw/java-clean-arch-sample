package com.muse.payments.payments.infrastructure.fraud;

import io.micrometer.core.instrument.MeterRegistry;

final class ResilienceMeters {

    private ResilienceMeters() {
    }

    static double count(MeterRegistry registry, String metric, String kind) {
        var tagged = registry.find(metric).tag("name", HttpFraudScreeningAdapter.INSTANCE_NAME).tag("kind", kind);
        if (tagged.functionCounter() != null) {
            return tagged.functionCounter().count();
        }
        if (tagged.counter() != null) {
            return tagged.counter().count();
        }
        if (tagged.timer() != null) {
            return tagged.timer().count();
        }
        return 0d;
    }
}
