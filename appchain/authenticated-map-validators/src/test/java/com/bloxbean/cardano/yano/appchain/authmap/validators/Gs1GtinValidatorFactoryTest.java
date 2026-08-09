package com.bloxbean.cardano.yano.appchain.authmap.validators;

import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidator;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorInitContext;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorVerdict;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Gs1GtinValidatorFactoryTest {

    @Test
    void acceptsSupportedGs1IdentifiersBoundToTheirKeys() {
        AuthenticatedMapValueValidator validator = factory();

        assertAccepted(validator, "95012346");
        assertAccepted(validator, "012345678905");
        assertAccepted(validator, "4006381333931");
        assertAccepted(validator, "00012345600012");
    }

    @Test
    void rejectsBadChecksKeysAndEncodingsWithoutThrowing() {
        AuthenticatedMapValueValidator validator = factory();

        assertRejected(validator, "95012345", cborText("95012345"));
        assertRejected(validator, "95012346", cborText("95012347"));
        assertRejected(validator, "95012346", new byte[]{0x68, '9'});
        assertRejected(validator, "95012346", concat(
                new byte[]{0x78, 0x08}, ascii("95012346")));
        assertThat(validator.validate("products", new byte[0], new byte[0]))
                .isEqualTo(ValidatorVerdict.REJECT);
    }

    @Test
    void factoryAcceptsOnlyItsFrozenEmptyParameterContract() {
        Gs1GtinValidatorFactory factory = new Gs1GtinValidatorFactory();
        assertThatThrownBy(() -> factory.create(new ValidatorInitContext(
                "gtin", Gs1GtinValidatorFactory.ID,
                AuthenticatedMapContract.VALIDATOR_SPI_CONTRACT_VERSION,
                new byte[]{(byte) 0xa1, 0x61, 0x78, 0x01}, List.of("products"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty parameters");
    }

    @Test
    void createsAnIndependentValidatorForEveryConfiguredChain() {
        assertThat(factory()).isNotSameAs(factory());
    }

    private static AuthenticatedMapValueValidator factory() {
        return new Gs1GtinValidatorFactory().create(new ValidatorInitContext(
                "gtin", Gs1GtinValidatorFactory.ID,
                AuthenticatedMapContract.VALIDATOR_SPI_CONTRACT_VERSION,
                new byte[]{(byte) 0xa0}, List.of("products")));
    }

    private static void assertAccepted(
            AuthenticatedMapValueValidator validator,
            String value
    ) {
        assertThat(validator.validate("products", ascii(value), cborText(value)))
                .isEqualTo(ValidatorVerdict.ACCEPT);
    }

    private static void assertRejected(
            AuthenticatedMapValueValidator validator,
            String key,
            byte[] value
    ) {
        assertThat(validator.validate("products", ascii(key), value))
                .isEqualTo(ValidatorVerdict.REJECT);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] cborText(String value) {
        byte[] text = ascii(value);
        return concat(new byte[]{(byte) (0x60 | text.length)}, text);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
