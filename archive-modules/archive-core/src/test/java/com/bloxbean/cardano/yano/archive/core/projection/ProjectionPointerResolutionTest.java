package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource.PointerCoordinate;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource.PointerCredential;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryFact;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectionPointerResolutionTest {

    private static final String CREDENTIAL = "77".repeat(28);

    @Test
    void registrationThenDeregistrationInOneBlockLeavesTheCoordinateUnresolved() {
        UtxoHistoryFact fact = fact(
                List.of(registration(100, 0, 0)),
                List.of(deregistration(1, 0)),
                pointerAddress(100, 0, 0));

        assertUnresolved(resolve(fact, 100, new TestSource()));
    }

    @Test
    void sameBlockReRegistrationLeavesTheOldCoordinateDead() {
        TestSource source = new TestSource().register(50, 0, 0);
        UtxoHistoryFact fact = fact(
                List.of(registration(100, 2, 0)),
                List.of(deregistration(1, 0)),
                pointerAddress(50, 0, 0));

        assertUnresolved(resolve(fact, 100, source));
    }

    @Test
    void sameBlockReRegistrationResolvesTheNewCoordinate() {
        TestSource source = new TestSource().register(50, 0, 0);
        UtxoHistoryFact fact = fact(
                List.of(registration(100, 2, 0)),
                List.of(deregistration(1, 0)),
                pointerAddress(100, 2, 0));

        assertResolved(resolve(fact, 100, source));
    }

    @Test
    void crossBlockReRegistrationDoesNotReactivateTheOldCoordinate() {
        TestSource source = new TestSource()
                .register(50, 0, 0)
                .deregister(75, 0, 0)
                .register(90, 0, 0);

        assertUnresolved(resolve(fact(List.of(), List.of(), pointerAddress(50, 0, 0)), 200, source));
        assertResolved(resolve(fact(List.of(), List.of(), pointerAddress(90, 0, 0)), 200, source));
    }

    @Test
    void replayAgainstAdvancedStateReproducesTheCaptureTimeAnswer() {
        TestSource atCapture = new TestSource().register(50, 0, 0);
        TestSource advanced = new TestSource()
                .register(50, 0, 0)
                .deregister(5_000, 0, 0)
                .register(6_000, 0, 0);
        UtxoHistoryFact fact = fact(List.of(), List.of(), pointerAddress(50, 0, 0));

        UtxoHistoryFact.Address captured = resolve(fact, 100, atCapture);
        UtxoHistoryFact.Address replayed = resolve(fact, 100, advanced);

        assertThat(replayed.stakeReferenceType()).isEqualTo(captured.stakeReferenceType());
        assertThat(replayed.stakeCredentialType()).isEqualTo(captured.stakeCredentialType());
        assertThat(replayed.stakeCredential()).isEqualTo(captured.stakeCredential());
    }

    @Test
    void resolutionIsDeterministicAndIdempotent() {
        TestSource source = new TestSource().register(50, 0, 0).deregister(75, 0, 0);
        UtxoHistoryFact fact = fact(List.of(), List.of(), pointerAddress(50, 0, 0));
        UtxoHistoryFact first = ProjectionPointerResolution.resolve(fact, 100, source);
        UtxoHistoryFact.Address firstAddress = first.newAddresses().getFirst();

        for (int i = 0; i < 10; i++) {
            assertThat(resolve(fact, 100, source))
                    .usingRecursiveComparison()
                    .isEqualTo(firstAddress);
        }

        assertThat(ProjectionPointerResolution.resolve(first, 100, source)).isSameAs(first);
    }

    @Test
    void aFutureRegistrationCoordinateStaysUnresolved() {
        TestSource source = new TestSource().register(9_000, 0, 0);
        UtxoHistoryFact fact = fact(List.of(), List.of(), pointerAddress(9_000, 0, 0));

        assertUnresolved(resolve(fact, 100, source));
    }

    private static UtxoHistoryFact.Address resolve(
            UtxoHistoryFact fact, long blockSlot, PointerCredentialSource source) {
        return ProjectionPointerResolution.resolve(fact, blockSlot, source)
                .newAddresses().getFirst();
    }

    private static void assertResolved(UtxoHistoryFact.Address address) {
        assertThat(address.stakeReferenceType()).isEqualTo(ProjectionPointerResolution.RESOLVED);
        assertThat(address.stakeCredentialType()).isEqualTo("key");
        assertThat(address.stakeCredential()).isEqualTo(HexUtil.decodeHexString(CREDENTIAL));
    }

    private static void assertUnresolved(UtxoHistoryFact.Address address) {
        assertThat(address.stakeReferenceType()).isEqualTo(ProjectionPointerResolution.UNRESOLVED);
        assertThat(address.stakeCredentialType()).isNull();
        assertThat(address.stakeCredential()).isNull();
    }

    private static UtxoHistoryFact fact(
            List<UtxoHistoryFact.PointerRegistration> registrations,
            List<UtxoHistoryFact.PointerDeregistration> deregistrations,
            UtxoHistoryFact.Address address) {
        return new UtxoHistoryFact(Era.Babbage.getValue(), registrations, deregistrations,
                List.of(address), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static UtxoHistoryFact.PointerRegistration registration(long slot, int tx, int cert) {
        return new UtxoHistoryFact.PointerRegistration(
                slot, tx, cert, "key", HexUtil.decodeHexString(CREDENTIAL));
    }

    private static UtxoHistoryFact.PointerDeregistration deregistration(int tx, int cert) {
        return new UtxoHistoryFact.PointerDeregistration(
                tx, cert, "key", HexUtil.decodeHexString(CREDENTIAL));
    }

    private static UtxoHistoryFact.Address pointerAddress(long slot, int tx, int cert) {
        return new UtxoHistoryFact.Address(new byte[]{1}, new byte[]{2}, "addr_test_pointer",
                0, "ptr", "key", new byte[]{3}, ProjectionPointerResolution.POINTER,
                null, null, slot, tx, cert);
    }

    private static final class TestSource implements PointerCredentialSource {
        private final Map<PointerCoordinate, PointerCredential> registrations = new HashMap<>();
        private final List<Deregistration> deregistrations = new ArrayList<>();

        TestSource register(long slot, int tx, int cert) {
            registrations.put(new PointerCoordinate(slot, tx, cert), credential());
            return this;
        }

        TestSource deregister(long slot, int tx, int cert) {
            deregistrations.add(new Deregistration(new PointerCoordinate(slot, tx, cert), credential()));
            return this;
        }

        @Override
        public Optional<PointerCredential> registrationAt(PointerCoordinate coordinate) {
            return Optional.ofNullable(registrations.get(coordinate));
        }

        @Override
        public boolean deregisteredWithin(
                PointerCredential credential, PointerCoordinate after, PointerCoordinate through) {
            return deregistrations.stream().anyMatch(deregistration ->
                    deregistration.credential().equals(credential)
                            && deregistration.coordinate().compareTo(after) > 0
                            && deregistration.coordinate().compareTo(through) <= 0);
        }

        @Override
        public IndexCompleteness completeness() {
            return IndexCompleteness.COMPLETE;
        }

        private static PointerCredential credential() {
            return new PointerCredential(0, CREDENTIAL);
        }

        private record Deregistration(PointerCoordinate coordinate, PointerCredential credential) { }
    }
}
