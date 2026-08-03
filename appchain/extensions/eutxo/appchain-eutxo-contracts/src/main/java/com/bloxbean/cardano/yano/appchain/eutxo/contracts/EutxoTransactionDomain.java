package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.transaction.spec.AuxiliaryData;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Signed replay domain for a validity-proved EUTxO transaction.
 *
 * <p>The envelope is ordinary Cardano transaction metadata. Its auxiliary-data
 * hash is carried by the transaction body and is therefore covered by the
 * Cardano VKey signature. The first validity profile deliberately admits one
 * exact metadata shape so a circuit never has to interpret an open-ended
 * metadata language.</p>
 */
public record EutxoTransactionDomain(
        String chainId,
        String network,
        String ledgerProfileDigest,
        String validityProfileDigest,
        byte[] nonce,
        long expiry
) {
    public static final int VERSION = 1;

    /** ASCII {@code YANO}, represented as an unsigned Cardano metadata label. */
    public static final long METADATA_LABEL = 0x59414E4FL;

    private static final BigInteger VERSION_KEY = BigInteger.ZERO;
    private static final BigInteger CHAIN_KEY = BigInteger.ONE;
    private static final BigInteger NETWORK_KEY = BigInteger.TWO;
    private static final BigInteger LEDGER_PROFILE_KEY = BigInteger.valueOf(3);
    private static final BigInteger VALIDITY_PROFILE_KEY = BigInteger.valueOf(4);
    private static final BigInteger NONCE_KEY = BigInteger.valueOf(5);
    private static final BigInteger EXPIRY_KEY = BigInteger.valueOf(6);
    private static final List<BigInteger> REQUIRED_KEYS = List.of(
            VERSION_KEY,
            CHAIN_KEY,
            NETWORK_KEY,
            LEDGER_PROFILE_KEY,
            VALIDITY_PROFILE_KEY,
            NONCE_KEY,
            EXPIRY_KEY);

    public EutxoTransactionDomain {
        chainId = text(chainId, "chain id", 63);
        network = text(network, "network", 16);
        if (!List.of("devnet", "preview", "preprod").contains(network)) {
            throw new IllegalArgumentException(
                    "EUTxO transaction domain requires a supported test network");
        }
        ledgerProfileDigest = digest(
                ledgerProfileDigest, "ledger profile digest");
        validityProfileDigest = digest(
                validityProfileDigest, "validity profile digest");
        nonce = Objects.requireNonNull(nonce, "nonce").clone();
        if (nonce.length != 32) {
            throw new IllegalArgumentException("nonce must contain 32 bytes");
        }
        if (expiry < 1) {
            throw new IllegalArgumentException("expiry must be positive");
        }
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    /**
     * Attaches the exact domain metadata and its body hash before signing.
     *
     * <p>Callers must invoke this before a Cardano transaction signer. Existing
     * auxiliary data is rejected because the first circuit profile accepts one
     * fixed metadata shape.</p>
     */
    public void attach(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        if (transaction.getBody() == null) {
            throw new IllegalArgumentException("transaction body is required");
        }
        if (transaction.getBody().getTtl() != expiry) {
            throw new IllegalArgumentException(
                    "transaction TTL must equal the validity-domain expiry");
        }
        if (transaction.getAuxiliaryData() != null
                || transaction.getBody().getAuxiliaryDataHash() != null) {
            throw new IllegalArgumentException(
                    "validity domain requires an empty auxiliary-data slot");
        }
        Metadata metadata = MetadataBuilder.createMetadata();
        metadata.put(METADATA_LABEL, metadataMap());
        AuxiliaryData auxiliaryData = AuxiliaryData.builder()
                .metadata(metadata)
                .build();
        transaction.setAuxiliaryData(auxiliaryData);
        transaction.getBody().setAuxiliaryDataHash(
                auxiliaryData.getAuxiliaryDataHash());
    }

    /** Parses and validates the fixed Cardano metadata envelope. */
    public static EutxoTransactionDomain from(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        if (transaction.getBody() == null
                || transaction.getAuxiliaryData() == null
                || transaction.getAuxiliaryData().getMetadata() == null
                || transaction.getBody().getAuxiliaryDataHash() == null) {
            throw new IllegalArgumentException(
                    "validity transaction is missing its signed domain metadata");
        }
        AuxiliaryData auxiliaryData = transaction.getAuxiliaryData();
        if (!empty(auxiliaryData.getNativeScripts())
                || !empty(auxiliaryData.getPlutusV1Scripts())
                || !empty(auxiliaryData.getPlutusV2Scripts())
                || !empty(auxiliaryData.getPlutusV3Scripts())) {
            throw new IllegalArgumentException(
                    "validity transaction auxiliary data contains scripts");
        }
        if (!java.util.Arrays.equals(
                transaction.getBody().getAuxiliaryDataHash(),
                auxiliaryData.getAuxiliaryDataHash())) {
            throw new IllegalArgumentException(
                    "validity transaction auxiliary-data hash does not match");
        }

        Metadata metadata = auxiliaryData.getMetadata();
        BigInteger label = BigInteger.valueOf(METADATA_LABEL);
        List<?> metadataKeys = metadata.keys();
        if (metadataKeys.size() != 1 || !metadataKeys.contains(label)) {
            throw new IllegalArgumentException(
                    "validity transaction must contain only the Yano domain label");
        }
        Object value = metadata.get(label);
        List<?> domainKeys = value instanceof MetadataMap map
                ? map.keys() : List.of();
        if (!(value instanceof MetadataMap map)
                || domainKeys.size() != REQUIRED_KEYS.size()
                || !domainKeys.containsAll(REQUIRED_KEYS)) {
            throw new IllegalArgumentException(
                    "validity transaction domain has an unsupported shape");
        }
        if (!BigInteger.valueOf(VERSION).equals(map.get(VERSION_KEY))) {
            throw new IllegalArgumentException(
                    "unsupported EUTxO transaction domain version");
        }
        EutxoTransactionDomain domain = new EutxoTransactionDomain(
                string(map.get(CHAIN_KEY), "chain id"),
                string(map.get(NETWORK_KEY), "network"),
                hex(bytes(map.get(LEDGER_PROFILE_KEY),
                        "ledger profile digest")),
                hex(bytes(map.get(VALIDITY_PROFILE_KEY),
                        "validity profile digest")),
                bytes(map.get(NONCE_KEY), "nonce"),
                integer(map.get(EXPIRY_KEY), "expiry").longValueExact());
        if (transaction.getBody().getTtl() != domain.expiry()) {
            throw new IllegalArgumentException(
                    "transaction TTL differs from signed domain expiry");
        }
        return domain;
    }

    public void requireExpected(
            String expectedChainId,
            String expectedNetwork,
            String expectedLedgerProfileDigest,
            String expectedValidityProfileDigest
    ) {
        if (!chainId.equals(expectedChainId)
                || !network.equals(expectedNetwork)
                || !ledgerProfileDigest.equals(expectedLedgerProfileDigest)
                || !validityProfileDigest.equals(
                expectedValidityProfileDigest)) {
            throw new IllegalArgumentException(
                    "signed EUTxO transaction domain does not match this chain");
        }
    }

    /** Stable commitment exposed as a fixed public input to validity circuits. */
    public byte[] commitment() {
        return Blake2bUtil.blake2bHash256(canonicalBytes());
    }

    public byte[] canonicalBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                write(output, "yano:eutxo:transaction-domain:v1"
                        .getBytes(StandardCharsets.US_ASCII));
                write(output, chainId.getBytes(StandardCharsets.UTF_8));
                write(output, network.getBytes(StandardCharsets.US_ASCII));
                write(output, HexFormat.of().parseHex(ledgerProfileDigest));
                write(output, HexFormat.of().parseHex(validityProfileDigest));
                write(output, nonce);
                output.writeLong(expiry);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(
                    "in-memory transaction-domain encoding failed", impossible);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoTransactionDomain domain
                && chainId.equals(domain.chainId)
                && network.equals(domain.network)
                && ledgerProfileDigest.equals(
                domain.ledgerProfileDigest)
                && validityProfileDigest.equals(
                domain.validityProfileDigest)
                && java.util.Arrays.equals(nonce, domain.nonce)
                && expiry == domain.expiry;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                chainId, network, ledgerProfileDigest,
                validityProfileDigest, expiry);
        return 31 * result + java.util.Arrays.hashCode(nonce);
    }

    private MetadataMap metadataMap() {
        return MetadataBuilder.createMap()
                .put(VERSION_KEY, BigInteger.valueOf(VERSION))
                .put(CHAIN_KEY, chainId)
                .put(NETWORK_KEY, network)
                .put(LEDGER_PROFILE_KEY,
                        HexFormat.of().parseHex(ledgerProfileDigest))
                .put(VALIDITY_PROFILE_KEY,
                        HexFormat.of().parseHex(validityProfileDigest))
                .put(NONCE_KEY, nonce)
                .put(EXPIRY_KEY, BigInteger.valueOf(expiry));
    }

    private static void write(DataOutputStream output, byte[] value)
            throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    private static boolean empty(List<?> values) {
        return values == null || values.isEmpty();
    }

    private static String text(String value, String label, int maximum) {
        value = Objects.requireNonNull(value, label).trim();
        if (value.isEmpty()
                || value.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }

    private static String digest(String value, String label) {
        value = text(value, label, 64);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    label + " must be a lowercase 32-byte digest");
        }
        return value;
    }

    private static String string(Object value, String label) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(
                    "transaction domain " + label + " must be text");
        }
        return text;
    }

    private static byte[] bytes(Object value, String label) {
        if (!(value instanceof byte[] bytes)) {
            throw new IllegalArgumentException(
                    "transaction domain " + label + " must be bytes");
        }
        return bytes.clone();
    }

    private static BigInteger integer(Object value, String label) {
        if (!(value instanceof BigInteger integer) || integer.signum() < 0) {
            throw new IllegalArgumentException(
                    "transaction domain " + label
                            + " must be an unsigned integer");
        }
        return integer;
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }
}
