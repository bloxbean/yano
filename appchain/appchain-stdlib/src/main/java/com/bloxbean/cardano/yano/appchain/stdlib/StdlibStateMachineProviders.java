package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfileCommitment;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipView;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfile;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.appchain.config.AppChainApprovalsConfig;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * ServiceLoader providers for the standard-library state machines.
 * Select via {@code yano.app-chain.state-machine} (or per chain via
 * {@code yano.app-chain.chains[i].state-machine}).
 * <p>
 * Machines with settings read them from the chain's dynamic plugin config
 * under {@code yano.app-chain.machines.<machine-id>.*} (ADR app-layer/008.1
 * I1.4), e.g. {@code machines.balances.minter}.
 */
public final class StdlibStateMachineProviders {

    public static final String AUTHENTICATED_MAP_GENESIS_SETTING =
            "machines.authenticated-map.genesis-cbor-hex";

    private StdlibStateMachineProviders() {
    }

    public static final class AuthenticatedMapProvider implements AppStateMachineProvider {
        @Override
        public String id() {
            return AuthenticatedMapStateMachine.ID;
        }

        @Override
        public AppStateMachine create() {
            throw new IllegalStateException("authenticated-map requires "
                    + AUTHENTICATED_MAP_GENESIS_SETTING);
        }

        @Override
        public AppStateMachine create(AppStateMachineContext context) {
            String encoded = context.settings().get(AUTHENTICATED_MAP_GENESIS_SETTING);
            if (encoded == null || encoded.isEmpty() || (encoded.length() & 1) != 0
                    || encoded.length() > 32 * 1024 * 1024
                    || !isCanonicalLowerHex(encoded)) {
                throw new IllegalArgumentException(AUTHENTICATED_MAP_GENESIS_SETTING
                        + " must contain bounded canonical lowercase hex");
            }
            AuthenticatedMapContract.Genesis genesis;
            try {
                genesis = AuthenticatedMapContract.decodeGenesis(
                        HexFormat.of().parseHex(encoded));
            } catch (IllegalArgumentException malformed) {
                throw new IllegalArgumentException(AUTHENTICATED_MAP_GENESIS_SETTING
                        + " is not canonical authenticated-map v1 genesis", malformed);
            }
            if (!context.chainId().equals(genesis.chainId())) {
                throw new IllegalArgumentException(
                        "authenticated-map genesis chain id differs from configured chain id");
            }
            StateCommitmentProfile profile = StateCommitmentProfiles.require(
                    genesis.commitmentProfileId());
            if (!Arrays.equals(profile.formatFingerprint(), genesis.formatFingerprint())) {
                throw new IllegalArgumentException(
                        "authenticated-map genesis format fingerprint is incompatible");
            }
            StateCommitmentIdentity stateIdentity = context.stateCommitmentIdentity()
                    .orElseGet(() -> StateCommitmentIdentity.fromSettings(context.settings()));
            if (stateIdentity.legacy()
                    || !stateIdentity.profile().equals(profile)
                    || !Arrays.equals(stateIdentity.genesisId(),
                    AuthenticatedMapContract.genesisId(genesis))) {
                throw new IllegalArgumentException(
                        "authenticated-map genesis differs from the runtime state commitment identity");
            }
            AppChainConsensusProfile consensus = context.consensusProfile()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "authenticated-map requires the normalized consensus profile"));
            if (!Arrays.equals(AppChainConsensusProfileCommitment.digest(consensus),
                    genesis.frameworkConsensusProfileDigest())) {
                throw new IllegalArgumentException(
                        "authenticated-map genesis consensus profile digest is incompatible");
            }
            if (genesis.maxBatchBytes() > consensus.maxMessageBytes()) {
                throw new IllegalArgumentException(
                        "authenticated-map genesis batch bytes exceed framework message bytes");
            }
            AppChainMembershipView membership = context.membershipView()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "authenticated-map requires the immutable membership view"));
            if (!Arrays.equals(membership.epochAt(0).digest(),
                    genesis.membershipCommitment())) {
                throw new IllegalArgumentException(
                        "authenticated-map genesis membership commitment is incompatible");
            }
            return AuthenticatedMapPreset.create(context, genesis);
        }

        private static boolean isCanonicalLowerHex(String value) {
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (!((character >= '0' && character <= '9')
                        || (character >= 'a' && character <= 'f'))) {
                    return false;
                }
            }
            return true;
        }
    }

    public static final class KvRegistryProvider implements AppStateMachineProvider {
        @Override
        public String id() {
            return KvRegistryStateMachine.ID;
        }

        @Override
        public AppStateMachine create() {
            return new KvRegistryStateMachine();
        }

        @Override
        public AppStateMachine create(AppStateMachineContext context) {
            String format = context.settings().getOrDefault("machines.kv-registry.value-format", "raw");
            return new KvRegistryStateMachine(KvRegistryStateMachine.ValueFormat.parse(format));
        }
    }

    public static final class ApprovalsProvider implements AppStateMachineProvider {
        @Override
        public String id() {
            return ApprovalsStateMachine.ID;
        }

        @Override
        public AppStateMachine create() {
            return new ApprovalsStateMachine();
        }

        @Override
        public AppStateMachine create(AppStateMachineContext context) {
            return new ApprovalsStateMachine(
                    AppChainApprovalsConfig.fromSettings(context.settings()),
                    com.bloxbean.cardano.yano.api.appchain.effects.ActivationSchedule
                            .from(context.settings(), ApprovalsStateMachine.ID));
        }
    }

    public static final class BalancesProvider implements AppStateMachineProvider {
        @Override
        public String id() {
            return BalancesStateMachine.ID;
        }

        @Override
        public AppStateMachine create() {
            return new BalancesStateMachine();
        }

        @Override
        public AppStateMachine create(AppStateMachineContext context) {
            return new BalancesStateMachine(context.settings().getOrDefault("machines.balances.minter", ""));
        }
    }

    public static final class DocTrailProvider implements AppStateMachineProvider {
        @Override
        public String id() {
            return DocTrailStateMachine.ID;
        }

        @Override
        public AppStateMachine create() {
            return new DocTrailStateMachine();
        }
    }
}
