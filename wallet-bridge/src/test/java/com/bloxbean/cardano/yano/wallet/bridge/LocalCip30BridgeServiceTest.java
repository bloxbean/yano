package com.bloxbean.cardano.yano.wallet.bridge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalCip30BridgeServiceTest {
    @Test
    void readMethodsReturnBackendValuesForReadSession() {
        FakeBackend backend = new FakeBackend();
        LocalCip30BridgeService service = service(backend, request -> true);
        BridgeSession session = service.enable("http://localhost:3000", Set.of(BridgePermission.READ_WALLET));

        assertThat(service.getNetworkId(session.token())).isZero();
        assertThat(service.getBalance(session.token())).isEqualTo("1a001e8480");
        assertThat(service.getUtxos(session.token())).containsExactly("82825820aa");
        assertThat(service.getChangeAddress(session.token())).isEqualTo("change");
        assertThat(service.getRewardAddresses(session.token())).containsExactly("stake");
    }

    @Test
    void readMethodsRequireReadPermission() {
        LocalCip30BridgeService service = service(new FakeBackend(), request -> true);
        BridgeSession session = service.enable("http://localhost:3000", Set.of(BridgePermission.SIGN_TX));

        assertBridgeError(
                () -> service.getBalance(session.token()),
                BridgeError.UNAUTHORIZED,
                BridgeMethod.GET_BALANCE);
    }

    @Test
    void sessionOriginMustMatchBrowserOrigin() {
        LocalCip30BridgeService service = service(new FakeBackend(), request -> true);
        BridgeSession session = service.enable("http://localhost:3000", Set.of(BridgePermission.READ_WALLET));

        service.verifySessionOrigin(session.token(), BridgeMethod.GET_BALANCE, "http://localhost:3000");

        assertBridgeError(
                () -> service.verifySessionOrigin(session.token(), BridgeMethod.GET_BALANCE, "http://evil.example"),
                BridgeError.REFUSED,
                BridgeMethod.GET_BALANCE);
    }

    @Test
    void signTxRequiresPermissionAndApprovalBeforeBackendSigning() {
        FakeBackend backend = new FakeBackend();
        LocalCip30BridgeService noApproval = service(backend, request -> false);
        BridgeSession signSession = noApproval.enable("http://localhost:3000", Set.of(BridgePermission.SIGN_TX));

        assertBridgeError(
                () -> noApproval.signTx(signSession.token(), "84a400", true),
                BridgeError.REFUSED,
                BridgeMethod.SIGN_TX);
        assertThat(backend.signCalls).isZero();

        LocalCip30BridgeService missingPermission = service(backend, request -> true);
        BridgeSession readSession = missingPermission.enable("http://localhost:3000", Set.of(BridgePermission.READ_WALLET));

        assertBridgeError(
                () -> missingPermission.signTx(readSession.token(), "84a400", false),
                BridgeError.UNAUTHORIZED,
                BridgeMethod.SIGN_TX);
        assertThat(backend.signCalls).isZero();

        AtomicReference<BridgeApprovalRequest> approval = new AtomicReference<>();
        LocalCip30BridgeService approved = service(backend, request -> {
            approval.set(request);
            return true;
        });
        BridgeSession approvedSession = approved.enable("http://localhost:3000", Set.of(BridgePermission.SIGN_TX));

        BridgeSignTxResult result = approved.signTx(approvedSession.token(), "84a400", true);

        assertThat(result.witnessSetCborHex()).isEqualTo("a100");
        assertThat(backend.signCalls).isOne();
        assertThat(backend.lastPartialSign).isTrue();
        assertThat(approval.get().session()).isEqualTo(approvedSession);
        assertThat(approval.get().method()).isEqualTo(BridgeMethod.SIGN_TX);
        assertThat(approval.get().txCborHex()).isEqualTo("84a400");
    }

    @Test
    void submitTxRequiresPermissionAndApproval() {
        FakeBackend backend = new FakeBackend();
        LocalCip30BridgeService service = service(backend, request -> true);
        BridgeSession session = service.enable("http://localhost:3000", Set.of(BridgePermission.SUBMIT_TX));

        String txHash = service.submitTx(session.token(), "84a400");

        assertThat(txHash).isEqualTo("c".repeat(64));
        assertThat(backend.submitCalls).isOne();
        assertThat(backend.lastSubmittedCbor).isEqualTo("84a400");
    }

    @Test
    void rejectsInvalidTransactionCborHexBeforeApproval() {
        FakeBackend backend = new FakeBackend();
        LocalCip30BridgeService service = service(backend, request -> {
            throw new AssertionError("approval should not run for invalid cbor hex");
        });
        BridgeSession session = service.enable("http://localhost:3000", Set.of(BridgePermission.SIGN_TX));

        assertBridgeError(
                () -> service.signTx(session.token(), "not-hex", false),
                BridgeError.INVALID_REQUEST,
                BridgeMethod.SIGN_TX);
    }

    private LocalCip30BridgeService service(FakeBackend backend, BridgeApprovalHandler approvalHandler) {
        return new LocalCip30BridgeService(new InMemoryBridgeSessionRegistry(), backend, approvalHandler);
    }

    private void assertBridgeError(Runnable action, BridgeError error, BridgeMethod method) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BridgeException.class)
                .satisfies(throwable -> {
                    BridgeException bridgeException = (BridgeException) throwable;
                    assertThat(bridgeException.error()).isEqualTo(error);
                    assertThat(bridgeException.method()).isEqualTo(method);
                });
    }

    private static class FakeBackend implements BridgeWalletBackend {
        private int signCalls;
        private int submitCalls;
        private boolean lastPartialSign;
        private String lastSubmittedCbor;

        @Override
        public int networkId() {
            return 0;
        }

        @Override
        public String balanceCborHex() {
            return "1a001e8480";
        }

        @Override
        public List<String> utxosCborHex() {
            return List.of("82825820aa");
        }

        @Override
        public String changeAddressHex() {
            return "change";
        }

        @Override
        public List<String> rewardAddressHexes() {
            return List.of("stake");
        }

        @Override
        public BridgeSignTxResult signTx(String txCborHex, boolean partialSign) {
            signCalls++;
            lastPartialSign = partialSign;
            return new BridgeSignTxResult("a100");
        }

        @Override
        public String submitTx(String txCborHex) {
            submitCalls++;
            lastSubmittedCbor = txCborHex;
            return "c".repeat(64);
        }
    }
}
