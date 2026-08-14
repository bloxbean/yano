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

    public YaciUtxoHistoryDecoder(LongUnaryOperator slotToEpoch, LongUnaryOperator slotToUnixTime) {
        this.blockDecoder = new YaciBlockDecoder(slotToEpoch, slotToUnixTime);
        this.genesisOutputs = List.of();
        this.genesisBlockNumber = 0;
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
        this.blockDecoder = new YaciBlockDecoder(slotToEpoch, slotToUnixTime, storedEra);
        this.genesisOutputs = List.copyOf(genesisOutputs);
        if (genesisBlockNumber < 0) throw new IllegalArgumentException("genesis block number must be non-negative");
        this.genesisBlockNumber = genesisBlockNumber;
    }

    @Override
    public BlockSourceContext<UtxoHistoryFact> decode(long blockNumber, CanonicalBlockReference reference, byte[] body) {
        BlockSourceContext<Block> decoded = blockDecoder.decode(blockNumber, reference, body);
        return new BlockSourceContext<>(decoded.blockNumber(), decoded.slot(), decoded.epoch(), decoded.blockTime(),
                decoded.blockHash(), decoded.parentHash(),
                derive(decoded.block(), decoded.slot(), includesGenesis(blockNumber)));
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
        int era = block.getEra() == null ? Era.Conway.getValue() : block.getEra().getValue();
        List<UtxoHistoryFact.PointerRegistration> pointerRegistrations = new ArrayList<>();
        List<UtxoHistoryFact.PointerDeregistration> pointerDeregistrations = new ArrayList<>();
        List<UtxoHistoryFact.Address> addresses = new ArrayList<>();
        List<UtxoHistoryFact.Output> outputs = new ArrayList<>();
        List<UtxoHistoryFact.Asset> assets = new ArrayList<>();
        List<UtxoHistoryFact.Input> inputs = new ArrayList<>();
        LinkedHashMap<String, UtxoHistoryFact.Datum> datums = new LinkedHashMap<>();
        LinkedHashMap<String, UtxoHistoryFact.Script> scripts = new LinkedHashMap<>();
        Set<String> seenAddresses = new HashSet<>();
        Set<Integer> invalid = block.getInvalidTransactions() == null ? Set.of() : Set.copyOf(block.getInvalidTransactions());
        List<TransactionBody> bodies = block.getTransactionBodies() == null ? List.of() : block.getTransactionBodies();

        if (includeGenesis) {
            LinkedHashMap<String, GenesisOutput> unique = new LinkedHashMap<>();
            for (GenesisOutput genesis : genesisOutputs) {
                AddressInfo address = address(genesis.address());
                String outpoint = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(address.fact().rawAddress()));
                GenesisOutput previous = unique.get(outpoint);
                if (previous == null) unique.put(outpoint, genesis);
                else if (!previous.address().equals(genesis.address()) || !previous.originType().equals(genesis.originType())) {
                    throw new ArchiveStoreException("conflicting genesis output " + outpoint);
                } else unique.put(outpoint, new GenesisOutput(previous.address(),
                        previous.amount().add(genesis.amount()), previous.originType()));
            }
            for (GenesisOutput genesis : unique.values()) {
                AddressInfo address = address(genesis.address());
                String addressId = HexUtil.encodeHexString(address.key());
                if (seenAddresses.add(addressId)) addresses.add(address.fact());
                outputs.add(new UtxoHistoryFact.Output(
                        Blake2bUtil.blake2bHash256(address.fact().rawAddress()), 0, -1,
                        genesis.originType(), address.key(), address.paymentCredential(), address.stakeCredential(),
                        exactLong(genesis.amount(), "genesis lovelace"), "none", null, null, false));
            }
        }

        for (int txIndex = 0; txIndex < bodies.size(); txIndex++) {
            TransactionBody tx = bodies.get(txIndex);
            byte[] txHash = hex(tx.getTxHash(), "transaction hash");
            boolean valid = !invalid.contains(txIndex);
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
            addInputs(inputs, txHash, txIndex, "input", tx.getInputs(), valid);
            addInputs(inputs, txHash, txIndex, "collateral", tx.getCollateralInputs(), !valid);
            addInputs(inputs, txHash, txIndex, "reference", tx.getReferenceInputs(), false);
            if (valid && tx.getOutputs() != null) {
                for (int outputIndex = 0; outputIndex < tx.getOutputs().size(); outputIndex++) {
                    addOutput(addresses, outputs, assets, datums, scripts, seenAddresses, txHash, txIndex,
                            outputIndex, "regular", false, tx.getOutputs().get(outputIndex));
                }
            }
            if (!valid && tx.getCollateralReturn() != null) {
                int outputIndex = tx.getOutputs() == null ? 0 : tx.getOutputs().size();
                addOutput(addresses, outputs, assets, datums, scripts, seenAddresses, txHash, txIndex,
                        outputIndex, "collateral_return", true, tx.getCollateralReturn());
            }
            addWitnessDatums(block, txIndex, datums);
        }
        return new UtxoHistoryFact(era, pointerRegistrations, pointerDeregistrations,
                addresses, outputs, assets, inputs,
                new ArrayList<>(datums.values()), new ArrayList<>(scripts.values()));
    }

    private void addOutput(List<UtxoHistoryFact.Address> addresses, List<UtxoHistoryFact.Output> outputs,
                           List<UtxoHistoryFact.Asset> assets, Map<String, UtxoHistoryFact.Datum> datums,
                           Map<String, UtxoHistoryFact.Script> scripts, Set<String> seenAddresses,
                           byte[] txHash, int txIndex, int outputIndex, String originType,
                           boolean collateralReturn, TransactionOutput output) {
        AddressInfo address = address(output.getAddress());
        String addressId = HexUtil.encodeHexString(address.key());
        if (seenAddresses.add(addressId)) addresses.add(address.fact());
        BigInteger lovelace = BigInteger.ZERO;
        if (output.getAmounts() != null) {
            for (Amount amount : output.getAmounts()) {
                if (amount == null || amount.getQuantity() == null) continue;
                if ("lovelace".equals(amount.getUnit())) lovelace = lovelace.add(amount.getQuantity());
                else assets.add(new UtxoHistoryFact.Asset(txHash, outputIndex,
                        hex(amount.getPolicyId(), "asset policy"), assetName(amount), amount.getQuantity()));
            }
        }
        byte[] datumHash = nullableHex(output.getDatumHash());
        String datumKind = "none";
        if (output.getInlineDatum() != null && !output.getInlineDatum().isBlank()) {
            byte[] cbor = hex(output.getInlineDatum(), "inline datum");
            datumHash = hex(Datum.cborToHash(cbor), "inline datum hash");
            datums.putIfAbsent(HexUtil.encodeHexString(datumHash), new UtxoHistoryFact.Datum(datumHash, cbor));
            datumKind = "inline";
        } else if (datumHash != null) datumKind = "hash";
        byte[] scriptHash = null;
        if (output.getScriptRef() != null && !output.getScriptRef().isBlank()) {
            byte[] cbor = hex(output.getScriptRef(), "reference script");
            try {
                var script = ReferenceScriptUtil.deserializeScriptRef(cbor);
                scriptHash = script.getScriptHash();
                scripts.putIfAbsent(HexUtil.encodeHexString(scriptHash),
                        new UtxoHistoryFact.Script(scriptHash, Integer.toString(script.getScriptType()), cbor));
            } catch (Exception e) {
                throw new ArchiveStoreException("cannot decode reference script", e);
            }
        }
        outputs.add(new UtxoHistoryFact.Output(txHash, outputIndex, txIndex, originType, address.key(),
                address.paymentCredential(), address.stakeCredential(), exactLong(lovelace, "lovelace"),
                datumKind, datumHash, scriptHash, collateralReturn));
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

    private static void addWitnessDatums(Block block, int txIndex, Map<String, UtxoHistoryFact.Datum> sink) {
        if (block.getTransactionWitness() == null || txIndex >= block.getTransactionWitness().size()) return;
        Witnesses witnesses = block.getTransactionWitness().get(txIndex);
        if (witnesses == null || witnesses.getDatums() == null) return;
        for (Datum datum : witnesses.getDatums()) {
            if (datum == null || datum.getHash() == null || datum.getCbor() == null) continue;
            byte[] hash = hex(datum.getHash(), "datum hash");
            sink.putIfAbsent(HexUtil.encodeHexString(hash),
                    new UtxoHistoryFact.Datum(hash, hex(datum.getCbor(), "datum CBOR")));
        }
    }

    private AddressInfo address(String display) {
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
            String stakeReference = parsed.getAddressType().name().equalsIgnoreCase("ptr") ? "pointer"
                    : stake == null ? "none" : "credential";
            Long pointerSlot = null; Integer pointerTx = null; Integer pointerCert = null;
            if (parsed.getAddressType().name().equalsIgnoreCase("ptr")) {
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
