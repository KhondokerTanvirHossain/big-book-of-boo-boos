package io.github.khondokertanvirhossain.bigbook.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.Test;

/**
 * The three rules from #7's seam, asserted rather than assumed. Test-only, and not counted against the
 * tripwire (CONTRIBUTING §4).
 *
 * <p>These have been <b>proven to fail</b>: a `core/policy` → HAPI-server import and a `catch (Exception)`
 * in `server/policy` were planted, the build went red, and they were removed (2026-09-22, issue #7). A rule
 * that has never failed has not been shown to work — the same reasoning as `check-deploy-selftest.sh`.
 */
class PolicyArchitectureTest {

    private static final String BIGBOOK = "io.github.khondokertanvirhossain.bigbook";

    private static JavaClasses production() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BIGBOOK);
    }

    /**
     * Rule 1: compilation cannot see a request or the database. This is what makes `core/policy` a pure
     * function, so it can be tested without a stack and retargeted at another engine (ADR-001 reversal cost).
     */
    @Test
    void policyCompilationCannotSeeARequestOrTheDatabase() {
        // `ca.uhn.fhir.rest.server.exceptions` is exempt, deliberately and narrowly: it holds 17 exception
        // types and no request state, and the validator must catch HAPI's specific exceptions rather than
        // `Exception` (D14). The rest of `rest.server..` — the servlet, the RestfulServer, RequestDetails —
        // stays forbidden. Naming the exemption keeps it from becoming a hole.
        noClasses()
                .that().resideInAPackage(BIGBOOK + ".core.policy..")
                .should().dependOnClassesThat(
                        com.tngtech.archunit.base.DescribedPredicate.describe(
                                "a request, a servlet or the database",
                                javaClass -> {
                                    String name = javaClass.getPackageName();
                                    if (name.equals("ca.uhn.fhir.rest.server.exceptions")) {
                                        return false;
                                    }
                                    return name.startsWith("ca.uhn.fhir.interceptor")
                                            || name.startsWith("ca.uhn.fhir.rest.api.server")
                                            || name.startsWith("ca.uhn.fhir.rest.server")
                                            || name.startsWith("jakarta.servlet")
                                            || name.startsWith("org.springframework.jdbc")
                                            || name.startsWith("org.springframework.web");
                                }))
                .because("core/policy compiles policies; it must not be able to read a request or the database (#7 seam)")
                .check(production());
    }

    /** Rule 2: the module boundary. core/ is a library; it must not reach back into the server. */
    @Test
    void coreDoesNotDependOnServer() {
        noClasses()
                .that().resideInAPackage(BIGBOOK + ".core..")
                .should().dependOnClassesThat().resideInAPackage(BIGBOOK + ".server..")
                .because("core/ is a library the server uses, never the other way round")
                .check(production());
    }

    /**
     * Rule 3: nothing outside `server/policy` reaches into the compiler's guts, so the seam stays the
     * `CompiledPolicy` contract rather than whatever happens to be convenient.
     */
    @Test
    void onlyServerPolicyBindsToTheCompilersInternals() {
        noClasses()
                .that().resideOutsideOfPackages(BIGBOOK + ".server.policy..", BIGBOOK + ".core.policy..")
                .should().dependOnClassesThat().resideInAPackage(BIGBOOK + ".core.policy.internal..")
                .because("the seam is the CompiledPolicy contract; the compiler's internals are private to it")
                .check(production());
    }

    /**
     * A fourth, from the V5 work: the enforcement path must not swallow exceptions. `catch (Exception)` in a
     * policy hook turns a bug into an allow, which is the opposite of failing closed (D14).
     *
     * <p>Written against `tryCatchBlocks` rather than `callMethodWhere`: a catch is not a method call, and the
     * first version of this rule passed with a planted `catch (Exception)` in place — proof that a rule which
     * has never failed has not been shown to work.
     */
    @Test
    void thePolicyPathCatchesSpecificExceptionsOnly() {
        JavaClasses production = production();
        java.util.List<String> broad = new java.util.ArrayList<>();
        for (com.tngtech.archunit.core.domain.JavaClass type : production) {
            if (!type.getPackageName().startsWith(BIGBOOK + ".core.policy")
                    && !type.getPackageName().startsWith(BIGBOOK + ".server.policy")) {
                continue;
            }
            for (com.tngtech.archunit.core.domain.JavaCodeUnit unit : type.getCodeUnits()) {
                for (com.tngtech.archunit.core.domain.TryCatchBlock block : unit.getTryCatchBlocks()) {
                    for (com.tngtech.archunit.core.domain.JavaClass caught : block.getCaughtThrowables()) {
                        // `Exception` and `Throwable` are forbidden outright: catching them hides
                        // programming errors. `RuntimeException` is allowed only where the handler is a
                        // documented fail-closed boundary — the two that exist turn a failure into a
                        // refusal (CriteriaValidator: unknown resource type -> reject; PolicyCompiler:
                        // unparseable criterion -> Criteria.Never), which is D14 working. Widening the
                        // rule to forbid those would push the code towards catching nothing and letting a
                        // HAPI parse error escape into the request path, which is worse.
                        boolean forbidden = caught.getName().equals("java.lang.Exception")
                                || caught.getName().equals("java.lang.Throwable");
                        if (forbidden) {
                            broad.add(unit.getFullName() + " catches " + caught.getSimpleName());
                        }
                    }
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(broad)
                .as("catching Exception or Throwable in the policy path hides a bug and can turn it into an allow (D14)")
                .isEmpty();

        // and the fail-closed boundaries that DO catch RuntimeException are pinned, so a new one is a
        // deliberate decision rather than drift
        java.util.List<String> failClosed = new java.util.ArrayList<>();
        for (com.tngtech.archunit.core.domain.JavaClass type : production) {
            if (!type.getPackageName().startsWith(BIGBOOK + ".core.policy")
                    && !type.getPackageName().startsWith(BIGBOOK + ".server.policy")) {
                continue;
            }
            for (com.tngtech.archunit.core.domain.JavaCodeUnit unit : type.getCodeUnits()) {
                for (com.tngtech.archunit.core.domain.TryCatchBlock block : unit.getTryCatchBlocks()) {
                    for (com.tngtech.archunit.core.domain.JavaClass caught : block.getCaughtThrowables()) {
                        if (caught.getName().equals("java.lang.RuntimeException")) {
                            failClosed.add(unit.getOwner().getSimpleName() + "." + unit.getName());
                        }
                    }
                }
            }
        }
        // The standard, so a third handler is argued against something rather than pointed at a precedent:
        // a RuntimeException handler belongs here only if EVERY path out of it narrows access. Both of these
        // convert a failure into a refusal and have no path that returns a permission:
        //
        //   CriteriaValidator.requireWritable   — FhirContext rejects the resource type; the only exit is
        //                                         throwing CriteriaRejectedException, so the policy will not save.
        //   PolicyCompiler.compileCriteria      — HAPI cannot parse the match URL; the only exit is
        //                                         Criteria.Never, which grants nothing.
        //   PolicyResolver.compile              — a policy document will not compile; the only exit is
        //                                         PolicyDefaults.denyAll, so the caller gets nothing at all.
        //                                         (Was PolicyBinder.resolve until the resolver was extracted
        //                                         for subscription delivery; same handler, same argument.)
        //
        // If a proposed handler has any exit that returns Always, a permitted interaction, an empty
        // hiddenFields list, or simply continues past the failure, it is not a fail-closed boundary and the
        // exception must be caught specifically instead. Add to this list only with that argument in the PR.
        org.assertj.core.api.Assertions.assertThat(failClosed)
                .as("every RuntimeException handler in the policy path must be a fail-closed boundary: see the comment above")
                .containsOnly("CriteriaValidator.requireWritable", "PolicyCompiler.compileCriteria",
                        "PolicyResolver.compile");
    }

}
