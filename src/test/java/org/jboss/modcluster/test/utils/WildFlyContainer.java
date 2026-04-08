package org.jboss.modcluster.test.utils;

import org.jboss.modcluster.test.utils.balancer.BalancerContainer;
import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static java.time.Duration.ofSeconds;
import static java.time.Duration.ofMillis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Container wrapper for WildFly/EAP workers with mod_cluster subsystem.
 * Builds container from ZIP distribution.
 */
public class WildFlyContainer {

    private static final Logger log = LoggerFactory.getLogger(WildFlyContainer.class);

    private static final int HTTP_PORT = 8080;
    private static final int HTTPS_PORT = 8443;
    private static final int MANAGEMENT_PORT = 9990;
    private static final int JGROUPS_TCP_PORT = 7600;
    private static final int JGROUPS_FD_PORT = 57600;

    private final String name;
    private final BalancerContainer balancer;
    private String javaOpts;
    private GenericContainer<?> container;
    private OnlineManagementClient managementClient;
    private WildFlyDeploymentManager deploymentManager;
    private WildFlyModClusterManager modClusterManager;
    private WildFlyUndertowManager undertowManager;
    private WildFlyLoadMetricsManager loadMetricsManager;
    private WildFlyJGroupsManager jgroupsManager;

    public WildFlyContainer(String name, BalancerContainer balancer) {
        this.name = name;
        this.balancer = balancer;
    }

    /**
     * Override JVM options for this worker. Must be called before {@link #start()}.
     * Useful for tests that need more heap (e.g., heap load metric tests).
     */
    public WildFlyContainer withJavaOpts(String javaOpts) {
        this.javaOpts = javaOpts;
        return this;
    }

    public void start() {
        Path zipPath = ContainerUtils.getWildFlyZipPath();

        if (zipPath != null && zipPath.toFile().exists()) {
            log.info("Building WildFly container from ZIP: {}", zipPath);
            startFromZip(zipPath);
        } else {
            log.info("No ZIP provided, using pre-built container image");
            startFromImage();
        }
    }

    /**
     * Start WildFly from a ZIP distribution (WildFly or EAP).
     * Uses direct docker build to avoid Testcontainers large file transfer issues.
     */
    private void startFromZip(Path zipPath) {
        String imageTag = ImageBuilder.ensureImage(zipPath);
        startFromPreBuiltImage(imageTag);
    }

    /**
     * Start WildFly from pre-built container image (fallback).
     * Note: This image reference is a placeholder and may not exist.
     * Provide a ZIP in distributions/ for reliable operation.
     */
    private void startFromImage() {
        String wildflyVersion = System.getProperty("wildfly.version", "31.0.1.Final");
        // Placeholder image — may not exist, provide a ZIP instead
        String imageName = "quay.io/wildfly/wildfly:" + wildflyVersion;

        startFromPreBuiltImage(imageName);
    }

    /**
     * Start container from a pre-built image (either from registry or locally built).
     * Includes optimized retry logic for transient Podman socket errors (SIGPIPE).
     */
    private void startFromPreBuiltImage(String imageName) {
        ContainerUtils.startWithRetry(() -> {
            container = new GenericContainer<>(imageName)
                    .withNetwork(balancer.getNetwork())
                    .withNetworkAliases(name)
                    .withCreateContainerCmdModifier(cmd -> cmd.withHostName(name))
                    .withExposedPorts(HTTP_PORT, HTTPS_PORT, MANAGEMENT_PORT, JGROUPS_TCP_PORT, JGROUPS_FD_PORT)
                    .withEnv("JAVA_OPTS", javaOpts != null ? javaOpts : System.getProperty("wildfly.java.opts"))
                    .withCommand("/opt/wildfly/bin/standalone.sh",
                                "-b", "0.0.0.0",
                                "-bmanagement", "0.0.0.0",
                                "-bprivate", "0.0.0.0",
                                "-Djboss.node.name=" + name,
                                "-Djboss.server.default.config=standalone-ha.xml",
                                "-Djboss.modcluster.multicast.address=224.0.1.105",
                                "-Djboss.modcluster.multicast.port=23364")
                    .waitingFor(Wait.forLogMessage(".*WFLYSRV0025.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(5)))
                    .withLogConsumer(outputFrame ->
                            System.out.println("[" + name.toUpperCase() + "] " + outputFrame.getUtf8String().trim()));

            ContainerUtils.applyJavaHomeIfNeeded(container);
            container.start();
            log.info("WildFly worker '{}' started", name);

            // Configure JGroups TCP for container-based clustering
            // (UDP multicast discovery does not work in Docker/Podman networks)
            jgroups().configureTcpDiscovery();
            reloadServer();                        // apply JGroups TCP config
            modCluster().configureStaticProxy();   // create outbound-socket-binding + proxies
            reloadServer();                        // make proxy config effective
            deployment().deployDemoApp();
        }, () -> {
            if (container != null) {
                try {
                    container.close();
                } catch (Exception e) {
                    log.debug("Error during cleanup: {}", e.getMessage());
                }
                container = null;
            }
        }, "WildFly worker '" + name + "'");
    }


    public void shutdown() {
        if (managementClient != null) {
            try {
                log.info("Initiating management API shutdown for worker '{}'", name);
                new Administration(managementClient).shutdown();
                Thread.sleep(2000); // Let JGroups send LEAVE
            } catch (IOException e) {
                log.debug("Management connection closed during shutdown (expected): {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Management API shutdown failed for '{}': {}", name, e.getMessage());
            }
        }
        stop(); // Docker container cleanup
    }

    public void stop() {
        closeManagementClient();

        if (container != null) {
            String containerId = container.getContainerId();

            // Step 1: Disconnect from network FIRST
            if (containerId != null && balancer != null && balancer.getNetwork() != null) {
                ContainerUtils.retryOnTransientError(() ->
                        container.getDockerClient()
                            .disconnectFromNetworkCmd()
                            .withContainerId(containerId)
                            .withNetworkId(balancer.getNetwork().getId())
                            .withForce(true)
                            .exec(),
                        "disconnect worker '" + name + "' from network", 3);
            }

            // Step 2: Stop container
            ContainerUtils.retryOnTransientError(() -> {
                if (container.isRunning()) {
                    container.stop();
                    log.info("WildFly worker '{}' stopped", name);
                }
            }, "stop worker '" + name + "'", 3);

            // Step 3: Remove container
            if (containerId != null) {
                ContainerUtils.retryOnTransientError(() ->
                        container.getDockerClient()
                            .removeContainerCmd(containerId)
                            .withForce(true)
                            .exec(),
                        "remove worker '" + name + "'", 3);
            }

            container = null;
            clearCachedManagers();
        }
    }

    /**
     * Hard kill the worker (simulates crash/SIGKILL).
     * Kills the container immediately without graceful shutdown.
     * Retries on transient Podman socket errors (SIGPIPE / broken pipe).
     * Throws if the SIGKILL fails after retries — callers must know the container is still alive.
     */
    public void kill() throws Exception {
        closeManagementClient();

        if (container == null) return;

        try {
            // isRunning() itself can throw on Podman socket errors — treat that as "maybe running"
            boolean running;
            try {
                running = container.isRunning();
            } catch (Exception e) {
                if (ContainerUtils.isTransientDockerError(e)) {
                    log.warn("Podman socket error checking isRunning() for '{}', will attempt SIGKILL anyway: {}",
                            name, e.getMessage());
                    running = true; // assume running, try to kill
                } else {
                    throw e;
                }
            }

            if (!running) {
                log.info("WildFly worker '{}' container already stopped", name);
                return;
            }

            String containerId = container.getContainerId();

            // SIGKILL with retry — Podman socket can SIGPIPE transiently.
            // SIGKILL must happen BEFORE network disconnect: the kernel closes all TCP
            // sockets of the killed process and sends RST to peers while the network
            // namespace is still connected to the bridge. If we disconnected the network
            // first, the process would still be alive but unreachable — TCP connections
            // would black-hole (no RST, no FIN) and peers would only detect the failure
            // via FD_ALL3 heartbeat timeout (30s), far too slow for failover tests.
            int maxAttempts = 3;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    container.getDockerClient()
                        .killContainerCmd(containerId)
                        .withSignal("KILL")
                        .exec();
                    log.info("WildFly worker '{}' killed (hard stop)", name);
                    break;
                } catch (Exception e) {
                    if (ContainerUtils.isTransientDockerError(e) && attempt < maxAttempts) {
                        log.warn("Transient error killing '{}' (attempt {}/{}): {}", name, attempt, maxAttempts, e.getMessage());
                        Thread.sleep(500L * attempt);
                    } else {
                        throw e;
                    }
                }
            }

            // Verify container is actually dead (Podman may need a moment after SIGKILL)
            await().atMost(ofSeconds(10))
                .pollInterval(ofMillis(500))
                .untilAsserted(() ->
                    assertThat(container.isRunning())
                        .as("Container for worker '%s' should be dead after SIGKILL", name)
                        .isFalse()
                );
        } finally {
            if (container != null) {
                String containerId = container.getContainerId();

                // Disconnect from network after kill — prevents MCMP contamination
                if (containerId != null && balancer != null && balancer.getNetwork() != null) {
                    ContainerUtils.retryOnTransientError(() ->
                            container.getDockerClient()
                                .disconnectFromNetworkCmd()
                                .withContainerId(containerId)
                                .withNetworkId(balancer.getNetwork().getId())
                                .withForce(true)
                                .exec(),
                            "disconnect killed worker '" + name + "' from network", 3);
                }

                // Force-remove the dead container (no need for SIGTERM via stop())
                if (containerId != null) {
                    ContainerUtils.retryOnTransientError(() ->
                            container.getDockerClient()
                                .removeContainerCmd(containerId)
                                .withForce(true)
                                .exec(),
                            "remove killed worker '" + name + "'", 3);
                }
            }
            container = null;
            clearCachedManagers();
        }
    }

    private void closeManagementClient() {
        if (managementClient != null) {
            try {
                managementClient.close();
            } catch (IOException e) {
                log.warn("Error closing management client for worker '{}'", name, e);
            }
            managementClient = null;
        }
    }

    private void clearCachedManagers() {
        deploymentManager = null;
        modClusterManager = null;
        undertowManager = null;
        loadMetricsManager = null;
        jgroupsManager = null;
    }

    public String getHttpUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(HTTP_PORT);
    }

    public String getHttpsUrl() {
        return "https://" + container.getHost() + ":" + container.getMappedPort(HTTPS_PORT);
    }

    public String getManagementUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(MANAGEMENT_PORT);
    }

    public String getInternalHttpUrl() {
        return "http://" + name + ":" + HTTP_PORT;
    }

    public String getName() {
        return name;
    }

    /**
     * Get the balancer container that this worker is associated with.
     *
     * @return the balancer container
     */
    public BalancerContainer getBalancer() {
        return balancer;
    }

    public GenericContainer<?> getContainer() {
        return container;
    }

    /**
     * Get Creaper ManagementClient for this WildFly instance.
     * Creates client on first call, reuses it afterwards.
     */
    public OnlineManagementClient getManagementClient() throws IOException {
        if (managementClient == null) {
            managementClient = ManagementClientFactory.create(
                    container.getHost(), container.getMappedPort(MANAGEMENT_PORT));
            log.debug("Created management client for worker '{}'", name);
        }
        return managementClient;
    }

    /**
     * Get Creaper Operations helper for this WildFly instance.
     */
    public Operations getOperations() throws IOException {
        return new Operations(getManagementClient());
    }

    /**
     * Get Creaper Administration helper for this WildFly instance.
     */
    public Administration getAdministration() throws IOException {
        return new Administration(getManagementClient());
    }

    /**
     * Get deployment manager for this worker.
     * Provides access to deployment operations (deploy, undeploy, status checks).
     *
     * @return cached deployment manager instance
     */
    public WildFlyDeploymentManager deployment() {
        if (deploymentManager == null) {
            deploymentManager = new WildFlyDeploymentManager(this);
        }
        return deploymentManager;
    }

    /**
     * Get mod_cluster configuration manager for this worker.
     * Provides access to mod_cluster subsystem operations (proxy config, attributes).
     *
     * @return cached mod_cluster manager instance
     */
    public WildFlyModClusterManager modCluster() {
        if (modClusterManager == null) {
            modClusterManager = new WildFlyModClusterManager(this);
        }
        return modClusterManager;
    }

    /**
     * Get Undertow subsystem manager for this worker.
     * Provides access to Undertow server, socket binding, and listener management.
     *
     * @return cached Undertow manager instance
     */
    public WildFlyUndertowManager undertow() {
        if (undertowManager == null) {
            undertowManager = new WildFlyUndertowManager(this);
        }
        return undertowManager;
    }

    /**
     * Get load metrics manager for this worker.
     * Provides access to load metric configuration (custom metrics, load values).
     *
     * @return cached load metrics manager instance
     */
    public WildFlyLoadMetricsManager loadMetrics() {
        if (loadMetricsManager == null) {
            loadMetricsManager = new WildFlyLoadMetricsManager(this);
        }
        return loadMetricsManager;
    }

    /**
     * Get JGroups manager for this worker.
     * Provides access to JGroups subsystem configuration (TCP/TCPPING discovery).
     *
     * @return cached JGroups manager instance
     */
    public WildFlyJGroupsManager jgroups() {
        if (jgroupsManager == null) {
            jgroupsManager = new WildFlyJGroupsManager(this);
        }
        return jgroupsManager;
    }

    /**
     * Execute a CLI command on this WildFly instance using Creaper.
     *
     * @deprecated Use getManagementClient() and Creaper operations instead
     */
    @Deprecated
    public String executeCli(String command) throws Exception {
        OnlineManagementClient client = getManagementClient();
        ModelNode result = client.execute(command);
        return result.toJSONString(false);
    }

    /**
     * Execute a CLI command using shell (fallback for complex commands).
     */
    public String executeCliViaShell(String command) throws Exception {
        Container.ExecResult execResult = container.execInContainer(
                "sh", "-c",
                "jboss-cli.sh --connect --controller=localhost:9990 --command='" + command + "'"
        );

        if (execResult.getExitCode() != 0) {
            throw new RuntimeException("CLI command failed: " + execResult.getStderr());
        }

        return execResult.getStdout();
    }


    /**
     * Reload the server configuration and wait for management to be ready.
     * Does not reconfigure static proxy or redeploy applications.
     * Use this when the management model already contains the desired configuration
     * (e.g., MCMP-over-SSL settings that must take effect via reload).
     */
    public void reloadServer() throws Exception {
        log.info("Reloading worker '{}'", name);

        // Invalidate cached client — reload drops the connection
        if (managementClient != null) {
            try {
                managementClient.close();
            } catch (IOException ignored) {
            }
            managementClient = null;
        }

        try {
            getAdministration().reload();
        } catch (Exception e) {
            if (e instanceof java.util.concurrent.TimeoutException
                    || e.getCause() instanceof java.util.concurrent.TimeoutException
                    || (e.getMessage() != null && e.getMessage().contains("Waiting for server timed out"))) {
                log.warn("Reload timed out for '{}', waiting with fresh connection (bootTimeout=120s)", name);
                managementClient = null;
                getAdministration().waitUntilRunning();
            } else {
                throw e;
            }
        }
        log.info("Worker '{}' reloaded successfully", name);
    }

    /**
     * Restart the server (full JVM restart, heavier than reload).
     * Uses {@code :shutdown(restart=true)} — the process controller (PID 1) stays
     * alive and spawns a new JVM. The mod_cluster subsystem reinitializes and
     * re-registers with the balancer from scratch, respecting the current
     * {@code excluded-contexts} configuration.
     */
    public void restartServer() throws Exception {
        log.info("Restarting worker '{}'", name);
        getAdministration().restart();
        managementClient = null; // force reconnect on next use
        log.info("Worker '{}' restarted successfully", name);
    }

    /**
     * Reload the server configuration.
     * All management model state (deployments, proxy config) persists across reloads.
     */
    public void reload() throws Exception {
        reloadServer();
    }

    /**
     * Get the last N lines from the WildFly server log.
     *
     * @param lines Number of lines to retrieve
     * @return Server log content
     */
    public String getServerLog(int lines) throws Exception {
        Container.ExecResult result = container.execInContainer(
            "sh", "-c",
            String.format("tail -%d /opt/wildfly/standalone/log/server.log 2>/dev/null || echo 'Log file not found'", lines)
        );
        return result.getStdout();
    }

    /**
     * Get the full server log.
     *
     * @return Complete server log content
     */
    public String getServerLog() throws Exception {
        Container.ExecResult result = container.execInContainer(
            "cat", "/opt/wildfly/standalone/log/server.log"
        );
        return result.getStdout();
    }

    /**
     * Grep the server log for specific patterns.
     *
     * @param pattern Regex pattern to search for
     * @return Matching lines from the log
     */
    public String grepServerLog(String pattern) throws Exception {
        Container.ExecResult result = container.execInContainer(
            "sh", "-c",
            String.format("grep -i '%s' /opt/wildfly/standalone/log/server.log || echo 'No matches found'", pattern)
        );
        return result.getStdout();
    }

}
