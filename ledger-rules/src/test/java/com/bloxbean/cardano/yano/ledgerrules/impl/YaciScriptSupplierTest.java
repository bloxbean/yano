package com.bloxbean.cardano.yano.ledgerrules.impl;

import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.ledgerrules.ScriptReferenceResolverScope;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class YaciScriptSupplierTest {
    @Test
    void resolvesAdmissionScopedReferenceScriptBeforeCanonicalState() throws Exception {
        PlutusV2Script script = PlutusV2Script.builder()
                .cborHex("49480100002221200101")
                .build();
        String hash = java.util.HexFormat.of().formatHex(script.getScriptHash());
        TransactionOutput output = TransactionOutput.builder()
                .address("00").value(new Value(BigInteger.ONE, null)).scriptRef(script).build();
        String scriptRef = java.util.HexFormat.of().formatHex(output.getScriptRef());
        Utxo overlayUtxo = new Utxo(new Outpoint("ab".repeat(32), 0), "00",
                BigInteger.ONE, List.of(), null, null, scriptRef, hash,
                false, 0, 0, null);
        UtxoState canonical = new EmptyUtxoState();
        YaciScriptSupplier supplier = new YaciScriptSupplier(canonical);

        assertThat(supplier.getScript(hash)).isEmpty();
        try (var ignored = ScriptReferenceResolverScope.open(List.of(overlayUtxo))) {
            assertThat(supplier.getScript(hash)).containsInstanceOf(PlutusV2Script.class);
        }
        assertThat(supplier.getScript(hash)).isEmpty();
    }

    private static final class EmptyUtxoState implements UtxoState {
        @Override public List<Utxo> getUtxosByAddress(String address, int page, int pageSize) {
            return List.of();
        }
        @Override public List<Utxo> getUtxosByPaymentCredential(
                String credential, int page, int pageSize) {
            return List.of();
        }
        @Override public Optional<Utxo> getUtxo(Outpoint outpoint) {
            return Optional.empty();
        }
        @Override public boolean isEnabled() { return true; }
    }
}
