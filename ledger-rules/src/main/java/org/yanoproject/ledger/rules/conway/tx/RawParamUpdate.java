package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.transaction.raw.ProtocolParamField;
import com.bloxbean.cardano.client.transaction.raw.RawProtocolParamUpdate;

import org.yanoproject.ledger.rules.view.model.ProposalState;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A Conway {@code PParamsUpdate} read from the original bytes of a {@code ParameterChange} proposal
 * ({@code protocol_param_update}, keys 0–33), with what the {@code GOV} rule needs for {@code MalformedProposal}
 * ({@code ppuWellFormed}) and for the security group that decides whether stake pools may vote.
 *
 * <p>Read through CCL's view ({@link RawProtocolParamUpdate}) of an update that {@link HaskellPolicy} has walked:
 * Haskell's {@code DecCBOR (PParamsUpdate era)} at decoder versions 9–11 (cardano-ledger-core
 * {@code Core/PParams.hs:262-294}: {@code SparseKeyed} over Conway's {@code eraPParams}). Its table,
 * {@link ProtocolParamField}, says which keys Conway updates and the type of each.</p>
 */
public final class RawParamUpdate {

    /**
     * Keys of Haskell's stake-pool {@code SecurityGroup} (Conway/PParams.hs:644-708): txFeePerByte 0, txFeeFixed 1,
     * maxBBSize 2, maxTxSize 3, maxBHSize 4, coinsPerUTxOByte 17, maxBlockExUnits 21, maxValSize 22, govActionDeposit
     * 30, minFeeRefScriptCostPerByte 33.
     */
    public static final Set<Integer> SECURITY_GROUP_KEYS = ProposalState.SECURITY_GROUP_KEYS;

    private final SortedSet<Integer> keys;
    private final SortedMap<Integer, BigInteger> integers;

    private RawParamUpdate(SortedSet<Integer> keys, SortedMap<Integer, BigInteger> integers) {
        this.keys = Collections.unmodifiableSortedSet(keys);
        this.integers = Collections.unmodifiableSortedMap(integers);
    }

    /**
     * @return the update that CCL's reader read, which {@link HaskellPolicy} has accepted: its keys, and the value of
     *         each whose type is a number (coins, sizes, epoch intervals, counts)
     */
    static RawParamUpdate accepted(RawProtocolParamUpdate update) {
        SortedSet<Integer> keys = new TreeSet<>();
        SortedMap<Integer, BigInteger> integers = new TreeMap<>();
        for (Map.Entry<CborSpan, CborSpan> entry : update.span().entries()) {
            int key = Math.toIntExact(entry.getKey().asLong());
            keys.add(key);
            switch (ProtocolParamField.RECORD.field(key, update.era()).type()) {
                case COIN, UINT16, UINT32 -> integers.put(key, StrictCbor.unsigned(entry.getValue()));
                default -> {
                }
            }
        }
        return new RawParamUpdate(keys, integers);
    }

    /** @return the keys present, ascending */
    public SortedSet<Integer> keys() {
        return keys;
    }

    /** @return the value of an integer-valued key (coins, sizes, epoch intervals, counts) when present */
    public Optional<BigInteger> integer(int key) {
        return Optional.ofNullable(integers.get(key));
    }

    /** @return true for {@code emptyPParamsUpdate} */
    public boolean isEmpty() {
        return keys.isEmpty();
    }

    /** @return whether any modified parameter is in the stake-pool {@code SecurityGroup} ({@code modifiedPPGroups}) */
    public boolean anyInSecurityGroup() {
        return keys.stream().anyMatch(SECURITY_GROUP_KEYS::contains);
    }

    /**
     * Conway's {@code ppuWellFormed pv} (Conway/PParams.hs:935-963): the listed parameters are not 0 when present, and
     * the update is not empty. Which parameters are listed depends on the protocol version, so the caller passes them:
     * the {@code GOV.MalformedProposal} unit of the version's rule set ({@code GovChecks.MalformedProposal}).
     *
     * @param nonZeroKeys the parameter keys that must not be 0 when present
     * @return the offending parameter keys (with {@code -1} for an empty update); empty when well formed
     */
    public SortedSet<Integer> malformedKeys(Collection<Integer> nonZeroKeys) {
        SortedSet<Integer> bad = new TreeSet<>();
        for (int key : nonZeroKeys) {
            zero(key, bad);
        }
        if (isEmpty()) {
            bad.add(-1);
        }
        return bad;
    }

    private void zero(int key, SortedSet<Integer> bad) {
        BigInteger value = integers.get(key);
        if (value != null && value.signum() == 0) {
            bad.add(key);
        }
    }

    @Override
    public String toString() {
        return "PParamsUpdate " + keys;
    }
}
