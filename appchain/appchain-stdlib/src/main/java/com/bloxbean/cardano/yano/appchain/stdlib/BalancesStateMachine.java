package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.BalancesContract;

import java.math.BigInteger;

/**
 * Standard-library state machine {@code balances} (ADR app-layer/006 E2.3):
 * account balances over a shared ledger with per-sender authorization and
 * non-negativity enforced deterministically. Every account balance is a
 * provable state key.
 * <p>
 * Commands (CBOR body):
 * <pre>
 *   [0, to(tstr), amount(uint)]           MINT     — only the configured minter member may mint
 *   [1, to(tstr), amount(uint)]           TRANSFER — moves from the SENDER's account to `to`
 * </pre>
 * Rules (all deterministic):
 * <ul>
 *   <li>Accounts are arbitrary application strings (the sender's own account is
 *       keyed by its member public key hex, so a member can only spend its own
 *       balance).</li>
 *   <li>MINT credits {@code to}; if {@code minter} is configured only that
 *       member's mints apply (others are no-ops).</li>
 *   <li>TRANSFER debits the sender's account (key = sender pubkey hex) and
 *       credits {@code to}; rejected as a no-op if the sender has insufficient
 *       balance — balances never go negative.</li>
 * </ul>
 * State entry (CBOR): {@code "b/" + account → amount(uint big-endian)}.
 * <p>
 * Use cases: netting, loyalty points, internal credits, x402 receipt balances.
 */
public final class BalancesStateMachine implements AppStateMachine {

    public static final String ID = "balances";
    public static final int OP_MINT = 0;
    public static final int OP_TRANSFER = 1;

    /** Optional minter public key hex; empty = any member may mint. */
    private final String minterHex;

    public BalancesStateMachine() {
        this("");
    }

    public BalancesStateMachine(String minterHex) {
        String normalized = minterHex != null ? minterHex.trim().toLowerCase() : "";
        if (!normalized.isEmpty() && !normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "machines.balances.minter must be a 32-byte hex Ed25519 member public key: " + minterHex);
        }
        this.minterHex = normalized;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        try {
            Command.decode(message.getBody());
            return AdmissionResult.accept();
        } catch (Exception e) {
            return AdmissionResult.reject("Malformed balances command: " + e.getMessage());
        }
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer) {
        for (AppMessage message : block.messages()) {
            Command command;
            try {
                command = Command.decode(message.getBody());
            } catch (Exception e) {
                continue;
            }
            String senderAccount = HexUtil.encodeHexString(message.getSender());

            if (command.op() == OP_MINT) {
                if (!minterHex.isEmpty() && !minterHex.equals(senderAccount)) {
                    continue; // not the authorized minter — deterministic no-op
                }
                credit(writer, command.to(), command.amount());
            } else if (command.op() == OP_TRANSFER) {
                BigInteger from = balance(writer, senderAccount);
                if (from.compareTo(command.amount()) < 0) {
                    continue; // insufficient funds — no-op, never negative
                }
                setBalance(writer, senderAccount, from.subtract(command.amount()));
                credit(writer, command.to(), command.amount());
            }
        }
    }

    // ------------------------------------------------------------------
    // Client/helper encoding + queries
    // ------------------------------------------------------------------

    public static byte[] mint(String toAccount, BigInteger amount) {
        return BalancesContract.mint(toAccount, amount);
    }

    public static byte[] transfer(String toAccount, BigInteger amount) {
        return BalancesContract.transfer(toAccount, amount);
    }

    public static byte[] accountKey(String account) {
        return BalancesContract.accountKey(account);
    }

    public static BigInteger decodeBalance(byte[] entry) {
        return BalancesContract.decodeBalance(entry);
    }

    // ------------------------------------------------------------------

    private static BigInteger balance(AppStateWriter writer, String account) {
        return writer.get(accountKey(account)).map(b -> new BigInteger(1, b)).orElse(BigInteger.ZERO);
    }

    private static void credit(AppStateWriter writer, String account, BigInteger amount) {
        setBalance(writer, account, balance(writer, account).add(amount));
    }

    private static void setBalance(AppStateWriter writer, String account, BigInteger value) {
        byte[] key = accountKey(account);
        if (value.signum() == 0) {
            writer.delete(key);
        } else {
            writer.put(key, value.toByteArray());
        }
    }

    record Command(int op, String to, BigInteger amount) {
        static Command decode(byte[] body) {
            BalancesContract.Command decoded = BalancesContract.decodeCommand(body);
            return new Command(decoded.operation(), decoded.account(), decoded.amount());
        }
    }
}
