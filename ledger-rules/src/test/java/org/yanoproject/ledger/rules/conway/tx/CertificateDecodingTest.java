package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a certificate's reading accepts and refuses where the dataset has no sample that tells (ADR 0003 I7). */
class CertificateDecodingTest {

    private static final String HASH28 = "581c" + "11".repeat(28);
    private static final String HASH32 = "5820" + "22".repeat(32);
    private static final String ACCOUNT = "581de0" + "44".repeat(28);
    private static final String CRED = "8200" + HASH28;
    private static final String TAGGED = "d81e";
    private static final String CHUNKED_HASH28 = "5f" + "4111" + "581b" + "11".repeat(27) + "ff";

    private static String pool(String account, String owners, String relays, String metadata) {
        return "8a03" + HASH28 + HASH32 + "0000" + "d81e820001" + account + owners + relays + metadata;
    }

    private static RawCertificate read(String certificate) {
        return RawCertificate.read(StrictCbor.span(HexUtil.decodeHexString(certificate)), 0);
    }

    private static void decodes(String certificate) {
        assertThatCode(() -> read(certificate)).doesNotThrowAnyException();
    }

    private static void refuses(String certificate) {
        assertThatThrownBy(() -> read(certificate)).isInstanceOf(TxDecodingException.class);
    }

    @Test
    void aRecordIsNeverTagged() {
        decodes("8200" + CRED);
        refuses(TAGGED + "8200" + CRED);
        refuses("8200" + TAGGED + CRED);
        refuses("8200" + "8200" + TAGGED + HASH28);
        decodes("8309" + CRED + "8102");
        refuses("8309" + CRED + TAGGED + "8102");
        decodes("830f" + CRED + "82" + "60" + HASH32);
        refuses("830f" + CRED + TAGGED + "82" + "60" + HASH32);
        refuses("830f" + CRED + TAGGED + "f6");
    }

    @Test
    void aSumOrAPoolsMetadataMayBeIndefiniteAndAnAnchorNot() {
        decodes("9f00" + CRED + "ff");
        decodes("8200" + "9f00" + HASH28 + "ff");
        decodes("8309" + CRED + "9f02ff");
        decodes(pool(ACCOUNT, "80", "9f" + "9f02" + "6161" + "ff" + "ff", "9f" + "6161" + "4100" + "ff"));
        refuses("830f" + CRED + "9f" + "60" + HASH32 + "ff");
    }

    @Test
    void anEmptyOrShortOrLongCertificate() {
        refuses("80");
        refuses("8100");
        refuses("8300" + CRED + "00");
        refuses("8101");
        refuses("820000");
    }

    @Test
    void aTagOutsideConwayOrUnknown() {
        refuses("8405" + HASH28 + HASH28 + HASH32);
        refuses("8206" + "820000");
        refuses("8213" + CRED);
        refuses("82" + "c24100" + CRED);
        refuses("82" + "20" + CRED);
        decodes("82" + "1800" + CRED);
    }

    @Test
    void aRewardAccountIsAnAccountAddress() {
        decodes(pool(ACCOUNT, "80", "80", "f6"));
        decodes(pool("581de1" + "44".repeat(28), "80", "80", "f6"));
        decodes(pool("581df0" + "44".repeat(28), "80", "80", "f6"));
        refuses(pool("581de2" + "44".repeat(28), "80", "80", "f6"));
        refuses(pool("581d60" + "44".repeat(28), "80", "80", "f6"));
        refuses(pool("581ce0" + "44".repeat(27), "80", "80", "f6"));
        refuses(pool("581ee0" + "44".repeat(29), "80", "80", "f6"));
        refuses(pool(TAGGED + ACCOUNT, "80", "80", "f6"));
        refuses(pool("5f" + "41e0" + "581c" + "44".repeat(28) + "ff", "80", "80", "f6"));
    }

    @Test
    void poolOwnersAreASetOfKeyHashes() {
        decodes(pool(ACCOUNT, "81" + HASH28, "80", "f6"));
        decodes(pool(ACCOUNT, "d9010281" + HASH28, "80", "f6"));
        decodes(pool(ACCOUNT, "9f" + HASH28 + "ff", "80", "f6"));
        refuses(pool(ACCOUNT, "81" + "581b" + "11".repeat(27), "80", "f6"));
        refuses(pool(ACCOUNT, "81" + CHUNKED_HASH28, "80", "f6"));
        refuses(pool(ACCOUNT, "81" + TAGGED + HASH28, "80", "f6"));
        refuses(pool(ACCOUNT, TAGGED + "81" + HASH28, "80", "f6"));
        refuses(pool(ACCOUNT, "a0", "80", "f6"));
        assertThat(read(pool(ACCOUNT, "82" + HASH28 + "581c" + "33".repeat(28), "80", "f6")).poolOwners()).hasSize(2);
    }

    @Test
    void relaysAreAnUntaggedArray() {
        decodes(pool(ACCOUNT, "80", "81" + "8202" + "6161", "f6"));
        refuses(pool(ACCOUNT, "80", "d9010280", "f6"));
        refuses(pool(ACCOUNT, "80", TAGGED + "80", "f6"));
        refuses(pool(ACCOUNT, "80", "a0", "f6"));
        refuses(pool(ACCOUNT, "80", "81" + TAGGED + "8202" + "6161", "f6"));
        refuses(pool(ACCOUNT, "80", "81" + "8202" + "f6", "f6"));
        refuses(pool(ACCOUNT, "80", "81" + "8400" + TAGGED + "f6" + "f6f6", "f6"));
    }

    @Test
    void poolMetadataHasAnyHashSizeForTheRulesToCheck() {
        assertThat(read(pool(ACCOUNT, "80", "80", "82" + "6161" + "4100")).pool().metadataHashSize()).isEqualTo(1);
        assertThat(read(pool(ACCOUNT, "80", "80", "f6")).pool().metadataHashSize()).isNull();
        refuses(pool(ACCOUNT, "80", "80", TAGGED + "82" + "6161" + "4100"));
        refuses(pool(ACCOUNT, "80", "80", "82" + "6161" + "5f4100ff"));
        refuses(pool(ACCOUNT, "80", "80", "83" + "6161" + "4100" + "00"));
    }

    @Test
    void aCoinIsAnUntaggedUnsignedInteger() {
        decodes("8307" + CRED + "1bffffffffffffffff");
        refuses("8307" + CRED + "c249010000000000000000");
        refuses("8307" + CRED + "20");
        refuses("8307" + CRED + TAGGED + "00");
    }

    @Test
    void theRecordHoldsWhatTheRulesRead() {
        byte[] pool = HexUtil.decodeHexString("22".repeat(28));
        RawCertificate delegation = read("850d" + CRED + "581c" + "22".repeat(28) + "8201" + HASH28 + "05");
        assertThat(delegation.tag()).isEqualTo(RawCertificate.STAKE_VOTE_REG_DELEG);
        assertThat(delegation.credential().script()).isFalse();
        assertThat(delegation.delegatee().pool()).isEqualTo(pool);
        assertThat(delegation.delegatee().drep().kind()).isEqualTo(1);
        assertThat(delegation.delegatee().drep().credential().script()).isTrue();
        assertThat(delegation.coin()).isEqualTo(BigInteger.valueOf(5));

        RawCertificate retirement = read("8304" + HASH28 + "1bffffffffffffffff");
        assertThat(retirement.credential()).isNull();
        assertThat(retirement.poolId()).hasSize(28);
        assertThat(retirement.epoch()).isEqualTo(new BigInteger("18446744073709551615"));

        RawCertificate hot = read("830e" + CRED + "8201" + HASH28);
        assertThat(hot.hot().script()).isTrue();
        assertThat(hot.delegatee()).isNull();

        RawCertificate registration = read(pool(ACCOUNT, "81" + HASH28, "80", "f6"));
        assertThat(registration.credential()).isNull();
        assertThat(registration.poolId()).hasSize(28);
        assertThat(registration.pool().vrfKeyHash()).hasSize(32);
        assertThat(registration.pool().cost()).isEqualTo(BigInteger.ZERO);
        assertThat(registration.pool().rewardAccountNetwork()).isZero();
    }
}
