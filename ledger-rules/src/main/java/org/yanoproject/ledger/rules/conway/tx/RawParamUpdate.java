package org.yanoproject.ledger.rules.conway.tx;

import org.yanoproject.ledger.rules.view.model.ProposalState;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A Conway {@code PParamsUpdate} read from the original bytes of a {@code ParameterChange} proposal
 * ({@code protocol_param_update}, keys 0–33): CCL's {@code ProtocolParamUpdate} has no Conway fields (keys 25–33), so
 * the {@code GOV} rule cannot use it for {@code MalformedProposal} ({@code ppuWellFormed}) or for the security group
 * that decides whether stake pools may vote.
 *
 * <p><b>Decoding</b> follows Haskell's {@code DecCBOR (PParamsUpdate era)} at decoder versions 9–11
 * (cardano-ledger-core {@code Core/PParams.hs:257-294}: {@code SparseKeyed} over Conway's {@code eraPParams},
 * Conway/PParams.hs:862-894), and anything it would refuse is a {@link TxDecodingException}
 * ({@code ENGINE.DecodingFailure}):</p>
 * <ul>
 *   <li>a map (definite or indefinite) without duplicate keys ({@code duplicateKey}, Coders.hs:563-569) whose keys are
 *       Conway's updatable parameters: 0–11 and 16–33 (12 {@code d}, 13 {@code extraEntropy}, 15 {@code minUTxOValue}
 *       are not Conway parameters, and the protocol version, 14, is not updatable in Conway,
 *       {@code ppGovProtocolVersion}, Conway/PParams.hs:1347-1354);</li>
 *   <li>coins ({@code CompactForm Coin}, {@code CoinPerByte}): {@code Word64} — keys 0, 1, 5, 6, 16, 17, 30, 31;</li>
 *   <li>{@code Word32}: 2, 3, 22 and the {@code EpochInterval}s 7, 28, 29, 32; {@code Word16}: 4, 8, 23, 24, 27;</li>
 *   <li>{@code NonNegativeInterval}: 9, 33; {@code UnitInterval}: 10, 11 (tag-30 rationals, {@link BoundedFields});</li>
 *   <li>18 {@code CostModels}: a map of {@code Word8} language ids to lists of {@code Int64}, without duplicate keys
 *       ({@code decodeCostModelsLenient}, Plutus/CostModels.hs:302-316; unknown languages are kept, and plutus-ledger-api
 *       1.65 accepts any length for a known one, {@code tagWithParamNames});</li>
 *   <li>19 {@code Prices}: {@code [NonNegativeInterval, NonNegativeInterval]}; 20, 21 {@code ExUnits}:
 *       {@code [mem, steps]}, each at most {@code maxBound :: Int64} (Plutus/ExUnits.hs:195-231);</li>
 *   <li>25 {@code PoolVotingThresholds}: five {@code UnitInterval}s; 26 {@code DRepVotingThresholds}: ten
 *       (Conway/PParams.hs:366-374, 519-532).</li>
 * </ul>
 */
public final class RawParamUpdate {

    /**
     * Keys of Haskell's stake-pool {@code SecurityGroup} (Conway/PParams.hs:644-708): txFeePerByte 0, txFeeFixed 1,
     * maxBBSize 2, maxTxSize 3, maxBHSize 4, coinsPerUTxOByte 17, maxBlockExUnits 21, maxValSize 22, govActionDeposit
     * 30, minFeeRefScriptCostPerByte 33.
     */
    public static final Set<Integer> SECURITY_GROUP_KEYS = ProposalState.SECURITY_GROUP_KEYS;

    private static final BigInteger WORD64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final BigInteger INT64_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger INT64_MIN = BigInteger.valueOf(Long.MIN_VALUE);

    private final SortedSet<Integer> keys;
    private final SortedMap<Integer, BigInteger> integers;

    private RawParamUpdate(SortedSet<Integer> keys, SortedMap<Integer, BigInteger> integers) {
        this.keys = Collections.unmodifiableSortedSet(keys);
        this.integers = Collections.unmodifiableSortedMap(integers);
    }

    /** Reads a {@code protocol_param_update} at the reader's position. */
    static RawParamUpdate read(CborReader reader) {
        long entries = reader.readMapHeader();
        SortedSet<Integer> keys = new TreeSet<>();
        SortedMap<Integer, BigInteger> integers = new TreeMap<>();
        for (long i = 0; reader.hasNext(entries, i); i++) {
            BigInteger rawKey = reader.readUnsigned();
            if (rawKey.bitLength() > 31 || !keys.add(rawKey.intValue())) {
                throw new TxDecodingException("PParamsUpdate: " + (rawKey.bitLength() > 31 ? "invalid" : "duplicate")
                        + " key " + rawKey);
            }
            int key = rawKey.intValue();
            switch (key) {
                case 0, 1, 5, 6, 16, 17, 30, 31 -> integers.put(key, bounded(reader, WORD64_MAX, key));
                case 2, 3, 7, 22, 28, 29, 32 -> integers.put(key, bounded(reader, BigInteger.valueOf(0xFFFF_FFFFL), key));
                case 4, 8, 23, 24, 27 -> integers.put(key, bounded(reader, BigInteger.valueOf(0xFFFF), key));
                case 9, 33 -> BoundedFields.boundedRational(reader, "PParamsUpdate key " + key, false);
                case 10, 11 -> BoundedFields.boundedRational(reader, "PParamsUpdate key " + key, true);
                case 18 -> costModels(reader);
                case 19 -> fixedArray(reader, 2, "Prices", () -> BoundedFields.boundedRational(reader, "price", false));
                case 20, 21 -> fixedArray(reader, 2, "ExUnits", () -> bounded(reader, INT64_MAX, key));
                case 25 -> fixedArray(reader, 5, "PoolVotingThresholds",
                        () -> BoundedFields.boundedRational(reader, "pool voting threshold", true));
                case 26 -> fixedArray(reader, 10, "DRepVotingThresholds",
                        () -> BoundedFields.boundedRational(reader, "DRep voting threshold", true));
                default -> throw new TxDecodingException("PParamsUpdate: invalid key " + key);
            }
        }
        return new RawParamUpdate(keys, integers);
    }

    private static BigInteger bounded(CborReader reader, BigInteger max, int key) {
        BigInteger value = reader.readUnsigned();
        if (value.compareTo(max) > 0) {
            throw new TxDecodingException("PParamsUpdate key " + key + ": " + value + " exceeds " + max);
        }
        return value;
    }

    private static void fixedArray(CborReader reader, int length, String what, Runnable element) {
        long found = reader.readArrayHeader();
        if (found != length && found != CborReader.INDEFINITE) {
            throw new TxDecodingException(what + " has " + found + " elements, expected " + length);
        }
        for (int i = 0; i < length; i++) {
            if (found == CborReader.INDEFINITE && !reader.hasNext(found, i)) {
                throw new TxDecodingException(what + " has " + i + " elements, expected " + length);
            }
            element.run();
        }
        if (found == CborReader.INDEFINITE && reader.hasNext(found, length)) {
            throw new TxDecodingException(what + " has more than " + length + " elements");
        }
    }

    /** {@code Map Word8 [Int64]} without duplicate languages. */
    private static void costModels(CborReader reader) {
        long languages = reader.readMapHeader();
        Set<Long> seen = new HashSet<>();
        for (long i = 0; reader.hasNext(languages, i); i++) {
            long language = reader.readUnsignedLong();
            if (language > 0xFF || !seen.add(language)) {
                throw new TxDecodingException("CostModels: " + (language > 0xFF ? "invalid" : "duplicate")
                        + " language " + language);
            }
            long values = reader.readArrayHeader();
            for (long j = 0; reader.hasNext(values, j); j++) {
                BigInteger value = reader.readInteger();
                if (value.compareTo(INT64_MAX) > 0 || value.compareTo(INT64_MIN) < 0) {
                    throw new TxDecodingException("CostModels: parameter " + value + " exceeds Int64");
                }
            }
        }
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
