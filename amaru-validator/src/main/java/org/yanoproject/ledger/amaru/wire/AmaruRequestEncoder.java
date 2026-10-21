package org.yanoproject.ledger.amaru.wire;

import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.amaru.AmaruLedgerConstants;
import org.yanoproject.ledger.amaru.AmaruNetworkParameters;
import org.yanoproject.ledger.amaru.AmaruNetworkParameters.EraBound;
import org.yanoproject.ledger.amaru.AmaruNetworkParameters.EraSummary;
import org.yanoproject.ledger.amaru.AmaruNetworkParameters.GlobalParameters;
import org.yanoproject.ledger.amaru.wire.AmaruRequest.CertPointer;
import org.yanoproject.ledger.amaru.wire.AmaruRequest.CommitteeMember;
import org.yanoproject.ledger.amaru.wire.AmaruRequest.ProposalKind;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepTarget;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;

/**
 * Writes v1 request documents exactly as the Rust reference encoder does
 * ({@code amaru-validator-wasm/src/interface.rs}): a definite map with keys in ascending order, key 7
 * only when test constants are set, key 9 only when a guardrail script exists, and shortest-form heads
 * throughout. The golden tests compare the output with the reference encoder's bytes.
 */
public final class AmaruRequestEncoder {

    /** The interface version this host implements (INTERFACE.md, invariant 5). */
    public static final int ABI_VERSION = 1;

    private AmaruRequestEncoder() {
    }

    public static byte[] encode(AmaruRequest request) {
        CborWriter w = new CborWriter(request.transaction().length + 4096);
        boolean constants = !request.ledgerConstants().isHaskell();
        boolean guardrail = request.guardrailScriptHash() != null;
        w.map(17 + (constants ? 1 : 0) + (guardrail ? 1 : 0));

        w.uint(0).uint(ABI_VERSION);
        w.uint(1).uint(request.mode().wire());
        w.uint(2).bytes(request.transaction());
        w.uint(3).uint(request.network().networkMagic());
        w.uint(4);
        eraHistory(w, request.network());
        w.uint(5);
        globalParameters(w, request.network().global());
        w.uint(6).bytes(request.protocolParameters());
        if (constants) {
            w.uint(7);
            ledgerConstants(w, request.ledgerConstants());
        }
        w.uint(8).uint(request.dormantEpochs());
        if (guardrail) {
            w.uint(9).bytes(HexUtil.decodeHexString(request.guardrailScriptHash()));
        }
        w.uint(10);
        roots(w, request.roots());
        w.uint(11).uint(request.treasury());
        w.uint(12).array(2).uint(request.slot()).uint(request.transactionIndex());

        w.uint(13).array(request.utxo().size());
        for (AmaruRequest.Utxo utxo : request.utxo()) {
            w.array(2);
            input(w, utxo.outpoint());
            w.bytes(utxo.output());
        }

        w.uint(14).array(request.accounts().size());
        for (AmaruRequest.Account account : request.accounts()) {
            w.array(5);
            credential(w, account.credential());
            w.uint(account.deposit()).uint(account.rewards());
            if (account.pool() == null) {
                w.nil();
            } else {
                w.array(2).bytes(hash(account.pool()));
                pointer(w, account.poolPointer());
            }
            if (account.drep() == null) {
                w.nil();
            } else {
                w.array(2);
                drep(w, account.drep());
                pointer(w, account.drepPointer());
            }
        }

        w.uint(15).array(request.pools().size());
        for (PoolId pool : request.pools()) {
            w.bytes(hash(pool));
        }

        w.uint(16).array(request.dreps().size());
        for (AmaruRequest.DRep drep : request.dreps()) {
            w.array(4);
            credential(w, drep.credential());
            w.uint(drep.deposit());
            pointer(w, drep.registeredAt());
            w.uint(drep.validUntil());
        }

        w.uint(17).array(request.committee().size());
        for (CommitteeMember member : request.committee()) {
            w.array(3);
            credential(w, member.cold());
            if (member.hot() != null) {
                w.array(2).uint(0);
                credential(w, member.hot());
            } else if (member.resigned()) {
                w.array(1).uint(1);
            } else {
                w.nil();
            }
            if (member.validUntil() == null) {
                w.nil();
            } else {
                w.uint(member.validUntil());
            }
        }

        w.uint(18).array(request.proposals().size());
        for (AmaruRequest.Proposal proposal : request.proposals()) {
            w.array(3);
            govActionId(w, proposal.id());
            proposalKind(w, proposal.kind());
            w.uint(proposal.validUntil());
        }
        return w.toByteArray();
    }

    /** The {@code required_keys} env: {@code {0: 1, 1: [major, minor]}}. */
    public static byte[] keysEnv(long protocolMajor, long protocolMinor) {
        return new CborWriter(16).map(2).uint(0).uint(ABI_VERSION).uint(1).array(2).uint(protocolMajor)
                .uint(protocolMinor).toByteArray();
    }

    private static void eraHistory(CborWriter w, AmaruNetworkParameters network) {
        w.array(2).uint(network.stabilityWindow()).array(network.eras().size());
        for (EraSummary era : network.eras()) {
            w.array(5);
            eraBound(w, era.start());
            if (era.end() == null) {
                w.nil();
            } else {
                eraBound(w, era.end());
            }
            w.uint(era.epochSizeSlots()).uint(era.slotLengthMs()).uint(era.eraTag());
        }
    }

    private static void eraBound(CborWriter w, EraBound bound) {
        w.array(3).uint(bound.timeMs()).uint(bound.slot()).uint(bound.epoch());
    }

    private static void globalParameters(CborWriter w, GlobalParameters p) {
        w.array(7).uint(p.securityParam()).uint(p.epochLengthScaleFactor()).uint(p.activeSlotCoeffInverse())
                .uint(p.maxLovelaceSupply()).uint(p.slotsPerKesPeriod()).uint(p.maxKesEvolution())
                .uint(p.systemStartMs());
    }

    private static void ledgerConstants(CborWriter w, AmaruLedgerConstants c) {
        int entries = (c.maxRefScriptSizePerTx() != null ? 1 : 0) + (c.maxRefScriptSizePerBlock() != null ? 1 : 0)
                + (c.refScriptCostStride() != null ? 1 : 0) + (c.refScriptCostMultiplierNumerator() != null ? 1 : 0);
        w.map(entries);
        if (c.maxRefScriptSizePerTx() != null) {
            w.uint(0).uint(c.maxRefScriptSizePerTx());
        }
        if (c.maxRefScriptSizePerBlock() != null) {
            w.uint(1).uint(c.maxRefScriptSizePerBlock());
        }
        if (c.refScriptCostStride() != null) {
            w.uint(2).uint(c.refScriptCostStride());
        }
        if (c.refScriptCostMultiplierNumerator() != null) {
            w.uint(3).array(2).uint(c.refScriptCostMultiplierNumerator()).uint(c.refScriptCostMultiplierDenominator());
        }
    }

    private static void roots(CborWriter w, EnactedRoots roots) {
        w.array(4);
        for (GovActionId root : new GovActionId[]{roots.pparamUpdate(), roots.hardFork(), roots.committee(),
                roots.constitution()}) {
            if (root == null) {
                w.nil();
            } else {
                govActionId(w, root);
            }
        }
    }

    static void input(CborWriter w, Outpoint outpoint) {
        w.array(2).bytes(HexUtil.decodeHexString(outpoint.txHash())).uint(outpoint.index());
    }

    static void credential(CborWriter w, CredentialKey credential) {
        w.array(2).uint(credential.type().tag()).bytes(HexUtil.decodeHexString(credential.hashHex()));
    }

    private static void drep(CborWriter w, DRepTarget drep) {
        switch (drep.kind()) {
            case CREDENTIAL -> credential(w, drep.credential());
            case ALWAYS_ABSTAIN -> w.array(1).uint(2);
            case ALWAYS_NO_CONFIDENCE -> w.array(1).uint(3);
        }
    }

    private static void pointer(CborWriter w, CertPointer pointer) {
        if (pointer == null) {
            w.nil();
        } else {
            w.array(3).uint(pointer.slot()).uint(pointer.transactionIndex()).uint(pointer.certificateIndex());
        }
    }

    private static void govActionId(CborWriter w, GovActionId id) {
        w.array(2).bytes(HexUtil.decodeHexString(id.txHashHex())).uint(id.index());
    }

    private static void proposalKind(CborWriter w, ProposalKind kind) {
        switch (kind) {
            case ProposalKind.ParameterChange p -> w.array(2).uint(0).bool(p.anyInSecurityGroup());
            case ProposalKind.HardFork h -> w.array(3).uint(1).uint(h.major()).uint(h.minor());
            case ProposalKind.Committee c -> w.array(1).uint(2);
            case ProposalKind.Constitution c -> w.array(1).uint(3);
            case ProposalKind.TreasuryWithdrawals t -> w.array(1).uint(4);
            case ProposalKind.Info i -> w.array(1).uint(5);
        }
    }

    private static byte[] hash(PoolId pool) {
        return HexUtil.decodeHexString(pool.hashHex());
    }
}
