package com.awardwatch.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

class DomainPackageTest {

    private static final Path SOURCES = Path.of("src/main/java/com/awardwatch/domain");

    private static final List<String> FORBIDDEN = List.of("org.springframework", "jakarta.persistence", "jakarta.validation", "com.fasterxml.jackson", "org.hibernate");

    private static List<Path> domainSources() throws IOException {
        try (Stream<Path> files = Files.list(SOURCES)) {
            return files.filter(file -> file.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void domainSourcesAreDiscoverable() throws IOException {
        assertThat(domainSources()).as("no sources found under %s - the scan below would pass vacuously", SOURCES.toAbsolutePath()).isNotEmpty();
    }

    @Test
    void domainDependsOnNoFramework() throws IOException {
        for (Path source : domainSources()) {
            String body = Files.readString(source);

            for (String forbidden : FORBIDDEN) {
                assertThat(body).as("%s must not import %s - the domain depends on nothing", source.getFileName(), forbidden).doesNotContain("import " + forbidden);
            }
        }
    }
}
