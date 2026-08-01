package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Demo-only virtual-funds transaction fixture used by the showcase shell.
 * The derived key is deliberately public and must never receive real funds.
 */
public final class ShowcaseEutxoTransactions {
    public static final BigInteger GENESIS_LOVELACE = BigInteger.valueOf(100_000_000L);
    private static final String SEED_LABEL = "yano-showcase-virtual-eutxo-v1";

    private ShowcaseEutxoTransactions() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "identity".equals(args[0])) {
            System.out.printf("{\"address\":\"%s\",\"genesisOutpoint\":\"%s\"}%n",
                    address(), genesisOutpoint());
            return;
        }
        if (args.length == 2 && "payment".equals(args[0])) {
            byte[] encoded = payment(EutxoOutpoint.parse(args[1]));
            System.out.printf("{\"transactionHex\":\"%s\",\"transactionId\":\"%s\"}%n",
                    HexFormat.of().formatHex(encoded), TransactionUtil.getTxHash(encoded));
            return;
        }
        if (args.length == 2 && "outpoint".equals(args[0])) {
            var records = EutxoQueryCodec.decodeRecords(HexFormat.of().parseHex(args[1]));
            if (records.size() != 1) {
                throw new IllegalArgumentException("showcase wallet must own exactly one output");
            }
            System.out.println(records.getFirst().outpoint());
            return;
        }
        if (args.length == 2 && "receipt".equals(args[0])) {
            System.out.println(receiptStatus(HexFormat.of().parseHex(args[1])));
            return;
        }
        throw new IllegalArgumentException(
                "usage: identity | payment <tx-id#index> | outpoint <records-cbor-hex>"
                        + " | receipt <receipt-cbor-hex>");
    }

    public static String address() {
        return wallet().address();
    }

    public static EutxoOutpoint genesisOutpoint() {
        try {
            String address = address();
            byte[] addressBytes = address.getBytes(StandardCharsets.UTF_8);
            byte[] output = CborSerializationUtil.serialize(TransactionOutput.builder()
                    .address(address)
                    .value(Value.fromCoin(GENESIS_LOVELACE))
                    .build()
                    .serialize());
            ByteBuffer canonical = ByteBuffer.allocate(8 + addressBytes.length + output.length);
            canonical.putInt(addressBytes.length).put(addressBytes);
            canonical.putInt(output.length).put(output);
            return new EutxoOutpoint(HexFormat.of().formatHex(
                    Blake2bUtil.blake2bHash256(canonical.array())), 0);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot derive showcase EUTxO genesis", failure);
        }
    }

    public static byte[] payment(EutxoOutpoint input) throws Exception {
        Wallet wallet = wallet();
        Transaction transaction = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(TransactionInput.builder()
                                .transactionId(input.transactionId())
                                .index(input.index())
                                .build()))
                        .outputs(List.of(TransactionOutput.builder()
                                .address(wallet.address())
                                .value(Value.fromCoin(GENESIS_LOVELACE))
                                .build()))
                        .fee(BigInteger.ZERO)
                        .validityStartInterval(0)
                        .ttl(0)
                        .networkId(NetworkId.TESTNET)
                        .build())
                .isValid(true)
                .build();
        return TransactionSigner.INSTANCE.sign(transaction, wallet.signingKey()).serialize();
    }

    public static String receiptStatus(byte[] encoded) {
        var receipt = EutxoQueryCodec.decodeOptionalReceipt(encoded);
        return receipt == null ? "NOT_FOUND" : receipt.status().name();
    }

    private static Wallet wallet() {
        try {
            SecretKey signingKey = SecretKey.create(MessageDigest.getInstance("SHA-256")
                    .digest(SEED_LABEL.getBytes(StandardCharsets.US_ASCII)));
            String address = AddressProvider.getEntAddress(
                    Credential.fromKey(Blake2bUtil.blake2bHash224(
                            KeyGenUtil.getPublicKeyFromPrivateKey(signingKey).getBytes())),
                    Networks.testnet()).toBech32();
            return new Wallet(signingKey, address);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot derive showcase virtual EUTxO wallet", failure);
        }
    }

    private record Wallet(SecretKey signingKey, String address) {
    }
}
