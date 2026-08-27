package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource.PointerCoordinate;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource.PointerCredential;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryFact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves pointer-address stake references at <em>capture</em> time, as of the block being
 * projected, so the sink needs no resolver state and can never fail for lack of it.
 *
 * <p>A pointer is valid at block B only if its registration coordinate R still has an active
 * credential there. The Shelley ledger drops a credential's {@code ptrs} entries when it is
 * deregistered, so the deciding interval is:
 *
 * <pre>R  &lt;  deregistration coordinate  &lt;=  end of block B</pre>
 *
 * <p>Two sources are consulted, and the order matters:
 *
 * <ol>
 *   <li>an <strong>in-block overlay</strong> of this block's own certificates, because the
 *       authoritative store may not have applied them yet when this contributor runs —
 *       depending on listener order would be fragile;</li>
 *   <li>the authoritative append-only records, for everything from earlier blocks.</li>
 * </ol>
 *
 * <p>Critically, the overlay is consulted for <em>deregistrations</em> even when the
 * registration came from the authoritative source. Otherwise a coordinate registered in an
 * earlier block and deregistered in <em>this</em> one would fall through and wrongly resolve.
 *
 * <p>Resolution never consults a coordinate newer than the block being projected. A pointer
 * address can encode arbitrary numbers, and without that bound a coordinate registered later
 * in the chain would resolve on replay while being unresolvable on the first pass.
 */
public final class ProjectionPointerResolution {

    private static final int CONWAY_ERA = com.bloxbean.cardano.yaci.core.model.Era.Conway.getValue();

    static final String POINTER = "pointer";
    static final String RESOLVED = "pointer_resolved";
    static final String UNRESOLVED = "pointer_unresolved";
    static final String NOT_EFFECTIVE = "pointer_not_effective";

    private ProjectionPointerResolution() {}

    /** One block's certificates, ordered, ready to answer registration and deregistration. */
    private record Overlay(Map<PointerCoordinate, PointerCredential> registrations,
                           List<Deregistration> deregistrations) {
        boolean deregisteredWithin(PointerCredential credential, PointerCoordinate after,
                                   PointerCoordinate through) {
            for (Deregistration d : deregistrations) {
                if (!d.credential().equals(credential)) continue;
                if (d.at().compareTo(after) > 0 && d.at().compareTo(through) <= 0) return true;
            }
            return false;
        }
    }

    private record Deregistration(PointerCoordinate at, PointerCredential credential) { }

    public static UtxoHistoryFact resolve(UtxoHistoryFact fact, long blockSlot,
                                          PointerCredentialSource source) {
        Objects.requireNonNull(fact, "fact");
        Objects.requireNonNull(source, "source");

        boolean anyPointer = fact.newAddresses().stream()
                .anyMatch(address -> POINTER.equals(address.stakeReferenceType()));
        if (!anyPointer) return fact;

        Overlay overlay = buildOverlay(fact);
        PointerCoordinate blockEnd = PointerCoordinate.endOfBlock(blockSlot);

        List<UtxoHistoryFact.Address> resolved = new ArrayList<>(fact.newAddresses().size());
        for (UtxoHistoryFact.Address address : fact.newAddresses()) {
            resolved.add(resolveAddress(address, fact.era(), blockSlot, blockEnd, overlay, source));
        }
        return new UtxoHistoryFact(fact.era(), fact.pointerRegistrations(), fact.pointerDeregistrations(),
                resolved, fact.outputs(), fact.assets(), fact.inputs(), fact.transactionDatums(),
                fact.transactionRedeemers());
    }

    /**
     * Collect this block's certificates in canonical order.
     *
     * <p>Registrations are kept by coordinate and deregistrations as an ordered list rather
     * than being collapsed. Collapsing would lose the ordering needed for
     * register / deregister / re-register within one block, where the later registration must
     * produce a <em>new</em> valid coordinate while the earlier one stays dead.
     */
    private static Overlay buildOverlay(UtxoHistoryFact fact) {
        record Event(int txIndex, int certIndex, UtxoHistoryFact.PointerRegistration registration,
                     UtxoHistoryFact.PointerDeregistration deregistration) { }
        List<Event> events = new ArrayList<>();
        fact.pointerRegistrations().forEach(r -> events.add(new Event(r.txIndex(), r.certIndex(), r, null)));
        fact.pointerDeregistrations().forEach(d -> events.add(new Event(d.txIndex(), d.certIndex(), null, d)));
        events.sort(Comparator.comparingInt(Event::txIndex).thenComparingInt(Event::certIndex));

        Map<PointerCoordinate, PointerCredential> registrations = new HashMap<>();
        List<Deregistration> deregistrations = new ArrayList<>();
        for (Event event : events) {
            if (event.registration() != null) {
                var r = event.registration();
                registrations.put(new PointerCoordinate(r.slot(), r.txIndex(), r.certIndex()),
                        credential(r.credentialType(), r.credential()));
            } else {
                var d = event.deregistration();
                // A deregistration's own slot is this block's slot; only tx/cert order distinguishes
                // it from other events here, and the fact model carries exactly those.
                deregistrations.add(new Deregistration(
                        new PointerCoordinate(slotOf(fact), d.txIndex(), d.certIndex()),
                        credential(d.credentialType(), d.credential())));
            }
        }
        return new Overlay(registrations, deregistrations);
    }

    /** Deregistration facts carry no slot; every certificate in a block shares the block's slot. */
    private static long slotOf(UtxoHistoryFact fact) {
        return fact.pointerRegistrations().isEmpty() ? 0 : fact.pointerRegistrations().get(0).slot();
    }

    private static UtxoHistoryFact.Address resolveAddress(
            UtxoHistoryFact.Address address, int era, long blockSlot, PointerCoordinate blockEnd,
            Overlay overlay, PointerCredentialSource source) {

        if (!POINTER.equals(address.stakeReferenceType())) return address;
        if (era >= CONWAY_ERA) return copy(address, NOT_EFFECTIVE, null, null);

        Long slot = address.pointerSlot();
        Integer txIndex = address.pointerTxIndex();
        Integer certIndex = address.pointerCertIndex();
        if (slot == null || txIndex == null || certIndex == null) {
            return copy(address, UNRESOLVED, null, null);
        }
        PointerCoordinate registration = new PointerCoordinate(slot, txIndex, certIndex);

        // A coordinate newer than this block can never be its pointer's target.
        if (registration.compareTo(blockEnd) > 0) return copy(address, UNRESOLVED, null, null);

        PointerCredential credential = overlay.registrations().get(registration);
        if (credential == null) {
            credential = source.registrationAt(registration).orElse(null);
        }
        if (credential == null) return copy(address, UNRESOLVED, null, null);

        // Deregistration invalidates every older coordinate for that credential. Both sources
        // are checked: the overlay covers this block, the index covers earlier ones. A
        // previous-block coordinate deregistered in this block is caught by the overlay and
        // must not fall through to the append-only registration record.
        if (overlay.deregisteredWithin(credential, registration, blockEnd)
                || source.deregisteredWithin(credential, registration, blockEnd)) {
            return copy(address, UNRESOLVED, null, null);
        }

        return copy(address, RESOLVED, credentialTypeName(credential.credentialType()),
                hexToBytes(credential.credentialHash()));
    }

    /**
     * Coordinate-level pointer lookup with the same as-of rule the UTXO path uses.
     *
     * <p>Exposed so the address-transaction projection resolves pointer stake references
     * identically rather than growing a second interpretation of the same ledger rule. There is
     * no in-block overlay here: address participations are resolved per block against
     * registrations that already exist, and a pointer registered in the very block that spends
     * to it is not resolvable by the live path either.
     *
     * @return the credential, or null when the pointer resolves to nothing — a coordinate newer
     *         than this block, an unknown registration, or one deregistered by this block
     */
    static PointerCredential resolveCoordinate(PointerCoordinate registration, long blockSlot,
                                               PointerCredentialSource source) {
        PointerCoordinate blockEnd = PointerCoordinate.endOfBlock(blockSlot);
        if (registration.compareTo(blockEnd) > 0) return null;
        PointerCredential credential = source.registrationAt(registration).orElse(null);
        if (credential == null) return null;
        if (source.deregisteredWithin(credential, registration, blockEnd)) return null;
        return credential;
    }

    /** {@code "key"} / {@code "script"} for a resolved credential; the archive's vocabulary. */
    static String credentialTypeNameOf(PointerCredential credential) {
        return credentialTypeName(credential.credentialType());
    }

    /** Raw credential hash bytes for a resolved credential. */
    static byte[] credentialHashOf(PointerCredential credential) {
        return hexToBytes(credential.credentialHash());
    }

    private static PointerCredential credential(String type, byte[] hash) {
        return new PointerCredential(credentialTypeCode(type),
                hash == null ? "" : com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(hash));
    }

    /**
     * The account-state store encodes 0 = key hash, 1 = script hash. The projection vocabulary
     * is {@code "key"}/{@code "script"} — the strings the shipped decoder emits and
     * {@code StakeAddressCodec} accepts — not the yaci enum names.
     */
    private static int credentialTypeCode(String type) {
        return "script".equalsIgnoreCase(type) || "SCRIPTHASH".equalsIgnoreCase(type) ? 1 : 0;
    }

    private static String credentialTypeName(int code) {
        return code == 1 ? "script" : "key";
    }

    private static byte[] hexToBytes(String hex) {
        return hex == null || hex.isEmpty() ? null
                : com.bloxbean.cardano.yaci.core.util.HexUtil.decodeHexString(hex);
    }

    private static UtxoHistoryFact.Address copy(UtxoHistoryFact.Address source, String referenceType,
                                                String credentialType, byte[] credential) {
        return new UtxoHistoryFact.Address(source.addressKey(), source.rawAddress(), source.displayAddress(),
                source.networkId(), source.addressType(), source.paymentCredentialType(),
                source.paymentCredential(), referenceType, credentialType, credential,
                source.pointerSlot(), source.pointerTxIndex(), source.pointerCertIndex());
    }
}
