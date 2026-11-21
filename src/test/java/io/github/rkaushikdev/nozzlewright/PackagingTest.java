package io.github.rkaushikdev.nozzlewright;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The build file has to match what the code actually imports.
 *
 * <p>A test run is the one environment where the test dependencies are always present, so a test
 * suite cannot notice a runtime dependency filed under the test scope unless it goes and looks at
 * the build file. This goes and looks: every third party package the shipped code imports must
 * come from a dependency the jar carries, nothing the shipped code imports may be test scoped, and
 * every version is pinned exactly, either in this file or by the pinned Spring Boot parent.
 */
class PackagingTest {

    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path SOURCE = ROOT.resolve("src/main/java/io/github/rkaushikdev/nozzlewright");

    /** Which declared dependency brings each imported package. The longest matching prefix wins. */
    static final Map<String, String> PROVIDED_BY = providers();

    /** Packages that come only from test scoped dependencies. */
    static final List<String> TEST_ONLY = List.of(
            "org.junit", "org.assertj", "org.mockito", "org.springframework.test", "org.springframework.boot.test");

    private static final Pattern IMPORT =
            Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);

    static Map<String, String> providers() {
        Map<String, String> made = new LinkedHashMap<>();
        made.put("jakarta.persistence", "spring-boot-starter-data-jpa");
        made.put("org.hibernate", "spring-boot-starter-data-jpa");
        made.put("org.springframework.orm", "spring-boot-starter-data-jpa");
        made.put("org.springframework.data", "spring-boot-starter-data-jpa");
        made.put("com.zaxxer.hikari", "spring-boot-starter-data-jpa");
        made.put("jakarta.validation", "spring-boot-starter-validation");
        made.put("org.flywaydb", "flyway-core");
        made.put("org.h2", "h2");
        made.put("com.fasterxml.jackson.dataformat.toml", "jackson-dataformat-toml");
        made.put("com.fasterxml.jackson", "spring-boot-starter-web");
        made.put("org.springframework", "spring-boot-starter-web");
        made.put("jakarta.servlet", "spring-boot-starter-web");
        made.put("ch.qos.logback", "spring-boot-starter-web");
        made.put("org.slf4j", "spring-boot-starter-web");
        made.put("io.swagger", "springdoc-openapi-starter-webmvc-ui");
        made.put("org.springdoc", "springdoc-openapi-starter-webmvc-ui");
        made.put("picocli", "picocli");
        return made;
    }

    record Dependency(String groupId, String artifactId, String version, String scope) {}

    static Document pom() throws Exception {
        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(ROOT.resolve("pom.xml").toFile());
    }

    static List<Dependency> dependencies() throws Exception {
        Element project = pom().getDocumentElement();
        Element block = child(project, "dependencies").orElseThrow();
        List<Dependency> found = new ArrayList<>();
        NodeList items = block.getElementsByTagName("dependency");
        for (int index = 0; index < items.getLength(); index++) {
            Element item = (Element) items.item(index);
            found.add(new Dependency(
                    text(item, "groupId"), text(item, "artifactId"), text(item, "version"), text(item, "scope")));
        }
        return found;
    }

    static List<Dependency> runtime() throws Exception {
        return dependencies().stream()
                .filter(item -> !item.scope().equals("test"))
                .toList();
    }

    static List<Dependency> testOnly() throws Exception {
        return dependencies().stream()
                .filter(item -> item.scope().equals("test"))
                .toList();
    }

    /** Every third party package the shipped code imports, cut to the provider table's prefixes. */
    static Set<String> importedRoots() throws IOException {
        Set<String> roots = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = IMPORT.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    String name = matcher.group(1);
                    if (name.startsWith("java.")
                            || name.startsWith("javax.")
                            || name.startsWith("jdk.")
                            || name.startsWith("io.github.rkaushikdev.")) {
                        continue;
                    }
                    roots.add(root(name));
                }
            }
        }
        return roots;
    }

    /** The provider table prefix an import falls under, or its first two segments when none does. */
    static String root(String name) {
        return PROVIDED_BY.keySet().stream()
                .filter(prefix -> name.equals(prefix) || name.startsWith(prefix + "."))
                .max((one, two) -> Integer.compare(one.length(), two.length()))
                .orElseGet(() -> {
                    String[] parts = name.split("\\.");
                    return parts.length < 2 ? name : parts[0] + "." + parts[1];
                });
    }

    static boolean pinned(String version) {
        return !version.isEmpty()
                && !version.contains("SNAPSHOT")
                && !version.equals("LATEST")
                && !version.equals("RELEASE")
                && !version.matches(".*[\\[\\](),].*");
    }

    static String property(String version) throws Exception {
        Matcher reference = Pattern.compile("\\$\\{(.+)}").matcher(version);
        if (!reference.matches()) {
            return version;
        }
        Element properties = child(pom().getDocumentElement(), "properties").orElseThrow();
        return text(properties, reference.group(1));
    }

    static Optional<Element> child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return Optional.of(element);
            }
        }
        return Optional.empty();
    }

    static String text(Element parent, String name) {
        return child(parent, name)
                .map(element -> element.getTextContent().strip())
                .orElse("");
    }

    @Test
    void theSourceTreeIsWhereItIsExpected() {
        assertThat(SOURCE).isDirectory();
        assertThat(SOURCE.resolve("package-info.java")).isRegularFile();
    }

    @Test
    void everyImportIsADeclaredRuntimeDependency() throws Exception {
        Set<String> declared = new TreeSet<>();
        runtime().forEach(item -> declared.add(item.artifactId()));
        List<String> missing = new ArrayList<>();
        for (String root : importedRoots()) {
            String provider = PROVIDED_BY.get(root);
            if (provider == null || !declared.contains(provider)) {
                missing.add(root);
            }
        }
        assertThat(missing)
                .as("imported by src/main but not brought in by a runtime dependency")
                .isEmpty();
    }

    @Test
    void noImportIsOnlyATestDependency() throws Exception {
        List<String> leaked = importedRoots().stream()
                .filter(root ->
                        TEST_ONLY.stream().anyMatch(prefix -> root.equals(prefix) || root.startsWith(prefix + ".")))
                .toList();
        assertThat(leaked).as("src/main imports a test-only dependency").isEmpty();
    }

    /** A positive control: an empty scan would make the two checks above vacuous. */
    @Test
    void theScanActuallyFindsImports() throws Exception {
        Set<String> roots = importedRoots();
        assertThat(roots).hasSizeGreaterThanOrEqualTo(8);
        assertThat(roots).contains("org.springframework", "jakarta.persistence", "org.flywaydb", "picocli");
        assertThat(root("org.springframework.web.bind.annotation.GetMapping")).isEqualTo("org.springframework");
        assertThat(root("org.example.unknown.Thing")).isEqualTo("org.example");
        assertThat(PROVIDED_BY).doesNotContainKey("org.example");
    }

    @Test
    void everyRuntimeDependencyIsPinned() throws Exception {
        String parent = text(child(pom().getDocumentElement(), "parent").orElseThrow(), "version");
        assertThat(parent).isEqualTo("3.5.16");
        for (Dependency item : runtime()) {
            if (!item.version().isEmpty()) {
                assertThat(pinned(property(item.version())))
                        .as(item.artifactId())
                        .isTrue();
            }
        }
    }

    @Test
    void everyTestDependencyIsPinned() throws Exception {
        for (Dependency item : testOnly()) {
            if (!item.version().isEmpty()) {
                assertThat(pinned(property(item.version())))
                        .as(item.artifactId())
                        .isTrue();
            }
        }
        assertThat(testOnly()).isNotEmpty();
    }

    /** picocli is the one runtime library the Boot parent does not manage, so it carries its own pin. */
    @Test
    void picocliIsPinnedEvenThoughTheParentDoesNotManageIt() throws Exception {
        Dependency picocli = runtime().stream()
                .filter(item -> item.artifactId().equals("picocli"))
                .findFirst()
                .orElseThrow();
        assertThat(property(picocli.version())).isEqualTo("4.7.7");
    }

    @Test
    void noDependencyIsListedTwice() throws Exception {
        List<String> names = dependencies().stream()
                .map(item -> item.groupId() + ":" + item.artifactId() + ":" + item.scope())
                .toList();
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void theTestScopeDoesNotRepeatTheRuntimeList() throws Exception {
        Set<String> runtime = new TreeSet<>();
        runtime().forEach(item -> runtime.add(item.artifactId()));
        assertThat(testOnly()).noneMatch(item -> runtime.contains(item.artifactId()));
    }

    @Test
    void theLauncherPointsAtSomethingRunnable() throws Exception {
        String main = property("${main.class}");
        assertThat(main).isEqualTo("io.github.rkaushikdev.nozzlewright.cli.Main");
        Path file = ROOT.resolve("src/main/java").resolve(main.replace('.', '/') + ".java");
        assertThat(file).as("%s does not exist", main).isRegularFile();
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .containsPattern("public static void main\\(String(\\.\\.\\.|\\[]) \\w+\\)");
        assertThat(ROOT.resolve("src/main/dist/bin/nozzlewright")).isRegularFile();
    }

    @Test
    void theArtifactNameMatchesTheSourcePackage() throws Exception {
        assertThat(text(pom().getDocumentElement(), "artifactId"))
                .isEqualTo(SOURCE.getFileName().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"package-info.java"})
    void thePackageDocumentationShips(String marker) {
        assertThat(SOURCE.resolve(marker)).isRegularFile();
    }
}
