package com.llmstudy.rag;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.llmstudy.rag",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule rag_is_independent_from_http_and_chat = noClasses()
            .that().resideInAPackage("..module.rag..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..controller..", "..dto..", "..module.chat..");

    @ArchTest
    static final ArchRule controllers_do_not_use_rag_internals = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..module.rag.retrieval..",
                    "..module.rag.aggregation..",
                    "..module.rag.rerank..");
}
