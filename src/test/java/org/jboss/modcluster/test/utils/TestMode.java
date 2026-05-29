package org.jboss.modcluster.test.utils;

/**
 * Determines how the test infrastructure provisions WildFly workers and load balancers.
 *
 * <ul>
 *   <li>{@link #DOCKER} — each worker/balancer runs inside its own Docker/Podman
 *       container, managed by Testcontainers. Networking uses a private Docker
 *       network with DNS aliases (e.g. {@code worker1}, {@code balancer}).</li>
 *   <li>{@link #NATIVE} — each worker/balancer runs as a local OS process started
 *       via {@link ProcessBuilder}. Networking uses {@code localhost} with static
 *       port offsets ({@link NativePortAllocator}).</li>
 * </ul>
 *
 * <p>The mode is selected by the {@code test.mode} system property (case-insensitive).
 * If not set, defaults to {@link #DOCKER}.
 *
 * <p>Usage:
 * <pre>{@code
 *   // In Maven: -Dtest.mode=native
 *   // In code:
 *   if (TestMode.current() == TestMode.NATIVE) { ... }
 * }</pre>
 */
public enum TestMode {

    /** Run workers and balancers inside Docker/Podman containers via Testcontainers. */
    DOCKER,

    /** Run workers and balancers as local OS processes (no container runtime required). */
    NATIVE;

    private static final String SYSTEM_PROPERTY = "test.mode";
    private static final TestMode DEFAULT = DOCKER;

    /**
     * Resolve the current test mode from the {@code test.mode} system property.
     *
     * @return the configured {@link TestMode}, or {@link #DOCKER} if the property is absent
     * @throws IllegalArgumentException if the property value is not a valid mode name
     */
    public static TestMode current() {
        String value = System.getProperty(SYSTEM_PROPERTY);
        if (value == null || value.isEmpty()) {
            return DEFAULT;
        }
        return valueOf(value.toUpperCase());
    }

    /**
     * Check whether the current mode is {@link #DOCKER}.
     *
     * @return {@code true} if running in Docker/container mode
     */
    public boolean isDocker() {
        return this == DOCKER;
    }

    /**
     * Check whether the current mode is {@link #NATIVE}.
     *
     * @return {@code true} if running in native/process mode
     */
    public boolean isNative() {
        return this == NATIVE;
    }

    /** Whether the host OS is Windows. */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
