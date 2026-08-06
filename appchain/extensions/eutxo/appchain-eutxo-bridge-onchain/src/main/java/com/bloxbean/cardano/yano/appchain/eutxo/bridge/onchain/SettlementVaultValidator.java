package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.CryptoLib;
import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain.mpf.MerklePatriciaForestry;
import com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain.mpf.ProofStep;

import java.math.BigInteger;
import java.util.Optional;

/**
 * ADR-UTXO-009 V1 settlement vault (§7): ONE vault, TWO authorization paths.
 *
 * <p>Settle{claims}: the federation threshold (member keys read from the
 * root-thread REFERENCE input) must have signed. Exit{[claim, proof]}: armed
 * only when the root thread is stale beyond its governed fallback delay;
 * every claim is proven present under the accepted state root by an MPF
 * inclusion proof of its v2 commitment digest (which binds payout AND
 * bounty).
 *
 * <p>Both paths enforce: positional payouts (output[i] pays claim[i]
 * exactly), a paired nullifier-shard spend, and remainder conservation —
 * the continuing vault output keeps {@code inputs − Σ(payout+bounty)} under
 * the batch marker Constr0[1, count, concat(claimIds)]. Σbounty floats free
 * for the executor; the L1 fee never comes from the vault.
 */
@SpendingValidator
public final class SettlementVaultValidator {
    @Param
    static byte[] rootThreadPolicyId;

    @Param
    static byte[] rootThreadAssetName;

    @Param
    static byte[] shardScriptHash;

    @Param
    static byte[] withdrawalKeyPrefix;

    @Param
    static byte[] claimDomain;

    /** Tier-1 frozen batch caps (single byte each), supplied at deploy. */
    @Param
    static byte[] maxSettleBatch;

    @Param
    static byte[] maxExitBatch;

    record Claim(
            BigInteger bridgeEpoch,
            BigInteger sequence,
            byte[] claimId,
            PlutusData destination,
            BigInteger payout,
            BigInteger bounty
    ) {
    }

    record ExitClaim(Claim claim, JulcList<ProofStep> proof) {
    }

    sealed interface VaultRedeemer permits Settle, Exit {
    }

    record Settle(JulcList<Claim> claims) implements VaultRedeemer {
    }

    record Exit(JulcList<ExitClaim> exits) implements VaultRedeemer {
    }

    record Root(
            byte[] chainId,
            BigInteger bridgeEpoch,
            byte[] stateRoot,
            byte[] memberKeys,
            BigInteger memberCount,
            BigInteger threshold,
            BigInteger updatedAtSlot,
            BigInteger fallbackDelaySlots
    ) {
    }

    private SettlementVaultValidator() {
    }

    @Entrypoint
    public static boolean validate(
            PlutusData datum, VaultRedeemer redeemer, ScriptContext context) {
        Optional<Root> rootOptional = acceptedRoot(context);
        if (rootOptional.isEmpty() || !shardIsSpent(context)) {
            return false;
        }
        Root root = rootOptional.get();
        Optional<TxInInfo> ownInput = ContextsLib.findOwnInput(context);
        if (ownInput.isEmpty()) {
            return false;
        }
        byte[] ownHash = scriptHashOf(
                ownInput.get().resolved().address().credential().toPlutusData());
        if (Builtins.lengthOfByteString(ownHash) != 28) {
            return false;
        }
        BigInteger inSum = vaultInputSum(context.txInfo(), ownHash);
        JulcList<TxOut> outputs = context.txInfo().outputs();

        return switch (redeemer) {
            case Settle settle -> settleValid(
                    settle.claims(), root, context.txInfo(), outputs, inSum, ownHash);
            case Exit exit -> exitValid(
                    exit.exits(), root, context.txInfo(), outputs, inSum, ownHash);
        };
    }

    // ------------------------------------------------------------------

    private static boolean settleValid(
            JulcList<Claim> claims, Root root, TxInfo txInfo,
            JulcList<TxOut> outputs, BigInteger inSum, byte[] ownHash) {
        long count = claims.size();
        if (count < 1 || count > Builtins.indexByteString(maxSettleBatch, 0)
                || count >= outputs.size()
                || !claimsPositional(
                claims, root.bridgeEpoch(), outputs, 0)) {
            return false;
        }
        BigInteger total = claimsTotal(claims);
        byte[] joined = claimsJoined(claims);
        return continuingValid(outputs, count, ownHash, inSum, total, joined)
                && thresholdSigned(txInfo, root.memberKeys(),
                root.memberCount(), root.threshold());
    }

    private static boolean exitValid(
            JulcList<ExitClaim> exits, Root root, TxInfo txInfo,
            JulcList<TxOut> outputs, BigInteger inSum, byte[] ownHash) {
        long count = exits.size();
        if (count < 1 || count > Builtins.indexByteString(maxExitBatch, 0)
                || count >= outputs.size()
                || !exitsPositional(
                exits, root.bridgeEpoch(), root.chainId(),
                root.stateRoot(), outputs, 0)) {
            return false;
        }
        BigInteger total = exitsTotal(exits);
        byte[] joined = exitsJoined(exits);
        BigInteger now = IntervalLib.finiteLowerBound(txInfo.validRange());
        return continuingValid(outputs, count, ownHash, inSum, total, joined)
                && now.subtract(root.updatedAtSlot())
                .compareTo(root.fallbackDelaySlots()) > 0;
    }

    private static boolean claimsPositional(
            JulcList<Claim> claims, BigInteger epoch,
            JulcList<TxOut> outputs, long index) {
        if (index >= claims.size()) {
            return true;
        }
        Claim claim = claims.get(index);
        return claimShapeValid(claim, epoch)
                && payoutMatches(outputs.get(index), claim)
                && claimsPositional(claims, epoch, outputs, index + 1);
    }

    private static boolean exitsPositional(
            JulcList<ExitClaim> exits, BigInteger epoch, byte[] chainId,
            byte[] stateRoot, JulcList<TxOut> outputs, long index) {
        if (index >= exits.size()) {
            return true;
        }
        ExitClaim exit = exits.get(index);
        Claim claim = exit.claim();
        if (!claimShapeValid(claim, epoch)
                || !payoutMatches(outputs.get(index), claim)) {
            return false;
        }
        byte[] digest = claimDigestV2(
                chainId, claim.bridgeEpoch(), claim.sequence(),
                claim.claimId(), claim.destination(),
                claim.payout(), claim.bounty());
        byte[] key = Builtins.appendByteString(
                withdrawalKeyPrefix, claim.claimId());
        return MerklePatriciaForestry.has(stateRoot, key, digest, exit.proof())
                && exitsPositional(exits, epoch, chainId, stateRoot,
                outputs, index + 1);
    }

    private static boolean claimShapeValid(Claim claim, BigInteger epoch) {
        return claim.bridgeEpoch().equals(epoch)
                && Builtins.lengthOfByteString(claim.claimId()) == 32
                && claim.sequence().signum() >= 0
                && claim.payout().signum() > 0
                && claim.bounty().signum() >= 0;
    }

    private static boolean payoutMatches(TxOut output, Claim claim) {
        return Builtins.equalsData(
                output.address().toPlutusData(), claim.destination())
                && ValuesLib.lovelaceOf(output.value()).equals(claim.payout());
    }

    private static BigInteger claimsTotal(JulcList<Claim> claims) {
        BigInteger sum = BigInteger.ZERO;
        for (Claim claim : claims) {
            sum = sum.add(claim.payout()).add(claim.bounty());
        }
        return sum;
    }

    private static BigInteger exitsTotal(JulcList<ExitClaim> exits) {
        BigInteger sum = BigInteger.ZERO;
        for (ExitClaim exit : exits) {
            sum = sum.add(exit.claim().payout()).add(exit.claim().bounty());
        }
        return sum;
    }

    private static byte[] claimsJoined(JulcList<Claim> claims) {
        byte[] joined = Builtins.emptyByteString();
        for (Claim claim : claims) {
            joined = Builtins.appendByteString(joined, claim.claimId());
        }
        return joined;
    }

    private static byte[] exitsJoined(JulcList<ExitClaim> exits) {
        byte[] joined = Builtins.emptyByteString();
        for (ExitClaim exit : exits) {
            joined = Builtins.appendByteString(joined, exit.claim().claimId());
        }
        return joined;
    }

    private static boolean continuingValid(
            JulcList<TxOut> outputs, long claimCount, byte[] ownHash,
            BigInteger inSum, BigInteger total, byte[] joined) {
        TxOut continuing = outputs.get(claimCount);
        BigInteger remainder = inSum.subtract(total);
        return remainder.signum() > 0
                && Builtins.equalsByteString(
                scriptHashOf(continuing.address().credential().toPlutusData()),
                ownHash)
                && ValuesLib.lovelaceOf(continuing.value()).equals(remainder)
                && batchMarkerValid(
                continuing.datum(), BigInteger.valueOf(claimCount), joined);
    }

    private static Optional<Root> acceptedRoot(ScriptContext context) {
        return acceptedRootIn(context.txInfo().referenceInputs());
    }

    private static Optional<Root> acceptedRootIn(JulcList<TxInInfo> references) {
        if (references.isEmpty()) {
            return Optional.empty();
        }
        TxOut resolved = references.head().resolved();
        if (ValuesLib.assetOf(
                resolved.value(), rootThreadPolicyId, rootThreadAssetName)
                .equals(BigInteger.ONE)) {
            return decodeRootDatum(resolved.datum());
        }
        return acceptedRootIn(references.tail());
    }

    private static Optional<Root> decodeRootDatum(OutputDatum outputDatum) {
        if (outputDatum instanceof OutputDatum.OutputDatumInline inline) {
            return decodeRoot(inline.datum());
        }
        return Optional.empty();
    }

    private static Optional<Root> decodeRoot(PlutusData data) {
        if (Builtins.constrTag(data) != 0) {
            return Optional.empty();
        }
        PlutusData fields = Builtins.sndPair(Builtins.unConstrData(data));
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chainId = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger epoch = Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        PlutusData f4 = Builtins.tailList(f3);
        byte[] stateRoot = Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        PlutusData members = Builtins.unListData(Builtins.headList(f5));
        byte[] memberBytes = joinMembers(members, Builtins.emptyByteString());
        BigInteger memberCount = countMembers(members, BigInteger.ZERO);
        PlutusData f6 = Builtins.tailList(f5);
        BigInteger threshold = Builtins.unIData(Builtins.headList(f6));
        PlutusData f7 = Builtins.tailList(f6);
        PlutusData f8 = Builtins.tailList(f7);
        BigInteger updatedAtSlot = Builtins.unIData(Builtins.headList(f8));
        PlutusData f9 = Builtins.tailList(f8);
        BigInteger fallbackDelay = Builtins.unIData(Builtins.headList(f9));
        if (!version.equals(BigInteger.ONE)
                || !Builtins.nullList(Builtins.tailList(f9))
                || Builtins.lengthOfByteString(stateRoot) != 32) {
            return Optional.empty();
        }
        return Optional.of(new Root(
                chainId, epoch, stateRoot, memberBytes, memberCount,
                threshold, updatedAtSlot, fallbackDelay));
    }

    private static boolean shardIsSpent(ScriptContext context) {
        boolean found = false;
        for (var input : context.txInfo().inputs()) {
            PlutusData credential =
                    input.resolved().address().credential().toPlutusData();
            if (Builtins.constrTag(credential) == 1
                    && Builtins.equalsByteString(
                    Builtins.unBData(Builtins.headList(
                            Builtins.constrFields(credential))),
                    shardScriptHash)) {
                found = true;
                break;
            }
        }
        return found;
    }

    private static BigInteger vaultInputSum(TxInfo txInfo, byte[] ownHash) {
        BigInteger sum = BigInteger.ZERO;
        for (var input : txInfo.inputs()) {
            PlutusData credential =
                    input.resolved().address().credential().toPlutusData();
            if (Builtins.constrTag(credential) == 1
                    && Builtins.equalsByteString(
                    Builtins.unBData(Builtins.headList(
                            Builtins.constrFields(credential))),
                    ownHash)) {
                sum = sum.add(ValuesLib.lovelaceOf(input.resolved().value()));
            }
        }
        return sum;
    }

    private static byte[] scriptHashOf(PlutusData credential) {
        if (Builtins.constrTag(credential) != 1) {
            return Builtins.emptyByteString();
        }
        return Builtins.unBData(
                Builtins.headList(Builtins.constrFields(credential)));
    }

    private static boolean batchMarkerValid(
            OutputDatum outputDatum, BigInteger count, byte[] joinedIds) {
        if (outputDatum instanceof OutputDatum.OutputDatumInline inline) {
            return markerDataValid(inline.datum(), count, joinedIds);
        }
        return false;
    }

    private static boolean markerDataValid(
            PlutusData data, BigInteger count, byte[] joinedIds) {
        PlutusData fields = Builtins.constrFields(data);
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        BigInteger markerCount = Builtins.unIData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        byte[] markerIds = Builtins.unBData(Builtins.headList(f2));
        return Builtins.constrTag(data) == 0
                && Builtins.nullList(Builtins.tailList(f2))
                && version.equals(BigInteger.ONE)
                && markerCount.equals(count)
                && Builtins.equalsByteString(markerIds, joinedIds);
    }

    private static byte[] joinMembers(PlutusData members, byte[] acc) {
        if (Builtins.nullList(members)) {
            return acc;
        }
        byte[] key = Builtins.unBData(Builtins.headList(members));
        return joinMembers(Builtins.tailList(members),
                Builtins.appendByteString(acc, key));
    }

    private static BigInteger countMembers(PlutusData members, BigInteger acc) {
        if (Builtins.nullList(members)) {
            return acc;
        }
        return countMembers(Builtins.tailList(members), acc.add(BigInteger.ONE));
    }

    private static boolean thresholdSigned(
            TxInfo txInfo, byte[] memberKeys, BigInteger memberCount,
            BigInteger threshold) {
        long total = memberCount.longValue();
        BigInteger signers = countSigners(txInfo, memberKeys, total, 0);
        return total >= 1
                && ByteStringLib.length(memberKeys) == total * 32
                && threshold.compareTo(BigInteger.ONE) >= 0
                && threshold.compareTo(memberCount) <= 0
                && signers.signum() >= 0
                && signers.compareTo(threshold) >= 0;
    }

    /** Iterate concatenated 32-byte keys; sortedness violation yields -1. */
    private static BigInteger countSigners(
            TxInfo txInfo, byte[] memberKeys, long total, long index) {
        if (index >= total) {
            return BigInteger.ZERO;
        }
        byte[] key = ByteStringLib.slice(memberKeys, index * 32, 32);
        if (index > 0) {
            byte[] previous = ByteStringLib.slice(
                    memberKeys, (index - 1) * 32, 32);
            if (!Builtins.lessThanByteString(previous, key)) {
                return BigInteger.valueOf(-1);
            }
        }
        BigInteger deeper = countSigners(txInfo, memberKeys, total, index + 1);
        if (deeper.signum() < 0) {
            return deeper;
        }
        if (ContextsLib.signedBy(txInfo, CryptoLib.blake2b_224(key))) {
            return deeper.add(BigInteger.ONE);
        }
        return deeper;
    }

    private static byte[] claimDigestV2(
            byte[] chainId, BigInteger epoch, BigInteger sequence,
            byte[] claimId, PlutusData destination,
            BigInteger payout, BigInteger bounty) {
        byte[] destinationFp = destinationFingerprint(destination);
        byte[] fields0 = Builtins.appendByteString(
                claimDomain, Builtins.blake2b_256(chainId));
        byte[] fields1 = Builtins.appendByteString(
                fields0, Builtins.integerToByteString(true, 8, epoch));
        byte[] fields2 = Builtins.appendByteString(
                fields1, Builtins.integerToByteString(true, 8, sequence));
        byte[] fields3 = Builtins.appendByteString(fields2, claimId);
        byte[] fields4 = Builtins.appendByteString(fields3, destinationFp);
        byte[] fields5 = Builtins.appendByteString(
                fields4, Builtins.integerToByteString(true, 8, payout));
        byte[] fields6 = Builtins.appendByteString(
                fields5, Builtins.integerToByteString(true, 8, bounty));
        return Builtins.blake2b_256(fields6);
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
                    Builtins.consByteString(0, Builtins.emptyByteString()));
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
                Builtins.consByteString(1, Builtins.emptyByteString()));
        byte[] base = Builtins.appendByteString(basePrefix, stakeCredential);
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
}
