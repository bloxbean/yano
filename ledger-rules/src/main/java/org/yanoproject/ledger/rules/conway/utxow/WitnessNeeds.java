package org.yanoproject.ledger.rules.conway.utxow;

import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.tx.AddressBytes;
import org.yanoproject.ledger.rules.conway.tx.RawCertificate;
import org.yanoproject.ledger.rules.conway.tx.RawProposal;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.RawVoter;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What a Conway transaction needs to be authorised: the scripts ({@code getConwayScriptsNeeded},
 * Conway/UTxO.hs:62-106) and the key hashes ({@code getConwayWitsVKeyNeeded}, :174-199).
 *
 * <p>Neither reads the certificate state: Conway's {@code getWitsVKeyNeeded _ = getConwayWitsVKeyNeeded} ignores
 * it (:148), and the certificate witnesses depend on the certificate alone ({@code getVKeyWitnessConwayTxCert},
 * {@code getScriptWitnessConwayTxCert}, Conway/TxCert.hs:763-804). The UTxO is the state before the transaction.</p>
 */
final class WitnessNeeds {

    /** Redeemer tags ({@code ConwayPlutusPurpose} constructor order). */
    static final int SPEND = 0;
    static final int MINT = 1;
    static final int CERT = 2;
    static final int REWARD = 3;
    static final int VOTING = 4;
    static final int PROPOSING = 5;

    /**
     * One needed script: the purpose as a redeemer pointer and item, and the script hash.
     *
     * @param tag   the purpose's redeemer tag
     * @param index the purpose's index ({@code AsIx})
     * @param item  the purpose's item, rendered ({@code AsItem})
     * @param hash  the script hash, hex
     */
    record NeededScript(int tag, long index, String item, String hash) {

        /** @return the redeemer key {@code (tag, index)}, packed as the redeemer map keys it */
        long key() {
            return ((long) tag << 32) | index;
        }

        String purpose() {
            return purposeName(tag) + " (AsIx " + index + ")";
        }

        @Override
        public String toString() {
            return "(" + purposeName(tag) + " (AsItem " + item + "), " + hash + ")";
        }
    }

    private WitnessNeeds() {
    }

    static String purposeName(int tag) {
        return switch (tag) {
            case SPEND -> "ConwaySpending";
            case MINT -> "ConwayMinting";
            case CERT -> "ConwayCertifying";
            case REWARD -> "ConwayRewarding";
            case VOTING -> "ConwayVoting";
            case PROPOSING -> "ConwayProposing";
            default -> "purpose " + tag;
        };
    }

    /**
     * {@code getConwayScriptsNeeded}: spending, then withdrawing, certifying, minting, voting and proposing, each in
     * its container's order with its index counted over every element ({@code zipAsIxItem} before
     * {@code catMaybes}).
     */
    static List<NeededScript> scriptsNeeded(TransitionContext ctx) {
        RawTransaction raw = ctx.raw();
        List<NeededScript> needed = new ArrayList<>();

        // getSpendingScriptsNeeded: the spending inputs (a Set TxIn) found in the UTxO whose payment credential is a
        // script (Alonzo/UTxO.hs:317-330).
        int index = 0;
        for (TxInRef in : raw.inputSet()) {
            int ix = index++;
            ctx.utxo(in).flatMap(WitnessNeeds::paymentScriptHash)
                    .ifPresent(hash -> needed.add(new NeededScript(SPEND, ix, in.toString(), hash)));
        }

        // getWithdrawingScriptsNeeded: the withdrawals' account addresses in Map order (network, then credential:
        // script before key, then hash), script credentials only (:332-341).
        index = 0;
        for (RawTransaction.Withdrawal w : sortedWithdrawals(raw)) {
            int ix = index++;
            byte[] account = w.rewardAccount();
            if ((account[0] & 0x10) != 0) {
                needed.add(new NeededScript(REWARD, ix, HexUtil.encodeHexString(account), hash28(account)));
            }
        }

        // certifyingScriptsNeeded: every certificate, indexed by position (Conway/UTxO.hs:76-81, no Alonzo dedup).
        for (RawCertificate cert : raw.certificates()) {
            byte[] hash = cert.scriptWitness();
            if (hash != null) {
                needed.add(new NeededScript(CERT, cert.index(), cert.toString(), HexUtil.encodeHexString(hash)));
            }
        }

        // getMintingScriptsNeeded: every minted policy (a Set PolicyID) (Alonzo/UTxO.hs:350-358).
        index = 0;
        for (String policy : new TreeSet<>(raw.mint().keySet())) {
            needed.add(new NeededScript(MINT, index++, policy, policy));
        }

        // votingScriptsNeeded: committee and DRep script credentials, voters in Ord Voter order (:83-94).
        index = 0;
        for (RawVoter voter : raw.voters()) {
            int ix = index++;
            if (voter.isScript()) {
                needed.add(new NeededScript(VOTING, ix, voter.toString(), HexUtil.encodeHexString(voter.hash())));
            }
        }

        // proposingScriptsNeeded: the guardrails policy of a parameter change or treasury withdrawal (:96-106).
        for (RawProposal proposal : raw.proposals()) {
            byte[] policy = proposal.policyHash();
            if (policy != null) {
                needed.add(new NeededScript(PROPOSING, proposal.index(), proposal.toString(),
                        HexUtil.encodeHexString(policy)));
            }
        }
        return needed;
    }

    /**
     * {@code getConwayWitsVKeyNeeded} = {@code getShelleyWitsVKeyNeededNoGov} (Shelley/UTxO.hs:205-251: certificate
     * authors, the payment keys of the spendable inputs, pool owners, withdrawal keys) ∪ required signers ∪ voter
     * keys.
     *
     * @return the needed key hashes, hex, sorted (Haskell's {@code Set (KeyHash Witness)})
     */
    static SortedSet<String> vkeysNeeded(TransitionContext ctx) {
        RawTransaction raw = ctx.raw();
        SortedSet<String> needed = new TreeSet<>();
        for (RawCertificate cert : raw.certificates()) {
            byte[] hash = cert.vkeyWitness();
            if (hash != null) {
                needed.add(HexUtil.encodeHexString(hash));
            }
        }
        // spendableInputsTxBodyF (Babbage): spending ∪ collateral inputs; a key payment credential or a bootstrap
        // address's root.
        SortedSet<TxInRef> spendable = new TreeSet<>(raw.inputSet());
        spendable.addAll(raw.collateralSet());
        for (TxInRef in : spendable) {
            ctx.utxo(in).flatMap(WitnessNeeds::paymentKeyHash).ifPresent(needed::add);
        }
        for (RawCertificate cert : raw.certificates()) {
            if (cert.tag() == 3) {
                cert.poolOwners().forEach(owner -> needed.add(HexUtil.encodeHexString(owner)));
            }
        }
        for (RawTransaction.Withdrawal w : raw.withdrawals()) {
            byte[] account = w.rewardAccount();
            if ((account[0] & 0x10) == 0) {
                needed.add(hash28(account));
            }
        }
        raw.requiredSigners().forEach(signer -> needed.add(HexUtil.encodeHexString(signer)));
        for (RawVoter voter : raw.voters()) {
            if (!voter.isScript()) {
                needed.add(HexUtil.encodeHexString(voter.hash()));
            }
        }
        return needed;
    }

    /** @return the withdrawals in Haskell's {@code Map AccountAddress} order */
    static List<RawTransaction.Withdrawal> sortedWithdrawals(RawTransaction raw) {
        List<RawTransaction.Withdrawal> sorted = new ArrayList<>(raw.withdrawals());
        sorted.sort(Comparator.comparingInt((RawTransaction.Withdrawal w) -> w.rewardAccount()[0] & 0x01)
                .thenComparingInt(w -> (w.rewardAccount()[0] & 0x10) != 0 ? 0 : 1)
                .thenComparing(w -> Arrays.copyOfRange(w.rewardAccount(), 1, 29), Arrays::compareUnsigned));
        return sorted;
    }

    /** @return the payment script hash (hex) of a resolved output's Shelley address */
    static Optional<String> paymentScriptHash(UtxoEntry entry) {
        return AddressBytes.paymentScriptHash(AddressBytes.fromCcl(entry.output().getAddress()))
                .map(HexUtil::encodeHexString);
    }

    /** @return the payment key hash (hex) of a resolved output: a key credential or a bootstrap address root */
    static Optional<String> paymentKeyHash(UtxoEntry entry) {
        byte[] address = AddressBytes.fromCcl(entry.output().getAddress());
        if (AddressBytes.isBootstrap(address)) {
            return Optional.of(HexUtil.encodeHexString(AddressBytes.bootstrapRoot(address)));
        }
        if (!AddressBytes.isVKeyLocked(address) || address.length < 29) {
            return Optional.empty();
        }
        return Optional.of(HexUtil.encodeHexString(Arrays.copyOfRange(address, 1, 29)));
    }

    private static String hash28(byte[] account) {
        return HexUtil.encodeHexString(Arrays.copyOfRange(account, 1, 29));
    }
}
