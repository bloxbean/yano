package com.bloxbean.cardano.yano.appchain.eutxo.zk.client;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoSnapshot;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Domain;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkAuthorizationProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.EdDSAJubjub;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubCurve;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubPoint;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoL2ClientSurfaceTest {

    @Test
    void cclBodyIsWrappedAndAuthorizedWithoutACardanoWitnessSet()
            throws Exception {
        Fixture fixture = fixture();
        byte[] cip8 = new byte[96];
        Arrays.fill(cip8, (byte) 9);
        byte[] context = new byte[32];
        Arrays.fill(context, (byte) 5);

        try (var first = EutxoL2SessionKey.fromCip8Signature(cip8, context);
             var second = EutxoL2SessionKey.fromCip8Signature(cip8, context)) {
            assertThat(first.publicKey()).isEqualTo(second.publicKey());
            var transaction = EutxoL2TransactionBuilder.sign(
                    fixture.domain,
                    fixture.body,
                    List.of(new EutxoL2TransactionBuilder.Signer(
                            fixture.credential, 1, List.of(0), first)));

            assertThat(transaction.decodedBody()).isEqualTo(fixture.body);
            assertThat(transaction.authorizations()).hasSize(1);
            assertThat(transaction.canonicalBytes())
                    .startsWith(new byte[]{0, 0});

            var authorization = transaction.authorizations().getFirst();
            BigInteger message = new BigInteger(
                    1, transaction.signingCommitment())
                    .mod(JubjubCurve.BASE_FIELD_PRIME);
            assertThat(EdDSAJubjub.verify(
                    JubjubPoint.fromBytes(authorization.publicKey()),
                    message,
                    new EdDSAJubjub.Signature(
                            JubjubPoint.fromBytes(authorization.rPoint()),
                            littleEndian(authorization.s()))))
                    .isTrue();

            first.close();
            assertThat(first.toString())
                    .isEqualTo("EutxoL2SessionKey[REDACTED]");
            assertThatThrownBy(first::publicKey)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void protocolSnapshotIsImmutableZeroFeeAndBoundToAllProfiles() {
        var authorization =
                EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1;
        var parameters = EutxoL2ProtocolParameters.create(
                "payments",
                EutxoProfile.V1,
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex(),
                authorization.id(),
                authorization.digestHex());

        assertThat(parameters.getProtocolParams().getMinFeeA()).isZero();
        assertThat(parameters.getProtocolParams().getMinFeeB()).isZero();
        assertThat(parameters.getProtocolParams().getMaxTxSize())
                .isEqualTo(EutxoProfile.V1.maxTransactionBytes());
        assertThat(parameters.digest()).matches("[0-9a-f]{64}");
        assertThatThrownBy(() -> new EutxoL2ProtocolParameters(
                parameters.chainId(),
                parameters.ledgerProfileDigest(),
                parameters.validityProfileDigest(),
                parameters.authorizationProfile(),
                parameters.authorizationProfileDigest(),
                parameters.maxTransactionBytes(),
                parameters.maxInputs(),
                parameters.maxOutputs(),
                "00".repeat(32)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digest mismatch");
    }

    @Test
    void randomSessionKeysRoundTripOnlyThroughAuthenticatedEncryption() {
        char[] password = "correct horse battery staple".toCharArray();
        try (var original = EutxoL2SessionKey.random()) {
            byte[] publicKey = original.publicKey();
            byte[] encrypted = original.encrypt(password);
            assertThat(contains(encrypted, publicKey)).isFalse();
            try (var restored = EutxoL2SessionKey.decrypt(
                    encrypted, password)) {
                assertThat(restored.publicKey()).isEqualTo(publicKey);
            }
            assertThatThrownBy(() -> EutxoL2SessionKey.decrypt(
                    encrypted, "wrong password value".toCharArray()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be opened");
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Test
    void cclSupplierPaginatesOneRootAndRejectsMixedSnapshots()
            throws Exception {
        Fixture fixture = fixture();
        EutxoRecord first = record(
                "11".repeat(32), 0, fixture.wallet.address(), 9);
        EutxoRecord second = record(
                "22".repeat(32), 0, fixture.wallet.address(), 10);
        byte[] root = new byte[32];
        Arrays.fill(root, (byte) 3);
        MutableSnapshots snapshots = new MutableSnapshots(
                fixture.wallet.address(), List.of(second, first), root);
        var supplier = new EutxoRootFixedUtxoSupplier(snapshots);

        assertThat(supplier.getPage(
                fixture.wallet.address(), 1, 0, OrderEnum.asc)
                .getFirst().getTxHash()).isEqualTo(first.outpoint().transactionId());
        assertThat(supplier.getPage(
                fixture.wallet.address(), 1, 1, OrderEnum.asc)
                .getFirst().getTxHash()).isEqualTo(second.outpoint().transactionId());
        assertThat(supplier.getTxOutput(
                first.outpoint().transactionId(), 0)).isPresent();
        assertThat(supplier.stateRoot()).isEqualTo(root);

        snapshots.root[0] ^= 1;
        assertThatThrownBy(() -> supplier.getPage(
                fixture.wallet.address(), 1, 0, OrderEnum.asc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("state root changed");
    }

    private static Fixture fixture() throws Exception {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 7);
        EutxoTestWallet wallet = EutxoTestWallet.fromSeed(seed);
        String credential = new Address(wallet.address())
                .getPaymentCredentialHash()
                .map(HexFormat.of()::formatHex)
                .orElseThrow();
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(TransactionInput.builder()
                        .transactionId("11".repeat(32))
                        .index(0)
                        .build()))
                .outputs(List.of(TransactionOutput.builder()
                        .address(wallet.address())
                        .value(Value.fromCoin(BigInteger.valueOf(100)))
                        .build()))
                .fee(BigInteger.ZERO)
                .ttl(100)
                .networkId(NetworkId.TESTNET)
                .build();
        var authorization =
                EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1;
        var domain = new EutxoL2Domain(
                "payments",
                "devnet",
                EutxoProfile.V1.digestHex(),
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex(),
                authorization.id(),
                authorization.digestHex(),
                new byte[32],
                100);
        return new Fixture(wallet, credential, body, domain);
    }

    private static EutxoRecord record(
            String transactionId,
            int index,
            String address,
            long lovelace
    ) throws Exception {
        TransactionOutput output = TransactionOutput.builder()
                .address(address)
                .value(Value.fromCoin(BigInteger.valueOf(lovelace)))
                .build();
        return new EutxoRecord(
                new EutxoOutpoint(transactionId, index),
                address,
                CborSerializationUtil.serialize(output.serialize()),
                EutxoRecord.Origin.TRANSACTION);
    }

    private static BigInteger littleEndian(byte[] value) {
        byte[] copy = value.clone();
        for (int left = 0, right = copy.length - 1;
             left < right; left++, right--) {
            byte item = copy[left];
            copy[left] = copy[right];
            copy[right] = item;
        }
        return new BigInteger(1, copy);
    }

    private static boolean contains(byte[] value, byte[] candidate) {
        outer:
        for (int offset = 0;
             offset <= value.length - candidate.length;
             offset++) {
            for (int index = 0; index < candidate.length; index++) {
                if (value[offset + index] != candidate[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private record Fixture(
            EutxoTestWallet wallet,
            String credential,
            TransactionBody body,
            EutxoL2Domain domain
    ) {
    }

    private static final class MutableSnapshots
            implements EutxoRootFixedUtxoSupplier.SnapshotSource {
        private final String address;
        private final List<EutxoRecord> records;
        private final byte[] root;

        private MutableSnapshots(
                String address,
                List<EutxoRecord> records,
                byte[] root
        ) {
            this.address = address;
            this.records = records;
            this.root = root;
        }

        @Override
        public EutxoSnapshot<List<EutxoRecord>> address(String requested) {
            return new EutxoSnapshot<>(
                    "payments", 7, root,
                    address.equals(requested) ? records : List.of());
        }

        @Override
        public EutxoSnapshot<Optional<EutxoRecord>> outpoint(
                EutxoOutpoint outpoint
        ) {
            return new EutxoSnapshot<>(
                    "payments", 7, root,
                    records.stream()
                            .filter(item -> item.outpoint().equals(outpoint))
                            .findFirst());
        }
    }
}
