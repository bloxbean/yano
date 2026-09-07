package com.bloxbean.cardano.yano.runtime.wallet;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.api.chain.ChainPoint;
import com.bloxbean.cardano.yano.api.wallet.WalletCredential;
import com.bloxbean.cardano.yano.api.wallet.WalletIndexCoverage;
import com.bloxbean.cardano.yano.api.wallet.WalletScan;
import com.bloxbean.cardano.yano.api.wallet.WalletScanEvent;
import com.bloxbean.cardano.yano.api.wallet.WalletScanRequest;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Request-local forward walk. No historical transaction-location index or UTxO mirror. */
public final class WalletScanner implements WalletScan {
    public interface Backend {
        void validate();
        List<WalletIndexStore.FilterRecord> filters(long afterBlock, long toBlock, int limit);
        Block block(ChainPoint point);
        List<Utxo> genesis();
    }

    private final Backend backend;
    private final WalletIndexCoverage coverage;
    private final ChainPoint end;
    private final Set<WalletCredential> credentials;
    private final List<byte[]> elements;
    private final Map<Outpoint, Utxo> tracked = new HashMap<>();
    private ChainPoint cursor;
    private boolean ready;
    private boolean finished;
    private boolean complete = true;

    public WalletScanner(Backend backend, WalletScanRequest request,
                         WalletIndexCoverage coverage, ChainPoint end) {
        this.backend = backend;
        this.coverage = coverage;
        this.end = end;
        this.cursor = request.after();
        this.credentials = Set.copyOf(request.credentials());
        this.elements = credentials.stream().map(WalletCredential::filterElement).toList();
        backend.validate();
        if (cursor.blockNumber() == -1) {
            for (Utxo output : backend.genesis()) if (matchesAddress(output.address())) remember(output);
        } else {
            for (Utxo output : request.knownOutputs()) {
                if (output.outpoint() == null || output.outpoint().txHash() == null
                        || !output.outpoint().txHash().matches("[0-9a-f]{64}") || output.outpoint().index() < 0
                        || output.lovelace() == null || output.lovelace().signum() < 0
                        || output.slot() > cursor.slot() || output.blockNumber() > cursor.blockNumber()
                        || !matchesAddress(output.address())) {
                    throw new IllegalArgumentException("Invalid known output at resume boundary");
                }
                if (tracked.putIfAbsent(output.outpoint(), output) != null) throw new IllegalArgumentException("Duplicate known outpoint");
            }
        }
    }

    @Override public List<WalletScanEvent> next() {
        if (finished) return List.of();
        backend.validate();
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Scan cancelled");
        if (!ready) {
            ready = true;
            List<WalletScanEvent> initial = new ArrayList<>();
            initial.add(new WalletScanEvent("ready", end, coverage, null, null, null, null, null));
            if (cursor.blockNumber() == -1 && !tracked.isEmpty()) {
                initial.add(new WalletScanEvent("genesis", ChainPoint.ORIGIN, null, null,
                        true, List.of(), List.copyOf(tracked.values()), null));
            }
            return initial;
        }
        if (cursor.equals(end)) {
            finished = true;
            // Older clients must never mistake a partial scan for a durable recovery boundary.
            return List.of(new WalletScanEvent(complete ? "done" : "incomplete", end, null, null,
                    null, null, null, complete ? null : "Wallet indexing gaps; do not advance recovery cursor", complete));
        }
        List<WalletScanEvent> events = new ArrayList<>();
        List<WalletIndexStore.FilterRecord> records = backend.filters(cursor.blockNumber(), end.blockNumber(), 256);
        if (records.isEmpty()) throw new IllegalStateException("Filter coverage gap before requested end");
        for (WalletIndexStore.FilterRecord record : records) {
            long expected = cursor.blockNumber() + 1;
            if (record.point().blockNumber() != expected
                    && !(cursor.blockNumber() == -1 && record.point().blockNumber() == 1)) {
                throw new IllegalStateException("Missing filter inside scan range");
            }
            if (record.error() != null) warning(record.point(), record.error(), events);
            if (record.error() != null || CredentialFilter.matches(record.filter(), elements)) {
                process(backend.block(record.point()), record.point(), events);
            }
            cursor = record.point();
        }
        backend.validate();
        events.add(WalletScanEvent.progress("progress", cursor));
        return events;
    }

    private void process(Block block, ChainPoint point, List<WalletScanEvent> events) {
        Set<Integer> invalid = block.getInvalidTransactions() == null ? Set.of() : Set.copyOf(block.getInvalidTransactions());
        List<TransactionBody> transactions = block.getTransactionBodies() == null ? List.of() : block.getTransactionBodies();
        for (int i = 0; i < transactions.size(); i++) {
            TransactionBody tx = transactions.get(i);
            boolean valid = !invalid.contains(i);
            boolean matched = false;
            List<Outpoint> inputs = new ArrayList<>();
            var effectiveInputs = valid ? tx.getInputs() : tx.getCollateralInputs();
            if (effectiveInputs != null) {
                for (var input : effectiveInputs) {
                    Outpoint outpoint = new Outpoint(input.getTransactionId(), input.getIndex());
                    inputs.add(outpoint);
                    if (tracked.remove(outpoint) != null) matched = true;
                }
            }
            List<Utxo> outputs = new ArrayList<>();
            if (valid && tx.getOutputs() != null) {
                for (int outputIndex = 0; outputIndex < tx.getOutputs().size(); outputIndex++) {
                    outputs.add(output(tx.getOutputs().get(outputIndex), tx.getTxHash(), outputIndex, false, point));
                }
            } else if (!valid && tx.getCollateralReturn() != null) {
                outputs.add(output(tx.getCollateralReturn(), tx.getTxHash(),
                        tx.getOutputs() == null ? 0 : tx.getOutputs().size(), true, point));
            }
            for (Utxo output : outputs) {
                boolean owned;
                try { owned = matchesAddress(output.address()); }
                catch (RuntimeException failure) {
                    warning(point, "Cannot decode output " + output.outpoint() + ": " + failure.getMessage(), events);
                    continue;
                }
                if (owned) {
                    remember(output);
                    matched = true;
                }
            }
            if (valid) {
                Set<WalletCredential> touched = new HashSet<>();
                try { WalletCredentials.events(tx, touched); }
                catch (RuntimeException failure) {
                    warning(point, "Cannot extract credentials from transaction " + tx.getTxHash()
                            + ": " + failure.getMessage(), events);
                }
                if (touched.stream().anyMatch(credentials::contains)) matched = true;
            }
            if (matched) events.add(new WalletScanEvent("transaction", point, null, tx.getTxHash(), valid,
                    List.copyOf(inputs), List.copyOf(outputs), null));
        }
    }

    private boolean matchesAddress(String address) {
        Set<WalletCredential> touched = new HashSet<>();
        WalletCredentials.address(address, touched);
        return touched.stream().anyMatch(credentials::contains);
    }

    private void warning(ChainPoint point, String reason, List<WalletScanEvent> events) {
        complete = false;
        // One warning per block per batch is enough; its point identifies the repair unit.
        if (events.stream().noneMatch(e -> e.type().equals("warning") && point.equals(e.point()))) {
            events.add(new WalletScanEvent("warning", point, null, null, null, null, null, reason, false));
        }
    }

    private void remember(Utxo output) {
        tracked.put(output.outpoint(), output);
        if (tracked.size() > 10_000) throw new IllegalStateException("Scan tracked-output limit exceeded");
    }

    private static Utxo output(TransactionOutput output, String txHash, int index,
                               boolean collateralReturn, ChainPoint point) {
        BigInteger lovelace = BigInteger.ZERO;
        List<AssetAmount> assets = new ArrayList<>();
        if (output.getAmounts() != null) {
            for (var amount : output.getAmounts()) {
                if ("lovelace".equals(amount.getUnit())) lovelace = lovelace.add(amount.getQuantity());
                else {
                    byte[] name = amount.getAssetNameBytes();
                    assets.add(new AssetAmount(amount.getPolicyId(),
                            name == null ? "" : HexUtil.encodeHexString(name), amount.getQuantity()));
                }
            }
        }
        return new Utxo(new Outpoint(txHash, index), output.getAddress(), lovelace, List.copyOf(assets),
                output.getDatumHash(), output.getInlineDatum() == null ? null : HexUtil.decodeHexString(output.getInlineDatum()),
                output.getScriptRef(), null, collateralReturn, point.slot(), point.blockNumber(), point.blockHash());
    }

    @Override public boolean finished() { return finished; }
    @Override public void close() { finished = true; tracked.clear(); }
}
