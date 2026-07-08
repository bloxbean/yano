package com.bloxbean.cardano.yano.runtime.appchain;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.util.List;

/**
 * CBOR codecs for the sequencer's system-topic message bodies
 * (topics {@code ~consensus/propose|vote|cert}). Proposals carry the full
 * block CBOR (AppBlockCodec) directly; votes and cert notices use the small
 * structures below. All ride the ordinary authenticated app-message envelope.
 */
final class ConsensusCodec {

    static final String TOPIC_PROPOSE = "~consensus/propose";
    static final String TOPIC_VOTE = "~consensus/vote";
    static final String TOPIC_CERT = "~consensus/cert";

    private ConsensusCodec() {
    }

    /** vote = [height, block-hash, signature-over-block-hash] */
    static byte[] encodeVote(long height, byte[] blockHash, byte[] signature) {
        Array arr = new Array();
        arr.add(new UnsignedInteger(height));
        arr.add(new ByteString(blockHash));
        arr.add(new ByteString(signature));
        return CborSerializationUtil.serialize(arr);
    }

    static Vote decodeVote(byte[] bytes) {
        List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(bytes)).getDataItems();
        return new Vote(
                ((UnsignedInteger) items.get(0)).getValue().longValue(),
                ((ByteString) items.get(1)).getBytes(),
                ((ByteString) items.get(2)).getBytes());
    }

    /** cert notice = [height, block-hash, cert-cbor] */
    static byte[] encodeCertNotice(long height, byte[] blockHash, byte[] certBytes) {
        Array arr = new Array();
        arr.add(new UnsignedInteger(height));
        arr.add(new ByteString(blockHash));
        arr.add(new ByteString(certBytes));
        return CborSerializationUtil.serialize(arr);
    }

    static CertNotice decodeCertNotice(byte[] bytes) {
        List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(bytes)).getDataItems();
        return new CertNotice(
                ((UnsignedInteger) items.get(0)).getValue().longValue(),
                ((ByteString) items.get(1)).getBytes(),
                ((ByteString) items.get(2)).getBytes());
    }

    record Vote(long height, byte[] blockHash, byte[] signature) {
    }

    record CertNotice(long height, byte[] blockHash, byte[] certBytes) {
    }
}
