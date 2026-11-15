package com.muse.payments.identity.domain;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenFamilyRepository {

    Optional<RefreshTokenFamily> findById(UUID familyId);

    RefreshTokenFamily save(RefreshTokenFamily family);

    void revokeFamily(UUID familyId, String reason);

    void revokeFamilyInNewTransaction(UUID familyId, String reason);
}
