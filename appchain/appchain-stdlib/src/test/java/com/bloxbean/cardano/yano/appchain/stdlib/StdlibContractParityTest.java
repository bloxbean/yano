package com.bloxbean.cardano.yano.appchain.stdlib;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.ApprovalsContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.BalancesContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.DocTrailContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.KvRegistryContract;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StdlibContractParityTest {
    @Test
    void portableCommandsRemainByteIdenticalToRuntimeHelpers() {
        assertThat(KvRegistryContract.put(new byte[]{1}, new byte[]{2}))
                .isEqualTo(KvRegistryStateMachine.put(new byte[]{1}, new byte[]{2}));
        assertThat(KvRegistryContract.delete(new byte[]{1}))
                .isEqualTo(KvRegistryStateMachine.delete(new byte[]{1}));
        assertThat(ApprovalsContract.propose("A-1", new byte[]{3}, 2, 100))
                .isEqualTo(ApprovalsStateMachine.propose("A-1", new byte[]{3}, 2, 100));
        assertThat(ApprovalsContract.approve("A-1"))
                .isEqualTo(ApprovalsStateMachine.approve("A-1"));
        assertThat(ApprovalsContract.reject("A-1"))
                .isEqualTo(ApprovalsStateMachine.reject("A-1"));
        assertThat(BalancesContract.mint("acct", BigInteger.TEN))
                .isEqualTo(BalancesStateMachine.mint("acct", BigInteger.TEN));
        assertThat(BalancesContract.transfer("acct", BigInteger.ONE))
                .isEqualTo(BalancesStateMachine.transfer("acct", BigInteger.ONE));
        assertThat(DocTrailContract.append("case-1", new byte[]{4}, "doc"))
                .isEqualTo(DocTrailStateMachine.append("case-1", new byte[]{4}, "doc"));
    }

    @Test
    void portableKeysAndStateDecodersMatchRuntimeLayouts() {
        byte[] owner = new byte[32];
        owner[0] = 9;
        Array kv = new Array();
        kv.add(new ByteString(owner));
        kv.add(new ByteString(new byte[]{5}));
        byte[] kvBytes = CborSerializationUtil.serialize(kv);
        assertThat(KvRegistryContract.decodeEntry(kvBytes).owner())
                .isEqualTo(KvRegistryStateMachine.decodeOwner(kvBytes));
        assertThat(KvRegistryContract.decodeEntry(kvBytes).value())
                .isEqualTo(KvRegistryStateMachine.decodeValue(kvBytes));

        ApprovalsStateMachine.Item item = new ApprovalsStateMachine.Item(
                0, owner, new byte[32], 2, 100, List.of(owner), new byte[0]);
        ApprovalsContract.Item decoded = ApprovalsContract.decodeItem(item.encode());
        assertThat(decoded.status()).isEqualTo(item.status());
        assertThat(decoded.proposer()).isEqualTo(item.proposer());
        assertThat(decoded.approvers()).usingElementComparator(java.util.Arrays::compare)
                .containsExactlyElementsOf(item.approvers());

        assertThat(BalancesContract.accountKey("acct"))
                .isEqualTo(BalancesStateMachine.accountKey("acct"));
        assertThat(BalancesContract.decodeBalance(new byte[]{10}))
                .isEqualTo(BalancesStateMachine.decodeBalance(new byte[]{10}));

        DocTrailStateMachine.Entry head = new DocTrailStateMachine.Entry(1, new byte[32]);
        assertThat(DocTrailContract.decodeHead(head.encode()).count()).isEqualTo(head.count());
        assertThat(DocTrailContract.entityKey("case-1"))
                .isEqualTo(DocTrailStateMachine.entityKey("case-1"));
        assertThat(DocTrailContract.computeHead(List.of(new byte[]{1}), List.of(owner)))
                .isEqualTo(DocTrailStateMachine.computeHead(
                        List.of(new byte[]{1}), List.of(owner)));
    }

    @Test
    void runtimeAdmissionParsersSharePortableProofKeyBounds() {
        assertThatThrownBy(() -> KvRegistryStateMachine.Command.decode(
                rawKvCommand(new byte[257], new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalsStateMachine.Command.decode(
                rawApprovalCommand("a".repeat(252))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BalancesStateMachine.Command.decode(
                rawBalanceCommand("a".repeat(255))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocTrailStateMachine.Command.decode(
                rawDocTrailCommand("a".repeat(255))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] rawKvCommand(byte[] key, byte[] value) {
        Array command = new Array();
        command.add(new UnsignedInteger(0));
        command.add(new ByteString(key));
        command.add(new ByteString(value));
        return CborSerializationUtil.serialize(command);
    }

    private static byte[] rawApprovalCommand(String itemId) {
        Array command = new Array();
        command.add(new UnsignedInteger(1));
        command.add(new UnicodeString(itemId));
        return CborSerializationUtil.serialize(command);
    }

    private static byte[] rawBalanceCommand(String account) {
        Array command = new Array();
        command.add(new UnsignedInteger(0));
        command.add(new UnicodeString(account));
        command.add(new UnsignedInteger(1));
        return CborSerializationUtil.serialize(command);
    }

    private static byte[] rawDocTrailCommand(String entityId) {
        Array command = new Array();
        command.add(new UnicodeString(entityId));
        command.add(new ByteString(new byte[]{1}));
        command.add(new UnicodeString(""));
        return CborSerializationUtil.serialize(command);
    }
}
