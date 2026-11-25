package com.begics.rag;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.begics.rag", importOptions = DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest
  static final ArchRule packageSlicesShouldBeFreeOfCycles =
      slices().matching("com.begics.rag.(*)..").should().beFreeOfCycles();

  @ArchTest
  static final ArchRule apiShouldNotDependOnInfrastructure =
      noClasses().that().resideInAPackage("com.begics.rag.api..")
          .should().dependOnClassesThat().resideInAnyPackage(
              "com.begics.rag.domain..", "com.begics.rag.model..", "com.begics.rag.storage..",
              "com.begics.rag.store..", "com.begics.rag.security..");

  @ArchTest
  static final ArchRule applicationShouldNotDependOnWebOrSecurity =
      noClasses().that().resideInAPackage("com.begics.rag.service..")
          .should().dependOnClassesThat().resideInAnyPackage("com.begics.rag.api..", "com.begics.rag.security..");

  @ArchTest
  static final ArchRule contractsShouldNotDependOnImplementationPackages =
      noClasses().that().resideInAPackage("com.begics.rag.contract..")
          .should().dependOnClassesThat().resideInAnyPackage(
              "com.begics.rag.api..", "com.begics.rag.domain..", "com.begics.rag.model..",
              "com.begics.rag.security..", "com.begics.rag.service..", "com.begics.rag.storage..",
              "com.begics.rag.store..");

  @ArchTest
  static final ArchRule domainShouldNotDependOnApplicationPackages =
      noClasses().that().resideInAPackage("com.begics.rag.domain..")
          .should().dependOnClassesThat().resideInAnyPackage(
              "com.begics.rag.api..", "com.begics.rag.contract..", "com.begics.rag.model..",
              "com.begics.rag.security..", "com.begics.rag.service..", "com.begics.rag.storage..",
              "com.begics.rag.store..");

  @ArchTest
  static final ArchRule modelsShouldNotDependOnApplicationOrPersistence =
      noClasses().that().resideInAPackage("com.begics.rag.model..")
          .should().dependOnClassesThat().resideInAnyPackage(
              "com.begics.rag.api..", "com.begics.rag.contract..", "com.begics.rag.security..",
              "com.begics.rag.service..", "com.begics.rag.storage..", "com.begics.rag.store..");

  @ArchTest
  static final ArchRule persistenceShouldNotDependOnWebOrProviderPackages =
      noClasses().that().resideInAPackage("com.begics.rag.store..")
          .should().dependOnClassesThat().resideInAnyPackage(
              "com.begics.rag.api..", "com.begics.rag.contract..", "com.begics.rag.model..",
              "com.begics.rag.security..", "com.begics.rag.service..", "com.begics.rag.storage..");

  @ArchTest
  static final ArchRule storageShouldNotDependOnApplicationOrPersistence =
      noClasses().that().resideInAPackage("com.begics.rag.storage..")
          .should().dependOnClassesThat().resideInAnyPackage(
              "com.begics.rag.api..", "com.begics.rag.contract..", "com.begics.rag.model..",
              "com.begics.rag.security..", "com.begics.rag.service..", "com.begics.rag.store..");

  @ArchTest
  static final ArchRule securityShouldBeIndependentOfApplicationPackages =
      noClasses().that().resideInAPackage("com.begics.rag.security..")
          .should().dependOnClassesThat().resideInAnyPackage(
              "com.begics.rag.api..", "com.begics.rag.contract..", "com.begics.rag.domain..",
              "com.begics.rag.model..", "com.begics.rag.service..", "com.begics.rag.storage..",
              "com.begics.rag.store..");
}
