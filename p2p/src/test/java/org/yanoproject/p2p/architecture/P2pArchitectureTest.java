package org.yanoproject.p2p.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class P2pArchitectureTest {

    @Test
    void p2pMustNotDependOnRuntimeOrConsensus() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("org.yanoproject.p2p..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.yanoproject.runtime..",
                        "org.yanoproject.consensus..");

        rule.check(new ClassFileImporter().importPackages("org.yanoproject.p2p"));
    }
}
