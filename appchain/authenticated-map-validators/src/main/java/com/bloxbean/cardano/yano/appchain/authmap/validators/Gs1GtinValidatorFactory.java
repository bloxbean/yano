package com.bloxbean.cardano.yano.appchain.authmap.validators;

import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidator;
import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidatorFactory;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorInitContext;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorVerdict;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.util.Arrays;

/**
 * First-party reference validator for GS1 GTIN-8, GTIN-12, GTIN-13, and
 * GTIN-14 identifiers.
 *
 * <p>The application key is the ASCII GTIN. The value is the same identifier
 * encoded as one canonical CBOR text string. The final digit must satisfy the
 * GS1 Mod-10 check-digit algorithm. Parameters are the canonical empty map.</p>
 */
public final class Gs1GtinValidatorFactory
        implements AuthenticatedMapValueValidatorFactory {
    public static final String ID = "gs1-gtin-v1";
    private static final byte[] EMPTY_PARAMETERS = {(byte) 0xa0};

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String contractVersion() {
        return AuthenticatedMapContract.VALIDATOR_SPI_CONTRACT_VERSION;
    }

    @Override
    public AuthenticatedMapValueValidator create(ValidatorInitContext context) {
        if (!ID.equals(context.providerId())
                || !contractVersion().equals(context.contractVersion())
                || !Arrays.equals(EMPTY_PARAMETERS, context.parameters())) {
            throw new IllegalArgumentException(
                    "gs1-gtin-v1 requires its exact provider, contract, and empty parameters");
        }
        // A factory invocation belongs to one configured chain. Do not return a
        // non-capturing method-reference here: the JVM may cache it as a singleton,
        // which would leak the same plugin product across MPF/JMT chain instances.
        return new Gs1GtinValidator();
    }

    private static final class Gs1GtinValidator
            implements AuthenticatedMapValueValidator {
        @Override
        public ValidatorVerdict validate(
                String collectionId,
                byte[] applicationKey,
                byte[] value
        ) {
            return validateGtin(collectionId, applicationKey, value);
        }
    }

    private static ValidatorVerdict validateGtin(
            String collectionId,
            byte[] applicationKey,
            byte[] value
    ) {
        if (collectionId == null || applicationKey == null || value == null
                || !supportedLength(applicationKey.length)
                || value.length != applicationKey.length + 1
                || value[0] != (byte) (0x60 | applicationKey.length)) {
            return ValidatorVerdict.REJECT;
        }
        for (int index = 0; index < applicationKey.length; index++) {
            byte digit = applicationKey[index];
            if (digit < '0' || digit > '9' || value[index + 1] != digit) {
                return ValidatorVerdict.REJECT;
            }
        }
        int sum = 0;
        boolean timesThree = true;
        for (int index = applicationKey.length - 2; index >= 0; index--) {
            int digit = applicationKey[index] - '0';
            sum += timesThree ? digit * 3 : digit;
            timesThree = !timesThree;
        }
        int expected = (10 - sum % 10) % 10;
        return applicationKey[applicationKey.length - 1] - '0' == expected
                ? ValidatorVerdict.ACCEPT : ValidatorVerdict.REJECT;
    }

    private static boolean supportedLength(int length) {
        return length == 8 || length == 12 || length == 13 || length == 14;
    }
}
