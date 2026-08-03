package ro.myfinance.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Executable architecture guardrails for the modular monolith. Plain JUnit (not {@code *IT}) so it runs in
 * the normal build. Encodes the hexagonal + module boundaries the refactors (S9–S13) established:
 * <ul>
 *   <li>domain stays free of Spring and the adapter layer (JPA annotations aside);</li>
 *   <li>controllers never reach a repository directly;</li>
 *   <li>no module reaches into another module's persistence adapter — cross-module reads go through that
 *       module's application layer (e.g. {@code CompanyDirectory}, {@code UserDirectory},
 *       {@code DocumentDirectory}).</li>
 * </ul>
 */
class ArchitectureTest {

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ro.myfinance");
    }

    @Test
    void domainDoesNotDependOnSpringOrAdapters() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "..adapter..")
                .because("domain must stay framework- and adapter-free (JPA mapping annotations aside)");
        rule.check(production);
    }

    @Test
    void controllersDoNotAccessRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..adapter.web..")
                .should().dependOnClassesThat().resideInAPackage("..adapter.persistence..")
                .because("controllers must go through the application layer, never a repository directly");
        rule.check(production);
    }

    @Test
    void noModuleReachesIntoAnotherModulesPersistence() {
        ArchRule rule = noClasses()
                .should(dependOnAnotherModulesPersistence())
                .because("cross-module reads must go through the owning module's application layer "
                        + "(a *Directory / query service), not its persistence adapter");
        rule.check(production);
    }

    private static ArchCondition<JavaClass> dependOnAnotherModulesPersistence() {
        return new ArchCondition<>("depend on another module's adapter.persistence") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String originModule = moduleOf(origin);
                for (Dependency dep : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dep.getTargetClass();
                    if (!target.getPackageName().contains(".adapter.persistence")) {
                        continue;
                    }
                    String targetModule = moduleOf(target);
                    if (targetModule != null && !targetModule.equals(originModule)
                            && !"common".equals(targetModule)) {
                        events.add(SimpleConditionEvent.violated(origin, dep.getDescription()));
                    }
                }
            }
        };
    }

    /** The top-level module segment of a {@code ro.myfinance.<module>...} class, or null if outside. */
    private static String moduleOf(JavaClass c) {
        String prefix = "ro.myfinance.";
        String pkg = c.getPackageName();
        if (!pkg.startsWith(prefix)) {
            return null;
        }
        String rest = pkg.substring(prefix.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }
}
