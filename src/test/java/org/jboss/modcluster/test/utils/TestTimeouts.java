package org.jboss.modcluster.test.utils;

import java.time.Duration;

/**
 * Centralized timeout constants for the test suite.
 * Each value can be overridden via a system property (e.g. {@code -Dtest.timeout.boot=240})
 * to accommodate slow CI nodes without code changes.
 */
public final class TestTimeouts {

    private TestTimeouts() {
    }

    // -- Infrastructure: container startup & management API --

    /** WildFly / Undertow balancer container startup timeout. */
    public static final Duration CONTAINER_STARTUP = durationMinutes("test.timeout.container.startup.minutes", 6);

    /** Apache httpd balancer container startup timeout. */
    public static final Duration HTTPD_STARTUP = durationMinutes("test.timeout.httpd.startup.minutes", 3);

    /** Creaper boot timeout — how long to wait for server to reach "running" after reload. */
    public static final int BOOT_TIMEOUT_MS = intProp("test.timeout.boot.ms", 180_000);

    /** Creaper management API connection timeout. */
    public static final int CONNECTION_TIMEOUT_MS = intProp("test.timeout.connection.ms", 10_000);

    // -- Cluster & balancer operations --

    /** Timeout for context registration/deregistration on the balancer. */
    public static final Duration CONTEXT_OPERATION = durationSeconds("test.timeout.context", 90);

    /** Timeout for cluster formation, worker registration, and view convergence. */
    public static final Duration CLUSTER_FORMATION = durationSeconds("test.timeout.cluster", 120);

    /** Timeout for failover completion after worker kill, including Infinispan rebalancing. */
    public static final Duration FAILOVER = durationSeconds("test.timeout.failover", 120);

    // -- Helpers --

    private static Duration durationSeconds(String prop, int defaultSeconds) {
        return Duration.ofSeconds(Integer.getInteger(prop, defaultSeconds));
    }

    private static Duration durationMinutes(String prop, int defaultMinutes) {
        return Duration.ofMinutes(Integer.getInteger(prop, defaultMinutes));
    }

    private static int intProp(String prop, int defaultValue) {
        return Integer.getInteger(prop, defaultValue);
    }
}
