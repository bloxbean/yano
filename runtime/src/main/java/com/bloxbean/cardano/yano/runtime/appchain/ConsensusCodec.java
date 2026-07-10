package com.bloxbean.cardano.yano.runtime.appchain;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
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

    // ------------------------------------------------------------------
    // Locked-proposal envelope persistence (ADR 008.2 §2.3): a member that
    // voted stores the ORIGINAL proposer-signed envelope so it can re-gossip
    // the partial round after timeouts/restarts. Full envelope v2 fields —
    // authenticity survives (receivers re-verify the proposer's signature).
    // ------------------------------------------------------------------

    static byte[] encodeEnvelope(AppMessage m) {
        Array arr = new Array();
        arr.add(new UnsignedInteger(m.getVersion()));
        arr.add(new ByteString(m.getMessageId()));
        arr.add(new UnicodeString(m.getChainId()));
        arr.add(new UnicodeString(m.getTopic() != null ? m.getTopic() : ""));
        arr.add(new ByteString(m.getSender()));
        arr.add(new UnsignedInteger(m.getSenderSeq()));
        arr.add(new UnsignedInteger(m.getExpiresAt()));
        arr.add(new ByteString(m.getBody()));
        arr.add(new UnsignedInteger(m.getAuthScheme()));
        arr.add(new ByteString(m.getAuthProof()));
        return CborSerializationUtil.serialize(arr);
    }

    static AppMessage decodeEnvelope(byte[] bytes) {
        List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(bytes)).getDataItems();
        return new AppMessage(
                ((UnsignedInteger) items.get(0)).getValue().intValue(),
                ((ByteString) items.get(1)).getBytes(),
                ((UnicodeString) items.get(2)).getString(),
                ((UnicodeString) items.get(3)).getString(),
                ((ByteString) items.get(4)).getBytes(),
                ((UnsignedInteger) items.get(5)).getValue().longValue(),
                ((UnsignedInteger) items.get(6)).getValue().longValue(),
                ((ByteString) items.get(7)).getBytes(),
                ((UnsignedInteger) items.get(8)).getValue().intValue(),
                ((ByteString) items.get(9)).getBytes());
    }
}
