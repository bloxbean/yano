package com.bloxbean.cardano.yano.api.util;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Bech32;
import com.bloxbean.cardano.client.governance.GovId;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

/**
 * Shared Cardano bech32 id formatter.
 *
 * <p>These helpers are intended for API/export presentation layers. Canonical
 * storage should keep compact ledger keys such as credential and pool hashes.
 */
public final class CardanoBech32Ids {
    public static final int KEY_HASH = 0;
    public static final int SCRIPT_HASH = 1;

    private CardanoBech32Ids() {
    }

    public static String stakeAddress(int credType, String credHash, long protocolMagic) {
        if (isBlank(credHash)) return null;
        try {
            Credential credential = switch (credType) {
                case KEY_HASH -> Credential.fromKey(credHash);
                case SCRIPT_HASH -> Credential.fromScript(credHash);
                default -> null;
            };
            if (credential == null) return null;
            return AddressProvider.getRewardAddress(credential, network(protocolMagic)).toBech32();
        } catch (Exception e) {
            return null;
        }
    }

    public static String poolId(String poolHash) {
        if (isBlank(poolHash)) return null;
        try {
            return Bech32.encode(HexUtil.decodeHexString(poolHash), "pool");
        } catch (Exception e) {
            return null;
        }
    }

    public static String drepId(int drepType, String drepHash) {
        if (isBlank(drepHash)) return null;
        try {
            byte[] hash = HexUtil.decodeHexString(drepHash);
            return switch (drepType) {
                case KEY_HASH -> GovId.drepFromKeyHash(hash);
                case SCRIPT_HASH -> GovId.drepFromScriptHash(hash);
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static Network network(long protocolMagic) {
        return protocolMagic == Constants.MAINNET_PROTOCOL_MAGIC
                ? Networks.mainnet()
                : Networks.testnet();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
