import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.ledgerstate.AccountStateCborCodec;
import com.bloxbean.cardano.yano.ledgerstate.AccountStateCfNames;
import com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore;
import com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yano.runtime.config.DefaultEpochParamProvider;
import com.bloxbean.cardano.yano.runtime.config.NetworkGenesisConfig;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DrepChainstateDump {
    private record DelegatorRow(int credType, String credHash, BigInteger reward, BigInteger deposit, String pool) {}

    public static void main(String[] args) throws Exception {
        String chainstate = args.length > 0 ? args[0] : "./chainstate-preview";
        String drepHashA = args.length > 1 ? args[1] : "1b0659556d778135e189997b26aa5db7ac7aa3bc9a3d6dd2768be8f1";
        String drepHashB = args.length > 2 ? args[2] : "9b239a50a537c0e366ffe6e0577ef462cd80be375e8f1c654771e3c2";
        long inspectSlot = args.length > 3 ? Long.parseLong(args[3]) : 63454827L;
        String[] probeCreds = {
                "cde00dff51f163f4c5dc98137f3e23d28a2e0b27d9665920877e6e81",
                "a1d8a3a34df68318ec0895e5154e4d1a63b48ba97c9b726f1c01d673",
                "ee3f333b37909e7ff5094ac62ca6f7881fba93e83d29226fadcdb439",
                "0a0478096d694283fcb7ce9f7af1c4f37d3ce7741ec1722160bc7087"
        };

        var genesisDir = resolveGenesisDir();
        var genesisConfig = NetworkGenesisConfig.load(
                genesisDir.resolve("shelley-genesis.json").toString(),
                genesisDir.resolve("byron-genesis.json").toString(),
                null,
                genesisDir.resolve("conway-genesis.json").toString()
        );
        long firstNonByronSlot = DefaultEpochParamProvider.resolveFirstNonByronSlot(
                genesisConfig.getNetworkMagic(), genesisConfig.hasByronGenesis());
        var paramProvider = DefaultEpochParamProvider.fromNetworkGenesisConfig(genesisConfig, firstNonByronSlot);

        RocksDB.loadLibrary();
        String[] cfNames = {
                "default",
                "blocks",
                "headers",
                "number_by_slot",
                "slot_by_number",
                "slot_to_hash",
                "metadata",
                "ebb_by_slot0",
                "utxo_unspent",
                "utxo_spent",
                "utxo_addr",
                "utxo_block_delta",
                "utxo_meta",
                "script_ref",
                AccountStateCfNames.ACCT_STATE,
                AccountStateCfNames.ACCT_DELTA,
                AccountStateCfNames.ACCT_BOUNDARY_DELTA,
                AccountStateCfNames.EPOCH_DELEG_SNAPSHOT,
                AccountStateCfNames.EPOCH_PARAMS
        };
        List<ColumnFamilyDescriptor> cfDescriptors = List.of(
                new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
                new ColumnFamilyDescriptor("blocks".getBytes()),
                new ColumnFamilyDescriptor("headers".getBytes()),
                new ColumnFamilyDescriptor("number_by_slot".getBytes()),
                new ColumnFamilyDescriptor("slot_by_number".getBytes()),
                new ColumnFamilyDescriptor("slot_to_hash".getBytes()),
                new ColumnFamilyDescriptor("metadata".getBytes()),
                new ColumnFamilyDescriptor("ebb_by_slot0".getBytes()),
                new ColumnFamilyDescriptor("utxo_unspent".getBytes()),
                new ColumnFamilyDescriptor("utxo_spent".getBytes()),
                new ColumnFamilyDescriptor("utxo_addr".getBytes()),
                new ColumnFamilyDescriptor("utxo_block_delta".getBytes()),
                new ColumnFamilyDescriptor("utxo_meta".getBytes()),
                new ColumnFamilyDescriptor("script_ref".getBytes()),
                new ColumnFamilyDescriptor(AccountStateCfNames.ACCT_STATE.getBytes()),
                new ColumnFamilyDescriptor(AccountStateCfNames.ACCT_DELTA.getBytes()),
                new ColumnFamilyDescriptor(AccountStateCfNames.ACCT_BOUNDARY_DELTA.getBytes()),
                new ColumnFamilyDescriptor(AccountStateCfNames.EPOCH_DELEG_SNAPSHOT.getBytes()),
                new ColumnFamilyDescriptor(AccountStateCfNames.EPOCH_PARAMS.getBytes())
        );
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        try (RocksDB db = RocksDB.openReadOnly(chainstate, cfDescriptors, handles)) {
            Map<String, ColumnFamilyHandle> cfByName = new HashMap<>();
            for (int i = 0; i < cfNames.length; i++) {
                cfByName.put(cfNames[i], handles.get(i));
            }
            ColumnFamilyHandle cfState = handles.get(14);
            ColumnFamilyHandle cfBlocks = handles.get(1);
            ColumnFamilyHandle cfNumberBySlot = handles.get(3);
            ColumnFamilyHandle cfSlotByNumber = handles.get(4);
            ColumnFamilyHandle cfSlotToHash = handles.get(5);

            var store = new DefaultAccountStateStore(
                    db,
                    name -> {
                        ColumnFamilyHandle handle = cfByName.get(name);
                        if (handle == null) throw new IllegalArgumentException("Missing CF: " + name);
                        return handle;
                    },
                    LoggerFactory.getLogger("DrepChainstateDump"),
                    true,
                    paramProvider
            );
            var governanceStore = new GovernanceStateStore(db, cfState);

            dumpDrep(governanceStore, 0, drepHashA);
            dumpDrep(governanceStore, 0, drepHashB);

            for (String credHash : probeCreds) {
                dumpCredential(store, 0, credHash);
            }

            dumpDelegatorsForDrep(db, cfState, store, 0, drepHashA);
            dumpDelegatorsForDrep(db, cfState, store, 0, drepHashB);
            dumpBlockAtSlot(db, cfBlocks, cfNumberBySlot, cfSlotByNumber, cfSlotToHash, inspectSlot);
        } finally {
            for (ColumnFamilyHandle handle : handles) {
                try {
                    handle.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void dumpBlockAtSlot(RocksDB db, ColumnFamilyHandle cfBlocks,
                                        ColumnFamilyHandle cfNumberBySlot,
                                        ColumnFamilyHandle cfSlotByNumber,
                                        ColumnFamilyHandle cfSlotToHash,
                                        long slot) throws Exception {
        byte[] blockNoBytes = db.get(cfNumberBySlot, java.nio.ByteBuffer.allocate(8)
                .order(java.nio.ByteOrder.BIG_ENDIAN).putLong(slot).array());
        if (blockNoBytes == null) {
            System.out.println("BLOCK slot=" + slot + " -> none");
            return;
        }
        long blockNo = java.nio.ByteBuffer.wrap(blockNoBytes).order(java.nio.ByteOrder.BIG_ENDIAN).getLong();
        byte[] slotBytes = db.get(cfSlotByNumber, java.nio.ByteBuffer.allocate(8)
                .order(java.nio.ByteOrder.BIG_ENDIAN).putLong(blockNo).array());
        if (slotBytes == null) {
            System.out.println("BLOCK slot=" + slot + " number=" + blockNo + " -> no slotByNumber");
            return;
        }
        byte[] blockHash = db.get(cfSlotToHash, slotBytes);
        if (blockHash == null) {
            System.out.println("BLOCK slot=" + slot + " number=" + blockNo + " -> no blockHash");
            return;
        }
        byte[] blockCbor = db.get(cfBlocks, blockHash);
        if (blockCbor == null) {
            System.out.println("BLOCK slot=" + slot + " number=" + blockNo + " -> no body");
            return;
        }

        Block block = BlockSerializer.INSTANCE.deserialize(blockCbor);
        System.out.println("BLOCK slot=" + slot + " number=" + blockNo + " txs=" + block.getTransactionBodies().size());
        List<TransactionBody> txs = block.getTransactionBodies();
        for (int txIdx = 0; txIdx < txs.size(); txIdx++) {
            TransactionBody tx = txs.get(txIdx);
            List<Certificate> certs = tx.getCertificates();
            if (certs == null || certs.isEmpty()) continue;
            System.out.println("  txIdx=" + txIdx + " txHash=" + tx.getTxHash() + " certs=" + certs.size());
            for (int certIdx = 0; certIdx < certs.size(); certIdx++) {
                System.out.println("    certIdx=" + certIdx + " " + certs.get(certIdx));
            }
        }
    }

    private static void dumpDrep(GovernanceStateStore governanceStore, int credType, String drepHash) throws Exception {
        var state = governanceStore.getDRepState(credType, drepHash).orElse(null);
        System.out.println("DREP " + credType + ":" + drepHash + " -> " + state);
    }

    private static void dumpCredential(DefaultAccountStateStore store, int credType, String credHash) {
        boolean registered = store.isStakeCredentialRegistered(credType, credHash);
        BigInteger reward = store.getRewardBalance(credType, credHash).orElse(null);
        BigInteger deposit = store.getStakeDeposit(credType, credHash).orElse(null);
        String pool = store.getDelegatedPool(credType, credHash).orElse(null);
        LedgerStateProvider.DRepDelegation drep = store.getDRepDelegation(credType, credHash).orElse(null);
        Long regSlot = readAcctRegSlot(store, credType, credHash);
        System.out.println("CRED " + credType + ":" + credHash
                + " registered=" + registered
                + " reward=" + reward
                + " deposit=" + deposit
                + " regSlot=" + regSlot
                + " pool=" + pool
                + " drep=" + (drep == null ? "null" : (drep.drepType() + ":" + drep.hash())));
    }

    private static void dumpDelegatorsForDrep(RocksDB db, ColumnFamilyHandle cfState,
                                              DefaultAccountStateStore store,
                                              int drepType, String drepHash) throws Exception {
        byte[] seek = new byte[]{DefaultAccountStateStore.PREFIX_DREP_DELEG};
        List<DelegatorRow> rows = new ArrayList<>();

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seek);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != DefaultAccountStateStore.PREFIX_DREP_DELEG) break;

                int credType = key[1] & 0xff;
                String credHash = hex(key, 2, key.length);
                var deleg = AccountStateCborCodec.decodeDRepDelegation(it.value());
                if (deleg.drepType() == drepType && drepHash.equals(deleg.drepHash())) {
                    rows.add(new DelegatorRow(
                            credType,
                            credHash,
                            store.getRewardBalance(credType, credHash).orElse(BigInteger.ZERO),
                            store.getStakeDeposit(credType, credHash).orElse(BigInteger.ZERO),
                            store.getDelegatedPool(credType, credHash).orElse(null)
                    ));
                }
                it.next();
            }
        }

        rows.sort(Comparator.comparing(DelegatorRow::reward).reversed());
        BigInteger totalReward = rows.stream().map(DelegatorRow::reward).reduce(BigInteger.ZERO, BigInteger::add);

        System.out.println("DELEGATORS for " + drepType + ":" + drepHash + " count=" + rows.size() + " totalReward=" + totalReward);
        for (DelegatorRow row : rows) {
            System.out.println("  " + row.credType + ":" + row.credHash
                    + " reward=" + row.reward
                    + " deposit=" + row.deposit
                    + " pool=" + row.pool);
        }
    }

    private static Path resolveGenesisDir() {
        Path a = Path.of("app/config/network/preview");
        if (Files.isDirectory(a)) return a;
        Path b = Path.of("./config/network/preview");
        if (Files.isDirectory(b)) return b;
        throw new IllegalStateException("preview genesis dir not found");
    }

    private static Long readAcctRegSlot(DefaultAccountStateStore store, int credType, String credHash) {
        try {
            var dbField = DefaultAccountStateStore.class.getDeclaredField("db");
            dbField.setAccessible(true);
            RocksDB db = (RocksDB) dbField.get(store);
            var cfStateField = DefaultAccountStateStore.class.getDeclaredField("cfState");
            cfStateField.setAccessible(true);
            ColumnFamilyHandle cfState = (ColumnFamilyHandle) cfStateField.get(store);
            byte[] key = acctRegSlotKey(credType, credHash);
            byte[] val = db.get(cfState, key);
            if (val == null || val.length != 8) return null;
            return java.nio.ByteBuffer.wrap(val).order(java.nio.ByteOrder.BIG_ENDIAN).getLong();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] acctRegSlotKey(int credType, String credHash) {
        byte[] hash = hexToBytes(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = 0x14;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static String hex(byte[] bytes, int from, int to) {
        StringBuilder sb = new StringBuilder((to - from) * 2);
        for (int i = from; i < to; i++) {
            sb.append(Character.forDigit((bytes[i] >>> 4) & 0xf, 16));
            sb.append(Character.forDigit(bytes[i] & 0xf, 16));
        }
        return sb.toString();
    }
}
