package com.bloxbean.cardano.yano.appchain.proofs.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;

/** Reference validator for the default nested finalized-message proof. */
@SpendingValidator
public final class BlockMessageRootAnchorValidator {
    @Param static byte[] anchorThreadPolicyId;
    @Param static byte[] anchorThreadAssetName;
    @Param static byte[] anchorScriptHash;
    @Param static byte[] expectedChainGenesisId;
    @Param static byte[] expectedApplicationId;
    @Param static byte[] expectedCommitmentProfileId;
    @Param static byte[] expectedFormatFingerprint;

    private BlockMessageRootAnchorValidator() {
    }

    @Entrypoint
    public static boolean validate(PlutusData datum,
                                   BlockMessageRootOnChainVerifier.Claim claim,
                                   ScriptContext context) {
        var anchor = MpfAnchorLib.acceptedAnchor(context, anchorThreadPolicyId,
                anchorThreadAssetName, anchorScriptHash, expectedChainGenesisId,
                expectedApplicationId, expectedCommitmentProfileId, expectedFormatFingerprint);
        return anchor.isPresent()
                && BlockMessageRootOnChainVerifier.verifyAtRoot(claim, anchor.get().stateRoot());
    }
}
