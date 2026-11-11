package com.muse.payments.shared.application;

public interface QueryHandler<Q extends Query<R>, R> {
    R handle(Q query);
}
