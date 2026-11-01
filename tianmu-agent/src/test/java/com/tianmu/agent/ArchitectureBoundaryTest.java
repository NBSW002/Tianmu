package com.tianmu.agent;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureBoundaryTest {
    @Test void reportTaskViewCannotCallModelsToolsOrMutateRunLifecycle() {
        var classes = new ClassFileImporter().importClasses(com.tianmu.agent.application.ReportTaskStateView.class);
        noClasses().should().dependOnClassesThat().haveFullyQualifiedName("com.tianmu.agent.runtime.RunState").check(classes);
        noClasses().should().dependOnClassesThat().resideInAnyPackage("..adapters..","..tool..","..model..","..harness..").check(classes);
    }
    @Test void domainContractsDoNotDependOnInfrastructureOrFeatureImplementations() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("com.tianmu.agent");
        noClasses().that().resideInAPackage("..core..")
                .should().dependOnClassesThat().resideInAnyPackage("..adapters..", "..video..", "..runtime..", "..harness..", "org.springframework..")
                .check(classes);
    }
    @Test void budgetEnforcementDoesNotDependOnTelemetryContext() {
        var classes = new ClassFileImporter().importClasses(com.tianmu.agent.runtime.control.ModelBudget.class);
        noClasses().should().dependOnClassesThat().resideInAPackage("io.opentelemetry.context..").check(classes);
    }
    @Test void httpControllersDoNotDependOnStorageImplementations() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("com.tianmu.agent");
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage("..adapters..").check(classes);
    }
    @Test void executionSchedulingDoesNotDependOnTelemetryImplementations() {
        var classes = new ClassFileImporter().importClasses(com.tianmu.agent.runtime.control.LoopControl.class);
        noClasses().should().dependOnClassesThat().resideInAnyPackage("io.opentelemetry..", "..harness..").check(classes);
    }
    @Test void evidencePolicyCannotMutateRunLifecycle() {
        var classes = new ClassFileImporter().importClasses(com.tianmu.agent.application.BusinessEvidencePolicy.class);
        noClasses().should().dependOnClassesThat().haveFullyQualifiedName("com.tianmu.agent.runtime.RunState").check(classes);
    }
    @Test void runRepositoryDoesNotExposeConversationAndReportOperations() {
        org.junit.jupiter.api.Assertions.assertFalse(com.tianmu.agent.runtime.store.ConversationStore.class.isAssignableFrom(com.tianmu.agent.runtime.store.RunRepository.class));
        org.junit.jupiter.api.Assertions.assertFalse(com.tianmu.agent.runtime.store.ReportStore.class.isAssignableFrom(com.tianmu.agent.runtime.store.RunRepository.class));
    }
    @Test void reportProgressCannotReachRunLifecycleModelsOrToolImplementations() {
        var classes = new ClassFileImporter().importClasses(com.tianmu.agent.application.ReportProgressService.class);
        noClasses().should().dependOnClassesThat().haveFullyQualifiedName("com.tianmu.agent.runtime.RunState").check(classes);
        noClasses().should().dependOnClassesThat().resideInAnyPackage("..model..", "..harness..", "..tool..", "..adapters..").check(classes);
    }
    @Test void historyQueryCannotMutateActiveRunStateOrCallModelsAndToolImplementations() {
        var classes = new ClassFileImporter().importClasses(com.tianmu.agent.application.HistoryQueryService.class);
        noClasses().should().dependOnClassesThat().haveFullyQualifiedName("com.tianmu.agent.runtime.RunState").check(classes);
        noClasses().should().dependOnClassesThat().haveFullyQualifiedName("com.tianmu.agent.runtime.ExecutionState").check(classes);
        noClasses().should().dependOnClassesThat().resideInAnyPackage("..model..", "..harness..", "..tool..", "..adapters..").check(classes);
    }
    @Test void reportContextProjectionCannotReachExecutionOrStorageAndOnlyBuildsAView() {
        var classes = new ClassFileImporter().importClasses(com.tianmu.agent.application.ReportContextProjection.class);
        noClasses().should().dependOnClassesThat().resideInAnyPackage("..runtime..","..adapters..","..tool..","..model..","..harness..").check(classes);
    }
}
