package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.account.AccountHistoryProvider;
import com.bloxbean.cardano.yano.api.util.AddressKeyUtil;
import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.address.AddressKeyCodec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Compatibility DTO adapter over the ADR-034 typed repositories. */
final class ArchiveAccountHistoryProvider implements AccountHistoryProvider {
    private final HistoryArchiveService service;
    private final AddressKeyCodec addressKeys = new AddressKeyCodec();

    ArchiveAccountHistoryProvider(HistoryArchiveService service) { this.service = service; }
    @Override public boolean isEnabled() { return service.available(); }
    @Override public boolean isHealthy() { return service.available(); }
    @Override public boolean isTxEventsEnabled() { return service.datasetAvailable(ArchiveDatasetId.ACCOUNT_EVENT); }
    @Override public boolean isAddressTxEnabled() { return service.datasetAvailable(ArchiveDatasetId.ADDRESS_TRANSACTION); }
    @Override public boolean isRewardsHistoryEnabled() { return service.datasetAvailable(ArchiveDatasetId.REWARD); }

    @Override
    public List<AddressTxRecord> getAddressTransactions(int scope, String hashHex, int page, int count, String order) {
        String subjectType = switch (scope) {
            case ADDR_SCOPE_ADDRESS -> "address";
            case ADDR_SCOPE_PAYMENT_CRED -> "payment_credential";
            case ADDR_SCOPE_STAKE_CRED -> "stake_credential";
            default -> throw new IllegalArgumentException("unknown address-history scope");
        };
        return query(ArchiveDatasetId.ADDRESS_TRANSACTION, Map.of("subject_type", subjectType,
                        "subject_key", HexUtil.decodeHexString(hashHex)), page, count, order).stream()
                .map(row -> new AddressTxRecord(hex(row, "tx_hash"), number(row, "slot"),
                        number(row, "block_number"), integer(row, "tx_index"))).toList();
    }

    @Override
    public List<AddressTxRecord> getAddressTransactionsForAddress(String address, boolean payment,
                                                                  int page, int count, String order) {
        byte[] key;
        int scope;
        if (payment) {
            key = AddressKeyUtil.paymentCred28(address); scope = ADDR_SCOPE_PAYMENT_CRED;
        } else {
            try { key = addressKeys.key(AddressUtil.addressToBytes(address)); }
            catch (Exception e) {
                try { key = addressKeys.key(HexUtil.decodeHexString(address)); }
                catch (Exception nested) { return List.of(); }
            }
            scope = ADDR_SCOPE_ADDRESS;
        }
        return key == null ? List.of() : getAddressTransactions(scope, HexUtil.encodeHexString(key), page, count, order);
    }

    @Override public List<WithdrawalRecord> getWithdrawals(int type, String hash, int page, int count) {
        return getWithdrawals(type, hash, page, count, "desc");
    }
    @Override public List<WithdrawalRecord> getWithdrawals(int type, String hash, int page, int count, String order) {
        return events(type, hash, "withdrawal", page, count, order).stream().map(row ->
                new WithdrawalRecord(hex(row, "tx_hash"), bigInteger(row, "amount"), number(row, "slot"),
                        number(row, "block_number"), integer(row, "tx_index"))).toList();
    }
    @Override public List<DelegationRecord> getDelegations(int type, String hash, int page, int count) {
        return getDelegations(type, hash, page, count, "desc");
    }
    @Override public List<DelegationRecord> getDelegations(int type, String hash, int page, int count, String order) {
        return events(type, hash, "delegation", page, count, order).stream().map(row ->
                new DelegationRecord(hex(row, "tx_hash"), hexNullable(row, "pool_hash"), number(row, "slot"),
                        number(row, "block_number"), integer(row, "tx_index"), cert(row),
                        Math.toIntExact(number(row, "epoch") + 2))).toList();
    }
    @Override public List<RegistrationRecord> getRegistrations(int type, String hash, int page, int count) {
        return getRegistrations(type, hash, page, count, "desc");
    }
    @Override public List<RegistrationRecord> getRegistrations(int type, String hash, int page, int count, String order) {
        return events(type, hash, List.of("registration", "deregistration"), page, count, order).stream()
                .map(row -> new RegistrationRecord(hex(row, "tx_hash"),
                Objects.toString(row.value("event_type")), bigIntegerNullable(row, "amount", BigInteger.ZERO),
                number(row, "slot"), number(row, "block_number"), integer(row, "tx_index"), cert(row))).toList();
    }
    @Override public List<MirRecord> getMirs(int type, String hash, int page, int count) {
        return getMirs(type, hash, page, count, "desc");
    }
    @Override public List<MirRecord> getMirs(int type, String hash, int page, int count, String order) {
        return events(type, hash, List.of("mir_treasury", "mir_reserves"), page, count, order).stream()
                .map(row -> new MirRecord(hex(row, "tx_hash"),
                Objects.toString(row.value("event_type")).substring(4), bigInteger(row, "amount"),
                Math.toIntExact(number(row, "epoch") + 1), number(row, "slot"),
                number(row, "block_number"), integer(row, "tx_index"), cert(row))).toList();
    }

    @Override
    public List<RewardRecord> getRewards(int type, String hash, int page, int count, String order) {
        return query(ArchiveDatasetId.REWARD, Map.of("stake_credential", HexUtil.decodeHexString(hash)),
                page, count, order).stream().map(row -> new RewardRecord(integer(row, "earned_epoch"),
                bigInteger(row, "amount"), Objects.toString(row.value("reward_type")),
                hexNullable(row, "pool_hash"), number(row, "boundary_slot"))).toList();
    }

    private List<ArchiveRecord> events(int type, String hash, String event, int page, int count, String order) {
        return events(type, hash, List.of(event), page, count, order);
    }

    private List<ArchiveRecord> events(int type, String hash, Collection<String> events,
                                       int page, int count, String order) {
        String credentialType = type == 0 ? "key" : "script";
        return query(ArchiveDatasetId.ACCOUNT_EVENT, Map.of("stake_credential", HexUtil.decodeHexString(hash),
                "stake_credential_type", credentialType, "event_type", List.copyOf(events)), page, count, order);
    }

    private List<ArchiveRecord> query(ArchiveDatasetId dataset, Map<String, Object> filters,
                                      int page, int count, String order) {
        if (page < 1 || count < 1 || count > 100) {
            throw new IllegalArgumentException("history page/count is out of range");
        }
        long offset = Math.multiplyExact((long) page - 1, count);
        long target = Math.addExact(offset, count);
        if (target > 100_000) {
            throw new IllegalArgumentException("history page exceeds the bounded lookup window");
        }
        try (var ignored = service.openQueryLease()) {
            ArchiveBackend backend = service.backend()
                    .orElseThrow(() -> new IllegalStateException("history unavailable"));
            String table = switch (dataset) {
                case ACCOUNT_EVENT -> "account_events";
                case ADDRESS_TRANSACTION -> "address_transactions";
                case REWARD -> "rewards";
                default -> throw new IllegalArgumentException("unsupported compatibility dataset");
            };
            var hotSnapshot = service.openHotSnapshot();
            try (ArchiveReadSession read = backend.openReadSession()) {
                List<ArchiveRecord> hot = hotSnapshot == null ? List.of()
                        : com.bloxbean.cardano.yano.archive.core.hot.HotArchiveRows.read(
                                hotSnapshot, dataset, table, filters);
                ArchiveCoverage coverage = backend.coverage(read, dataset);
                Optional<BlockRange> liveCoverage = service.liveCoverage(dataset);
                if (coverage.completeRanges().isEmpty()) {
                    if (liveCoverage.isEmpty()) throw new IllegalStateException("history coverage is incomplete");
                    Comparator<ArchiveRecord> comparator = comparator(dataset);
                    if (!"asc".equalsIgnoreCase(order)) comparator = comparator.reversed();
                    return hot.stream().sorted(comparator)
                            .skip(offset).limit(count).toList();
                }
                if (liveCoverage.isPresent()) {
                    long coldEnd = coverage.completeRanges().getLast().endInclusive();
                    long liveStart = liveCoverage.orElseThrow().startInclusive();
                    if (liveStart > Math.addExact(coldEnd, 1)) {
                        throw new IllegalStateException("history coverage has a cold/live gap");
                    }
                }
                ArchiveRange range = newRange(dataset, coverage.completeRanges().getFirst().startInclusive(),
                        coverage.completeRanges().getLast().endInclusive());
                Comparator<ArchiveRecord> comparator = comparator(dataset);
                if (!"asc".equalsIgnoreCase(order)) comparator = comparator.reversed();
                ArchivePageCursor.Order selectedOrder = "asc".equalsIgnoreCase(order)
                        ? ArchivePageCursor.Order.ASC : ArchivePageCursor.Order.DESC;
                int coldTarget = Math.toIntExact(Math.min(Integer.MAX_VALUE, target + hot.size()));
                List<ArchiveRecord> merged = new ArrayList<>();
                Optional<ArchivePageCursor> cursor = Optional.empty();
                do {
                    int remaining = coldTarget - merged.size();
                    if (remaining <= 0) break;
                    ArchiveQueryResult<ArchiveRecord> result = backend.repositories().records(dataset).query(read,
                            new ArchiveQuery(range, filters, selectedOrder, Math.min(remaining, 100), cursor));
                    if (!result.complete()) throw new IllegalStateException("history coverage is incomplete");
                    merged.addAll(result.rows());
                    cursor = result.nextCursor();
                } while (cursor.isPresent());
                merged.addAll(hot);
                merged.sort(comparator);
                LinkedHashMap<String, ArchiveRecord> unique = new LinkedHashMap<>();
                for (ArchiveRecord row : merged) unique.putIfAbsent(identity(dataset, row), row);
                return unique.values().stream().skip(offset).limit(count).toList();
            } finally {
                if (hotSnapshot != null) hotSnapshot.close();
            }
        }
    }

    private static Comparator<ArchiveRecord> comparator(ArchiveDatasetId dataset) {
        return switch (dataset) {
            case REWARD -> Comparator.comparingLong((ArchiveRecord row) -> number(row, "earned_epoch"))
                    .thenComparing(row -> Objects.toString(row.value("reward_type")))
                    .thenComparing(row -> Objects.toString(row.value("source_id")));
            case ACCOUNT_EVENT -> Comparator.comparingLong((ArchiveRecord row) -> number(row, "block_number"))
                    .thenComparingLong(row -> number(row, "tx_index"))
                    .thenComparingLong(row -> number(row, "event_index"));
            case ADDRESS_TRANSACTION -> Comparator.comparingLong((ArchiveRecord row) -> number(row, "block_number"))
                    .thenComparingLong(row -> number(row, "tx_index"));
            default -> Comparator.comparingLong(row -> number(row, "block_number"));
        };
    }

    private static String identity(ArchiveDatasetId dataset, ArchiveRecord row) {
        return switch (dataset) {
            case ADDRESS_TRANSACTION -> hex(row, "tx_hash") + ':' + row.value("subject_type") + ':'
                    + HexUtil.encodeHexString((byte[]) row.value("subject_key"));
            case ACCOUNT_EVENT -> hex(row, "tx_hash") + ':' + number(row, "event_index") + ':' + row.value("event_type");
            case REWARD -> hex(row, "stake_credential") + ':' + number(row, "earned_epoch") + ':'
                    + row.value("reward_type") + ':' + row.value("source_id");
            default -> row.toString();
        };
    }

    private static ArchiveRange newRange(ArchiveDatasetId dataset, long start, long end) {
        return dataset.sourceKind() == SourceKind.BLOCK ? new BlockRange(start, end) : new EpochRange(start, end);
    }
    private static int cert(ArchiveRecord row) { return (int) (number(row, "event_index") >>> 32); }
    private static int integer(ArchiveRecord row, String name) { return Math.toIntExact(number(row, name)); }
    private static long number(ArchiveRecord row, String name) { return ((Number) row.value(name)).longValue(); }
    private static BigInteger bigInteger(ArchiveRecord row, String name) { return bigIntegerNullable(row, name, BigInteger.ZERO); }
    private static BigInteger bigIntegerNullable(ArchiveRecord row, String name, BigInteger fallback) {
        Object value = row.value(name);
        if (value == null) return fallback;
        if (value instanceof BigInteger integer) return integer;
        if (value instanceof BigDecimal decimal) return decimal.toBigIntegerExact();
        return BigInteger.valueOf(((Number) value).longValue());
    }
    private static String hex(ArchiveRecord row, String name) { return HexUtil.encodeHexString((byte[]) row.value(name)); }
    private static String hexNullable(ArchiveRecord row, String name) {
        Object value = row.value(name); return value == null ? null : HexUtil.encodeHexString((byte[]) value);
    }
}
