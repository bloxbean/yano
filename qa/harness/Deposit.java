import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.List;

/**
 * Build + sign a real L1 tx spending a (faucet) UTxO owned by the seed's
 * enterprise address and paying to args[4]. Prints: address, signed tx hex.
 * args: seedHex utxoTxHash utxoIndex utxoLovelace toAddress fee
 */
public class Deposit {
    public static void main(String[] args) throws Exception {
        SecretKey sk = SecretKey.create(HexUtil.decodeHexString(args[0]));
        VerificationKey vk = KeyGenUtil.getPublicKeyFromPrivateKey(sk);
        Address from = AddressProvider.getEntAddress(
                Credential.fromKey(KeyGenUtil.getKeyHash(vk)), Networks.testnet());
        System.err.println("from=" + from.toBech32());
        BigInteger in = new BigInteger(args[3]);
        BigInteger fee = new BigInteger(args[5]);
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(args[1], Integer.parseInt(args[2]))))
                .outputs(List.of(TransactionOutput.builder()
                        .address(args[4])
                        .value(com.bloxbean.cardano.client.transaction.spec.Value.builder()
                                .coin(in.subtract(fee)).build())
                        .build()))
                .fee(fee)
                .build();
        Transaction tx = Transaction.builder().body(body)
                .witnessSet(new TransactionWitnessSet()).build();
        Transaction signed = TransactionSigner.INSTANCE.sign(tx, sk);
        System.out.println(HexUtil.encodeHexString(signed.serialize()));
    }
}
