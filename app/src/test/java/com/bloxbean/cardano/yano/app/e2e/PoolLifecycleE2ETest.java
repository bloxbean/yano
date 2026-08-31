package com.bloxbean.cardano.yano.app.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.spec.UnitInterval;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #62 end-to-end matrix against a running short-epoch devnet.
 *
 * <p>Every pool is test-owned and non-producing. The genesis producer pool is never
 * retired. This suite is opt-in because it starts a server and submits real transactions.
 */
@QuarkusTest
@TestProfile(PoolLifecycleDevnetTestProfile.class)
@Tag("integration")
class PoolLifecycleE2ETest extends BaseE2ETest {
    private static final int DELEGATORS_PER_POOL = 5;
    private static final BigInteger POOL_DEPOSIT = BigInteger.valueOf(500_000_000L);
    private static final BigInteger MIN_POOL_COST = BigInteger.valueOf(170_000_000L);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, PoolFixture> pools = new LinkedHashMap<>();

    private Account sameBlockBarrier;

    @Override
    protected int getAccountBaseIndex() {
        return 5_000;
    }

    @BeforeAll
    void prepareNonProducingPoolsAndDelegators() throws Exception {
        String[] names = {"A", "B", "C", "D", "E", "F-retire-register", "F-register-retire"};
        List<Transaction> poolRegistrations = new ArrayList<>();
        List<Transaction> stakeRegistrations = new ArrayList<>();

        int accountOffset = 0;
        for (String name : names) {
            Account owner = fixtureAccount(accountOffset++);
            fundAddress(owner.baseAddress(), 2_000);

            PoolRegistration registration = poolRegistration(owner);
            List<Account> delegators = new ArrayList<>();
            for (int i = 0; i < DELEGATORS_PER_POOL; i++) {
                Account delegator = fixtureAccount(accountOffset++);
                fundAddress(delegator.baseAddress(), 20);
                delegators.add(delegator);
            }

            PoolFixture fixture = new PoolFixture(name, owner, registration, List.copyOf(delegators));
            pools.put(name, fixture);
            poolRegistrations.add(buildInitialPoolRegistration(fixture));
        }

        sameBlockBarrier = fixtureAccount(accountOffset);
        fundAddress(sameBlockBarrier.baseAddress(), 20);

        List<String> poolRegistrationHashes = submitTogether(poolRegistrations);
        awaitTransactions(poolRegistrationHashes);
        awaitState("all test pools registered", () -> pools.values().stream().allMatch(this::poolIsRegistered));

        for (PoolFixture fixture : pools.values()) {
            for (Account delegator : fixture.delegators()) {
                stakeRegistrations.add(buildStakeRegistration(delegator));
            }
        }
        awaitTransactions(submitTogether(stakeRegistrations));

        List<Transaction> delegations = new ArrayList<>();
        for (PoolFixture fixture : pools.values()) {
            for (Account delegator : fixture.delegators()) {
                delegations.add(buildDelegationOnly(fixture, delegator));
            }
        }
        List<String> delegationHashes = submitTogether(delegations);
        awaitTransactions(delegationHashes);
        awaitState("five live delegations per test pool",
                () -> pools.values().stream().allMatch(pool -> liveDelegationCount(pool) == DELEGATORS_PER_POOL));
    }

    @Test
    @Order(1)
    void scenarioA_pendingRetirementCancelledInLaterBlock() throws Exception {
        PoolFixture pool = pools.get("A");
        int retirementEpoch = currentEpoch() + 2;

        submitAndAwait(buildRetirement(pool, retirementEpoch, pool.owner()));
        awaitState("scenario A retirement visible", () -> retirementEpoch(pool) == retirementEpoch);

        submitAndAwait(buildPoolUpdate(pool, pool.owner()));
        awaitState("scenario A retirement cancelled", () -> retirementEpoch(pool) == -1);
        assertEquals(DELEGATORS_PER_POOL, liveDelegationCount(pool));

        advanceToEpoch(retirementEpoch + 1);
        assertTrue(poolIsRegistered(pool));
        assertEquals(-1, retirementEpoch(pool));
        assertEquals(DELEGATORS_PER_POOL, liveDelegationCount(pool));
        assertTrue(poolSnapshotStake(retirementEpoch, pool).signum() > 0,
                "cancelled retirement must retain snapshot stake");
    }

    @Test
    @Order(2)
    void scenarioB_effectiveRetirementAndReregistrationInRetirementEpoch() throws Exception {
        PoolFixture pool = pools.get("B");
        int retirementEpoch = currentEpoch() + 1;
        BigInteger rewardBefore = rewardBalance(pool.owner());

        submitAndAwait(buildRetirement(pool, retirementEpoch, pool.owner()));
        advanceToEpoch(retirementEpoch);
        assertReaped(pool, rewardBefore);

        submitAndAwait(buildNewPoolLifecycleRegistration(pool));
        awaitState("scenario B pool re-registered", () -> poolIsRegistered(pool));
        assertEquals(0, liveDelegationCount(pool), "old delegations must not reappear");

        advanceToEpoch(retirementEpoch + 1);
        assertEquals(BigInteger.ZERO, poolSnapshotStake(retirementEpoch, pool));

        submitAndAwait(buildDelegationOnly(pool, pool.delegators().getFirst()));
        awaitState("scenario B one new delegation", () -> liveDelegationCount(pool) == 1);
        advanceToEpoch(retirementEpoch + 2);
        assertTrue(poolSnapshotStake(retirementEpoch + 1, pool).signum() > 0);
        assertEquals(rewardBefore.add(POOL_DEPOSIT), rewardBalance(pool.owner()),
                "pool deposit must be refunded exactly once");
    }

    @Test
    @Order(3)
    void scenarioC_effectiveRetirementAndReregistrationThreeEpochsLater() throws Exception {
        PoolFixture pool = pools.get("C");
        int retirementEpoch = currentEpoch() + 1;
        BigInteger rewardBefore = rewardBalance(pool.owner());

        submitAndAwait(buildRetirement(pool, retirementEpoch, pool.owner()));
        advanceToEpoch(retirementEpoch);
        assertReaped(pool, rewardBefore);

        advanceToEpoch(retirementEpoch + 3);
        submitAndAwait(buildNewPoolLifecycleRegistration(pool));
        awaitState("scenario C pool re-registered", () -> poolIsRegistered(pool));
        assertEquals(0, liveDelegationCount(pool), "delayed re-registration must not restore old delegations");
        assertEquals(rewardBefore.add(POOL_DEPOSIT), rewardBalance(pool.owner()));
    }

    @Test
    @Order(4)
    void scenarioD_sameTransactionRetirementThenRegistrationCancels() throws Exception {
        PoolFixture pool = pools.get("D");
        int retirementEpoch = currentEpoch() + 2;
        Tx tx = new Tx()
                .retirePool(pool.poolId(), retirementEpoch)
                .updatePool(pool.registration())
                .from(pool.owner().baseAddress());

        submitAndAwait(buildSigned(tx, pool.owner(), pool.owner()));
        awaitState("scenario D cancellation visible", () -> retirementEpoch(pool) == -1);
        assertEquals(DELEGATORS_PER_POOL, liveDelegationCount(pool));
    }

    @Test
    @Order(5)
    void scenarioE_sameTransactionRegistrationThenRetirementStaysScheduled() throws Exception {
        PoolFixture pool = pools.get("E");
        int retirementEpoch = currentEpoch() + 2;
        Tx tx = new Tx()
                .updatePool(pool.registration())
                .retirePool(pool.poolId(), retirementEpoch)
                .from(pool.owner().baseAddress());

        submitAndAwait(buildSigned(tx, pool.owner(), pool.owner()));
        awaitState("scenario E retirement visible", () -> retirementEpoch(pool) == retirementEpoch);
        assertEquals(DELEGATORS_PER_POOL, liveDelegationCount(pool));
    }

    @Test
    @Order(6)
    void scenarioF_sameBlockDifferentTransactionsFollowTxIndex() throws Exception {
        PoolFixture retireThenRegister = pools.get("F-retire-register");
        PoolFixture registerThenRetire = pools.get("F-register-retire");
        int retirementEpoch = currentEpoch() + 2;

        Account f1UpdatePayer = fixtureAccount(100);
        Account f2RetirementPayer = fixtureAccount(101);
        fundAddress(f1UpdatePayer.baseAddress(), 20);
        fundAddress(f2RetirementPayer.baseAddress(), 20);

        List<Transaction> ordered = List.of(
                buildRetirement(retireThenRegister, retirementEpoch, retireThenRegister.owner()),
                buildPoolUpdate(retireThenRegister, f1UpdatePayer),
                buildPoolUpdate(registerThenRetire, registerThenRetire.owner()),
                buildRetirement(registerThenRetire, retirementEpoch, f2RetirementPayer));

        // A confirmed barrier gives the lazy producer a fresh full scheduling interval,
        // so all pre-built transactions enter the mempool before its next drain.
        Tx barrier = new Tx().payToAddress(sameBlockBarrier.baseAddress(), Amount.lovelace(BigInteger.ONE))
                .from(sameBlockBarrier.baseAddress());
        submitAndAwait(buildSigned(barrier, sameBlockBarrier, null));

        List<String> hashes = submitTogether(ordered);
        awaitTransactions(hashes);
        List<JsonNode> infos = hashes.stream().map(this::txInfo).toList();
        String blockHash = infos.getFirst().path("block").asText();
        assertFalse(blockHash.isBlank());
        assertTrue(infos.stream().allMatch(info -> blockHash.equals(info.path("block").asText())),
                "scenario F transactions must share one block");
        for (int i = 1; i < infos.size(); i++) {
            assertTrue(infos.get(i - 1).path("index").asInt() < infos.get(i).path("index").asInt(),
                    "submission order must match txIndex order");
        }

        awaitState("scenario F ordered final state",
                () -> retirementEpoch(retireThenRegister) == -1
                        && retirementEpoch(registerThenRetire) == retirementEpoch);
        assertEquals(DELEGATORS_PER_POOL, liveDelegationCount(retireThenRegister));
        assertEquals(DELEGATORS_PER_POOL, liveDelegationCount(registerThenRetire));
    }

    private PoolRegistration poolRegistration(Account owner) {
        byte[] operator = Blake2bUtil.blake2bHash224(owner.stakeHdKeyPair().getPublicKey().getKeyData());
        byte[] vrf = Blake2bUtil.blake2bHash256(owner.publicKeyBytes());
        String ownerHash = HexUtil.encodeHexString(operator);
        return PoolRegistration.builder()
                .operator(operator)
                .vrfKeyHash(vrf)
                .pledge(BigInteger.ZERO)
                .cost(MIN_POOL_COST)
                .margin(new UnitInterval(BigInteger.ONE, BigInteger.valueOf(20)))
                .rewardAccount(HexUtil.encodeHexString(new Address(owner.stakeAddress()).getBytes()))
                .poolOwners(Set.of(ownerHash))
                .relays(List.of())
                .build();
    }

    private Account fixtureAccount(int offset) {
        return Account.createFromMnemonic(Networks.testnet(), getMnemonic(), getAccountBaseIndex() + offset, 0);
    }

    private Transaction buildInitialPoolRegistration(PoolFixture pool) {
        Tx tx = new Tx()
                .registerStakeAddress(pool.owner().baseAddress())
                .registerPool(pool.registration())
                .from(pool.owner().baseAddress());
        return buildSigned(tx, pool.owner(), pool.owner());
    }

    private Transaction buildNewPoolLifecycleRegistration(PoolFixture pool) {
        Tx tx = new Tx()
                .registerPool(pool.registration())
                .from(pool.owner().baseAddress());
        return buildSigned(tx, pool.owner(), pool.owner());
    }

    private Transaction buildPoolUpdate(PoolFixture pool, Account payer) {
        Tx tx = new Tx()
                .updatePool(pool.registration())
                .from(payer.baseAddress());
        return buildSigned(tx, payer, pool.owner());
    }

    private Transaction buildRetirement(PoolFixture pool, int epoch, Account payer) {
        Tx tx = new Tx()
                .retirePool(pool.poolId(), epoch)
                .from(payer.baseAddress());
        return buildSigned(tx, payer, pool.owner());
    }

    private Transaction buildStakeRegistration(Account delegator) {
        Tx tx = new Tx()
                .registerStakeAddress(delegator.baseAddress())
                .from(delegator.baseAddress());
        return buildSigned(tx, delegator, delegator);
    }

    private Transaction buildDelegationOnly(PoolFixture pool, Account delegator) {
        Tx tx = new Tx()
                .delegateTo(delegator.baseAddress(), pool.poolHash())
                .from(delegator.baseAddress());
        return buildSigned(tx, delegator, delegator);
    }

    private Transaction buildSigned(Tx tx, Account paymentSigner, Account stakeSigner) {
        var context = quickTxBuilder.compose(tx).withSigner(SignerProviders.signerFrom(paymentSigner));
        if (stakeSigner != null) {
            context.withSigner(SignerProviders.stakeKeySignerFrom(stakeSigner));
        }
        return context.buildAndSign();
    }

    private List<String> submitTogether(List<Transaction> transactions) throws Exception {
        List<String> hashes = new ArrayList<>(transactions.size());
        for (Transaction transaction : transactions) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "tx/submit"))
                    .header("Content-Type", "application/cbor")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(transaction.serialize()))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            hashes.add(mapper.readTree(response.body()).asText());
        }
        return hashes;
    }

    private void submitAndAwait(Transaction transaction) throws Exception {
        List<String> hashes = submitTogether(List.of(transaction));
        awaitTransactions(hashes);
    }

    private void awaitTransactions(List<String> hashes) {
        for (String hash : hashes) {
            awaitState("transaction " + hash + " in block",
                    () -> request("txs/" + hash).statusCode() == 200);
        }
    }

    private int currentEpoch() {
        return getJson("epochs/latest").path("epoch").asInt();
    }

    private void advanceToEpoch(int targetEpoch) throws Exception {
        while (currentEpoch() < targetEpoch) {
            postJson("devnet/time/advance", "{\"epochs\":1}");
        }
        awaitState("epoch " + targetEpoch, () -> currentEpoch() >= targetEpoch);
    }

    private boolean poolIsRegistered(PoolFixture pool) {
        return stream(getJson("accounts/pools?page=1&count=100"))
                .anyMatch(entry -> pool.poolHash().equals(entry.path("pool_hash").asText()));
    }

    private int retirementEpoch(PoolFixture pool) {
        return stream(getJson("accounts/pool-retirements?page=1&count=100"))
                .filter(entry -> pool.poolHash().equals(entry.path("pool_hash").asText()))
                .mapToInt(entry -> entry.path("retirement_epoch").asInt())
                .findFirst()
                .orElse(-1);
    }

    private int liveDelegationCount(PoolFixture pool) {
        return (int) stream(getJson("accounts/delegations?page=1&count=100"))
                .filter(entry -> pool.poolHash().equals(entry.path("pool_hash").asText()))
                .count();
    }

    private BigInteger rewardBalance(Account account) {
        JsonNode state = getJson("accounts/" + account.stakeAddress());
        return new BigInteger(state.path("withdrawable_amount").asText());
    }

    private BigInteger poolSnapshotStake(int epoch, PoolFixture pool) {
        HttpResponse<String> response = request("epochs/" + epoch + "/stake/pool/" + pool.poolId());
        if (response.statusCode() == 404) {
            return BigInteger.ZERO;
        }
        assertEquals(200, response.statusCode(), response.body());
        try {
            return new BigInteger(mapper.readTree(response.body()).path("active_stake").asText());
        } catch (Exception e) {
            throw new IllegalStateException("Invalid pool snapshot response", e);
        }
    }

    private void assertReaped(PoolFixture pool, BigInteger rewardBefore) {
        awaitState("pool " + pool.name() + " reaped",
                () -> !poolIsRegistered(pool) && retirementEpoch(pool) == -1 && liveDelegationCount(pool) == 0);
        assertEquals(rewardBefore.add(POOL_DEPOSIT), rewardBalance(pool.owner()),
                "effective retirement must refund the stored pool deposit once");
    }

    private JsonNode txInfo(String hash) {
        return getJson("txs/" + hash);
    }

    private JsonNode getJson(String path) {
        HttpResponse<String> response = request(path);
        assertEquals(200, response.statusCode(), "GET " + path + ": " + response.body());
        try {
            return mapper.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON from " + path, e);
        }
    }

    private HttpResponse<String> request(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("GET " + path + " failed", e);
        }
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "POST " + path + ": " + response.body());
        return mapper.readTree(response.body());
    }

    private void awaitState(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (Throwable failure) {
                lastFailure = failure;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting " + description, e);
            }
        }
        AssertionError timeout = new AssertionError("Timed out awaiting " + description);
        if (lastFailure != null) {
            timeout.initCause(lastFailure);
        }
        throw timeout;
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> values = new ArrayList<>();
        array.forEach(values::add);
        return values.stream();
    }

    private record PoolFixture(String name,
                               Account owner,
                               PoolRegistration registration,
                               List<Account> delegators) {
        String poolHash() {
            return HexUtil.encodeHexString(registration.getOperator());
        }

        String poolId() {
            return registration.getBech32PoolId();
        }
    }
}
