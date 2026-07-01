package com.bloxbean.cardano.yano.p2p.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class P2pArchitectureTest {

    @Test
    void p2pMustNotDependOnRuntimeOrConsensus() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("com.bloxbean.cardano.yano.p2p..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.bloxbean.cardano.yano.runtime..",
                        "com.bloxbean.cardano.yano.consensus..");

        rule.check(new ClassFileImporter().importPackages("com.bloxbean.cardano.yano.p2p"));
    }
}
