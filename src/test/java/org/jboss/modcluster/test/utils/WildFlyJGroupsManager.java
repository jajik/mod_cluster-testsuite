package org.jboss.modcluster.test.utils;

import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.ReadResourceOption;
import org.wildfly.extras.creaper.core.online.operations.Values;

import org.awaitility.core.ConditionTimeoutException;
import org.testcontainers.containers.Container.ExecResult;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Manages JGroups subsystem configuration for WildFly containers.
 * Handles switching from UDP multicast to TCP unicast discovery,
 * which is required for container-based environments where multicast
 * does not work (Docker/Podman networks).
 */
public class WildFlyJGroupsManager {

    private static final Logger log = LoggerFactory.getLogger(WildFlyJGroupsManager.class);

    private final WildFlyContainer container;

    WildFlyJGroupsManager(final WildFlyContainer container) {
        this.container = container;
    }

    /**
     * Configure JGroups to use TCP transport with TCPPING discovery.
     * Required because UDP multicast discovery does not work in Docker/Podman networks.
     * Workers discover each other using container network aliases (worker1, worker2, etc.).
     * Changes are persistent and take effect after reload.
     *
     * <h3>Why TCP/TCPPING instead of UDP multicast</h3>
     * <p>The default {@code standalone-ha.xml} UDP stack uses MPING (multicast discovery) which
     * requires UDP multicast between containers. Docker bridge networks historically don't forward
     * multicast. Podman with netavark bridge networks may support it, but this is not guaranteed
     * across all environments (CI, different Podman versions, Docker). TCPPING with static member
     * lists is deterministic and works in all container networking configurations.</p>
     *
     * <h3>Criteria for switching back to UDP multicast</h3>
     * <ul>
     *   <li>Verify UDP multicast works on the target Podman/Docker bridge network
     *       (pasta networking with netavark may support it)</li>
     *   <li>Verify multicast works on CI environment (not just local)</li>
     *   <li>If multicast works: remove this method entirely, keep default UDP stack,
     *       only set {@code external_addr} on UDP transport — FD_SOCK, FD_ALL, MPING
     *       all work out of the box</li>
     *   <li>Test with {@code standalone-ha.xml} default UDP stack +
     *       {@code -Djboss.default.multicast.address=<address>} to verify cluster formation</li>
     * </ul>
     */
    public void configureTcpDiscovery() throws Exception {
        Operations ops = container.getOperations();

        // Switch JGroups channel from UDP to TCP stack
        Address channelAddress = Address.subsystem("jgroups").and("channel", "ee");
        ops.writeAttribute(channelAddress, "stack", "tcp").assertSuccess();
        ops.writeAttribute(channelAddress, "statistics-enabled", true).assertSuccess();

        // Add TCPPING at position 0 (top of stack) with container network aliases.
        // add-index=0 is critical: discovery protocols must be at the top of the
        // JGroups protocol stack. Without it, TCPPING is appended at the end and
        // cluster discovery fails, breaking Infinispan and distributable sessions.
        // This MUST happen before removing MPING and before any optional tuning
        // (FD_SOCK2, FD_ALL3, GMS) — if those steps fail, the stack still has a
        // discovery protocol and JGroups can form a channel.
        Address tcppingAddress = Address.subsystem("jgroups")
            .and("stack", "tcp")
            .and("protocol", "TCPPING");

        if (!ops.exists(tcppingAddress)) {
            ModelNode properties = new ModelNode();
            properties.get("initial_hosts").set(
                "worker1[7600],worker2[7600],worker3[7600],worker4[7600]");
            properties.get("port_range").set("0");

            ops.add(tcppingAddress, Values.of("add-index", 0)
                .and("properties", properties)).assertSuccess();
        }

        // Now safe to remove MPING — TCPPING is already in the stack.
        // Remove MPING (multicast-based discovery, unusable in containers).
        // In WildFly 31+, MPING is a socket-discovery-protocol, not a regular protocol.
        // Try both resource types — one will match, the other is a no-op.
        ops.removeIfExists(Address.subsystem("jgroups")
            .and("stack", "tcp")
            .and("socket-discovery-protocol", "MPING"));
        ops.removeIfExists(Address.subsystem("jgroups")
            .and("stack", "tcp")
            .and("protocol", "MPING"));

        // Configure TCP transport for container networking.
        // When JGroups binds to 0.0.0.0, it auto-detects a physical address via
        // InetAddress.getLocalHost(). In Podman rootless containers this often resolves
        // to 127.0.0.1 or a wrong interface, making the node unreachable by peers.
        // Setting external_addr forces JGroups to publish the Docker/Podman
        // DNS-resolvable hostname instead, and increasing sock_conn_timeout handles
        // the extra latency in Podman rootless networking (slirp4netns/pasta).
        Address tcpTransport = Address.subsystem("jgroups")
            .and("stack", "tcp")
            .and("transport", "TCP");
        ops.invoke("map-put", tcpTransport,
            Values.of("name", "properties")
                .and("key", "external_addr")
                .and("value", container.getName())).assertSuccess();
        ops.invoke("map-put", tcpTransport,
            Values.of("name", "properties")
                .and("key", "sock_conn_timeout")
                .and("value", "10000")).assertSuccess();
        log.info("JGroups TCP transport configured: external_addr='{}', sock_conn_timeout=10000 on worker '{}'",
            container.getName(), container.getName());

        // Configure FD_SOCK2 external_addr so the socket-based failure detector
        // publishes a reachable address. Without this, FD_SOCK2 publishes 127.0.0.1
        // or a wrong interface address, and peers cannot connect to verify liveness.
        // This forces fallback to FD_ALL3 heartbeat detection (~42s delay), causing
        // Infinispan timeouts and HTTP 500 errors during failover.
        // EAP 8.1.4 (WildFly Core 27) models FD_SOCK2 as a regular protocol.
        // Not all WildFly versions have FD_SOCK2 — skip if absent.
        Address fdSock2Address = Address.subsystem("jgroups")
            .and("stack", "tcp")
            .and("protocol", "FD_SOCK2");
        if (ops.exists(fdSock2Address)) {
            ops.invoke("map-put", fdSock2Address,
                Values.of("name", "properties")
                    .and("key", "external_addr")
                    .and("value", container.getName())).assertSuccess();
            log.info("FD_SOCK2 external_addr='{}' configured on worker '{}'",
                container.getName(), container.getName());
        } else {
            log.debug("FD_SOCK2 not in TCP stack on '{}' — expected on WildFly 40+ (WFLY-20710 removed it); " +
                "failure detection relies on TCP transport built-in detection + FD_ALL3",
                container.getName());
        }

        // Tune FD_ALL3 to avoid false suspicions in CI Podman networking.
        // WildFly 40+ removed FD_SOCK2 (WFLY-20710), so FD_ALL3 is the primary fallback
        // for nodes without direct TCP connections to the crashed member. The TCP transport's
        // built-in failure detection only works on established connections (coordinator sees
        // crashes in ~1s), but other nodes rely on FD_ALL3 heartbeats.
        // CI Podman networking shows heartbeat gaps of 8-33 seconds due to network latency
        // and packet loss (builds #106-#107). With 10s timeout, worker1's FD_ALL3 had already
        // accumulated ~8s of missed heartbeats from worker3 *before* the view change even
        // started, leaving only 2s headroom — triggering a false suspicion that caused
        // split-brain. 30s timeout with 5s interval gives 6 heartbeat windows and tolerates
        // the extreme latency seen on CI. This test doesn't need fast failure detection —
        // it needs reliable detection without false positives.
        Address fdAll3Address = Address.subsystem("jgroups")
            .and("stack", "tcp")
            .and("protocol", "FD_ALL3");
        if (ops.exists(fdAll3Address)) {
            ops.invoke("map-put", fdAll3Address,
                Values.of("name", "properties")
                    .and("key", "timeout")
                    .and("value", "30000")).assertSuccess();
            ops.invoke("map-put", fdAll3Address,
                Values.of("name", "properties")
                    .and("key", "interval")
                    .and("value", "5000")).assertSuccess();
            log.info("FD_ALL3 tuned: timeout=30000, interval=5000 on worker '{}'", container.getName());
        }

        // Increase GMS join_timeout from default 2s to 10s.
        // In Podman rootless, TCP connections between containers may take several
        // seconds due to SYN retransmits through slirp4netns/pasta networking.
        Address gmsAddress = Address.subsystem("jgroups")
            .and("stack", "tcp")
            .and("protocol", "pbcast.GMS");
        ops.invoke("map-put", gmsAddress,
            Values.of("name", "properties")
                .and("key", "join_timeout")
                .and("value", "10000")).assertSuccess();

        log.info("JGroups TCP clustering configured on worker '{}'", container.getName());
    }

    /**
     * Read the raw JGroups view string from the {@code ee} channel.
     * The view string format is: {@code [coordinator|view-id] (member-count) [member1, member2, ...]}.
     *
     * @return the view string, or {@code null} if the view cannot be read
     */
    private String readViewString() {
        try {
            Operations ops = container.getOperations();
            Address channelAddr = Address.subsystem("jgroups").and("channel", "ee");
            ModelNodeResult result = ops.readResource(channelAddr, ReadResourceOption.INCLUDE_RUNTIME);

            if (!result.isSuccess()) {
                log.warn("JGroups channel resource not available on '{}'. " +
                    "Raw DMR response: {}", container.getName(), result.toString());
                return null;
            }

            ModelNode resource = result.value();
            if (!resource.hasDefined("view")) {
                log.warn("JGroups view not defined on '{}' (channel not started yet). " +
                    "Resource: {}", container.getName(), resource.toJSONString(false));
                return null;
            }

            return resource.get("view").asString();
        } catch (Exception e) {
            log.warn("Error reading JGroups view on '{}': {}", container.getName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Get the number of members in the JGroups cluster view.
     *
     * @return number of cluster members, or 1 if view cannot be read/parsed
     */
    public int getClusterViewSize() {
        String view = readViewString();
        if (view == null) {
            return 1;
        }

        Matcher matcher = Pattern.compile("\\((\\d+)\\)").matcher(view);
        if (matcher.find()) {
            int size = Integer.parseInt(matcher.group(1));
            log.debug("JGroups cluster view on '{}': {} (size={})", container.getName(), view, size);
            return size;
        }

        log.warn("Could not parse JGroups view on '{}'. View string: '{}'. " +
            "Expected format: '[coordinator|view-id] (member-count) [member1, ...]'",
            container.getName(), view);
        return 1;
    }

    /**
     * Get the member names in the JGroups cluster view.
     * Parses the view string to extract member names (without version suffixes).
     * For example, from {@code [worker1(v=16.0.5)|2] (2) [worker1(v=16.0.5), worker3(v=16.0.5)]}
     * returns {@code {"worker1", "worker3"}}.
     *
     * @return set of member names, or empty set if view cannot be read/parsed
     */
    public Set<String> getClusterViewMembers() {
        String view = readViewString();
        if (view == null) {
            return Collections.emptySet();
        }

        // Extract the member name from each "name(v=...)" entry in the view string
        Set<String> members = new HashSet<>();
        Matcher memberMatcher = Pattern.compile("(\\w+)\\(v=").matcher(view);
        while (memberMatcher.find()) {
            members.add(memberMatcher.group(1));
        }

        if (members.isEmpty()) {
            log.warn("Could not parse member names from JGroups view on '{}'. View string: '{}'",
                container.getName(), view);
        } else {
            log.debug("JGroups cluster members on '{}': {}", container.getName(), members);
        }

        return members;
    }

    /**
     * Wait until the JGroups cluster has at least the expected number of members.
     * Polls the cluster view until the expected membership count is reached or timeout expires.
     * On timeout, logs network diagnostics to help debug connectivity issues.
     *
     * @param expectedMembers minimum number of expected cluster members
     * @param timeout maximum time to wait
     */
    public void waitForClusterFormation(int expectedMembers, Duration timeout) {
        log.info("Waiting for JGroups cluster to form with {} members on '{}'...", expectedMembers, container.getName());
        try {
            await().atMost(timeout)
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    int size = getClusterViewSize();
                    assertThat(size)
                        .as("JGroups cluster on '%s' should have at least %d members (current: %d)",
                            container.getName(), expectedMembers, size)
                        .isGreaterThanOrEqualTo(expectedMembers);
                });
            log.info("JGroups cluster formed with {} members on '{}'", expectedMembers, container.getName());
        } catch (ConditionTimeoutException e) {
            logNetworkDiagnostics();
            throw e;
        }
    }

    /**
     * Wait until all provided workers' JGroups views converge to exactly the expected membership.
     * Checks that every worker reports the expected view size and that none of the excluded
     * members appear in any view. This is stronger than checking a single worker's view,
     * which can pass while other workers are still processing the view change.
     *
     * @param managers        JGroups managers for all workers that should converge
     * @param expectedMembers exact number of expected cluster members
     * @param excludedMembers member names that must NOT appear in any view (e.g. killed workers)
     * @param timeout         maximum time to wait
     */
    public static void waitForClusterViewConvergence(List<WildFlyJGroupsManager> managers,
                                                     int expectedMembers,
                                                     Set<String> excludedMembers,
                                                     Duration timeout) {
        log.info("Waiting for cluster view convergence: {} members, excluded {} across {} workers...",
            expectedMembers, excludedMembers, managers.size());
        try {
            await().atMost(timeout)
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    for (WildFlyJGroupsManager mgr : managers) {
                        Set<String> members = mgr.getClusterViewMembers();
                        assertThat(members)
                            .as("JGroups view on '%s' should have exactly %d members (current: %s)",
                                mgr.container.getName(), expectedMembers, members)
                            .hasSize(expectedMembers);
                        for (String excluded : excludedMembers) {
                            assertThat(members)
                                .as("JGroups view on '%s' should not contain killed member '%s' (current: %s)",
                                    mgr.container.getName(), excluded, members)
                                .doesNotContain(excluded);
                        }
                    }
                });
            log.info("Cluster view converged: {} members on all {} workers",
                expectedMembers, managers.size());
        } catch (ConditionTimeoutException e) {
            for (WildFlyJGroupsManager mgr : managers) {
                log.warn("View on '{}' at timeout: {}", mgr.container.getName(), mgr.getClusterViewMembers());
                mgr.logNetworkDiagnostics();
            }
            throw e;
        }
    }

    /**
     * Log network diagnostics from this container to help debug cluster formation failures.
     * Tests DNS resolution and TCP connectivity to all worker hostnames.
     */
    private void logNetworkDiagnostics() {
        log.warn("=== Network diagnostics from '{}' (cluster formation failed) ===", container.getName());
        try {
            // Show /etc/hosts to check hostname resolution
            ExecResult hostsResult = container.getContainer().execInContainer("cat", "/etc/hosts");
            log.warn("/etc/hosts on '{}':\n{}", container.getName(), hostsResult.getStdout().trim());

            // Test DNS and TCP connectivity to each worker
            String[] workers = {"worker1", "worker2", "worker3", "worker4"};
            for (String worker : workers) {
                if (worker.equals(container.getName())) continue;

                ExecResult dnsResult = container.getContainer().execInContainer("getent", "hosts", worker);
                log.warn("DNS '{}' from '{}': exit={} result='{}'",
                    worker, container.getName(), dnsResult.getExitCode(), dnsResult.getStdout().trim());

                if (dnsResult.getExitCode() == 0) {
                    ExecResult tcpResult = container.getContainer().execInContainer("bash", "-c",
                        "timeout 3 bash -c 'echo > /dev/tcp/" + worker + "/7600' 2>&1 && echo 'TCP_OK' || echo 'TCP_FAIL'");
                    log.warn("TCP {}:7600 from '{}': {}",
                        worker, container.getName(), tcpResult.getStdout().trim());
                }
            }

            // Show network interfaces
            ExecResult ipResult = container.getContainer().execInContainer("ip", "addr", "show");
            log.warn("Network interfaces on '{}':\n{}", container.getName(), ipResult.getStdout().trim());
        } catch (Exception ex) {
            log.warn("Failed to collect diagnostics from '{}': {}", container.getName(), ex.getMessage());
        }
    }
}
