package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.*;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.core.dataset.*;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.function.LongUnaryOperator;

/** Pure canonical-block decoder for resolver-independent block datasets. */
public final class YaciBlockArchiveDecoder implements CanonicalBlockDecoder<ArchiveBlockFacts> {
    private final LongUnaryOperator slotToEpoch;
    private final LongUnaryOperator slotToUnixTime;

    public YaciBlockArchiveDecoder(LongUnaryOperator slotToEpoch, LongUnaryOperator slotToUnixTime) {
        this.slotToEpoch = Objects.requireNonNull(slotToEpoch, "slotToEpoch");
        this.slotToUnixTime = Objects.requireNonNull(slotToUnixTime, "slotToUnixTime");
    }

    @Override
    public BlockSourceContext<ArchiveBlockFacts> decode(long blockNumber,
                                                        CanonicalBlockReference reference,
                                                        byte[] body) {
        try {
            Block block = BlockSerializer.INSTANCE.deserialize(body);
            return decodeBlock(blockNumber, reference, block);
        } catch (ArchiveStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot decode canonical block " + blockNumber, e);
        }
    }

    BlockSourceContext<ArchiveBlockFacts> decodeBlock(long blockNumber,
                                                      CanonicalBlockReference reference,
                                                      Block block) {
        try {
            if (block == null || block.getHeader() == null || block.getHeader().getHeaderBody() == null) {
                throw new ArchiveStoreException("canonical block body has no header");
            }
            long slot = block.getHeader().getHeaderBody().getSlot();
            byte[] parent = decodeNullable(block.getHeader().getHeaderBody().getPrevHash());
            Set<Integer> invalid = block.getInvalidTransactions() == null
                    ? Set.of() : Set.copyOf(block.getInvalidTransactions());
            List<TransactionFact> transactions = new ArrayList<>();
            List<AccountEventFact> accountEvents = new ArrayList<>();
            List<TransactionBody> bodies = block.getTransactionBodies() == null
                    ? List.of() : block.getTransactionBodies();
            long epoch = slotToEpoch.applyAsLong(slot);
            for (int txIndex = 0; txIndex < bodies.size(); txIndex++) {
                TransactionBody tx = bodies.get(txIndex);
                byte[] txHash = requiredHash(tx.getTxHash(), "transaction hash");
                boolean valid = !invalid.contains(txIndex);
                transactions.add(new TransactionFact(txHash, txIndex, valid, exactLong(tx.getFee(), "fee")));
                if (valid) deriveAccountEvents(tx, txHash, txIndex, epoch, accountEvents);
            }
            return new BlockSourceContext<>(blockNumber, slot, epoch,
                    Instant.ofEpochSecond(slotToUnixTime.applyAsLong(slot)), reference.blockHash(), parent,
                    new ArchiveBlockFacts(transactions, accountEvents));
        } catch (ArchiveStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot decode canonical block " + blockNumber, e);
        }
    }

    private static void deriveAccountEvents(TransactionBody tx, byte[] txHash, int txIndex, long epoch,
                                            List<AccountEventFact> sink) {
        int eventIndex = 0;
        if (tx.getWithdrawals() != null) {
            for (var withdrawal : tx.getWithdrawals().entrySet()) {
                Credential credential = rewardCredential(withdrawal.getKey());
                if (credential != null && withdrawal.getValue() != null && withdrawal.getValue().signum() > 0) {
                    sink.add(event(credential, "withdrawal", txHash, txIndex, eventIndex,
                            null, null, exactLong(withdrawal.getValue(), "withdrawal")));
                }
                eventIndex++;
            }
        }
        if (tx.getCertificates() == null) return;
        for (int certificateIndex = 0; certificateIndex < tx.getCertificates().size(); certificateIndex++) {
            Certificate certificate = tx.getCertificates().get(certificateIndex);
            long index = ((long) certificateIndex) << 32;
            switch (certificate) {
                case StakeRegistration value -> addRegistration(sink, value.getStakeCredential(), "registration",
                        txHash, txIndex, index, null);
                case RegCert value -> addRegistration(sink, value.getStakeCredential(), "registration",
                        txHash, txIndex, index, value.getCoin());
                case StakeDeregistration value -> addRegistration(sink, value.getStakeCredential(), "deregistration",
                        txHash, txIndex, index, BigInteger.ZERO);
                case UnregCert value -> addRegistration(sink, value.getStakeCredential(), "deregistration",
                        txHash, txIndex, index, value.getCoin());
                case StakeDelegation value -> addDelegation(sink, value.getStakeCredential(),
                        value.getStakePoolId() == null ? null : value.getStakePoolId().getPoolKeyHash(),
                        txHash, txIndex, index);
                case StakeVoteDelegCert value -> addDelegation(sink, value.getStakeCredential(),
                        value.getPoolKeyHash(), txHash, txIndex, index);
                case StakeRegDelegCert value -> {
                    addRegistration(sink, value.getStakeCredential(), "registration", txHash, txIndex, index,
                            value.getCoin());
                    addDelegation(sink, value.getStakeCredential(), value.getPoolKeyHash(),
                            txHash, txIndex, index + 1);
                }
                case StakeVoteRegDelegCert value -> {
                    addRegistration(sink, value.getStakeCredential(), "registration", txHash, txIndex, index,
                            value.getCoin());
                    addDelegation(sink, value.getStakeCredential(), value.getPoolKeyHash(),
                            txHash, txIndex, index + 1);
                }
                case MoveInstataneous value -> addMir(sink, value, txHash, txIndex, index, epoch);
                default -> { }
            }
        }
    }

    private static void addRegistration(List<AccountEventFact> sink, StakeCredential value, String type,
                                        byte[] txHash, int txIndex, long eventIndex, BigInteger amount) {
        Credential credential = credential(value);
        if (credential != null) sink.add(event(credential, type, txHash, txIndex, eventIndex,
                null, null, amount == null ? null : exactLong(amount, type)));
    }

    private static void addDelegation(List<AccountEventFact> sink, StakeCredential value, String poolHash,
                                      byte[] txHash, int txIndex, long eventIndex) {
        Credential credential = credential(value);
        if (credential != null && poolHash != null && !poolHash.isBlank()) {
            sink.add(event(credential, "delegation", txHash, txIndex, eventIndex,
                    requiredHash(poolHash, "pool hash"), null, null));
        }
    }

    private static void addMir(List<AccountEventFact> sink, MoveInstataneous value, byte[] txHash,
                               int txIndex, long baseIndex, long epoch) {
        if (value.getStakeCredentialCoinMap() == null) return;
        int item = 0;
        for (var entry : value.getStakeCredentialCoinMap().entrySet()) {
            Credential credential = credential(entry.getKey());
            BigInteger amount = entry.getValue();
            if (credential != null && amount != null && amount.signum() > 0) {
                String type = value.isTreasury() ? "mir_treasury" : "mir_reserves";
                // Epoch is retained in the enclosing block columns. The event
                // index is unbounded and collision-free across certificate rows.
                sink.add(event(credential, type, txHash, txIndex, baseIndex + item,
                        null, null, exactLong(amount, "MIR amount")));
            }
            item++;
        }
    }

    private static AccountEventFact event(Credential credential, String type, byte[] txHash,
                                          int txIndex, long eventIndex, byte[] poolHash,
                                          byte[] drepCredential, Long amount) {
        return new AccountEventFact(credential.hash(), credential.type(), type, txHash, txIndex,
                eventIndex, poolHash, drepCredential, amount);
    }

    private static Credential credential(StakeCredential credential) {
        if (credential == null || credential.getHash() == null || credential.getHash().isBlank()) return null;
        return new Credential(credential.getType().name().toLowerCase(Locale.ROOT),
                requiredHash(credential.getHash(), "stake credential"));
    }

    private static Credential rewardCredential(String rewardAddressHex) {
        try {
            byte[] address = HexUtil.decodeHexString(rewardAddressHex);
            if (address.length != 29) return null;
            return new Credential((address[0] & 0x10) == 0 ? "key" : "script",
                    Arrays.copyOfRange(address, 1, 29));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] requiredHash(String hex, String field) {
        try {
            byte[] value = HexUtil.decodeHexString(hex);
            if (value.length == 0) throw new IllegalArgumentException();
            return value;
        } catch (Exception e) {
            throw new ArchiveStoreException(field + " is not valid hex", e);
        }
    }

    private static byte[] decodeNullable(String hex) {
        return hex == null || hex.isBlank() ? new byte[0] : requiredHash(hex, "parent hash");
    }

    private static long exactLong(BigInteger value, String field) {
        if (value == null) return 0;
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new ArchiveStoreException(field + " exceeds signed 64-bit schema", e);
        }
    }

    private record Credential(String type, byte[] hash) { }
}
