package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoStateKeys;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * M1 transition engine for signed, key-controlled, ADA-only, zero-fee
 * Conway transactions.
 */
class KeyPaymentTransitionEngine implements UtxoTransitionEngine {
    private final EutxoProfile profile;
    private final SigningProvider signingProvider;
    private final PlutusV3Evaluator plutusV3Evaluator;

    KeyPaymentTransitionEngine(EutxoProfile profile) {
        this(profile, CryptoConfiguration.INSTANCE.getSigningProvider(),
                profile.scriptsEnabled() ? new ScalusPlutusV3Evaluator() : null);
    }

    KeyPaymentTransitionEngine(EutxoProfile profile, SigningProvider signingProvider) {
        this(profile, signingProvider, null);
    }

    KeyPaymentTransitionEngine(
            EutxoProfile profile,
            SigningProvider signingProvider,
            PlutusV3Evaluator plutusV3Evaluator
    ) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.signingProvider = Objects.requireNonNull(signingProvider, "signingProvider");
        this.plutusV3Evaluator = plutusV3Evaluator;
        if (profile.scriptsEnabled() && plutusV3Evaluator == null) {
            throw new IllegalArgumentException("script-enabled profile requires a Plutus V3 evaluator");
        }
    }

    @Override
    public PreflightResult preflight(byte[] transactionCbor) {
        Parsed parsed = parse(transactionCbor);
        return parsed.failure != null
                ? PreflightResult.reject(parsed.transactionId, parsed.failure.code, parsed.failure.detail)
                : PreflightResult.accept(parsed.transactionId);
    }

    @Override
    public TransitionResult transition(byte[] transactionCbor, long l1Slot, AppStateReader state) {
        Objects.requireNonNull(state, "state");
        Parsed parsed = parse(transactionCbor);
        if (parsed.failure != null) {
            return reject(parsed.transactionId, parsed.failure);
        }
        Transaction transaction = parsed.transaction;
        String transactionId = parsed.transactionId;
        if (state.get(EutxoStateKeys.transaction(transactionId)).isPresent()) {
            return reject(transactionId, "DUPLICATE_TRANSACTION",
                    "transaction id was already accepted");
        }

        TransactionBody body = transaction.getBody();
        if (body.getValidityStartInterval() > 0 && l1Slot < body.getValidityStartInterval()) {
            return reject(transactionId, "TOO_EARLY", "validity interval has not started");
        }
        if (body.getTtl() > 0 && l1Slot > body.getTtl()) {
            return reject(transactionId, "EXPIRED", "validity interval has ended");
        }

        List<EutxoRecord> inputs = new ArrayList<>(body.getInputs().size());
        for (TransactionInput input : body.getInputs()) {
            EutxoOutpoint outpoint;
            try {
                outpoint = outpoint(input);
            } catch (IllegalArgumentException failure) {
                return reject(transactionId, "INVALID_INPUT", failure.getMessage());
            }
            byte[] encoded = state.get(EutxoStateKeys.utxo(outpoint)).orElse(null);
            if (encoded == null) {
                return reject(transactionId, "INPUT_NOT_FOUND", "input " + outpoint + " is not unspent");
            }
            try {
                inputs.add(EutxoRecord.decode(encoded));
            } catch (RuntimeException failure) {
                throw new IllegalStateException("committed EUTxO record cannot be decoded", failure);
            }
        }

        Failure scriptValidation = validateScripts(transactionCbor, transaction, inputs);
        if (scriptValidation != null) {
            return reject(transactionId, scriptValidation);
        }
        Failure authorization = authorize(transactionCbor, transaction, inputs);
        if (authorization != null) {
            return reject(transactionId, authorization);
        }
        Failure conservation = conserve(inputs, body.getOutputs());
        if (conservation != null) {
            return reject(transactionId, conservation);
        }

        List<EutxoOutpoint> consumed = inputs.stream().map(EutxoRecord::outpoint).toList();
        List<EutxoRecord> created = new ArrayList<>(body.getOutputs().size());
        for (int index = 0; index < body.getOutputs().size(); index++) {
            TransactionOutput output = body.getOutputs().get(index);
            try {
                byte[] outputCbor = CborSerializationUtil.serialize(output.serialize());
                created.add(new EutxoRecord(
                        new EutxoOutpoint(transactionId, index),
                        output.getAddress(),
                        outputCbor,
                        EutxoRecord.Origin.TRANSACTION));
            } catch (Exception failure) {
                throw new IllegalStateException("validated output cannot be encoded", failure);
            }
        }
        return TransitionResult.accept(transactionId, consumed, created);
    }

    private Parsed parse(byte[] transactionCbor) {
        if (transactionCbor == null || transactionCbor.length == 0) {
            return Parsed.failed("", "EMPTY_TRANSACTION", "transaction CBOR is empty");
        }
        if (transactionCbor.length > profile.maxTransactionBytes()) {
            return Parsed.failed("", "TRANSACTION_TOO_LARGE", "transaction exceeds profile size");
        }
        final Transaction transaction;
        final String transactionId;
        try {
            transaction = Transaction.deserialize(transactionCbor);
            transactionId = TransactionUtil.getTxHash(transactionCbor);
        } catch (Exception failure) {
            return Parsed.failed("", "INVALID_CBOR", "transaction CBOR cannot be decoded");
        }
        try {
            if (!Arrays.equals(transactionCbor, transaction.serialize())) {
                return Parsed.failed(transactionId, "NON_CANONICAL_CBOR",
                        "transaction CBOR is not canonical");
            }
        } catch (Exception failure) {
            return Parsed.failed(transactionId, "INVALID_CBOR",
                    "transaction cannot be canonically encoded");
        }

        Failure shape = validateShape(transaction);
        return shape == null
                ? new Parsed(transaction, transactionId, null)
                : new Parsed(transaction, transactionId, shape);
    }

    private Failure validateShape(Transaction transaction) {
        TransactionBody body = transaction.getBody();
        if (body == null) {
            return failure("INVALID_BODY", "transaction body is missing");
        }
        if (!transaction.isValid()) {
            return failure("COLLATERAL_TRANSITION_UNSUPPORTED", "isValid=false is not supported");
        }
        if (body.getInputs() == null || body.getInputs().isEmpty()
                || body.getInputs().size() > profile.maxInputs()) {
            return failure("INPUT_BOUND", "transaction input count is outside the profile bound");
        }
        if (body.getOutputs() == null || body.getOutputs().isEmpty()
                || body.getOutputs().size() > profile.maxOutputs()) {
            return failure("OUTPUT_BOUND", "transaction output count is outside the profile bound");
        }
        if (body.getFee() == null || body.getFee().signum() != 0) {
            return failure("NON_ZERO_FEE", "profile v1 requires a zero L2 fee");
        }
        if (nonEmpty(body.getMint())) {
            return failure("MINT_UNSUPPORTED", "minting and burning are not supported");
        }
        if (nonEmpty(body.getCerts()) || nonEmpty(body.getWithdrawals())
                || body.getUpdate() != null || body.getVotingProcedures() != null
                || nonEmpty(body.getProposalProcedures())
                || body.getCurrentTreasuryValue() != null || body.getDonation() != null) {
            return failure("LEDGER_OPERATION_UNSUPPORTED",
                    "stake, governance, withdrawal, and update operations are not supported");
        }
        if (nonEmpty(body.getCollateral()) || body.getCollateralReturn() != null
                || body.getTotalCollateral() != null) {
            return failure("COLLATERAL_UNSUPPORTED", "collateral fields are not supported");
        }
        if (nonEmpty(body.getReferenceInputs())) {
            return failure("REFERENCE_INPUT_UNSUPPORTED",
                    "reference inputs graduate with a later script profile");
        }
        if (!profile.scriptsEnabled() && hasScripts(transaction)) {
            return failure("SCRIPT_UNSUPPORTED", "profile v1 supports key-controlled spending only");
        }
        if (profile.scriptsEnabled()) {
            Failure scriptShape = validateScriptShape(transaction);
            if (scriptShape != null) {
                return scriptShape;
            }
        }
        for (TransactionOutput output : body.getOutputs()) {
            Failure outputFailure = validateOutput(output);
            if (outputFailure != null) {
                return outputFailure;
            }
        }
        return null;
    }

    private Failure validateOutput(TransactionOutput output) {
        if (output == null || output.getAddress() == null || output.getValue() == null) {
            return failure("INVALID_OUTPUT", "output address and value are required");
        }
        Value value = output.getValue();
        if (value.getCoin() == null || value.getCoin().signum() <= 0) {
            return failure("INVALID_OUTPUT_VALUE", "output lovelace must be positive");
        }
        if (nonEmpty(value.getMultiAssets())) {
            return failure("NATIVE_ASSET_UNSUPPORTED",
                    "native assets require an explicit admitted-asset profile");
        }
        try {
            Address address = new Address(output.getAddress());
            boolean keyAddress = AddressProvider.isPubKeyHashInPaymentPart(address);
            if (!keyAddress && !profile.scriptsEnabled()) {
                return failure("SCRIPT_OUTPUT_UNSUPPORTED",
                        "profile v1 outputs must use key payment credentials");
            }
            if (!keyAddress && output.getInlineDatum() == null) {
                return failure("INLINE_DATUM_REQUIRED",
                        "script outputs require an inline datum");
            }
            if (output.getDatumHash() != null) {
                return failure("DATUM_HASH_UNSUPPORTED",
                        "the Plutus V3 profile requires inline datums");
            }
            if (output.getScriptRef() != null) {
                return failure("REFERENCE_SCRIPT_UNSUPPORTED",
                        "reference scripts are not supported by this profile");
            }
            if (address.getNetwork().getNetworkId() != 0) {
                return failure("NETWORK_MISMATCH", "profile v1 uses Cardano testnet addresses");
            }
            byte[] encoded = CborSerializationUtil.serialize(output.serialize());
            if (encoded.length > profile.maxOutputCborBytes()) {
                return failure("OUTPUT_TOO_LARGE", "output exceeds the profile CBOR bound");
            }
        } catch (Exception failure) {
            return failure("INVALID_OUTPUT", "output cannot be encoded under the profile");
        }
        return null;
    }

    private Failure authorize(
            byte[] transactionCbor,
            Transaction transaction,
            List<EutxoRecord> inputs
    ) {
        byte[] bodyHash = Blake2bUtil.blake2bHash256(
                TransactionUtil.extractTransactionBodyFromTx(transactionCbor));
        Map<String, VkeyWitness> validWitnesses = new HashMap<>();
        List<VkeyWitness> witnesses = transaction.getWitnessSet() == null
                ? null : transaction.getWitnessSet().getVkeyWitnesses();
        if (witnesses != null) {
            for (VkeyWitness witness : witnesses) {
                if (witness == null || witness.getVkey() == null || witness.getVkey().length != 32
                        || witness.getSignature() == null || witness.getSignature().length != 64) {
                    continue;
                }
                if (signingProvider.verify(
                        witness.getSignature(), bodyHash, witness.getVkey())) {
                    validWitnesses.put(
                            KeyGenUtil.getKeyHash(witness.getVkey()), witness);
                }
            }
        }
        for (EutxoRecord input : inputs) {
            final Address address;
            try {
                address = new Address(input.address());
            } catch (RuntimeException failure) {
                throw new IllegalStateException("committed EUTxO address is invalid", failure);
            }
            if (!AddressProvider.isPubKeyHashInPaymentPart(address)) {
                if (profile.scriptsEnabled()) {
                    continue;
                }
                return failure("SCRIPT_INPUT_UNSUPPORTED",
                        "profile v1 inputs must use key payment credentials");
            }
            String credential = AddressProvider.getPaymentCredentialHash(address)
                    .map(HexFormat.of()::formatHex)
                    .orElse("");
            if (!validWitnesses.containsKey(credential)) {
                return failure("MISSING_INPUT_WITNESS",
                        "a valid witness for every input payment credential is required");
            }
        }
        if (transaction.getBody().getRequiredSigners() != null) {
            for (byte[] requiredSigner : transaction.getBody().getRequiredSigners()) {
                if (requiredSigner == null || requiredSigner.length != 28
                        || !validWitnesses.containsKey(HexFormat.of().formatHex(requiredSigner))) {
                    return failure("MISSING_REQUIRED_SIGNER", "a required signer witness is missing");
                }
            }
        }
        return null;
    }

    private Failure validateScripts(
            byte[] transactionCbor,
            Transaction transaction,
            List<EutxoRecord> inputs
    ) {
        long scriptInputs = inputs.stream()
                .filter(input -> {
                    try {
                        return !AddressProvider.isPubKeyHashInPaymentPart(
                                new Address(input.address()));
                    } catch (RuntimeException failure) {
                        throw new IllegalStateException(
                                "committed EUTxO address is invalid", failure);
                    }
                })
                .count();
        if (scriptInputs == 0 && !hasScripts(transaction)) {
            return null;
        }
        if (!profile.scriptsEnabled()) {
            return failure("SCRIPT_UNSUPPORTED", "the selected profile disables scripts");
        }
        if (scriptInputs > 0 && !hasScripts(transaction)) {
            return failure("SCRIPT_WITNESS_MISSING",
                    "every script input requires the admitted Plutus V3 witness");
        }
        int redeemers = transaction.getWitnessSet() == null
                ? 0 : size(transaction.getWitnessSet().getRedeemers());
        if (scriptInputs != redeemers) {
            return failure("SCRIPT_REDEEMER_COUNT",
                    "the spending redeemer count must equal the script input count");
        }
        PlutusV3Evaluator.Evaluation evaluation =
                plutusV3Evaluator.evaluate(transactionCbor, inputs);
        return evaluation.valid()
                ? null : failure(evaluation.code(), evaluation.detail());
    }

    private Failure validateScriptShape(Transaction transaction) {
        var witnesses = transaction.getWitnessSet();
        if (witnesses == null) {
            return null;
        }
        if (nonEmpty(witnesses.getNativeScripts())
                || nonEmpty(witnesses.getPlutusV1Scripts())
                || nonEmpty(witnesses.getPlutusV2Scripts())) {
            return failure("SCRIPT_FAMILY_UNSUPPORTED",
                    "only witnessed Plutus V3 spending is supported");
        }
        int scripts = size(witnesses.getPlutusV3Scripts());
        int datums = size(witnesses.getPlutusDataList());
        int redeemers = size(witnesses.getRedeemers());
        if (scripts > profile.maxScripts()) {
            return failure("SCRIPT_BOUND", "Plutus V3 script count exceeds the profile bound");
        }
        if (datums > profile.maxDatums()) {
            return failure("DATUM_BOUND", "datum count exceeds the profile bound");
        }
        if (redeemers > profile.maxRedeemers()) {
            return failure("REDEEMER_BOUND", "redeemer count exceeds the profile bound");
        }
        if ((scripts == 0) != (redeemers == 0)) {
            return failure("SCRIPT_WITNESS_MISMATCH",
                    "Plutus V3 scripts and spending redeemers must be supplied together");
        }
        if (witnesses.getRedeemers() != null) {
            for (var redeemer : witnesses.getRedeemers()) {
                if (redeemer == null
                        || redeemer.getTag()
                        != com.bloxbean.cardano.client.plutus.spec.RedeemerTag.Spend) {
                    return failure("REDEEMER_PURPOSE_UNSUPPORTED",
                            "only spending redeemers are supported");
                }
                if (redeemer.getExUnits() == null
                        || redeemer.getExUnits().getMem() == null
                        || redeemer.getExUnits().getSteps() == null
                        || redeemer.getExUnits().getMem().signum() < 0
                        || redeemer.getExUnits().getSteps().signum() < 0
                        || redeemer.getExUnits().getMem().compareTo(
                        BigInteger.valueOf(profile.maxExecutionMemory())) > 0
                        || redeemer.getExUnits().getSteps().compareTo(
                        BigInteger.valueOf(profile.maxExecutionSteps())) > 0) {
                    return failure("EXECUTION_BUDGET_BOUND",
                            "redeemer execution units are outside the profile bound");
                }
            }
        }
        return null;
    }

    private Failure conserve(List<EutxoRecord> inputs, List<TransactionOutput> outputs) {
        BigInteger consumed = BigInteger.ZERO;
        for (EutxoRecord input : inputs) {
            try {
                TransactionOutput output = TransactionOutput.deserialize(
                        CborSerializationUtil.deserialize(input.outputCbor()));
                consumed = consumed.add(output.getValue().getCoin());
            } catch (Exception failure) {
                throw new IllegalStateException("committed EUTxO output cannot be decoded", failure);
            }
        }
        BigInteger created = outputs.stream()
                .map(TransactionOutput::getValue)
                .map(Value::getCoin)
                .reduce(BigInteger.ZERO, BigInteger::add);
        return consumed.equals(created)
                ? null
                : failure("VALUE_NOT_CONSERVED", "consumed and created lovelace differ");
    }

    private static EutxoOutpoint outpoint(TransactionInput input) {
        if (input == null) {
            throw new IllegalArgumentException("transaction input is null");
        }
        return new EutxoOutpoint(input.getTransactionId(), input.getIndex());
    }

    private static boolean hasScripts(Transaction transaction) {
        var witnesses = transaction.getWitnessSet();
        return witnesses != null
                && (nonEmpty(witnesses.getNativeScripts())
                || nonEmpty(witnesses.getPlutusV1Scripts())
                || nonEmpty(witnesses.getPlutusV2Scripts())
                || nonEmpty(witnesses.getPlutusV3Scripts())
                || nonEmpty(witnesses.getPlutusDataList())
                || nonEmpty(witnesses.getRedeemers()));
    }

    private static boolean nonEmpty(List<?> values) {
        return values != null && !values.isEmpty();
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static TransitionResult reject(String transactionId, Failure failure) {
        return reject(transactionId, failure.code, failure.detail);
    }

    private static TransitionResult reject(String transactionId, String code, String detail) {
        return TransitionResult.reject(transactionId, code, detail);
    }

    private static Failure failure(String code, String detail) {
        return new Failure(code, detail);
    }

    private record Failure(String code, String detail) {
    }

    private record Parsed(Transaction transaction, String transactionId, Failure failure) {
        static Parsed failed(String transactionId, String code, String detail) {
            return new Parsed(null, transactionId,
                    KeyPaymentTransitionEngine.failure(code, detail));
        }
    }
}
