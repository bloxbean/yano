package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.core.dataset.AccountEventFact;
import com.bloxbean.cardano.yano.archive.core.dataset.AddressParticipationFact;
import com.bloxbean.cardano.yano.archive.core.dataset.AddressSubjectRows;
import com.bloxbean.cardano.yano.archive.core.dataset.TransactionFact;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryFact;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic encoding of the projection facts carried by a section payload.
 *
 * <p>The facts encoded here are the same {@code TransactionFact} and
 * {@code UtxoHistoryFact} types the existing archive datasets already consume. That is
 * the point: the sink rebuilds rows with the very same
 * {@code BlockArchiveDataset.derive} code the replay workers use, so ADR-039 changes
 * <em>when and how often</em> facts are produced without changing <em>what</em> they
 * are. Differential parity is then a property of the transport, not of a second
 * hand-written row builder that would have to be kept in step.
 */
public final class ProjectionFactCodec {
    static final int TRANSACTION_FORMAT = 1;
    static final int UTXO_HISTORY_FORMAT = 1;
    static final int ACCOUNT_EVENT_FORMAT = 1;
    static final int ADDRESS_TRANSACTION_FORMAT = 1;

    private ProjectionFactCodec() {}

    // ------------------------------------------------------------- transaction

    public static byte[] encodeTransactions(List<TransactionFact> facts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(TRANSACTION_FORMAT);
            out.writeInt(facts.size());
            for (TransactionFact fact : facts) {
                writeBytes(out, fact.txHash());
                out.writeInt(fact.txIndex());
                out.writeBoolean(fact.valid());
                writeNullableLong(out, fact.fee());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to encode transaction facts", e);
        }
        return bytes.toByteArray();
    }

    public static List<TransactionFact> decodeTransactions(byte[] payload) {
        if (payload.length == 0) return List.of();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            expectFormat(in.readUnsignedByte(), TRANSACTION_FORMAT, "transaction");
            int count = in.readInt();
            List<TransactionFact> facts = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                facts.add(new TransactionFact(readBytes(in), in.readInt(), in.readBoolean(), readNullableLong(in)));
            }
            requireExhausted(in, "transaction");
            return List.copyOf(facts);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to decode transaction facts", e);
        }
    }

    /**
     * Decode transaction facts one at a time from a chunk-backed stream.
     *
     * <p>Neither the encoded section nor the decoded list is ever held whole: the reader stays
     * positioned in one chunk, and each fact is handed to {@code consumer} and released before
     * the next is read. Facts that straddle a chunk boundary are handled by the stream, since
     * chunk boundaries carry no semantic meaning.
     *
     * @return the number of facts decoded, for receipt accounting without a second pass
     */
    public static long streamTransactions(java.util.List<byte[]> chunks,
                                          java.util.function.Consumer<TransactionFact> consumer) {
        long decoded = 0;
        try (DataInputStream in = new DataInputStream(new ProjectionChunkedInput(chunks))) {
            if (in.available() == 0) return 0;
            expectFormat(in.readUnsignedByte(), TRANSACTION_FORMAT, "transaction");
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                consumer.accept(new TransactionFact(readBytes(in), in.readInt(), in.readBoolean(),
                        readNullableLong(in)));
                decoded++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to stream transaction facts", e);
        }
        return decoded;
    }

    // -------------------------------------------------------- address transactions

    public static byte[] encodeAddressParticipations(AddressParticipationFact fact) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(ADDRESS_TRANSACTION_FORMAT);
            out.writeInt(fact.transactions().size());
            for (var tx : fact.transactions()) {
                writeBytes(out, tx.txHash());
                out.writeInt(tx.txIndex());
                out.writeInt(tx.participations().size());
                for (var participation : tx.participations()) {
                    writeNullableString(out, participation.role());
                    var p = participation.participant();
                    writeNullableBytes(out, p.addressKey());
                    writeNullableString(out, p.address());
                    writeNullableBytes(out, p.paymentCredential());
                    writeNullableString(out, p.stakeCredentialType());
                    writeNullableBytes(out, p.stakeCredential());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to encode address participation facts", e);
        }
        return bytes.toByteArray();
    }

    public static AddressParticipationFact decodeAddressParticipations(byte[] payload) {
        if (payload.length == 0) return new AddressParticipationFact(List.of());
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            var fact = readAddressParticipations(in);
            requireExhausted(in, "address-transaction");
            return fact;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to decode address participation facts", e);
        }
    }

    /**
     * Decode address participations one transaction at a time from a chunk-backed stream.
     *
     * <p>Streamed at transaction granularity rather than fact granularity: a transaction's
     * participations must be seen together, because row emission groups by subject across the
     * whole transaction. One transaction is the natural unit and is protocol-bounded.
     *
     * @return the number of rows-worth of participations decoded, for receipt accounting
     */
    public static long streamAddressParticipations(java.util.List<byte[]> chunks,
                                                   java.util.function.Consumer<
                                                           AddressParticipationFact.Transaction> consumer) {
        long decoded = 0;
        try (DataInputStream in = new DataInputStream(new ProjectionChunkedInput(chunks))) {
            if (in.available() == 0) return 0;
            expectFormat(in.readUnsignedByte(), ADDRESS_TRANSACTION_FORMAT, "address-transaction");
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                var tx = readAddressTransaction(in);
                consumer.accept(tx);
                decoded += tx.participations().size();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to stream address participation facts", e);
        }
        return decoded;
    }

    private static AddressParticipationFact readAddressParticipations(DataInputStream in) throws IOException {
        expectFormat(in.readUnsignedByte(), ADDRESS_TRANSACTION_FORMAT, "address-transaction");
        int count = in.readInt();
        List<AddressParticipationFact.Transaction> transactions = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) transactions.add(readAddressTransaction(in));
        return new AddressParticipationFact(transactions);
    }

    private static AddressParticipationFact.Transaction readAddressTransaction(DataInputStream in)
            throws IOException {
        byte[] txHash = readBytes(in);
        int txIndex = in.readInt();
        int participationCount = in.readInt();
        List<AddressParticipationFact.Participation> participations =
                new ArrayList<>(Math.max(0, participationCount));
        for (int i = 0; i < participationCount; i++) {
            String role = readNullableString(in);
            participations.add(new AddressParticipationFact.Participation(role,
                    new AddressSubjectRows.Participant(readNullableBytes(in), readNullableString(in),
                            readNullableBytes(in), readNullableString(in), readNullableBytes(in))));
        }
        return new AddressParticipationFact.Transaction(txHash, txIndex, participations);
    }

    // ------------------------------------------------------------- account events

    public static byte[] encodeAccountEvents(List<AccountEventFact> facts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(ACCOUNT_EVENT_FORMAT);
            out.writeInt(facts.size());
            for (AccountEventFact fact : facts) writeAccountEvent(out, fact);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to encode account event facts", e);
        }
        return bytes.toByteArray();
    }

    public static List<AccountEventFact> decodeAccountEvents(byte[] payload) {
        if (payload.length == 0) return List.of();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            expectFormat(in.readUnsignedByte(), ACCOUNT_EVENT_FORMAT, "account-event");
            int count = in.readInt();
            List<AccountEventFact> facts = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) facts.add(readAccountEvent(in));
            requireExhausted(in, "account-event");
            return List.copyOf(facts);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to decode account event facts", e);
        }
    }

    /**
     * Decode account events one at a time from a chunk-backed stream.
     *
     * <p>Streamed for the same reason transactions are: certificates are independent of one
     * another, so no fact needs any other fact to be materialised. Nothing is held whole.
     *
     * @return the number of facts decoded, for receipt accounting without a second pass
     */
    public static long streamAccountEvents(java.util.List<byte[]> chunks,
                                           java.util.function.Consumer<AccountEventFact> consumer) {
        long decoded = 0;
        try (DataInputStream in = new DataInputStream(new ProjectionChunkedInput(chunks))) {
            if (in.available() == 0) return 0;
            expectFormat(in.readUnsignedByte(), ACCOUNT_EVENT_FORMAT, "account-event");
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                consumer.accept(readAccountEvent(in));
                decoded++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to stream account event facts", e);
        }
        return decoded;
    }

    private static void writeAccountEvent(DataOutputStream out, AccountEventFact fact) throws IOException {
        writeBytes(out, fact.stakeCredential());
        writeNullableString(out, fact.credentialType());
        writeNullableString(out, fact.eventType());
        writeBytes(out, fact.txHash());
        out.writeInt(fact.txIndex());
        out.writeLong(fact.eventIndex());
        writeNullableBytes(out, fact.poolHash());
        writeNullableString(out, fact.drepType());
        writeNullableBytes(out, fact.drepCredential());
        writeNullableLong(out, fact.amount());
    }

    private static AccountEventFact readAccountEvent(DataInputStream in) throws IOException {
        return new AccountEventFact(readBytes(in), readNullableString(in), readNullableString(in),
                readBytes(in), in.readInt(), in.readLong(), readNullableBytes(in),
                readNullableString(in), readNullableBytes(in), readNullableLong(in));
    }

    /**
     * Decode a utxo-history fact from a chunk-backed stream, without concatenating the chunks.
     *
     * <p>This one is decoded whole rather than streamed, and the reason is a real constraint
     * rather than convenience: row derivation resolves each output's address through a map
     * built from the section's address list, so outputs cannot be emitted before the addresses
     * are known. The retained size is nonetheless bounded by the protocol — a section derives
     * from a single block, and Cardano caps block body size — so peak heap is one block's
     * decoded facts, not one batch's.
     */
    public static UtxoHistoryFact decodeUtxoHistory(java.util.List<byte[]> chunks) {
        try (DataInputStream in = new DataInputStream(new ProjectionChunkedInput(chunks))) {
            if (in.available() == 0) {
                return new UtxoHistoryFact(0, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                        java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                        java.util.List.of());
            }
            return readUtxoHistory(in);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to decode utxo-history facts", e);
        }
    }

    // ------------------------------------------------------------ utxo history

    public static byte[] encodeUtxoHistory(UtxoHistoryFact fact) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(UTXO_HISTORY_FORMAT);
            out.writeInt(fact.era());

            out.writeInt(fact.pointerRegistrations().size());
            for (var r : fact.pointerRegistrations()) {
                out.writeLong(r.slot());
                out.writeInt(r.txIndex());
                out.writeInt(r.certIndex());
                writeNullableString(out, r.credentialType());
                writeNullableBytes(out, r.credential());
            }

            out.writeInt(fact.pointerDeregistrations().size());
            for (var d : fact.pointerDeregistrations()) {
                out.writeInt(d.txIndex());
                out.writeInt(d.certIndex());
                writeNullableString(out, d.credentialType());
                writeNullableBytes(out, d.credential());
            }

            out.writeInt(fact.newAddresses().size());
            for (var a : fact.newAddresses()) {
                writeNullableBytes(out, a.addressKey());
                writeNullableBytes(out, a.rawAddress());
                writeNullableString(out, a.displayAddress());
                writeNullableInt(out, a.networkId());
                writeNullableString(out, a.addressType());
                writeNullableString(out, a.paymentCredentialType());
                writeNullableBytes(out, a.paymentCredential());
                writeNullableString(out, a.stakeReferenceType());
                writeNullableString(out, a.stakeCredentialType());
                writeNullableBytes(out, a.stakeCredential());
                writeNullableLong(out, a.pointerSlot());
                writeNullableInt(out, a.pointerTxIndex());
                writeNullableInt(out, a.pointerCertIndex());
            }

            out.writeInt(fact.outputs().size());
            for (var o : fact.outputs()) {
                writeBytes(out, o.txHash());
                out.writeInt(o.outputIndex());
                out.writeInt(o.txIndex());
                writeNullableString(out, o.originType());
                writeNullableBytes(out, o.addressKey());
                writeNullableBytes(out, o.paymentCredential());
                writeNullableBytes(out, o.stakeCredential());
                out.writeLong(o.lovelace());
                writeNullableString(out, o.datumKind());
                writeNullableBytes(out, o.datumHash());
                writeNullableBytes(out, o.inlineDatumCbor());
                writeNullableBytes(out, o.referenceScriptHash());
                writeNullableString(out, o.referenceScriptType());
                writeNullableBytes(out, o.referenceScriptCbor());
                out.writeBoolean(o.collateralReturn());
            }

            out.writeInt(fact.assets().size());
            for (var a : fact.assets()) {
                writeBytes(out, a.txHash());
                out.writeInt(a.outputIndex());
                writeNullableBytes(out, a.policyId());
                writeNullableBytes(out, a.assetName());
                writeBigInteger(out, a.quantity());
            }

            out.writeInt(fact.inputs().size());
            for (var i : fact.inputs()) {
                writeBytes(out, i.spendingTxHash());
                out.writeInt(i.spendingTxIndex());
                out.writeInt(i.inputIndex());
                writeNullableString(out, i.inputRole());
                writeNullableBytes(out, i.referencedTxHash());
                out.writeInt(i.referencedOutputIndex());
                out.writeBoolean(i.consumesOutput());
            }

            out.writeInt(fact.transactionDatums().size());
            for (var d : fact.transactionDatums()) {
                writeBytes(out, d.txHash());
                out.writeInt(d.txIndex());
                writeNullableBytes(out, d.datumHash());
                writeNullableBytes(out, d.datumCbor());
            }

            out.writeInt(fact.transactionRedeemers().size());
            for (var r : fact.transactionRedeemers()) {
                writeBytes(out, r.txHash());
                out.writeInt(r.txIndex());
                writeNullableString(out, r.purpose());
                out.writeInt(r.redeemerIndex());
                writeNullableBytes(out, r.redeemerCbor());
                writeNullableBytes(out, r.redeemerDataHash());
                writeBigInteger(out, r.executionMem());
                writeBigInteger(out, r.executionSteps());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to encode utxo-history facts", e);
        }
        return bytes.toByteArray();
    }

    /** Legacy byte[] entry point, retained for tests and golden fixtures. */
    public static UtxoHistoryFact decodeUtxoHistory(byte[] payload) {
        if (payload.length == 0) {
            return new UtxoHistoryFact(0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of());
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            UtxoHistoryFact fact = readUtxoHistory(in);
            requireExhausted(in, "utxo-history");
            return fact;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to decode utxo-history facts", e);
        }
    }

    /** Shared reader, so the streaming and byte[] paths cannot diverge. */
    private static UtxoHistoryFact readUtxoHistory(DataInputStream in) throws IOException {
        expectFormat(in.readUnsignedByte(), UTXO_HISTORY_FORMAT, "utxo-history");
        int era = in.readInt();

        int count = in.readInt();
        List<UtxoHistoryFact.PointerRegistration> registrations = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            registrations.add(new UtxoHistoryFact.PointerRegistration(in.readLong(), in.readInt(), in.readInt(),
                    readNullableString(in), readNullableBytes(in)));
        }

        count = in.readInt();
        List<UtxoHistoryFact.PointerDeregistration> deregistrations = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            deregistrations.add(new UtxoHistoryFact.PointerDeregistration(in.readInt(), in.readInt(),
                    readNullableString(in), readNullableBytes(in)));
        }

        count = in.readInt();
        List<UtxoHistoryFact.Address> addresses = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            addresses.add(new UtxoHistoryFact.Address(readNullableBytes(in), readNullableBytes(in),
                    readNullableString(in), readNullableInt(in), readNullableString(in), readNullableString(in),
                    readNullableBytes(in), readNullableString(in), readNullableString(in), readNullableBytes(in),
                    readNullableLong(in), readNullableInt(in), readNullableInt(in)));
        }

        count = in.readInt();
        List<UtxoHistoryFact.Output> outputs = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            outputs.add(new UtxoHistoryFact.Output(readBytes(in), in.readInt(), in.readInt(),
                    readNullableString(in), readNullableBytes(in), readNullableBytes(in), readNullableBytes(in),
                    in.readLong(), readNullableString(in), readNullableBytes(in), readNullableBytes(in),
                    readNullableBytes(in), readNullableString(in), readNullableBytes(in), in.readBoolean()));
        }

        count = in.readInt();
        List<UtxoHistoryFact.Asset> assets = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            assets.add(new UtxoHistoryFact.Asset(readBytes(in), in.readInt(), readNullableBytes(in),
                    readNullableBytes(in), readBigInteger(in)));
        }

        count = in.readInt();
        List<UtxoHistoryFact.Input> inputs = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            inputs.add(new UtxoHistoryFact.Input(readBytes(in), in.readInt(), in.readInt(),
                    readNullableString(in), readNullableBytes(in), in.readInt(), in.readBoolean()));
        }

        count = in.readInt();
        List<UtxoHistoryFact.TransactionDatum> datums = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            datums.add(new UtxoHistoryFact.TransactionDatum(readBytes(in), in.readInt(),
                    readNullableBytes(in), readNullableBytes(in)));
        }

        count = in.readInt();
        List<UtxoHistoryFact.TransactionRedeemer> redeemers = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            redeemers.add(new UtxoHistoryFact.TransactionRedeemer(readBytes(in), in.readInt(),
                    readNullableString(in), in.readInt(), readNullableBytes(in), readNullableBytes(in),
                    readBigInteger(in), readBigInteger(in)));
        }

        return new UtxoHistoryFact(era, registrations, deregistrations, addresses, outputs, assets, inputs,
                datums, redeemers);
    }

    // ------------------------------------------------------------------ helpers

    private static void expectFormat(int actual, int expected, String what) {
        if (actual != expected) {
            throw new IllegalArgumentException("unsupported " + what + " fact format " + actual);
        }
    }

    private static void requireExhausted(DataInputStream in, String what) throws IOException {
        if (in.available() != 0) {
            throw new IllegalArgumentException("trailing bytes after " + what + " facts");
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        if (value == null) throw new IllegalArgumentException("required byte field was null");
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) throw new IllegalArgumentException("negative byte length");
        return in.readNBytes(length);
    }

    private static void writeNullableBytes(DataOutputStream out, byte[] value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
        } else {
            out.writeInt(value.length);
            out.write(value);
        }
    }

    private static byte[] readNullableBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) return null;
        return in.readNBytes(length);
    }

    private static void writeNullableString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeUTF(value);
    }

    private static String readNullableString(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    private static void writeNullableLong(DataOutputStream out, Long value) throws IOException {
        out.writeBoolean(value != null);
        out.writeLong(value == null ? 0L : value);
    }

    private static Long readNullableLong(DataInputStream in) throws IOException {
        boolean present = in.readBoolean();
        long value = in.readLong();
        return present ? value : null;
    }

    private static void writeNullableInt(DataOutputStream out, Integer value) throws IOException {
        out.writeBoolean(value != null);
        out.writeInt(value == null ? 0 : value);
    }

    private static Integer readNullableInt(DataInputStream in) throws IOException {
        boolean present = in.readBoolean();
        int value = in.readInt();
        return present ? value : null;
    }

    private static void writeBigInteger(DataOutputStream out, BigInteger value) throws IOException {
        writeNullableBytes(out, value == null ? null : value.toByteArray());
    }

    private static BigInteger readBigInteger(DataInputStream in) throws IOException {
        byte[] bytes = readNullableBytes(in);
        return bytes == null ? null : new BigInteger(bytes);
    }
}
