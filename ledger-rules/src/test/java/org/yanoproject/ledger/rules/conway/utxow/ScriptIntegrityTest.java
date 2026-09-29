package org.yanoproject.ledger.rules.conway.utxow;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.plutus.spec.CostMdls;
import com.bloxbean.cardano.client.plutus.spec.CostModel;
import com.bloxbean.cardano.client.plutus.spec.Language;
import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Language views and the script integrity preimage (Alonzo/PParams.hs:545-600, Alonzo/Tx.hs:377-423). */
class ScriptIntegrityTest {

    private static ProtocolParams params(LinkedHashMap<String, List<Long>> costModels) {
        ProtocolParams params = MutationWorld.protocolParams();
        params.setCostModelsRaw(costModels);
        params.setCostModels(null);
        return params;
    }

    /**
     * PlutusV1 keeps Alonzo's quirk (tag serialised twice, cost model as a byte string holding an indefinite list),
     * PlutusV2/V3 use the definite list, a missing cost model is {@code null}, and the views are sorted shortlex by
     * tag: {@code 01} (V2), {@code 02} (V3), {@code 41 00} (V1).
     */
    @Test
    void languageViewsHaveHaskellsExactBytes() {
        LinkedHashMap<String, List<Long>> models = new LinkedHashMap<>();
        models.put("PlutusV1", List.of(1L, -2L));
        models.put("PlutusV2", List.of(3L, 1000L));
        byte[] views = ScriptIntegrity.languageViews(new TreeSet<>(Set.of(1, 2, 3)), params(models));
        assertThat(HexUtil.encodeHexString(views)).isEqualTo("a3" + "01" + "820319" + "03e8" + "02" + "f6"
                + "4100" + "44" + "9f0121ff");

        LinkedHashMap<String, List<Long>> noV1 = new LinkedHashMap<>();
        noV1.put("PlutusV2", List.of(3L));
        assertThat(HexUtil.encodeHexString(ScriptIntegrity.languageViews(Set.of(1), params(noV1))))
                .as("a missing PlutusV1 cost model is null inside the byte string").isEqualTo("a1" + "4100" + "41f6");
        assertThat(HexUtil.encodeHexString(ScriptIntegrity.languageViews(Set.of(), params(noV1)))).isEqualTo("a0");
    }

    /**
     * Only the raw lists are the ledger's order: a view that has cost models only in the named form cannot be hashed
     * and fails closed; a language absent from a raw map has no cost model ({@code null}).
     */
    @Test
    void onlyRawCostModelsAreUsed() {
        ProtocolParams namedOnly = MutationWorld.protocolParams();
        LinkedHashMap<String, LinkedHashMap<String, Long>> named = new LinkedHashMap<>();
        LinkedHashMap<String, Long> v3 = new LinkedHashMap<>();
        v3.put("b", 2L);
        v3.put("a", 1L);
        named.put("PlutusV3", v3);
        namedOnly.setCostModels(named);
        namedOnly.setCostModelsRaw(null);
        assertThatThrownBy(() -> ScriptIntegrity.languageViews(Set.of(3), namedOnly))
                .isInstanceOf(LedgerStateUnavailableException.class);

        ProtocolParams rawWithoutV3 = MutationWorld.protocolParams();
        rawWithoutV3.setCostModels(named);
        LinkedHashMap<String, List<Long>> v2Only = new LinkedHashMap<>();
        v2Only.put("PlutusV2", List.of(1L));
        rawWithoutV3.setCostModelsRaw(v2Only);
        assertThatThrownBy(() -> ScriptIntegrity.languageViews(Set.of(3), rawWithoutV3))
                .as("the named map has PlutusV3 but the raw one lost it").isInstanceOf(
                        LedgerStateUnavailableException.class);
        rawWithoutV3.setCostModels(null);
        assertThat(HexUtil.encodeHexString(ScriptIntegrity.languageViews(Set.of(3), rawWithoutV3)))
                .isEqualTo("a102f6");
    }

    /**
     * The raw map's three states ({@code LedgerView#protocolParams}): {@code null} is not supplied (fail closed); empty
     * with an empty or absent named map is "no cost model for any language", so every language view is {@code null}
     * and a script is {@code NoCostModel}, as in Haskell (the cardano-blueprint {@code no-cost-model} vectors); empty
     * with a non-empty named map means the raw form was dropped (fail closed).
     */
    @Test
    void anEmptyRawMapIsNoCostModelsAndANullOneIsUnavailable() {
        ProtocolParams notSupplied = params(null);
        assertThatThrownBy(() -> ScriptIntegrity.costModel(1, notSupplied))
                .isInstanceOf(LedgerStateUnavailableException.class);

        ProtocolParams none = params(new LinkedHashMap<>());
        assertThat(ScriptIntegrity.costModel(1, none)).isEmpty();
        assertThat(HexUtil.encodeHexString(ScriptIntegrity.languageViews(Set.of(1, 3), none)))
                .isEqualTo("a2" + "02" + "f6" + "4100" + "41f6");
        none.setCostModels(new LinkedHashMap<>());
        assertThat(ScriptIntegrity.costModel(3, none)).isEmpty();

        ProtocolParams dropped = params(new LinkedHashMap<>());
        LinkedHashMap<String, LinkedHashMap<String, Long>> named = new LinkedHashMap<>();
        LinkedHashMap<String, Long> v1 = new LinkedHashMap<>();
        v1.put("a", 1L);
        named.put("PlutusV1", v1);
        dropped.setCostModels(named);
        assertThatThrownBy(() -> ScriptIntegrity.costModel(3, dropped))
                .as("the named map has cost models but the raw one is empty")
                .isInstanceOf(LedgerStateUnavailableException.class);
    }

    /** Cross-checked with CCL's independent language-view encoder on the full preprod-like cost models. */
    @Test
    void languageViewsAgreeWithCardanoClientLib() {
        ProtocolParams params = MutationWorld.protocolParams();
        long[] v1 = CostModelUtil.PlutusV1CostModel.getCosts();
        LinkedHashMap<String, List<Long>> models = new LinkedHashMap<>(params.getCostModelsRaw());
        models.put("PlutusV1", Arrays.stream(v1).boxed().toList());
        params.setCostModelsRaw(models);

        CostMdls ccl = new CostMdls();
        ccl.add(new CostModel(Language.PLUTUS_V1, v1));
        ccl.add(new CostModel(Language.PLUTUS_V2, longs(models.get("PlutusV2"))));
        ccl.add(new CostModel(Language.PLUTUS_V3, longs(models.get("PlutusV3"))));
        assertThat(ScriptIntegrity.languageViews(Set.of(1, 2, 3), params)).isEqualTo(ccl.getLanguageViewEncoding());
    }

    private static long[] longs(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).toArray();
    }
}
