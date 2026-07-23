package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Permissionless withdrawal validator. It accepts only a claim committed under
 * the current threshold-controlled app-chain MPF root and an atomic settlement
 * cursor transition for that claim's sequence.
 */
@SpendingValidator
public final class ProofVaultValidator {
    private static final long PATH_NIBBLES = 64;

    @Param
    static byte[] rootThreadPolicyId;

    @Param
    static byte[] rootThreadAssetName;

    @Param
    static byte[] rootScriptHash;

    @Param
    static byte[] nullifierThreadPolicyId;

    @Param
    static byte[] nullifierThreadAssetName;

    @Param
    static byte[] nullifierScriptHash;

    @Param
    static byte[] claimDomain;

    @Param
    static byte[] withdrawalKeyPrefix;

    record Claim(
            BigInteger version,
            byte[] chainId,
            BigInteger bridgeEpoch,
            BigInteger sequence,
            byte[] claimId,
            PlutusData destination,
            BigInteger lovelace
    ) {
    }

    record Fold(
            BigInteger cursor,
            byte[] prefix,
            BigInteger nibble,
            byte[] neighbor1,
            byte[] neighbor2,
            byte[] neighbor3,
            byte[] neighbor4,
            byte[] branchValueHash
    ) {
    }

    record Proof(
            BigInteger version,
            Claim claim,
            byte[] key,
            byte[] value,
            byte[] leafSuffix,
            JulcList<Fold> folds
    ) {
    }

    record Root(
            byte[] chainId,
            BigInteger bridgeEpoch,
            BigInteger height,
            byte[] stateRoot,
            BigInteger generation
    ) {
    }

    record Nullifier(
            byte[] chainId,
            BigInteger bridgeEpoch,
            BigInteger nextSequence,
            BigInteger generation
    ) {
    }

    record NullifierTransition(Nullifier current, Nullifier next) {
    }

    private ProofVaultValidator() {
    }

    @Entrypoint
    public static boolean validate(
            PlutusData datum,
            PlutusData redeemer,
            ScriptContext context
    ) {
        Optional<Proof> parsedProof = proof(redeemer);
        if (parsedProof.isEmpty()) {
            return false;
        }
        Proof proof = parsedProof.get();
        if (!claimShape(proof)
                || Builtins.lengthOfByteString(proof.key()) < 1
                || Builtins.lengthOfByteString(proof.key()) > 256
                || !Builtins.equalsByteString(
                proof.key(),
                Builtins.appendByteString(
                        withdrawalKeyPrefix,
                        proof.claim().claimId()))
                || Builtins.lengthOfByteString(proof.value()) != 32
                || Builtins.lengthOfByteString(proof.leafSuffix()) < 1
                || Builtins.lengthOfByteString(proof.leafSuffix()) > 33
                || proof.folds().size() > PATH_NIBBLES
                || !Builtins.equalsByteString(
                claimDigest(proof.claim()),
                proof.value())) {
            return false;
        }
        Optional<Root> acceptedRoot = acceptedRoot(context);
        Optional<NullifierTransition> nullifier =
                nullifierTransition(context);
        if (acceptedRoot.isEmpty() || nullifier.isEmpty()) {
            return false;
        }
        Root root = acceptedRoot.get();
        Claim claim = proof.claim();
        Nullifier current = nullifier.get().current();
        Nullifier next = nullifier.get().next();
        if (!Builtins.equalsByteString(root.chainId(), claim.chainId())
                || !Builtins.equalsByteString(
                current.chainId(), claim.chainId())
                || !Builtins.equalsByteString(next.chainId(), claim.chainId())
                || !root.bridgeEpoch().equals(claim.bridgeEpoch())
                || !current.bridgeEpoch().equals(claim.bridgeEpoch())
                || !next.bridgeEpoch().equals(claim.bridgeEpoch())
                || !current.generation().equals(root.generation())
                || !next.generation().equals(current.generation())
                || !claim.sequence().equals(current.nextSequence())
                || !next.nextSequence().equals(
                current.nextSequence().add(BigInteger.ONE))
                || !verifyMpf(proof, root.stateRoot())) {
            return false;
        }
        Optional<TxInInfo> ownInput = ContextsLib.findOwnInput(context);
        JulcList<TxOut> continuing =
                ContextsLib.getContinuingOutputs(context);
        if (ownInput.isEmpty() || continuing.size() != 1
                || !settlementMatches(
                continuing.head().datum(), claim)) {
            return false;
        }
        BigInteger payoutCount = BigInteger.ZERO;
        for (TxOut output : context.txInfo().outputs()) {
            if (Builtins.equalsData(
                    output.address().toPlutusData(),
                    claim.destination())
                    && ValuesLib.lovelaceOf(output.value())
                    .equals(claim.lovelace())) {
                payoutCount = payoutCount.add(BigInteger.ONE);
            }
        }
        BigInteger inputLovelace =
                ValuesLib.lovelaceOf(ownInput.get().resolved().value());
        BigInteger continuingLovelace =
                ValuesLib.lovelaceOf(continuing.head().value());
        return payoutCount.equals(BigInteger.ONE)
                && continuingLovelace.signum() > 0
                && inputLovelace.subtract(continuingLovelace)
                .equals(claim.lovelace());
    }

    static boolean verifyMpf(Proof proof, byte[] expectedRoot) {
        if (Builtins.lengthOfByteString(expectedRoot) != 32) {
            return false;
        }
        byte[] pathHash = Builtins.blake2b_256(proof.key());
        long encodedLength =
                Builtins.lengthOfByteString(proof.leafSuffix());
        long marker = Builtins.indexByteString(proof.leafSuffix(), 0);
        boolean odd = marker == 0;
        long suffixLength = odd
                ? 1 + (encodedLength - 2) * 2
                : (encodedLength - 1) * 2;
        long cursorEnd = PATH_NIBBLES - suffixLength;
        if ((marker != 0 && marker != 255)
                || (odd && encodedLength < 2)
                || cursorEnd < 0
                || (odd && Builtins.indexByteString(
                proof.leafSuffix(), 1) != pathNibble(pathHash, cursorEnd))
                || !Builtins.equalsByteString(
                Builtins.sliceByteString(
                        odd ? 2 : 1,
                        encodedLength - (odd ? 2 : 1),
                        proof.leafSuffix()),
                Builtins.sliceByteString(
                        odd ? (cursorEnd + 1) / 2 : cursorEnd / 2,
                        encodedLength - (odd ? 2 : 1),
                        pathHash))) {
            return false;
        }
        byte[] child = commitLeaf(
                proof.leafSuffix(),
                Builtins.blake2b_256(proof.value()));
        boolean valid = true;
        for (Fold fold : proof.folds()) {
            long cursor = fold.cursor().longValue();
            long prefixLength =
                    Builtins.lengthOfByteString(fold.prefix());
            long nibble = fold.nibble().longValue();
            if (cursor < 0 || nibble < 0 || nibble > 15
                    || cursor + prefixLength + 1 != cursorEnd
                    || !validPrefix(
                    pathHash, cursor, fold.prefix())
                    || pathNibble(pathHash, cursor + prefixLength)
                    != nibble
                    || !hashLength(fold.neighbor1())
                    || !hashLength(fold.neighbor2())
                    || !hashLength(fold.neighbor3())
                    || !hashLength(fold.neighbor4())
                    || (Builtins.lengthOfByteString(
                    fold.branchValueHash()) != 0
                    && !hashLength(fold.branchValueHash()))) {
                valid = false;
                break;
            }
            byte[] merkle = aggregate(
                    nibble,
                    child,
                    fold.neighbor1(),
                    fold.neighbor2(),
                    fold.neighbor3(),
                    fold.neighbor4());
            if (Builtins.lengthOfByteString(
                    fold.branchValueHash()) == 32) {
                merkle = hash(
                        merkle,
                        commitLeaf(
                                Builtins.consByteString(
                                        255, Builtins.emptyByteString()),
                                fold.branchValueHash()));
            }
            child = hash(fold.prefix(), merkle);
            cursorEnd = cursor;
        }
        return valid
                && cursorEnd == 0
                && Builtins.equalsByteString(expectedRoot, child);
    }

    private static boolean claimShape(Proof proof) {
        Claim claim = proof.claim();
        return proof.version().equals(BigInteger.ONE)
                && claim.version().equals(BigInteger.ONE)
                && Builtins.lengthOfByteString(claim.chainId()) >= 1
                && Builtins.lengthOfByteString(claim.chainId()) <= 128
                && claim.bridgeEpoch().signum() >= 0
                && claim.sequence().signum() >= 0
                && Builtins.lengthOfByteString(claim.claimId()) == 32
                && claim.lovelace().signum() > 0;
    }

    private static byte[] claimDigest(Claim claim) {
        byte[] destination = destinationFingerprint(
                claim.destination());
        byte[] fields0 = Builtins.appendByteString(
                claimDomain,
                Builtins.blake2b_256(claim.chainId()));
        byte[] fields1 = Builtins.appendByteString(
                fields0,
                Builtins.integerToByteString(
                        true, 8, claim.bridgeEpoch()));
        byte[] fields2 = Builtins.appendByteString(
                fields1,
                Builtins.integerToByteString(
                        true, 8, claim.sequence()));
        byte[] fields3 = Builtins.appendByteString(
                fields2, claim.claimId());
        byte[] fields4 = Builtins.appendByteString(
                fields3, destination);
        byte[] fields5 = Builtins.appendByteString(
                fields4,
                Builtins.integerToByteString(
                        true, 8, claim.lovelace()));
        return Builtins.blake2b_256(fields5);
    }

    private static byte[] destinationFingerprint(PlutusData address) {
        PlutusData fields = Builtins.constrFields(address);
        PlutusData payment = Builtins.headList(fields);
        PlutusData f1 = Builtins.tailList(fields);
        PlutusData stake = Builtins.headList(f1);
        PlutusData trailing = Builtins.tailList(f1);
        if (Builtins.constrTag(address) != 0
                || !Builtins.nullList(trailing)) {
            return Builtins.emptyByteString();
        }
        byte[] encoded = credentialBytes(payment);
        if (Builtins.lengthOfByteString(encoded) != 29) {
            return Builtins.emptyByteString();
        }
        if (Builtins.constrTag(stake) == 1
                && Builtins.nullList(Builtins.constrFields(stake))) {
            byte[] enterprise = Builtins.appendByteString(
                    encoded,
                    Builtins.consByteString(
                            0, Builtins.emptyByteString()));
            return Builtins.blake2b_256(enterprise);
        }
        if (Builtins.constrTag(stake) != 0) {
            return Builtins.emptyByteString();
        }
        PlutusData optionFields = Builtins.constrFields(stake);
        PlutusData staking = Builtins.headList(optionFields);
        if (!Builtins.nullList(Builtins.tailList(optionFields))
                || Builtins.constrTag(staking) != 0) {
            return Builtins.emptyByteString();
        }
        PlutusData stakingFields = Builtins.constrFields(staking);
        PlutusData credential = Builtins.headList(stakingFields);
        if (!Builtins.nullList(Builtins.tailList(stakingFields))) {
            return Builtins.emptyByteString();
        }
        byte[] stakeCredential = credentialBytes(credential);
        if (Builtins.lengthOfByteString(stakeCredential) != 29) {
            return Builtins.emptyByteString();
        }
        byte[] basePrefix = Builtins.appendByteString(
                encoded,
                Builtins.consByteString(
                        1, Builtins.emptyByteString()));
        byte[] base = Builtins.appendByteString(
                basePrefix, stakeCredential);
        return Builtins.blake2b_256(base);
    }

    private static byte[] credentialBytes(PlutusData credential) {
        long tag = Builtins.constrTag(credential);
        PlutusData fields = Builtins.constrFields(credential);
        byte[] hash = Builtins.unBData(Builtins.headList(fields));
        if ((tag != 0 && tag != 1)
                || !Builtins.nullList(Builtins.tailList(fields))
                || Builtins.lengthOfByteString(hash) != 28) {
            return Builtins.emptyByteString();
        }
        return Builtins.consByteString(tag, hash);
    }

    private static Optional<Root> acceptedRoot(ScriptContext context) {
        Optional<Root> result = Optional.empty();
        BigInteger count = BigInteger.ZERO;
        for (TxInInfo input : context.txInfo().referenceInputs()) {
            if (ValuesLib.assetOf(
                    input.resolved().value(),
                    rootThreadPolicyId,
                    rootThreadAssetName).equals(BigInteger.ONE)
                    && atScriptAddress(
                    input.resolved(), rootScriptHash)) {
                count = count.add(BigInteger.ONE);
                result = root(input.resolved().datum());
            }
        }
        return count.equals(BigInteger.ONE) ? result : Optional.empty();
    }

    private static Optional<Root> root(OutputDatum datum) {
        if (datum instanceof OutputDatum.OutputDatumInline inline) {
            return rootData(inline.datum());
        }
        return Optional.empty();
    }

    private static Optional<Root> rootData(PlutusData value) {
        PlutusData fields = Builtins.constrFields(value);
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chain = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger epoch = Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        BigInteger height = Builtins.unIData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] stateRoot = Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        PlutusData f6 = Builtins.tailList(f5);
        PlutusData f7 = Builtins.tailList(f6);
        BigInteger generation = Builtins.unIData(Builtins.headList(f7));
        PlutusData trailing = Builtins.tailList(f7);
        if (Builtins.constrTag(value) != 0
                || !version.equals(BigInteger.ONE)
                || !Builtins.nullList(trailing)
                || Builtins.lengthOfByteString(chain) < 1
                || Builtins.lengthOfByteString(chain) > 128
                || epoch.signum() < 0
                || Builtins.lengthOfByteString(stateRoot) != 32
                || height.signum() < 0
                || generation.signum() < 0) {
            return Optional.empty();
        }
        return Optional.of(new Root(
                chain, epoch, height, stateRoot, generation));
    }

    private static Optional<NullifierTransition> nullifierTransition(
            ScriptContext context
    ) {
        Optional<Nullifier> current = Optional.empty();
        Optional<Nullifier> next = Optional.empty();
        BigInteger inputCount = BigInteger.ZERO;
        BigInteger outputCount = BigInteger.ZERO;
        for (TxInInfo input : context.txInfo().inputs()) {
            if (ValuesLib.assetOf(
                    input.resolved().value(),
                    nullifierThreadPolicyId,
                    nullifierThreadAssetName).equals(BigInteger.ONE)
                    && atScriptAddress(
                    input.resolved(), nullifierScriptHash)) {
                inputCount = inputCount.add(BigInteger.ONE);
                current = nullifier(input.resolved().datum());
            }
        }
        for (TxOut output : context.txInfo().outputs()) {
            if (ValuesLib.assetOf(
                    output.value(),
                    nullifierThreadPolicyId,
                    nullifierThreadAssetName).equals(BigInteger.ONE)
                    && atScriptAddress(
                    output, nullifierScriptHash)) {
                outputCount = outputCount.add(BigInteger.ONE);
                next = nullifier(output.datum());
            }
        }
        return inputCount.equals(BigInteger.ONE)
                && outputCount.equals(BigInteger.ONE)
                && current.isPresent() && next.isPresent()
                ? Optional.of(new NullifierTransition(
                current.get(), next.get()))
                : Optional.empty();
    }

    private static Optional<Nullifier> nullifier(OutputDatum datum) {
        if (datum instanceof OutputDatum.OutputDatumInline inline) {
            return nullifierData(inline.datum());
        }
        return Optional.empty();
    }

    private static Optional<Nullifier> nullifierData(PlutusData value) {
        PlutusData fields = Builtins.constrFields(value);
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chain = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger epoch = Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        BigInteger sequence = Builtins.unIData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        BigInteger generation = Builtins.unIData(Builtins.headList(f4));
        PlutusData trailing = Builtins.tailList(f4);
        if (Builtins.constrTag(value) != 0
                || !version.equals(BigInteger.ONE)
                || !Builtins.nullList(trailing)
                || Builtins.lengthOfByteString(chain) < 1
                || Builtins.lengthOfByteString(chain) > 128
                || epoch.signum() < 0
                || sequence.signum() < 0
                || generation.signum() < 0) {
            return Optional.empty();
        }
        return Optional.of(new Nullifier(
                chain, epoch, sequence, generation));
    }

    private static boolean settlementMatches(
            OutputDatum datum,
            Claim claim
    ) {
        if (datum instanceof OutputDatum.OutputDatumInline inline) {
            return settlementDataMatches(inline.datum(), claim);
        }
        return false;
    }

    private static boolean settlementDataMatches(
            PlutusData value,
            Claim claim
    ) {
        PlutusData fields = Builtins.constrFields(value);
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chain = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger epoch = Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        byte[] claimId = Builtins.unBData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        PlutusData destination = Builtins.headList(f4);
        PlutusData f5 = Builtins.tailList(f4);
        BigInteger lovelace = Builtins.unIData(Builtins.headList(f5));
        PlutusData trailing = Builtins.tailList(f5);
        return Builtins.constrTag(value) == 2
                && version.equals(BigInteger.ONE)
                && Builtins.nullList(trailing)
                && Builtins.equalsByteString(chain, claim.chainId())
                && epoch.equals(claim.bridgeEpoch())
                && Builtins.equalsByteString(claimId, claim.claimId())
                && Builtins.equalsData(
                destination, claim.destination())
                && lovelace.equals(claim.lovelace());
    }

    private static boolean atScriptAddress(
            TxOut output,
            byte[] scriptHash
    ) {
        PlutusData credential =
                output.address().credential().toPlutusData();
        PlutusData fields = Builtins.constrFields(credential);
        byte[] observed = Builtins.unBData(
                Builtins.headList(fields));
        return Builtins.constrTag(credential) == 1
                && Builtins.nullList(Builtins.tailList(fields))
                && Builtins.equalsByteString(observed, scriptHash);
    }

    private static Optional<Proof> proof(PlutusData value) {
        if (Builtins.constrTag(value) != 0) {
            return Optional.empty();
        }
        PlutusData fields = Builtins.constrFields(value);
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        Optional<Claim> claim = claim(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        byte[] key = Builtins.unBData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        byte[] committedValue = Builtins.unBData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] leafSuffix = Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        PlutusData foldData = Builtins.headList(f5);
        PlutusData trailing = Builtins.tailList(f5);
        if (claim.isEmpty() || !Builtins.nullList(trailing)) {
            return Optional.empty();
        }
        JulcList<Fold> folds = JulcList.empty();
        boolean foldsValid = true;
        for (PlutusData item : Builtins.asList(foldData)) {
            Optional<Fold> fold = fold(item);
            if (fold.isEmpty()) {
                foldsValid = false;
                break;
            }
            folds = folds.prepend(fold.get());
        }
        if (!foldsValid) {
            return Optional.empty();
        }
        return Optional.of(new Proof(
                version,
                claim.get(),
                key,
                committedValue,
                leafSuffix,
                folds.reverse()));
    }

    private static Optional<Claim> claim(PlutusData value) {
        if (Builtins.constrTag(value) != 3) {
            return Optional.empty();
        }
        PlutusData fields = Builtins.constrFields(value);
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chainId = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger bridgeEpoch = Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        BigInteger sequence = Builtins.unIData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] claimId = Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        PlutusData destination = Builtins.headList(f5);
        PlutusData f6 = Builtins.tailList(f5);
        BigInteger lovelace = Builtins.unIData(Builtins.headList(f6));
        PlutusData trailing = Builtins.tailList(f6);
        if (!Builtins.nullList(trailing)) {
            return Optional.empty();
        }
        return Optional.of(new Claim(
                version,
                chainId,
                bridgeEpoch,
                sequence,
                claimId,
                destination,
                lovelace));
    }

    private static Optional<Fold> fold(PlutusData value) {
        if (Builtins.constrTag(value) != 0) {
            return Optional.empty();
        }
        PlutusData fields = Builtins.constrFields(value);
        BigInteger cursor = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] prefix = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger nibble = Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        byte[] neighbor1 = Builtins.unBData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] neighbor2 = Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        byte[] neighbor3 = Builtins.unBData(Builtins.headList(f5));
        PlutusData f6 = Builtins.tailList(f5);
        byte[] neighbor4 = Builtins.unBData(Builtins.headList(f6));
        PlutusData f7 = Builtins.tailList(f6);
        byte[] branchValueHash = Builtins.unBData(Builtins.headList(f7));
        PlutusData trailing = Builtins.tailList(f7);
        if (!Builtins.nullList(trailing)) {
            return Optional.empty();
        }
        return Optional.of(new Fold(
                cursor,
                prefix,
                nibble,
                neighbor1,
                neighbor2,
                neighbor3,
                neighbor4,
                branchValueHash));
    }

    private static boolean validPrefix(
            byte[] pathHash,
            long cursor,
            byte[] prefix
    ) {
        long length = Builtins.lengthOfByteString(prefix);
        long index = 0;
        boolean valid = true;
        while (index < length) {
            long nibble = Builtins.indexByteString(prefix, index);
            if (nibble < 0 || nibble > 15
                    || nibble != pathNibble(pathHash, cursor + index)) {
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

    private static byte[] commitLeaf(
            byte[] encodedSuffix,
            byte[] valueHash
    ) {
        return Builtins.blake2b_256(
                Builtins.appendByteString(
                        encodedSuffix,
                        valueHash));
    }

    private static byte[] aggregate(
            long nibble,
            byte[] me,
            byte[] lvl1,
            byte[] lvl2,
            byte[] lvl3,
            byte[] lvl4
    ) {
        if (nibble == 0) return hash(hash(hash(hash(me, lvl4), lvl3), lvl2), lvl1);
        if (nibble == 1) return hash(hash(hash(hash(lvl4, me), lvl3), lvl2), lvl1);
        if (nibble == 2) return hash(hash(hash(lvl3, hash(me, lvl4)), lvl2), lvl1);
        if (nibble == 3) return hash(hash(hash(lvl3, hash(lvl4, me)), lvl2), lvl1);
        if (nibble == 4) return hash(hash(lvl2, hash(hash(me, lvl4), lvl3)), lvl1);
        if (nibble == 5) return hash(hash(lvl2, hash(hash(lvl4, me), lvl3)), lvl1);
        if (nibble == 6) return hash(hash(lvl2, hash(lvl3, hash(me, lvl4))), lvl1);
        if (nibble == 7) return hash(hash(lvl2, hash(lvl3, hash(lvl4, me))), lvl1);
        if (nibble == 8) return hash(lvl1, hash(hash(hash(me, lvl4), lvl3), lvl2));
        if (nibble == 9) return hash(lvl1, hash(hash(hash(lvl4, me), lvl3), lvl2));
        if (nibble == 10) return hash(lvl1, hash(hash(lvl3, hash(me, lvl4)), lvl2));
        if (nibble == 11) return hash(lvl1, hash(hash(lvl3, hash(lvl4, me)), lvl2));
        if (nibble == 12) return hash(lvl1, hash(lvl2, hash(hash(me, lvl4), lvl3)));
        if (nibble == 13) return hash(lvl1, hash(lvl2, hash(hash(lvl4, me), lvl3)));
        if (nibble == 14) return hash(lvl1, hash(lvl2, hash(lvl3, hash(me, lvl4))));
        return hash(lvl1, hash(lvl2, hash(lvl3, hash(lvl4, me))));
    }

    private static boolean hashLength(byte[] value) {
        return Builtins.lengthOfByteString(value) == 32;
    }

    private static byte[] hash(byte[] left, byte[] right) {
        return Builtins.blake2b_256(
                Builtins.appendByteString(left, right));
    }
}
