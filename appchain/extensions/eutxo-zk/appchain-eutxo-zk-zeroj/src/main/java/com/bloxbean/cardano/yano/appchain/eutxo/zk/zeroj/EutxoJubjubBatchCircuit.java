package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Authorization;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoFinalizedProofWitness;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchSettlement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchManifest;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProfile;
import com.bloxbean.cardano.zeroj.api.CurveId;
import com.bloxbean.cardano.zeroj.circuit.CircuitAPI;
import com.bloxbean.cardano.zeroj.circuit.CircuitBuilder;
import com.bloxbean.cardano.zeroj.circuit.Variable;
import com.bloxbean.cardano.zeroj.circuit.lib.Poseidon;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.EdDSAJubjub;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.InCircuitEdDSAJubjub;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.InCircuitJubjub;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubCurve;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubPoint;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonHash;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonParamsBLS12_381T3;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed-bound development circuit for multiple Jubjub-authorized L2 envelopes.
 *
 * <p>As with {@code zeroj-jubjub-dev-v1}, canonical decoding and full Cardano
 * transition semantics remain trusted host checks. This circuit proves each
 * signature and one bounded ordered accumulator.</p>
 */
public final class EutxoJubjubBatchCircuit {
    private static final BigInteger BATCH_DOMAIN =
            ZerojScalars.domain("yano:eutxo:jubjub-batch:dev:v1");
    private static final BigInteger AUTHORIZATION_DOMAIN =
            ZerojScalars.domain("yano:eutxo:jubjub-authorizations:dev:v1");
    private static final BigInteger DUMMY_SECRET = BigInteger.ONE;
    private static final BigInteger DUMMY_MESSAGE = BigInteger.ZERO;

    private EutxoJubjubBatchCircuit() {
    }

    public static CircuitBuilder circuit(EutxoZkBatchProfile profile) {
        Objects.requireNonNull(profile, "profile");
        CircuitBuilder builder = CircuitBuilder.create(profile.circuitId())
                .publicVar("previousRoot")
                .publicVar("nextRoot")
                .publicVar("batchDigest")
                .publicVar("authorizationCommitment")
                .publicVar("batchSize")
                .publicVar("settlementContext")
                .publicVar("batchDataCommitment")
                .publicVar("withdrawalCommitment")
                .secretVar("settlementContextWitness")
                .secretVar("batchDataCommitmentWitness")
                .secretVar("withdrawalCommitmentWitness");
        for (int index = 0;
            index < profile.maximumTransactions();
             index++) {
            builder.secretVar(name("enabled", index))
                    .secretVar(name("transitionDigest", index))
                    .secretVar(name("message", index))
                    .secretVar(name("publicKeyU", index))
                    .secretVar(name("publicKeyV", index))
                    .secretVar(name("rU", index))
                    .secretVar(name("rV", index))
                    .secretVar(name("s", index))
                    .secretVar(name("kModL", index))
                    .secretVar(name("kQuotient", index));
        }
        return builder.define(api -> define(api, profile));
    }

    public static Statement statement(
            EutxoZkBatchProfile profile,
            byte[] previousRoot,
            List<EutxoL2Transaction> transactions
    ) {
        EutxoZkBatchManifest manifest = new EutxoZkBatchManifest(
                transactions.stream()
                        .map(EutxoL2Transaction::transactionId)
                        .toList());
        return statement(
                profile,
                previousRoot,
                transactions,
                developmentTransitionDigests(transactions),
                new EutxoZkBatchSettlement(
                        BigInteger.ZERO,
                        manifest.commitmentScalar(),
                        BigInteger.ZERO));
    }

    public static Statement statement(
            EutxoZkBatchProfile profile,
            byte[] previousRoot,
            List<EutxoL2Transaction> transactions,
            EutxoZkBatchSettlement settlement
    ) {
        return statement(
                profile,
                previousRoot,
                transactions,
                developmentTransitionDigests(transactions),
                settlement);
    }

    public static Statement statementFromFinalized(
            EutxoZkBatchProfile profile,
            byte[] previousRoot,
            List<EutxoFinalizedProofWitness> finalized,
            EutxoZkBatchSettlement settlement
    ) {
        Objects.requireNonNull(finalized, "finalized");
        List<EutxoL2Transaction> transactions =
                finalizedTransactions(finalized);
        return statement(
                profile,
                previousRoot,
                transactions,
                finalized.stream()
                        .map(EutxoFinalizedProofWitness::transitionDigest)
                        .map(ZerojScalars::scalar)
                        .toList(),
                settlement);
    }

    private static Statement statement(
            EutxoZkBatchProfile profile,
            byte[] previousRoot,
            List<EutxoL2Transaction> transactions,
            List<BigInteger> transitionDigests,
            EutxoZkBatchSettlement settlement
    ) {
        requireBatch(profile, transactions);
        requireTransitionDigests(transactions, transitionDigests);
        Objects.requireNonNull(settlement, "settlement");
        BigInteger root = ZerojScalars.scalar(previousRoot);
        BigInteger digest = BATCH_DOMAIN;
        BigInteger authorizations = AUTHORIZATION_DOMAIN;
        for (int index = 0; index < transactions.size(); index++) {
            EutxoL2Transaction transaction = transactions.get(index);
            EutxoL2Authorization authorization =
                    onlyAuthorization(transaction);
            JubjubPoint publicKey =
                    canonicalPoint(authorization.publicKey());
            BigInteger message =
                    ZerojScalars.scalar(transaction.signingCommitment());
            BigInteger publicKeyCommitment = poseidon(
                    publicKey.affineU(), publicKey.affineV());
            BigInteger authorizationBinding =
                    poseidon(message, publicKeyCommitment);
            BigInteger transition = transitionDigests.get(index);
            root = poseidon(root, transition);
            digest = poseidon(
                    digest,
                    poseidon(transition, authorizationBinding));
            authorizations = poseidon(
                    authorizations, publicKeyCommitment);
        }
        return new Statement(
                ZerojScalars.scalar(previousRoot),
                root,
                digest,
                authorizations,
                BigInteger.valueOf(transactions.size()),
                settlement.settlementContext(),
                settlement.batchDataCommitment(),
                settlement.withdrawalLovelace());
    }

    public static BigInteger[] witness(
            EutxoZkBatchProfile profile,
            Statement statement,
            List<EutxoL2Transaction> transactions
    ) {
        return witness(
                profile,
                statement,
                transactions,
                developmentTransitionDigests(transactions));
    }

    public static BigInteger[] witnessFromFinalized(
            EutxoZkBatchProfile profile,
            Statement statement,
            List<EutxoFinalizedProofWitness> finalized
    ) {
        Objects.requireNonNull(finalized, "finalized");
        List<EutxoL2Transaction> transactions =
                finalizedTransactions(finalized);
        return witness(
                profile,
                statement,
                transactions,
                finalized.stream()
                        .map(EutxoFinalizedProofWitness::transitionDigest)
                        .map(ZerojScalars::scalar)
                        .toList());
    }

    private static BigInteger[] witness(
            EutxoZkBatchProfile profile,
            Statement statement,
            List<EutxoL2Transaction> transactions,
            List<BigInteger> transitionDigests
    ) {
        requireBatch(profile, transactions);
        requireTransitionDigests(transactions, transitionDigests);
        Map<String, List<BigInteger>> assignments = new LinkedHashMap<>();
        assignments.put("previousRoot", List.of(statement.previousRoot()));
        assignments.put("nextRoot", List.of(statement.nextRoot()));
        assignments.put("batchDigest", List.of(statement.batchDigest()));
        assignments.put("authorizationCommitment",
                List.of(statement.authorizationCommitment()));
        assignments.put("batchSize", List.of(statement.batchSize()));
        assignments.put("settlementContext",
                List.of(statement.settlementContext()));
        assignments.put("batchDataCommitment",
                List.of(statement.batchDataCommitment()));
        assignments.put("withdrawalCommitment",
                List.of(statement.withdrawalCommitment()));
        assignments.put("settlementContextWitness",
                List.of(statement.settlementContext()));
        assignments.put("batchDataCommitmentWitness",
                List.of(statement.batchDataCommitment()));
        assignments.put("withdrawalCommitmentWitness",
                List.of(statement.withdrawalCommitment()));
        for (int index = 0;
             index < profile.maximumTransactions();
             index++) {
            boolean enabled = index < transactions.size();
            SignatureWitness item = enabled
                    ? signatureWitness(transactions.get(index))
                    : dummyWitness();
            assignments.put(name("enabled", index),
                    List.of(enabled ? BigInteger.ONE : BigInteger.ZERO));
            assignments.put(name("transitionDigest", index),
                    List.of(enabled
                            ? transitionDigests.get(index)
                            : BigInteger.ZERO));
            assignments.put(name("message", index),
                    List.of(item.message()));
            assignments.put(name("publicKeyU", index),
                    List.of(item.publicKey().affineU()));
            assignments.put(name("publicKeyV", index),
                    List.of(item.publicKey().affineV()));
            assignments.put(name("rU", index),
                    List.of(item.rPoint().affineU()));
            assignments.put(name("rV", index),
                    List.of(item.rPoint().affineV()));
            assignments.put(name("s", index), List.of(item.s()));
            assignments.put(name("kModL", index),
                    List.of(item.kModL()));
            assignments.put(name("kQuotient", index),
                    List.of(item.kQuotient()));
        }
        return circuit(profile).calculateWitness(
                assignments, CurveId.BLS12_381);
    }

    private static void define(
            CircuitAPI api,
            EutxoZkBatchProfile profile
    ) {
        Variable runningRoot = api.var("previousRoot");
        Variable runningBatch = api.constant(BATCH_DOMAIN);
        Variable runningAuthorizations =
                api.constant(AUTHORIZATION_DOMAIN);
        Variable enabledSum = api.constant(0);
        Variable previousEnabled = api.constant(1);
        for (int index = 0;
             index < profile.maximumTransactions();
             index++) {
            Variable enabled = api.var(name("enabled", index));
            Variable transitionDigest =
                    api.var(name("transitionDigest", index));
            Variable message = api.var(name("message", index));
            Variable publicKeyU = api.var(name("publicKeyU", index));
            Variable publicKeyV = api.var(name("publicKeyV", index));
            Variable rU = api.var(name("rU", index));
            Variable rV = api.var(name("rV", index));
            var publicKey = new InCircuitJubjub.Point(
                    publicKeyU,
                    publicKeyV,
                    api.constant(1),
                    api.mul(publicKeyU, publicKeyV));
            var rPoint = new InCircuitJubjub.Point(
                    rU,
                    rV,
                    api.constant(1),
                    api.mul(rU, rV));
            api.assertBoolean(enabled);
            api.assertEqual(
                    api.mul(enabled, api.not(previousEnabled)),
                    api.constant(0));
            api.assertEqual(
                    api.mul(api.not(enabled), transitionDigest),
                    api.constant(0));
            assertNotIdentity(api, publicKey);
            assertNotIdentity(api, rPoint);
            InCircuitEdDSAJubjub.verify(
                    api,
                    publicKey,
                    message,
                    rPoint,
                    api.var(name("s", index)),
                    api.var(name("kModL", index)),
                    api.var(name("kQuotient", index)));
            Variable publicKeyCommitment = Poseidon.hash(
                    api,
                    PoseidonParamsBLS12_381T3.INSTANCE,
                    publicKeyU,
                    publicKeyV);
            Variable authorizationBinding = Poseidon.hash(
                    api,
                    PoseidonParamsBLS12_381T3.INSTANCE,
                    message,
                    publicKeyCommitment);
            Variable candidateRoot = Poseidon.hash(
                    api,
                    PoseidonParamsBLS12_381T3.INSTANCE,
                    runningRoot,
                    transitionDigest);
            Variable batchBinding = Poseidon.hash(
                    api,
                    PoseidonParamsBLS12_381T3.INSTANCE,
                    transitionDigest,
                    authorizationBinding);
            Variable candidateBatch = Poseidon.hash(
                    api,
                    PoseidonParamsBLS12_381T3.INSTANCE,
                    runningBatch,
                    batchBinding);
            Variable candidateAuthorization = Poseidon.hash(
                    api,
                    PoseidonParamsBLS12_381T3.INSTANCE,
                    runningAuthorizations,
                    publicKeyCommitment);
            runningRoot = api.select(enabled, candidateRoot, runningRoot);
            runningBatch = api.select(enabled, candidateBatch, runningBatch);
            runningAuthorizations = api.select(
                    enabled,
                    candidateAuthorization,
                    runningAuthorizations);
            enabledSum = api.add(enabledSum, enabled);
            previousEnabled = enabled;
        }
        api.assertEqual(api.var("nextRoot"), runningRoot);
        api.assertEqual(api.var("batchDigest"), runningBatch);
        api.assertEqual(
                api.var("authorizationCommitment"),
                runningAuthorizations);
        api.assertEqual(api.var("batchSize"), enabledSum);
        api.assertEqual(
                api.var("settlementContext"),
                api.var("settlementContextWitness"));
        api.assertEqual(
                api.var("batchDataCommitment"),
                api.var("batchDataCommitmentWitness"));
        api.assertEqual(
                api.var("withdrawalCommitment"),
                api.var("withdrawalCommitmentWitness"));
    }

    private static List<BigInteger> developmentTransitionDigests(
            List<EutxoL2Transaction> transactions
    ) {
        Objects.requireNonNull(transactions, "transactions");
        return transactions.stream()
                .map(EutxoL2Transaction::signingCommitment)
                .map(ZerojScalars::scalar)
                .toList();
    }

    private static List<EutxoL2Transaction> finalizedTransactions(
            List<EutxoFinalizedProofWitness> finalized
    ) {
        return finalized.stream()
                .map(EutxoFinalizedProofWitness::transition)
                .map(transition -> EutxoL2Transaction.decode(
                        transition.canonicalTransaction()))
                .toList();
    }

    private static void requireTransitionDigests(
            List<EutxoL2Transaction> transactions,
            List<BigInteger> transitionDigests
    ) {
        if (transitionDigests == null
                || transitionDigests.size() != transactions.size()
                || transitionDigests.stream().anyMatch(
                Objects::isNull)) {
            throw new IllegalArgumentException(
                    "every transaction needs one transition digest");
        }
    }

    private static SignatureWitness signatureWitness(
            EutxoL2Transaction transaction
    ) {
        EutxoL2Authorization authorization =
                onlyAuthorization(transaction);
        JubjubPoint publicKey =
                canonicalPoint(authorization.publicKey());
        JubjubPoint rPoint = canonicalPoint(authorization.rPoint());
        BigInteger s = fromLittleEndian(authorization.s());
        BigInteger message =
                ZerojScalars.scalar(transaction.signingCommitment());
        if (publicKey.isIdentity() || rPoint.isIdentity()
                || !publicKey.isInSubgroup() || !rPoint.isInSubgroup()
                || s.compareTo(JubjubCurve.SUBGROUP_ORDER) >= 0) {
            throw new IllegalArgumentException(
                    "batch contains an invalid Jubjub authorization");
        }
        var reduction = InCircuitEdDSAJubjub.witnessComputeKReduction(
                rPoint, publicKey, message);
        return new SignatureWitness(
                message, publicKey, rPoint, s,
                reduction.kModL(), reduction.kQuotient());
    }

    private static SignatureWitness dummyWitness() {
        var keypair = EdDSAJubjub.keypairFromSecret(DUMMY_SECRET);
        var signature = EdDSAJubjub.sign(
                DUMMY_SECRET, DUMMY_MESSAGE);
        var reduction = InCircuitEdDSAJubjub.witnessComputeKReduction(
                signature.r(), keypair.pk(), DUMMY_MESSAGE);
        return new SignatureWitness(
                DUMMY_MESSAGE,
                keypair.pk(),
                signature.r(),
                signature.s(),
                reduction.kModL(),
                reduction.kQuotient());
    }

    private static void requireBatch(
            EutxoZkBatchProfile profile,
            List<EutxoL2Transaction> transactions
    ) {
        Objects.requireNonNull(profile, "profile");
        transactions = List.copyOf(
                Objects.requireNonNull(transactions, "transactions"));
        if (transactions.isEmpty()
                || transactions.size() > profile.maximumTransactions()) {
            throw new IllegalArgumentException(
                    "batch exceeds immutable profile "
                            + profile.maximumTransactions());
        }
    }

    private static EutxoL2Authorization onlyAuthorization(
            EutxoL2Transaction transaction
    ) {
        Objects.requireNonNull(transaction, "transaction");
        if (transaction.authorizations().size() != 1) {
            throw new IllegalArgumentException(
                    "development batch requires exactly one authorization per transaction");
        }
        return transaction.authorizations().getFirst();
    }

    private static void assertNotIdentity(
            CircuitAPI api,
            InCircuitJubjub.Point point
    ) {
        Variable identity = api.mul(
                api.isZero(point.u()),
                api.isZero(api.sub(point.v(), api.constant(1))));
        api.assertEqual(identity, api.constant(0));
    }

    private static JubjubPoint canonicalPoint(byte[] bytes) {
        JubjubPoint point = JubjubPoint.fromBytes(bytes);
        if (!Arrays.equals(bytes, point.toBytes())) {
            throw new IllegalArgumentException(
                    "Jubjub point is not canonically encoded");
        }
        return point;
    }

    private static BigInteger fromLittleEndian(byte[] encoded) {
        byte[] reversed = encoded.clone();
        for (int left = 0, right = reversed.length - 1;
             left < right; left++, right--) {
            byte value = reversed[left];
            reversed[left] = reversed[right];
            reversed[right] = value;
        }
        return new BigInteger(1, reversed);
    }

    private static BigInteger poseidon(
            BigInteger left,
            BigInteger right
    ) {
        return PoseidonHash.hash(
                PoseidonParamsBLS12_381T3.INSTANCE, left, right);
    }

    static byte[] nextValidityRoot(
            byte[] previousRoot,
            byte[] transitionDigest
    ) {
        return ZerojScalars.bytes32(poseidon(
                ZerojScalars.scalar(previousRoot),
                ZerojScalars.scalar(transitionDigest)));
    }

    private static String name(String prefix, int index) {
        return prefix + index;
    }

    public record Statement(
            BigInteger previousRoot,
            BigInteger nextRoot,
            BigInteger batchDigest,
            BigInteger authorizationCommitment,
            BigInteger batchSize,
            BigInteger settlementContext,
            BigInteger batchDataCommitment,
            BigInteger withdrawalCommitment
    ) {
        public Statement {
            Objects.requireNonNull(previousRoot, "previousRoot");
            Objects.requireNonNull(nextRoot, "nextRoot");
            Objects.requireNonNull(batchDigest, "batchDigest");
            Objects.requireNonNull(
                    authorizationCommitment,
                    "authorizationCommitment");
            Objects.requireNonNull(batchSize, "batchSize");
            Objects.requireNonNull(
                    settlementContext, "settlementContext");
            Objects.requireNonNull(
                    batchDataCommitment, "batchDataCommitment");
            Objects.requireNonNull(
                    withdrawalCommitment, "withdrawalCommitment");
        }

        public List<BigInteger> ordered() {
            return List.of(
                    previousRoot,
                    nextRoot,
                    batchDigest,
                    authorizationCommitment,
                    batchSize,
                    settlementContext,
                    batchDataCommitment,
                    withdrawalCommitment);
        }
    }

    private record SignatureWitness(
            BigInteger message,
            JubjubPoint publicKey,
            JubjubPoint rPoint,
            BigInteger s,
            BigInteger kModL,
            BigInteger kQuotient
    ) {
    }
}
