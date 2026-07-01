package com.bloxbean.cardano.yano.consensus.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ConsensusArchitectureTest {

    @Test
    void consensusMustNotDependOnRuntimeOrP2p() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("com.bloxbean.cardano.yano.consensus..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.bloxbean.cardano.yano.runtime..",
                        "com.bloxbean.cardano.yano.p2p..");

        rule.check(new ClassFileImporter().importPackages("com.bloxbean.cardano.yano.consensus"));
    }
}
