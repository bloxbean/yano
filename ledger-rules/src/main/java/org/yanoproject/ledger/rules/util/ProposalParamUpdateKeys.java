package org.yanoproject.ledger.rules.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads which protocol parameters a {@code ParameterChange} proposal changes, straight from the original
 * CBOR: the key set of its {@code protocol_param_update} map (Conway CDDL numbering, 0–33).
 *
 * <p>CCL's {@code ProtocolParamUpdate} has no fields for the Conway keys 25–33 (voting thresholds,
 * committee, governance deposits and lifetimes, {@code minFeeRefScriptCostPerByte}), so a decoded action
 * cannot tell, for example, whether a proposal touches Haskell's security group. See
 * {@link org.yanoproject.ledger.rules.view.model.ProposalState#paramUpdateKeys()}.</p>
 *
 * <pre>
 * transaction          = [body, witnesses, is_valid, auxiliary_data]
 * body                 = { … 20: proposal_procedures … }
 * proposal_procedures  = nonempty_oset&lt;proposal_procedure&gt;       ; optionally tagged 258
 * proposal_procedure   = [deposit, reward_account, gov_action, anchor]
 * gov_action           = [0, gov_action_id / null, protocol_param_update, policy_hash / null] / …
 * </pre>
 */
public final class ProposalParamUpdateKeys {

    private static final int PROPOSAL_PROCEDURES = 20;
    private static final int SET_TAG = 258;

    private ProposalParamUpdateKeys() {
    }

    /**
     * @param txCbor a transaction, {@code [body, witnesses, is_valid, auxiliary_data]}
     * @return one entry per proposal procedure, in body order: the parameter-update keys of a
     *         {@code ParameterChange}, or {@code null} for other actions; empty when the body has no
     *         proposals
     * @throws IllegalArgumentException when the bytes are not well-formed
     */
    public static List<Set<Integer>> fromTransaction(byte[] txCbor) {
        Objects.requireNonNull(txCbor, "txCbor");
        Reader r = new Reader(txCbor, 0);
        long txItems = r.containerHead(4);
        if (txItems == 0) {
            throw new IllegalArgumentException("empty transaction array");
        }
        long entries = r.containerHead(5);
        for (long i = 0; entries < 0 ? !r.atBreak() : i < entries; i++) {
            long key = r.uint();
            if (key == PROPOSAL_PROCEDURES) {
                return procedures(r);
            }
            r.skip();
        }
        return List.of();
    }

    /**
     * @param govActionCbor one {@code gov_action}
     * @return the parameter-update keys when it is a {@code ParameterChange}, otherwise {@code null}
     * @throws IllegalArgumentException when the bytes are not well-formed
     */
    public static Set<Integer> fromGovAction(byte[] govActionCbor) {
        Objects.requireNonNull(govActionCbor, "govActionCbor");
        return govAction(new Reader(govActionCbor, 0));
    }

    private static List<Set<Integer>> procedures(Reader r) {
        r.optionalTag(SET_TAG);
        long count = r.containerHead(4);
        List<Set<Integer>> result = new ArrayList<>();
        for (long i = 0; count < 0 ? !r.atBreak() : i < count; i++) {
            long fields = r.containerHead(4);
            if (fields != 4) {
                throw new IllegalArgumentException("proposal_procedure must have 4 fields");
            }
            r.skip(); // deposit
            r.skip(); // reward account
            result.add(govAction(r));
            r.skip(); // anchor
        }
        return Collections.unmodifiableList(result);
    }

    private static Set<Integer> govAction(Reader r) {
        int start = r.offset;
        long fields = r.containerHead(4);
        long tag = r.uint();
        if (tag != 0) {
            r.offset = CborItems.skip(r.data, start);
            return null;
        }
        if (fields != 4) {
            throw new IllegalArgumentException("parameter_change_action must have 4 fields");
        }
        r.skip(); // gov_action_id / null
        Set<Integer> keys = new TreeSet<>();
        long entries = r.containerHead(5);
        for (long i = 0; entries < 0 ? !r.atBreak() : i < entries; i++) {
            long key = r.uint();
            if (key > Integer.MAX_VALUE || !keys.add((int) key)) {
                throw new IllegalArgumentException("bad or duplicate protocol_param_update key " + key);
            }
            r.skip();
        }
        r.skip(); // policy hash / null
        return Collections.unmodifiableSet(keys);
    }

    /** A cursor over CBOR heads; containers are entered, other items skipped with {@link CborItems}. */
    private static final class Reader {
        private final byte[] data;
        private int offset;

        Reader(byte[] data, int offset) {
            this.data = data;
            this.offset = offset;
        }

        boolean atBreak() {
            if (offset >= data.length) {
                throw new IllegalArgumentException("truncated");
            }
            if ((data[offset] & 0xff) == 0xff) {
                offset++;
                return true;
            }
            return false;
        }

        void skip() {
            offset = CborItems.skip(data, offset);
        }

        void optionalTag(long tag) {
            if (offset < data.length && (data[offset] & 0xff) >>> 5 == 6) {
                long actual = argument();
                if (actual != tag) {
                    throw new IllegalArgumentException("unexpected tag " + actual);
                }
            }
        }

        /** @return the length of an array (4) or map (5) head, or -1 when indefinite */
        long containerHead(int major) {
            if (offset >= data.length || (data[offset] & 0xff) >>> 5 != major) {
                throw new IllegalArgumentException("expected CBOR major type " + major + " at " + offset);
            }
            if ((data[offset] & 0x1f) == 31) {
                offset++;
                return -1;
            }
            return argument();
        }

        long uint() {
            if (offset >= data.length || (data[offset] & 0xff) >>> 5 != 0) {
                throw new IllegalArgumentException("expected an unsigned integer at " + offset);
            }
            return argument();
        }

        private long argument() {
            int info = data[offset++] & 0x1f;
            if (info < 24) {
                return info;
            }
            int bytes = switch (info) {
                case 24 -> 1;
                case 25 -> 2;
                case 26 -> 4;
                case 27 -> 8;
                default -> throw new IllegalArgumentException("unsupported additional information " + info);
            };
            if (offset + bytes > data.length) {
                throw new IllegalArgumentException("truncated");
            }
            long value = 0;
            for (int i = 0; i < bytes; i++) {
                value = (value << 8) | (data[offset++] & 0xff);
            }
            return value;
        }
    }
}
