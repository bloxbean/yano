package com.bloxbean.cardano.yano.appchain.proofs.onchain;

import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/** Bounded two-level MPF verifier for ADR-028 authenticated snapshot descriptors. */
@SpendingValidator
public final class AuthenticatedSnapshotOnChainVerifier {
    private static final long MAX_DESCRIPTOR_BYTES = 4096;
    private static final long PATH_NIBBLES = 64;
    private static final long MAX_KEY_BYTES = 256;
    private static final long MAX_VALUE_BYTES = 8 * 1024;
    private static final long MAX_FOLDS = 32;

    @Param static byte[] anchorThreadPolicyId;
    @Param static byte[] anchorThreadAssetName;
    @Param static byte[] anchorScriptHash;
    @Param static byte[] expectedChainId;
    @Param static byte[] expectedChainGenesisId;
    @Param static byte[] expectedAnchorApplicationId;
    @Param static byte[] expectedApplicationProfileDigest;
    @Param static byte[] expectedPrimaryProfileId;
    @Param static byte[] expectedPrimaryFormatFingerprint;
    @Param static byte[] expectedSecondaryProfileId;
    @Param static byte[] expectedSecondaryFormatFingerprint;
    @Param static byte[] expectedSecondaryProofWireVersion;
    @Param static byte[] expectedDescriptorKey;
    @Param static byte[] expectedFactKey;
    @Param static byte[] expectedSeriesId;
    @Param static BigInteger expectedSnapshotSequence;
    @Param static byte[] expectedSchemaId;
    @Param static byte[] expectedSourceCommitmentAlgorithm;
    @Param static byte[] expectedSourceCommitmentWireVersion;
    @Param static BigInteger expectedPreviousEpoch;
    @Param static BigInteger expectedNewEpoch;
    @Param static BigInteger expectedDatasetEpoch;
    @Param static BigInteger expectedPredicate;
    @Param static BigInteger expectedCoin;
    @Param static byte[] expectedAuxiliary;

    public record Fold(BigInteger cursor, byte[] prefix, BigInteger nibble,
                       byte[] neighbor1, byte[] neighbor2, byte[] neighbor3,
                       byte[] neighbor4, byte[] branchValueHash) { }

    public record Proof(byte[] key, byte[] value, byte[] leafSuffix,
                        JulcList<Fold> folds, BigInteger terminalCursor,
                        byte[] conflictingKeyHash, byte[] conflictingValueHash) { }

    public record NestedProof(
            Proof descriptor,
            Proof fact
    ) { }

    private record Item(byte[] bytes, long next) { }
    private record UInt(BigInteger value, long next) { }
    private record AnchorRoot(byte[] stateRoot) { }

    private AuthenticatedSnapshotOnChainVerifier() { }

    @Entrypoint
    public static boolean validate(PlutusData datum, NestedProof proof, ScriptContext context) {
        Optional<AnchorRoot> root = acceptedAnchor(context);
        return root.isPresent() && verifyAtRoot(proof, root.get().stateRoot(),
                expectedSecondaryProfileId, expectedSecondaryFormatFingerprint);
    }

    private static Optional<AnchorRoot> acceptedAnchor(ScriptContext context) {
        Optional<AnchorRoot> result = Optional.empty();
        BigInteger count = BigInteger.ZERO;
        for (TxInInfo input : context.txInfo().referenceInputs()) {
            if (ValuesLib.assetOf(input.resolved().value(), anchorThreadPolicyId,
                    anchorThreadAssetName).equals(BigInteger.ONE)
                    && atScriptAddress(input.resolved(), anchorScriptHash)) {
                count = count.add(BigInteger.ONE);
                result = anchor(input.resolved().datum());
            }
        }
        return count.equals(BigInteger.ONE) ? result : Optional.empty();
    }

    private static Optional<AnchorRoot> anchor(OutputDatum datum) {
        if (datum instanceof OutputDatum.OutputDatumInline inline) {
            return anchorData(inline.datum());
        }
        return Optional.empty();
    }

    private static Optional<AnchorRoot> anchorData(PlutusData value) {
        if (Builtins.constrTag(value) != 0) return Optional.empty();
        PlutusData fields = Builtins.constrFields(value);
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chainId = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        byte[] genesis = Builtins.unBData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        byte[] application = Builtins.unBData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] profile = Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        byte[] fingerprint = Builtins.unBData(Builtins.headList(f5));
        PlutusData f6 = Builtins.tailList(f5);
        BigInteger height = Builtins.unIData(Builtins.headList(f6));
        PlutusData f7 = Builtins.tailList(f6);
        byte[] blockHash = Builtins.unBData(Builtins.headList(f7));
        PlutusData f8 = Builtins.tailList(f7);
        byte[] stateRoot = Builtins.unBData(Builtins.headList(f8));
        PlutusData f9 = Builtins.tailList(f8);
        PlutusData f10 = Builtins.tailList(f9);
        PlutusData trailing = Builtins.tailList(f10);
        if (!version.equals(BigInteger.ONE) || !Builtins.nullList(trailing)
                || !Builtins.equalsByteString(chainId, expectedChainId) || height.signum() < 0
                || Builtins.lengthOfByteString(blockHash) != 32
                || Builtins.lengthOfByteString(stateRoot) != 32
                || !Builtins.equalsByteString(genesis, expectedChainGenesisId)
                || !Builtins.equalsByteString(application, expectedAnchorApplicationId)
                || !Builtins.equalsByteString(profile, expectedPrimaryProfileId)
                || !Builtins.equalsByteString(fingerprint,
                expectedPrimaryFormatFingerprint)) return Optional.empty();
        return Optional.of(new AnchorRoot(stateRoot));
    }

    private static boolean atScriptAddress(TxOut output, byte[] scriptHash) {
        PlutusData credential = output.address().credential().toPlutusData();
        PlutusData fields = Builtins.constrFields(credential);
        byte[] observed = Builtins.unBData(Builtins.headList(fields));
        return Builtins.constrTag(credential) == 1
                && Builtins.nullList(Builtins.tailList(fields))
                && Builtins.equalsByteString(observed, scriptHash);
    }

    /**
     * The caller authenticates {@code primaryRoot} from the unique L1 anchor
     * output. This method then verifies descriptor inclusion, extracts the
     * bound secondary root/profile, and verifies the semantic fact.
     */
    public static boolean verifyAtRoot(NestedProof proof, byte[] primaryRoot,
                                       byte[] expectedSecondaryProfile,
                                       byte[] expectedSecondaryFingerprint) {
        if (Builtins.lengthOfByteString(primaryRoot) != 32
                || !Builtins.equalsByteString(proof.descriptor().key(),
                expectedDescriptorKey)
                || !Builtins.equalsByteString(proof.fact().key(), expectedFactKey)
                || !verifyInclusion(proof.descriptor(), primaryRoot)) {
            return false;
        }
        Optional<DescriptorIdentity> identity = descriptorIdentity(proof.descriptor().value());
        return identity.isPresent()
                && Builtins.equalsByteString(identity.get().profile(), expectedSecondaryProfile)
                && Builtins.equalsByteString(identity.get().fingerprint(),
                expectedSecondaryFingerprint)
                && identity.get().semanticallyBound()
                && verifySemanticFact(proof.fact(), identity.get().root(),
                expectedPredicate, expectedCoin, expectedAuxiliary);
    }

    private record DescriptorIdentity(byte[] profile, byte[] fingerprint, byte[] root,
                                      boolean semanticallyBound) { }

    private static boolean verifySemanticFact(Proof proof, byte[] root,
                                              BigInteger predicate, BigInteger expected,
                                              byte[] auxiliary) {
        if (predicate.signum() < 0 || predicate.compareTo(BigInteger.valueOf(7)) > 0) {
            return false;
        }
        long mode = predicate.longValue();
        boolean proofValid = mode == 4 ? verifyAbsence(proof, root)
                : verifyInclusion(proof, root);
        if (!proofValid) return false;
        if (mode == 4) return true;
        if (mode >= 0 && mode <= 3) {
            return stakePredicate(proof.value(), predicate, expected, auxiliary);
        }
        return (mode == 6 || mode == 7)
                && drepPredicate(proof.value(), expected, mode == 7);
    }

    private static boolean stakePredicate(byte[] value, BigInteger predicate,
                                          BigInteger expectedCoin, byte[] expectedPool) {
        long length = Builtins.lengthOfByteString(value);
        if (length < 32 || Builtins.indexByteString(value, 0) != 130
                || expectedCoin.signum() < 0) return false;
        UInt coin = readUInt(value, 1);
        long poolOffset = coin.next();
        if (coin.value().signum() < 0 || poolOffset + 30 != length
                || Builtins.indexByteString(value, poolOffset) != 88
                || Builtins.indexByteString(value, poolOffset + 1) != 28) return false;
        byte[] pool = Builtins.sliceByteString(poolOffset + 2, 28, value);
        boolean amount = coin.value().compareTo(expectedCoin) >= 0;
        boolean exact = coin.value().equals(expectedCoin);
        boolean delegated = Builtins.lengthOfByteString(expectedPool) == 28
                && Builtins.equalsByteString(pool, expectedPool);
        long mode = predicate.longValue();
        return mode == 0 ? amount : mode == 1 ? delegated
                : mode == 2 ? amount && delegated : mode == 3 && exact && delegated;
    }

    private static boolean drepPredicate(byte[] value, BigInteger expected, boolean exact) {
        if (expected.signum() < 0) return false;
        UInt amount = readUInt(value, 0);
        return amount.next() == Builtins.lengthOfByteString(value)
                && (exact ? amount.value().equals(expected)
                : amount.value().compareTo(expected) >= 0);
    }

    private static boolean verifyInclusion(Proof proof, byte[] expectedRoot) {
        long keyLength = Builtins.lengthOfByteString(proof.key());
        long valueLength = Builtins.lengthOfByteString(proof.value());
        long encodedLength = Builtins.lengthOfByteString(proof.leafSuffix());
        if (Builtins.lengthOfByteString(expectedRoot) != 32
                || keyLength < 1 || keyLength > MAX_KEY_BYTES
                || valueLength < 1 || valueLength > MAX_VALUE_BYTES
                || encodedLength < 1 || encodedLength > 33
                || proof.folds().size() > MAX_FOLDS) return false;
        byte[] pathHash = Builtins.blake2b_256(proof.key());
        long marker = Builtins.indexByteString(proof.leafSuffix(), 0);
        boolean odd = marker == 0;
        long suffixLength = odd ? 1 + (encodedLength - 2) * 2 : (encodedLength - 1) * 2;
        long cursorEnd = PATH_NIBBLES - suffixLength;
        if ((marker != 0 && marker != 255) || (odd && encodedLength < 2)
                || cursorEnd < 0 || (odd && Builtins.indexByteString(
                proof.leafSuffix(), 1) != pathNibble(pathHash, cursorEnd))
                || !Builtins.equalsByteString(Builtins.sliceByteString(
                odd ? 2 : 1, encodedLength - (odd ? 2 : 1), proof.leafSuffix()),
                Builtins.sliceByteString(odd ? (cursorEnd + 1) / 2 : cursorEnd / 2,
                        encodedLength - (odd ? 2 : 1), pathHash))) return false;
        byte[] child = hash(proof.leafSuffix(), Builtins.blake2b_256(proof.value()));
        boolean valid = true;
        for (Fold fold : proof.folds()) {
            if (!bounded(fold.cursor(), PATH_NIBBLES) || !bounded(fold.nibble(), 15)) {
                valid = false;
                break;
            }
            long cursor = fold.cursor().longValue();
            long prefixLength = Builtins.lengthOfByteString(fold.prefix());
            long nibble = fold.nibble().longValue();
            if (cursor < 0 || nibble < 0 || nibble > 15
                    || cursor + prefixLength + 1 != cursorEnd
                    || !validPrefix(pathHash, cursor, fold.prefix())
                    || pathNibble(pathHash, cursor + prefixLength) != nibble
                    || !hashLength(fold.neighbor1()) || !hashLength(fold.neighbor2())
                    || !hashLength(fold.neighbor3()) || !hashLength(fold.neighbor4())
                    || (Builtins.lengthOfByteString(fold.branchValueHash()) != 0
                    && !hashLength(fold.branchValueHash()))) {
                valid = false;
                break;
            }
            byte[] merkle = aggregate(nibble, child, fold.neighbor1(), fold.neighbor2(),
                    fold.neighbor3(), fold.neighbor4());
            if (Builtins.lengthOfByteString(fold.branchValueHash()) == 32) {
                merkle = hash(merkle, hash(Builtins.consByteString(
                        255, Builtins.emptyByteString()), fold.branchValueHash()));
            }
            child = hash(fold.prefix(), merkle);
            cursorEnd = cursor;
        }
        return valid && cursorEnd == 0 && Builtins.equalsByteString(expectedRoot, child);
    }

    private static boolean verifyAbsence(Proof proof, byte[] expectedRoot) {
        long keyLength = Builtins.lengthOfByteString(proof.key());
        long suffixLength = Builtins.lengthOfByteString(proof.leafSuffix());
        if (!bounded(proof.terminalCursor(), PATH_NIBBLES)) return false;
        long cursorEnd = proof.terminalCursor().longValue();
        if (Builtins.lengthOfByteString(expectedRoot) != 32
                || keyLength < 1 || keyLength > MAX_KEY_BYTES
                || cursorEnd < 0 || cursorEnd > PATH_NIBBLES
                || proof.folds().size() > MAX_FOLDS) return false;
        byte[] queryPath = Builtins.blake2b_256(proof.key());
        boolean missing = suffixLength == 0;
        if (missing) {
            if (Builtins.lengthOfByteString(proof.conflictingKeyHash()) != 0
                    || Builtins.lengthOfByteString(proof.conflictingValueHash()) != 0) return false;
        } else if (suffixLength > 33
                || Builtins.lengthOfByteString(proof.conflictingKeyHash()) != 32
                || Builtins.lengthOfByteString(proof.conflictingValueHash()) != 32
                || Builtins.equalsByteString(queryPath, proof.conflictingKeyHash())
                || !validLeafSuffix(proof.conflictingKeyHash(), cursorEnd, proof.leafSuffix())) {
            return false;
        }
        byte[] child = missing ? new byte[]{0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0}
                : hash(proof.leafSuffix(), proof.conflictingValueHash());
        boolean valid = true;
        for (Fold fold : proof.folds()) {
            if (!bounded(fold.cursor(), PATH_NIBBLES) || !bounded(fold.nibble(), 15)) {
                valid = false;
                break;
            }
            long cursor = fold.cursor().longValue();
            long prefixLength = Builtins.lengthOfByteString(fold.prefix());
            long nibble = fold.nibble().longValue();
            if (cursor < 0 || nibble < 0 || nibble > 15
                    || cursor + prefixLength + 1 != cursorEnd
                    || !validPrefix(queryPath, cursor, fold.prefix())
                    || pathNibble(queryPath, cursor + prefixLength) != nibble
                    || !hashLength(fold.neighbor1()) || !hashLength(fold.neighbor2())
                    || !hashLength(fold.neighbor3()) || !hashLength(fold.neighbor4())
                    || (Builtins.lengthOfByteString(fold.branchValueHash()) != 0
                    && !hashLength(fold.branchValueHash()))) {
                valid = false;
                break;
            }
            byte[] merkle = aggregate(nibble, child, fold.neighbor1(), fold.neighbor2(),
                    fold.neighbor3(), fold.neighbor4());
            if (Builtins.lengthOfByteString(fold.branchValueHash()) == 32) {
                merkle = hash(merkle, hash(Builtins.consByteString(
                        255, Builtins.emptyByteString()), fold.branchValueHash()));
            }
            child = hash(fold.prefix(), merkle);
            cursorEnd = cursor;
        }
        return valid && cursorEnd == 0 && Builtins.equalsByteString(expectedRoot, child);
    }

    private static boolean validLeafSuffix(byte[] pathHash, long cursorEnd, byte[] encoded) {
        long encodedLength = Builtins.lengthOfByteString(encoded);
        long marker = Builtins.indexByteString(encoded, 0);
        boolean odd = marker == 0;
        long suffixNibbles = odd ? 1 + (encodedLength - 2) * 2
                : (encodedLength - 1) * 2;
        return (marker == 0 || marker == 255) && (!odd || encodedLength >= 2)
                && cursorEnd + suffixNibbles == PATH_NIBBLES
                && (!odd || Builtins.indexByteString(encoded, 1)
                == pathNibble(pathHash, cursorEnd))
                && Builtins.equalsByteString(Builtins.sliceByteString(
                odd ? 2 : 1, encodedLength - (odd ? 2 : 1), encoded),
                Builtins.sliceByteString(odd ? (cursorEnd + 1) / 2 : cursorEnd / 2,
                        encodedLength - (odd ? 2 : 1), pathHash));
    }

    private static boolean validPrefix(byte[] pathHash, long cursor, byte[] prefix) {
        long index = 0;
        boolean valid = true;
        while (index < Builtins.lengthOfByteString(prefix)) {
            long nibble = Builtins.indexByteString(prefix, index);
            if (nibble < 0 || nibble > 15 || nibble != pathNibble(pathHash, cursor + index)) {
                valid = false;
                break;
            }
            index += 1;
        }
        return valid;
    }

    private static long pathNibble(byte[] pathHash, long index) {
        long value = Builtins.indexByteString(pathHash, index / 2);
        return index % 2 == 0 ? value / 16 : value % 16;
    }

    private static byte[] aggregate(long nibble, byte[] me, byte[] a,
                                    byte[] b, byte[] c, byte[] d) {
        if (nibble == 0) return hash(hash(hash(hash(me, d), c), b), a);
        if (nibble == 1) return hash(hash(hash(hash(d, me), c), b), a);
        if (nibble == 2) return hash(hash(hash(c, hash(me, d)), b), a);
        if (nibble == 3) return hash(hash(hash(c, hash(d, me)), b), a);
        if (nibble == 4) return hash(hash(b, hash(hash(me, d), c)), a);
        if (nibble == 5) return hash(hash(b, hash(hash(d, me), c)), a);
        if (nibble == 6) return hash(hash(b, hash(c, hash(me, d))), a);
        if (nibble == 7) return hash(hash(b, hash(c, hash(d, me))), a);
        if (nibble == 8) return hash(a, hash(hash(hash(me, d), c), b));
        if (nibble == 9) return hash(a, hash(hash(hash(d, me), c), b));
        if (nibble == 10) return hash(a, hash(hash(c, hash(me, d)), b));
        if (nibble == 11) return hash(a, hash(hash(c, hash(d, me)), b));
        if (nibble == 12) return hash(a, hash(b, hash(hash(me, d), c)));
        if (nibble == 13) return hash(a, hash(b, hash(hash(d, me), c)));
        if (nibble == 14) return hash(a, hash(b, hash(c, hash(me, d))));
        return hash(a, hash(b, hash(c, hash(d, me))));
    }

    private static boolean hashLength(byte[] value) {
        return Builtins.lengthOfByteString(value) == 32;
    }

    private static byte[] hash(byte[] left, byte[] right) {
        return Builtins.blake2b_256(Builtins.appendByteString(left, right));
    }

    /** Parse and semantically bind all canonical snapshot-descriptor-v1 fields. */
    private static Optional<DescriptorIdentity> descriptorIdentity(byte[] value) {
        long length = Builtins.lengthOfByteString(value);
        if (length < 180 || length > MAX_DESCRIPTOR_BYTES
                || Builtins.indexByteString(value, 0) != 0x97) {
            return Optional.empty();
        }
        UInt version = readUInt(value, 1);
        Item generation = readItem(value, version.next(), 2, 32);
        Item application = readItem(value, generation.next(), 2, 32);
        Item series = readItem(value, application.next(), 3, 128);
        UInt sequence = readUInt(value, series.next());
        Item snapshotId = readItem(value, sequence.next(), 3, 256);
        Item profile = readItem(value, snapshotId.next(), 3, 128);
        Item fingerprint = readItem(value, profile.next(), 2, 32);
        Item proofWire = readItem(value, fingerprint.next(), 3, 128);
        Item root = readItem(value, proofWire.next(), 2, 32);
        Item sourceRoot = readItem(value, root.next(), 2, 32);
        Item sourceAlgorithm = readItem(value, sourceRoot.next(), 3, 128);
        Item sourceWire = readItem(value, sourceAlgorithm.next(), 3, 128);
        Item schema = readItem(value, sourceWire.next(), 3, 128);
        UInt entryCount = readUInt(value, schema.next());
        UInt baseHeight = readUInt(value, entryCount.next());
        UInt completedHeight = readUInt(value, baseHeight.next());
        UInt coveredFrom = readUInt(value, completedHeight.next());
        UInt coveredThrough = readUInt(value, coveredFrom.next());
        Item previousCommitment = readItem(value, coveredThrough.next(), 2, 32);
        long boundaryOffset = previousCommitment.next();
        boolean l1Boundary = boundaryOffset < length
                && Builtins.indexByteString(value, boundaryOffset) == 0x86;
        UInt boundaryTag = readUInt(value, boundaryOffset + 1);
        UInt previousEpoch = readUInt(value, boundaryTag.next());
        UInt newEpoch = readUInt(value, previousEpoch.next());
        UInt datasetEpoch = readUInt(value, newEpoch.next());
        UInt boundarySlot = readUInt(value, datasetEpoch.next());
        Item boundaryHash = readItem(value, boundarySlot.next(), 2, 32);
        UInt recovery = readUInt(value, boundaryHash.next());
        UInt complete = readUInt(value, recovery.next());
        if (!version.value().equals(BigInteger.ONE) || sequence.value().signum() < 0
                || Builtins.lengthOfByteString(generation.bytes()) != 32
                || Builtins.lengthOfByteString(application.bytes()) != 32
                || Builtins.lengthOfByteString(series.bytes()) < 1
                || Builtins.lengthOfByteString(snapshotId.bytes()) < 1
                || Builtins.lengthOfByteString(profile.bytes()) < 1
                || Builtins.lengthOfByteString(fingerprint.bytes()) != 32
                || Builtins.lengthOfByteString(proofWire.bytes()) < 1
                || Builtins.lengthOfByteString(root.bytes()) != 32
                || Builtins.lengthOfByteString(sourceRoot.bytes()) != 32
                || Builtins.lengthOfByteString(sourceAlgorithm.bytes()) < 1
                || Builtins.lengthOfByteString(sourceWire.bytes()) < 1
                || Builtins.lengthOfByteString(schema.bytes()) < 1
                || Builtins.lengthOfByteString(previousCommitment.bytes()) != 32
                || !l1Boundary || !boundaryTag.value().equals(BigInteger.ONE)
                || previousEpoch.value().signum() < 0 || newEpoch.value().signum() < 0
                || datasetEpoch.value().signum() < 0 || boundarySlot.value().signum() < 0
                || Builtins.lengthOfByteString(boundaryHash.bytes()) != 32
                || !recovery.value().equals(BigInteger.ZERO)
                || !complete.value().equals(BigInteger.ONE)
                || complete.next() != length) {
            return Optional.empty();
        }
        boolean bound = Builtins.equalsByteString(generation.bytes(), expectedChainGenesisId)
                && Builtins.equalsByteString(application.bytes(), expectedApplicationProfileDigest)
                && Builtins.equalsByteString(series.bytes(), expectedSeriesId)
                && sequence.value().equals(expectedSnapshotSequence)
                && Builtins.equalsByteString(profile.bytes(), expectedSecondaryProfileId)
                && Builtins.equalsByteString(fingerprint.bytes(), expectedSecondaryFormatFingerprint)
                && Builtins.equalsByteString(proofWire.bytes(), expectedSecondaryProofWireVersion)
                && Builtins.equalsByteString(schema.bytes(), expectedSchemaId)
                && Builtins.equalsByteString(sourceAlgorithm.bytes(),
                expectedSourceCommitmentAlgorithm)
                && Builtins.equalsByteString(sourceWire.bytes(), expectedSourceCommitmentWireVersion)
                && previousEpoch.value().equals(expectedPreviousEpoch)
                && newEpoch.value().equals(expectedNewEpoch)
                && datasetEpoch.value().equals(expectedDatasetEpoch)
                && completedHeight.value().compareTo(baseHeight.value()) >= 0
                && coveredThrough.value().compareTo(coveredFrom.value()) >= 0;
        return Optional.of(new DescriptorIdentity(
                profile.bytes(), fingerprint.bytes(), root.bytes(), bound));
    }

    private static boolean bounded(BigInteger value, long maximum) {
        return value.signum() >= 0 && value.compareTo(BigInteger.valueOf(maximum)) <= 0;
    }

    private static Item readItem(byte[] value, long offset, long major, long maximum) {
        long length = Builtins.lengthOfByteString(value);
        if (offset < 0 || offset >= length) return new Item(Builtins.emptyByteString(), length + 1);
        long initial = Builtins.indexByteString(value, offset);
        long observedMajor = initial / 32;
        long additional = initial % 32;
        long payload = offset + 1;
        if (additional < 24) {
            return readItemAt(value, payload, additional, observedMajor, major, maximum);
        }
        if (additional == 24 && payload < length) {
            long itemLength = Builtins.indexByteString(value, payload);
            return itemLength >= 24
                    ? readItemAt(value, payload + 1, itemLength, observedMajor, major, maximum)
                    : new Item(Builtins.emptyByteString(), length + 1);
        }
        if (additional == 25 && payload + 1 < length) {
            long itemLength = Builtins.indexByteString(value, payload) * 256
                    + Builtins.indexByteString(value, payload + 1);
            return itemLength > 255
                    ? readItemAt(value, payload + 2, itemLength, observedMajor, major, maximum)
                    : new Item(Builtins.emptyByteString(), length + 1);
        }
        return new Item(Builtins.emptyByteString(), length + 1);
    }

    private static Item readItemAt(byte[] value, long payload, long itemLength,
                                   long observedMajor, long expectedMajor, long maximum) {
        long length = Builtins.lengthOfByteString(value);
        if (observedMajor != expectedMajor || itemLength > maximum
                || payload + itemLength > length) {
            return new Item(Builtins.emptyByteString(), length + 1);
        }
        return new Item(Builtins.sliceByteString(payload, itemLength, value),
                payload + itemLength);
    }

    private static UInt readUInt(byte[] value, long offset) {
        long length = Builtins.lengthOfByteString(value);
        if (offset < 0 || offset >= length) return new UInt(BigInteger.valueOf(-1), length + 1);
        long initial = Builtins.indexByteString(value, offset);
        long major = initial / 32;
        long additional = initial % 32;
        if (major != 0) return new UInt(BigInteger.valueOf(-1), length + 1);
        if (additional < 24) return new UInt(BigInteger.valueOf(additional), offset + 1);
        if (additional == 24 && offset + 1 < length) {
            long decoded = Builtins.indexByteString(value, offset + 1);
            return decoded >= 24 ? new UInt(BigInteger.valueOf(decoded), offset + 2)
                    : new UInt(BigInteger.valueOf(-1), length + 1);
        }
        if (additional == 25 && offset + 2 < length) {
            long decoded = Builtins.indexByteString(value, offset + 1) * 256
                    + Builtins.indexByteString(value, offset + 2);
            return decoded > 255 ? new UInt(BigInteger.valueOf(decoded), offset + 3)
                    : new UInt(BigInteger.valueOf(-1), length + 1);
        }
        if (additional == 26 && offset + 4 < length) {
            long decoded = Builtins.indexByteString(value, offset + 1) * 16777216
                    + Builtins.indexByteString(value, offset + 2) * 65536
                    + Builtins.indexByteString(value, offset + 3) * 256
                    + Builtins.indexByteString(value, offset + 4);
            return decoded >= 65536 ? new UInt(BigInteger.valueOf(decoded), offset + 5)
                    : new UInt(BigInteger.valueOf(-1), length + 1);
        }
        if (additional == 27 && offset + 8 < length) {
            long high = Builtins.indexByteString(value, offset + 1);
            long decoded = high * 72057594037927936L
                    + Builtins.indexByteString(value, offset + 2) * 281474976710656L
                    + Builtins.indexByteString(value, offset + 3) * 1099511627776L
                    + Builtins.indexByteString(value, offset + 4) * 4294967296L
                    + Builtins.indexByteString(value, offset + 5) * 16777216L
                    + Builtins.indexByteString(value, offset + 6) * 65536L
                    + Builtins.indexByteString(value, offset + 7) * 256L
                    + Builtins.indexByteString(value, offset + 8);
            return high < 128 && decoded >= 4294967296L
                    ? new UInt(BigInteger.valueOf(decoded), offset + 9)
                    : new UInt(BigInteger.valueOf(-1), length + 1);
        }
        return new UInt(BigInteger.valueOf(-1), length + 1);
    }
}
