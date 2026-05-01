package com.bloxbean.cardano.yano.wallet.core.tx;

import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.hdwallet.Wallet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QuickAdaTxServiceTest {
    private static final String MNEMONIC =
            "drive useless envelope shine range ability time copper alarm museum near flee wrist live type device meadow allow churn purity wisdom praise drop code";

    @Test
    void sendsChangeBackToSenderBaseAddressByDefault() throws Exception {
        Wallet sender = Wallet.createFromMnemonic(Networks.preprod(), MNEMONIC);
        String senderAddress = sender.getBaseAddressString(0);
        String receiverAddress = Wallet.create(Networks.preprod()).getBaseAddressString(0);
        UtxoSupplier utxoSupplier = singleUtxoSupplier(senderAddress);

        QuickAdaTxDraft draft = new QuickAdaTxService().buildSignedDraft(
                sender,
                utxoSupplier,
                this::protocolParams,
                new NoopTransactionProcessor(),
                receiverAddress,
                BigInteger.valueOf(1_000_000));

        Transaction transaction = Transaction.deserialize(HexUtil.decodeHexString(draft.cborHex()));

        assertThat(draft.fromAddress()).isEqualTo(senderAddress);
        assertThat(transaction.getBody().getOutputs())
                .anySatisfy(output -> assertThat(output.getAddress()).isEqualTo(senderAddress));
    }

    private UtxoSupplier singleUtxoSupplier(String address) {
        return new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String queryAddress, Integer nrOfItems, Integer page, OrderEnum order) {
                if (!address.equals(queryAddress) || page != 0) {
                    return List.of();
                }
                return List.of(Utxo.builder()
                        .address(address)
                        .txHash("7e1eecf7439fb5119a6762985a61c9fb3ca8158d9fc38361f0c4746430d5e0c7")
                        .outputIndex(0)
                        .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000))))
                        .build());
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return Optional.empty();
            }

            @Override
            public boolean isUsedAddress(Address candidate) {
                return address.equals(candidate.toBech32());
            }
        };
    }

    private ProtocolParams protocolParams() {
        return ProtocolParams.builder()
                .minFeeA(44)
                .minFeeB(155381)
                .minUtxo("1000000")
                .coinsPerUtxoSize("4312")
                .minFeeRefScriptCostPerByte(BigDecimal.valueOf(15))
                .build();
    }

    private static class NoopTransactionProcessor implements TransactionProcessor {
        @Override
        public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) {
            return Result.success("not evaluated").withValue(List.of());
        }

        @Override
        public Result<String> submitTransaction(byte[] cborData) {
            return Result.success("not submitted");
        }
    }
}
