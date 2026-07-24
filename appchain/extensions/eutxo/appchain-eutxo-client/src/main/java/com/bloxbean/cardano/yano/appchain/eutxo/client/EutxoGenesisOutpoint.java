package com.bloxbean.cardano.yano.appchain.eutxo.client;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Derives the deterministic outpoint for a simple single-output virtual genesis. */
public final class EutxoGenesisOutpoint {
    private EutxoGenesisOutpoint() {
    }

    public static EutxoOutpoint singleOutput(String address, BigInteger lovelace) {
        if (address == null || address.isBlank()
                || lovelace == null || lovelace.signum() <= 0) {
            throw new IllegalArgumentException(
                    "genesis address and positive lovelace are required");
        }
        try {
            byte[] addressBytes = address.getBytes(StandardCharsets.UTF_8);
            byte[] output = CborSerializationUtil.serialize(
                    TransactionOutput.builder()
                            .address(address)
                            .value(Value.fromCoin(lovelace))
                            .build()
                            .serialize());
            ByteBuffer canonical = ByteBuffer.allocate(
                    8 + addressBytes.length + output.length);
            canonical.putInt(addressBytes.length).put(addressBytes);
            canonical.putInt(output.length).put(output);
            return new EutxoOutpoint(HexFormat.of().formatHex(
                    Blake2bUtil.blake2bHash256(canonical.array())), 0);
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "virtual genesis output cannot be encoded", failure);
        }
    }
}
