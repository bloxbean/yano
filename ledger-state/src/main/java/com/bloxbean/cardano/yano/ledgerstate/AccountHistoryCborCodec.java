package com.bloxbean.cardano.yano.ledgerstate;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.account.AccountHistoryProvider;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class AccountHistoryCborCodec {
    private AccountHistoryCborCodec() {}

    static byte[] encodeWithdrawal(String txHash, BigInteger amount, long slot, long blockNo, int txIdx) {
        Map map = baseRecord(txHash, slot, blockNo, txIdx);
        map.put(new UnsignedInteger(10), new UnsignedInteger(amount));
        return CborSerializationUtil.serialize(map, true);
    }

    static byte[] encodeDelegation(String txHash, String poolHash, long slot, long blockNo,
                                   int txIdx, int certIdx, int activeEpoch) {
        Map map = baseRecord(txHash, slot, blockNo, txIdx);
        map.put(new UnsignedInteger(11), new ByteString(HexUtil.decodeHexString(poolHash)));
        map.put(new UnsignedInteger(12), new UnsignedInteger(certIdx));
        map.put(new UnsignedInteger(13), new UnsignedInteger(activeEpoch));
        return CborSerializationUtil.serialize(map, true);
    }

    static byte[] encodeRegistration(String txHash, String action, BigInteger deposit,
                                     long slot, long blockNo, int txIdx, int certIdx) {
        Map map = baseRecord(txHash, slot, blockNo, txIdx);
        map.put(new UnsignedInteger(14), new ByteString(action.getBytes(StandardCharsets.UTF_8)));
        map.put(new UnsignedInteger(15), new UnsignedInteger(deposit != null ? deposit : BigInteger.ZERO));
        map.put(new UnsignedInteger(12), new UnsignedInteger(certIdx));
        return CborSerializationUtil.serialize(map, true);
    }

    static byte[] encodeMir(String txHash, String pot, BigInteger amount, int earnedEpoch,
                            long slot, long blockNo, int txIdx, int certIdx) {
        Map map = baseRecord(txHash, slot, blockNo, txIdx);
        map.put(new UnsignedInteger(16), new ByteString(pot.getBytes(StandardCharsets.UTF_8)));
        map.put(new UnsignedInteger(10), new UnsignedInteger(amount));
        map.put(new UnsignedInteger(17), new UnsignedInteger(earnedEpoch));
        map.put(new UnsignedInteger(12), new UnsignedInteger(certIdx));
        return CborSerializationUtil.serialize(map, true);
    }

    static byte[] encodeDelta(long slot, List<byte[]> keys) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(slot));
        Array arr = new Array();
        if (keys != null) {
            for (byte[] key : keys) {
                arr.add(new ByteString(key));
            }
        }
        map.put(new UnsignedInteger(1), arr);
        return CborSerializationUtil.serialize(map, true);
    }

    static Delta decodeDelta(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        long slot = CborSerializationUtil.toLong(map.get(new UnsignedInteger(0)));
        List<byte[]> keys = new ArrayList<>();
        var arr = (Array) map.get(new UnsignedInteger(1));
        for (var item : arr.getDataItems()) {
            keys.add(((ByteString) item).getBytes());
        }
        return new Delta(slot, keys);
    }

    static AccountHistoryProvider.WithdrawalRecord decodeWithdrawal(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        CommonRecord common = commonRecord(map);
        BigInteger amount = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(10)));
        return new AccountHistoryProvider.WithdrawalRecord(
                common.txHash(), amount, common.slot(), common.blockNo(), common.txIdx());
    }

    static AccountHistoryProvider.DelegationRecord decodeDelegation(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        CommonRecord common = commonRecord(map);
        String poolHash = HexUtil.encodeHexString(((ByteString) map.get(new UnsignedInteger(11))).getBytes());
        int certIdx = toInt(map, 12);
        int activeEpoch = toInt(map, 13);
        return new AccountHistoryProvider.DelegationRecord(
                common.txHash(), poolHash, common.slot(), common.blockNo(), common.txIdx(), certIdx, activeEpoch);
    }

    static AccountHistoryProvider.RegistrationRecord decodeRegistration(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        CommonRecord common = commonRecord(map);
        String action = new String(((ByteString) map.get(new UnsignedInteger(14))).getBytes(), StandardCharsets.UTF_8);
        BigInteger deposit = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(15)));
        int certIdx = toInt(map, 12);
        return new AccountHistoryProvider.RegistrationRecord(
                common.txHash(), action, deposit, common.slot(), common.blockNo(), common.txIdx(), certIdx);
    }

    static AccountHistoryProvider.MirRecord decodeMir(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        CommonRecord common = commonRecord(map);
        String pot = new String(((ByteString) map.get(new UnsignedInteger(16))).getBytes(), StandardCharsets.UTF_8);
        BigInteger amount = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(10)));
        int earnedEpoch = toInt(map, 17);
        int certIdx = toInt(map, 12);
        return new AccountHistoryProvider.MirRecord(
                common.txHash(), pot, amount, earnedEpoch, common.slot(), common.blockNo(), common.txIdx(), certIdx);
    }

    private static Map baseRecord(String txHash, long slot, long blockNo, int txIdx) {
        Map map = new Map();
        if (txHash != null) {
            map.put(new UnsignedInteger(1), new ByteString(HexUtil.decodeHexString(txHash)));
        }
        map.put(new UnsignedInteger(2), new UnsignedInteger(slot));
        map.put(new UnsignedInteger(3), new UnsignedInteger(blockNo));
        map.put(new UnsignedInteger(4), new UnsignedInteger(txIdx));
        return map;
    }

    private static CommonRecord commonRecord(Map map) {
        String txHash = null;
        var txHashItem = map.get(new UnsignedInteger(1));
        if (txHashItem instanceof ByteString bs) {
            txHash = HexUtil.encodeHexString(bs.getBytes());
        }
        long slot = CborSerializationUtil.toLong(map.get(new UnsignedInteger(2)));
        long blockNo = CborSerializationUtil.toLong(map.get(new UnsignedInteger(3)));
        int txIdx = toInt(map, 4);
        return new CommonRecord(txHash, slot, blockNo, txIdx);
    }

    private static int toInt(Map map, int key) {
        return (int) CborSerializationUtil.toLong(map.get(new UnsignedInteger(key)));
    }

    record Delta(long slot, List<byte[]> keys) {}
    private record CommonRecord(String txHash, long slot, long blockNo, int txIdx) {}
}
