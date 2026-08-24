package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.core.address.StakeAddressCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns one transaction's participating addresses into {@code address_transactions} rows.
 *
 * <p>Extracted so the live path and the ADR-039 projection path cannot disagree about what an
 * address transaction row <em>is</em>. Differential parity between two implementations of the
 * same rule is a test that can pass while both are wrong; sharing the rule makes the question
 * not arise. The same argument already applies to {@code UtxoHistoryDataset}, which both paths
 * call.
 *
 * <p>Everything here is a pure function of already-resolved inputs. The one thing that needs
 * live state — mapping a consumed outpoint back to the address that owned it — happens before
 * this class is reached, in the live resolver on one path and at capture time on the other.
 */
public final class AddressSubjectRows {

    /** How an address took part in a transaction. */
    public enum Role { INPUT, OUTPUT, COLLATERAL_INPUT, COLLATERAL_RETURN }

    /**
     * An address that participated, already resolved to its parts.
     *
     * @param addressKey          hash the archive indexes the address by
     * @param address             display form, carried only for the {@code address} subject
     * @param paymentCredential   payment credential, null when the address has none
     * @param stakeCredentialType {@code key} or {@code script}; null when there is no stake part
     * @param stakeCredential     stake credential, null when there is no stake part
     */
    public record Participant(byte[] addressKey, String address, byte[] paymentCredential,
                              String stakeCredentialType, byte[] stakeCredential) { }

    private final AddressTransactionSubjects selected;
    private final long networkMagic;
    private final Map<SubjectKey, SubjectRoles> subjects = new LinkedHashMap<>();

    public AddressSubjectRows(AddressTransactionSubjects selected, long networkMagic) {
        this.selected = selected;
        this.networkMagic = networkMagic;
    }

    /** Record one participation. Order of first appearance determines row order. */
    public void add(Participant participant, Role role) {
        if (selected.address()) {
            put(AddressTransactionSubjects.ADDRESS, participant.addressKey(),
                    participant.address(), null, role);
        }
        if (selected.paymentCredential()) {
            put(AddressTransactionSubjects.PAYMENT_CREDENTIAL, participant.paymentCredential(),
                    null, null, role);
        }
        if (selected.stakeCredential()) {
            put(AddressTransactionSubjects.STAKE_CREDENTIAL, participant.stakeCredential(), null,
                    StakeAddressCodec.encode(networkMagic, participant.stakeCredentialType(),
                            participant.stakeCredential()),
                    role);
        }
    }

    private void put(String type, byte[] key, String address, String stakeAddress, Role role) {
        if (key == null) return;
        subjects.computeIfAbsent(new SubjectKey(type, key), ignored ->
                new SubjectRoles(new AddressSubject(type, key), address, stakeAddress)).increment(role);
    }

    /** Emit the accumulated rows for one transaction, in first-appearance order. */
    public void emit(byte[] txHash, int txIndex, byte[] blockHash, long blockNumber, long slot,
                     long epoch, long blockTimeSeconds, java.util.UUID jobId, Consumer<ArchiveRow> sink) {
        for (SubjectRoles roles : subjects.values()) {
            AddressSubject subject = roles.subject;
            sink.accept(new ArchiveRow("address_transactions", Arrays.asList(subject.subjectType(),
                    subject.subjectKey(), roles.address, roles.stakeAddress, txHash, blockHash,
                    blockNumber, slot, epoch, blockTimeSeconds, txIndex, roles.inputCount,
                    roles.outputCount, roles.collateralInputCount, roles.collateralReturnCount, jobId)));
        }
    }

    /** Subjects accumulated so far; exposed for assertions and diagnostics. */
    public List<AddressSubject> subjects() {
        List<AddressSubject> result = new ArrayList<>(subjects.size());
        for (SubjectRoles roles : subjects.values()) result.add(roles.subject);
        return List.copyOf(result);
    }

    private static final class SubjectRoles {
        private final AddressSubject subject;
        private final String address;
        private final String stakeAddress;
        private int inputCount;
        private int outputCount;
        private int collateralInputCount;
        private int collateralReturnCount;

        private SubjectRoles(AddressSubject subject, String address, String stakeAddress) {
            this.subject = subject;
            this.address = address;
            this.stakeAddress = stakeAddress;
        }

        private void increment(Role role) {
            switch (role) {
                case INPUT -> inputCount++;
                case OUTPUT -> outputCount++;
                case COLLATERAL_INPUT -> collateralInputCount++;
                case COLLATERAL_RETURN -> collateralReturnCount++;
            }
        }
    }

    private record SubjectKey(String type, byte[] key) {
        private SubjectKey { key = key.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof SubjectKey that && type.equals(that.type) && Arrays.equals(key, that.key);
        }
        @Override public int hashCode() { return 31 * type.hashCode() + Arrays.hashCode(key); }
    }
}
