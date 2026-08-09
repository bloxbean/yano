package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ShowcaseStateCommitmentConfigTest {
    private static final Path CONFIG = Path.of(
            "src/main/showcase/config/application-appchain.yml");
    private static final Pattern CHAIN = Pattern.compile(
            "(?ms)^    chains\\[(\\d+)]:\\n(.*?)(?=^    chains\\[|\\z)");
    private static final Pattern SETTING = Pattern.compile(
            "(?m)^        (commitment-profile|format-fingerprint|genesis-id): \"?([^\"\\s]+)\"?$");

    @Test
    void everyNonGeneratedChainPinsACompleteUniqueStateIdentity() throws Exception {
        String yaml = Files.readString(CONFIG);
        Matcher chains = CHAIN.matcher(yaml);
        Set<Integer> seenIndexes = new HashSet<>();
        Set<String> genesisIds = new HashSet<>();
        String fingerprint = HexFormat.of().formatHex(
                StateCommitmentProfiles.MPF.formatFingerprint());

        while (chains.find()) {
            int index = Integer.parseInt(chains.group(1));
            seenIndexes.add(index);
            Matcher settings = SETTING.matcher(chains.group(2));
            Map<String, String> identity = new java.util.LinkedHashMap<>();
            while (settings.find()) {
                identity.put("state." + settings.group(1), settings.group(2));
            }
            if (index == 8 || index == 9) {
                assertThat(identity).as("authenticated-map identity is generated").isEmpty();
                continue;
            }
            assertThat(identity)
                    .hasSize(3)
                    .containsEntry(StateCommitmentIdentity.PROFILE_SETTING,
                            StateCommitmentProfiles.MPF.id())
                    .containsEntry(StateCommitmentIdentity.FINGERPRINT_SETTING,
                            fingerprint)
                    .containsKey(StateCommitmentIdentity.GENESIS_ID_SETTING);
            StateCommitmentIdentity resolved = StateCommitmentIdentity.fromSettings(identity);
            assertThat(genesisIds.add(HexFormat.of().formatHex(resolved.genesisId())))
                    .as("chain %s has a unique state genesis id", index)
                    .isTrue();
        }

        assertThat(seenIndexes).containsExactlyInAnyOrderElementsOf(
                java.util.stream.IntStream.rangeClosed(0, 12).boxed().toList());
        assertThat(genesisIds).hasSize(11);
    }
}
