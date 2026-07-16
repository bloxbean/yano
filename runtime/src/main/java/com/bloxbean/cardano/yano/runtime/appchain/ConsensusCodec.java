package com.bloxbean.cardano.yano.runtime.appchain;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.codec.internal.CborStructurePreflight;

import java.util.Arrays;
import java.util.List;

/**
 * CBOR codecs for the sequencer's system-topic message bodies
 * (topics {@code ~consensus/propose|vote|cert}). Proposals carry the full
 * block CBOR (AppBlockCodec) directly; votes and cert notices use the small
 * structures below. All ride the ordinary authenticated app-message envelope.
 */
final class ConsensusCodec {
    static final int MAX_VOTE_BYTES = 256;
    static final int MAX_CERT_NOTICE_BYTES =
            AppChainConfig.MAX_FINALITY_CERT_HEADROOM_BYTES + 128;

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
        if (!CborStructurePreflight.accepts(bytes, MAX_VOTE_BYTES, 4, 16)) {
            throw invalid("Invalid bounded vote");
        }
        try {
            List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(bytes))
                    .getDataItems();
            if (items.size() != 3) {
                throw invalid("Invalid vote shape");
            }
            Vote vote = new Vote(
                    ((UnsignedInteger) items.get(0)).getValue().longValueExact(),
                    ((ByteString) items.get(1)).getBytes(),
                    ((ByteString) items.get(2)).getBytes());
            if (vote.blockHash().length != 32
                    || vote.signature().length != AppChainConfig.ED25519_SIGNATURE_BYTES
                    || !Arrays.equals(bytes, encodeVote(
                    vote.height(), vote.blockHash(), vote.signature()))) {
                throw invalid("Invalid canonical vote");
            }
            return vote;
        } catch (RuntimeException malformed) {
            throw invalid("Invalid bounded canonical vote");
        }
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
        if (!CborStructurePreflight.accepts(bytes, MAX_CERT_NOTICE_BYTES, 4, 128)) {
            throw invalid("Invalid bounded certificate notice");
        }
        try {
            List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(bytes))
                    .getDataItems();
            if (items.size() != 3) {
                throw invalid("Invalid certificate notice shape");
            }
            CertNotice notice = new CertNotice(
                    ((UnsignedInteger) items.get(0)).getValue().longValueExact(),
                    ((ByteString) items.get(1)).getBytes(),
                    ((ByteString) items.get(2)).getBytes());
            if (notice.blockHash().length != 32 || notice.certBytes().length == 0
                    || notice.certBytes().length
                    > AppChainConfig.MAX_FINALITY_CERT_HEADROOM_BYTES
                    || !Arrays.equals(bytes, encodeCertNotice(
                    notice.height(), notice.blockHash(), notice.certBytes()))) {
                throw invalid("Invalid canonical certificate notice");
            }
            return notice;
        } catch (RuntimeException malformed) {
            throw invalid("Invalid bounded canonical certificate notice");
        }
    }

    record Vote(long height, byte[] blockHash, byte[] signature) {
    }

    record CertNotice(long height, byte[] blockHash, byte[] certBytes) {
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
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
