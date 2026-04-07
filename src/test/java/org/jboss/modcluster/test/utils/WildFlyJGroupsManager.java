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

        // Configure FD_SOCK2 for socket-based failure detection.
        // FD_SOCK2 detects member failure almost instantly when the TCP socket is closed,
        // unlike FD_ALL3 which relies on heartbeat timeouts (hard to tune for CI Podman
        // networking where heartbeat gaps of 8-33s cause false suspicions or slow detection).
        // WildFly 40+ / EAP 8.2+ removed FD_SOCK2 from the default TCP stack (WFLY-20710),
        // so we re-add it. The external_addr must be set to the container hostname so peers
        // can connect to the FD_SOCK2 server socket; without it, FD_SOCK2 publishes 127.0.0.1.
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
            // Re-add FD_SOCK2 at index 2: after TCPPING(0) and MERGE3(1), before FD_ALL3(2→3).
            // This matches the standard JGroups TCP stack protocol ordering.
            ModelNode fdSock2Props = new ModelNode();
            fdSock2Props.get("external_addr").set(container.getName());

            ops.add(fdSock2Address, Values.of("add-index", 2)
                .and("properties", fdSock2Props)).assertSuccess();
            log.info("FD_SOCK2 re-added to TCP stack with external_addr='{}' on worker '{}' " +
                "(WFLY-20710 removed it; needed for reliable failure detection in containers)",
                container.getName(), container.getName());
        }

        // Tune FD_ALL3 as a backup failure detector.
        // With FD_SOCK2 handling primary failure detection (instant socket-close detection),
        // FD_ALL3 serves as a backstop for hung nodes (alive but unresponsive). Keep generous
        // timeouts to avoid false suspicions from CI Podman networking latency.
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

        // Reduce VERIFY_SUSPECT timeout for faster view propagation.
        // When FD_SOCK2 detects a socket close (definitive failure), the SUSPECT event
        // passes through VERIFY_SUSPECT before reaching GMS. Default timeout is 5000ms,
        // which delays view installation. In container environments, socket close is
        // definitive, so 2s is enough to catch FD_ALL3 false positives while giving
        // Infinispan's ClusterTopologyManagerImpl more time within its 6s clusterReplyTimeout
        // window (ISPN000476) to get responses from all members.
        Address verifySuspectAddress = Address.subsystem("jgroups")
            .and("stack", "tcp")
            .and("protocol", "VERIFY_SUSPECT");
        if (ops.exists(verifySuspectAddress)) {
            ops.invoke("map-put", verifySuspectAddress,
                Values.of("name", "properties")
                    .and("key", "timeout")
                    .and("value", "2000")).assertSuccess();
            log.info("VERIFY_SUSPECT timeout reduced to 2000ms on worker '{}'", container.getName());
        } else {
            // JGroups 5.3+ uses VERIFY_SUSPECT2
            verifySuspectAddress = Address.subsystem("jgroups")
                .and("stack", "tcp")
                .and("protocol", "VERIFY_SUSPECT2");
            if (ops.exists(verifySuspectAddress)) {
                ops.invoke("map-put", verifySuspectAddress,
                    Values.of("name", "properties")
                        .and("key", "timeout")
                        .and("value", "2000")).assertSuccess();
                log.info("VERIFY_SUSPECT2 timeout reduced to 2000ms on worker '{}'", container.getName());
            }
        }

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
