package architecture;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5.2 Código fuente de las Fitness Functions (stack único: Java / JUnit 5 / ArchUnit).
 * Monolito Modular - Sistema Digital de Juego de Cartas (Clásica y Apuntado a 101).
 *
 * RG-01, RG-02 y RG-03 se verifican con ArchUnit sobre el bytecode.
 * RG-04 (CC <= 10) se verifica en el mismo stack con JavaParser sobre el código fuente,
 * porque ArchUnit no calcula complejidad ciclomática.
 */
public class ArchitectureFitnessTest {

    /** Módulos funcionales colaterales sujetos a RG-03 (gameplay es el mediador y queda fuera). */
    private static final List<String> COLLATERAL_MODULES = List.of("player", "deck", "scoring", "rules");

    /** RG-04: umbral máximo de complejidad ciclomática de McCabe por método. */
    private static final int MAX_CYCLOMATIC_COMPLEXITY = 10;

    /** RG-04: carpetas de validadores (obligatorias según la declaración formal). */
    private static final List<String> VALIDATOR_DIRS =
            List.of("app/rules/engine", "app/rules/classic", "app/rules/apuntado");

    /** RG-04: restricción adicional del equipo sobre el calculador de penalizaciones. */
    private static final boolean APPLY_TO_SCORING_CALCULATOR = true;
    private static final String SCORING_CALCULATOR_DIR = "app/scoring/calculator";

    /** Raíz de fuentes; se puede cambiar con -Dsources.root=... */
    private static final Path SOURCES_ROOT =
            Paths.get(System.getProperty("sources.root", "src/main/java"));

    private static JavaClasses importedClasses;

    @BeforeAll
    public static void setUp() {
        // Se excluyen las clases de test para que no generen falsos positivos.
        importedClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("app");
    }

    // ------------------------------------------------------------------ RG-01

    @Test
    public void rg01_isolation_layer_test() {
        // Presentación y controladores de entrada (app.gameplay.controller) no pueden depender
        // de infraestructura ni de persistencia. La estructura de namespaces (1.2) no define un
        // paquete "ui" propio; "app.ui.." se mantiene por si se crea en la estructura definitiva.
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("app.ui..", "app.gameplay.controller..")
                .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..persistence..")
                .because("RG-01: la UI y los controladores deben comunicarse a través de IGameController "
                        + "(GameOrchestrator), sin acceso directo a infraestructura o persistencia.");

        rule.check(importedClasses);
    }

    // ------------------------------------------------------------------ RG-02

    @Test
    public void rg02_strategy_independence_test() {
        // Independencia bidireccional entre ReglaClasicaStrategy y ReglaApuntadoStrategy.
        // Las dependencias hacia app.rules.engine y app.core siguen permitidas.
        ArchRule classicToApuntado = noClasses()
                .that().resideInAPackage("app.rules.classic..")
                .should().dependOnClassesThat().resideInAPackage("app.rules.apuntado..")
                .because("RG-02: la modalidad Clásica no puede conocer la modalidad Apuntado.");

        ArchRule apuntadoToClassic = noClasses()
                .that().resideInAPackage("app.rules.apuntado..")
                .should().dependOnClassesThat().resideInAPackage("app.rules.classic..")
                .because("RG-02: la modalidad Apuntado no puede conocer la modalidad Clásica.");

        classicToApuntado.check(importedClasses);
        apuntadoToClassic.check(importedClasses);
    }

    @Test
    public void rg02_no_cycles_between_modules_test() {
        // Ausencia de ciclos entre los módulos funcionales de primer nivel
        // (core, player, deck, gameplay, scoring, rules).
        ArchRule rule = slices().matching("app.(*)..")
                .should().beFreeOfCycles()
                .because("RG-02: el Monolito Modular debe mantenerse libre de dependencias circulares entre módulos.");

        rule.check(importedClasses);
    }

    // ------------------------------------------------------------------ RG-03

    @Test
    public void rg03_mediator_topology_test() {
        // Ningún módulo colateral (player, deck, scoring, rules) puede depender de OTRO módulo
        // colateral. Las dependencias internas de cada módulo (p. ej. RuleEngine ->
        // IGameRulesStrategy, o player.application -> player.domain) quedan permitidas, y
        // gameplay puede depender de los demás porque es el mediador (no se le restringe).
        for (String module : COLLATERAL_MODULES) {
            String[] otherModules = COLLATERAL_MODULES.stream()
                    .filter(other -> !other.equals(module))
                    .map(other -> "app." + other + "..")
                    .toArray(String[]::new);

            ArchRule rule = noClasses()
                    .that().resideInAPackage("app." + module + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(otherModules)
                    .because("RG-03: el módulo '" + module + "' no puede comunicarse punto a punto con otros "
                            + "módulos colaterales; debe hacerlo a través del mediador gameplay.");

            rule.check(importedClasses);
        }
    }

    // ------------------------------------------------------------------ RG-04

    @Test
    public void rg04_cyclomatic_complexity_limit_test() throws IOException {
        List<String> dirs = new ArrayList<>(VALIDATOR_DIRS);
        if (APPLY_TO_SCORING_CALCULATOR) {
            dirs.add(SCORING_CALCULATOR_DIR);
        }

        JavaParser parser = new JavaParser(
                new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

        List<String> violations = new ArrayList<>();
        int analyzedFiles = 0;

        for (String dir : dirs) {
            Path folder = SOURCES_ROOT.resolve(dir);
            if (!Files.isDirectory(folder)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> walk = Files.walk(folder)) {
                files = walk.filter(p -> p.toString().endsWith(".java")).toList();
            }
            for (Path file : files) {
                analyzedFiles++;
                ParseResult<CompilationUnit> result = parser.parse(file);
                assertTrue(result.isSuccessful() && result.getResult().isPresent(),
                        "No se pudo analizar " + file + ": " + result.getProblems());
                CompilationUnit cu = result.getResult().get();

                for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                    check(file, m.getNameAsString(), m, violations);
                }
                for (ConstructorDeclaration c : cu.findAll(ConstructorDeclaration.class)) {
                    check(file, c.getNameAsString(), c, violations);
                }
            }
        }

        // Evita un "verde vacío" si la raíz de fuentes o los paquetes están mal configurados.
        assertTrue(analyzedFiles > 0,
                "RG-04: no se encontró ningún .java en " + SOURCES_ROOT.toAbsolutePath()
                        + " para " + dirs + ". Ajuste -Dsources.root.");

        assertEquals(0, violations.size(),
                "RG-04: métodos con complejidad ciclomática > " + MAX_CYCLOMATIC_COMPLEXITY + ":\n"
                        + String.join("\n", violations));
    }

    private static void check(Path file, String name, Node node, List<String> violations) {
        int cc = cyclomaticComplexity(node);
        if (cc > MAX_CYCLOMATIC_COMPLEXITY) {
            int line = node.getBegin().map(p -> p.line).orElse(-1);
            violations.add(" - " + file + ":" + line + " " + name + "() CC=" + cc);
        }
    }

    /**
     * McCabe: 1 + puntos de decisión (if, for, foreach, while, do, case no-default,
     * catch, operador ternario, && y ||).
     */
    static int cyclomaticComplexity(Node node) {
        int cc = 1;
        cc += node.findAll(IfStmt.class).size();
        cc += node.findAll(ForStmt.class).size();
        cc += node.findAll(ForEachStmt.class).size();
        cc += node.findAll(WhileStmt.class).size();
        cc += node.findAll(DoStmt.class).size();
        cc += node.findAll(CatchClause.class).size();
        cc += node.findAll(ConditionalExpr.class).size();
        cc += (int) node.findAll(SwitchEntry.class).stream()
                .filter(e -> !e.getLabels().isEmpty())
                .count();
        cc += (int) node.findAll(BinaryExpr.class).stream()
                .filter(b -> b.getOperator() == BinaryExpr.Operator.AND
                        || b.getOperator() == BinaryExpr.Operator.OR)
                .count();
        return cc;
    }
}
