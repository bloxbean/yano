package com.bloxbean.cardano.yano.api.appchain.authmap;

/**
 * Pure, state-free predicate for one authenticated-map key/value pair.
 *
 * <p>Implementations are trusted consensus code. They must be total over all
 * bounded byte inputs, deterministic, thread-safe, and free from ambient I/O,
 * time, randomness, locale, and configuration dependencies.</p>
 */
@FunctionalInterface
public interface AuthenticatedMapValueValidator {

    /** Returns the deterministic verdict for exactly these immutable inputs. */
    ValidatorVerdict validate(String collectionId, byte[] applicationKey, byte[] value);
}
