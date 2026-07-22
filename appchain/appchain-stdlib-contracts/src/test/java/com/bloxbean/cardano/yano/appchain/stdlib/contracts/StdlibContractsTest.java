package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.StdlibContractCbor;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StdlibContractsTest {
    @Test
    void commandContractsRoundTripCanonicalBoundedValues() {
        assertThat(KvRegistryContract.decodeCommand(
                KvRegistryContract.put(new byte[]{1}, new byte[]{2})))
                .satisfies(command -> {
                    assertThat(command.put()).isTrue();
                    assertThat(command.key()).containsExactly(1);
                    assertThat(command.value()).containsExactly(2);
                });
        assertThat(ApprovalsContract.decodeCommand(
                ApprovalsContract.propose("A-1", new byte[]{3}, 2, 100)))
                .satisfies(command -> {
                    assertThat(command.itemId()).isEqualTo("A-1");
                    assertThat(command.required()).isEqualTo(2);
                });
        assertThat(BalancesContract.decodeCommand(
                BalancesContract.transfer("account-2", BigInteger.TEN)).amount())
                .isEqualTo(BigInteger.TEN);
        assertThat(DocTrailContract.decodeCommand(
                DocTrailContract.append("case-1", new byte[]{4}, "ipfs://cid")).reference())
                .isEqualTo("ipfs://cid");
    }

    @Test
    void stateContractsDecodeAuthenticatedValuesAndCloneMutableInputs() {
        byte[] owner = new byte[32];
        owner[0] = 7;
        Array entry = new Array();
        entry.add(new ByteString(owner));
        entry.add(new ByteString(new byte[]{8}));

        KvRegistryContract.Entry decoded = KvRegistryContract.decodeEntry(
                StdlibContractCbor.encode(entry));
        owner[0] = 0;

        assertThat(decoded.owner()[0]).isEqualTo((byte) 7);
        assertThat(decoded.value()).containsExactly(8);
        assertThat(BalancesContract.decodeBalance(new byte[]{10})).isEqualTo(BigInteger.TEN);
        assertThat(DocTrailContract.computeHead(
                List.of(new byte[]{1}), List.of(new byte[32]))).hasSize(32);
    }

    @Test
    void decoderRejectsNonCanonicalIndefiniteAndOversizedInputs() {
        byte[] canonical = KvRegistryContract.put(new byte[]{1}, new byte[]{2});
        byte[] nonCanonical = new byte[canonical.length + 1];
        nonCanonical[0] = canonical[0];
        nonCanonical[1] = 0x18;
        nonCanonical[2] = 0;
        System.arraycopy(canonical, 2, nonCanonical, 3, canonical.length - 2);

        assertThatThrownBy(() -> KvRegistryContract.decodeCommand(nonCanonical))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalsContract.decodeCommand(new byte[]{(byte) 0x9f, (byte) 0xff}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KvRegistryContract.decodeCommand(
                new byte[StdlibContractCbor.MAX_WIRE_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandsAreBoundedByTheProofSafeStateKeyLimit() {
        assertThat(KvRegistryContract.decodeCommand(
                KvRegistryContract.put(new byte[]{1}, new byte[0])).value()).isEmpty();
        assertThatThrownBy(() -> KvRegistryContract.put(new byte[257], new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalsContract.propose("a".repeat(252), new byte[0], 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BalancesContract.mint("a".repeat(255), BigInteger.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocTrailContract.append(
                "a".repeat(255), new byte[]{1}, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvalItemDecoderBoundsApproverIdentities() {
        Array item = new Array();
        item.add(new UnsignedInteger(0));
        item.add(new ByteString(new byte[32]));
        item.add(new ByteString(new byte[32]));
        item.add(new UnsignedInteger(2));
        item.add(new UnsignedInteger(0));
        Array approvers = new Array();
        approvers.add(new ByteString(new byte[32]));
        item.add(approvers);
        item.add(new ByteString(new byte[0]));

        ApprovalsContract.Item decoded = ApprovalsContract.decodeItem(
                StdlibContractCbor.encode(item));

        assertThat(decoded.required()).isEqualTo(2);
        assertThat(decoded.approvers()).hasSize(1);
    }
}
