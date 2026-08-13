package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.archive.core.dataset.*;

import java.io.*;
import java.math.BigInteger;

/** Stable bounded binary codecs for durable epoch staging. */
public final class StandardEpochFactCodecs {
    private StandardEpochFactCodecs() { }

    public static final EpochFactCodec<EpochStakeFact> EPOCH_STAKE = codec(
            (out, v) -> { text(out, v.credentialType()); bytes(out, v.stakeCredential()); bytes(out, v.poolHash()); out.writeLong(v.amount()); },
            in -> new EpochStakeFact(text(in), bytes(in), bytes(in), in.readLong()));
    public static final EpochFactCodec<DrepDistributionFact> DREP = codec(
            (out, v) -> { text(out, v.drepType()); bytes(out, v.credential()); out.writeLong(v.amount()); nullableLong(out, v.storedExpiry()); out.writeLong(v.dormantEpochs()); nullableLong(out, v.effectiveExpiry()); out.writeBoolean(v.active()); },
            in -> new DrepDistributionFact(text(in), bytes(in), in.readLong(), nullableLong(in), in.readLong(), nullableLong(in), in.readBoolean()));
    public static final EpochFactCodec<AdaPotFact> ADA_POT = codec(
            (out, v) -> { out.writeLong(v.treasury()); out.writeLong(v.reserves()); out.writeLong(v.deposits()); out.writeLong(v.fees()); out.writeLong(v.distributed()); out.writeLong(v.undistributed()); out.writeLong(v.rewardsPot()); out.writeLong(v.poolRewardsPot()); },
            in -> new AdaPotFact(in.readLong(), in.readLong(), in.readLong(), in.readLong(), in.readLong(), in.readLong(), in.readLong(), in.readLong()));
    public static final EpochFactCodec<GovernanceProposalStatusFact> GOVERNANCE = codec(
            (out, v) -> { bytes(out, v.txHash()); out.writeInt(v.governanceActionIndex()); text(out, v.actionType()); text(out, v.observationPhase()); text(out, v.statusCode()); nullableText(out, v.decisionReason()); out.writeLong(v.deposit()); bytes(out, v.returnAddress()); out.writeLong(v.submittedEpoch()); out.writeLong(v.expiresAfterEpoch()); },
            in -> new GovernanceProposalStatusFact(bytes(in), in.readInt(), text(in), text(in), text(in), nullableText(in), in.readLong(), bytes(in), in.readLong(), in.readLong()));
    public static final EpochFactCodec<RewardFact> REWARD = codec(
            (out, v) -> { bytes(out, v.stakeCredential()); text(out, v.credentialType()); nullableBytes(out, v.poolHash()); text(out, v.rewardType()); out.writeLong(v.earnedEpoch()); out.writeLong(v.spendableEpoch()); out.writeLong(v.amount()); text(out, v.sourceId()); },
            in -> new RewardFact(bytes(in), text(in), nullableBytes(in), text(in), in.readLong(), in.readLong(), in.readLong(), text(in)));

    private static <T> EpochFactCodec<T> codec(Encoder<T> encoder, Decoder<T> decoder) {
        return new EpochFactCodec<>() {
            public byte[] encode(T value) {
                try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
                    encoder.write(out, value); return bytes.toByteArray();
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }
            public T decode(byte[] value) {
                try (var in = new DataInputStream(new ByteArrayInputStream(value))) {
                    T decoded = decoder.read(in);
                    if (in.available() != 0) throw new IOException("trailing epoch fact bytes");
                    return decoded;
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }
        };
    }
    private static void text(DataOutputStream out, String value) throws IOException { bytes(out, value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String text(DataInputStream in) throws IOException { return new String(bytes(in), java.nio.charset.StandardCharsets.UTF_8); }
    private static void nullableText(DataOutputStream out, String value) throws IOException { nullableBytes(out, value == null ? null : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String nullableText(DataInputStream in) throws IOException { byte[] value = nullableBytes(in); return value == null ? null : new String(value, java.nio.charset.StandardCharsets.UTF_8); }
    private static void bytes(DataOutputStream out, byte[] value) throws IOException { if (value == null || value.length > 64 * 1024 * 1024) throw new IOException("invalid bytes"); out.writeInt(value.length); out.write(value); }
    private static byte[] bytes(DataInputStream in) throws IOException { int size = in.readInt(); if (size < 0 || size > 64 * 1024 * 1024) throw new IOException("invalid bytes"); return in.readNBytes(size); }
    private static void nullableBytes(DataOutputStream out, byte[] value) throws IOException { if (value == null) out.writeInt(-1); else bytes(out, value); }
    private static byte[] nullableBytes(DataInputStream in) throws IOException { int size = in.readInt(); if (size == -1) return null; if (size < 0 || size > 64 * 1024 * 1024) throw new IOException("invalid bytes"); return in.readNBytes(size); }
    private static void nullableInt(DataOutputStream out, Integer value) throws IOException { out.writeBoolean(value != null); if (value != null) out.writeInt(value); }
    private static Integer nullableInt(DataInputStream in) throws IOException { return in.readBoolean() ? in.readInt() : null; }
    private static void nullableLong(DataOutputStream out, Long value) throws IOException { out.writeBoolean(value != null); if (value != null) out.writeLong(value); }
    private static Long nullableLong(DataInputStream in) throws IOException { return in.readBoolean() ? in.readLong() : null; }
    @FunctionalInterface private interface Encoder<T> { void write(DataOutputStream out, T value) throws IOException; }
    @FunctionalInterface private interface Decoder<T> { T read(DataInputStream in) throws IOException; }
}
