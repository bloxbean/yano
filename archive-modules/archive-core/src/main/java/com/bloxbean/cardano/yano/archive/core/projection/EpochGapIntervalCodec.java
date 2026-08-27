package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGapInterval;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

final class EpochGapIntervalCodec {
    record Checkpoint(int epoch, long slot, byte[] hash) {
        Checkpoint { hash = hash.clone(); }
        @Override public byte[] hash() { return hash.clone(); }
    }
    record State(ArchiveDatasetId dataset, int causedByEpoch, String failureClass,
                 boolean open, List<Checkpoint> checkpoints) {
        State {
            if (dataset == null || causedByEpoch < 0 || failureClass == null || failureClass.isBlank()) {
                throw new IllegalArgumentException("invalid gap interval state");
            }
            checkpoints = List.copyOf(checkpoints);
            int previousEpoch = -1;
            long previousSlot = -1;
            for (var point : checkpoints) {
                if (point.epoch() <= previousEpoch || point.slot() < previousSlot
                        || point.hash().length == 0) {
                    throw new IllegalArgumentException("gap interval checkpoints are not canonical order");
                }
                previousEpoch = point.epoch();
                previousSlot = point.slot();
            }
        }

        /** Split around repaired epochs while keeping one compact RocksDB journal per cause. */
        List<EpochArtifactGapInterval> intervals() {
            List<EpochArtifactGapInterval> result = new ArrayList<>();
            int start = 0;
            for (int index = 1; index <= checkpoints.size(); index++) {
                boolean end = index == checkpoints.size()
                        || checkpoints.get(index).epoch() != checkpoints.get(index - 1).epoch() + 1;
                if (!end) continue;
                var first = checkpoints.get(start);
                var last = checkpoints.get(index - 1);
                result.add(new EpochArtifactGapInterval(dataset, first.epoch(), last.epoch(),
                        first.slot(), first.hash(), last.slot(), last.hash(),
                        open && index == checkpoints.size(), causedByEpoch, failureClass));
                start = index;
            }
            return List.copyOf(result);
        }
    }
    private EpochGapIntervalCodec() { }

    static byte[] encode(State state) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(1); out.writeUTF(state.dataset().name());
                out.writeInt(state.causedByEpoch()); out.writeUTF(state.failureClass());
                out.writeBoolean(state.open()); out.writeInt(state.checkpoints().size());
                for (var point : state.checkpoints()) {
                    out.writeInt(point.epoch()); out.writeLong(point.slot());
                    out.writeInt(point.hash().length); out.write(point.hash());
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) { throw new ProjectionOutboxException("failed to encode gap interval", e); }
    }

    static State decode(byte[] encoded) {
        try (var in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readInt() != 1) throw new IllegalArgumentException("unsupported gap interval version");
            var dataset = ArchiveDatasetId.valueOf(in.readUTF());
            int causedBy = in.readInt(); String failure = in.readUTF(); boolean open = in.readBoolean();
            int count = in.readInt();
            if (count <= 0 || count > 1_000_000) throw new IllegalArgumentException("invalid checkpoint count");
            List<Checkpoint> checkpoints = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int epoch = in.readInt(); long slot = in.readLong(); int length = in.readInt();
                if (length <= 0 || length > 128) throw new IllegalArgumentException("invalid checkpoint hash");
                byte[] hash = in.readNBytes(length);
                if (hash.length != length) throw new IllegalArgumentException("truncated checkpoint hash");
                checkpoints.add(new Checkpoint(epoch, slot, hash));
            }
            if (in.available() != 0) throw new IllegalArgumentException("trailing gap interval bytes");
            return new State(dataset, causedBy, failure, open, checkpoints);
        } catch (Exception e) { throw new ProjectionOutboxException("failed to decode gap interval", e); }
    }
}
