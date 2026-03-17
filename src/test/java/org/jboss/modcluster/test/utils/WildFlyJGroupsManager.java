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
            log.warn("FD_SOCK2 protocol not found in TCP stack on worker '{}' — skipping external_addr configuration",
                container.getName());
        }

        // Tune FD_ALL3 as a safety net: reduce timeout from 40s to 10s and
        // interval from 8s to 3s for faster backup failure detection if FD_SOCK2
        // somehow fails to detect a crash.
        Address fdAll3Address = Address.subsystem("jgroups")
            .and("stack", "tcp")
            .and("protocol", "FD_ALL3");
        if (ops.exists(fdAll3Address)) {
            ops.invoke("map-put", fdAll3Address,
                Values.of("name", "properties")
                    .and("key", "timeout")
                    .and("value", "10000")).assertSuccess();
            ops.invoke("map-put", fdAll3Address,
                Values.of("name", "properties")
                    .and("key", "interval")
                    .and("value", "3000")).assertSuccess();
            log.info("FD_ALL3 tuned: timeout=10000, interval=3000 on worker '{}'", container.getName());
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
     * Get the number of members in the JGroups cluster view.
     * Reads the runtime {@code view} attribute of the {@code ee} channel.
     * The view string format is: {@code [coordinator|view-id] (member-count) [member1, member2, ...]}.
     *
     * @return number of cluster members, or 1 if view cannot be read/parsed
     */
    public int getClusterViewSize() {
        try {
            Operations ops = container.getOperations();
            Address channelAddr = Address.subsystem("jgroups").and("channel", "ee");
            ModelNodeResult result = ops.readResource(channelAddr, ReadResourceOption.INCLUDE_RUNTIME);

            if (!result.isSuccess()) {
                log.warn("JGroups channel resource not available on '{}'. " +
                    "Raw DMR response: {}", container.getName(), result.toString());
                return 1;
            }

            ModelNode resource = result.value();
            if (!resource.hasDefined("view")) {
                log.warn("JGroups view not defined on '{}' (channel not started yet). " +
                    "Resource: {}", container.getName(), resource.toJSONString(false));
                return 1;
            }

            String view = resource.get("view").asString();
            // Parse "(N)" from the view string to get member count
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
        } catch (Exception e) {
            log.warn("Error reading JGroups view on '{}': {}", container.getName(), e.getMessage(), e);
            return 1;
        }
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
