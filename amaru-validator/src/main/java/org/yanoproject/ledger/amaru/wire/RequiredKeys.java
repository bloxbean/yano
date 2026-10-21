package org.yanoproject.ledger.amaru.wire;

import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.CredentialType;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.PoolId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A {@code required_keys} response (INTERFACE.md): what Amaru's {@code prepare_transaction} needs the host
 * to resolve.
 *
 * @param inputs        spent, reference and collateral inputs
 * @param accounts      stake credentials
 * @param pools         pool ids
 * @param dreps         DRep credentials (certificates, voters, delegation targets)
 * @param committeeCold committee members named by cold credential (informational)
 * @param committeeHot  committee members named by hot credential (informational)
 * @param proposals     proposals voted on or chained to (informational)
 */
public record RequiredKeys(List<Outpoint> inputs, List<CredentialKey> accounts, List<PoolId> pools,
                           List<CredentialKey> dreps, List<CredentialKey> committeeCold,
                           List<CredentialKey> committeeHot, List<GovActionId> proposals) {

    /** The decoded response: the keys, or the module's error message. */
    public sealed interface Result permits Keys, Error {
    }

    public record Keys(RequiredKeys keys) implements Result {
    }

    /** The module could not process the call (malformed env, or the transaction does not decode). */
    public record Error(String message) implements Result {
    }

    /**
     * @throws CborReader.CborException or {@link IllegalArgumentException} when the document is not a
     *                                  valid response
     */
    public static Result decode(byte[] document) {
        Map<?, ?> map = WireValues.map(CborReader.decode(document), "required_keys response");
        long status = WireValues.uint(map.get(0L), "status");
        if (status == 2) {
            return new Error(WireValues.text(map.get(8L), "message"));
        }
        if (status != 0) {
            throw new IllegalArgumentException("unknown required_keys status " + status);
        }
        for (Object key : map.keySet()) {
            if (!(key instanceof Long k) || k < 0 || k > 7) {
                throw new IllegalArgumentException("unknown required_keys key " + key);
            }
        }
        return new Keys(new RequiredKeys(
                list(map.get(1L), "inputs", RequiredKeys::input),
                list(map.get(2L), "accounts", RequiredKeys::credential),
                list(map.get(3L), "pools", v -> new PoolId(HexUtil.encodeHexString(WireValues.bytes(v, "pool", 28)))),
                list(map.get(4L), "dreps", RequiredKeys::credential),
                list(map.get(5L), "committee_cold", RequiredKeys::credential),
                list(map.get(6L), "committee_hot", RequiredKeys::credential),
                list(map.get(7L), "proposals", RequiredKeys::govActionId)));
    }

    private static <T> List<T> list(Object value, String field, Function<Object, T> item) {
        if (value == null) {
            return List.of();
        }
        List<T> result = new ArrayList<>();
        for (Object element : WireValues.list(value, field)) {
            result.add(item.apply(element));
        }
        return List.copyOf(result);
    }

    static Outpoint input(Object value) {
        List<?> pair = WireValues.list(value, "tx_in", 2);
        return Outpoints.of(HexUtil.encodeHexString(WireValues.bytes(pair.get(0), "transaction_id", 32)),
                Math.toIntExact(WireValues.uint(pair.get(1), "index")));
    }

    static CredentialKey credential(Object value) {
        List<?> pair = WireValues.list(value, "credential", 2);
        return new CredentialKey(CredentialType.fromTag((int) WireValues.uint(pair.get(0), "credential tag")),
                HexUtil.encodeHexString(WireValues.bytes(pair.get(1), "credential hash", 28)));
    }

    static GovActionId govActionId(Object value) {
        List<?> pair = WireValues.list(value, "gov_action_id", 2);
        return new GovActionId(HexUtil.encodeHexString(WireValues.bytes(pair.get(0), "transaction_id", 32)),
                Math.toIntExact(WireValues.uint(pair.get(1), "index")));
    }
}
