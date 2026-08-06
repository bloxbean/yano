package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable consensus claim produced when reserve-backed L2 value is burned. */
public record EutxoWithdrawalClaim(
        int abiVersion,
        String chainId,
        long bridgeEpoch,
        EutxoOutpoint withdrawalOutpoint,
        String destinationAddress,
        BigInteger lovelace,
        byte[] nonce,
        long settlementSequence,
        long requestedHeight,
        BigInteger bounty
) {
    public static final int ABI_VERSION = 1;
    /** ADR-UTXO-009: {payout, bounty} claims with a committed executor bounty. */
    public static final int ABI_VERSION_V2 = 2;

    /** Legacy v1 claim (zero bounty); byte- and id-compatible with pre-009. */
    public EutxoWithdrawalClaim(
            int abiVersion,
            String chainId,
            long bridgeEpoch,
            EutxoOutpoint withdrawalOutpoint,
            String destinationAddress,
            BigInteger lovelace,
            byte[] nonce,
            long settlementSequence,
            long requestedHeight
    ) {
        this(abiVersion, chainId, bridgeEpoch, withdrawalOutpoint,
                destinationAddress, lovelace, nonce, settlementSequence,
                requestedHeight, BigInteger.ZERO);
    }

    public EutxoWithdrawalClaim {
        if (abiVersion != ABI_VERSION && abiVersion != ABI_VERSION_V2) {
            throw new IllegalArgumentException("unsupported EUTxO withdrawal claim ABI");
        }
        bounty = Objects.requireNonNull(bounty, "bounty");
        if (abiVersion == ABI_VERSION && bounty.signum() != 0) {
            throw new IllegalArgumentException(
                    "v1 withdrawal claims cannot carry a bounty");
        }
        if (bounty.signum() < 0
                || bounty.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(
                    "withdrawal bounty must fit a non-negative signed 64-bit integer");
        }
        chainId = text(chainId, "chainId", 128);
        if (bridgeEpoch < 0 || settlementSequence < 0 || requestedHeight < 0) {
            throw new IllegalArgumentException(
                    "bridge epoch, settlement sequence, and requested height cannot be negative");
        }
        Objects.requireNonNull(withdrawalOutpoint, "withdrawalOutpoint");
        destinationAddress = text(destinationAddress, "destinationAddress", 256);
        lovelace = Objects.requireNonNull(lovelace, "lovelace");
        if (lovelace.signum() <= 0
                || lovelace.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(
                    "withdrawal lovelace must fit a positive signed 64-bit integer");
        }
        nonce = Objects.requireNonNull(nonce, "nonce").clone();
        if (nonce.length != 32) {
            throw new IllegalArgumentException("withdrawal nonce must contain 32 bytes");
        }
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    public String claimId() {
        String identity = abiVersion + "\n" + chainId + "\n" + bridgeEpoch + "\n"
                + withdrawalOutpoint + "\n" + destinationAddress + "\n" + lovelace
                + "\n" + HexFormat.of().formatHex(nonce) + "\n" + settlementSequence;
        if (abiVersion >= ABI_VERSION_V2) {
            // Appended (never inserted): v1 identities stay byte-identical,
            // and the leading abiVersion makes cross-version collisions
            // impossible.
            identity += "\n" + bounty;
        }
        return HexFormat.of().formatHex(
                Blake2bUtil.blake2bHash256(identity.getBytes(StandardCharsets.UTF_8)));
    }

    /** Total reserve outflow this claim commits: payout plus bounty. */
    public BigInteger totalLovelace() {
        return lovelace.add(bounty);
    }

    public byte[] encode() {
        return EutxoCbor.encodeWithdrawalClaim(this);
    }

    public static EutxoWithdrawalClaim decode(byte[] bytes) {
        return EutxoCbor.decodeWithdrawalClaim(bytes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoWithdrawalClaim claim
                && abiVersion == claim.abiVersion
                && chainId.equals(claim.chainId)
                && bridgeEpoch == claim.bridgeEpoch
                && withdrawalOutpoint.equals(claim.withdrawalOutpoint)
                && destinationAddress.equals(claim.destinationAddress)
                && lovelace.equals(claim.lovelace)
                && java.util.Arrays.equals(nonce, claim.nonce)
                && settlementSequence == claim.settlementSequence
                && requestedHeight == claim.requestedHeight
                && bounty.equals(claim.bounty);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                abiVersion, chainId, bridgeEpoch, withdrawalOutpoint,
                destinationAddress, lovelace, settlementSequence, requestedHeight,
                bounty);
        return 31 * result + java.util.Arrays.hashCode(nonce);
    }

    private static String text(String value, String field, int maximum) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maximum + " characters");
        }
        return normalized;
    }
}
