package org.yanoproject.ledger.conformance.diversity.cbor;

import com.bloxbean.cardano.client.ledger.conformance.CborConformance;
import com.bloxbean.cardano.client.ledger.conformance.CborRule;
import com.bloxbean.cardano.client.ledger.conformance.SampleOutcome;
import com.bloxbean.cardano.client.transaction.raw.HaskellPolicy;
import com.bloxbean.cardano.client.transaction.raw.LedgerDecodingException;
import com.bloxbean.cardano.client.transaction.raw.RawBlock;
import com.bloxbean.cardano.client.transaction.raw.RawTx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.diversity.NodeDiversity;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CCL ADR 0005 J5 and J1's admission check, over the dataset: for every sample the strict reads accept that lives in a
 * transaction, placed in its minimal transaction ({@link CborRule#transactionFor}), and every transaction of an
 * accepted block, admission's decoding as {@code JavaLedgerValidationEngine} runs it: CCL's strict read, then CCL's
 * model, then Yano's mapping into its rules' records ({@link RawTransaction#of}). Once the strict read accepts, the
 * model decodes (no model gap at the pin) and the mapping rejects nothing.
 *
 * <p>A part read by its own type may sit in a transaction its parent's rules refuse (a duplicate in a set, say): that
 * transaction is not accepted, and not mapped. The test needs the corpora, as the report does.</p>
 */
class CborMappingDiversityReportTest {

    @Test
    void whatTheStrictReadAcceptsIsMappedWithoutARejection() throws IOException {
        Path dataset = NodeDiversity.requireCorpus(CborDecodingDiversityReportTest.DATASET);
        Path conway = dataset.resolve("conway");
        HaskellPolicy policy = HaskellPolicy.CONWAY;
        int mapped = 0;
        int refusedAround = 0;
        List<String> failures = new ArrayList<>();
        for (SampleOutcome outcome : CborConformance.run(dataset)) {
            if (outcome.strict() != SampleOutcome.Strict.ACCEPTED) {
                continue;
            }
            byte[] sample = Files.readAllBytes(conway.resolve(outcome.path()));
            List<byte[]> transactions = new ArrayList<>();
            if (outcome.rule().equals("block")) {
                RawBlock block = policy.block(sample);
                for (int i = 0; i < block.txCount(); i++) {
                    transactions.add(block.txBytes(i));
                }
            } else {
                byte[] tx = CborRule.transactionFor(outcome.rule(), sample);
                if (tx != null) {
                    transactions.add(tx);
                }
            }
            for (byte[] tx : transactions) {
                RawTx accepted;
                try {
                    accepted = policy.transaction(tx);
                } catch (LedgerDecodingException e) {
                    refusedAround++;
                    continue;
                }
                try {
                    RawTransaction.of(accepted, tx, Transaction.deserialize(tx));
                    mapped++;
                } catch (Exception e) {
                    failures.add(outcome.path() + ": " + e);
                }
            }
        }
        System.out.println("cbor-decoding mapping (J5): " + mapped + " accepted transactions mapped, " + refusedAround
                + " placed parts in a transaction its other rules refuse");
        assertThat(mapped).isGreaterThan(5_000);
        assertThat(failures).as("a model gap or a rejection after the strict read accepted (J5)").isEmpty();
    }
}
