package org.yanoproject.ledger.conformance.engines;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.plutus.spec.CostMdls;
import com.bloxbean.cardano.client.plutus.spec.Language;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.ConformanceEngine;
import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.conway.LedgerContext;
import org.yanoproject.ledger.rules.conway.LedgerStateValidator;
import org.yanoproject.ledger.rules.conway.RuleValidationResult;
import org.yanoproject.ledger.rules.view.slice.SimpleUtxoSlice;
import org.yanoproject.ledger.rules.view.slice.yaci.YaciAccountsSlice;
import org.yanoproject.ledger.rules.view.slice.yaci.YaciCommitteeSlice;
import org.yanoproject.ledger.rules.view.slice.yaci.YaciDRepsSlice;
import org.yanoproject.ledger.rules.view.slice.yaci.YaciPoolsSlice;
import org.yanoproject.ledger.rules.view.slice.yaci.YaciProposalsSlice;

import java.util.List;

/**
 * The current Java rules: the copied CCL {@link LedgerStateValidator} with all ten default rules, over slices
 * adapted from the case's view (the UTxO slice from the resolved inputs, the account, pool, DRep, committee and
 * proposal slices through {@link ViewStateProvider}), the case's slot, epoch, network and parameters, and cost
 * models from the parameters for the script-data-hash check.
 *
 * <p>These rules are independent checks, not a state transition: they report every failure they find, in rule
 * order. Their free-text errors are named after Haskell by {@link LegacyFailureNames}; a transaction that CCL
 * cannot decode is reported as {@code ENGINE.DecodingFailure}.</p>
 */
public final class JavaLegacyEngine implements ConformanceEngine {

    @Override
    public String name() {
        return "java-legacy";
    }

    @Override
    public String description() {
        return "the copied CCL rules (LedgerStateValidator, 10 rules) over slices adapted from the view";
    }

    @Override
    public Observation validate(ConformanceCase testCase) {
        byte[] txCbor = testCase.txCbor();
        Transaction tx;
        try {
            tx = Transaction.deserialize(txCbor);
        } catch (Exception e) {
            return Observation.rejected(List.of(new Observation.Failure("ENGINE", "DecodingFailure",
                    Observation.abbreviate(e.getMessage()))));
        }
        ProtocolParams params = testCase.view().protocolParams().require("protocol parameters");
        ResolvedInputs resolved = ResolvedInputs.resolve(tx, testCase.view());
        ViewStateProvider provider = new ViewStateProvider(testCase.view(), testCase.env().currentEpoch());
        LedgerContext context = LedgerContext.builder()
                .protocolParams(params)
                .currentSlot(testCase.env().currentSlot())
                .currentEpoch(testCase.env().currentEpoch())
                .currentTransactionHash(TxIdentity.txIdHex(txCbor))
                .networkId(testCase.env().networkId())
                .costMdls(costModels(params))
                .utxoSlice(new SimpleUtxoSlice(resolved.outputs()))
                .accountsSlice(new YaciAccountsSlice(provider))
                .poolsSlice(new YaciPoolsSlice(provider))
                .drepsSlice(new YaciDRepsSlice(provider))
                .committeeSlice(new YaciCommitteeSlice(provider))
                .proposalsSlice(new YaciProposalsSlice(provider))
                .build();
        RuleValidationResult result = LedgerStateValidator.builder().build().validate(context, tx);
        if (result.isValid()) {
            return Observation.accepted();
        }
        int major = testCase.env().protocolMajor();
        return Observation.rejected(result.getErrors().stream()
                .map(e -> LegacyFailureNames.javaRule(e.getRule(), e.getMessage(), major, testCase.view()))
                .toList());
    }

    private static CostMdls costModels(ProtocolParams params) {
        CostMdls costMdls = new CostMdls();
        for (Language language : List.of(Language.PLUTUS_V1, Language.PLUTUS_V2, Language.PLUTUS_V3)) {
            CostModelUtil.getCostModelFromProtocolParams(params, language).ifPresent(costMdls::add);
        }
        return costMdls;
    }
}
