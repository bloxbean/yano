package com.bloxbean.cardano.yano.archive.api.schema;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.bloxbean.cardano.yano.archive.api.schema.ArchiveValueType.*;

/**
 * Stable logical schema contract shared by both archive engines.
 * DuckLake stores it directly; SQLite may normalize behind writable views.
 */
public final class ArchiveSchemas {
    private static final Map<ArchiveDatasetId, ArchiveDatasetSchema> SCHEMAS = build();

    private ArchiveSchemas() {}

    public static ArchiveDatasetSchema schema(ArchiveDatasetId dataset) {
        return SCHEMAS.get(dataset);
    }

    public static Map<ArchiveDatasetId, ArchiveDatasetSchema> all() {
        return SCHEMAS;
    }

    private static Map<ArchiveDatasetId, ArchiveDatasetSchema> build() {
        var schemas = new EnumMap<ArchiveDatasetId, ArchiveDatasetSchema>(ArchiveDatasetId.class);
        schemas.put(ArchiveDatasetId.TRANSACTION, dataset(ArchiveDatasetId.TRANSACTION, 2,
                table("chain_transaction", pk("tx_hash"),
                        b("tx_hash"), b("block_hash"), l("block_number"), l("slot"), l("epoch"),
                        l("block_time"), i("tx_index"), bool("valid"), ln("fee"), uuid("archive_job_id")),
                order("block_number", "tx_index", "tx_hash")));
        schemas.put(ArchiveDatasetId.ACCOUNT_EVENT, dataset(ArchiveDatasetId.ACCOUNT_EVENT, 3,
                table("account_events", pk("stake_credential", "slot", "tx_index", "event_index", "event_type", "tx_hash"),
                        b("stake_credential"), s("stake_credential_type"), s("stake_address"),
                        s("event_type"), b("tx_hash"),
                        b("block_hash"), l("block_number"), l("slot"), l("epoch"), l("block_time"),
                        i("tx_index"), l("event_index"), bn("pool_hash"), sn("drep_type"), bn("drep_credential"),
                        ln("amount"), uuid("archive_job_id")),
                order("slot", "tx_index", "event_index", "event_type", "tx_hash")));
        schemas.put(ArchiveDatasetId.ADDRESS_TRANSACTION, dataset(ArchiveDatasetId.ADDRESS_TRANSACTION, 3,
                table("address_transactions", pk("subject_type", "subject_key", "tx_hash"),
                        s("subject_type"), b("subject_key"), sn("address"), sn("stake_address"),
                        b("tx_hash"), b("block_hash"),
                        l("block_number"), l("slot"), l("epoch"), l("block_time"), i("tx_index"),
                        i("input_count"), i("output_count"), i("collateral_input_count"),
                        i("collateral_return_count"), uuid("archive_job_id")),
                order("block_number", "tx_index", "tx_hash")));
        schemas.put(ArchiveDatasetId.UTXO_HISTORY, dataset(ArchiveDatasetId.UTXO_HISTORY, 5,
                table("transaction_outputs", pk("tx_hash", "output_index"), b("tx_hash"), i("output_index"),
                        i("tx_index"), s("origin_type"), s("address"), in("network_id"), s("address_type"),
                        sn("payment_credential_type"), bn("payment_credential"), sn("stake_address"),
                        sn("stake_credential_type"), bn("stake_credential"), l("lovelace"),
                        s("datum_kind"), bn("datum_hash"),
                        bn("inline_datum_cbor"), bn("reference_script_hash"), sn("reference_script_type"),
                        bn("reference_script_cbor"), bool("is_collateral_return"), bn("block_hash"),
                        ln("block_number"), ln("slot"), ln("epoch"), ln("block_time"), uuid("archive_job_id")),
                table("transaction_output_assets", pk("tx_hash", "output_index", "policy_id", "asset_name"),
                        b("tx_hash"), i("output_index"), b("policy_id"), b("asset_name"), d("quantity"),
                        ln("block_number"), ln("slot"), ln("epoch"), uuid("archive_job_id")),
                table("transaction_inputs", pk("spending_tx_hash", "input_role", "input_index"),
                        b("spending_tx_hash"), i("spending_tx_index"), i("input_index"), s("input_role"),
                        b("referenced_tx_hash"), i("referenced_output_index"), bool("consumes_output"),
                        b("block_hash"), l("block_number"), l("slot"), l("epoch"), l("block_time"), uuid("archive_job_id")),
                table("transaction_datums", pk("tx_hash", "datum_hash"),
                        b("tx_hash"), i("tx_index"), b("datum_hash"), b("datum_cbor"),
                        b("block_hash"), l("block_number"), l("slot"), l("epoch"), l("block_time"),
                        uuid("archive_job_id")),
                table("transaction_redeemers", pk("tx_hash", "purpose", "redeemer_index"),
                        b("tx_hash"), i("tx_index"), s("purpose"), i("redeemer_index"),
                        b("redeemer_cbor"), bn("redeemer_data_hash"), d("execution_mem"),
                        d("execution_steps"), b("block_hash"), l("block_number"), l("slot"),
                        l("epoch"), l("block_time"), uuid("archive_job_id")),
                order("block_number", "tx_index", "output_index", "tx_hash")));
        schemas.put(ArchiveDatasetId.REWARD, dataset(ArchiveDatasetId.REWARD, 4,
                table("rewards", pk("stake_credential", "earned_epoch", "reward_type", "source_id"),
                        b("stake_credential"), s("stake_credential_type"), s("stake_address"),
                        bn("pool_hash"), s("reward_type"),
                        l("earned_epoch"), l("spendable_epoch"), l("amount"), s("source_id"),
                        b("boundary_block_hash"), l("boundary_block_number"), l("boundary_slot"),
                        l("boundary_block_time"), uuid("archive_job_id")),
                order("earned_epoch", "stake_credential", "reward_type", "source_id")));
        schemas.put(ArchiveDatasetId.EPOCH_STAKE, dataset(ArchiveDatasetId.EPOCH_STAKE, 2,
                table("epoch_stakes", pk("epoch", "stake_credential"), l("epoch"), s("stake_credential_type"),
                        b("stake_credential"), s("stake_address"), bn("pool_hash"), l("amount"), b("boundary_block_hash"),
                        l("boundary_block_number"), l("boundary_slot"), l("boundary_block_time"),
                        s("source_state_version"), uuid("archive_job_id")),
                order("epoch", "stake_credential")));
        schemas.put(ArchiveDatasetId.DREP_DISTRIBUTION, dataset(ArchiveDatasetId.DREP_DISTRIBUTION, 2,
                table("drep_distributions", pk("epoch", "drep_type", "drep_credential"), l("epoch"),
                        s("drep_type"), bn("drep_credential"), l("amount"), ln("stored_expiry"),
                        l("dormant_epochs"), ln("effective_expiry"), bool("active"), b("boundary_block_hash"),
                        l("boundary_block_number"), l("boundary_slot"), l("boundary_block_time"),
                        s("source_state_version"), uuid("archive_job_id")),
                order("epoch", "drep_type", "drep_credential")));
        schemas.put(ArchiveDatasetId.ADA_POT, dataset(ArchiveDatasetId.ADA_POT,
                table("ada_pots", pk("epoch"), l("epoch"), l("treasury"), l("reserves"), l("deposits"),
                        l("fees"), l("distributed"), l("undistributed"), l("rewards_pot"),
                        l("pool_rewards_pot"), b("boundary_block_hash"), l("boundary_block_number"),
                        l("boundary_slot"), l("boundary_block_time"), s("source_state_version"), uuid("archive_job_id")),
                order("epoch")));
        schemas.put(ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS, dataset(ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS,
                table("governance_proposal_statuses", pk("epoch", "tx_hash", "governance_action_index", "observation_phase"),
                        l("epoch"), b("tx_hash"), i("governance_action_index"), s("action_type"),
                        s("observation_phase"), s("status_code"), sn("decision_reason"), l("deposit"),
                        b("return_address"), l("submitted_epoch"), l("expires_after_epoch"),
                        b("boundary_block_hash"), l("boundary_block_number"), l("boundary_slot"),
                        l("boundary_block_time"), s("source_state_version"), uuid("archive_job_id")),
                order("epoch", "tx_hash", "governance_action_index", "observation_phase")));
        return Map.copyOf(schemas);
    }

    private static ArchiveDatasetSchema dataset(ArchiveDatasetId id, ArchiveTableSchema table,
                                                List<String> order) {
        return dataset(id, 1, table, order);
    }

    private static ArchiveDatasetSchema dataset(ArchiveDatasetId id, int projectionVersion,
                                                ArchiveTableSchema table, List<String> order) {
        return new ArchiveDatasetSchema(id, projectionVersion, List.of(table), order);
    }

    private static ArchiveDatasetSchema dataset(ArchiveDatasetId id, int projectionVersion,
                                                ArchiveTableSchema t1, ArchiveTableSchema t2,
                                                ArchiveTableSchema t3, ArchiveTableSchema t4,
                                                ArchiveTableSchema t5, List<String> order) {
        return new ArchiveDatasetSchema(id, projectionVersion, List.of(t1, t2, t3, t4, t5), order);
    }

    private static ArchiveDatasetSchema dataset(ArchiveDatasetId id, ArchiveTableSchema t1,
                                                ArchiveTableSchema t2, ArchiveTableSchema t3,
                                                ArchiveTableSchema t4, ArchiveTableSchema t5,
                                                ArchiveTableSchema t6, List<String> order) {
        return dataset(id, 1, t1, t2, t3, t4, t5, t6, order);
    }

    private static ArchiveDatasetSchema dataset(ArchiveDatasetId id, int projectionVersion,
                                                ArchiveTableSchema t1, ArchiveTableSchema t2,
                                                ArchiveTableSchema t3, ArchiveTableSchema t4,
                                                ArchiveTableSchema t5, ArchiveTableSchema t6,
                                                List<String> order) {
        return new ArchiveDatasetSchema(id, projectionVersion, List.of(t1, t2, t3, t4, t5, t6), order);
    }

    private static ArchiveTableSchema table(String name, List<String> pk, ArchiveColumn... columns) {
        return new ArchiveTableSchema(name, List.of(columns), pk);
    }

    private static List<String> pk(String... names) { return List.of(names); }
    private static List<String> order(String... names) { return List.of(names); }
    private static ArchiveColumn b(String n) { return new ArchiveColumn(n, BINARY, false); }
    private static ArchiveColumn bn(String n) { return new ArchiveColumn(n, BINARY, true); }
    private static ArchiveColumn s(String n) { return new ArchiveColumn(n, TEXT, false); }
    private static ArchiveColumn sn(String n) { return new ArchiveColumn(n, TEXT, true); }
    private static ArchiveColumn bool(String n) { return new ArchiveColumn(n, BOOLEAN, false); }
    private static ArchiveColumn i(String n) { return new ArchiveColumn(n, INT32, false); }
    private static ArchiveColumn in(String n) { return new ArchiveColumn(n, INT32, true); }
    private static ArchiveColumn l(String n) { return new ArchiveColumn(n, INT64, false); }
    private static ArchiveColumn ln(String n) { return new ArchiveColumn(n, INT64, true); }
    private static ArchiveColumn d(String n) { return new ArchiveColumn(n, DECIMAL_38, false); }
    private static ArchiveColumn uuid(String n) { return new ArchiveColumn(n, UUID, false); }
}
