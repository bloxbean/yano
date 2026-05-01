package com.bloxbean.cardano.yano.wallet.bridge;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public class LocalCip30BridgeService {
    private final BridgeSessionRegistry sessionRegistry;
    private final BridgeWalletBackend walletBackend;
    private final BridgeApprovalHandler approvalHandler;

    public LocalCip30BridgeService(
            BridgeSessionRegistry sessionRegistry,
            BridgeWalletBackend walletBackend) {
        this(sessionRegistry, walletBackend, BridgeApprovalHandler.denyAll());
    }

    public LocalCip30BridgeService(
            BridgeSessionRegistry sessionRegistry,
            BridgeWalletBackend walletBackend,
            BridgeApprovalHandler approvalHandler) {
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry is required");
        this.walletBackend = Objects.requireNonNull(walletBackend, "walletBackend is required");
        this.approvalHandler = Objects.requireNonNull(approvalHandler, "approvalHandler is required");
    }

    public BridgeSession enable(String origin, Set<BridgePermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            throw new BridgeException(BridgeError.INVALID_REQUEST, BridgeMethod.ENABLE, "At least one permission is required");
        }
        return sessionRegistry.createSession(origin, permissions);
    }

    public int getNetworkId(String token) {
        requirePermission(token, BridgeMethod.GET_NETWORK_ID, BridgePermission.READ_WALLET);
        return walletBackend.networkId();
    }

    public String getBalance(String token) {
        requirePermission(token, BridgeMethod.GET_BALANCE, BridgePermission.READ_WALLET);
        return walletBackend.balanceCborHex();
    }

    public List<String> getUtxos(String token) {
        requirePermission(token, BridgeMethod.GET_UTXOS, BridgePermission.READ_WALLET);
        return List.copyOf(walletBackend.utxosCborHex());
    }

    public String getChangeAddress(String token) {
        requirePermission(token, BridgeMethod.GET_CHANGE_ADDRESS, BridgePermission.READ_WALLET);
        return walletBackend.changeAddressHex();
    }

    public List<String> getRewardAddresses(String token) {
        requirePermission(token, BridgeMethod.GET_REWARD_ADDRESSES, BridgePermission.READ_WALLET);
        return List.copyOf(walletBackend.rewardAddressHexes());
    }

    public BridgeSignTxResult signTx(String token, String txCborHex, boolean partialSign) {
        requireHexPayload(BridgeMethod.SIGN_TX, txCborHex);
        BridgeSession session = requirePermission(token, BridgeMethod.SIGN_TX, BridgePermission.SIGN_TX);
        requireApproval(session, BridgeMethod.SIGN_TX, txCborHex, partialSign);
        return walletBackend.signTx(txCborHex, partialSign);
    }

    public String submitTx(String token, String txCborHex) {
        requireHexPayload(BridgeMethod.SUBMIT_TX, txCborHex);
        BridgeSession session = requirePermission(token, BridgeMethod.SUBMIT_TX, BridgePermission.SUBMIT_TX);
        requireApproval(session, BridgeMethod.SUBMIT_TX, txCborHex, false);
        String txHash = walletBackend.submitTx(txCborHex);
        if (txHash == null || txHash.isBlank()) {
            throw new BridgeException(BridgeError.BACKEND_ERROR, BridgeMethod.SUBMIT_TX, "Transaction submit did not return a tx hash");
        }
        return txHash;
    }

    public void verifySessionOrigin(String token, BridgeMethod method, String origin) {
        BridgeSession session = sessionRegistry.findByToken(token)
                .orElseThrow(() -> new BridgeException(BridgeError.UNAUTHORIZED, method, "Bridge session is not authorized"));
        if (origin == null || origin.isBlank()) {
            throw new BridgeException(BridgeError.REFUSED, method, "Browser Origin header is required");
        }
        if (!session.origin().equals(origin)) {
            throw new BridgeException(BridgeError.REFUSED, method, "Bridge request origin does not match the enabled session");
        }
    }

    private BridgeSession requirePermission(String token, BridgeMethod method, BridgePermission permission) {
        BridgeSession session = sessionRegistry.findByToken(token)
                .orElseThrow(() -> new BridgeException(BridgeError.UNAUTHORIZED, method, "Bridge session is not authorized"));
        if (!session.allows(permission)) {
            throw new BridgeException(BridgeError.UNAUTHORIZED, method, "Bridge session is missing permission: " + permission);
        }
        return session;
    }

    private void requireApproval(BridgeSession session, BridgeMethod method, String txCborHex, boolean partialSign) {
        BridgeApprovalRequest request = new BridgeApprovalRequest(session, method, txCborHex, partialSign);
        if (!approvalHandler.approve(request)) {
            throw new BridgeException(BridgeError.REFUSED, method, "Bridge request was refused");
        }
    }

    private void requireHexPayload(BridgeMethod method, String cborHex) {
        if (cborHex == null || cborHex.isBlank()) {
            throw new BridgeException(BridgeError.INVALID_REQUEST, method, "Transaction CBOR hex is required");
        }
        String normalized = cborHex.trim();
        if ((normalized.length() & 1) == 1) {
            throw new BridgeException(BridgeError.INVALID_REQUEST, method, "Transaction CBOR hex must have an even length");
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.digit(normalized.charAt(i), 16) < 0) {
                throw new BridgeException(BridgeError.INVALID_REQUEST, method, "Transaction CBOR hex contains non-hex characters");
            }
        }
    }
}
