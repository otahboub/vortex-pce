package net.dcn.pce;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Build identity resolved from a Maven-filtered classpath resource.
 *
 * <p>The controller version has a single source of truth: the {@code <version>} element in
 * {@code pom.xml}. Reporting a separately maintained literal lets the reported version drift
 * from the artifact actually running, which makes an incident report unusable.
 */
public final class BuildInfo {

    private static final String RESOURCE = "/vortex-build.properties";
    private static final String UNKNOWN = "unknown";

    private static final String VERSION;
    private static final String ARTIFACT_ID;

    static {
        Properties properties = new Properties();
        try (InputStream stream = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (stream != null) {
                properties.load(stream);
            }
        } catch (IOException e) {
            // A missing or unreadable descriptor must not prevent the controller from starting;
            // the endpoints degrade to reporting "unknown".
            properties.clear();
        }
        VERSION = sanitize(properties.getProperty("version"));
        ARTIFACT_ID = sanitize(properties.getProperty("artifactId"));
    }

    private BuildInfo() {
    }

    /** Controller version as declared in {@code pom.xml}, or {@code unknown}. */
    public static String version() {
        return VERSION;
    }

    /** Maven artifact identifier, or {@code unknown}. */
    public static String artifactId() {
        return ARTIFACT_ID;
    }

    /**
     * Rejects an unfiltered placeholder. If the resource reaches the classpath without Maven
     * filtering it still contains {@code ${project.version}}, which is worse than reporting
     * nothing because it looks like a version string.
     */
    private static String sanitize(String value) {
        if (value == null || value.isBlank() || value.contains("${")) {
            return UNKNOWN;
        }
        return value.trim();
    }
}
