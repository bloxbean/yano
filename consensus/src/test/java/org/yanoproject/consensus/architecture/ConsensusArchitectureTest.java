package org.yanoproject.consensus.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ConsensusArchitectureTest {

    @Test
    void consensusMustNotDependOnRuntimeOrP2p() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("org.yanoproject.consensus..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.yanoproject.runtime..",
                        "org.yanoproject.p2p..");

        rule.check(new ClassFileImporter().importPackages("org.yanoproject.consensus"));
    }
}
