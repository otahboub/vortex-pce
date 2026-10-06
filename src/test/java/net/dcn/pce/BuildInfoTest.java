package net.dcn.pce;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BuildInfoTest {

    @Test
    void versionIsResolvedFromTheFilteredBuildDescriptor() {
        assertNotEquals("unknown", BuildInfo.version(),
                "resource filtering should supply the project version at build time");
        assertFalse(BuildInfo.version().contains("${"),
                "an unfiltered placeholder must never be reported as a version");
        assertEquals("dcn-pce-controller", BuildInfo.artifactId());
    }

    @Test
    void reportedVersionMatchesTheProjectVersionDeclaredInPom() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        Matcher matcher = Pattern.compile(
                        "<artifactId>dcn-pce-controller</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(pom);

        assertEquals(true, matcher.find(), "pom.xml should declare the project version");
        assertEquals(matcher.group(1).trim(), BuildInfo.version(),
                "the runtime must report the version declared in pom.xml, not a separate literal");
    }
}
