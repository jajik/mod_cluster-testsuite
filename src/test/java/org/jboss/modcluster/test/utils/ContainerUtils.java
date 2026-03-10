package org.jboss.modcluster.test.utils;

/**
 * Shared utility methods for Testcontainers and Docker/Podman operations.
 */
public final class ContainerUtils {

    private ContainerUtils() {
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
                    || message.contains("Socket closed")
                    || message.contains("Waiting for server timed out")
                    || message.contains("Timeout reconnecting"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
