package io.github.gbalazsfalvi.slotkeeper.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.*;
import org.junit.jupiter.api.Test;

/**
 * Executable boundaries protect the pure core and keep the vulnerable fixture out of the product.
 */
class ArchitectureTest {
  @Test
  void coreHasNoFrameworkOrAdapterDependency() {
    var classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.gbalazsfalvi.slotkeeper.core");
    noClasses()
        .that()
        .resideInAPackage("..core..")
        .should()
        .dependOnClassesThat()
        .resideOutsideOfPackages("java..", "io.github.gbalazsfalvi.slotkeeper.core..")
        .check(classes);
  }

  @Test
  void productCannotDependOnRehearsalOrVulnerableTeachingClasses() {
    var classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.gbalazsfalvi.slotkeeper");
    noClasses()
        .that()
        .resideOutsideOfPackages("..verification..", "..training..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..verification..", "..training..")
        .check(classes);
  }
}
