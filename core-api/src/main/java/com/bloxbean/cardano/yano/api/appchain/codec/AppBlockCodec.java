package com.bloxbean.cardano.yano.api.appchain.codec;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.serializers.AppMsgSubmissionSerializers;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical CBOR codec for app blocks — one format for wire, storage and
 * hashing ("store what you gossip, hash what you store", ADR app-layer/005 D9).
 * See cddl/appchain/app-block.cddl.
 */
public final class AppBlockCodec {

    private AppBlockCodec() {
    }

    /**
     * blake2b-256 over the CBOR header
     * {@code [version, chain-id, height, prev-hash, l1-slot, l1-block-hash,
     * timestamp, messages-root, state-root]}. The header binds the full
     * message list via messages-root and the whole history via prev-hash.
     */
    public static byte[] blockHash(AppBlock block) {
        return Blake2bUtil.blake2bHash256(CborSerializationUtil.serialize(headerArray(block)));
    }

    /**
     * Binary merkle root (blake2b-256) over the ordered message ids.
     * Empty list → 32 zero bytes; odd level width → last node duplicated.
     */
    public static byte[] messagesRoot(List<AppMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new byte[32];
        }
        List<byte[]> level = new ArrayList<>(messages.size());
        for (AppMessage message : messages) {
            level.add(message.getMessageId());
        }
        while (level.size() > 1) {
            List<byte[]> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i < level.size(); i += 2) {
                byte[] left = level.get(i);
                byte[] right = i + 1 < level.size() ? level.get(i + 1) : left;
                byte[] combined = new byte[left.length + right.length];
                System.arraycopy(left, 0, combined, 0, left.length);
                System.arraycopy(right, 0, combined, left.length, right.length);
                next.add(Blake2bUtil.blake2bHash256(combined));
            }
            level = next;
        }
        return level.get(0);
    }

    public static byte[] serialize(AppBlock block) {
        Array arr = headerArray(block);

        Array messagesArr = new Array();
        for (AppMessage message : block.messages()) {
            messagesArr.add(AppMsgSubmissionSerializers.serializeAppMessage(message));
        }
        arr.add(messagesArr);
        arr.add(new ByteString(block.proposer()));
        arr.add(certArray(block.cert()));

        return CborSerializationUtil.serialize(arr);
    }

    public static AppBlock deserialize(byte[] bytes) {
        Array arr = (Array) CborSerializationUtil.deserializeOne(bytes);
        List<DataItem> items = arr.getDataItems();

        int version = ((UnsignedInteger) items.get(0)).getValue().intValue();
        String chainId = ((UnicodeString) items.get(1)).getString();
        long height = ((UnsignedInteger) items.get(2)).getValue().longValue();
        byte[] prevHash = ((ByteString) items.get(3)).getBytes();
        long l1Slot = ((UnsignedInteger) items.get(4)).getValue().longValue();
        byte[] l1BlockHash = ((ByteString) items.get(5)).getBytes();
        long timestamp = ((UnsignedInteger) items.get(6)).getValue().longValue();
        byte[] messagesRoot = ((ByteString) items.get(7)).getBytes();
        byte[] stateRoot = ((ByteString) items.get(8)).getBytes();

        List<AppMessage> messages = new ArrayList<>();
        for (DataItem messageDI : ((Array) items.get(9)).getDataItems()) {
            messages.add(AppMsgSubmissionSerializers.deserializeAppMessage((Array) messageDI));
        }
        byte[] proposer = ((ByteString) items.get(10)).getBytes();
        FinalityCert cert = parseCert((Array) items.get(11));

        return new AppBlock(version, chainId, height, prevHash, l1Slot, l1BlockHash,
                timestamp, messagesRoot, stateRoot, messages, proposer, cert);
    }

    public static byte[] serializeCert(FinalityCert cert) {
        return CborSerializationUtil.serialize(certArray(cert));
    }

    public static FinalityCert deserializeCert(byte[] bytes) {
        return parseCert((Array) CborSerializationUtil.deserializeOne(bytes));
    }

    private static Array headerArray(AppBlock block) {
        Array arr = new Array();
        arr.add(new UnsignedInteger(block.version()));
        arr.add(new UnicodeString(block.chainId()));
        arr.add(new UnsignedInteger(block.height()));
        arr.add(new ByteString(block.prevHash()));
        arr.add(new UnsignedInteger(block.l1Slot()));
        arr.add(new ByteString(block.l1BlockHash() != null ? block.l1BlockHash() : new byte[0]));
        arr.add(new UnsignedInteger(block.timestamp()));
        arr.add(new ByteString(block.messagesRoot()));
        arr.add(new ByteString(block.stateRoot()));
        return arr;
    }

    private static Array certArray(FinalityCert cert) {
        Array certArr = new Array();
        certArr.add(new UnsignedInteger(cert.scheme()));
        Array sigsArr = new Array();
        for (FinalityCert.Signature signature : cert.signatures()) {
            Array sigArr = new Array();
            sigArr.add(new ByteString(signature.signer()));
            sigArr.add(new ByteString(signature.signature()));
            sigsArr.add(sigArr);
        }
        certArr.add(sigsArr);
        return certArr;
    }

    private static FinalityCert parseCert(Array certArr) {
        List<DataItem> certItems = certArr.getDataItems();
        int scheme = ((UnsignedInteger) certItems.get(0)).getValue().intValue();
        List<FinalityCert.Signature> signatures = new ArrayList<>();
        for (DataItem sigDI : ((Array) certItems.get(1)).getDataItems()) {
            List<DataItem> sigItems = ((Array) sigDI).getDataItems();
            signatures.add(new FinalityCert.Signature(
                    ((ByteString) sigItems.get(0)).getBytes(),
                    ((ByteString) sigItems.get(1)).getBytes()));
        }
        return new FinalityCert(scheme, signatures);
    }
}
