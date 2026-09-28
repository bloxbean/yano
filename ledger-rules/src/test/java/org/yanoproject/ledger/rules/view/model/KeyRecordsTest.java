package org.yanoproject.ledger.rules.view.model;

import org.junit.jupiter.api.Test;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.effects.LedgerChange.CommitteeResigned;
import org.yanoproject.ledger.rules.effects.TxEffects;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyRecordsTest {

    private static final String H28 = "ab".repeat(28);
    private static final String H32 = "cd".repeat(32);

    @Test
    void keysAreLowercasedAndLengthChecked() {
        assertThat(CredentialKey.key(H28.toUpperCase()).hashHex()).isEqualTo(H28);
        assertThat(new PoolId(H28.toUpperCase()).hashHex()).isEqualTo(H28);
        assertThat(new GovActionId(H32.toUpperCase(), 1).txHashHex()).isEqualTo(H32);
        assertThat(Outpoints.normalize(new Outpoint(H32.toUpperCase(), 0))).isEqualTo(new Outpoint(H32, 0));

        assertThatThrownBy(() -> CredentialKey.key("ab".repeat(27))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("28 bytes");
        assertThatThrownBy(() -> CredentialKey.script(H32)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PoolId(H32)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GovActionId(H28, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> Outpoints.of(H28, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Outpoints.of(H32, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PoolState(new PoolId(H28), BigInteger.ONE, H28, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TxEffects.ofChanges(H28, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CredentialKey.key("zz".repeat(28))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void phase2InvalidEffectsCannotCarryLedgerChanges() {
        assertThatThrownBy(() -> new TxEffects(H32, false, List.of(), List.of(),
                List.of(new CommitteeResigned(CredentialKey.key(H28)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phase-2-invalid");
    }
}
