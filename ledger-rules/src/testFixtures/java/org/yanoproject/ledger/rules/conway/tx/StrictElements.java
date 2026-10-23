package org.yanoproject.ledger.rules.conway.tx;

/**
 * The strict transaction decoder ({@link RawTransaction} and its readers) applied to one standalone CBOR item, for
 * conformance corpora that test decoding element by element (ADR-060 §6, cardano-cbor-dataset). Each method reads
 * exactly one item, rejects trailing bytes, and throws a {@link RuntimeException} (normally
 * {@link TxDecodingException}) where Haskell would not decode the item.
 *
 * <p>The readers are package-private; this fixture is the only way in, and it adds no decoding of its own.</p>
 */
public final class StrictElements {

    private StrictElements() {
    }

    /**
     * {@code transaction}. The node also decodes it with CCL ({@code Transaction.deserialize}) and passes that model
     * along; the strict reader keeps it for structure only and decides nothing on it, so it is left out here.
     */
    public static RawTransaction transaction(byte[] cbor) {
        return RawTransaction.parse(cbor, null);
    }

    /** {@code certificate} */
    public static void certificate(byte[] cbor) {
        RawCertificate.read(StrictCbor.span(cbor), 0);
    }

    /** {@code credential} */
    public static void credential(byte[] cbor) {
        RawCredential.read(StrictCbor.span(cbor));
    }

    /** {@code drep} */
    public static void drep(byte[] cbor) {
        RawCertificate.DRep.read(StrictCbor.span(cbor));
    }

    /** {@code native_script} */
    public static void nativeScript(byte[] cbor) {
        RawScript.of(RawScript.NATIVE, StrictCbor.span(cbor));
    }

    /** {@code script}, {@code [language, script]} (no tag-24 wrapper) */
    public static void script(byte[] cbor) {
        RawScript.readScript(StrictCbor.span(cbor));
    }

    /** {@code plutus_data} */
    public static void plutusData(byte[] cbor) {
        PlutusData.validate(cbor);
    }

    /** {@code proposal_procedure} */
    public static void proposal(byte[] cbor) {
        RawProposal.read(StrictCbor.span(cbor), 0);
    }

    /** {@code protocol_param_update} */
    public static void paramUpdate(byte[] cbor) {
        RawParamUpdate.read(StrictCbor.span(cbor));
    }

    /** {@code relay} */
    public static void relay(byte[] cbor) {
        BoundedFields.relay(StrictCbor.span(cbor));
    }

    /** {@code transaction_output} */
    public static RawOutput output(byte[] cbor) {
        return RawOutput.read(StrictCbor.span(cbor), 0, false);
    }

    /** {@code value} */
    public static void value(byte[] cbor) {
        RawOutput.readValue(StrictCbor.span(cbor));
    }
}
