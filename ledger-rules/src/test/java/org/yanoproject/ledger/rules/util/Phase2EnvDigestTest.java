package org.yanoproject.ledger.rules.util;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Phase2EnvDigestTest {

    @Test
    void changesWithCostModelsPricesLimitsAndProtocolVersion() {
        byte[] base = Phase2EnvDigest.of(params(10, 1L, "0.0577", "10000000"));

        assertThat(Phase2EnvDigest.of(params(10, 1L, "0.0577", "10000000"))).isEqualTo(base).hasSize(32);
        assertThat(Phase2EnvDigest.of(params(10, 2L, "0.0577", "10000000"))).isNotEqualTo(base);
        assertThat(Phase2EnvDigest.of(params(10, 1L, "0.0578", "10000000"))).isNotEqualTo(base);
        assertThat(Phase2EnvDigest.of(params(10, 1L, "0.0577", "10000001"))).isNotEqualTo(base);
        assertThat(Phase2EnvDigest.of(params(11, 1L, "0.0577", "10000000"))).isNotEqualTo(base);
        // Trailing zeros of a price do not matter.
        assertThat(Phase2EnvDigest.of(params(10, 1L, "0.05770", "10000000"))).isEqualTo(base);
    }

    private static ProtocolParams params(int major, long cost, String priceMem, String maxTxMem) {
        ProtocolParams pp = new ProtocolParams();
        pp.setProtocolMajorVer(major);
        pp.setProtocolMinorVer(0);
        pp.setPriceMem(new BigDecimal(priceMem));
        pp.setPriceStep(new BigDecimal("0.0000721"));
        pp.setMaxTxExMem(maxTxMem);
        pp.setMaxTxExSteps("10000000000");
        LinkedHashMap<String, List<Long>> raw = new LinkedHashMap<>();
        raw.put("PlutusV3", List.of(cost, 2L));
        pp.setCostModelsRaw(raw);
        return pp;
    }
}
