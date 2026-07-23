package com.bloxbean.cardano.yano.runtime.appchain;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.codec.internal.CborStructurePreflight;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Built-in {@code metadata-label} observer (ADR 008.4 §3.1, ADR-005 D5
 * registry use case): watches transaction metadata for a configured label
 * and claims the raw CBOR bytes of that label's value.
 *
 * <p>Config: {@code observers.<id>.type: metadata-label},
 * {@code observers.<id>.label: <uint>}.
 */
final class MetadataLabelObserver implements L1Observer {

    static final String TYPE = "metadata-label";
    private static final CborStructurePreflight.Limits METADATA_CBOR_LIMITS =
            new CborStructurePreflight.Limits(
                    Math.toIntExact(AppChainConfig.MAX_BLOCK_BYTES),
                    64, 500_000, 250_000, AppChainConfig.MAX_MESSAGE_BYTES);

    private final String observerId;
    private final long label;

    MetadataLabelObserver(String observerId, Map<String, String> settings) {
        this.observerId = observerId;
        String labelValue = settings.get("label");
        if (labelValue == null || labelValue.isBlank())
            throw new IllegalArgumentException("observers." + observerId
                    + ".label is required for the metadata-label observer");
        this.label = Long.parseLong(labelValue.trim());
    }

    @Override
    public String observerId() {
        return observerId;
    }

    @Override
    public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
        if (block == null || block.getTransactionBodies() == null
                || block.getAuxiliaryDataMap() == null || block.getAuxiliaryDataMap().isEmpty())
            return List.of();
        List<L1Observation> observations = new ArrayList<>();
        List<TransactionBody> txs = block.getTransactionBodies();
        for (Map.Entry<Integer, AuxData> entry : block.getAuxiliaryDataMap().entrySet()) {
            int txIndex = entry.getKey();
            AuxData auxData = entry.getValue();
            if (txIndex < 0 || txIndex >= txs.size() || auxData == null
                    || auxData.getMetadataCbor() == null)
                continue;
            byte[] labelValue = extractLabel(auxData.getMetadataCbor());
            if (labelValue == null)
                continue;
            observations.add(new L1Observation(observerId,
                    HexUtil.decodeHexString(txs.get(txIndex).getTxHash()),
                    slot, blockHash, labelValue));
        }
        return observations;
    }

    /** The re-encoded CBOR of the watched label's value, or null if absent. */
    private byte[] extractLabel(String metadataCborHex) {
        try {
            byte[] metadataCbor = HexUtil.decodeHexString(metadataCborHex);
            if (!CborStructurePreflight.accepts(metadataCbor, METADATA_CBOR_LIMITS)) {
                return null;
            }
            List<DataItem> items = CborDecoder.decode(metadataCbor);
            if (items.isEmpty() || !(items.get(0) instanceof co.nstant.in.cbor.model.Map metadataMap))
                return null;
            DataItem value = metadataMap.get(new UnsignedInteger(BigInteger.valueOf(label)));
            if (value == null)
                return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new CborEncoder(out).encode(value);
            return out.toByteArray();
        } catch (Exception e) {
            return null; // undecodable metadata → no observation (deterministic)
        }
    }

    @Override
    public Map<String, Object> status() {
        return Map.of("type", TYPE, "label", label);
    }
}
