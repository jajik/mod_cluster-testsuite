package org.jboss.modcluster.test.utils;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Static port allocation for native (non-Docker) test mode.
 *
 * <p>In Docker mode, each container has its own network namespace, so all workers
 * use identical ports (8080, 9990, 7600, etc.) and are distinguished by hostname.
 * In native mode, all processes share the host network namespace, so each worker
 * must use unique ports.
 *
 * <p>WildFly's {@code -Djboss.socket.binding.port-offset=N} shifts every
 * socket-binding port by {@code N}. This class assigns a fixed offset per worker name:
 *
 * <table>
 *   <caption>Port offset assignments</caption>
 *   <tr><th>Instance</th><th>Offset</th><th>HTTP</th><th>HTTPS</th><th>Management</th><th>JGroups TCP</th><th>JGroups FD</th></tr>
 *   <tr><td>balancer</td><td>0</td><td>8080</td><td>8443</td><td>9990</td><td>—</td><td>—</td></tr>
 *   <tr><td>worker1</td><td>100</td><td>8180</td><td>8543</td><td>10090</td><td>7700</td><td>57700</td></tr>
 *   <tr><td>worker2</td><td>200</td><td>8280</td><td>8643</td><td>10190</td><td>7800</td><td>57800</td></tr>
 *   <tr><td>worker3</td><td>300</td><td>8380</td><td>8743</td><td>10290</td><td>7900</td><td>57900</td></tr>
 *   <tr><td>worker4</td><td>400</td><td>8480</td><td>8843</td><td>10390</td><td>8000</td><td>58000</td></tr>
 * </table>
 *
 * <p>Offsets are static (not dynamic) because JGroups TCPPING requires
 * {@code initial_hosts} to be configured before the server starts — dynamic
 * port discovery is not feasible with TCPPING.
 *
 * @see TestMode
 */
public final class NativePortAllocator {

    /** Base HTTP port before offset. */
    private static final int BASE_HTTP = 8080;

    /** Base HTTPS port before offset. */
    private static final int BASE_HTTPS = 8443;

    /** Base management port before offset. */
    private static final int BASE_MANAGEMENT = 9990;

    /** Base JGroups TCP port before offset. */
    private static final int BASE_JGROUPS_TCP = 7600;

    /** Base JGroups failure-detection (FD_SOCK2) port before offset. */
    private static final int BASE_JGROUPS_FD = 57600;

    /** MCMP port for httpd — no offset needed since httpd is a single process. */
    public static final int HTTPD_MCMP_PORT = 8090;

    /** Maximum number of workers supported. */
    private static final int MAX_WORKERS = 4;

    /** Port offset step between consecutive workers. */
    private static final int OFFSET_STEP = 100;

    private static final Map<String, Integer> OFFSETS = Map.of(
            "balancer", 0,
            "worker1", 100,
            "worker2", 200,
            "worker3", 300,
            "worker4", 400
    );

    private NativePortAllocator() {
    }

    /**
     * Get the port offset for a named instance.
     *
     * @param name instance name (e.g. "worker1", "balancer")
     * @return the port offset value to pass to {@code -Djboss.socket.binding.port-offset}
     * @throws IllegalArgumentException if the name is not a known instance
     */
    public static int offset(String name) {
        Integer offset = OFFSETS.get(name);
        if (offset == null) {
            throw new IllegalArgumentException("Unknown instance name: '" + name
                    + "'. Known instances: " + OFFSETS.keySet());
        }
        return offset;
    }

    /**
     * Get the HTTP port for a named instance.
     *
     * @param name instance name (e.g. "worker1")
     * @return the HTTP port (base 8080 + offset)
     */
    public static int httpPort(String name) {
        return BASE_HTTP + offset(name);
    }

    /**
     * Get the HTTPS port for a named instance.
     *
     * @param name instance name (e.g. "worker1")
     * @return the HTTPS port (base 8443 + offset)
     */
    public static int httpsPort(String name) {
        return BASE_HTTPS + offset(name);
    }

    /**
     * Get the management port for a named instance.
     *
     * @param name instance name (e.g. "worker1")
     * @return the management port (base 9990 + offset)
     */
    public static int managementPort(String name) {
        return BASE_MANAGEMENT + offset(name);
    }

    /**
     * Get the JGroups TCP port for a named instance.
     *
     * @param name instance name (e.g. "worker1")
     * @return the JGroups TCP port (base 7600 + offset)
     */
    public static int jgroupsTcpPort(String name) {
        return BASE_JGROUPS_TCP + offset(name);
    }

    /**
     * Get the JGroups failure-detection (FD_SOCK2) port for a named instance.
     *
     * @param name instance name (e.g. "worker1")
     * @return the JGroups FD port (base 57600 + offset)
     */
    public static int jgroupsFdPort(String name) {
        return BASE_JGROUPS_FD + offset(name);
    }

    /**
     * Build the TCPPING {@code initial_hosts} string for native mode.
     *
     * <p>All workers run on {@code localhost}, so the hosts string uses
     * offset ports to distinguish them. Example for 4 workers:
     * {@code "localhost[7700],localhost[7800],localhost[7900],localhost[8000]"}
     *
     * <p>In Docker mode, the equivalent would be
     * {@code "worker1[7600],worker2[7600],..."} (same port, different hostnames).
     *
     * @return comma-separated TCPPING initial_hosts value
     */
    public static String tcppingInitialHosts() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= MAX_WORKERS; i++) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append("localhost[").append(jgroupsTcpPort("worker" + i)).append("]");
        }
        return sb.toString();
    }
}
