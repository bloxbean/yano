package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.ledgerrules.TransactionValidator;
import com.bloxbean.cardano.yano.ledgerrules.ValidationResult;
import com.bloxbean.cardano.yano.ledgerrules.impl.YaciScriptSupplier;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionValidationServiceTest {

    private SimpleUtxoState utxoState;
    private TransactionValidationService service;

    @BeforeEach
    void setUp() {
        utxoState = new SimpleUtxoState();

        // Use a no-op validator that always succeeds (UTXO resolution is what we test here)
        TransactionValidator alwaysValid = (txCbor, inputUtxos) -> ValidationResult.success();
        service = new TransactionValidationService(alwaysValid, utxoState);
    }

    @Test
    void validate_invalidCbor_returnsError() {
        byte[] badCbor = new byte[]{0x01, 0x02, 0x03};
        ValidationResult result = service.validate(badCbor);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.errors().get(0).rule()).isEqualTo("CborDeserialization");
    }

    @Test
    void validate_missingUtxo_returnsUtxoNotFound() throws Exception {
        // Build a tx that references a UTXO not in the store
        String fakeTxHash = "aa".repeat(32);
        var input = com.bloxbean.cardano.client.transaction.spec.TransactionInput.builder()
                .transactionId(fakeTxHash)
                .index(0)
                .build();
        var output = com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                .address("addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp")
                .value(new com.bloxbean.cardano.client.transaction.spec.Value(BigInteger.valueOf(1_000_000), null))
                .build();
        var body = com.bloxbean.cardano.client.transaction.spec.TransactionBody.builder()
                .inputs(List.of(input))
                .outputs(List.of(output))
                .fee(BigInteger.valueOf(200_000))
                .build();
        var tx = com.bloxbean.cardano.client.transaction.spec.Transaction.builder()
                .body(body)
                .build();
        byte[] txCbor = tx.serialize();

        // utxoState is empty — no UTXOs to find
        ValidationResult result = service.validate(txCbor);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.errors().get(0).rule()).isEqualTo("UtxoNotFound");
        assertThat(result.errors().get(0).message()).contains(fakeTxHash);
    }

    @Test
    void validate_withCustomResolver_usesResolver() throws Exception {
        // Build a tx
        String fakeTxHash = "bb".repeat(32);
        var input = com.bloxbean.cardano.client.transaction.spec.TransactionInput.builder()
                .transactionId(fakeTxHash)
                .index(0)
                .build();
        var output = com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                .address("addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp")
                .value(new com.bloxbean.cardano.client.transaction.spec.Value(BigInteger.valueOf(1_000_000), null))
                .build();
        var body = com.bloxbean.cardano.client.transaction.spec.TransactionBody.builder()
                .inputs(List.of(input))
                .outputs(List.of(output))
                .fee(BigInteger.valueOf(200_000))
                .build();
        var tx = com.bloxbean.cardano.client.transaction.spec.Transaction.builder()
                .body(body)
                .build();
        byte[] txCbor = tx.serialize();

        // Custom resolver that returns null -> UtxoNotFound
        ValidationResult result = service.validate(txCbor, op -> null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors().get(0).rule()).isEqualTo("UtxoNotFound");
    }

    @Test
    void validate_exposesReferenceScriptsFromCustomResolverToScriptSupplier() throws Exception {
        String inputHash = "cc".repeat(32);
        var input = com.bloxbean.cardano.client.transaction.spec.TransactionInput.builder()
                .transactionId(inputHash).index(0).build();
        var output = com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                .address("addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp")
                .value(new com.bloxbean.cardano.client.transaction.spec.Value(
                        BigInteger.valueOf(1_000_000), null)).build();
        byte[] txCbor = com.bloxbean.cardano.client.transaction.spec.Transaction.builder()
                .body(com.bloxbean.cardano.client.transaction.spec.TransactionBody.builder()
                        .inputs(List.of(input)).outputs(List.of(output))
                        .fee(BigInteger.valueOf(200_000)).build())
                .build().serialize();
        PlutusV2Script script = PlutusV2Script.builder()
                .cborHex("49480100002221200101").build();
        String scriptHash = java.util.HexFormat.of().formatHex(script.getScriptHash());
        var scriptOutput = com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                .address("00")
                .value(new com.bloxbean.cardano.client.transaction.spec.Value(BigInteger.ONE, null))
                .scriptRef(script).build();
        var resolved = new com.bloxbean.cardano.yano.api.utxo.model.Utxo(
                new Outpoint(inputHash, 0), "00", BigInteger.ONE, List.of(),
                null, null, java.util.HexFormat.of().formatHex(scriptOutput.getScriptRef()),
                scriptHash, false, 0, 0, null);
        YaciScriptSupplier supplier = new YaciScriptSupplier(utxoState);
        TransactionValidationService overlayService = new TransactionValidationService(
                (bytes, inputs) -> supplier.getScript(scriptHash).isPresent()
                        ? ValidationResult.success()
                        : ValidationResult.failure(new com.bloxbean.cardano.yano.ledgerrules.ValidationError(
                                "ReferenceScript", "not found",
                                com.bloxbean.cardano.yano.ledgerrules.ValidationError.Phase.PHASE_2)),
                utxoState);

        assertThat(overlayService.validate(txCbor, ignored -> resolved).valid()).isTrue();
        assertThat(supplier.getScript(scriptHash)).isEmpty();
    }

    /**
     * Simple in-memory UtxoState for testing.
     */
    private static class SimpleUtxoState implements UtxoState {
        private final Map<Outpoint, com.bloxbean.cardano.yano.api.utxo.model.Utxo> store = new HashMap<>();

        void put(Outpoint op, com.bloxbean.cardano.yano.api.utxo.model.Utxo utxo) {
            store.put(op, utxo);
        }

        @Override
        public List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> getUtxosByAddress(String addr, int page, int pageSize) {
            return List.of();
        }

        @Override
        public List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> getUtxosByPaymentCredential(String cred, int page, int pageSize) {
            return List.of();
        }

        @Override
        public Optional<com.bloxbean.cardano.yano.api.utxo.model.Utxo> getUtxo(Outpoint outpoint) {
            return Optional.ofNullable(store.get(outpoint));
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
