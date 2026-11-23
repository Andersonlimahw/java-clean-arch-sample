package com.muse.payments.shared.infrastructure.security;

import com.muse.payments.shared.application.PersonalDataKeyPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-user data keys in {@code user_crypto_keys}, wrapped by the KEK (ADR 0020). Keys are never
 * cached, so a destroyed key cannot be served from memory by this or another instance.
 */
@Repository
public class UserCryptoKeyAdapter implements SubjectKeyStore, PersonalDataKeyPort {

    private static final Logger log = LoggerFactory.getLogger(UserCryptoKeyAdapter.class);
    private static final String TOMBSTONE_KEK_ID = "none";

    private static final String SELECT = """
            SELECT encrypted_data_key, kek_id, destroyed_at
            FROM user_crypto_keys
            WHERE user_id = ?
            """;
    private static final String SELECT_FOR_SHARE = SELECT + " FOR SHARE";
    private static final String SELECT_FOR_UPDATE = SELECT + " FOR UPDATE";
    private static final String INSERT_KEY = """
            INSERT INTO user_crypto_keys (user_id, encrypted_data_key, kek_id, algorithm)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (user_id) DO NOTHING
            """;
    private static final String INSERT_TOMBSTONE = """
            INSERT INTO user_crypto_keys (user_id, encrypted_data_key, kek_id, algorithm, destroyed_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (user_id) DO NOTHING
            """;
    private static final String DESTROY = """
            UPDATE user_crypto_keys
            SET encrypted_data_key = ?, destroyed_at = ?
            WHERE user_id = ? AND destroyed_at IS NULL
            """;

    private static final RowMapper<KeyRow> ROW_MAPPER = (rs, rowNum) -> new KeyRow(
            rs.getBytes("encrypted_data_key"),
            rs.getString("kek_id"),
            rs.getTimestamp("destroyed_at") != null);

    private final JdbcTemplate jdbcTemplate;
    private final KeyEncryptionKeys keks;
    private final AesGcmCryptoService crypto;
    private final AtomicBoolean unknownKekWarned = new AtomicBoolean();

    public UserCryptoKeyAdapter(JdbcTemplate jdbcTemplate, KeyEncryptionKeys keks, AesGcmCryptoService crypto) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.keks = Objects.requireNonNull(keks);
        this.crypto = Objects.requireNonNull(crypto);
    }

    /**
     * Runs in the append transaction. {@code FOR SHARE} makes a concurrent erasure wait until this
     * append commits, so the erasure's read-model sanitization sees what this append projected.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<byte[]> keyForWrite(UUID subjectId) {
        Objects.requireNonNull(subjectId, "subjectId is required");
        Optional<KeyRow> row = find(SELECT_FOR_SHARE, subjectId);
        if (row.isEmpty()) {
            byte[] dataKey = crypto.newKey();
            jdbcTemplate.update(INSERT_KEY, subjectId,
                    crypto.encrypt(keks.active(), dataKey, wrapAad(subjectId, keks.activeId())),
                    keks.activeId(), AesGcmCryptoService.ALGORITHM);
            row = find(SELECT_FOR_SHARE, subjectId);
        }
        return unwrap(subjectId, row.orElseThrow(() -> new IllegalStateException("Data key row vanished")));
    }

    @Override
    public Optional<byte[]> keyForRead(UUID subjectId) {
        Objects.requireNonNull(subjectId, "subjectId is required");
        return find(SELECT, subjectId).flatMap(row -> unwrap(subjectId, row));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean destroy(UUID subjectId) {
        Objects.requireNonNull(subjectId, "subjectId is required");
        Timestamp now = Timestamp.from(Instant.now());
        Optional<KeyRow> row = find(SELECT_FOR_UPDATE, subjectId);
        if (row.isPresent()) {
            return !row.get().destroyed() && jdbcTemplate.update(DESTROY, new byte[0], now, subjectId) == 1;
        }
        // No key yet: leave a tombstone so no later append can create one for this subject.
        if (jdbcTemplate.update(INSERT_TOMBSTONE, subjectId, new byte[0], TOMBSTONE_KEK_ID,
                AesGcmCryptoService.ALGORITHM, now) == 1) {
            return true;
        }
        return destroy(subjectId);
    }

    private Optional<KeyRow> find(String sql, UUID subjectId) {
        List<KeyRow> rows = jdbcTemplate.query(sql, ROW_MAPPER, subjectId);
        return rows.stream().findFirst();
    }

    private Optional<byte[]> unwrap(UUID subjectId, KeyRow row) {
        if (row.destroyed()) {
            return Optional.empty();
        }
        Optional<byte[]> kek = keks.find(row.kekId());
        if (kek.isEmpty()) {
            if (keks.ephemeral()) {
                if (unknownKekWarned.compareAndSet(false, true)) {
                    log.warn("A data key is wrapped by KEK '{}', which this JVM does not have (ephemeral KEK '{}'). "
                            + "Its personal data reads as redacted.", row.kekId(), keks.activeId());
                }
                return Optional.empty();
            }
            throw new IllegalStateException("A data key is wrapped by unknown KEK '" + row.kekId()
                    + "'. Configure it as LGPD_KEK_BASE64 or LGPD_PREVIOUS_KEK_BASE64.");
        }
        try {
            return Optional.of(crypto.decrypt(kek.get(), row.encryptedKey(), wrapAad(subjectId, row.kekId())));
        } catch (AesGcmCryptoService.CryptoIntegrityException e) {
            throw new IllegalStateException("A data key failed its integrity check under KEK '" + row.kekId() + "'");
        }
    }

    private static byte[] wrapAad(UUID subjectId, String kekId) {
        return ("lgpd-dek|" + subjectId + "|" + kekId).getBytes(StandardCharsets.UTF_8);
    }

    private record KeyRow(byte[] encryptedKey, String kekId, boolean destroyed) {
    }
}
