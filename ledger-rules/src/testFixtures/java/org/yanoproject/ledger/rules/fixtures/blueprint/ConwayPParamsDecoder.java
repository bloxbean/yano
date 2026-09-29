package org.yanoproject.ledger.rules.fixtures.blueprint;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Decodes Haskell's Conway {@code PParams} record encoding into CCL {@link ProtocolParams} as the Java rules read them.
 *
 * <p>The encoding is a 31-element array in {@code eraPParams} order (cardano-ledger
 * {@code Conway/PParams.hs}, {@code instance EraPParams ConwayEra}): minFeeA, minFeeB, maxBBSize, maxTxSize,
 * maxBHSize, keyDeposit, poolDeposit, eMax, nOpt, a0, rho, tau, protocolVersion {@code [major, minor]}, minPoolCost,
 * coinsPerUTxOByte, costModels, prices {@code [mem, steps]}, maxTxExUnits, maxBlockExUnits, maxValSize,
 * collateralPercentage, maxCollateralInputs, pool voting thresholds (5), DRep voting thresholds (10),
 * committeeMinSize, committeeMaxTermLength, govActionLifetime, govActionDeposit, drepDeposit, drepActivity,
 * minFeeRefScriptCostPerByte. Rationals are {@code #6.30([n, d])}.</p>
 *
 * <p>Cost models go to {@link ProtocolParams#getCostModelsRaw()} under {@code PlutusV1}/{@code PlutusV2}/
 * {@code PlutusV3} as the ledger stores them (the parameter list in its canonical order, no entry for a language
 * without a cost model), which is what {@code LedgerView#protocolParams()} requires. A language id outside 0–2 fails
 * the decode.</p>
 *
 * <p>Rationals become {@link BigDecimal}s. The ones the transaction rules read (the execution-unit prices and
 * {@code minFeeRefScriptCostPerByte}) must be exact, or the decode fails. The others (a0, rho, tau and the voting
 * thresholds, read only by the epoch boundary) are exact when the fraction has a finite decimal form and otherwise
 * rounded to 34 significant digits, which is recorded in the notes (an Imp test sets a threshold to 2/3).</p>
 */
public final class ConwayPParamsDecoder {

    /** Number of fields in the Conway {@code PParams} record. */
    public static final int FIELDS = 31;

    private static final String[] LANGUAGES = {"PlutusV1", "PlutusV2", "PlutusV3"};

    private ConwayPParamsDecoder() {
    }

    /**
     * @param cbor a Conway {@code PParams} encoding
     * @return the parameters
     * @throws IllegalArgumentException when the bytes are not a Conway {@code PParams} record
     */
    public static ProtocolParams decode(byte[] cbor) {
        return decode(cbor, new ArrayList<>());
    }

    /**
     * @param notes receives a note per rational that had to be rounded
     */
    public static ProtocolParams decode(byte[] cbor, List<String> notes) {
        CborReader r = new CborReader(cbor);
        ProtocolParams params = decode(r, notes);
        if (!r.atEnd()) {
            throw new IllegalArgumentException("trailing bytes after the protocol parameters at offset " + r.position());
        }
        return params;
    }

    /** Reads one {@code PParams} record at the reader's position; {@code notes} receives the rounded rationals. */
    public static ProtocolParams decode(CborReader r, List<String> notes) {
        Rationals q = new Rationals(r, notes);
        r.readArray(FIELDS, "Conway PParams");
        ProtocolParams.ProtocolParamsBuilder b = ProtocolParams.builder();
        b.minFeeA(r.readInt());                                    // 0
        b.minFeeB(r.readInt());                                    // 1
        b.maxBlockSize(r.readInt());                               // 2
        b.maxTxSize(r.readInt());                                  // 3
        b.maxBlockHeaderSize(r.readInt());                         // 4
        b.keyDeposit(r.readBigInteger().toString());               // 5
        b.poolDeposit(r.readBigInteger().toString());              // 6
        b.eMax(r.readInt());                                       // 7
        b.nOpt(r.readInt());                                       // 8
        b.a0(q.boundary("a0"));                             // 9
        b.rho(q.boundary("rho"));                            // 10
        b.tau(q.boundary("tau"));                            // 11
        r.readArray(2, "protocol version");                        // 12
        b.protocolMajorVer(r.readInt());
        b.protocolMinorVer(r.readInt());
        b.minPoolCost(r.readBigInteger().toString());              // 13
        b.coinsPerUtxoSize(r.readBigInteger().toString());         // 14
        b.costModelsRaw(costModels(r));                            // 15
        r.readArray(2, "prices");                                  // 16
        b.priceMem(r.readRationalDecimal());
        b.priceStep(r.readRationalDecimal());
        r.readArray(2, "max tx execution units");                  // 17
        b.maxTxExMem(r.readBigInteger().toString());
        b.maxTxExSteps(r.readBigInteger().toString());
        r.readArray(2, "max block execution units");               // 18
        b.maxBlockExMem(r.readBigInteger().toString());
        b.maxBlockExSteps(r.readBigInteger().toString());
        b.maxValSize(r.readBigInteger().toString());               // 19
        b.collateralPercent(new BigDecimal(r.readBigInteger()));   // 20
        b.maxCollateralInputs(r.readInt());                        // 21
        r.readArray(5, "pool voting thresholds");                  // 22
        b.pvtMotionNoConfidence(q.boundary("pvtMotionNoConfidence"));
        b.pvtCommitteeNormal(q.boundary("pvtCommitteeNormal"));
        b.pvtCommitteeNoConfidence(q.boundary("pvtCommitteeNoConfidence"));
        b.pvtHardForkInitiation(q.boundary("pvtHardForkInitiation"));
        b.pvtPPSecurityGroup(q.boundary("pvtPPSecurityGroup"));
        r.readArray(10, "DRep voting thresholds");                 // 23
        b.dvtMotionNoConfidence(q.boundary("dvtMotionNoConfidence"));
        b.dvtCommitteeNormal(q.boundary("dvtCommitteeNormal"));
        b.dvtCommitteeNoConfidence(q.boundary("dvtCommitteeNoConfidence"));
        b.dvtUpdateToConstitution(q.boundary("dvtUpdateToConstitution"));
        b.dvtHardForkInitiation(q.boundary("dvtHardForkInitiation"));
        b.dvtPPNetworkGroup(q.boundary("dvtPPNetworkGroup"));
        b.dvtPPEconomicGroup(q.boundary("dvtPPEconomicGroup"));
        b.dvtPPTechnicalGroup(q.boundary("dvtPPTechnicalGroup"));
        b.dvtPPGovGroup(q.boundary("dvtPPGovGroup"));
        b.dvtTreasuryWithdrawal(q.boundary("dvtTreasuryWithdrawal"));
        b.committeeMinSize(r.readInt());                           // 24
        b.committeeMaxTermLength(r.readInt());                     // 25
        b.govActionLifetime(r.readInt());                          // 26
        b.govActionDeposit(r.readBigInteger());                    // 27
        b.drepDeposit(r.readBigInteger());                         // 28
        b.drepActivity(r.readInt());                               // 29
        b.minFeeRefScriptCostPerByte(r.readRationalDecimal());     // 30
        return b.build();
    }

    /** Reads the rationals the epoch boundary alone uses, rounding a fraction without a finite decimal form. */
    private record Rationals(CborReader r, List<String> notes) {
        BigDecimal boundary(String field) {
            BigInteger[] q = r.readRational();
            try {
                return new BigDecimal(q[0]).divide(new BigDecimal(q[1]));
            } catch (ArithmeticException e) {
                notes.add(field + " = " + q[0] + "/" + q[1] + " rounded (read only by the epoch boundary)");
                return new BigDecimal(q[0]).divide(new BigDecimal(q[1]), MathContext.DECIMAL128);
            }
        }
    }

    /** {@code CostModels}: a map from the language id (0–2) to the parameter list, in the ledger's order. */
    private static LinkedHashMap<String, List<Long>> costModels(CborReader r) {
        LinkedHashMap<Integer, List<Long>> byLanguage = new LinkedHashMap<>();
        long size = r.readMap();
        for (long i = 0; r.hasNext(size, i); i++) {
            int language = r.readInt();
            if (language < 0 || language >= LANGUAGES.length) {
                throw new IllegalArgumentException("cost model for unknown language " + language);
            }
            List<Long> values = new ArrayList<>();
            long count = r.readArray();
            for (long j = 0; r.hasNext(count, j); j++) {
                values.add(r.readLong());
            }
            if (byLanguage.put(language, values) != null) {
                throw new IllegalArgumentException("duplicate cost model for language " + language);
            }
        }
        LinkedHashMap<String, List<Long>> raw = new LinkedHashMap<>();
        for (int language = 0; language < LANGUAGES.length; language++) {
            List<Long> values = byLanguage.get(language);
            if (values != null) {
                raw.put(LANGUAGES[language], List.copyOf(values));
            }
        }
        return raw;
    }
}
