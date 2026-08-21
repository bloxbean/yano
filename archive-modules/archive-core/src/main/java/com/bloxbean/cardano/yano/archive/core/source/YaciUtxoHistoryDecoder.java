package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.PointerAddress;
import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.client.api.util.ReferenceScriptUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.StakeDeregistration;
import com.bloxbean.cardano.yaci.core.model.certs.UnregCert;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.util.AddressKeyUtil;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.core.address.AddressKeyCodec;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryFact;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryProjection;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.function.LongUnaryOperator;
import java.util.function.LongFunction;

/** Canonical, resolver-independent normalized UTXO-history decoder. */
public final class YaciUtxoHistoryDecoder implements CanonicalBlockDecoder<UtxoHistoryFact> {
    private final YaciBlockDecoder blockDecoder;
    private final AddressKeyCodec addressKeys = new AddressKeyCodec();
    private final List<GenesisOutput> genesisOutputs;
    private final long genesisBlockNumber;
    private final UtxoHistoryProjection projection;
    /** Per-block address memoisation bound; 0 disables caching entirely. */
    private volatile int addressCacheMaxEntries = BoundedDecodeCache.DEFAULT_MAX_ENTRIES;
    private final java.util.concurrent.atomic.LongAdder addressCacheHits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder addressCacheMisses = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder addressCacheSkipped = new java.util.concurrent.atomic.LongAdder();

    public YaciUtxoHistoryDecoder(LongUnaryOperator slotToEpoch, LongUnaryOperator slotToUnixTime) {
        this.blockDecoder = new YaciBlockDecoder(slotToEpoch, slotToUnixTime);
        this.genesisOutputs = List.of();
        this.genesisBlockNumber = 0;
        this.projection = UtxoHistoryProjection.all();
    }

    public YaciUtxoHistoryDecoder(LongUnaryOperator slotToEpoch, LongUnaryOperator slotToUnixTime,
                                  LongFunction<Era> storedEra) {
        this(slotToEpoch, slotToUnixTime, storedEra, List.of());
    }

    public YaciUtxoHistoryDecoder(LongUnaryOperator slotToEpoch, LongUnaryOperator slotToUnixTime,
                                  LongFunction<Era> storedEra, List<GenesisOutput> genesisOutputs) {
        this(slotToEpoch, slotToUnixTime, storedEra, genesisOutputs, 0);
    }

    public YaciUtxoHistoryDecoder(LongUnaryOperator slotToEpoch, LongUnaryOperator slotToUnixTime,
                                  LongFunction<Era> storedEra, List<GenesisOutput> genesisOutputs,
                                  long genesisBlockNumber) {
        this(slotToEpoch, slotToUnixTime, storedEra, genesisOutputs, genesisBlockNumber,
                UtxoHistoryProjection.all());
    }

    public YaciUtxoHistoryDecoder(LongUnaryOperator slotToEpoch, LongUnaryOperator slotToUnixTime,
                                  LongFunction<Era> storedEra, List<GenesisOutput> genesisOutputs,
                                  long genesisBlockNumber, UtxoHistoryProjection projection) {
        this.blockDecoder = new YaciBlockDecoder(slotToEpoch, slotToUnixTime, storedEra);
        this.genesisOutputs = List.copyOf(genesisOutputs);
        if (genesisBlockNumber < 0) throw new IllegalArgumentException("genesis block number must be non-negative");
        this.genesisBlockNumber = genesisBlockNumber;
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    @Override
    public BlockSourceContext<UtxoHistoryFact> decode(long blockNumber, CanonicalBlockReference reference, byte[] body) {
        BlockSourceContext<Block> decoded = blockDecoder.decode(blockNumber, reference, body);
        return project(decoded);
    }

    /** Derives UTXO facts from a block already parsed by the shared archive source. */
    public BlockSourceContext<UtxoHistoryFact> project(BlockSourceContext<Block> decoded) {
        Objects.requireNonNull(decoded, "decoded");
        return new BlockSourceContext<>(decoded.blockNumber(), decoded.slot(), decoded.epoch(), decoded.blockTime(),
                decoded.blockHash(), decoded.parentHash(),
                derive(decoded.block(), decoded.slot(), includesGenesis(decoded.blockNumber()), decoded.blockNumber()));
    }

    boolean includesGenesis(long blockNumber) {
        return blockNumber == genesisBlockNumber;
    }

    UtxoHistoryFact derive(Block block) {
        long slot = block.getHeader() == null || block.getHeader().getHeaderBody() == null
                ? 0 : block.getHeader().getHeaderBody().getSlot();
        return derive(block, slot);
    }

    UtxoHistoryFact derive(Block block, long slot) {
        return derive(block, slot, false);
    }

    UtxoHistoryFact derive(Block block, long slot, boolean includeGenesis) {
        return derive(block, slot, includeGenesis, 0);
    }

    private UtxoHistoryFact derive(Block block, long slot, boolean includeGenesis, long blockNumber) {
        int era = block.getEra() == null ? Era.Conway.getValue() : block.getEra().getValue();
        List<UtxoHistoryFact.PointerRegistration> pointerRegistrations = new ArrayList<>();
        List<UtxoHistoryFact.PointerDeregistration> pointerDeregistrations = new ArrayList<>();
        List<UtxoHistoryFact.Address> addresses = new ArrayList<>();
        List<UtxoHistoryFact.Output> outputs = new ArrayList<>();
        List<UtxoHistoryFact.Asset> assets = new ArrayList<>();
        List<UtxoHistoryFact.Input> inputs = new ArrayList<>();
        List<UtxoHistoryFact.TransactionDatum> transactionDatums = new ArrayList<>();
        List<UtxoHistoryFact.TransactionRedeemer> transactionRedeemers = new ArrayList<>();
        Set<String> seenAddresses = new HashSet<>();
        // Per-block memoisation of address decoding. Profiling attributed ~82% of the
        // ADR-039 projection cost to fact derivation, and nearly all of that to decoding the
        // same output addresses repeatedly: each call parses the address, extracts payment
        // and delegation credentials, hashes a key, and re-encodes the whole thing back to
        // bech32. Addresses repeat heavily within a block, and the decode is a pure function
        // of the display string, so caching is semantics-preserving. Scoped to one block, so
        // it is inherently bounded and needs no eviction policy; passed as a parameter rather
        // than held as a field because one decoder instance may serve two worker threads.
        BoundedDecodeCache<AddressInfo> addressCache = new BoundedDecodeCache<>(addressCacheMaxEntries);
        Set<Integer> invalid = block.getInvalidTransactions() == null ? Set.of() : Set.copyOf(block.getInvalidTransactions());
        List<TransactionBody> bodies = block.getTransactionBodies() == null ? List.of() : block.getTransactionBodies();

        boolean includeOutputs = projection.includes(UtxoHistoryProjection.Table.TRANSACTION_OUTPUTS, blockNumber);
        boolean includeAssets = projection.includes(UtxoHistoryProjection.Table.TRANSACTION_OUTPUT_ASSETS, blockNumber);
        boolean includeInputs = projection.includes(UtxoHistoryProjection.Table.TRANSACTION_INPUTS, blockNumber);
        boolean includeDatums = projection.includes(UtxoHistoryProjection.Table.TRANSACTION_DATUMS, blockNumber);
        boolean includeRedeemers = projection.includes(UtxoHistoryProjection.Table.TRANSACTION_REDEEMERS, blockNumber);

        if (includeGenesis && includeOutputs) {
            LinkedHashMap<String, GenesisOutput> unique = new LinkedHashMap<>();
            for (GenesisOutput genesis : genesisOutputs) {
                AddressInfo address = address(genesis.address(), addressCache);
                String outpoint = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(address.fact().rawAddress()));
                GenesisOutput previous = unique.get(outpoint);
                if (previous == null) unique.put(outpoint, genesis);
                else if (!previous.address().equals(genesis.address()) || !previous.originType().equals(genesis.originType())) {
                    throw new ArchiveStoreException("conflicting genesis output " + outpoint);
                } else unique.put(outpoint, new GenesisOutput(previous.address(),
                        previous.amount().add(genesis.amount()), previous.originType()));
            }
            for (GenesisOutput genesis : unique.values()) {
                AddressInfo address = address(genesis.address(), addressCache);
                String addressId = HexUtil.encodeHexString(address.key());
                if (seenAddresses.add(addressId)) addresses.add(address.fact());
                if (includeOutputs) outputs.add(new UtxoHistoryFact.Output(
                        Blake2bUtil.blake2bHash256(address.fact().rawAddress()), 0, -1,
                        genesis.originType(), address.key(), address.paymentCredential(), address.stakeCredential(),
                        exactLong(genesis.amount(), "genesis lovelace"), "none", null, null,
                        null, null, null, false));
            }
        }

        for (int txIndex = 0; txIndex < bodies.size(); txIndex++) {
            TransactionBody tx = bodies.get(txIndex);
            byte[] txHash = hex(tx.getTxHash(), "transaction hash");
            boolean valid = !invalid.contains(txIndex);
            // Keep the small sequential pointer projection current even while
            // address/output rows are disabled, so enabling them later does
            // not require replaying old certificates.
            if (valid && era < Era.Conway.getValue() && tx.getCertificates() != null) {
                for (int certIndex = 0; certIndex < tx.getCertificates().size(); certIndex++) {
                    var certificate = tx.getCertificates().get(certIndex);
                    if (certificate instanceof StakeRegistration registration
                            && registration.getStakeCredential() != null) {
                        var credential = registration.getStakeCredential();
                        pointerRegistrations.add(new UtxoHistoryFact.PointerRegistration(slot, txIndex, certIndex,
                                credential.getType().name().equals("ADDR_KEYHASH") ? "key" : "script",
                                hex(credential.getHash(), "pointer stake credential")));
                    } else if ((certificate instanceof StakeDeregistration
                            || certificate instanceof UnregCert)) {
                        var credential = certificate instanceof StakeDeregistration deregistration
                                ? deregistration.getStakeCredential()
                                : ((UnregCert) certificate).getStakeCredential();
                        if (credential != null) pointerDeregistrations.add(
                                new UtxoHistoryFact.PointerDeregistration(
                                        txIndex, certIndex,
                                        credential.getType().name().equals("ADDR_KEYHASH") ? "key" : "script",
                                        hex(credential.getHash(), "pointer stake credential")));
                    }
                }
            }
            if (includeInputs) {
                addInputs(inputs, txHash, txIndex, "input", tx.getInputs(), valid);
                addInputs(inputs, txHash, txIndex, "collateral", tx.getCollateralInputs(), !valid);
                addInputs(inputs, txHash, txIndex, "reference", tx.getReferenceInputs(), false);
            }
            if (includeOutputs && valid && tx.getOutputs() != null) {
                for (int outputIndex = 0; outputIndex < tx.getOutputs().size(); outputIndex++) {
                    addOutput(addresses, outputs, assets, seenAddresses, addressCache, txHash, txIndex,
                            outputIndex, "regular", false, tx.getOutputs().get(outputIndex),
                            includeOutputs, includeAssets);
                }
            }
            if (includeOutputs && !valid && tx.getCollateralReturn() != null) {
                int outputIndex = tx.getOutputs() == null ? 0 : tx.getOutputs().size();
                addOutput(addresses, outputs, assets, seenAddresses, addressCache, txHash, txIndex,
                        outputIndex, "collateral_return", true, tx.getCollateralReturn(),
                        includeOutputs, includeAssets);
            }
            if (includeDatums || includeRedeemers) {
                addWitnessData(block, txHash, txIndex, transactionDatums, transactionRedeemers,
                        includeDatums, includeRedeemers);
            }
        }
        addressCacheHits.add(addressCache.hits());
        addressCacheMisses.add(addressCache.misses());
        addressCacheSkipped.add(addressCache.admissionsSkipped());

        return new UtxoHistoryFact(era, pointerRegistrations, pointerDeregistrations,
                addresses, outputs, assets, inputs,
                transactionDatums, transactionRedeemers);
    }

    private void addOutput(List<UtxoHistoryFact.Address> addresses, List<UtxoHistoryFact.Output> outputs,
                           List<UtxoHistoryFact.Asset> assets, Set<String> seenAddresses,
                           BoundedDecodeCache<AddressInfo> addressCache,
                           byte[] txHash, int txIndex, int outputIndex, String originType,
                           boolean collateralReturn, TransactionOutput output,
                           boolean includeOutputs, boolean includeAssets) {
        AddressInfo address = address(output.getAddress(), addressCache);
        String addressId = HexUtil.encodeHexString(address.key());
        if (includeOutputs && seenAddresses.add(addressId)) addresses.add(address.fact());
        BigInteger lovelace = BigInteger.ZERO;
        if ((includeOutputs || includeAssets) && output.getAmounts() != null) {
            for (Amount amount : output.getAmounts()) {
                if (amount == null || amount.getQuantity() == null) continue;
                if ("lovelace".equals(amount.getUnit())) lovelace = lovelace.add(amount.getQuantity());
                else if (includeAssets) assets.add(new UtxoHistoryFact.Asset(txHash, outputIndex,
                        hex(amount.getPolicyId(), "asset policy"), assetName(amount), amount.getQuantity()));
            }
        }
        if (!includeOutputs) return;
        byte[] datumHash = nullableHex(output.getDatumHash());
        byte[] inlineDatumCbor = null;
        String datumKind = "none";
        if (output.getInlineDatum() != null && !output.getInlineDatum().isBlank()) {
            inlineDatumCbor = hex(output.getInlineDatum(), "inline datum");
            datumHash = hex(Datum.cborToHash(inlineDatumCbor), "inline datum hash");
            datumKind = "inline";
        } else if (datumHash != null) datumKind = "hash";
        byte[] scriptHash = null;
        String scriptType = null;
        byte[] scriptCbor = null;
        if (output.getScriptRef() != null && !output.getScriptRef().isBlank()) {
            scriptCbor = hex(output.getScriptRef(), "reference script");
            try {
                var script = ReferenceScriptUtil.deserializeScriptRef(scriptCbor);
                scriptHash = script.getScriptHash();
                scriptType = scriptType(script.getScriptType());
            } catch (Exception e) {
                throw new ArchiveStoreException("cannot decode reference script", e);
            }
        }
        outputs.add(new UtxoHistoryFact.Output(txHash, outputIndex, txIndex, originType, address.key(),
                address.paymentCredential(), address.stakeCredential(), exactLong(lovelace, "lovelace"),
                datumKind, datumHash, inlineDatumCbor, scriptHash, scriptType, scriptCbor, collateralReturn));
    }

    private static void addInputs(List<UtxoHistoryFact.Input> sink, byte[] txHash, int txIndex, String role,
                                  Collection<TransactionInput> source, boolean consumes) {
        if (source == null) return;
        int inputIndex = 0;
        for (TransactionInput input : source) {
            sink.add(new UtxoHistoryFact.Input(txHash, txIndex, inputIndex++, role,
                    hex(input.getTransactionId(), "input transaction hash"), input.getIndex(), consumes));
        }
    }

    private static void addWitnessData(Block block, byte[] txHash, int txIndex,
                                       List<UtxoHistoryFact.TransactionDatum> datums,
                                       List<UtxoHistoryFact.TransactionRedeemer> redeemers,
                                       boolean includeDatums, boolean includeRedeemers) {
        if (block.getTransactionWitness() == null || txIndex >= block.getTransactionWitness().size()) return;
        Witnesses witnesses = block.getTransactionWitness().get(txIndex);
        if (witnesses == null) return;
        if (includeDatums && witnesses.getDatums() != null) {
            Set<String> seen = new HashSet<>();
            for (Datum datum : witnesses.getDatums()) {
                if (datum == null || datum.getHash() == null || datum.getCbor() == null) continue;
                byte[] hash = hex(datum.getHash(), "datum hash");
                if (seen.add(HexUtil.encodeHexString(hash))) {
                    datums.add(new UtxoHistoryFact.TransactionDatum(txHash, txIndex, hash,
                            hex(datum.getCbor(), "datum CBOR")));
                }
            }
        }
        if (includeRedeemers && witnesses.getRedeemers() != null) {
            // Pre-Conway redeemers are encoded as a list. The ledger decodes
            // that list with Map.fromList, so a repeated pointer is legal on
            // old chain data and the last value is the semantic redeemer.
            // Preserve that behavior instead of archiving physical duplicates.
            Map<RedeemerKey, UtxoHistoryFact.TransactionRedeemer> semanticRedeemers = new LinkedHashMap<>();
            for (Redeemer redeemer : witnesses.getRedeemers()) {
                if (redeemer == null || redeemer.getTag() == null || redeemer.getCbor() == null
                        || redeemer.getCbor().isBlank() || redeemer.getExUnits() == null
                        || redeemer.getExUnits().getMem() == null || redeemer.getExUnits().getSteps() == null) continue;
                byte[] dataHash = redeemer.getData() == null ? null : nullableHex(redeemer.getData().getHash());
                String purpose = redeemer.getTag().name().toLowerCase(Locale.ROOT);
                var decoded = new UtxoHistoryFact.TransactionRedeemer(txHash, txIndex,
                        purpose, redeemer.getIndex(),
                        hex(redeemer.getCbor(), "redeemer CBOR"), dataHash,
                        redeemer.getExUnits().getMem(), redeemer.getExUnits().getSteps());
                semanticRedeemers.put(new RedeemerKey(purpose, redeemer.getIndex()), decoded);
            }
            redeemers.addAll(semanticRedeemers.values());
        }
    }

    private record RedeemerKey(String purpose, int index) {}

    private static String scriptType(int type) {
        return switch (type) {
            case 0 -> "native";
            case 1 -> "plutus_v1";
            case 2 -> "plutus_v2";
            case 3 -> "plutus_v3";
            default -> throw new ArchiveStoreException("unknown reference script type " + type);
        };
    }

    /**
     * Bound on per-block address memoisation. Zero disables caching, which must produce
     * byte-identical output — that equivalence is asserted by
     * {@code AddressCacheEquivalenceTest}.
     */
    public void setAddressCacheMaxEntries(int maxEntries) {
        if (maxEntries < 0) throw new IllegalArgumentException("maxEntries must not be negative");
        this.addressCacheMaxEntries = maxEntries;
    }

    public int addressCacheMaxEntries() {
        return addressCacheMaxEntries;
    }

    /** Cumulative memoisation counters across every block this decoder has processed. */
    public AddressCacheStats addressCacheStats() {
        return new AddressCacheStats(addressCacheHits.sum(), addressCacheMisses.sum(),
                addressCacheSkipped.sum(), addressCacheMaxEntries);
    }

    /**
     * @param admissionsSkipped lookups that could not be admitted because the per-block bound
     *                          was reached; a persistently non-zero value means the bound is
     *                          too small for this chain's blocks, not that anything is wrong
     */
    public record AddressCacheStats(long hits, long misses, long admissionsSkipped, int maxEntries) {
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }
    }

    private AddressInfo address(String display, BoundedDecodeCache<AddressInfo> cache) {
        AddressInfo cached = cache.get(display);
        if (cached != null) return cached;
        AddressInfo decoded = decodeAddress(display);
        cache.put(display, decoded);
        return decoded;
    }

    private AddressInfo decodeAddress(String display) {
        try {
            byte[] raw;
            try { raw = AddressUtil.addressToBytes(display); }
            catch (Exception e) { raw = HexUtil.decodeHexString(display); }
            Address parsed = new Address(raw);
            byte[] key = addressKeys.key(raw);
            byte[] payment = parsed.getPaymentCredentialHash().orElse(null);
            byte[] stake = parsed.getDelegationCredentialHash().orElse(null);
            String paymentType = parsed.getPaymentCredential().map(c -> c.getType().name().toLowerCase(Locale.ROOT)).orElse(null);
            String stakeType = parsed.getDelegationCredential().map(c -> c.getType().name().toLowerCase(Locale.ROOT)).orElse(null);
            boolean pointerAddress = parsed.getAddressType().name().equalsIgnoreCase("ptr");
            if (pointerAddress) {
                // Some generic address decoders expose the encoded pointer
                // bytes through the delegation-credential accessor. A pointer
                // is not a credential; resolution belongs to the sequential
                // ledger projection below.
                stake = null;
                stakeType = null;
            }
            String stakeReference = pointerAddress ? "pointer"
                    : stake == null ? "none" : "credential";
            Long pointerSlot = null; Integer pointerTx = null; Integer pointerCert = null;
            if (pointerAddress) {
                var pointer = new PointerAddress(raw).getPointer();
                pointerSlot = pointer.getSlot(); pointerTx = pointer.getTxIndex(); pointerCert = pointer.getCertIndex();
            }
            String normalized;
            try { normalized = AddressUtil.bytesToAddress(raw); }
            catch (Exception e) { normalized = display; }
            var fact = new UtxoHistoryFact.Address(key, raw, normalized,
                    parsed.getNetwork() == null ? null : parsed.getNetwork().getNetworkId(),
                    parsed.getAddressType().name().toLowerCase(Locale.ROOT), paymentType, payment,
                    stakeReference, stakeType, stake, pointerSlot, pointerTx, pointerCert);
            return new AddressInfo(key, payment, stake, fact);
        } catch (Exception e) {
            // Byron addresses may not expose Shelley credentials but remain queryable by exact address.
            try {
                byte[] raw = AddressUtil.addressToBytes(display);
                byte[] key = addressKeys.key(raw);
                return new AddressInfo(key, null, null, new UtxoHistoryFact.Address(key, raw, display, null,
                        "byron", null, null, "none", null, null, null, null, null));
            } catch (Exception nested) {
                throw new ArchiveStoreException("cannot decode output address", e);
            }
        }
    }

    private static byte[] assetName(Amount amount) {
        if (amount.getAssetNameBytes() != null) return amount.getAssetNameBytes();
        return amount.getAssetName() == null ? new byte[0] : hex(amount.getAssetName(), "asset name");
    }
    private static byte[] nullableHex(String value) { return value == null || value.isBlank() ? null : hex(value, "hash"); }
    private static byte[] hex(String value, String field) {
        try {
            byte[] decoded = HexUtil.decodeHexString(value);
            if (decoded.length == 0) throw new IllegalArgumentException("empty");
            return decoded;
        } catch (Exception e) { throw new ArchiveStoreException(field + " is not valid hex", e); }
    }
    private static long exactLong(BigInteger value, String field) {
        try { return value.longValueExact(); }
        catch (ArithmeticException e) { throw new ArchiveStoreException(field + " exceeds signed 64-bit schema", e); }
    }
    private record AddressInfo(byte[] key, byte[] paymentCredential, byte[] stakeCredential,
                               UtxoHistoryFact.Address fact) { }

    public record GenesisOutput(String address, BigInteger amount, String originType) {
        public GenesisOutput {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(originType, "originType");
            if (amount.signum() < 0) throw new IllegalArgumentException("negative genesis output");
            if (!originType.equals("genesis_shelley") && !originType.equals("genesis_byron")) {
                throw new IllegalArgumentException("invalid genesis output type");
            }
        }
    }
}
