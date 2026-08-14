package com.bloxbean.cardano.yano.api.appchain.authmap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable, deliberately narrow genesis input supplied to one validator factory. */
public record ValidatorInitContext(
        String descriptorId,
        String providerId,
        String contractVersion,
        byte[] parameters,
        List<String> collectionIds
) {
    public static final int MAX_PARAMETERS_BYTES = 16_384;
    public static final int MAX_COLLECTIONS = 64;
    private static final int MAX_ID_LENGTH = 128;

    public ValidatorInitContext {
        descriptorId = requireText(descriptorId, "descriptorId");
        providerId = requireText(providerId, "providerId");
        contractVersion = requireText(contractVersion, "contractVersion");
        parameters = Objects.requireNonNull(parameters, "parameters").clone();
        if (parameters.length == 0 || parameters.length > MAX_PARAMETERS_BYTES) {
            throw new IllegalArgumentException(
                    "parameters must contain 1-" + MAX_PARAMETERS_BYTES + " bytes");
        }
        List<String> ids = new ArrayList<>(Objects.requireNonNull(
                collectionIds, "collectionIds"));
        if (ids.isEmpty() || ids.size() > MAX_COLLECTIONS) {
            throw new IllegalArgumentException(
                    "collectionIds must contain 1-" + MAX_COLLECTIONS + " entries");
        }
        ids.replaceAll(id -> requireText(id, "collectionIds[]"));
        if (!ids.equals(ids.stream().sorted().toList())
                || new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException(
                    "collectionIds must be unique and canonically ordered");
        }
        collectionIds = List.copyOf(ids);
    }

    @Override
    public byte[] parameters() {
        return parameters.clone();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.length() > MAX_ID_LENGTH || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field
                    + " must be 1-" + MAX_ID_LENGTH + " trimmed non-NUL characters");
        }
        return value;
    }
}
