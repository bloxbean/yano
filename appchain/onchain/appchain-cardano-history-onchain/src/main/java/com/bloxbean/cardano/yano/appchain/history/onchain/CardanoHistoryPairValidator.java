package com.bloxbean.cardano.yano.appchain.history.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.yano.appchain.proofs.onchain.MpfPairOnChainVerifier;
import com.bloxbean.cardano.yano.appchain.proofs.onchain.MpfAnchorLib;

import java.math.BigInteger;

/** Fixes fact/completeness keys, epoch and predicate operands outside the redeemer. */
@SpendingValidator
public final class CardanoHistoryPairValidator {
    @Param static byte[] anchorThreadPolicyId;
    @Param static byte[] anchorThreadAssetName;
    @Param static byte[] anchorScriptHash;
    @Param static byte[] expectedChainGenesisId;
    @Param static byte[] expectedApplicationId;
    @Param static byte[] expectedCommitmentProfileId;
    @Param static byte[] expectedFormatFingerprint;
    @Param static byte[] expectedFactKey;
    @Param static byte[] expectedCompletenessKey;
    @Param static BigInteger expectedEpoch;
    @Param static BigInteger expectedPredicate;
    @Param static BigInteger expectedCoin;
    @Param static byte[] expectedAuxiliary;

    private CardanoHistoryPairValidator() { }

    @Entrypoint
    public static boolean validate(PlutusData datum,
                                   MpfPairOnChainVerifier.ProofPair proofs,
                                   ScriptContext context) {
        var anchor = MpfAnchorLib.acceptedAnchor(context, anchorThreadPolicyId,
                anchorThreadAssetName, anchorScriptHash, expectedChainGenesisId,
                expectedApplicationId, expectedCommitmentProfileId, expectedFormatFingerprint);
        return anchor.isPresent()
                && MpfPairOnChainVerifier.verifyBoundAtRoot(proofs,
                anchor.get().stateRoot(), expectedFactKey, expectedCompletenessKey,
                expectedEpoch, expectedPredicate, expectedCoin, expectedAuxiliary);
    }
}
