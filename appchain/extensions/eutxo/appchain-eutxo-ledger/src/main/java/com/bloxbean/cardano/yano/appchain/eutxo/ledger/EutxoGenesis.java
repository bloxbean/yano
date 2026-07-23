package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EutxoGenesis {
    private static final Pattern OUTPUT_PROPERTY =
            Pattern.compile("machines\\.eutxo\\.genesis\\.outputs\\[(\\d+)]\\."
                    + "(address|lovelace|inline-datum-hex)");

    private final String transactionId;
    private final List<EutxoRecord> records;

    private EutxoGenesis(String transactionId, List<EutxoRecord> records) {
        this.transactionId = transactionId;
        this.records = List.copyOf(records);
    }

    static EutxoGenesis from(Map<String, String> settings) {
        Map<Integer, MutableOutput> outputs = new TreeMap<>();
        String simpleAddress = settings.get("machines.eutxo.genesis.address");
        String simpleLovelace = settings.get("machines.eutxo.genesis.lovelace");
        String simpleDatum = settings.get("machines.eutxo.genesis.inline-datum-hex");
        if (simpleAddress != null || simpleLovelace != null || simpleDatum != null) {
            MutableOutput output = outputs.computeIfAbsent(0, ignored -> new MutableOutput());
            output.address = simpleAddress;
            output.inlineDatumHex = simpleDatum;
            if (simpleLovelace != null) {
                try {
                    output.lovelace = new BigInteger(simpleLovelace);
                } catch (NumberFormatException failure) {
                    throw new IllegalArgumentException(
                            "EUTxO genesis lovelace must be an integer", failure);
                }
            }
        }
        settings.forEach((key, value) -> {
            Matcher matcher = OUTPUT_PROPERTY.matcher(key);
            if (!matcher.matches()) {
                return;
            }
            int index = Integer.parseInt(matcher.group(1));
            if (index < 0 || index >= 1_024) {
                throw new IllegalArgumentException("EUTxO genesis output index is outside 0-1023");
            }
            MutableOutput output = outputs.computeIfAbsent(index, ignored -> new MutableOutput());
            if (index == 0 && (simpleAddress != null || simpleLovelace != null
                    || simpleDatum != null)) {
                throw new IllegalArgumentException(
                        "use either simple EUTxO genesis keys or indexed outputs, not both");
            }
            if ("address".equals(matcher.group(2))) {
                output.address = value;
            } else if ("lovelace".equals(matcher.group(2))) {
                try {
                    output.lovelace = new BigInteger(value);
                } catch (NumberFormatException failure) {
                    throw new IllegalArgumentException(
                            "EUTxO genesis lovelace must be an integer", failure);
                }
            } else {
                output.inlineDatumHex = value;
            }
        });
        if (outputs.isEmpty()) {
            return new EutxoGenesis(
                    HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(new byte[0])),
                    List.of());
        }
        int expected = 0;
        List<PendingOutput> pending = new ArrayList<>();
        for (Map.Entry<Integer, MutableOutput> entry : outputs.entrySet()) {
            if (entry.getKey() != expected++) {
                throw new IllegalArgumentException(
                        "EUTxO genesis output indexes must be contiguous from zero");
            }
            MutableOutput value = entry.getValue();
            if (value.address == null || value.address.isBlank()
                    || value.lovelace == null || value.lovelace.signum() <= 0) {
                throw new IllegalArgumentException(
                        "every EUTxO genesis output needs an address and positive lovelace");
            }
            try {
                TransactionOutput output = TransactionOutput.builder()
                        .address(value.address.trim())
                        .value(Value.fromCoin(value.lovelace))
                        .inlineDatum(decodeDatum(value.inlineDatumHex))
                        .build();
                pending.add(new PendingOutput(
                        value.address.trim(),
                        CborSerializationUtil.serialize(output.serialize())));
            } catch (Exception failure) {
                throw new IllegalArgumentException("invalid EUTxO genesis output", failure);
            }
        }
        byte[] canonical = canonicalBytes(pending);
        String transactionId = HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(canonical));
        List<EutxoRecord> records = new ArrayList<>(pending.size());
        for (int index = 0; index < pending.size(); index++) {
            PendingOutput output = pending.get(index);
            records.add(new EutxoRecord(
                    new EutxoOutpoint(transactionId, index),
                    output.address,
                    output.outputCbor,
                    EutxoRecord.Origin.GENESIS));
        }
        return new EutxoGenesis(transactionId, records);
    }

    String transactionId() {
        return transactionId;
    }

    List<EutxoRecord> records() {
        return records;
    }

    private static byte[] canonicalBytes(List<PendingOutput> outputs) {
        int size = outputs.stream()
                .mapToInt(output -> output.address.getBytes(StandardCharsets.UTF_8).length
                        + output.outputCbor.length + 8)
                .sum();
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(size);
        outputs.forEach(output -> {
            byte[] address = output.address.getBytes(StandardCharsets.UTF_8);
            buffer.putInt(address.length).put(address);
            buffer.putInt(output.outputCbor.length).put(output.outputCbor);
        });
        return buffer.array();
    }

    private static final class MutableOutput {
        private String address;
        private BigInteger lovelace;
        private String inlineDatumHex;
    }

    private record PendingOutput(String address, byte[] outputCbor) {
    }

    private static PlutusData decodeDatum(String datumHex) {
        if (datumHex == null || datumHex.isBlank()) {
            return null;
        }
        try {
            String canonical = datumHex.trim();
            if ((canonical.length() & 1) != 0
                    || !canonical.equals(canonical.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "EUTxO genesis inline datum must be canonical lowercase hex");
            }
            return PlutusData.deserialize(HexFormat.of().parseHex(canonical));
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "EUTxO genesis inline datum cannot be decoded", failure);
        }
    }
}
