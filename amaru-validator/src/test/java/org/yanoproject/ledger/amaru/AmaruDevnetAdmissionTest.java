package org.yanoproject.ledger.amaru;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.api.model.FundResult;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.devnet.YanoDevnetAssembly;
import org.yanoproject.runtime.assembly.Yano;
import org.yanoproject.runtime.blockproducer.TransactionValidationException;
import org.yanoproject.runtime.tx.TransactionBootstrapOptions;
import org.yanoproject.runtime.validation.ValidationEngines;
import org.yanoproject.testkit.devnet.YanoDevnetTestConfig;
import org.yanoproject.tx.DefaultTransactionServicesFactory;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-056 step 1d / ADR-057 Phase B: a devnet whose mempool admission runs through
 * {@code yano.validation.engine=amaru} (the real module, {@code phase2: scalus}), with the canonical snapshot,
 * ticked view and mempool overlay of the runtime wiring.
 */
class AmaruDevnetAdmissionTest {

    @Test
    void paymentsAreJudgedByTheAmaruEngine() throws Exception {
        YanoDevnetTestConfig config = YanoDevnetTestConfig.builder()
                .temporaryRocksDbStorage()
                .runtimeOption(YanoPropertyKeys.Validation.ENGINE, "amaru")
                .runtimeOption(YanoPropertyKeys.Validation.AMARU_POOL_SIZE, "1")
                .build();
        try (config; Yano node = YanoDevnetAssembly.devnet(config.yanoConfig())
                .runtimeOptions(config.runtimeOptions())
                .transactionBootstrap(TransactionBootstrapOptions.enabled(true, false, "scalus"),
                        DefaultTransactionServicesFactory::create)
                .build()) {
            node.start();
            ValidationEngines engines = node.validationEngines().orElseThrow();
            assertThat(engines.admissionEngine().name()).isEqualTo("amaru");

            Account payer = new Account(Networks.testnet());
            FundResult funded = node.devnetControl().orElseThrow().fundAddress(payer.baseAddress(), 10_000_000_000L);
            Outpoint input = new Outpoint(funded.txHash(), funded.index());
            long deadline = System.currentTimeMillis() + 30_000;
            while (node.ledger().getUtxoState().getUtxo(input).isEmpty()) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("the funding UTxO never became visible");
                }
                Thread.sleep(50);
            }

            // Still in the first epoch: Yano persists its Conway genesis bootstrap only at the first boundary,
            // so governance reads come from the view-level genesis fallback (ADR-056 step 1d, 6a).
            // The testkit devnet profile's epochs are long enough that this test never reaches that boundary.

            // Fee one lovelace: Amaru rejects with the Haskell constructor.
            assertThatThrownBy(() -> node.txGateway().submitTransaction(payment(payer, input, funded.lovelace(), 1)))
                    .isInstanceOf(TransactionValidationException.class)
                    .hasMessageContaining("FeeTooSmallUTxO");

            byte[] valid = payment(payer, input, funded.lovelace(), 300_000);
            String hash = TxIdentity.txIdHex(valid);
            try {
                node.txGateway().submitTransaction(valid);
            } catch (IllegalArgumentException noUpstream) {
                // Admitted; relaying to an upstream peer fails because this test devnet has none.
                assertThat(noUpstream).hasMessageContaining("port");
            }
            assertThat(node.mempoolQueryGateway().resolveUtxo(new Outpoint(hash, 0))).isPresent();
            assertThat(engines.admissionHealthy()).isTrue();
        }
    }

    private static byte[] payment(Account payer, Outpoint input, long inputLovelace, long fee) throws Exception {
        Account payee = new Account(Networks.testnet());
        TransactionOutput pay = new TransactionOutput(payee.baseAddress(),
                Value.builder().coin(BigInteger.valueOf(5_000_000)).build());
        TransactionOutput change = new TransactionOutput(payer.baseAddress(),
                Value.builder().coin(BigInteger.valueOf(inputLovelace - 5_000_000 - fee)).build());
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(input.txHash(), input.index())))
                .outputs(List.of(pay, change))
                .fee(BigInteger.valueOf(fee))
                .build();
        Transaction tx = Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).isValid(true).build();
        return payer.sign(tx).serialize();
    }
}
