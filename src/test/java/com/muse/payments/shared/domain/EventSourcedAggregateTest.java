package com.muse.payments.shared.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventSourcedAggregateTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-15T12:00:00Z");

    @Test
    void rehydratesByApplyingEventsInOrderWithoutMarkingThemUncommitted() {
        UUID accountId = UUID.randomUUID();
        AccountAggregate account = EventSourcedAggregate.rehydrate(AccountAggregate::new, List.of(
                new AccountOpened(accountId, 10, UUID.randomUUID(), OCCURRED_AT),
                new AccountCredited(accountId, 5, UUID.randomUUID(), OCCURRED_AT)));

        assertThat(account.getId()).isEqualTo(accountId);
        assertThat(account.balance()).isEqualTo(15);
        assertThat(account.version()).isEqualTo(2);
        assertThat(account.peekDomainEvents()).isEmpty();

        account.credit(3);
        assertThat(account.version()).isEqualTo(3);
        assertThat(account.pullDomainEvents()).containsExactly(
                new AccountCredited(accountId, 3, account.peekLastEventId(), OCCURRED_AT));
    }

    @Test
    void refusesToRehydrateAnAggregateThatAlreadyHasStateOrPendingEvents() {
        AccountAggregate aggregate = new AccountAggregate();
        UUID accountId = UUID.randomUUID();
        aggregate.open(accountId, 1);

        assertThatThrownBy(() -> EventSourcedAggregate.rehydrate(() -> aggregate, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Rehydration requires a new aggregate");
    }

    private static final class AccountAggregate extends EventSourcedAggregate<UUID> {

        private UUID id;
        private int balance;
        private UUID lastEventId;

        void open(UUID accountId, int openingBalance) {
            raise(new AccountOpened(accountId, openingBalance, UUID.randomUUID(), OCCURRED_AT));
        }

        void credit(int amount) {
            raise(new AccountCredited(id, amount, UUID.randomUUID(), OCCURRED_AT));
        }

        int balance() {
            return balance;
        }

        UUID peekLastEventId() {
            return lastEventId;
        }

        @Override
        protected void apply(DomainEvent event) {
            if (event instanceof AccountOpened opened) {
                id = opened.accountId();
                balance = opened.openingBalance();
            } else if (event instanceof AccountCredited credited) {
                if (!credited.accountId().equals(id)) {
                    throw new IllegalArgumentException("Event belongs to another account");
                }
                balance += credited.amount();
            } else {
                throw new IllegalArgumentException("Unsupported event " + event.getClass().getName());
            }
            lastEventId = event.eventId();
        }

        @Override
        public UUID getId() {
            return id;
        }
    }

    private record AccountOpened(UUID accountId, int openingBalance, UUID eventId, Instant occurredAt)
            implements DomainEvent {
    }

    private record AccountCredited(UUID accountId, int amount, UUID eventId, Instant occurredAt)
            implements DomainEvent {
    }
}
