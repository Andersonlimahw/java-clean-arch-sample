package com.muse.payments.shared.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
              AND next_attempt_at <= :now
            ORDER BY next_attempt_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEventEntity> findPendingForPublishing(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    /** Moves the lease of rows this transaction already locked. Runs in the caller's transaction. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox_events
            SET next_attempt_at = :leaseUntil
            WHERE id IN (:ids)
              AND published_at IS NULL
            """, nativeQuery = true)
    int claimAllUntil(@Param("ids") Collection<UUID> ids, @Param("leaseUntil") Instant leaseUntil);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox_events
            SET published_at = :publishedAt
            WHERE id IN (:ids)
              AND published_at IS NULL
            """, nativeQuery = true)
    int markPublished(@Param("ids") Collection<UUID> ids, @Param("publishedAt") Instant publishedAt);
}
