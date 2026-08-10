package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.ProtocolParamsCanonicalCodec;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.util.Arrays;

/** Replay-only consumer of verified epoch-parameter observations. */
public final class EpochParamsStateMachine implements AppStateMachine {
    private final String observerId;

    public EpochParamsStateMachine() {
        this(EpochParamsContract.DEFAULT_OBSERVER_ID);
    }

    public EpochParamsStateMachine(String observerId) {
        if (observerId == null || observerId.isBlank()) {
            throw new IllegalArgumentException("epoch-params observer id is required");
        }
        this.observerId = observerId;
    }

    @Override public String id() { return EpochParamsContract.STATE_MACHINE_ID; }

    @Override
    public AppCapabilityManifest capabilityManifest() {
        return StdlibCapabilityManifests.component(id(), "~l1/" + observerId,
                        java.util.List.of(EpochParamsContract.QUERY_PATH,
                                EpochParamsContract.FIELD_QUERY_PATH,
                                EpochParamsContract.META_QUERY_PATH,
                                EpochParamsContract.LATEST_QUERY_PATH))
                .proofSubject(new AppCapabilityManifest.ProofSubject(
                        EpochParamsContract.PROOF_SUBJECT, "", "params/", "state-proof"))
                .build();
    }

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                      AppEffectEmitter effects) {
        for (var sequenced : context.l1Observations()) {
            L1Observation observation = sequenced.observation();
            if (!observerId.equals(observation.observerId())
                    || !(observation.anchor() instanceof L1Observation.EpochAnchor epochAnchor)) {
                continue;
            }
            EpochParamsContract.Claim claim = EpochParamsContract.decodeClaim(observation.claim());
            if (claim.effectiveEpoch() != epochAnchor.newEpoch()) {
                throw new IllegalArgumentException("epoch-params claim does not match its anchor");
            }
            var document = ProtocolParamsCanonicalCodec.decode(
                    claim.effectiveEpoch(), claim.canonicalParamsCbor());
            byte[] key = EpochParamsContract.documentKey(claim.effectiveEpoch());
            byte[] current = writer.get(key).orElse(null);
            if (current != null && !Arrays.equals(current, claim.canonicalParamsCbor())) {
                throw new IllegalStateException("Historical protocol parameters are write-once");
            }
            if (current == null) {
                writer.put(key, claim.canonicalParamsCbor());
                writer.put(EpochParamsContract.metaKey(claim.effectiveEpoch()),
                        EpochParamsContract.encodeMeta(new EpochParamsContract.Meta(
                                claim.effectiveEpoch(), document.fields().size(),
                                Blake2bUtil.blake2bHash256(claim.canonicalParamsCbor()))));
                for (var field : document.fields()) {
                    writer.put(EpochParamsContract.fieldKey(claim.effectiveEpoch(), field.id()),
                            field.canonicalCbor());
                }
            }
            long latest = writer.get(EpochParamsContract.latestKey())
                    .map(EpochParamsContract::decodeEpoch).orElse(-1L);
            if (claim.effectiveEpoch() > latest) {
                writer.put(EpochParamsContract.latestKey(),
                        EpochParamsContract.encodeEpoch(claim.effectiveEpoch()));
            }
        }
    }

    @Override
    public byte[] query(String path, byte[] params, AppQueryContext state) {
        if (EpochParamsContract.LATEST_QUERY_PATH.equals(path)) {
            if (params == null || params.length != 0) throw invalidQuery();
            return state.get(EpochParamsContract.latestKey()).orElse(new byte[0]);
        }
        if (EpochParamsContract.QUERY_PATH.equals(path)) {
            long epoch;
            try { epoch = EpochParamsContract.decodeEpoch(params); }
            catch (RuntimeException malformed) { throw invalidQuery(); }
            return state.get(EpochParamsContract.documentKey(epoch)).orElse(new byte[0]);
        }
        if (EpochParamsContract.META_QUERY_PATH.equals(path)) {
            long epoch;
            try { epoch = EpochParamsContract.decodeEpoch(params); }
            catch (RuntimeException malformed) { throw invalidQuery(); }
            return state.get(EpochParamsContract.metaKey(epoch)).orElse(new byte[0]);
        }
        if (EpochParamsContract.FIELD_QUERY_PATH.equals(path)) {
            EpochParamsContract.FieldQuery query;
            try { query = EpochParamsContract.decodeFieldQuery(params); }
            catch (RuntimeException malformed) { throw invalidQuery(); }
            return state.get(EpochParamsContract.fieldKey(query.epoch(), query.fieldId()))
                    .orElse(new byte[0]);
        }
        throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                "unknown epoch-params query path");
    }

    private static AppQueryException invalidQuery() {
        return new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                "epoch-params query requires a canonical epoch or epoch/field id");
    }
}
