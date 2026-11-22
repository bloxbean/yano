package org.yanoproject.runtime.sync.validation;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.transaction.raw.RawHeader;
import com.bloxbean.cardano.client.transaction.raw.RawItems;
import com.bloxbean.cardano.client.transaction.raw.RawVrfCert;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import org.yanoproject.ledger.rules.conway.tx.HaskellPolicy;
import org.yanoproject.ledger.rules.conway.tx.StrictCbor;
import org.yanoproject.ledger.rules.conway.tx.TxDecodingException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Parsed Shelley+ header data shared by staged validators: CCL's reader reads the header, {@link HaskellPolicy} decides
 * whether it decodes, and the fields must then match yaci's decoding of it.
 */
public record ShelleyHeaderView(
        byte[] headerArrayBytes,
        byte[] headerBodyBytes,
        byte[] signature,
        long blockNumber,
        long slot,
        byte[] previousHash,
        byte[] issuerVkey,
        byte[] vrfVkey,
        byte[] nonceVrfOutput,
        byte[] nonceVrfProof,
        byte[] leaderVrfOutput,
        byte[] leaderVrfProof,
        long blockBodySize,
        byte[] blockBodyHash,
        byte[] opcertKesVkey,
        long opcertCounter,
        long opcertKesPeriod,
        byte[] opcertColdSignature,
        long protocolMajor,
        long protocolMinor
) {
    private static final int MAJOR_BYTES = 2;
    private static final int MAJOR_ARRAY = 4;
    private static final byte[] NO_BYTES = new byte[0];

    public ShelleyHeaderView {
        headerArrayBytes = headerArrayBytes.clone();
        headerBodyBytes = headerBodyBytes.clone();
        signature = signature.clone();
        previousHash = previousHash.clone();
        issuerVkey = issuerVkey.clone();
        vrfVkey = vrfVkey.clone();
        nonceVrfOutput = nonceVrfOutput.clone();
        nonceVrfProof = nonceVrfProof.clone();
        leaderVrfOutput = leaderVrfOutput.clone();
        leaderVrfProof = leaderVrfProof.clone();
        blockBodyHash = blockBodyHash.clone();
        opcertKesVkey = opcertKesVkey.clone();
        opcertColdSignature = opcertColdSignature.clone();
    }

    public static ShelleyHeaderView from(BlockHeader blockHeader, byte[] originalHeaderBytes) {
        if (blockHeader == null || blockHeader.getHeaderBody() == null) {
            throw new HeaderValidationFailure("shape", "missing decoded Shelley header body");
        }
        if (originalHeaderBytes == null || originalHeaderBytes.length == 0) {
            throw new HeaderValidationFailure("shape", "missing original header bytes");
        }

        RawHeader header = read(originalHeaderBytes);
        var decoded = blockHeader.getHeaderBody();
        byte[] previousHash = header.prevHash().orElse(NO_BYTES);
        Optional<RawVrfCert> nonce = header.nonceVrf();
        RawVrfCert leader = header.leaderVrf().or(header::vrfResult).orElseThrow();

        requireEquals("block-number", header.blockNumber(), decoded.getBlockNumber());
        requireEquals("slot", header.slot(), decoded.getSlot());
        requireHexEquals("previous-hash", previousHash, decoded.getPrevHash());
        requireHexEquals("issuer-vkey", header.issuerVkey(), decoded.getIssuerVkey());
        requireHexEquals("vrf-vkey", header.vrfVkey(), decoded.getVrfVkey());
        requireHexEquals("body-hash", header.bodyHash(), decoded.getBlockBodyHash());
        requireEquals("body-size", header.bodySize(), decoded.getBlockBodySize());

        String computedHashHex = HexUtil.encodeHexString(header.hash());
        if (!computedHashHex.equalsIgnoreCase(decoded.getBlockHash())) {
            throw new HeaderValidationFailure("header-hash", "computed block hash does not match decoded header hash");
        }

        if (blockHeader.getBodySignature() != null) {
            requireHexEquals("body-signature", header.bodySignature(), blockHeader.getBodySignature());
        }

        return new ShelleyHeaderView(
                header.span().bytes(),
                signedBody(header),
                header.bodySignature(),
                header.blockNumber(),
                header.slot(),
                previousHash,
                header.issuerVkey(),
                header.vrfVkey(),
                nonce.map(RawVrfCert::output).orElse(NO_BYTES),
                nonce.map(RawVrfCert::proof).orElse(NO_BYTES),
                leader.output(),
                leader.proof(),
                header.bodySize(),
                header.bodyHash(),
                header.hotVkey(),
                header.sequenceNumber(),
                header.kesPeriod(),
                header.sigma(),
                header.protocolMajor(),
                header.protocolMinor());
    }

    @Override
    public byte[] headerArrayBytes() {
        return headerArrayBytes.clone();
    }

    @Override
    public byte[] headerBodyBytes() {
        return headerBodyBytes.clone();
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }

    @Override
    public byte[] previousHash() {
        return previousHash.clone();
    }

    @Override
    public byte[] issuerVkey() {
        return issuerVkey.clone();
    }

    @Override
    public byte[] vrfVkey() {
        return vrfVkey.clone();
    }

    @Override
    public byte[] nonceVrfOutput() {
        return nonceVrfOutput.clone();
    }

    @Override
    public byte[] nonceVrfProof() {
        return nonceVrfProof.clone();
    }

    @Override
    public byte[] leaderVrfOutput() {
        return leaderVrfOutput.clone();
    }

    @Override
    public byte[] leaderVrfProof() {
        return leaderVrfProof.clone();
    }

    @Override
    public byte[] blockBodyHash() {
        return blockBodyHash.clone();
    }

    @Override
    public byte[] opcertKesVkey() {
        return opcertKesVkey.clone();
    }

    @Override
    public byte[] opcertColdSignature() {
        return opcertColdSignature.clone();
    }

    public boolean hasSeparateNonceVrf() {
        return nonceVrfOutput.length > 0 || nonceVrfProof.length > 0;
    }

    /**
     * The header in what chain sync delivers: a byte string, or {@code [era, bytes]}, around it, or the header itself.
     * {@link HaskellPolicy} decides whether it decodes.
     */
    private static RawHeader read(byte[] originalHeaderBytes) {
        try {
            CborSpan root = StrictCbor.span(originalHeaderBytes);
            return HaskellPolicy.header(switch (root.majorType()) {
                case MAJOR_BYTES -> StrictCbor.span(RawItems.bytes(root));
                case MAJOR_ARRAY -> {
                    List<CborSpan> items = RawItems.array(root, "header");
                    boolean wrapped = items.size() == 2 && items.get(1).majorType() == MAJOR_BYTES
                            && items.get(0).majorType() != MAJOR_ARRAY;
                    yield wrapped ? StrictCbor.span(RawItems.bytes(items.get(1))) : root;
                }
                default -> throw new HeaderValidationFailure("shape", "unsupported Shelley header CBOR root");
            });
        } catch (TxDecodingException | CborRuntimeException e) {
            throw new HeaderValidationFailure("shape", e.getMessage());
        }
    }

    /**
     * What the KES signature signs: Haskell's own encoding of the body ({@code getSignableRepresentation}, a
     * {@code serialize'}, Praos/BlockHeader.hs:114-118), not the bytes received, so this is the one re-encoding here.
     */
    private static byte[] signedBody(RawHeader header) {
        return CborSerializationUtil.serialize(CborSerializationUtil.deserializeOne(header.bodySpan().bytes()));
    }

    private static void requireEquals(String field, long actual, long expected) {
        if (actual != expected) {
            throw new HeaderValidationFailure(field, field + " mismatch");
        }
    }

    private static void requireHexEquals(String field, byte[] actual, String expectedHex) {
        if (expectedHex == null || expectedHex.isBlank()) {
            if (actual.length == 0) {
                return;
            }
            throw new HeaderValidationFailure(field, field + " expected value is missing");
        }
        byte[] expected = HexUtil.decodeHexString(expectedHex);
        if (!Arrays.equals(actual, expected)) {
            throw new HeaderValidationFailure(field, field + " mismatch");
        }
    }
}
