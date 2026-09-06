package com.bloxbean.cardano.yano.api.wallet;

import java.util.HexFormat;
import java.util.Locale;

/** Role and key/script type are part of credential identity, not just the hash. */
public record WalletCredential(String role, String type, String hash) {
    public WalletCredential {
        if (!"payment".equals(role) && !"stake".equals(role) && !"drep".equals(role)) {
            throw new IllegalArgumentException("Credential role must be payment, stake or drep");
        }
        if (!"key".equals(type) && !"script".equals(type)) {
            throw new IllegalArgumentException("Credential type must be key or script");
        }
        if (!WalletHex.valid(hash, 28)) {
            throw new IllegalArgumentException("Credential hash must be 28 bytes of hex");
        }
        hash = hash.toLowerCase(Locale.ROOT);
    }

    public byte[] filterElement() {
        int roleTag = switch (role) { case "payment" -> 0; case "stake" -> 2; default -> 4; };
        byte[] result = new byte[29];
        result[0] = (byte) (roleTag + (type.equals("script") ? 1 : 0));
        System.arraycopy(HexFormat.of().parseHex(hash), 0, result, 1, 28);
        return result;
    }
}
