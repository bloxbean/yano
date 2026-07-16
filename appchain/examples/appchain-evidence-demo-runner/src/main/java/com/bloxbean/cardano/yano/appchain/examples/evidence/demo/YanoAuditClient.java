package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.anchor.AnchorDatumV1;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundleCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceVerifier;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.EffectProofVerifier;
import com.bloxbean.cardano.yano.appchain.examples.evidence.client.EvidenceClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Bounded Yano transport plus independent state/effect/finality audit helpers. */
final class YanoAuditClient {
    private static final int MAX_STATUS_BYTES = 512 * 1024;
    static final int MAX_EVIDENCE_BYTES = EvidenceBundleCodec.MAX_JSON_BYTES;
    private static final int MAX_TX_OUTPUTS = 128;
    private static final int MAX_TX_INPUTS = 256;
    private static final int MAX_ASSETS_PER_OUTPUT = 256;
    private static final Set<String> TX_UTXO_FIELDS = Set.of("hash", "inputs", "outputs");
    private static final Set<String> OUTPUT_FIELDS = Set.of(
            "tx_hash", "output_index", "address", "amount", "data_hash",
            "inline_datum", "reference_script_hash");
    private static final Set<String> AMOUNT_FIELDS = Set.of("unit", "quantity");

    private final URI baseUrl;
    private final String chainId;
    private final SecretValue apiKey;
    private final Set<String> expectedMemberKeys;
    private final int expectedThreshold;
    private final EvidenceVerifier.TrustContext trustContext;
    private final BoundedHttp http;
    private final AppChainClient appChain;
    private final EvidenceClient evidence;

    YanoAuditClient(URI baseUrl,
                    String chainId,
                    Set<String> expectedMemberKeys,
                    int expectedThreshold,
                    SecretValue apiKey) {
        this.baseUrl = baseUrl;
        this.chainId = chainId;
        this.apiKey = apiKey;
        try {
            this.trustContext = new EvidenceVerifier.TrustContext(
                    chainId, expectedMemberKeys, expectedThreshold);
        } catch (RuntimeException invalid) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        this.expectedMemberKeys = this.trustContext.memberKeysHex();
        this.expectedThreshold = this.trustContext.threshold();
        this.http = new BoundedHttp(Duration.ofSeconds(5), Duration.ofSeconds(30));
        AppChainClient.Builder builder = AppChainClient.builder(baseUrl.toString())
                .chainId(chainId)
                .connectTimeoutSeconds(5)
                .directConnections();
        if (apiKey != null) {
            builder.apiKey(apiKey.reveal());
        }
        this.appChain = builder.build();
        this.evidence = new EvidenceClient(appChain, chainId);
    }

    AppChainClient appChain() {
        return appChain;
    }

    EvidenceClient evidence() {
        return evidence;
    }

    long l1BlockNumber() {
        BoundedHttp.Response response = http.get(uri("/status"), headers(), MAX_STATUS_BYTES);
        if (response.status() != 200) {
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        }
        JsonNode block = StrictJson.parse(response.body()).path("chain").path("blockNumber");
        if (!block.isIntegralNumber() || !block.canConvertToLong() || block.longValue() < 0) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        return block.longValue();
    }

    Status status() {
        BoundedHttp.Response response = http.get(chainUri("/status"), headers(), MAX_STATUS_BYTES);
        if (response.status() != 200) {
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        }
        JsonNode root = StrictJson.parse(response.body());
        JsonNode chain = root.get("chainId");
        JsonNode running = root.get("running");
        JsonNode heightNode = root.get("tipHeight");
        JsonNode stateRootNode = root.get("stateRoot");
        JsonNode memberKeyNode = root.get("memberKey");
        JsonNode membersNode = root.get("members");
        JsonNode thresholdNode = root.get("threshold");
        JsonNode stateMachineNode = root.get("stateMachine");
        if (!root.isObject() || chain == null || !chain.isTextual()
                || !chainId.equals(chain.textValue())
                || running == null || !running.isBoolean() || !running.booleanValue()
                || heightNode == null || !heightNode.isIntegralNumber()
                || !heightNode.canConvertToLong() || heightNode.longValue() < 0
                || stateRootNode == null || !stateRootNode.isTextual()
                || !stateRootNode.textValue().matches("[0-9a-f]{64}")
                || memberKeyNode == null || !memberKeyNode.isTextual()
                || !memberKeyNode.textValue().matches("[0-9a-f]{64}")
                || membersNode == null || !membersNode.isIntegralNumber()
                || !membersNode.canConvertToInt() || membersNode.intValue() < 1
                || thresholdNode == null || !thresholdNode.isIntegralNumber()
                || !thresholdNode.canConvertToInt() || thresholdNode.intValue() < 1
                || thresholdNode.intValue() > membersNode.intValue()
                || stateMachineNode == null || !stateMachineNode.isTextual()
                || stateMachineNode.textValue().isBlank()
                || stateMachineNode.textValue().length() > 128) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        long height = heightNode.longValue();
        String stateRoot = stateRootNode.textValue();
        JsonNode anchor = root.path("anchor");
        long anchoredHeight = 0;
        String anchorTx = null;
        long anchorSlot = 0;
        String anchorScriptAddress = null;
        String anchorThreadPolicyId = null;
        if (!anchor.isMissingNode() && !anchor.isNull()) {
            JsonNode anchorHeightNode = anchor.get("lastAnchoredHeight");
            JsonNode enabledNode = anchor.get("enabled");
            JsonNode modeNode = anchor.get("mode");
            JsonNode bootstrappedNode = anchor.get("bootstrapped");
            if (!anchor.isObject() || anchorHeightNode == null
                    || !anchorHeightNode.isIntegralNumber()
                    || !anchorHeightNode.canConvertToLong()
                    || anchorHeightNode.longValue() < 0
                    || enabledNode == null || !enabledNode.isBoolean()
                    || !enabledNode.booleanValue()
                    || modeNode == null || !modeNode.isTextual()
                    || !"script".equals(modeNode.textValue())
                    || bootstrappedNode == null || !bootstrappedNode.isBoolean()
                    ) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
            anchoredHeight = anchorHeightNode.longValue();
            if (bootstrappedNode.booleanValue()) {
                JsonNode addressNode = anchor.get("address");
                JsonNode scriptAddressNode = anchor.get("scriptAddress");
                JsonNode policyNode = anchor.get("threadPolicyId");
                if (addressNode == null || !boundedText(addressNode, 8, 256)
                        || scriptAddressNode == null
                        || !boundedText(scriptAddressNode, 8, 256)
                        || !addressNode.textValue().equals(scriptAddressNode.textValue())
                        || policyNode == null || !policyNode.isTextual()
                        || !policyNode.textValue().matches("[0-9a-f]{56}")) {
                    throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
                }
                anchorScriptAddress = scriptAddressNode.textValue();
                anchorThreadPolicyId = policyNode.textValue();
            }
            JsonNode txNode = anchor.get("lastAnchorTx");
            if (txNode != null && !txNode.isNull()) {
                if (!txNode.isTextual() || !txNode.textValue().matches("[0-9a-f]{64}")) {
                    throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
                }
                anchorTx = txNode.textValue();
                JsonNode slotNode = anchor.get("lastAnchorL1Slot");
                if (slotNode == null || !slotNode.isIntegralNumber()
                        || !slotNode.canConvertToLong() || slotNode.longValue() < 0) {
                    throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
                }
                anchorSlot = slotNode.longValue();
            } else if (anchoredHeight > 0) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
        }
        return new Status(height, stateRoot, memberKeyNode.textValue(),
                membersNode.intValue(), thresholdNode.intValue(),
                stateMachineNode.textValue(), anchoredHeight, anchorTx, anchorSlot,
                anchorScriptAddress, anchorThreadPolicyId);
    }

    boolean anchorTransactionVisible(String transactionHash) {
        return anchorTransaction(transactionHash) != null;
    }

    AnchorTransaction anchorTransaction(String transactionHash) {
        if (transactionHash == null || !transactionHash.matches("[0-9a-f]{64}")) {
            throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
        }
        BoundedHttp.Response response = http.get(uri("/txs/" + transactionHash),
                headers(), MAX_STATUS_BYTES);
        if (response.status() == 404) {
            return null;
        }
        if (response.status() != 200) {
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        }
        JsonNode tx = StrictJson.parse(response.body());
        JsonNode hash = tx.get("hash");
        JsonNode blockHeight = tx.get("block_height");
        JsonNode slot = tx.get("slot");
        JsonNode validContract = tx.get("valid_contract");
        if (!tx.isObject() || hash == null || !hash.isTextual()
                || !transactionHash.equals(hash.textValue())
                || blockHeight == null || !blockHeight.isIntegralNumber()
                || !blockHeight.canConvertToLong() || blockHeight.longValue() < 0
                || slot == null || !slot.isIntegralNumber()
                || !slot.canConvertToLong() || slot.longValue() < 0
                || validContract == null || !validContract.isBoolean()
                || !validContract.booleanValue()) {
            throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
        }
        return new AnchorTransaction(slot.longValue());
    }

    AnchorCommitment verifyAnchorCommitment(String transactionHash,
                                            AnchorExpectation expected) {
        if (expected == null || transactionHash == null
                || !chainId.equals(expected.chainId())
                || !transactionHash.matches("[0-9a-f]{64}")) {
            throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
        }
        AnchorTransaction transaction = anchorTransaction(transactionHash);
        if (transaction == null || transaction.slot() != expected.l1Slot()) {
            throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
        }
        BoundedHttp.Response response = http.get(uri("/txs/" + transactionHash + "/utxos"),
                headers(), MAX_STATUS_BYTES);
        if (response.status() == 404) {
            throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
        }
        if (response.status() != 200) {
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        }
        try {
            JsonNode root = StrictJson.parse(response.body());
            if (!root.isObject() || !exactFields(root, TX_UTXO_FIELDS)
                    || !transactionHash.equals(text(root.get("hash"), 64))
                    || !boundedArray(root.get("inputs"), MAX_TX_INPUTS, true)
                    || !boundedArray(root.get("outputs"), MAX_TX_OUTPUTS, false)) {
                throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
            }
            String expectedUnit = AnchorDatumV1.threadTokenUnit(
                    expected.threadPolicyId(), chainId);
            AnchorCommitment commitment = null;
            int expectedThreadOutputs = 0;
            for (JsonNode output : root.get("outputs")) {
                ParsedOutput parsed = parseOutput(output, transactionHash);
                String threadQuantity = parsed.amounts().get(expectedUnit);
                if (threadQuantity != null) {
                    expectedThreadOutputs++;
                    if (!"1".equals(threadQuantity)
                            || !expected.scriptAddress().equals(parsed.address())) {
                        throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
                    }
                }
                if (!expected.scriptAddress().equals(parsed.address())) {
                    continue;
                }
                if (commitment != null || !"1".equals(threadQuantity)
                        || parsed.inlineDatum() == null) {
                    throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
                }
                byte[] inlineDatum = HexFormat.of().parseHex(parsed.inlineDatum());
                AnchorDatumV1 datum = AnchorDatumV1.decode(inlineDatum);
                if (!expected.matches(datum)) {
                    throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
                }
                commitment = new AnchorCommitment(transactionHash, transaction.slot(),
                        parsed.outputIndex(), parsed.address(), expectedUnit,
                        parsed.inlineDatum());
            }
            if (commitment == null || expectedThreadOutputs != 1) {
                throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
            }
            return commitment;
        } catch (DemoException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
        }
    }

    FinalityAudit verifyMessageEvidence(String messageId, boolean requireAnchor) {
        if (messageId == null || !messageId.matches("[0-9a-f]{64}")) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
        BoundedHttp.Response response = http.get(chainUri("/evidence/" + messageId),
                headers(), MAX_EVIDENCE_BYTES);
        if (response.status() != 200) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
        try {
            EvidenceBundle bundle = EvidenceBundleCodec.fromJson(response.body());
            LinkedHashSet<String> memberKeys = new LinkedHashSet<>(bundle.memberKeysHex());
            int threshold = bundle.threshold();
            if (memberKeys.isEmpty() || threshold > memberKeys.size()
                    || threshold != expectedThreshold
                    || !memberKeys.equals(expectedMemberKeys)) {
                throw new DemoException(DemoError.STATE_PROOF_FAILED);
            }
            if (!chainId.equals(bundle.chainId()) || !messageId.equals(bundle.messageIdHex())) {
                throw new DemoException(DemoError.STATE_PROOF_FAILED);
            }
            if (bundle.threshold() != threshold
                    || !bundle.memberKeysHex().equals(memberKeys.stream().toList())) {
                throw new DemoException(DemoError.STATE_PROOF_FAILED);
            }
            if (bundle.blocks().isEmpty() || bundle.blocks().size() > 4_096) {
                throw new DemoException(DemoError.STATE_PROOF_FAILED);
            }
            validateExpectedBlockChain(bundle, chainId);
            EvidenceVerifier.Result result = EvidenceVerifier.verify(bundle, trustContext);
            if (!result.valid() || !result.messageContentVerified()
                    || requireAnchor && !result.anchoredToL1()) {
                throw new DemoException(requireAnchor
                        ? DemoError.ANCHOR_UNAVAILABLE : DemoError.STATE_PROOF_FAILED);
            }
            if (bundle.anchor() != null) {
                var anchor = bundle.anchor();
                if (anchor.anchoredHeight() != bundle.blocks().getLast().height()
                        || anchor.l1Slot() < 0
                        || anchor.txHash() == null
                        || !anchor.txHash().matches("[0-9a-f]{64}")
                        || anchor.anchoredBlockHashHex() == null
                        || !anchor.anchoredBlockHashHex().matches("[0-9a-f]{64}")) {
                    throw new DemoException(DemoError.STATE_PROOF_FAILED);
                }
            }
            long anchoredHeight = bundle.anchor() == null ? 0 : bundle.anchor().anchoredHeight();
            long anchorSlot = bundle.anchor() == null ? 0 : bundle.anchor().l1Slot();
            String anchorBlockHash = bundle.anchor() == null ? null
                    : bundle.anchor().anchoredBlockHashHex();
            String anchorStateRoot = bundle.anchor() == null ? null
                    : Digests.hex(bundle.blocks().getLast().stateRoot());
            Map<Long, String> certifiedStateRoots = bundle.blocks().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            com.bloxbean.cardano.yano.api.appchain.AppBlock::height,
                            block -> Digests.hex(block.stateRoot())));
            return new FinalityAudit(result.certSignatures(), threshold,
                    Set.copyOf(memberKeys), result.anchoredToL1(), anchoredHeight,
                    result.anchorTxHash(), anchorSlot, anchorBlockHash, anchorStateRoot,
                    bundle.blocks().getFirst().height(),
                    Digests.hex(bundle.blocks().getFirst().stateRoot()),
                    certifiedStateRoots);
        } catch (DemoException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
    }

    EffectRecord verifyEffect(long height, int ordinal, String expectedStateRoot,
                              String expectedType,
                              byte[] expectedPayload, byte[] expectedSourceMessageId) {
        AppChainClient.EffectProofLookup lookup;
        try {
            lookup = appChain.effectProof(height, ordinal);
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.EFFECT_PROOF_FAILED);
        }
        if (!lookup.available()
                || !EffectProofVerifier.verifyFor(lookup.proof(), expectedStateRoot,
                chainId, height, ordinal)) {
            throw new DemoException(DemoError.EFFECT_PROOF_FAILED);
        }
        try {
            byte[] encoded = HexFormat.of().parseHex(lookup.proof().recordCborHex());
            EffectRecord record = EffectRecord.decode(encoded);
            if (!Arrays.equals(record.encode(), encoded)
                    || !expectedType.equals(record.type())
                    || !Arrays.equals(expectedPayload, record.payload())
                    || !Arrays.equals(expectedSourceMessageId, record.sourceMessageId())) {
                throw new DemoException(DemoError.EFFECT_PROOF_FAILED);
            }
            return record;
        } catch (DemoException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.EFFECT_PROOF_FAILED);
        }
    }

    static void validateExpectedBlockChain(EvidenceBundle bundle, String expectedChainId) {
        if (bundle == null || expectedChainId == null || bundle.blocks().isEmpty()) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
        long previousHeight = 0;
        for (int index = 0; index < bundle.blocks().size(); index++) {
            var block = bundle.blocks().get(index);
            if (block == null || block.version() != com.bloxbean.cardano.yano.api.appchain.AppBlock.BLOCK_VERSION
                    || !expectedChainId.equals(block.chainId()) || block.height() < 1
                    || index > 0 && (previousHeight == Long.MAX_VALUE
                    || block.height() != previousHeight + 1)
                    || block.prevHash() == null || block.prevHash().length != 32
                    || block.messagesRoot() == null || block.messagesRoot().length != 32
                    || block.stateRoot() == null || block.stateRoot().length != 32
                    || block.proposer() == null || block.proposer().length != 32) {
                throw new DemoException(DemoError.STATE_PROOF_FAILED);
            }
            previousHeight = block.height();
        }
        long firstHeight = bundle.blocks().getFirst().height();
        long lastHeight = bundle.blocks().getLast().height();
        if (bundle.anchor() == null && bundle.blocks().size() != 1
                || bundle.anchor() != null
                && (bundle.anchor().anchoredHeight() != lastHeight
                || bundle.anchor().anchoredHeight() < firstHeight)) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
    }

    private URI uri(String suffix) {
        return URI.create(baseUrl + suffix);
    }

    private URI chainUri(String suffix) {
        return URI.create(baseUrl + "/app-chain/chains/" + chainId + suffix);
    }

    private Map<String, String> headers() {
        return apiKey == null
                ? Map.of("Accept", "application/json")
                : Map.of("Accept", "application/json", "X-API-Key", apiKey.reveal());
    }

    private static ParsedOutput parseOutput(JsonNode output, String transactionHash) {
        if (output == null || !output.isObject() || !exactFields(output, OUTPUT_FIELDS)
                || !transactionHash.equals(text(output.get("tx_hash"), 64))
                || output.get("output_index") == null
                || !output.get("output_index").isIntegralNumber()
                || !output.get("output_index").canConvertToInt()
                || output.get("output_index").intValue() < 0
                || !boundedText(output.get("address"), 8, 256)
                || !boundedArray(output.get("amount"), MAX_ASSETS_PER_OUTPUT, false)) {
            throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
        }
        Map<String, String> amounts = new java.util.LinkedHashMap<>();
        for (JsonNode amount : output.get("amount")) {
            if (amount == null || !amount.isObject() || !exactFields(amount, AMOUNT_FIELDS)
                    || !boundedText(amount.get("unit"), 1, 120)
                    || !boundedText(amount.get("quantity"), 1, 100)) {
                throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
            }
            String unit = amount.get("unit").textValue();
            String quantity = amount.get("quantity").textValue();
            if (!("lovelace".equals(unit) || unit.matches("[0-9a-f]{56,120}"))
                    || !quantity.matches("0|-?[1-9][0-9]*")
                    || amounts.putIfAbsent(unit, quantity) != null) {
                throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
            }
        }
        JsonNode inline = output.get("inline_datum");
        String inlineDatum = null;
        if (inline != null && !inline.isNull()) {
            if (!inline.isTextual() || inline.textValue().isEmpty()
                    || inline.textValue().length() > 8 * 1024 * 2
                    || (inline.textValue().length() & 1) != 0
                    || !inline.textValue().matches("[0-9a-f]+")) {
                throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
            }
            inlineDatum = inline.textValue();
        }
        return new ParsedOutput(output.get("output_index").intValue(),
                output.get("address").textValue(), Map.copyOf(amounts), inlineDatum);
    }

    private static boolean exactFields(JsonNode object, Set<String> expected) {
        Set<String> present = new HashSet<>();
        object.fieldNames().forEachRemaining(present::add);
        return present.equals(expected);
    }

    private static boolean boundedArray(JsonNode node, int maximum, boolean mayBeEmpty) {
        return node != null && node.isArray() && node.size() <= maximum
                && (mayBeEmpty || !node.isEmpty());
    }

    private static String text(JsonNode node, int exactLength) {
        return node != null && node.isTextual()
                && node.textValue().length() == exactLength ? node.textValue() : null;
    }

    private static boolean boundedText(JsonNode node, int minimum, int maximum) {
        return node.isTextual() && node.textValue().length() >= minimum
                && node.textValue().length() <= maximum
                && node.textValue().chars().noneMatch(character -> character < 0x21
                || character == 0x7f);
    }

    record Status(long height,
                  String stateRoot,
                  String memberKey,
                  int members,
                  int threshold,
                  String stateMachine,
                  long anchoredHeight,
                  String anchorTx,
                  long anchorSlot,
                  String anchorScriptAddress,
                  String anchorThreadPolicyId) {
    }

    record FinalityAudit(int certificateSignatures,
                         int threshold,
                         Set<String> memberKeys,
                         boolean anchored,
                         long anchoredHeight,
                         String anchorTx,
                         long anchorSlot,
                         String anchorBlockHash,
                         String anchorStateRoot,
                         long messageHeight,
                         String messageStateRoot,
                         Map<Long, String> certifiedStateRoots) {
        FinalityAudit {
            memberKeys = Set.copyOf(memberKeys);
            certifiedStateRoots = Map.copyOf(certifiedStateRoots);
        }
    }

    record AnchorTransaction(long slot) {
    }

    record AnchorExpectation(String chainId,
                             long l1Slot,
                             long anchoredHeight,
                             byte[] blockHash,
                             byte[] stateRoot,
                             Set<String> memberKeys,
                             int threshold,
                             String scriptAddress,
                             String threadPolicyId) {
        AnchorExpectation {
            if (chainId == null || chainId.isBlank()) {
                throw new IllegalArgumentException("chainId is required");
            }
            blockHash = blockHash.clone();
            stateRoot = stateRoot.clone();
            memberKeys = Set.copyOf(memberKeys);
        }

        boolean matches(AnchorDatumV1 datum) {
            return datum.height() == anchoredHeight
                    && datum.chainId().equals(chainId)
                    && Arrays.equals(datum.blockHash(), blockHash)
                    && Arrays.equals(datum.stateRoot(), stateRoot)
                    && new HashSet<>(datum.memberKeysHex()).equals(memberKeys)
                    && datum.memberKeysHex().size() == memberKeys.size()
                    && datum.threshold() == threshold;
        }
    }

    record AnchorCommitment(String transactionHash,
                            long l1Slot,
                            int outputIndex,
                            String scriptAddress,
                            String threadTokenUnit,
                            String inlineDatumHex) {
    }

    private record ParsedOutput(int outputIndex,
                                String address,
                                Map<String, String> amounts,
                                String inlineDatum) {
    }
}
