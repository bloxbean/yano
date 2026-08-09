package com.bloxbean.cardano.yano.appchain.proofs.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;

/** Minimal generic MPF validator bound to one application anchor and one exact state key. */
@SpendingValidator
public final class MpfAnchorValidator {
    @Param static byte[] anchorThreadPolicyId;
    @Param static byte[] anchorThreadAssetName;
    @Param static byte[] anchorScriptHash;
    @Param static byte[] expectedChainGenesisId;
    @Param static byte[] expectedApplicationId;
    @Param static byte[] expectedCommitmentProfileId;
    @Param static byte[] expectedFormatFingerprint;
    @Param static byte[] expectedStateKey;

    private MpfAnchorValidator() { }

    @Entrypoint
    public static boolean validate(PlutusData datum, MpfOnChainVerifier.Proof proof,
                                   ScriptContext context) {
        var anchor = MpfAnchorLib.acceptedAnchor(context, anchorThreadPolicyId,
                anchorThreadAssetName, anchorScriptHash, expectedChainGenesisId,
                expectedApplicationId, expectedCommitmentProfileId, expectedFormatFingerprint);
        return anchor.isPresent()
                && Builtins.equalsByteString(proof.key(), expectedStateKey)
                && MpfOnChainVerifier.verifyInclusion(proof, anchor.get().stateRoot());
    }
}
