package org.yanoproject.app.api.wallet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import org.yanoproject.app.e2e.DevnetTestProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

public class WalletIndexDevnetProfile extends DevnetTestProfile {
    // Public test mnemonic. Only used on a disposable local devnet.
    static final String MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    private static boolean prepared;

    static Account account(int index) { return new Account(Networks.testnet(), index == 0 ? MNEMONIC
            : "legal winner thank year wave sausage worth useful legal winner thank yellow", 0); }

    @Override public synchronized Map<String, String> getConfigOverrides() {
        Map<String, String> result = new HashMap<>(super.getConfigOverrides());
        if (!prepared) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                ObjectNode genesis = (ObjectNode) mapper.readTree(Files.readAllBytes(TEMP_SHELLEY_GENESIS));
                genesis.withObject("initialFunds").put(HexFormat.of().formatHex(account(0).getBaseAddress().getBytes()), 1_000_000_000L);
                Files.write(TEMP_SHELLEY_GENESIS, mapper.writeValueAsBytes(genesis));
                prepared = true;
            } catch (Exception failure) { throw new IllegalStateException("Cannot prepare wallet test genesis", failure); }
        }
        result.put("yano.scan.index.enabled", "true");
        result.put("yano.address-first-seen.enabled", "true");
        result.put("yano.history.projection.enabled", "false");
        result.put("yano.chain.block-body-prune-depth", "0");
        result.put("yano.server.enabled", "true");
        result.put("yano.server.port", "24119");
        return result;
    }
}
