package org.yanoproject.ledger.conformance.blueprint;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.fixtures.blueprint.BlueprintVectorLoader;
import org.yanoproject.ledger.rules.fixtures.blueprint.ConwayPParamsDecoder;
import org.yanoproject.ledger.rules.fixtures.blueprint.NewEpochStateDecoder;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepTarget;
import org.yanoproject.ledger.rules.view.model.PoolId;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code NewEpochState} decoder's paths the pinned vectors do not exercise (the later account layout, the
 * fail-closed cases, the vector parser), on hand-built states. The populated paths the vectors do exercise (UMap
 * accounts, pools, DReps, committee state, proposals, roots) are checked against Haskell's final states by
 * {@code BlueprintVectorGateTest}.
 */
class NewEpochStateDecoderTest {

    private static final String PP_HASH = "11".repeat(32);
    private static final String STAKE = "aa".repeat(28);
    private static final String POOL = "bb".repeat(28);
    private static final String DREP = "cc".repeat(28);

    @Test
    void decodesTheUMapAccountLayoutOfTheVectors() {
        // UMElem = [StrictMaybe [reward, deposit], ptrs, StrictMaybe pool, StrictMaybe drep]; an element without the
        // reward/deposit pair is not a registration.
        byte[] accounts = cbor(w -> {
            w.arr(2).map(2);
            credential(w, 0, STAKE);
            w.arr(4).arr(1).arr(2).uint(7).uint(2_000_000).arr(0).arr(1).bytes(POOL).arr(1).arr(2).uint(0).bytes(DREP);
            credential(w, 1, DREP);
            w.arr(4).arr(0).arr(0).arr(0).arr(0);
            w.map(0);
        });
        NewEpochStateDecoder.Decoded decoded = decoder(pparams(false)).decode(nes(accounts, utxoCbor(), pstate(false)));

        assertThat(decoded.unsupported()).isEmpty();
        assertThat(decoded.view().account(CredentialKey.key(STAKE)).orElseThrowUnavailable()).contains(
                new AccountState(CredentialKey.key(STAKE), BigInteger.valueOf(2_000_000), BigInteger.valueOf(7),
                        new PoolId(POOL), DRepTarget.credential(CredentialKey.key(DREP))));
        assertThat(decoded.view().account(CredentialKey.script(DREP)).isAbsent()).isTrue();
        assertThat(decoded.keys().accounts()).containsExactly(CredentialKey.key(STAKE));
    }

    @Test
    void decodesTheLaterConwayAccountsLayout() {
        // cardano-ledger f649f975: {credential => [balance, deposit, pool / null, drep / null]}
        byte[] accounts = cbor(w -> {
            w.map(1);
            credential(w, 0, STAKE);
            w.arr(4).uint(5).uint(2_000_000).nul().arr(1).uint(2);
        });
        NewEpochStateDecoder.Decoded decoded = decoder(pparams(false)).decode(nes(accounts, utxoCbor(), pstate(false)));

        assertThat(decoded.unsupported()).isEmpty();
        assertThat(decoded.view().account(CredentialKey.key(STAKE)).orElseThrowUnavailable()).contains(
                new AccountState(CredentialKey.key(STAKE), BigInteger.valueOf(2_000_000), BigInteger.valueOf(5), null,
                        DRepTarget.ALWAYS_ABSTAIN));
    }

    @Test
    void failsClosedOnAMemPackUtxo() {
        byte[] memPack = cbor(w -> w.map(1).bytes("00".repeat(34)).bytes("00".repeat(40)));
        NewEpochStateDecoder.Decoded decoded = decoder(pparams(false)).decode(nes(emptyUMap(), memPack, pstate(false)));

        assertThat(decoded.ok()).isFalse();
        assertThat(decoded.view()).isNull();
        assertThat(decoded.unsupported()).singleElement().asString().startsWith("UTxO: MemPack-encoded TxIn keys");
    }

    @Test
    void failsClosedOnTheLaterPStateLayout() {
        NewEpochStateDecoder.Decoded decoded = decoder(pparams(false)).decode(nes(emptyUMap(), utxoCbor(), pstate(true)));

        assertThat(decoded.ok()).isFalse();
        assertThat(decoded.unsupported()).singleElement().asString().contains("later PState layout");
    }

    @Test
    void failsClosedWithoutTheParameterRecord() {
        NewEpochStateDecoder decoder = new NewEpochStateDecoder(hash -> Optional.empty());
        NewEpochStateDecoder.Decoded decoded = decoder.decode(nes(emptyUMap(), utxoCbor(), pstate(false)));

        assertThat(decoded.unsupported()).singleElement().asString()
                .contains("no protocol-parameter record for hash " + PP_HASH);
    }

    @Test
    void roundsOnlyTheRationalsTheTransactionRulesDoNotRead() {
        List<String> notes = new ArrayList<>();
        ConwayPParamsDecoder.decode(pparams(true), notes);
        assertThat(notes).singleElement().asString().startsWith("pvtHardForkInitiation = 2/3 rounded");

        // A price of 2/3 lovelace per unit cannot be represented exactly: the decode fails (closed).
        byte[] badPrice = pparams(false, 2, 3);
        assertThat(decoder(badPrice).decode(nes(emptyUMap(), utxoCbor(), pstate(false))).unsupported())
                .singleElement().asString().contains("rational 2/3 has no exact decimal form");
    }

    @Test
    void parsesAVector() {
        byte[] vector = cbor(w -> {
            w.arr(5);
            w.arr(13).uint(4320).uint(1).uint(4320).uint(129600).uint(1620).uint(2160).uint(108).uint(62).uint(5)
                    .uint(45_000_000_000_000_000L).tag(30).arr(2).uint(1).uint(5).uint(0)
                    .arr(3).uint(2017).uint(266).uint(78_291_000_000_000_000L);
            w.raw(nes(emptyUMap(), utxoCbor(), pstate(false)));
            w.raw(nes(emptyUMap(), utxoCbor(), pstate(false)));
            w.arrIndefinite();
            w.arr(2).uint(1).uint(1);
            w.arr(4).uint(0).bytes("84a0").bool(true).uint(4321);
            w.arr(2).uint(2).uint(1);
            w.brk();
            w.text("Conway/Imp/Example");
        });
        var parsed = BlueprintVectorLoader.parse("conway/example/0", vector);

        assertThat(parsed.group()).isEqualTo("conway");
        assertThat(parsed.title()).isEqualTo("Conway/Imp/Example");
        assertThat(parsed.config().systemStartEpochMillis()).isEqualTo(1_506_203_091_000L); // mainnet's system start
        assertThat(parsed.config().epochOf(4321 + 4320)).isEqualTo(2);
        assertThat(parsed.events()).hasSize(3);
        assertThat(parsed.transactions()).singleElement().satisfies(tx -> {
            assertThat(tx.success()).isTrue();
            assertThat(tx.slot()).isEqualTo(4321);
        });
    }

    // ------------------------------------------------------------------------------------------ builders

    private static NewEpochStateDecoder decoder(byte[] pparams) {
        return new NewEpochStateDecoder(hash -> PP_HASH.equals(hash) ? Optional.of(pparams) : Optional.empty());
    }

    private static byte[] emptyUMap() {
        return cbor(w -> w.arr(2).map(0).map(0));
    }

    private static byte[] utxoCbor() {
        // {[txid, 0] => [address, 1000000]}
        return cbor(w -> w.map(1).arr(2).bytes("22".repeat(32)).uint(0)
                .arr(2).bytes("60" + "dd".repeat(28)).uint(1_000_000));
    }

    /** The vectors' PState ({pool => params}, …), or the later one that starts with the VRF-key index. */
    private static byte[] pstate(boolean later) {
        return cbor(w -> {
            w.arr(4);
            if (later) {
                w.map(1).bytes("ee".repeat(32)).uint(1);
            } else {
                w.map(0);
            }
            w.map(0).map(0).map(0);
        });
    }

    private static byte[] nes(byte[] accounts, byte[] utxo, byte[] pstate) {
        return cbor(w -> {
            w.arr(7).uint(1).map(0).map(0);
            w.arr(4);
            w.arr(2).uint(0).uint(45_000_000_000_000_000L);                   // treasury, reserves
            w.arr(2);                                                         // LedgerState
            w.arr(3);                                                         // ConwayCertState
            w.arr(3).map(0).map(0).uint(0);                                   // VState
            w.raw(pstate);
            w.arr(4).raw(accounts).map(0).map(0).arr(4).map(0).map(0).uint(0).uint(0);
            w.arr(6).raw(utxo).uint(0).uint(0);                               // UTxOState
            w.arr(7);                                                         // ConwayGovState
            w.arr(2).arr(4).arr(0).arr(0).arr(0).arr(0).arr(0);               // proposals
            w.arr(0);                                                         // no committee
            w.arr(2).arr(2).text("https://example.com").bytes("33".repeat(32)).nul();
            w.bytes(PP_HASH).bytes(PP_HASH).arr(1).uint(0).uint(0);           // pparams, future, pulser
            w.arr(2).map(0).map(0).uint(0);                                   // instant stake, donation
            w.uint(0).uint(0);                                                // snapshots, non-myopic
            w.arr(0).arr(2).map(0).uint(0).nul();                             // reward update, pool distr, AVVM
        });
    }

    private static byte[] pparams(boolean twoThirdsThreshold) {
        return pparams(twoThirdsThreshold, 577, 10_000);
    }

    private static byte[] pparams(boolean twoThirdsThreshold, long priceNumerator, long priceDenominator) {
        return cbor(w -> {
            w.arr(ConwayPParamsDecoder.FIELDS);
            w.uint(44).uint(155_381).uint(65_536).uint(16_384).uint(1100).uint(2_000_000).uint(500_000_000).uint(18)
                    .uint(500);
            rational(w, 3, 10);
            rational(w, 3, 1000);
            rational(w, 1, 5);
            w.arr(2).uint(10).uint(0);
            w.uint(340_000_000).uint(4310);
            w.map(1).uint(2).arr(2).uint(1).uint(2);                          // PlutusV3: [1, 2]
            w.arr(2);
            rational(w, priceNumerator, priceDenominator);
            rational(w, 721, 10_000_000);
            w.arr(2).uint(14_000_000).uint(10_000_000_000L).arr(2).uint(62_000_000).uint(20_000_000_000L);
            w.uint(5000).uint(150).uint(3);
            w.arr(5);
            for (int i = 0; i < 5; i++) {
                if (twoThirdsThreshold && i == 3) {
                    rational(w, 2, 3);
                } else {
                    rational(w, 51, 100);
                }
            }
            w.arr(10);
            for (int i = 0; i < 10; i++) {
                rational(w, 67, 100);
            }
            w.uint(7).uint(146).uint(6).uint(100_000_000_000L).uint(500_000_000).uint(20);
            rational(w, 15, 1);
        });
    }

    private static void rational(Writer w, long numerator, long denominator) {
        w.tag(30).arr(2).uint(numerator).uint(denominator);
    }

    private static void credential(Writer w, int type, String hashHex) {
        w.arr(2).uint(type).bytes(hashHex);
    }

    private static byte[] cbor(Consumer<Writer> body) {
        Writer w = new Writer();
        body.accept(w);
        return w.out.toByteArray();
    }

    /** A minimal CBOR writer for hand-built states. */
    private static final class Writer {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer head(int major, long value) {
            if (value < 24) {
                out.write((major << 5) | (int) value);
            } else if (value < 0x100) {
                out.write((major << 5) | 24);
                out.write((int) value);
            } else if (value < 0x10000) {
                out.write((major << 5) | 25);
                out.write((int) (value >>> 8));
                out.write((int) value);
            } else if (value < 0x100000000L) {
                out.write((major << 5) | 26);
                for (int i = 3; i >= 0; i--) {
                    out.write((int) (value >>> (8 * i)));
                }
            } else {
                out.write((major << 5) | 27);
                for (int i = 7; i >= 0; i--) {
                    out.write((int) (value >>> (8 * i)));
                }
            }
            return this;
        }

        Writer uint(long v) {
            return head(0, v);
        }

        Writer bytes(String hex) {
            byte[] b = HexFormat.of().parseHex(hex);
            head(2, b.length);
            out.writeBytes(b);
            return this;
        }

        Writer text(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            head(3, b.length);
            out.writeBytes(b);
            return this;
        }

        Writer arr(long n) {
            return head(4, n);
        }

        Writer arrIndefinite() {
            out.write(0x9f);
            return this;
        }

        Writer brk() {
            out.write(0xff);
            return this;
        }

        Writer map(long n) {
            return head(5, n);
        }

        Writer tag(long t) {
            return head(6, t);
        }

        Writer nul() {
            out.write(0xf6);
            return this;
        }

        Writer bool(boolean b) {
            out.write(b ? 0xf5 : 0xf4);
            return this;
        }

        Writer raw(byte[] b) {
            out.writeBytes(b);
            return this;
        }
    }
}
