package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** BB-R-011.3: a configuration key without a row in docs/guides/config.md fails the build. */
class ConfigDocTest {

    private static final Path REPO = Path.of(System.getProperty("bigbook.repo-root"));
    private static final Pattern COMPOSE_VARIABLE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)");

    @Test
    void everyBigBookPropertyIsDocumented() throws IOException {
        Set<String> properties = new TreeSet<>();
        for (URL metadata : Collections.list(
                getClass().getClassLoader().getResources("META-INF/spring-configuration-metadata.json"))) {
            for (JsonNode property : new ObjectMapper().readTree(metadata).path("properties")) {
                if (property.path("name").asText().startsWith("bigbook.")) {
                    properties.add(property.path("name").asText());
                }
            }
        }

        assertThat(properties).as("@ConfigurationProperties metadata was generated").isNotEmpty();
        assertThat(undocumented(properties)).as("bigbook.* properties with no row in config.md").isEmpty();
    }

    @Test
    void everyComposeVariableIsDocumented() throws IOException {
        Set<String> variables = new TreeSet<>();
        try (Stream<Path> files = Files.list(REPO.resolve("deploy/compose"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
                Matcher matcher = COMPOSE_VARIABLE.matcher(Files.readString(file));
                while (matcher.find()) {
                    variables.add(matcher.group(1));
                }
            }
        }

        assertThat(variables).as("compose files were found").isNotEmpty();
        assertThat(undocumented(variables)).as("compose variables with no row in config.md").isEmpty();
    }

    private static Set<String> undocumented(Set<String> keys) throws IOException {
        String doc = Files.readString(REPO.resolve("docs/guides/config.md"));
        Set<String> missing = new TreeSet<>(keys);
        missing.removeIf(key -> doc.contains("`" + key + "`"));
        return missing;
    }
}
