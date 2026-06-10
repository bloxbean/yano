package com.bloxbean.cardano.yano.runtime.genesis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShelleyGenesisParserTest {

    @Test
    void parse_extractsInitialFunds() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/genesis/test-shelley-genesis.json")) {
            ShelleyGenesisData data = ShelleyGenesisParser.parse(in);

            assertThat(data.initialFunds()).hasSize(2);
            assertThat(data.initialFunds())
                    .containsEntry("604fb41f142d1f7b8e51dd9232a110cf72aec955275439b72870b9c20d", new BigInteger("10000000000000"));
            assertThat(data.initialFunds())
                    .containsEntry("60a0f1aa7dca95017c11e7e373aebcf0c4568cf47ec12b94f8eb5bba8b", new BigInteger("3000000000000000"));
        }
    }

    @Test
    void parse_usesEmbeddedExtraConfigInitialFunds() throws IOException {
        String json = """
                {
                  "initialFunds": {},
                  "extraConfig": {
                    "initialFunds": {
                      "data": {
                        "addr_test1": "1234"
                      }
                    }
                  }
                }
                """;

        try (InputStream in = new java.io.ByteArrayInputStream(json.getBytes())) {
            ShelleyGenesisData data = ShelleyGenesisParser.parse(in);

            assertThat(data.initialFunds()).containsExactlyEntriesOf(
                    java.util.Map.of("addr_test1", BigInteger.valueOf(1234)));
            assertThat(data.bootstrap().initialFunds()).containsExactlyEntriesOf(data.initialFunds());
        }
    }

    @Test
    void parse_extraConfigInitialFundsConflictingWithLegacyFailsClosed() {
        String json = """
                {
                  "initialFunds": {"addr1": "1"},
                  "extraConfig": {
                    "initialFunds": {
                      "data": {"addr2": "2"}
                    }
                  }
                }
                """;

        assertThatThrownBy(() -> {
            try (InputStream in = new java.io.ByteArrayInputStream(json.getBytes())) {
                ShelleyGenesisParser.parse(in);
            }
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot specify both initialFunds and extraConfig.initialFunds");
    }

    @Test
    void parse_extraConfigInitialFundsFileFailsClosed() {
        String json = """
                {
                  "initialFunds": {},
                  "extraConfig": {
                    "initialFunds": {
                      "file": ["funds.json"],
                      "hash": "00"
                    }
                  }
                }
                """;

        assertThatThrownBy(() -> {
            try (InputStream in = new java.io.ByteArrayInputStream(json.getBytes())) {
                ShelleyGenesisParser.parse(in);
            }
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("file injection is not supported");
    }

    @Test
    void parse_extractsNetworkMetadata() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/genesis/test-shelley-genesis.json")) {
            ShelleyGenesisData data = ShelleyGenesisParser.parse(in);

            assertThat(data.networkMagic()).isEqualTo(42);
            assertThat(data.epochLength()).isEqualTo(600);
            assertThat(data.slotLength()).isEqualTo(1.0);
            assertThat(data.systemStart()).isEqualTo("2024-01-01T00:00:00Z");
            assertThat(data.maxLovelaceSupply()).isEqualTo(45000000000000000L);
            assertThat(data.activeSlotsCoeff()).isEqualTo(1.0);
        }
    }

    @Test
    void parse_emptyInitialFunds_returnsEmptyMap() throws IOException {
        // Use preprod genesis which has empty initialFunds
        String json = """
                {
                  "activeSlotsCoeff": 0.05,
                  "epochLength": 432000,
                  "initialFunds": {},
                  "networkMagic": 1,
                  "slotLength": 1,
                  "systemStart": "2022-04-01T00:00:00Z",
                  "maxLovelaceSupply": 45000000000000000
                }
                """;
        try (InputStream in = new java.io.ByteArrayInputStream(json.getBytes())) {
            ShelleyGenesisData data = ShelleyGenesisParser.parse(in);

            assertThat(data.initialFunds()).isEmpty();
            assertThat(data.networkMagic()).isEqualTo(1);
            assertThat(data.activeSlotsCoeff()).isEqualTo(0.05);
        }
    }

    @Test
    void parse_protocolRationalsUseJsonDecimals() throws IOException {
        String json = """
                {
                  "protocolParams": {
                    "rho": 0.003,
                    "tau": 0.20,
                    "a0": 0.3,
                    "decentralisationParam": 1
                  }
                }
                """;
        try (InputStream in = new java.io.ByteArrayInputStream(json.getBytes())) {
            ShelleyGenesisData data = ShelleyGenesisParser.parse(in);

            assertThat(data.rho()).isEqualByComparingTo(new BigDecimal("0.003"));
            assertThat(data.tau()).isEqualByComparingTo(new BigDecimal("0.20"));
            assertThat(data.a0()).isEqualByComparingTo(new BigDecimal("0.3"));
            assertThat(data.decentralisationParam()).isEqualByComparingTo(BigDecimal.ONE);
        }
    }

    @Test
    void parse_stakingPoolsAndDelegations() throws IOException {
        String poolHash = "7301761068762f5900bde9eb7c1c15b09840285130f5b0f53606cc57";
        String stakeHash = "295b987135610616f3c74e11c94d77b6ced5ccc93a7d719cfb135062";
        String rewardHash = "11a14edf73b08a0a27cb98b2c57eb37c780df18fcfcf6785ed5df84a";
        String json = """
                {
                  "networkMagic": 42,
                  "maxLovelaceSupply": 45000000000000000,
                  "initialFunds": {"addr": "1000"},
                  "protocolParams": {
                    "keyDeposit": 2000000,
                    "poolDeposit": 500000000
                  },
                  "staking": {
                    "pools": {
                      "%s": {
                        "cost": 340000000,
                        "margin": 0.2,
                        "metadata": null,
                        "owners": [],
                        "pledge": 0,
                        "publicKey": "%s",
                        "relays": [],
                        "rewardAccount": {
                          "credential": {
                            "keyHash": "%s"
                          },
                          "network": "Testnet"
                        },
                        "vrf": "c2b62ffa92ad18ffc117ea3abeb161a68885000a466f9c71db5e4731d6630061"
                      }
                    },
                    "stake": {
                      "%s": "%s"
                    }
                  }
                }
                """.formatted(poolHash, poolHash, rewardHash, stakeHash, poolHash);

        try (InputStream in = new java.io.ByteArrayInputStream(json.getBytes())) {
            ShelleyGenesisData data = ShelleyGenesisParser.parse(in);

            assertThat(data.bootstrap().poolDeposit()).isEqualTo(BigInteger.valueOf(500_000_000));
            assertThat(data.bootstrap().keyDeposit()).isEqualTo(BigInteger.valueOf(2_000_000));
            assertThat(data.bootstrap().pools()).hasSize(1);
            assertThat(data.bootstrap().delegations()).hasSize(1);

            var pool = data.bootstrap().pools().get(0);
            assertThat(pool.poolHash()).isEqualTo(poolHash);
            assertThat(pool.cost()).isEqualTo(BigInteger.valueOf(340_000_000));
            assertThat(pool.marginNumerator()).isEqualTo(BigInteger.valueOf(2));
            assertThat(pool.marginDenominator()).isEqualTo(BigInteger.TEN);
            assertThat(pool.rewardAccount()).isEqualTo("e0" + rewardHash);

            var delegation = data.bootstrap().delegations().get(0);
            assertThat(delegation.stakeCredentialHash()).isEqualTo(stakeHash);
            assertThat(delegation.poolHash()).isEqualTo(poolHash);
        }
    }

    @Test
    void parse_stakingPoolsSupportsCanonicalHaskellFieldNames() throws IOException {
        String poolHash = "7301761068762f5900bde9eb7c1c15b09840285130f5b0f53606cc57";
        String stakeHash = "295b987135610616f3c74e11c94d77b6ced5ccc93a7d719cfb135062";
        String rewardHash = "11a14edf73b08a0a27cb98b2c57eb37c780df18fcfcf6785ed5df84a";
        String json = """
                {
                  "networkMagic": 42,
                  "maxLovelaceSupply": 45000000000000000,
                  "initialFunds": {},
                  "protocolParams": {
                    "keyDeposit": 2000000,
                    "poolDeposit": 500000000
                  },
                  "staking": {
                    "pools": {
                      "%s": {
                        "poolId": "%s",
                        "vrf": "c2b62ffa92ad18ffc117ea3abeb161a68885000a466f9c71db5e4731d6630061",
                        "pledge": 0,
                        "cost": 340000000,
                        "margin": 0.2,
                        "accountAddress": {
                          "credential": {
                            "keyHash": "%s"
                          },
                          "network": 0
                        },
                        "owners": [],
                        "relays": [],
                        "metadata": null
                      }
                    },
                    "stake": {
                      "%s": "%s"
                    }
                  }
                }
                """.formatted(poolHash, poolHash, rewardHash, stakeHash, poolHash);

        try (InputStream in = new java.io.ByteArrayInputStream(json.getBytes())) {
            ShelleyGenesisData data = ShelleyGenesisParser.parse(in);

            assertThat(data.bootstrap().pools()).hasSize(1);
            assertThat(data.bootstrap().pools().get(0).poolHash()).isEqualTo(poolHash);
            assertThat(data.bootstrap().pools().get(0).rewardAccount()).isEqualTo("e0" + rewardHash);
            assertThat(data.bootstrap().delegations().get(0).poolHash()).isEqualTo(poolHash);
        }
    }

    @Test
    void parse_stakingPoolBodyIdMustMatchMapKey() {
        String poolHash = "7301761068762f5900bde9eb7c1c15b09840285130f5b0f53606cc57";
        String json = """
                {
                  "staking": {
                    "pools": {
                      "%s": {
                        "poolId": "%s",
                        "vrf": "c2b62ffa92ad18ffc117ea3abeb161a68885000a466f9c71db5e4731d6630061",
                        "pledge": 0,
                        "cost": 340000000,
                        "margin": 0.2,
                        "accountAddress": {
                          "credential": {"keyHash": "11a14edf73b08a0a27cb98b2c57eb37c780df18fcfcf6785ed5df84a"},
                          "network": 0
                        },
                        "owners": [],
                        "relays": [],
                        "metadata": null
                      }
                    },
                    "stake": {}
                  }
                }
                """.formatted(poolHash, "cc".repeat(28));

        assertThatThrownBy(() -> {
            try (InputStream in = new java.io.ByteArrayInputStream(json.getBytes())) {
                ShelleyGenesisParser.parse(in);
            }
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match map key");
    }

    @Test
    void parse_stakingPoolMissingRequiredFieldFailsClosed() {
        String poolHash = "7301761068762f5900bde9eb7c1c15b09840285130f5b0f53606cc57";
        String json = """
                {
                  "staking": {
                    "pools": {
                      "%s": {
                        "poolId": "%s",
                        "vrf": "c2b62ffa92ad18ffc117ea3abeb161a68885000a466f9c71db5e4731d6630061",
                        "pledge": 0,
                        "margin": 0.2,
                        "accountAddress": {
                          "credential": {"keyHash": "11a14edf73b08a0a27cb98b2c57eb37c780df18fcfcf6785ed5df84a"},
                          "network": 0
                        },
                        "owners": [],
                        "relays": [],
                        "metadata": null
                      }
                    },
                    "stake": {}
                  }
                }
                """.formatted(poolHash, poolHash);

        assertThatThrownBy(() -> {
            try (InputStream in = new java.io.ByteArrayInputStream(json.getBytes())) {
                ShelleyGenesisParser.parse(in);
            }
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required field cost");
    }

    @Test
    void parse_stakingPoolMalformedRewardAccountFailsClosed() {
        String poolHash = "7301761068762f5900bde9eb7c1c15b09840285130f5b0f53606cc57";
        String json = """
                {
                  "staking": {
                    "pools": {
                      "%s": {
                        "poolId": "%s",
                        "vrf": "c2b62ffa92ad18ffc117ea3abeb161a68885000a466f9c71db5e4731d6630061",
                        "pledge": 0,
                        "cost": 340000000,
                        "margin": 0.2,
                        "accountAddress": {
                          "credential": {},
                          "network": 0
                        },
                        "owners": [],
                        "relays": [],
                        "metadata": null
                      }
                    },
                    "stake": {}
                  }
                }
                """.formatted(poolHash, poolHash);

        assertThatThrownBy(() -> {
            try (InputStream in = new java.io.ByteArrayInputStream(json.getBytes())) {
                ShelleyGenesisParser.parse(in);
            }
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid accountAddress/rewardAccount");
    }

    @Test
    void parse_missingStakingProducesEmptyBootstrapSection() throws IOException {
        String json = """
                {
                  "initialFunds": {},
                  "networkMagic": 1,
                  "maxLovelaceSupply": 45000000000000000
                }
                """;
        try (InputStream in = new java.io.ByteArrayInputStream(json.getBytes())) {
            ShelleyGenesisData data = ShelleyGenesisParser.parse(in);

            assertThat(data.bootstrap().hasStaking()).isFalse();
            assertThat(data.bootstrap().pools()).isEmpty();
            assertThat(data.bootstrap().delegations()).isEmpty();
        }
    }
}
