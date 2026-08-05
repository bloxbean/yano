package com.bloxbean.cardano.yano.appchain.showcase.client;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoClient;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoKeyWallet;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2KeyBinding;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReceipt;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoVaultDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;

import java.io.Console;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * EUTxO bridge-chain walkthrough for the showcase (ADR-UTXO-008 BR-M4).
 *
 * <p>User role: deposit YOUR OWN L1 funds into the chain's vault with the
 * correct inline datum (mnemonic supplied via file or interactive prompt,
 * never argv), then operate on the L2: list UTxOs, transfer, and create a
 * withdrawal claim. Operator role: settle a claim on the L1 with the vault
 * operator seed. Everything — L1 UTxO queries, protocol parameters,
 * submission, and L2 reads — goes through the SAME Yano node.
 *
 * <p>The default vault/withdrawal identities are the showcase's PUBLIC
 * deterministic demo keys; both can be overridden for other bridge chains.
 */
public final class ShowcaseEutxoClientDemo {
    private static final long DEFAULT_AMOUNT = 5_000_000L;
    private static final long REFUND_DEADLINE = 10_000_000L;
    private static final long TTL_SLOT = 10_000_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String apiBase;
    private final String chainId;
    private final BackendService backend;
    private final QuickTxBuilder quickTx;
    private final UtxoSupplier utxoSupplier;
    private final EutxoClient eutxo;

    private ShowcaseEutxoClientDemo(String apiBase, String chainId) {
        this.apiBase = apiBase;
        this.chainId = chainId;
        this.backend = new BFBackendService(apiBase + "/", "demo");
        this.quickTx = new QuickTxBuilder(backend);
        this.utxoSupplier = new DefaultUtxoSupplier(backend.getUtxoService());
        this.eutxo = new EutxoClient(
                AppChainClient.builder(apiBase).chainId(chainId).build());
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 3) {
            usage();
            System.exit(2);
        }
        String apiBase = normalizeBase(arguments[0]);
        String chainId = arguments[1];
        String scenario = arguments[2];
        Args args = new Args(arguments, 3);
        ShowcaseEutxoClientDemo demo = new ShowcaseEutxoClientDemo(apiBase, chainId);
        switch (scenario) {
            case "deposit" -> demo.deposit(args);
            case "utxos" -> demo.utxos(args);
            case "transfer" -> demo.transfer(args);
            case "claim" -> demo.claim(args);
            case "settle" -> demo.settle(args);
            case "receipt" -> demo.receipt(args);
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    // ------------------------------------------------------------------
    // User role
    // ------------------------------------------------------------------

    private void deposit(Args args) throws Exception {
        Account account = account(args);
        long amount = args.longValue("--amount", DEFAULT_AMOUNT);
        String l2Owner = args.value("--l2-owner-address", account.baseAddress());
        String vaultAddress = args.value("--vault-address", demoVaultAddress());
        System.out.printf(Locale.ROOT,
                "depositor  : %s%n", account.baseAddress());
        System.out.printf(Locale.ROOT,
                "l2 owner   : %s%nvault      : %s%namount     : %d lovelace%n",
                l2Owner, vaultAddress, amount);

        // 1. Stage the exact amount on OUR OWN address so the vault input is
        //    a single clean UTxO (mirrors the maintained bridge workflow).
        String stagingTx = submitL1(new Tx()
                .payToAddress(account.baseAddress(), Amount.lovelace(
                        BigInteger.valueOf(amount)))
                .from(account.baseAddress()), account);
        Utxo staged = awaitL1Utxo(stagingTx, account.baseAddress(), amount);
        System.out.printf(Locale.ROOT, "staged     : %s#%d%n",
                staged.getTxHash(), staged.getOutputIndex());

        // 2. Pay the vault with the inline datum that names the L2 owner —
        //    without this datum the observer ignores the payment entirely.
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        EutxoVaultDatum datum = new EutxoVaultDatum(
                EutxoVaultDatum.ABI_VERSION,
                chainId,
                l2Owner,
                nonce,
                new EutxoOutpoint(staged.getTxHash(), staged.getOutputIndex()),
                REFUND_DEADLINE,
                paymentCredential(account.baseAddress()),
                EutxoL2KeyBinding.none());
        String acceptedTx = submitL1(new Tx()
                .collectFrom(List.of(staged))
                .payToContract(vaultAddress, Amount.lovelace(
                        BigInteger.valueOf(amount)),
                        PlutusData.deserialize(datum.encode()))
                .from(account.baseAddress()), account);
        Utxo accepted = awaitL1Utxo(acceptedTx, vaultAddress, amount);
        EutxoOutpoint outpoint = new EutxoOutpoint(
                accepted.getTxHash(), accepted.getOutputIndex());
        System.out.printf(Locale.ROOT, "accepted   : %s%n", outpoint);

        // 3. Wait for stability-gated observation to mirror the deposit.
        System.out.println("waiting for the L2 mirror (stability depth blocks)…");
        for (int i = 0; i < 240; i++) {
            try {
                var record = eutxo.depositSnapshot(outpoint).value();
                if (record.isPresent()) {
                    System.out.printf(Locale.ROOT,
                            "MIRRORED   : %s (owner %s)%n",
                            record.orElseThrow().mirroredOutpoint(), l2Owner);
                    System.out.println("deposit complete — the funds are live on the L2.");
                    return;
                }
            } catch (RuntimeException ignored) {
                // Observation is asynchronous.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(
                "deposit did not mirror in time; check chain status and stability depth");
    }

    private void utxos(Args args) throws Exception {
        String address = args.value("--address", null);
        if (address == null) {
            address = account(args).baseAddress();
        }
        List<EutxoRecord> records = eutxo.utxos(address);
        System.out.printf(Locale.ROOT, "address    : %s%n", address);
        if (records.isEmpty()) {
            System.out.println("no L2 UTxOs (deposit first)");
            return;
        }
        BigInteger total = BigInteger.ZERO;
        for (EutxoRecord record : records) {
            BigInteger value = recordLovelace(record);
            System.out.printf(Locale.ROOT, "  %s : %d lovelace%n",
                    record.outpoint(), value);
            total = total.add(value);
        }
        System.out.printf(Locale.ROOT, "balance    : %d lovelace across %d UTxOs%n",
                total, records.size());
    }

    private void transfer(Args args) throws Exception {
        Account account = account(args);
        String to = args.require("--to");
        long amount = args.longValue("--amount", DEFAULT_AMOUNT / 5);
        submitL2Spend(account, to, amount, null,
                "TRANSFERRED");
    }

    private void claim(Args args) throws Exception {
        Account account = account(args);
        long amount = args.longValue("--amount", 2_000_000L);
        long bridgeEpoch = args.longValue("--bridge-epoch", 1L);
        String withdrawalAddress = args.value(
                "--withdrawal-address", demoWithdrawalAddress());
        String payout = args.value("--payout-address", account.baseAddress());
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        EutxoWithdrawalDatum datum = new EutxoWithdrawalDatum(
                EutxoWithdrawalDatum.ABI_VERSION,
                chainId, bridgeEpoch, payout, nonce);
        System.out.printf(Locale.ROOT,
                "claiming %d lovelace to L1 payout %s%n", amount, payout);
        EutxoReceipt receipt = submitL2Spend(
                account, withdrawalAddress, amount, datum, "CLAIM SUBMITTED");
        // The claim id embeds the CHAIN-ASSIGNED settlement sequence, so we
        // cannot predict it; every other identity field is ours (the nonce
        // makes it unique), so probe candidate sequences until the chain
        // resolves the record.
        String claimId = null;
        outer:
        for (int attempt = 0; attempt < 60 && claimId == null; attempt++) {
            for (long sequence = 0; sequence < 4096; sequence++) {
                EutxoWithdrawalClaim candidate = new EutxoWithdrawalClaim(
                        EutxoWithdrawalClaim.ABI_VERSION,
                        chainId, bridgeEpoch,
                        new EutxoOutpoint(receipt.transactionId(), 0),
                        payout, BigInteger.valueOf(amount), nonce,
                        sequence, receipt.appHeight());
                if (eutxo.withdrawalSnapshot(candidate.claimId())
                        .value().isPresent()) {
                    claimId = candidate.claimId();
                    continue outer;
                }
            }
            Thread.sleep(500);
        }
        if (claimId == null) {
            throw new IllegalStateException(
                    "withdrawal claim did not appear; check the chain status");
        }
        System.out.printf(Locale.ROOT, "claim id   : %s%n", claimId);
        System.out.println(
                "the claim is irrevocable; the operator settles it on the L1 with:");
        System.out.printf(Locale.ROOT,
                "  … eutxo %s %s settle --operator-seed-file <file> --claim-id %s%n",
                apiBase, chainId, claimId);
    }

    private void receipt(Args args) throws Exception {
        String transactionId = args.require("--tx");
        var receipt = eutxo.transaction(transactionId);
        if (receipt.isEmpty()) {
            System.out.println("no receipt yet (not finalized, or unknown transaction)");
            return;
        }
        EutxoReceipt value = receipt.orElseThrow();
        System.out.printf(Locale.ROOT,
                "transaction: %s%nstatus     : %s%nheight     : %d%n",
                value.transactionId(), value.status(), value.appHeight());
    }

    // ------------------------------------------------------------------
    // Operator role
    // ------------------------------------------------------------------

    private void settle(Args args) throws Exception {
        Path seedFile = Path.of(args.require("--operator-seed-file"));
        String claimId = args.require("--claim-id");
        String vaultAddress = args.value("--vault-address", demoVaultAddress());
        EutxoKeyWallet operator = walletFromSeedFile(seedFile);
        ScriptPubkey vault = ScriptPubkey.create(
                VerificationKey.create(operator.verificationKey().getBytes()));
        EutxoWithdrawalRecord record = eutxo.withdrawalSnapshot(claimId).value()
                .orElseThrow(() -> new IllegalStateException(
                        "unknown withdrawal claim: " + claimId));
        if (record.status() == EutxoWithdrawalRecord.Status.CONFIRMED) {
            System.out.println("claim is already CONFIRMED");
            return;
        }
        EutxoWithdrawalClaim claim = record.claim();
        BigInteger amount = claim.lovelace();
        // Aggregate vault deposits: no single UTxO needs to cover the claim.
        List<Utxo> vaultInputs = new ArrayList<>();
        BigInteger gathered = BigInteger.ZERO;
        BigInteger target = amount.add(BigInteger.valueOf(1_000_000));
        for (Utxo candidate : utxoSupplier.getAll(vaultAddress)) {
            vaultInputs.add(candidate);
            gathered = gathered.add(lovelace(candidate));
            if (gathered.compareTo(target) >= 0) {
                break;
            }
        }
        if (gathered.compareTo(amount) <= 0) {
            throw new IllegalStateException("vault holds " + gathered
                    + " lovelace; cannot cover the " + amount + " claim");
        }
        // The vault pays out EXACTLY the claim: fees come from the
        // operator's own wallet, so the physical vault never drifts below
        // the chain's ledger reserve.
        BigInteger continuing = gathered.subtract(amount);
        EutxoSettlementDatum marker = EutxoSettlementDatum.forAddress(
                EutxoSettlementDatum.ABI_VERSION,
                chainId,
                claim.bridgeEpoch(),
                claimId,
                claim.destinationAddress(),
                amount);
        System.out.printf(Locale.ROOT,
                "settling %s: %d lovelace -> %s%n",
                claimId, amount, claim.destinationAddress());
        Result<String> result = quickTx.compose(new Tx()
                        .collectFrom(vaultInputs)
                        .payToAddress(claim.destinationAddress(),
                                Amount.lovelace(amount))
                        .payToContract(vaultAddress,
                                Amount.lovelace(continuing),
                                PlutusData.deserialize(marker.encode()))
                        .attachNativeScript(vault)
                        .from(operator.address()))
                .withSigner(SignerProviders.signerFrom(operator.signingKey()))
                .complete();
        if (!result.isSuccessful()) {
            throw new IllegalStateException("settlement failed: "
                    + safe(String.valueOf(result.getResponse()))
                    + " (the operator address " + operator.address()
                    + " must hold L1 funds for fees)");
        }
        System.out.printf(Locale.ROOT, "settled tx : %s%n", result.getValue());
        System.out.println("waiting for CONFIRMED reconciliation…");
        for (int i = 0; i < 240; i++) {
            EutxoWithdrawalRecord updated =
                    eutxo.withdrawalSnapshot(claimId).value().orElse(null);
            if (updated != null
                    && updated.status() == EutxoWithdrawalRecord.Status.CONFIRMED) {
                System.out.println("CONFIRMED  : withdrawal reconciled");
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("claim did not reconcile in time");
    }

    // ------------------------------------------------------------------
    // Shared plumbing
    // ------------------------------------------------------------------

    private EutxoReceipt submitL2Spend(
            Account account,
            String to,
            long amount,
            EutxoWithdrawalDatum datum,
            String label) throws Exception {
        String from = account.baseAddress();
        List<EutxoRecord> records = eutxo.utxos(from);
        List<TransactionInput> inputs = new ArrayList<>();
        BigInteger selected = BigInteger.ZERO;
        BigInteger needed = BigInteger.valueOf(amount);
        for (EutxoRecord record : records) {
            inputs.add(new TransactionInput(
                    record.outpoint().transactionId(), record.outpoint().index()));
            selected = selected.add(recordLovelace(record));
            if (selected.compareTo(needed) >= 0) {
                break;
            }
        }
        if (selected.compareTo(needed) < 0) {
            throw new IllegalStateException("insufficient L2 balance: have "
                    + selected + ", need " + needed + " (deposit first)");
        }
        List<TransactionOutput> outputs = new ArrayList<>();
        TransactionOutput.TransactionOutputBuilder paid = TransactionOutput.builder()
                .address(to)
                .value(Value.fromCoin(needed));
        if (datum != null) {
            paid.inlineDatum(PlutusData.deserialize(datum.encode()));
        }
        outputs.add(paid.build());
        if (selected.compareTo(needed) > 0) {
            outputs.add(TransactionOutput.builder()
                    .address(from)
                    .value(Value.fromCoin(selected.subtract(needed)))
                    .build());
        }
        long ttl;
        try {
            ttl = backend.getBlockService().getLatestBlock()
                    .getValue().getSlot() + 7_200L;
        } catch (Exception unavailable) {
            ttl = TTL_SLOT;
        }
        TransactionBody body = TransactionBody.builder()
                .inputs(inputs)
                .outputs(outputs)
                .fee(BigInteger.ZERO)
                .ttl(ttl)
                .networkId(NetworkId.TESTNET)
                .build();
        Transaction unsigned = Transaction.builder()
                .body(body)
                .witnessSet(new TransactionWitnessSet())
                .isValid(true)
                .build();
        Transaction signed = account.sign(unsigned);
        byte[] cbor = signed.serialize();
        var submission = eutxo.submit(cbor);
        String transactionId = TransactionUtil.getTxHash(cbor);
        for (int i = 0; i < 180; i++) {
            var receipt = eutxo.transaction(transactionId);
            if (receipt.isPresent()) {
                EutxoReceipt value = receipt.orElseThrow();
                if (value.status() != EutxoReceipt.Status.ACCEPTED) {
                    throw new IllegalStateException(
                            "L2 transaction was rejected: " + value.status());
                }
                System.out.printf(Locale.ROOT,
                        "%s: %s (message %s, height %d)%n",
                        label, transactionId, submission.messageId(),
                        value.appHeight());
                return value;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("L2 finality timeout for " + transactionId);
    }

    private String submitL1(Tx transaction, Account account) throws Exception {
        Result<String> result = quickTx.compose(transaction)
                .withSigner(SignerProviders.signerFrom(account))
                .complete();
        if (!result.isSuccessful()) {
            throw new IllegalStateException("L1 transaction failed: "
                    + safe(String.valueOf(result.getResponse()))
                    + " (is the depositor address funded?)");
        }
        return result.getValue();
    }

    private Utxo awaitL1Utxo(String transactionId, String address, long amount)
            throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            try {
                Utxo found = utxoSupplier.getAll(address).stream()
                        .filter(value -> transactionId.equals(value.getTxHash()))
                        .filter(value -> lovelace(value).equals(
                                BigInteger.valueOf(amount)))
                        .findFirst().orElse(null);
                if (found != null) {
                    return found;
                }
            } catch (RuntimeException ignored) {
                // Retry until the node serves the new UTxO.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("L1 UTxO did not appear for " + transactionId);
    }

    private Account account(Args args) throws Exception {
        String file = args.value("--mnemonic-file", null);
        String mnemonic;
        if (file != null) {
            mnemonic = Files.readString(Path.of(file)).trim();
        } else {
            Console console = System.console();
            if (console == null) {
                throw new IllegalStateException(
                        "no console for a mnemonic prompt; pass --mnemonic-file");
            }
            char[] typed = console.readPassword("L1 wallet mnemonic: ");
            mnemonic = new String(typed).trim();
        }
        if (mnemonic.split("\\s+").length < 12) {
            throw new IllegalArgumentException(
                    "mnemonic must contain at least 12 words");
        }
        return new Account(Networks.testnet(), mnemonic);
    }

    private static EutxoKeyWallet walletFromSeedFile(Path file) throws Exception {
        String value = Files.readString(file).trim();
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "operator seed file must hold 64 lowercase hex characters");
        }
        return EutxoKeyWallet.fromSeed(HexFormat.of().parseHex(value));
    }

    // PUBLIC deterministic showcase demo identities (never reuse elsewhere).
    private static String demoVaultAddress() throws Exception {
        EutxoKeyWallet operator = EutxoKeyWallet.fromSeed(demoSeed("bridge-operator"));
        ScriptPubkey vault = ScriptPubkey.create(
                VerificationKey.create(operator.verificationKey().getBytes()));
        return com.bloxbean.cardano.client.address.AddressProvider
                .getEntAddress(vault, Networks.testnet()).toBech32();
    }

    private static String demoWithdrawalAddress() throws Exception {
        return EutxoKeyWallet.fromSeed(demoSeed("bridge-payout")).address();
    }

    private static byte[] demoSeed(String actor) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(
                ("yano-showcase-demo-actor:" + actor)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] paymentCredential(String address) {
        return new Address(address).getPaymentCredentialHash()
                .orElseThrow(() -> new IllegalArgumentException(
                        "address has no payment credential"));
    }

    private static BigInteger recordLovelace(EutxoRecord record) {
        try {
            return TransactionOutput.deserialize(
                    (co.nstant.in.cbor.model.Array)
                            com.bloxbean.cardano.client.common.cbor
                                    .CborSerializationUtil.deserialize(
                                            record.outputCbor()))
                    .getValue().getCoin();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot decode L2 output", failure);
        }
    }

    private static BigInteger lovelace(Utxo utxo) {
        return utxo.getAmount().stream()
                .filter(amount -> "lovelace".equals(amount.getUnit()))
                .map(Amount::getQuantity)
                .findFirst().orElse(BigInteger.ZERO);
    }

    private static String normalizeBase(String value) {
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.endsWith("/api/v1") ? normalized : normalized + "/api/v1";
    }

    private static String safe(String message) {
        String flat = message.replaceAll("[\\r\\n\\t]+", " ");
        return flat.substring(0, Math.min(flat.length(), 240));
    }

    private static void usage() {
        System.err.println("""
                usage: … eutxo <base-url> <chain-id> <scenario> [options]
                scenarios (user role — YOUR mnemonic, file or prompt, never argv):
                  deposit  --mnemonic-file F [--amount 5000000] [--l2-owner-address addr]
                  utxos    [--address addr | --mnemonic-file F]
                  transfer --mnemonic-file F --to <l2-address> [--amount N]
                  claim    --mnemonic-file F [--amount 2000000] [--payout-address addr]
                  receipt  --tx <l2-transaction-id>
                scenario (operator role — the vault keyholder):
                  settle   --operator-seed-file F --claim-id <id>
                defaults: the showcase bridge chain's PUBLIC deterministic demo
                vault/withdrawal identities (override with --vault-address /
                --withdrawal-address). base-url may be http://host:port — /api/v1
                is appended automatically.""");
    }

    /** Tiny flag parser: --name value pairs after the positional arguments. */
    private static final class Args {
        private final String[] values;
        private final int offset;

        private Args(String[] values, int offset) {
            this.values = values;
            this.offset = offset;
        }

        private String value(String name, String fallback) {
            for (int i = offset; i < values.length - 1; i++) {
                if (values[i].equals(name)) {
                    return values[i + 1];
                }
            }
            return fallback;
        }

        private String require(String name) {
            String value = value(name, null);
            if (value == null) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value;
        }

        private long longValue(String name, long fallback) {
            String value = value(name, null);
            return value == null ? fallback : Long.parseLong(value);
        }
    }
}
