package com.bloxbean.cardano.yano.api.util;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Bech32;
import com.bloxbean.cardano.client.governance.GovId;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.transaction.spec.governance.DRepType;
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

    public static String poolHash(String poolIdOrHash) {
        if (isBlank(poolIdOrHash)) return null;
        try {
            if (CardanoHex.isHash28Bytes(poolIdOrHash)) {
                return poolIdOrHash.toLowerCase();
            }
            Bech32.Bech32Data data = Bech32.decode(poolIdOrHash);
            if (data == null || !"pool".equals(data.hrp) || data.data == null || data.data.length != 28) {
                return null;
            }
            return HexUtil.encodeHexString(data.data);
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

    public static String drepHex(int drepType, String drepHash) {
        String drepId = drepId(drepType, drepHash);
        if (drepId == null) return null;
        try {
            Bech32.Bech32Data data = Bech32.decode(drepId);
            return data != null && data.data != null ? HexUtil.encodeHexString(data.data) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String govActionId(String txHash, int certIndex) {
        if (isBlank(txHash)) return null;
        try {
            return GovId.govAction(txHash, certIndex);
        } catch (Exception e) {
            return null;
        }
    }

    public static String bech32Address(String addressOrHex) {
        if (isBlank(addressOrHex)) return null;
        try {
            if (CardanoHex.isHex(addressOrHex)) {
                return new Address(HexUtil.decodeHexString(addressOrHex)).toBech32();
            }
            return new Address(addressOrHex).toBech32();
        } catch (Exception e) {
            return null;
        }
    }

    public static String committeeHotId(int credType, String credHash) {
        if (isBlank(credHash)) return null;
        try {
            byte[] hash = HexUtil.decodeHexString(credHash);
            return switch (credType) {
                case KEY_HASH -> GovId.ccHotFromKeyHash(hash);
                case SCRIPT_HASH -> GovId.ccHotFromScriptHash(hash);
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    public static ParsedDRepId parseDRepId(String drepId) {
        if (isBlank(drepId)) return null;
        try {
            DRep drep = GovId.toDrep(drepId);
            if (drep == null || drep.getType() == null) return null;
            DRepType type = drep.getType();
            if (type == DRepType.ADDR_KEYHASH) {
                return new ParsedDRepId(KEY_HASH, drep.getHash());
            }
            if (type == DRepType.SCRIPTHASH) {
                return new ParsedDRepId(SCRIPT_HASH, drep.getHash());
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public record ParsedDRepId(int drepType, String drepHash) {}

    private static Network network(long protocolMagic) {
        return protocolMagic == Constants.MAINNET_PROTOCOL_MAGIC
                ? Networks.mainnet()
                : Networks.testnet();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
