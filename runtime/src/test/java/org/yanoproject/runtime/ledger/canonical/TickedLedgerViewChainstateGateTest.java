package org.yanoproject.runtime.ledger.canonical;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.yanoproject.ledgerstate.EpochBoundaryPreview;
import org.yanoproject.ledgerstate.EpochBoundaryProcessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in ADR-056 ticking gate over real chain data. Disabled unless
 * {@code YANO_TICK_GATE_CHAINSTATE} is set.
 *
 * <p>It opens a <b>copy</b> of a node's chain-state directory, ticks the canonical state at its tip
 * to the next epoch ({@link TickedLedgerView}), then applies that epoch boundary to the copy with
 * the real ledger-state code and compares every ticked value (protocol parameters with cost models,
 * proposals, enacted roots, committee, guardrail, pools and the VRF index, DRep registration,
 * reward_rest writes) with
 * what the boundary persisted — the same checks as the synthetic {@link TickedLedgerViewEquivalenceTest}.
 * The pre-boundary state is whatever the copy holds; the closer its tip is to the end of the epoch,
 * the closer the run is to the chain's real boundary, but the comparison is valid at any tip because
 * both sides start from the same state.</p>
 *
 * <p><b>The copy is modified</b> (the boundary is applied to it). To prevent running against a live
 * chain state by mistake, the directory must contain a marker file {@code .tick-gate-copy}.</p>
 *
 * <pre>
 * # With the node stopped (or from a backup), copy the chain state and mark the copy:
 * cp -a /path/to/chainstate /tmp/tick-gate/chainstate
 * touch /tmp/tick-gate/chainstate/.tick-gate-copy
 * YANO_TICK_GATE_CHAINSTATE=/tmp/tick-gate/chainstate YANO_TICK_GATE_NETWORK=preprod \
 *   ./gradlew :runtime:test --offline --tests '*TickedLedgerViewChainstateGateTest*'
 * </pre>
 *
 * <p>Environment:</p>
 * <ul>
 *   <li>{@code YANO_TICK_GATE_CHAINSTATE} — the marked copy (required).</li>
 *   <li>{@code YANO_TICK_GATE_NETWORK} — {@code mainnet}, {@code preprod} (default) or {@code preview}:
 *       network magic and slot layout.</li>
 *   <li>{@code YANO_TICK_GATE_WITH_REWARDS} — {@code true} to also run the reward step of the boundary
 *       (slow on mainnet; rewards are not compared). By default the boundary resumes after it, as
 *       crash recovery does.</li>
 * </ul>
 *
 * <p>Wiring is the test node's ({@link TickingTestNode}): the stores and the tracker read the copy's
 * persisted state, but there is no era provider, AdaPot tracker or UTxO stake view, and fallback
 * genesis values are synthetic. Both sides of the comparison use the same wiring, so the gate checks
 * that the dry run equals Yano's real boundary code on real state; it is not a check against Haskell.</p>
 */
@EnabledIfEnvironmentVariable(named = "YANO_TICK_GATE_CHAINSTATE", matches = ".+")
class TickedLedgerViewChainstateGateTest {

    private static final String MARKER = ".tick-gate-copy";

    @Test
    void tickedViewEqualsTheRealBoundaryOnACopiedChainstate() {
        Path copy = Path.of(System.getenv("YANO_TICK_GATE_CHAINSTATE")).toAbsolutePath();
        assertThat(copy).isDirectory();
        assertThat(Files.exists(copy.resolve(MARKER)))
                .as("%s must be a copy marked with %s (the gate applies an epoch boundary to it)", copy, MARKER)
                .isTrue();
        String network = System.getenv().getOrDefault("YANO_TICK_GATE_NETWORK", "preprod").toLowerCase(Locale.ROOT);
        boolean withRewards = Boolean.parseBoolean(System.getenv().getOrDefault("YANO_TICK_GATE_WITH_REWARDS", "false"));
        long magic;
        long epochLength;
        long shelleyStartSlot;
        switch (network) {
            case "mainnet" -> {
                magic = 764_824_073L;
                epochLength = 432_000L;
                shelleyStartSlot = 4_492_800L;
            }
            case "preprod" -> {
                magic = 1L;
                epochLength = 432_000L;
                shelleyStartSlot = 86_400L;
            }
            case "preview" -> {
                magic = 2L;
                epochLength = 86_400L;
                shelleyStartSlot = 0L;
            }
            default -> throw new IllegalArgumentException("Unknown YANO_TICK_GATE_NETWORK " + network);
        }

        try (TickingTestNode node = new TickingTestNode(copy,
                TickingTestNode.baseParams(epochLength, shelleyStartSlot), magic, true, true)) {
            CanonicalTip tip = node.gate.tip();
            int[] boundary = node.accounts.getLastBoundaryState();
            System.out.printf("tick gate: %s tip slot=%d tipEpoch=%d ledgerEpoch=%d lastBoundary=%s%n", network,
                    tip.slot(), tip.tipSlotEpoch(), tip.ledgerEpoch(),
                    boundary != null ? boundary[0] + "@" + boundary[1] : "none");
            assertThat(tip.ledgerEpoch()).as("ledger epoch of the copy").isGreaterThanOrEqualTo(0);
            assertThat(boundary == null || boundary[1] == EpochBoundaryProcessor.STEP_COMPLETE)
                    .as("the copy's last boundary is complete").isTrue();

            TickingGate.Result result = TickingGate.run(node, null, !withRewards);

            EpochBoundaryPreview preview = result.preview();
            System.out.printf("tick gate: boundary into %d: retiredPools=%d poolRefunds=%s unclaimedPoolDeposits=%s%n",
                    preview.newEpoch(), preview.retiredPools().size(), preview.poolDepositRefunds(),
                    preview.unclaimedPoolDeposits());
            if (preview.governance() != null) {
                System.out.printf("tick gate: enacted=%s removed=%d rewardRestWrites=%d unclaimed=%s%n",
                        preview.governance().enacted(), preview.governance().removedProposals().size(),
                        preview.governance().rewardRestWrites().size(), preview.governance().unclaimedToTreasury());
            } else {
                System.out.printf("tick gate: governance not previewed: %s%n", preview.governanceUnavailableReason());
            }
            System.out.printf("tick gate: compared %d pools, %d VRFs, %d proposals, %d committee members; "
                            + "%d mismatches%n", result.ticked().pools().size(), result.ticked().vrfs().size(),
                    result.ticked().proposals().size(), result.ticked().committeeByCold().size(),
                    result.mismatches().size());
            result.mismatches().forEach(m -> System.out.println("tick gate MISMATCH: " + m));
            // Passed-through DRep expiries differ only when the boundary flushes the dormant counter.
            result.drepExpiryDifferences().forEach(m -> System.out.println("tick gate (expected pass-through) " + m));
            assertThat(result.mismatches()).isEmpty();
        }
    }
}
