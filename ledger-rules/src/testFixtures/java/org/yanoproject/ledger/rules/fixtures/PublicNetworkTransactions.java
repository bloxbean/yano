package org.yanoproject.ledger.rules.fixtures;

import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Real preprod and preview transactions for regression tests (ADR-056 Phase 7c), in
 * {@code org/yanoproject/ledger/rules/public-network/}: full transaction CBOR as {@code <network>-<tx id>.hex}, and
 * chain-valid transactions with their ledger state as shadow-sync bundles, {@code <network>-<tx id>.json}.
 *
 * <ul>
 *   <li>{@link #PREPROD_SAME_BLOCK_DATUM_PRODUCER}: from Koios {@code tx_cbor}. Output 0 has an inline datum whose map
 *       keys are not in canonical order; a later transaction of the same block spends it with a PlutusV2 script.</li>
 *   <li>{@link #PREVIEW_INDEFINITE_POOL_OWNERS}: from the shadow-sync dump. A pool registration whose owners and relays
 *       are indefinite-length arrays.</li>
 *   <li>{@link #PHASE2_CASES}: chain-valid transactions with Plutus scripts that shadow sync reported. Each bundle is
 *       the finding's shadow-sync dump (the transaction's own bytes and every ledger-view read the engine made), with
 *       each recorded UTxO replaced by the chain's exact output and inline-datum bytes, taken from the producing
 *       transaction's CBOR on Koios ({@code tx_cbor}). Each must validate under every phase-2 evaluator.</li>
 *   <li>{@link #PREPROD_INDEFINITE_ASSET_MAP_OUTPUT}: the shadow-sync dump, unchanged (its transaction and both
 *       recorded UTxOs are byte-identical to the chain's, Koios {@code tx_cbor}). Output 1's value has a policy with 324
 *       assets: 5001 bytes as encoded (definite-length map head), 5000 as Haskell measures it (indefinite-length map
 *       above 23 entries), with {@code maxValSize} 5000.</li>
 * </ul>
 */
public final class PublicNetworkTransactions {

    /** Preprod, block 2634522, slot 69176184. */
    public static final String PREPROD_SAME_BLOCK_DATUM_PRODUCER =
            "preprod-1fc4d810bf7a14929c044c53d1186dc9444d58d4bb5d66403375139cd48210bc";
    /** Preview, block 2527148, slot 60896134. */
    public static final String PREVIEW_INDEFINITE_POOL_OWNERS =
            "preview-1c09afd80edba3e530fa48fa34f1bc0b2c7999b7e0c76bda2d644444c53e1032";
    /** Preprod, block 4990228, slot 129586448, protocol version 11, the block's first transaction. */
    public static final String PREPROD_INDEFINITE_ASSET_MAP_OUTPUT =
            "preprod-96ae78f724a27b0d76c3d6a861857af3a644de971fe0c7fcbefe4e45811e5687";

    /**
     * A chain-valid transaction and what it exercises.
     *
     * @param name        {@code <network>-<tx id>}
     * @param description what it exercises
     */
    public record Phase2Case(String name, String description) {

        /** @return the shadow-sync bundle: the transaction and the ledger-view reads it needs */
        public ShadowDumpBundle bundle() {
            return PublicNetworkTransactions.bundle(name);
        }

        public String txId() {
            return PublicNetworkTransactions.txId(name);
        }

        @Override
        public String toString() {
            return name + ": " + description;
        }
    }

    /** Chain-valid transactions whose Plutus scripts an evaluator once failed, or could not run. */
    public static final List<Phase2Case> PHASE2_CASES = List.of(
            // Block-local inline datums (the overlay kept no bytes; CCL's canonical re-encoding reorders map keys).
            new Phase2Case("preprod-4cad62782c450c1f82de7bb61354143bed695bc412bfc77419f0957564c57e94",
                    "PV 9, PlutusV2 d4156c1a spends a same-block datum"),
            new Phase2Case("preview-f896a7ee1658678091ec2807e17372ad6b1e7c1f0c48994baa32b501a16528f2",
                    "PV 10, PlutusV2 9e9dfb8c spends a same-block datum"),
            // Integers in [2^63, 2^64).
            new Phase2Case("preprod-8e4b1ced6f994c9a4ba2dad6a9f5e70545fe111f011caa86e7ba9ff1fa81805a",
                    "PV 9, token quantity 14999999995627669111 in an output and an input"),
            new Phase2Case("preprod-b35f5500b64e95520b04314f9a2a8a943af8e08963255066b8231b08555da3a5",
                    "PV 10, token quantity 11190585375857514048"),
            new Phase2Case("preprod-c0c3e628da6d12af6dc18a9d05028b146baddaf5c49da52e410b16fe778f3302",
                    "PV 9, metadatum integer 10^19"),
            new Phase2Case("preprod-2edd684fb762fb4cc464367aa5a01b5e129e202e41b68f755a4e28d9ea293802",
                    "PV 10, datum Constr alternative 2^64-1, serialised by the script"),
            // Machine and translation differences.
            new Phase2Case("preprod-031e36a7435259b33cb3b55eebc90d44a4f58efeef1322b929d7d00518cdd732",
                    "PV 10, verifyEcdsaSecp256k1Signature with an all-zero signature is False"),
            new Phase2Case("preview-89d3a6272398582f7e3608e4048e5da8c3afe541c01914e0a2326bc351b216ed",
                    "PV 11, PlutusV3 context of a NewConstitution proposal"),
            new Phase2Case("preprod-2243432419d9ee47ffafcd9cba6fd785ee9f07204b6585e247e54d98f5067d19",
                    "PV 10, a key and a script withdrawal: Rewarding redeemer order"),
            new Phase2Case("preprod-aee75c1c595f90f893717d0186a96f09abf28579f08b76e412f327fa36bebf9b",
                    "PV 10, an output's PlutusV2 reference script with a Plutus Core 1.1.0 program and bytes after it"),
            new Phase2Case("preview-2c3657d09e194507a4b120c3aeed4616818ad3875382ba68b4e668e6d3d5d625",
                    "PV 11, UpdateCommittee removals with a set tag"));

    private PublicNetworkTransactions() {
    }

    /** @return the transaction's CBOR */
    public static byte[] cbor(String name) {
        try (InputStream in = open(name + ".hex")) {
            return HexUtil.decodeHexString(new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the shadow-sync bundle {@code <name>.json}: the transaction and the ledger-view reads it needs */
    public static ShadowDumpBundle bundle(String name) {
        try (InputStream in = open(name + ".json")) {
            return ShadowDumpBundle.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the transaction id, the name's suffix */
    public static String txId(String name) {
        return name.substring(name.indexOf('-') + 1);
    }

    private static InputStream open(String file) {
        String path = "/org/yanoproject/ledger/rules/public-network/" + file;
        InputStream in = PublicNetworkTransactions.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("no fixture " + path);
        }
        return in;
    }
}
