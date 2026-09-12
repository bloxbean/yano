package org.yanoproject.ledgerrules.impl;

import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.util.ReferenceScriptUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.ledgerrules.ScriptReferenceResolverScope;

import java.util.Optional;

public class YaciScriptSupplier implements ScriptSupplier {
    private UtxoState utxoState;
    public YaciScriptSupplier(UtxoState utxoState) {
        this.utxoState = utxoState;
    }

    @Override
    public Optional<PlutusScript> getScript(String scriptHash) {
        return ScriptReferenceResolverScope.resolve(scriptHash)
                .or(() -> utxoState.getScriptRefBytesByHash(scriptHash))
                .map(bytes -> ReferenceScriptUtil.deserializeScriptRef(bytes))
                .filter(script -> script instanceof PlutusScript)
                .map(PlutusScript.class::cast);
    }
}
