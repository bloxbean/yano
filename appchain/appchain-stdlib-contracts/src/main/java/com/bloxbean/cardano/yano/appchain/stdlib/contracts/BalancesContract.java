package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.StdlibContractCbor;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Version 1 command, state-key, and balance contract for {@code balances}. */
public final class BalancesContract {
    public static final String STATE_MACHINE_ID = "balances";
    public static final String DEFAULT_TOPIC = "balances.command.v1";
    public static final int OP_MINT = 0;
    public static final int OP_TRANSFER = 1;

    private BalancesContract() {
    }

    public static byte[] mint(String account, BigInteger amount) {
        return command(OP_MINT, account, amount);
    }

    public static byte[] transfer(String account, BigInteger amount) {
        return command(OP_TRANSFER, account, amount);
    }

    public static Command decodeCommand(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                StdlibContractCbor.decodeArray(bytes, 3).getDataItems();
        int op = StdlibContractCbor.uintInt(values.get(0));
        String account = StdlibContractCbor.text(values.get(1));
        BigInteger amount = ((UnsignedInteger) values.get(2)).getValue();
        validate(op, account, amount);
        return new Command(op, account, amount);
    }

    public static byte[] accountKey(String account) {
        StdlibContractCbor.requireText(account, "account");
        byte[] key = ("b/" + account).getBytes(StandardCharsets.UTF_8);
        StdlibContractCbor.requireStateKey(key, "balance state key");
        return key;
    }

    public static BigInteger decodeBalance(byte[] entry) {
        if (entry == null || entry.length == 0) return BigInteger.ZERO;
        if (entry.length > StdlibContractCbor.MAX_WIRE_BYTES) throw StdlibContractCbor.malformed();
        return new BigInteger(1, entry);
    }

    private static byte[] command(int op, String account, BigInteger amount) {
        validate(op, account, amount);
        Array array = new Array();
        array.add(new UnsignedInteger(op));
        array.add(new UnicodeString(account));
        array.add(new UnsignedInteger(amount));
        return StdlibContractCbor.encode(array);
    }

    private static void validate(int op, String account, BigInteger amount) {
        if (op != OP_MINT && op != OP_TRANSFER) throw StdlibContractCbor.malformed();
        accountKey(account);
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    public record Command(int operation, String account, BigInteger amount) {
        public boolean mint() { return operation == OP_MINT; }
    }
}
