package org.jboss.modcluster.test.utils;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Random;

/**
 * Shared utility methods for Testcontainers and Docker/Podman operations.
 */
public final class ContainerUtils {

    private static final Logger log = LoggerFactory.getLogger(ContainerUtils.class);

    static final String CONTAINER_JAVA_HOME_PATH = "/opt/java";

    private ContainerUtils() {
    }

    /** Set JAVA_HOME on the container if {@code container.java.home} system property is configured. */
    public static void applyJavaHomeIfNeeded(GenericContainer<?> container) {
        String javaHome = System.getProperty("container.java.home");
        if (javaHome != null && !javaHome.isEmpty()) {
            container.withEnv("JAVA_HOME", CONTAINER_JAVA_HOME_PATH);
        }
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
                (name.startsWith("wildfly-") && name.endsWith(".zip")) ||
                (name.startsWith("jboss-eap-") && name.endsWith(".zip")));

            if (zips != null && zips.length > 0) {
                return zips[0].toPath();
            }
        }

        return null;
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

    /**
     * Retry a Docker cleanup operation, silently absorbing errors.
     * Cleanup must never throw — a failed cleanup should not mask the original test failure.
     *
     * @param action     the cleanup action to run
     * @param label      human-readable label for log messages (e.g., "disconnect worker1")
     * @param maxRetries maximum number of attempts
     */
    public static void retryOnTransientError(Runnable action, String label, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                action.run();
                return;
            } catch (NotFoundException e) {
                // Resource already gone — success
                return;
            } catch (Exception e) {
                if (isTransientDockerError(e) && attempt < maxRetries) {
                    log.debug("{} failed with transient error (attempt {}/{}), retrying: {}",
                            label, attempt, maxRetries, e.getMessage());
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    log.debug("{} failed: {}", label, e.getMessage());
                    return;
                }
            }
        }
    }

    /**
     * Execute a command inside a container with retry on transient Podman SIGPIPE errors.
     *
     * @param container the container to execute in
     * @param command   the command and arguments
     * @return the execution result
     * @throws Exception if the command fails after all retry attempts
     */
    public static Container.ExecResult execInContainerWithRetry(final GenericContainer<?> container,
                                                                final String... command) throws Exception {
        final int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return container.execInContainer(command);
            } catch (Exception e) {
                if (isTransientDockerError(e) && attempt < maxAttempts) {
                    log.warn("Transient error in execInContainer (attempt {}/{}): {}",
                            attempt, maxAttempts, e.getMessage());
                    Thread.sleep(500L * attempt);
                } else {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    /**
     * Copy a file into a container with retry on transient Podman SIGPIPE errors.
     *
     * @param container     the container to copy into
     * @param hostPath      path to the file on the host
     * @param containerPath destination path inside the container
     * @throws Exception if the copy fails after all retry attempts
     */
    public static void copyFileToContainerWithRetry(final GenericContainer<?> container,
                                                    final Path hostPath,
                                                    final String containerPath) throws Exception {
        final int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                container.copyFileToContainer(MountableFile.forHostPath(hostPath), containerPath);
                return;
            } catch (Exception e) {
                if (isTransientDockerError(e) && attempt < maxAttempts) {
                    log.warn("Transient error in copyFileToContainer (attempt {}/{}): {}",
                            attempt, maxAttempts, e.getMessage());
                    Thread.sleep(500L * attempt);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Retry an operation that must succeed, throwing on final failure.
     *
     * @param action     the action to run
     * @param label      human-readable label for log messages
     * @param maxRetries maximum number of attempts
     */
    public static void retryOrThrow(Runnable action, String label, int maxRetries) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                action.run();
                return;
            } catch (Exception e) {
                lastException = e;
                if (isTransientDockerError(e) && attempt < maxRetries) {
                    log.warn("{} failed with transient error (attempt {}/{}), retrying: {}",
                            label, attempt, maxRetries, e.getMessage());
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry for " + label, ie);
                    }
                } else {
                    break;
                }
            }
        }
        throw new RuntimeException("Failed: " + label + " after " + maxRetries + " attempts", lastException);
    }

    /**
     * Force-disconnect and force-remove all containers from a Docker/Podman network.
     * Used as a safety net before closing a network to ensure {@code network.close()} succeeds.
     *
     * @param dockerClient Docker client to use for API calls
     * @param networkId    ID of the network to clean
     * @return number of containers processed
     */
    public static int disconnectAllFromNetwork(DockerClient dockerClient, String networkId) {
        Map<String, com.github.dockerjava.api.model.Network.ContainerNetworkConfig> containers;
        try {
            com.github.dockerjava.api.model.Network networkInfo =
                    dockerClient.inspectNetworkCmd().withNetworkId(networkId).exec();
            containers = networkInfo.getContainers();
        } catch (NotFoundException e) {
            return 0;
        } catch (Exception e) {
            log.debug("Failed to inspect network {}: {}", networkId, e.getMessage());
            return 0;
        }

        if (containers == null || containers.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (String containerId : containers.keySet()) {
            retryOnTransientError(() ->
                    dockerClient.disconnectFromNetworkCmd()
                            .withContainerId(containerId)
                            .withNetworkId(networkId)
                            .withForce(true)
                            .exec(),
                    "disconnect container " + containerId.substring(0, 12), 3);

            retryOnTransientError(() ->
                    dockerClient.removeContainerCmd(containerId)
                            .withForce(true)
                            .exec(),
                    "remove container " + containerId.substring(0, 12), 3);
            count++;
        }

        log.debug("Cleaned {} containers from network {}", count, networkId);
        return count;
    }
}
