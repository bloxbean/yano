package com.bloxbean.cardano.yano.api.appchain.l1view;

import com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolParamsCanonicalCodecTest {
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    @Test
    void encodesCostModelsOnceInCompactLedgerOrderWithinProofEnvelope() throws Exception {
        Map<String, LinkedHashMap<String, Long>> named = new LinkedHashMap<>();
        Map<String, List<Long>> raw = new LinkedHashMap<>();
        for (int language = 1; language <= 3; language++) {
            LinkedHashMap<String, Long> operations = new LinkedHashMap<>();
            List<Long> values = new ArrayList<>();
            for (int index = 0; index < 300; index++) {
                long value = index * 17L + language;
                operations.put("operation-with-a-deliberately-long-name-" + index, value);
                values.add(value);
            }
            named.put("PlutusV" + language, operations);
            raw.put("PlutusV" + language, values);
        }

        byte[] encoded = ProtocolParamsCanonicalCodec.encode(snapshot(42, named, raw));
        List<?> fields = CBOR.readValue(encoded, List.class);

        assertThat(encoded.length).isLessThanOrEqualTo(8 * 1024);
        assertThat(fields).hasSize(56);
        assertThat(fields.get(21)).isNull();
        assertThat(fields.get(22)).isInstanceOf(Map.class);
        assertThat(ProtocolParamsCanonicalCodec.validate(42, encoded)).isEqualTo(encoded);
    }

    @Test
    void derivesCompactLedgerOrderWhenOnlyNamedProjectionIsAvailable() throws Exception {
        LinkedHashMap<String, Long> model = new LinkedHashMap<>();
        model.put("000", 11L);
        model.put("001", 22L);

        byte[] encoded = ProtocolParamsCanonicalCodec.encode(
                snapshot(7, Map.of("UnknownPlutus", model), null));
        List<?> fields = CBOR.readValue(encoded, List.class);

        assertThat(fields.get(21)).isNull();
        assertThat(((Map<?, ?>) fields.get(22)).get("UnknownPlutus")).isEqualTo(List.of(11, 22));
        assertThat(ProtocolParamsCanonicalCodec.validate(7, encoded)).isEqualTo(encoded);
    }

    private static ProtocolParamsSnapshot snapshot(int epoch,
                                                   Map<String, LinkedHashMap<String, Long>> named,
                                                   Map<String, List<Long>> raw) {
        return new ProtocolParamsSnapshot(
                epoch,
                44,
                155381,
                90112,
                16384,
                1100,
                null,
                null,
                18,
                500,
                null,
                null,
                null,
                null,
                null,
                10,
                2,
                null,
                null,
                null,
                named,
                raw,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                150,
                3,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                3,
                146,
                6,
                null,
                null,
                20,
                null
        );
    }
}
