package com.bloxbean.cardano.yano.archive.core.address;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;

/** Canonical reward-address presentation derived from a ledger credential. */
public final class StakeAddressCodec {
    private static final long MAINNET_MAGIC = 764_824_073L;

    private StakeAddressCodec() { }

    public static String encode(long networkMagic, String credentialType, byte[] credential) {
        if (credential == null) return null;
        try {
            String hash = HexUtil.encodeHexString(credential);
            Credential value = switch (credentialType) {
                case "key" -> Credential.fromKey(hash);
                case "script" -> Credential.fromScript(hash);
                default -> throw new ArchiveStoreException("unknown stake credential type " + credentialType);
            };
            return AddressProvider.getRewardAddress(value,
                    networkMagic == MAINNET_MAGIC ? Networks.mainnet() : Networks.testnet()).toBech32();
        } catch (ArchiveStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot encode canonical stake address", e);
        }
    }
}
