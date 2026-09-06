package com.bloxbean.cardano.yano.runtime.wallet;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.address.util.AddressUtil;

import java.math.BigInteger;
import java.util.List;
import java.util.zip.CRC32;

/** Full decoded identity, with structural validation beyond the display-address decoder. */
final class WalletAddresses {
    private WalletAddresses() { }

    static byte[] decode(String address) {
        try {
            byte[] bytes = AddressUtil.addressToBytes(address);
            if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Empty address");
            int type = (bytes[0] & 255) >>> 4;
            switch (type) {
                case 0, 1, 2, 3 -> require(bytes.length == 57);
                case 4, 5 -> validatePointer(bytes);
                case 6, 7, 14, 15 -> require(bytes.length == 29);
                case 8 -> validateByron(bytes);
                default -> throw new IllegalArgumentException("Reserved address type");
            }
            return bytes;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid address", failure);
        }
    }

    private static void validatePointer(byte[] bytes) {
        require(bytes.length >= 32);
        int offset = 29;
        for (int component = 0; component < 3; component++) {
            int start = offset;
            int first = bytes[offset] & 255;
            // Each pointer component is a minimally encoded unsigned 64-bit integer.
            require(first != 0x80);
            while (true) {
                require(offset < bytes.length);
                int current = bytes[offset++] & 255;
                int length = offset - start;
                require(length <= 10 && (length < 10 || (first & 127) <= 1));
                if ((current & 128) == 0) break;
            }
            if (component < 2) require(offset < bytes.length);
        }
        require(offset == bytes.length);
    }

    private static void validateByron(byte[] bytes) throws CborException {
        List<DataItem> decoded = CborDecoder.decode(bytes);
        require(decoded.size() == 1 && decoded.getFirst() instanceof Array);
        List<DataItem> envelope = ((Array) decoded.getFirst()).getDataItems();
        require(envelope.size() == 2 && envelope.get(0) instanceof ByteString
                && envelope.get(1) instanceof UnsignedInteger);
        ByteString payload = (ByteString) envelope.get(0);
        require(payload.hasTag() && payload.getTag().getValue() == 24);
        CRC32 crc = new CRC32();
        crc.update(payload.getBytes());
        require(((UnsignedInteger) envelope.get(1)).getValue().equals(BigInteger.valueOf(crc.getValue())));
        List<DataItem> contents = CborDecoder.decode(payload.getBytes());
        require(contents.size() == 1 && contents.getFirst() instanceof Array);
        List<DataItem> fields = ((Array) contents.getFirst()).getDataItems();
        require(fields.size() == 3 && fields.get(0) instanceof ByteString root && root.getBytes().length == 28
                && fields.get(1) instanceof Map && fields.get(2) instanceof UnsignedInteger kind
                && kind.getValue().compareTo(BigInteger.TWO) <= 0);
        Map attributes = (Map) fields.get(1);
        for (DataItem key : attributes.getKeys()) {
            require(key instanceof UnsignedInteger number && number.getValue().bitLength() <= 8
                    && attributes.get(key) instanceof ByteString);
        }
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Malformed address structure");
    }
}
