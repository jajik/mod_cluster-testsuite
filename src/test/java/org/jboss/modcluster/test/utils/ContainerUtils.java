package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

/**
 * Shared utility methods for Testcontainers and Docker/Podman operations.
 */
public final class ContainerUtils {

    private static final Logger log = LoggerFactory.getLogger(ContainerUtils.class);

    public static final String DEFAULT_JAVA_OPTS = "-Xms64m -Xmx512m";

    private ContainerUtils() {
    }

    /**
     * Functional interface for container startup actions that may throw exceptions.
     */
    @FunctionalInterface
    public interface ContainerStarter {
        void start() throws Exception;
    }

    /**
     * Checks if an exception represents a transient Docker/Podman socket error
     * or a transient server startup error.
     * Traverses the entire exception cause chain looking for SIGPIPE, broken pipe,
     * connection reset, socket closed errors (including Czech locale variants),
     * and Creaper reload/boot timeouts.
     *
     * @param throwable exception to check
     * @return true if this is a transient error that may succeed on retry
     */
    public static boolean isTransientDockerError(final Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            // TimeoutException from Creaper reload/waitUntilRunning — server was slow to boot
            if (current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            final String message = current.getMessage();
            if (message != null && (message.contains("SIGPIPE")
                    || message.contains("Broken pipe")
                    || message.contains("Connection reset")
                    || message.contains("Connection refused")
                    || message.contains("Socket closed")
                    || message.contains("Waiting for server timed out")
                    || message.contains("Timeout reconnecting")
                    || message.contains("rolled back"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Get WildFly ZIP path from system property or environment variable.
     * Priority:
     * 1. System property: wildfly.zip.path
     * 2. Environment variable: WILDFLY_ZIP_PATH
     * 3. Convention: distributions/wildfly-*.zip or distributions/jboss-eap-*.zip
     */
    public static Path getWildFlyZipPath() {
        String zipPath = System.getProperty("wildfly.zip.path");
        if (zipPath != null) {
            return Paths.get(zipPath);
        }

        zipPath = System.getenv("WILDFLY_ZIP_PATH");
        if (zipPath != null) {
            return Paths.get(zipPath);
        }

        File distDir = new File("distributions");
        if (distDir.exists() && distDir.isDirectory()) {
            File[] zips = distDir.listFiles((dir, name) ->
                name.startsWith("wildfly-") && name.endsWith(".zip") ||
                name.startsWith("jboss-eap-") && name.endsWith(".zip"));

            if (zips != null && zips.length > 0) {
                return zips[0].toPath();
            }
        }

        return null;
    }

    /**
     * Determine required Java version based on WildFly/EAP version.
     * Can be overridden via system property: -Dcontainer.java.version=17 or -Dcontainer.java.version=11
     *
     * Auto-detection rules:
     * - WildFly 31+ requires Java 17
     * - WildFly 30 and earlier requires Java 11
     * - EAP 8+ requires Java 17
     * - EAP 7.x requires Java 11
     */
    public static String getRequiredJavaVersion(String zipFileName) {
        String javaVersionOverride = System.getProperty("container.java.version");
        if (javaVersionOverride != null && !javaVersionOverride.trim().isEmpty()) {
            String javaImage = "openjdk-" + javaVersionOverride;
            log.info("Using Java version from system property: {} ({})", javaVersionOverride, javaImage);
            return javaImage;
        }

        if (zipFileName.startsWith("wildfly-")) {
            String versionPart = zipFileName.substring(8);
            String majorVersion = versionPart.split("\\.")[0];

            try {
                int major = Integer.parseInt(majorVersion);
                String javaVersion = major >= 31 ? "openjdk-17" : "openjdk-11";
                log.info("WildFly {} requires {}", major, javaVersion);
                return javaVersion;
            } catch (NumberFormatException e) {
                log.warn("Could not parse WildFly version from: {}, defaulting to Java 17", zipFileName);
                return "openjdk-17";
            }
        } else if (zipFileName.startsWith("jboss-eap-")) {
            String versionPart = zipFileName.substring(10);
            String majorVersion = versionPart.split("\\.")[0];

            try {
                int major = Integer.parseInt(majorVersion);
                String javaVersion = major >= 8 ? "openjdk-17" : "openjdk-11";
                log.info("EAP {} requires {}", major, javaVersion);
                return javaVersion;
            } catch (NumberFormatException e) {
                log.warn("Could not parse EAP version from: {}, defaulting to Java 17", zipFileName);
                return "openjdk-17";
            }
        }

        log.warn("Unknown distribution format: {}, defaulting to Java 17", zipFileName);
        return "openjdk-17";
    }

    /**
     * Get the root cause message from an exception chain.
     *
     * @param throwable Exception to traverse
     * @return Root cause message or top-level message if no cause
     */
    public static String getRootCauseMessage(Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        return rootCause.getMessage() != null ? rootCause.getMessage() : rootCause.toString();
    }

    /**
     * Start a container with retry logic for transient Docker/Podman errors.
     * Retries up to 5 times with exponential backoff and jitter.
     *
     * @param action the startup action (container creation + start + post-start config)
     * @param cleanup cleanup to run between retry attempts (close container, null references)
     * @param entityName human-readable name for log messages (e.g., "WildFly worker 'worker1'")
     */
    public static void startWithRetry(ContainerStarter action, Runnable cleanup, String entityName) {
        final int maxRetries = 5;
        Exception lastException = null;
        final Random random = new Random();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                action.start();
                return;
            } catch (Exception e) {
                lastException = e;

                if (isTransientDockerError(e) && attempt < maxRetries) {
                    final long baseDelay = attempt * 500L;
                    final long jitter = 100 + random.nextInt(200);
                    final long delayMs = baseDelay + jitter;

                    log.warn("{} start failed with transient error on attempt {}/{}, retrying after {}ms",
                             entityName, attempt, maxRetries, delayMs);
                    log.debug("Error details: {}", getRootCauseMessage(e));

                    cleanup.run();

                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry backoff", ie);
                    }
                } else {
                    break;
                }
            }
        }

        throw new RuntimeException("Failed to start " + entityName + " after " + maxRetries + " attempts", lastException);
    }
}
