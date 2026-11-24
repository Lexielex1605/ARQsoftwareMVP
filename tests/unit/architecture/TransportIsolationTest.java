package unit.architecture;

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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fitness Functions complementarias de aislamiento del adaptador de transporte (app.infrastructure)
 * y complejidad ciclomática <= 10 en orquestador y capa web.
 */
public class TransportIsolationTest {

    private static final int MAX_CC = 10;
    private static final Path SOURCES_ROOT = Paths.get("src/main/java");
    private static JavaClasses importedClasses;

    @BeforeAll
    static void setUp() {
        importedClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("app");
    }

    @Test
    void transportLayerDependsOnlyOnOrchestratorInterfaceAndCoreDtos() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("app.infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "app.deck..", "app.rules..", "app.scoring..", "app.player..")
                .because("RG-01: la capa de transporte app.infrastructure solo debe comunicarse con IGameController y app.core.");

        rule.check(importedClasses);
    }

    @Test
    void orchestratorDoesNotDependOnConcreteInfrastructureAdapters() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("app.gameplay.orchestrator..")
                .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..")
                .because("El mediador GameOrchestrator debe depender únicamente de interfaces de servicio.");

        rule.check(importedClasses);
    }

    @Test
    void orchestratorAndTransportKeepCyclomaticComplexityWithinLimit() throws IOException {
        List<String> dirs = List.of("app/gameplay/orchestrator", "app/infrastructure/web");
        JavaParser parser = new JavaParser(
                new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        List<String> violations = new ArrayList<>();

        for (String dir : dirs) {
            Path folder = SOURCES_ROOT.resolve(dir);
            try (Stream<Path> walk = Files.walk(folder)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    ParseResult<CompilationUnit> result = parser.parse(file);
                    assertTrue(result.isSuccessful() && result.getResult().isPresent(),
                            "Error analizando " + file + ": " + result.getProblems());
                    CompilationUnit cu = result.getResult().get();
                    for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                        checkCc(file, m.getNameAsString(), m, violations);
                    }
                    for (ConstructorDeclaration c : cu.findAll(ConstructorDeclaration.class)) {
                        checkCc(file, c.getNameAsString(), c, violations);
                    }
                }
            }
        }
        assertEquals(0, violations.size(),
                "Métodos en orquestador/transporte con CC > " + MAX_CC + ":\n" + String.join("\n", violations));
    }

    private static void checkCc(Path file, String name, Node node, List<String> violations) {
        int cc = 1
                + node.findAll(IfStmt.class).size()
                + node.findAll(ForStmt.class).size()
                + node.findAll(ForEachStmt.class).size()
                + node.findAll(WhileStmt.class).size()
                + node.findAll(DoStmt.class).size()
                + node.findAll(CatchClause.class).size()
                + node.findAll(ConditionalExpr.class).size()
                + (int) node.findAll(SwitchEntry.class).stream().filter(e -> !e.getLabels().isEmpty()).count()
                + (int) node.findAll(BinaryExpr.class).stream()
                        .filter(b -> b.getOperator() == BinaryExpr.Operator.AND
                                || b.getOperator() == BinaryExpr.Operator.OR)
                        .count();
        if (cc > MAX_CC) {
            int line = node.getBegin().map(p -> p.line).orElse(-1);
            violations.add(" - " + file + ":" + line + " " + name + "() CC=" + cc);
        }
    }
}
