package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.util.HexUtil;

/**
 * A proposal procedure {@code [deposit, reward_account, gov_action, anchor]} as far as the witness rules read it:
 * its position and the guardrails policy hash of a parameter change ({@code [0, prev, update, policy]}) or
 * treasury withdrawal ({@code [2, withdrawals, policy]}), which Conway's {@code proposingScriptsNeeded}
 * (Conway/UTxO.hs:96-106) needs.
 *
 * @param index       the position in the proposal list
 * @param actionTag   the governance action's CDDL tag 0–6
 * @param policyHash  the guardrails script hash, or null when absent or the action has none
 */
public record RawProposal(int index, int actionTag, byte[] policyHash) {

    public RawProposal {
        policyHash = policyHash != null ? policyHash.clone() : null;
    }

    @Override
    public byte[] policyHash() {
        return policyHash != null ? policyHash.clone() : null;
    }

    /** Reads a proposal procedure at the reader's position. */
    static RawProposal read(CborReader reader, int index) {
        long length = reader.readArrayHeader();
        if (length != 4 && length != CborReader.INDEFINITE) {
            throw new TxDecodingException("a proposal procedure has 4 elements");
        }
        reader.skip(); // deposit
        reader.skip(); // reward account
        long actionLength = reader.readArrayHeader();
        long tag = reader.readUnsignedLong();
        int expected = switch ((int) Math.min(tag, 7)) {
            case 0 -> 4;
            case 1, 2 -> 3;
            case 3 -> 2;
            case 4 -> 5;
            case 5 -> 3;
            case 6 -> 1;
            default -> throw new TxDecodingException("unknown governance action tag " + tag);
        };
        byte[] policy = null;
        for (int i = 1; i < expected; i++) {
            boolean policyField = (tag == 0 && i == 3) || (tag == 2 && i == 2);
            if (tag == 5 && i == 2) {
                constitution(reader);
            } else if (policyField && !reader.peekNull()) {
                policy = reader.readBytes();
                if (policy.length != RawCredential.HASH_LENGTH) {
                    throw new TxDecodingException("a guardrails policy hash is 28 bytes");
                }
            } else {
                reader.skip();
            }
        }
        if (actionLength != CborReader.INDEFINITE && actionLength != expected) {
            throw new TxDecodingException("governance action tag " + tag + " has " + actionLength + " elements");
        }
        if (actionLength == CborReader.INDEFINITE && reader.hasNext(actionLength, expected)) {
            throw new TxDecodingException("governance action tag " + tag + " has extra elements");
        }
        BoundedFields.anchor(reader, "proposal");
        if (length == CborReader.INDEFINITE && reader.hasNext(length, 4)) {
            throw new TxDecodingException("a proposal procedure has 4 elements");
        }
        return new RawProposal(index, (int) tag, policy);
    }

    /** {@code constitution = [anchor, script_hash / null]}: the anchor with its bounds, the rest skipped. */
    private static void constitution(CborReader reader) {
        long length = reader.readArrayHeader();
        if (length != 2 && length != CborReader.INDEFINITE) {
            throw new TxDecodingException("a constitution has 2 elements");
        }
        BoundedFields.anchor(reader, "constitution");
        reader.skip();
        if (length == CborReader.INDEFINITE && reader.hasNext(length, 2)) {
            throw new TxDecodingException("a constitution has 2 elements");
        }
    }

    @Override
    public String toString() {
        return "proposal " + index + (policyHash != null ? " (policy " + HexUtil.encodeHexString(policyHash) + ")"
                : "");
    }
}
