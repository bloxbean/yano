package com.bloxbean.cardano.yano.appchain.showcase;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Component-local state for one emitted outbox action and its result. */
public final class ShowcaseReleaseStateMachine implements AppStateMachine {
    public static final String ID = "showcase-release";
    public static final String QUERY_PATH = "get";
    public static final String SCOPE_PREFIX = "showcase/order-release/";
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_CONFIRMED = 1;
    public static final int STATUS_FAILED = 2;
    private static final int VERSION = 1;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                      AppEffectEmitter effects) {
        AppBlock block = context.block();
        // The workflow is the only command writer for this private component.
    }

    @Override
    public void onEffectResult(AppBlockExecutionContext context, EffectResult result,
                               AppStateWriter writer, AppEffectEmitter effects) {
        AppBlock block = context.block();
        if (!ShowcaseOutboxExecutor.TYPE.equals(result.type())
                || !result.scope().startsWith(SCOPE_PREFIX)) {
            return;
        }
        String releaseId = result.scope().substring(SCOPE_PREFIX.length());
        ReleaseRecord existing = writer.get(key(releaseId)).map(ReleaseRecord::decode).orElse(null);
        if (existing == null || existing.status() != STATUS_PENDING
                || existing.effectHeight() != result.effectId().height()
                || existing.effectOrdinal() != result.effectId().ordinal()) {
            return;
        }
        int status = result.outcome() == EffectOutcome.CONFIRMED
                ? STATUS_CONFIRMED : STATUS_FAILED;
        writer.put(key(releaseId), existing.completed(status, result).encode());
    }

    @Override
    public byte[] query(String path, byte[] params, AppQueryContext state) {
        if (!QUERY_PATH.equals(path)) {
            throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                    "unknown showcase release query");
        }
        String releaseId;
        try {
            releaseId = new String(params, StandardCharsets.UTF_8);
            if (!releaseId.equals(releaseId.trim()) || releaseId.isBlank()
                    || releaseId.getBytes(StandardCharsets.UTF_8).length > 96) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException malformed) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "release id must be 1..96 bytes of trimmed UTF-8");
        }
        return state.get(key(releaseId)).orElse(new byte[0]);
    }

    static byte[] key(String releaseId) {
        return ("r/" + releaseId).getBytes(StandardCharsets.UTF_8);
    }

    static ReleaseRecord pending(EffectId effectId, byte[] orderHash, String approvalId) {
        return new ReleaseRecord(STATUS_PENDING, effectId.height(), effectId.ordinal(), 0,
                new byte[0], new byte[0], orderHash, approvalId);
    }

    public record ReleaseRecord(int status, long effectHeight, int effectOrdinal,
                                int outcomeCode, byte[] externalRef, byte[] detailHash,
                                byte[] orderHash, String approvalId) {
        public ReleaseRecord {
            externalRef = externalRef != null ? externalRef.clone() : new byte[0];
            detailHash = detailHash != null ? detailHash.clone() : new byte[0];
            orderHash = orderHash != null ? orderHash.clone() : new byte[0];
            if (status < STATUS_PENDING || status > STATUS_FAILED || effectHeight <= 0
                    || effectOrdinal < 0 || orderHash.length != 32 || approvalId == null) {
                throw new IllegalArgumentException("invalid showcase release state");
            }
        }

        ReleaseRecord completed(int terminalStatus, EffectResult result) {
            return new ReleaseRecord(terminalStatus, effectHeight, effectOrdinal,
                    result.outcome().code(), result.externalRef(),
                    result.detailHash() != null ? result.detailHash() : new byte[0],
                    orderHash, approvalId);
        }

        public byte[] encode() {
            Array value = new Array();
            value.add(new UnsignedInteger(VERSION));
            value.add(new UnsignedInteger(status));
            value.add(new UnsignedInteger(effectHeight));
            value.add(new UnsignedInteger(effectOrdinal));
            value.add(new UnsignedInteger(outcomeCode));
            value.add(new ByteString(externalRef));
            value.add(new ByteString(detailHash));
            value.add(new ByteString(orderHash));
            value.add(new UnicodeString(approvalId));
            return CborSerializationUtil.serialize(value);
        }

        static ReleaseRecord decode(byte[] encoded) {
            try {
                List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(encoded))
                        .getDataItems();
                if (items.size() != 9
                        || ((UnsignedInteger) items.get(0)).getValue().intValueExact() != VERSION) {
                    throw new IllegalArgumentException();
                }
                return new ReleaseRecord(
                        ((UnsignedInteger) items.get(1)).getValue().intValueExact(),
                        ((UnsignedInteger) items.get(2)).getValue().longValueExact(),
                        ((UnsignedInteger) items.get(3)).getValue().intValueExact(),
                        ((UnsignedInteger) items.get(4)).getValue().intValueExact(),
                        ((ByteString) items.get(5)).getBytes(),
                        ((ByteString) items.get(6)).getBytes(),
                        ((ByteString) items.get(7)).getBytes(),
                        ((UnicodeString) items.get(8)).getString());
            } catch (RuntimeException malformed) {
                throw new IllegalArgumentException("invalid showcase release state");
            }
        }
    }
}
