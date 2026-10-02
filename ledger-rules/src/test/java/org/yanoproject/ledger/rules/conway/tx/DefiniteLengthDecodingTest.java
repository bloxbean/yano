package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.crypto.Base58;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadata;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRetirement;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDelegation;
import com.bloxbean.cardano.client.transaction.spec.cert.StakePoolId;
import com.bloxbean.cardano.client.transaction.spec.cert.VoteDelegCert;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.Voter;
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedures;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.fixtures.tx.BuiltTx;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Below decoder version 12 Haskell reads every ledger byte string (hashes, keys, signatures, addresses, asset names,
 * Plutus binaries, the tag-24 wrappers of inline datums, reference scripts and Byron address payloads) with
 * {@code decodeBytesDefinite} / {@code decodeByteArrayDefinite} (cardano-ledger-binary Decoding/Decoder.hs:350-376,
 * 1426-1447; {@code decodeNestedCborBytes}, Decoding.hs:238-239) and text with cborg's definite-only
 * {@code decodeString}: an indefinite (chunked) string there is a decoding failure. Only metadata
 * ({@code decodeMetadatum}, Metadata.hs:151-185, the 64-byte bound on the concatenation) and Plutus {@code Data}
 * ({@code decodeBoundedBytes}, plutus-core Data.hs: chunks of at most 64 bytes) accept chunks.
 *
 * <p>Each case builds a valid transaction, replaces one field's definite string by the same content as a one-chunk
 * indefinite string, and decodes the bytes with {@link RawTransaction#parse} (CCL's cbor-java would concatenate the
 * chunks, so the Java decoder is exercised directly).</p>
 */
class DefiniteLengthDecodingTest {

    private static final byte[] ZERO32 = new byte[32];

    /** A field to chunk: the transaction, the definite string's content, and which occurrence to chunk. */
    private record Field(String name, Supplier<TxSpec> spec, Supplier<byte[]> content, int occurrence, boolean text) {
        Field(String name, Supplier<TxSpec> spec, Supplier<byte[]> content) {
            this(name, spec, content, 0, false);
        }
    }

    private static byte[] keyHash(TestKey key) {
        return HexUtil.decodeHexString(key.keyHash());
    }

    private static byte[] account(TestKey key) {
        return new Address(MutationWorld.rewardAccount(key, MutationWorld.NETWORK)).getBytes();
    }

    private static TxSpec simple() {
        return MutationWorld.simpleSpec();
    }

    private static TxSpec with(TxSpec spec, Consumer<TxSpec> edit) {
        edit.accept(spec);
        return spec;
    }

    private static TransactionOutput output(TestKey key) {
        return MutationWorld.output(key.enterpriseAddress(MutationWorld.NETWORK), BigInteger.valueOf(5_000_000));
    }

    private static List<Field> fields() {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("input transaction id", DefiniteLengthDecodingTest::simple,
                () -> HexUtil.decodeHexString(MutationWorld.KEY_INPUT.getTransactionId())));
        fields.add(new Field("output address", DefiniteLengthDecodingTest::simple,
                () -> new Address(TestKey.DEV_AA.enterpriseAddress(MutationWorld.NETWORK)).getBytes()));
        fields.add(new Field("vkey", DefiniteLengthDecodingTest::simple, TestKey.DEV_42::verificationKey));
        fields.add(new Field("vkey witness signature", DefiniteLengthDecodingTest::simple,
                () -> ConwayTxBuilder.build(simple(), MutationWorld.view()).tx().getWitnessSet().getVkeyWitnesses()
                        .getFirst().getSignature()));
        fields.add(new Field("required signer", () -> with(simple(), s -> {
            s.requiredSigners.add(TestKey.DEV_AA.keyHash());
            s.signers.add(TestKey.DEV_AA);
        }), () -> keyHash(TestKey.DEV_AA)));
        fields.add(new Field("auxiliary data hash", () -> with(simple(), s -> s.metadata = MutationWorld.smallMetadata()),
                DefiniteLengthDecodingTest::auxDataHash));
        fields.add(new Field("script integrity hash", MutationWorld::scriptSpec, DefiniteLengthDecodingTest::scriptDataHash));
        fields.add(new Field("witness Plutus script", MutationWorld::scriptSpec,
                () -> HexUtil.decodeHexString("450101002499")));
        fields.add(new Field("output datum hash", () -> with(simple(), s -> {
            TransactionOutput out = output(TestKey.DEV_AA);
            out.setDatumHash(MutationWorld.datumHash(MutationWorld.DATUM));
            s.outputs.add(out);
        }), () -> MutationWorld.datumHash(MutationWorld.DATUM)));
        fields.add(new Field("inline datum wrapper (tag 24)", () -> with(simple(), s -> {
            TransactionOutput out = output(TestKey.DEV_AA);
            out.setInlineDatum(MutationWorld.DATUM);
            s.outputs.add(out);
        }), () -> serialize(MutationWorld.DATUM)));
        fields.add(new Field("reference script wrapper (tag 24)", () -> with(simple(), s -> {
            TransactionOutput out = output(TestKey.DEV_AA);
            out.setScriptRef(MutationWorld.ALWAYS_SUCCEEDS);
            s.outputs.add(out);
        }), DefiniteLengthDecodingTest::alwaysSucceedsScriptRef));
        fields.add(new Field("policy id", () -> with(simple(), s -> s.inputs.add(MutationWorld.TOKEN_COLLATERAL_INPUT)),
                () -> HexUtil.decodeHexString(MutationWorld.TOKEN_POLICY)));
        fields.add(new Field("delegatee pool", () -> with(simple(), s -> {
            s.certs.add(new StakeDelegation(MutationWorld.stakeCredential(TestKey.DEV_BB),
                    new StakePoolId(keyHash(TestKey.DEV_77))));
            s.signers.add(TestKey.DEV_BB);
        }), () -> keyHash(TestKey.DEV_77)));
        fields.add(new Field("DRep credential", () -> with(simple(), s -> {
            s.certs.add(new VoteDelegCert(MutationWorld.stakeCredential(TestKey.DEV_BB),
                    DRep.addrKeyHash(TestKey.DEV_77.keyHash())));
            s.signers.add(TestKey.DEV_BB);
        }), () -> keyHash(TestKey.DEV_77)));
        fields.add(new Field("pool operator", DefiniteLengthDecodingTest::poolRegistration,
                () -> keyHash(TestKey.DEV_42), 0, false));
        fields.add(new Field("pool owner", DefiniteLengthDecodingTest::poolRegistration,
                () -> keyHash(TestKey.DEV_42), 1, false));
        fields.add(new Field("pool VRF key hash", DefiniteLengthDecodingTest::poolRegistration,
                () -> MutationWorld.FRESH_VRF.clone()));
        fields.add(new Field("pool reward account", DefiniteLengthDecodingTest::poolRegistration,
                () -> account(TestKey.DEV_42)));
        fields.add(new Field("pool metadata hash", DefiniteLengthDecodingTest::poolRegistration,
                () -> HexUtil.decodeHexString("cd".repeat(32))));
        fields.add(new Field("pool metadata url", DefiniteLengthDecodingTest::poolRegistration,
                () -> "https://example.com/pool.json".getBytes(StandardCharsets.UTF_8), 0, true));
        fields.add(new Field("retired pool id", () -> with(simple(), s -> {
            s.certs.add(new PoolRetirement(keyHash(TestKey.DEV_77), 1));
            s.signers.add(TestKey.DEV_77);
        }), () -> keyHash(TestKey.DEV_77)));
        fields.add(new Field("withdrawal account", () -> with(simple(), s -> {
            s.withdrawals.add(new Withdrawal(MutationWorld.rewardAccount(TestKey.DEV_BB, MutationWorld.NETWORK),
                    MutationWorld.REWARD_BALANCE));
            s.signers.add(TestKey.DEV_BB);
        }), () -> account(TestKey.DEV_BB)));
        fields.add(new Field("proposal return account", DefiniteLengthDecodingTest::proposal,
                () -> account(TestKey.DEV_77)));
        fields.add(new Field("anchor data hash", DefiniteLengthDecodingTest::proposal, () -> ZERO32.clone()));
        fields.add(new Field("anchor url", DefiniteLengthDecodingTest::proposal,
                () -> "https://example.com/proposal.json".getBytes(StandardCharsets.UTF_8), 0, true));
        fields.add(new Field("voter", DefiniteLengthDecodingTest::vote, () -> keyHash(TestKey.DEV_77)));
        fields.add(new Field("voted action's transaction id", DefiniteLengthDecodingTest::vote,
                () -> HexUtil.decodeHexString(MutationWorld.INFO_ACTION.txHashHex())));
        fields.add(new Field("native script key hash", () -> with(simple(), s -> {
            s.inputs.add(MutationWorld.NATIVE_INPUT);
            s.nativeScripts.add(MutationWorld.NATIVE_SCRIPT);
        }), () -> keyHash(TestKey.DEV_42)));
        fields.add(new Field("auxiliary data Plutus script", () -> with(simple(),
                s -> s.auxPlutusScripts.add(MutationWorld.ALWAYS_SUCCEEDS)), () -> HexUtil.decodeHexString("450101002499")));
        fields.add(new Field("bootstrap witness vkey", DefiniteLengthDecodingTest::bootstrap,
                TestKey.DEV_42::verificationKey));
        fields.add(new Field("bootstrap witness signature", DefiniteLengthDecodingTest::bootstrap,
                () -> ConwayTxBuilder.build(bootstrap(), MutationWorld.view()).tx().getWitnessSet()
                        .getBootstrapWitnesses().getFirst().getSignature()));
        fields.add(new Field("bootstrap witness chain code", DefiniteLengthDecodingTest::bootstrap,
                MutationWorld.BOOTSTRAP_CHAIN_CODE::clone));
        fields.add(new Field("bootstrap witness attributes", DefiniteLengthDecodingTest::bootstrap,
                MutationWorld.BOOTSTRAP_ATTRIBUTES::clone));
        return fields;
    }

    private static byte[] auxDataHash() {
        return ConwayTxBuilder.build(with(simple(), s -> s.metadata = MutationWorld.smallMetadata()),
                MutationWorld.view()).tx().getBody().getAuxiliaryDataHash();
    }

    private static byte[] scriptDataHash() {
        return ConwayTxBuilder.build(MutationWorld.scriptSpec(), MutationWorld.view()).tx().getBody()
                .getScriptDataHash();
    }

    private static byte[] alwaysSucceedsScriptRef() {
        try {
            return MutationWorld.ALWAYS_SUCCEEDS.scriptRefBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] serialize(PlutusData data) {
        try {
            return data.serializeToBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TxSpec poolRegistration() {
        return with(simple(), s -> {
            s.inputs.add(MutationWorld.RICH_INPUT);
            s.certs.add(MutationWorld.poolRegistration(TestKey.DEV_42, MutationWorld.FRESH_VRF, MutationWorld.MIN_POOL_COST,
                    MutationWorld.NETWORK, "cd".repeat(32)));
            s.changeAdjust = MutationWorld.POOL_DEPOSIT.negate();
        });
    }

    private static TxSpec proposal() {
        return with(simple(), s -> {
            s.inputs.add(MutationWorld.GOV_INPUT);
            s.proposals.add(ProposalProcedure.builder().deposit(MutationWorld.GOV_ACTION_DEPOSIT)
                    .rewardAccount(MutationWorld.rewardAccount(TestKey.DEV_77, MutationWorld.NETWORK))
                    .govAction(new InfoAction())
                    .anchor(new Anchor("https://example.com/proposal.json", ZERO32.clone()))
                    .build());
            s.changeAdjust = MutationWorld.GOV_ACTION_DEPOSIT.negate();
        });
    }

    private static TxSpec vote() {
        return with(simple(), s -> {
            VotingProcedures votes = new VotingProcedures();
            votes.add(new Voter(VoterType.DREP_KEY_HASH, MutationWorld.credential(TestKey.DEV_77)),
                    new GovActionId(MutationWorld.INFO_ACTION.txHashHex(), MutationWorld.INFO_ACTION.index()),
                    new VotingProcedure(Vote.YES, null));
            s.votingProcedures = votes;
            s.signers.add(TestKey.DEV_77);
        });
    }

    private static TxSpec bootstrap() {
        TxSpec spec = simple();
        spec.inputs = new ArrayList<>(List.of(MutationWorld.BYRON_INPUT));
        spec.outputs = new ArrayList<>();
        spec.signers = new ArrayList<>();
        spec.bootstrapSigners.add(TestKey.DEV_42);
        return spec;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The CBOR head of a definite byte (major 2) or text (major 3) string of {@code length} bytes. */
    private static byte[] head(int major, int length) {
        int base = major << 5;
        if (length < 24) {
            return new byte[]{(byte) (base | length)};
        }
        if (length < 256) {
            return new byte[]{(byte) (base | 24), (byte) length};
        }
        return new byte[]{(byte) (base | 25), (byte) (length >> 8), (byte) length};
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** Replaces the {@code occurrence}-th definite encoding of {@code content} by a one-chunk indefinite string. */
    private static byte[] chunk(byte[] cbor, byte[] content, int occurrence, boolean text) {
        int major = text ? 3 : 2;
        byte[] definite = concat(head(major, content.length), content);
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i + definite.length <= cbor.length; i++) {
            if (Arrays.equals(cbor, i, i + definite.length, definite, 0, definite.length)) {
                found.add(i);
            }
        }
        assertThat(found).as("occurrences of the field").hasSizeGreaterThan(occurrence);
        int at = found.get(occurrence);
        return concat(Arrays.copyOfRange(cbor, 0, at), new byte[]{(byte) (text ? 0x7f : 0x5f)}, definite,
                new byte[]{(byte) 0xff}, Arrays.copyOfRange(cbor, at + definite.length, cbor.length));
    }

    // ------------------------------------------------------------------------------------------------ tests

    @Test
    void everyLedgerByteStringMustBeDefinite() {
        Map<String, String> accepted = new LinkedHashMap<>();
        for (Field field : fields()) {
            BuiltTx built = ConwayTxBuilder.build(field.spec().get(), MutationWorld.view());
            assertThatCode(() -> RawTransaction.parse(built.cbor(), built.tx())).as(field.name())
                    .doesNotThrowAnyException();
            byte[] chunked = chunk(built.cbor(), field.content().get(), field.occurrence(), field.text());
            try {
                RawTransaction.parse(chunked, built.tx());
                accepted.put(field.name(), "accepted");
            } catch (TxDecodingException expected) {
                // refused, as Haskell refuses it
            }
        }
        assertThat(accepted).as("chunked fields the decoder accepted").isEmpty();
    }

    @Test
    void theOutputsByronAddressPayloadMustBeDefinite() {
        String byron = MutationWorld.bootstrapAddress(TestKey.DEV_42);
        TxSpec spec = simple();
        spec.outputs.add(MutationWorld.output(byron, BigInteger.valueOf(5_000_000)));
        BuiltTx built = ConwayTxBuilder.build(spec, MutationWorld.view());
        byte[] address = Base58.decode(byron);
        // address = [24(bytes payload), crc]: chunk the payload and re-encode the address byte string.
        List<CborSpan> parts = StrictCbor.span(address).items();
        byte[] payload = parts.get(0).untag().byteString();
        byte[] crc = Arrays.copyOfRange(address, parts.get(1).offset(), address.length);
        byte[] chunkedAddress = concat(new byte[]{(byte) 0x82, (byte) 0xd8, 0x18, 0x5f}, head(2, payload.length), payload,
                new byte[]{(byte) 0xff}, crc);
        byte[] original = concat(head(2, address.length), address);
        byte[] cbor = built.cbor();
        int at = -1;
        for (int i = 0; i + original.length <= cbor.length; i++) {
            if (Arrays.equals(cbor, i, i + original.length, original, 0, original.length)) {
                at = i;
            }
        }
        assertThat(at).isNotNegative();
        byte[] edited = concat(Arrays.copyOfRange(cbor, 0, at), head(2, chunkedAddress.length), chunkedAddress,
                Arrays.copyOfRange(cbor, at + original.length, cbor.length));
        assertThatThrownBy(() -> RawTransaction.parse(edited, built.tx())).isInstanceOf(TxDecodingException.class);
    }

    @Test
    void metadataAcceptsChunkedStrings() {
        byte[] bytes = new byte[40];
        Arrays.fill(bytes, (byte) 0x5a);
        TxSpec spec = with(simple(), s -> s.metadata = new CBORMetadata().put(BigInteger.valueOf(674), bytes)
                .put(BigInteger.valueOf(675), "a chunked metadatum text"));
        BuiltTx built = ConwayTxBuilder.build(spec, MutationWorld.view());
        byte[] chunkedBytes = chunk(built.cbor(), bytes, 0, false);
        assertThatCode(() -> RawTransaction.parse(chunkedBytes, built.tx())).doesNotThrowAnyException();
        byte[] chunkedText = chunk(built.cbor(), "a chunked metadatum text".getBytes(StandardCharsets.UTF_8), 0, true);
        assertThatCode(() -> RawTransaction.parse(chunkedText, built.tx())).doesNotThrowAnyException();
    }

    private static TxSpec redeemerCarrying(PlutusData data) {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.redeemers = new ArrayList<>(List.of(Redeemer.builder().tag(RedeemerTag.Spend).index(BigInteger.ONE)
                .data(data).exUnits(ExUnits.builder().mem(BigInteger.valueOf(100_000))
                        .steps(BigInteger.valueOf(50_000_000)).build()).build()));
        return spec;
    }

    @Test
    void plutusDataAcceptsChunksOfAtMost64Bytes() {
        byte[] small = new byte[40];
        Arrays.fill(small, (byte) 0x33);
        BuiltTx built = ConwayTxBuilder.build(redeemerCarrying(ConstrPlutusData.of(0, BytesPlutusData.of(small))),
                MutationWorld.view());
        assertThatCode(() -> RawTransaction.parse(built.cbor(), built.tx())).doesNotThrowAnyException();
        byte[] chunked = chunk(built.cbor(), small, 0, false);
        assertThatCode(() -> RawTransaction.parse(chunked, built.tx())).as("a chunked byte string in Data")
                .doesNotThrowAnyException();
    }

    @Test
    void plutusDataRefusesByteStringsOver64Bytes() {
        // CCL chunks byte strings over 64 bytes itself; the definite 65-byte form is written into the bytes.
        byte[] small = new byte[64];
        Arrays.fill(small, (byte) 0x33);
        BuiltTx built = ConwayTxBuilder.build(redeemerCarrying(ConstrPlutusData.of(0, BytesPlutusData.of(small))),
                MutationWorld.view());
        byte[] definite64 = concat(head(2, 64), small);
        byte[] bigger = new byte[65];
        Arrays.fill(bigger, (byte) 0x33);
        byte[] definite65 = concat(head(2, 65), bigger);
        byte[] cbor = built.cbor();
        int at = -1;
        for (int i = 0; i + definite64.length <= cbor.length; i++) {
            if (Arrays.equals(cbor, i, i + definite64.length, definite64, 0, definite64.length)) {
                at = i;
            }
        }
        assertThat(at).isNotNegative();
        byte[] edited = concat(Arrays.copyOfRange(cbor, 0, at), definite65,
                Arrays.copyOfRange(cbor, at + definite64.length, cbor.length));
        // The witness set's length is unchanged in CBOR terms (the byte string sits in a nested item), so it still
        // parses as CBOR; plutus-core's decodeBoundedBytes refuses the 65-byte string.
        assertThatThrownBy(() -> RawTransaction.parse(edited, built.tx())).isInstanceOf(TxDecodingException.class)
                .hasMessageContaining("64 bytes");
    }

    @Test
    void anInlineDatumMustBeWellFormedData() {
        TxSpec spec = with(simple(), s -> {
            TransactionOutput out = output(TestKey.DEV_AA);
            out.setInlineDatum(MutationWorld.DATUM);
            s.outputs.add(out);
        });
        BuiltTx built = ConwayTxBuilder.build(spec, MutationWorld.view());
        // Replace the inline datum (the integer 42, 18 2a) by a text string (61 41): not Plutus Data.
        byte[] wrapped = concat(new byte[]{(byte) 0xd8, 0x18}, head(2, 2), new byte[]{0x18, 0x2a});
        byte[] cbor = built.cbor();
        int at = -1;
        for (int i = 0; i + wrapped.length <= cbor.length; i++) {
            if (Arrays.equals(cbor, i, i + wrapped.length, wrapped, 0, wrapped.length)) {
                at = i;
            }
        }
        assertThat(at).isNotNegative();
        byte[] edited = cbor.clone();
        edited[at + 3] = 0x61;
        edited[at + 4] = 0x41;
        assertThatThrownBy(() -> RawTransaction.parse(edited, built.tx())).isInstanceOf(TxDecodingException.class);
    }
}
