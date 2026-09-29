package org.yanoproject.ledger.rules.conway.tx;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One Conway certificate as far as the witness rules read it: its position, its CDDL tag, the credential that
 * must authorise it, and for pool certificates the pool id and owners.
 *
 * <p>Tags ({@code ConwayTxCert} decoder): 0 stake registration (no deposit), 1 stake deregistration, 2 stake
 * delegation, 3 pool registration, 4 pool retirement, 7 registration with deposit, 8 unregistration with refund,
 * 9 vote delegation, 10 stake and vote delegation, 11–13 registration with delegation, 14 committee hot-key
 * authorisation, 15 committee cold-key resignation, 16 DRep registration, 17 DRep unregistration, 18 DRep update.
 * The genesis-delegation and MIR tags (5, 6) do not exist in Conway.</p>
 *
 * @param index      the position in the certificate list
 * @param tag        the certificate's CDDL tag
 * @param credential the certificate's credential (element 1) for tags 0–2 and 7–18; null for pool certificates
 * @param poolId     the pool's key hash (operator) for tags 3 and 4, else null
 * @param poolOwners the pool owners' key hashes for tag 3, else empty
 */
public record RawCertificate(int index, int tag, RawCredential credential, byte[] poolId, List<byte[]> poolOwners) {

    public RawCertificate {
        poolId = poolId != null ? poolId.clone() : null;
        poolOwners = poolOwners.stream().map(byte[]::clone).toList();
    }

    @Override
    public byte[] poolId() {
        return poolId != null ? poolId.clone() : null;
    }

    @Override
    public List<byte[]> poolOwners() {
        return poolOwners.stream().map(byte[]::clone).toList();
    }

    /**
     * {@code getScriptWitnessConwayTxCert} (Conway/TxCert.hs:763-784): the script hash that must authorise the
     * certificate. A registration without a deposit (tag 0) needs none during Conway; pool certificates are
     * authorised by keys only.
     *
     * @return the credential's script hash, or null
     */
    public byte[] scriptWitness() {
        if (tag == 0 || credential == null || !credential.script()) {
            return null;
        }
        return credential.hash();
    }

    /**
     * {@code getVKeyWitnessConwayTxCert} (Conway/TxCert.hs:786-804): the key hash that must sign — the credential's
     * key hash, or the pool id for pool certificates; none for a registration without a deposit (tag 0).
     *
     * @return the key hash, or null
     */
    public byte[] vkeyWitness() {
        if (tag == 3 || tag == 4) {
            return poolId();
        }
        if (tag == 0 || credential == null || credential.script()) {
            return null;
        }
        return credential.hash();
    }

    /** Reads one certificate at the reader's position. */
    static RawCertificate read(CborReader reader, int index) {
        long length = reader.readArrayHeader();
        long tag = reader.readUnsignedLong();
        int expected;
        RawCredential credential = null;
        byte[] poolId = null;
        List<byte[]> owners = new ArrayList<>();
        int read = 2;
        switch ((int) Math.min(tag, 19)) {
            case 0, 1 -> {
                credential = RawCredential.read(reader);
                expected = 2;
            }
            case 2, 7, 8, 9, 14, 17, 18 -> {
                credential = RawCredential.read(reader);
                expected = 3;
            }
            case 10, 11, 12, 15, 16 -> {
                credential = RawCredential.read(reader);
                expected = tag == 15 ? 3 : 4;
            }
            case 13 -> {
                credential = RawCredential.read(reader);
                expected = 5;
            }
            case 3 -> {
                poolId = keyHash(reader.readBytes(), "pool operator");
                // vrf, pledge, cost, margin, reward account
                for (int i = 0; i < 5; i++) {
                    reader.skip();
                }
                reader.skipTag(258);
                long count = reader.readArrayHeader();
                for (long i = 0; reader.hasNext(count, i); i++) {
                    owners.add(keyHash(reader.readBytes(), "pool owner"));
                }
                read = 8;
                expected = 10;
            }
            case 4 -> {
                poolId = keyHash(reader.readBytes(), "pool id");
                expected = 3;
            }
            default -> throw new TxDecodingException("certificate tag " + tag + " is not a Conway certificate");
        }
        // Skip the fields the witness rules do not read.
        for (int i = tag == 3 ? read : 2; i < expected; i++) {
            if (length == CborReader.INDEFINITE && !reader.hasNext(length, i)) {
                throw new TxDecodingException("certificate tag " + tag + " has too few elements");
            }
            reader.skip();
        }
        if (length != CborReader.INDEFINITE && length != expected) {
            throw new TxDecodingException("certificate tag " + tag + " has " + length + " elements, expected "
                    + expected);
        }
        if (length == CborReader.INDEFINITE && reader.hasNext(length, expected)) {
            throw new TxDecodingException("certificate tag " + tag + " has extra elements");
        }
        return new RawCertificate(index, (int) tag, credential, poolId, owners);
    }

    private static byte[] keyHash(byte[] value, String what) {
        if (value.length != RawCredential.HASH_LENGTH) {
            throw new TxDecodingException(what + " key hash of " + value.length + " bytes");
        }
        return value;
    }

    @Override
    public String toString() {
        return "certificate " + index + " (tag " + tag + (credential != null ? ", " + credential : "") + ")";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RawCertificate other && index == other.index && tag == other.tag
                && Objects.equals(credential, other.credential);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, tag, credential);
    }
}
