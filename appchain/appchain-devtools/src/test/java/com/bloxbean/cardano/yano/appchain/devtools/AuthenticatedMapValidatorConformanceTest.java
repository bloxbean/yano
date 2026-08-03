package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidator;
import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidatorFactory;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorInitContext;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorVerdict;
import com.bloxbean.cardano.yano.appchain.devtools.AuthenticatedMapValidatorConformance.Report;
import com.bloxbean.cardano.yano.appchain.devtools.AuthenticatedMapValidatorConformance.ValidationCase;
import com.bloxbean.cardano.yano.appchain.devtools.fixture.ClassLoaderSensitiveValidatorFactory;
import com.bloxbean.cardano.yano.appchain.authmap.validators.Gs1GtinValidatorFactory;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedMapValidatorConformanceTest {

    @Test
    void referenceValidatorPassesTheFullGateAndIsolatedLoaders(@TempDir Path temporary)
            throws Exception {
        ValidatorInitContext context = context(Gs1GtinValidatorFactory.ID);
        List<ValidationCase> corpus = gtinCorpus();

        Report ordinary = AuthenticatedMapValidatorConformance.verify(
                new Gs1GtinValidatorFactory(), context, corpus);
        assertThat(ordinary.passed()).isTrue();
        assertThat(ordinary.violations()).isEmpty();
        assertThat(ordinary.invocations()).isEqualTo(corpus.size() * 5L);

        Path optional = Files.createDirectory(temporary.resolve("optional"));
        Files.writeString(optional.resolve("unrelated-marker"), "present");
        URL providerClasses = Gs1GtinValidatorFactory.class.getProtectionDomain()
                .getCodeSource().getLocation();
        Report isolated = AuthenticatedMapValidatorConformance.verifyIsolated(
                Gs1GtinValidatorFactory.class.getName(),
                "com.bloxbean.cardano.yano.appchain.authmap.validators",
                List.of(providerClasses), List.of(optional.toUri().toURL()), List.of(),
                context, corpus);
        assertThat(isolated.passed()).isTrue();
    }

    @Test
    void deliberatelyNondeterministicValidatorIsRejected() {
        AtomicInteger calls = new AtomicInteger();
        AuthenticatedMapValueValidatorFactory factory = factory("toggle-v1",
                context -> (collection, key, value) -> calls.getAndIncrement() % 2 == 0
                        ? ValidatorVerdict.ACCEPT : ValidatorVerdict.REJECT);

        Report report = AuthenticatedMapValidatorConformance.verify(
                factory, context("toggle-v1"), gtinCorpus());

        assertThat(report.passed()).isFalse();
        assertThat(report.violations()).isNotEmpty();
    }

    @Test
    void differingAmbientClassLoaderInputsAreRejected(@TempDir Path temporary)
            throws Exception {
        Path withMarker = Files.createDirectory(temporary.resolve("with-marker"));
        Files.writeString(withMarker.resolve("ambient-validator-flag"), "present");
        Path withoutMarker = Files.createDirectory(temporary.resolve("without-marker"));
        URL fixtureClasses = ClassLoaderSensitiveValidatorFactory.class.getProtectionDomain()
                .getCodeSource().getLocation();

        Report report = AuthenticatedMapValidatorConformance.verifyIsolated(
                ClassLoaderSensitiveValidatorFactory.class.getName(),
                "com.bloxbean.cardano.yano.appchain.devtools.fixture",
                List.of(fixtureClasses), List.of(withMarker.toUri().toURL()),
                List.of(withoutMarker.toUri().toURL()),
                context(ClassLoaderSensitiveValidatorFactory.ID), gtinCorpus());

        assertThat(report.passed()).isFalse();
        assertThat(report.violations()).anyMatch(value ->
                value.contains("loader-2") || value.contains("different verdict vectors"));
    }

    private static AuthenticatedMapValueValidatorFactory factory(
            String id,
            java.util.function.Function<ValidatorInitContext,
                    AuthenticatedMapValueValidator> creator
    ) {
        return new AuthenticatedMapValueValidatorFactory() {
            @Override public String id() { return id; }
            @Override public String contractVersion() {
                return AuthenticatedMapContract.VALIDATOR_SPI_CONTRACT_VERSION;
            }
            @Override public AuthenticatedMapValueValidator create(ValidatorInitContext context) {
                return creator.apply(context);
            }
        };
    }

    private static ValidatorInitContext context(String providerId) {
        return new ValidatorInitContext("product-id", providerId,
                AuthenticatedMapContract.VALIDATOR_SPI_CONTRACT_VERSION,
                new byte[]{(byte) 0xa0}, List.of("products"));
    }

    private static List<ValidationCase> gtinCorpus() {
        List<ValidationCase> cases = new ArrayList<>();
        cases.add(gtin("gtin-8", "95012346", ValidatorVerdict.ACCEPT));
        cases.add(gtin("gtin-12", "012345678905", ValidatorVerdict.ACCEPT));
        cases.add(gtin("gtin-13", "4006381333931", ValidatorVerdict.ACCEPT));
        cases.add(gtin("gtin-14", "00012345600012", ValidatorVerdict.ACCEPT));
        cases.add(gtin("bad-check", "95012345", ValidatorVerdict.REJECT));
        cases.add(new ValidationCase("truncated", "products",
                ascii("95012346"), new byte[]{0x68, '9'}, ValidatorVerdict.REJECT));
        cases.add(new ValidationCase("non-canonical", "products", ascii("95012346"),
                concat(new byte[]{0x78, 0x08}, ascii("95012346")), ValidatorVerdict.REJECT));
        cases.add(new ValidationCase("key-mismatch", "products", ascii("95012346"),
                cborText("95012347"), ValidatorVerdict.REJECT));
        cases.add(new ValidationCase("empty", "products", new byte[0], new byte[0],
                ValidatorVerdict.REJECT));
        return List.copyOf(cases);
    }

    private static ValidationCase gtin(String name, String value, ValidatorVerdict expected) {
        return new ValidationCase(name, "products", ascii(value), cborText(value), expected);
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
