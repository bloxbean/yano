package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Canonical claim emitted only for a Cardano staging output that has already
 * been accepted into the configured bridge vault.
 */
public record EutxoDepositClaim(
        int abiVersion,
        String chainId,
        EutxoOutpoint acceptedOutpoint,
        long l1Slot,
        byte[] l1BlockHash,
        String vaultAddress,
        String vaultScriptHash,
        byte[] acceptedOutputCbor,
        String l2Address,
        byte[] mirroredOutputCbor,
        byte[] depositNonce,
        EutxoOutpoint stagingOutpoint,
        long refundDeadline,
        byte[] depositorKeyHash,
        EutxoL2KeyBinding l2KeyBinding
) {
    public static final int ABI_VERSION = 2;

    public EutxoDepositClaim(
            int abiVersion,
            String chainId,
            EutxoOutpoint acceptedOutpoint,
            long l1Slot,
            byte[] l1BlockHash,
            String vaultAddress,
            String vaultScriptHash,
            byte[] acceptedOutputCbor,
            String l2Address,
            byte[] mirroredOutputCbor,
            byte[] depositNonce,
            EutxoOutpoint stagingOutpoint,
            long refundDeadline
    ) {
        this(abiVersion, chainId, acceptedOutpoint, l1Slot, l1BlockHash,
                vaultAddress, vaultScriptHash, acceptedOutputCbor, l2Address,
                mirroredOutputCbor, depositNonce, stagingOutpoint,
                refundDeadline, new byte[28],
                EutxoL2KeyBinding.none());
    }

    public EutxoDepositClaim {
        if (abiVersion != ABI_VERSION) {
            throw new IllegalArgumentException("unsupported EUTxO deposit ABI");
        }
        chainId = boundedText(chainId, "chainId", 128);
        Objects.requireNonNull(acceptedOutpoint, "acceptedOutpoint");
        if (l1Slot < 0) {
            throw new IllegalArgumentException("L1 slot cannot be negative");
        }
        l1BlockHash = fixedBytes(l1BlockHash, "l1BlockHash", 32);
        vaultAddress = boundedText(vaultAddress, "vaultAddress", 256);
        vaultScriptHash = canonicalHex(vaultScriptHash, "vaultScriptHash", 28);
        acceptedOutputCbor = boundedBytes(
                acceptedOutputCbor, "acceptedOutputCbor", EutxoProfile.V1.maxOutputCborBytes());
        l2Address = boundedText(l2Address, "l2Address", 256);
        mirroredOutputCbor = boundedBytes(
                mirroredOutputCbor, "mirroredOutputCbor", EutxoProfile.V1.maxOutputCborBytes());
        depositNonce = fixedBytes(depositNonce, "depositNonce", 32);
        depositorKeyHash = fixedBytes(
                depositorKeyHash, "depositorKeyHash", 28);
        l2KeyBinding = Objects.requireNonNull(
                l2KeyBinding, "l2KeyBinding");
        Objects.requireNonNull(stagingOutpoint, "stagingOutpoint");
        if (refundDeadline < 0) {
            throw new IllegalArgumentException("refund deadline cannot be negative");
        }
    }

    @Override
    public byte[] l1BlockHash() {
        return l1BlockHash.clone();
    }

    @Override
    public byte[] acceptedOutputCbor() {
        return acceptedOutputCbor.clone();
    }

    @Override
    public byte[] mirroredOutputCbor() {
        return mirroredOutputCbor.clone();
    }

    @Override
    public byte[] depositNonce() {
        return depositNonce.clone();
    }

    @Override
    public byte[] depositorKeyHash() {
        return depositorKeyHash.clone();
    }

    public byte[] encode() {
        return EutxoCbor.encodeDepositClaim(this);
    }

    public static EutxoDepositClaim decode(byte[] bytes) {
        return EutxoCbor.decodeDepositClaim(bytes);
    }

    /**
     * The L2 transaction identity is derived only from the chain, ABI, and
     * exact accepted L1 outpoint. Its single output is index zero.
     */
    public EutxoOutpoint mirroredOutpoint() {
        byte[] identity = (chainId + '\n' + abiVersion + '\n' + acceptedOutpoint)
                .getBytes(StandardCharsets.UTF_8);
        return new EutxoOutpoint(
                HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(identity)), 0);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoDepositClaim claim
                && abiVersion == claim.abiVersion
                && chainId.equals(claim.chainId)
                && acceptedOutpoint.equals(claim.acceptedOutpoint)
                && l1Slot == claim.l1Slot
                && java.util.Arrays.equals(l1BlockHash, claim.l1BlockHash)
                && vaultAddress.equals(claim.vaultAddress)
                && vaultScriptHash.equals(claim.vaultScriptHash)
                && java.util.Arrays.equals(acceptedOutputCbor, claim.acceptedOutputCbor)
                && l2Address.equals(claim.l2Address)
                && java.util.Arrays.equals(mirroredOutputCbor, claim.mirroredOutputCbor)
                && java.util.Arrays.equals(depositNonce, claim.depositNonce)
                && stagingOutpoint.equals(claim.stagingOutpoint)
                && refundDeadline == claim.refundDeadline
                && java.util.Arrays.equals(
                depositorKeyHash, claim.depositorKeyHash)
                && l2KeyBinding.equals(claim.l2KeyBinding);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                abiVersion, chainId, acceptedOutpoint, l1Slot, vaultAddress,
                vaultScriptHash, l2Address, stagingOutpoint, refundDeadline,
                l2KeyBinding);
        result = 31 * result + java.util.Arrays.hashCode(l1BlockHash);
        result = 31 * result + java.util.Arrays.hashCode(acceptedOutputCbor);
        result = 31 * result + java.util.Arrays.hashCode(mirroredOutputCbor);
        result = 31 * result + java.util.Arrays.hashCode(depositNonce);
        return 31 * result + java.util.Arrays.hashCode(depositorKeyHash);
    }

    private static String boundedText(String value, String field, int maxLength) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maxLength + " characters");
        }
        return normalized;
    }

    private static String canonicalHex(String value, String field, int bytes) {
        String normalized = boundedText(value, field, bytes * 2);
        if (normalized.length() != bytes * 2
                || !normalized.equals(normalized.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(field + " must be canonical lowercase hex");
        }
        try {
            HexFormat.of().parseHex(normalized);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(field + " must be canonical lowercase hex", failure);
        }
        return normalized;
    }

    private static byte[] fixedBytes(byte[] value, String field, int length) {
        byte[] copy = Objects.requireNonNull(value, field).clone();
        if (copy.length != length) {
            throw new IllegalArgumentException(field + " must contain " + length + " bytes");
        }
        return copy;
    }

    private static byte[] boundedBytes(byte[] value, String field, int maxLength) {
        byte[] copy = Objects.requireNonNull(value, field).clone();
        if (copy.length == 0 || copy.length > maxLength) {
            throw new IllegalArgumentException(field + " exceeds its encoded bound");
        }
        return copy;
    }

}
