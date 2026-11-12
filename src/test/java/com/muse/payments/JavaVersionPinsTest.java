package com.muse.payments;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails the build when the three Java version pins disagree.
 * Surefire supplies {@code maven.java.version} from the pom property {@code java.version}.
 */
class JavaVersionPinsTest {

    private static final Pattern JAVA_MAJOR = Pattern.compile("(?m)^ARG JAVA_MAJOR=(\\S+)\\s*$");

    @Test
    void pomFileAndDockerfilePinTheSameJavaVersion() throws IOException {
        String pom = System.getProperty("maven.java.version");
        Path root = Path.of(System.getProperty("user.dir"));
        String file = Files.readString(root.resolve(".java-version"), StandardCharsets.UTF_8).trim();
        String dockerfile = Files.readString(root.resolve("Dockerfile"), StandardCharsets.UTF_8);
        Matcher matcher = JAVA_MAJOR.matcher(dockerfile);

        assertThat(pom).as("pom java.version").isNotBlank();
        assertThat(matcher.find()).as("Dockerfile ARG JAVA_MAJOR").isTrue();
        assertThat(file).as(".java-version").isEqualTo(pom);
        assertThat(matcher.group(1)).as("Dockerfile ARG JAVA_MAJOR").isEqualTo(pom);
    }
}
