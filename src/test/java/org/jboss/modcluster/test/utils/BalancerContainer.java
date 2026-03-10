package org.jboss.modcluster.test.utils;

import org.jboss.modcluster.test.base.BalancerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.wildfly.extras.creaper.core.ManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineOptions;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Container wrapper for load balancers (Undertow or httpd with mod_cluster).
 */
public abstract class BalancerContainer {

    private static final Logger log = LoggerFactory.getLogger(BalancerContainer.class);

    protected GenericContainer<?> container;
    protected Network network;
    protected BalancerType type;
    protected boolean ownsNetwork = true;

    protected static final int HTTP_PORT = 8080;
    protected static final int HTTPS_PORT = 8443;
    protected static final int MCMP_PORT = 6666;
    protected static final int MANAGEMENT_PORT = 9990;

    public static BalancerContainer create(BalancerType type) {
        switch (type) {
            case UNDERTOW:
                return new UndertowBalancerContainer();
            case HTTPD:
                return new HttpdBalancerContainer();
            default:
                throw new IllegalArgumentException("Unknown balancer type: " + type);
        }
    }

    public abstract void start();

    /**
     * Start the balancer on an existing network with a custom alias.
     * Used when multiple balancers share the same Docker network.
     *
     * @param network existing network to attach to
     * @param networkAlias alias for this balancer on the network
     */
    public abstract void start(Network network, String networkAlias);

    public void stop() {
        // Stop and remove container first
        if (container != null) {
            try {
                if (container.isRunning()) {
                    container.stop();
                    log.debug("Balancer container stopped");
                }

                // Explicitly remove container
                String containerId = container.getContainerId();
                if (containerId != null) {
                    container.getDockerClient()
                        .removeContainerCmd(containerId)
                        .withForce(true)
                        .exec();
                    log.debug("Balancer container removed");
                }
            } catch (Exception e) {
                log.debug("Ignoring error stopping/removing balancer container: {}", e.getMessage());
            }
        }

        // Give more time for container removal to complete before network cleanup
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Close network to free resources (only if this balancer owns the network)
        if (network != null && ownsNetwork) {
            try {
                network.close();
                log.debug("Network closed");
            } catch (Exception e) {
                log.debug("Ignoring error closing network: {}", e.getMessage());
            }
        }
    }

    public String getHttpUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(HTTP_PORT);
    }

    public String getHttpsUrl() {
        return "https://" + container.getHost() + ":" + container.getMappedPort(HTTPS_PORT);
    }

    public String getMcmpUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(MCMP_PORT);
    }

    public String getInternalHttpUrl() {
        return "http://" + container.getContainerInfo().getConfig().getHostName() + ":" + HTTP_PORT;
    }

    public Network getNetwork() {
        return network;
    }

    public GenericContainer<?> getContainer() {
        return container;
    }

    public BalancerType getType() {
        return type;
    }

    /**
     * Get the internal MCMP management port for this balancer type.
     * Workers use this port in their outbound-socket-binding to connect to the balancer's MCMP endpoint.
     *
     * @return 8080 for Undertow (shares HTTP port), 6666 for httpd (dedicated MCMP port)
     */
    public abstract int getInternalMcmpPort();

    /**
     * Get the internal MCMP port used when SSL/TLS is enabled on the MCMP channel.
     * On Undertow, MCMP switches from HTTP (8080) to HTTPS (8443) when SSL is enabled.
     * On httpd, MCMP stays on the same port (6666) with SSL overlaid.
     *
     * @return 8443 for Undertow, 6666 for httpd
     */
    public abstract int getMcmpSslPort();

    /**
     * Get worker/node information from the balancer.
     * Returns a map of worker names to their runtime information including load.
     */
    public abstract Map<String, org.jboss.dmr.ModelNode> getWorkerInfo() throws Exception;

    /**
     * Get the list of balancer group names registered on this balancer.
     * Each group corresponds to a distinct load-balancing pool of workers.
     *
     * @return list of balancer names (e.g., ["mycluster", "balancerXXX1"])
     * @throws Exception if the query fails
     */
    public abstract List<String> getBalancerNames() throws Exception;

    /**
     * Disable a node on this balancer via the mod_cluster filter management interface.
     * The node will not receive new requests but will continue serving existing sessions.
     *
     * @param nodeName the name of the node to disable (e.g., "worker1")
     * @throws Exception if the operation fails
     */
    public abstract void disableNode(String nodeName) throws Exception;

    /**
     * Stop a node on this balancer via the mod_cluster filter management interface.
     * The node will immediately stop receiving all requests.
     *
     * @param nodeName the name of the node to stop (e.g., "worker1")
     * @throws Exception if the operation fails
     */
    public abstract void stopNode(String nodeName) throws Exception;

    /**
     * Enable a previously disabled/stopped node on this balancer.
     *
     * @param nodeName the name of the node to enable (e.g., "worker1")
     * @throws Exception if the operation fails
     */
    public abstract void enableNode(String nodeName) throws Exception;

    /**
     * Remove all application context registrations for a node from this balancer.
     * The node will re-register its contexts on the next STATUS/CONFIG cycle.
     * Used to clear stale context entries when a node changes balancer groups.
     *
     * @param nodeName the name of the node to remove
     * @throws Exception if the operation fails
     */
    public abstract void removeNode(String nodeName) throws Exception;

    /**
     * Disable a load-balancing group on this balancer.
     * All nodes in the group will not receive new requests but continue serving existing sessions.
     *
     * @param groupName the name of the load-balancing group to disable
     * @throws Exception if the operation fails
     */
    public abstract void disableLoadBalancingGroup(String groupName) throws Exception;

    /**
     * Stop a load-balancing group on this balancer.
     * All nodes in the group will immediately stop receiving all requests.
     *
     * @param groupName the name of the load-balancing group to stop
     * @throws Exception if the operation fails
     */
    public abstract void stopLoadBalancingGroup(String groupName) throws Exception;

    /**
     * Enable a previously disabled/stopped load-balancing group on this balancer.
     *
     * @param groupName the name of the load-balancing group to enable
     * @throws Exception if the operation fails
     */
    public abstract void enableLoadBalancingGroup(String groupName) throws Exception;

    /**
     * Get the context status for a specific node and context on this balancer.
     *
     * @param nodeName the name of the node (e.g., "worker1")
     * @param contextPath the context path (e.g., "/demo")
     * @return the context status string (e.g., "ENABLED", "DISABLED", "STOPPED")
     * @throws Exception if the query fails
     */
    public abstract String getContextStatus(String nodeName, String contextPath) throws Exception;

    /**
     * Get all registered context paths for a specific node on this balancer.
     * Returns the list of context paths (e.g., ["/demo", "/simplecontext-111"]) that
     * the balancer knows about for the given node.
     *
     * @param nodeName the name of the node (e.g., "worker1")
     * @return list of registered context paths, or empty list if node not found
     * @throws Exception if the query fails
     */
    public abstract List<String> getRegisteredContexts(String nodeName) throws Exception;

    /**
     * Disable a specific context on a node via the balancer management interface.
     * The context will not receive new requests but existing sessions continue.
     *
     * @param nodeName the name of the node (e.g., "worker1")
     * @param contextPath the context path (e.g., "/demo" or "demo")
     * @throws Exception if the operation fails
     */
    public abstract void disableContext(String nodeName, String contextPath) throws Exception;

    /**
     * Stop a specific context on a node via the balancer management interface.
     * The context will immediately stop receiving all requests.
     *
     * @param nodeName the name of the node (e.g., "worker1")
     * @param contextPath the context path (e.g., "/demo" or "demo")
     * @throws Exception if the operation fails
     */
    public abstract void stopContext(String nodeName, String contextPath) throws Exception;

    /**
     * Enable a previously disabled/stopped context on a node via the balancer management interface.
     *
     * @param nodeName the name of the node (e.g., "worker1")
     * @param contextPath the context path (e.g., "/demo" or "demo")
     * @throws Exception if the operation fails
     */
    public abstract void enableContext(String nodeName, String contextPath) throws Exception;

    /**
     * Set the max-retries attribute on the mod_cluster filter.
     * Controls how many times the balancer retries a failed request.
     * Undertow-specific setting.
     *
     * @param maxRetries the maximum number of retries
     * @throws Exception if the operation fails
     */
    public abstract void setMaxRetries(int maxRetries) throws Exception;

    /**
     * Reload the balancer server to apply configuration changes.
     * Should only be called when no workers are connected (e.g., during initial setup),
     * otherwise worker MCMP connections will be disrupted.
     *
     * @throws Exception if the reload fails
     */
    public abstract void reload() throws Exception;

    /**
     * Enables SSL on the internal MCMP management client.
     * Called after mTLS is configured on the MCMP port so that test-code queries
     * (INFO, DUMP, etc.) use HTTPS. No-op for balancers that don't use MCMP (Undertow).
     */
    public abstract void enableMcmpSsl();

    /**
     * Undertow-based mod_cluster balancer.
     * Uses the same WildFly/EAP ZIP as workers, but configured as a load balancer.
     */
    static class UndertowBalancerContainer extends BalancerContainer {

        @Override
        public int getInternalMcmpPort() {
            return HTTP_PORT;
        }

        @Override
        public int getMcmpSslPort() {
            return HTTPS_PORT;
        }

        @Override
        public void start() {
            type = BalancerType.UNDERTOW;
            network = Network.newNetwork();
            ownsNetwork = true;

            Path zipPath = getWildFlyZipPath();

            if (zipPath != null && zipPath.toFile().exists()) {
                log.info("Building Undertow balancer from ZIP: {}", zipPath);
                startFromZip(zipPath, "balancer");
            } else {
                log.info("No ZIP provided, using pre-built Undertow balancer image");
                startFromImage("balancer");
            }
        }

        @Override
        public void start(Network network, String networkAlias) {
            type = BalancerType.UNDERTOW;
            this.network = network;
            ownsNetwork = false;

            Path zipPath = getWildFlyZipPath();

            if (zipPath != null && zipPath.toFile().exists()) {
                log.info("Building Undertow balancer from ZIP: {}", zipPath);
                startFromZip(zipPath, networkAlias);
            } else {
                log.info("No ZIP provided, using pre-built Undertow balancer image");
                startFromImage(networkAlias);
            }
        }

        private void startFromZip(Path zipPath) {
            startFromZip(zipPath, "balancer");
        }

        private void startFromZip(Path zipPath, String networkAlias) {
            String zipFileName = zipPath.getFileName().toString();
            String javaVersion = getRequiredJavaVersion(zipFileName);

            // Generate consistent image tag (same as workers, image is reused!)
            String imageTag = ImageBuilder.generateImageTag(zipFileName, javaVersion);

            // Check if image already exists (might have been built by worker)
            if (!ImageBuilder.imageExists(imageTag)) {
                log.info("Building Undertow balancer from ZIP: {} (this may take a few minutes on first run)", zipFileName);
                ImageBuilder.buildImageFromZip(zipPath, javaVersion, imageTag);
            } else {
                log.info("Using existing image: {}", imageTag);
            }

            // Start with balancer configuration in --admin-only mode (like noe-tests)
            // Use standalone.xml (NOT standalone-ha.xml) - we'll configure mod_cluster filter
            container = new GenericContainer<>(imageTag)
                    .withNetwork(network)
                    .withNetworkAliases(networkAlias)
                    .withExposedPorts(HTTP_PORT, HTTPS_PORT, MANAGEMENT_PORT)
                    .withCommand("/opt/wildfly/bin/standalone.sh",
                                "-Djboss.node.name=" + networkAlias,
                                "-bmanagement", "0.0.0.0",
                                "--admin-only")
                    .waitingFor(Wait.forLogMessage(".*WFLYSRV0025.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(5)))
                    .withLogConsumer(outputFrame ->
                            log.debug("[UNDERTOW-BALANCER-{}] {}", networkAlias.toUpperCase(),
                                    outputFrame.getUtf8String().trim()));

            container.start();
            log.info("Undertow balancer '{}' started in admin-only mode on network: {}", networkAlias, network.getId());

            // Configure the balancer to act as a balancer (not a worker) - all changes in admin-only mode
            configureAsBalancer();
        }

        /**
         * Configure this WildFly instance to act as a mod_cluster balancer.
         * Uses Creaper Operations API following the order from noe-tests CLILib.
         * Server must be started in --admin-only mode to avoid reload-required state.
         */
        private void configureAsBalancer() {
            try {
                OnlineManagementClient client =
                    ManagementClient.online(
                        OnlineOptions.standalone()
                            .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                            .auth("admin", "admin")
                            .connectionTimeout(60_000)
                            .bootTimeout(120_000)
                            .build()
                    );

                Operations ops =
                    new Operations(client);

                log.info("Configuring Undertow mod_cluster filter on balancer (admin-only mode)");

                // Step 0: Get container's internal IP and configure public interface to use it
                String containerIp = container.getContainerInfo().getNetworkSettings().getNetworks()
                    .values().iterator().next().getIpAddress();
                log.info("Container IP: {}", containerIp);

                Address publicInterfaceAddr =
                    Address.of("interface", "public");

                // Undefine any-address first, then set inet-address
                ops.undefineAttribute(publicInterfaceAddr, "any-address");
                ops.writeAttribute(publicInterfaceAddr, "inet-address", containerIp)
                    .assertSuccess("Failed to configure public interface");
                log.info("Public interface configured to: {}", containerIp);

                // Step 1: Create multicast socket binding for advertisement
                Address multicastAddr =
                    Address
                        .of("socket-binding-group", "standard-sockets")
                        .and("socket-binding", "modcluster");

                ops.add(multicastAddr,
                    Values.of("port", 0)
                        .and("multicast-address", "224.0.1.105")
                        .and("multicast-port", 23364))
                    .assertSuccess("Failed to add multicast socket binding");
                log.info("Multicast socket binding created");

                // Step 2: Add mod_cluster filter to undertow using standard HTTP socket
                Address filterAddr =
                    Address.subsystem("undertow")
                        .and("configuration", "filter")
                        .and("mod-cluster", "modcluster");

                ops.add(filterAddr,
                    Values.of("management-socket-binding", "http")
                        .and("advertise-socket-binding", "modcluster")
                        .and("health-check-interval", 5)  // Check worker health every 5 seconds
                        .and("broken-node-timeout", 10)   // Mark as down after 10 seconds of no response
                        .and("max-retries", 1)             // Retry on another backend when sticky target fails
                        .and("failover-strategy", "LOAD_BALANCED"))  // Failover to least loaded node
                    .assertSuccess("Failed to add mod_cluster filter");
                log.info("Mod_cluster filter created with health checks and failover enabled");

                // Step 3: Add filter-ref to default-host (following CLILib order)
                Address filterRefAddr =
                    Address.subsystem("undertow")
                        .and("server", "default-server")
                        .and("host", "default-host")
                        .and("filter-ref", "modcluster");

                ops.add(filterRefAddr)
                    .assertSuccess("Failed to add filter-ref");
                log.info("Filter-ref added to default-host");

                // Step 4: Reload from admin-only mode to normal mode (like noe-tests stop/start)
                log.info("Reloading server to transition from admin-only to normal mode");
                new Administration(client).reload();
                client.close();

                // Poll management interface until server is running after leaving admin-only mode
                OnlineManagementClient readyClient = ManagementClient.online(
                    OnlineOptions.standalone()
                        .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                        .auth("admin", "admin")
                        .connectionTimeout(60_000)
                        .bootTimeout(60_000)
                        .build()
                );
                new Administration(readyClient).waitUntilRunning();
                readyClient.close();

                log.info("Undertow balancer configured successfully. MCMP on HTTP socket binding (port {})", HTTP_PORT);

            } catch (Exception e) {
                log.error("Failed to configure balancer", e);
                throw new RuntimeException("Balancer configuration failed", e);
            }
        }

        @Override
        public Map<String, org.jboss.dmr.ModelNode> getWorkerInfo() throws Exception {
            Map<String, org.jboss.dmr.ModelNode> workerInfo = new HashMap<>();

            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            // Address to mod_cluster filter
            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            // Get list of balancers
            List<String> balancers = ops.readChildrenNames(filterAddr, "balancer").stringListValue();
            log.debug("Balancers: {}", balancers);

            // For each balancer, get its nodes (workers)
            for (String balancerName : balancers) {
                Address balancerAddr = filterAddr.and("balancer", balancerName);
                List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();
                log.debug("Balancer '{}' has nodes: {}", balancerName, nodes);

                // For each node, read its runtime info
                for (String nodeName : nodes) {
                    Address nodeAddr = balancerAddr.and("node", nodeName);
                    org.wildfly.extras.creaper.core.online.ModelNodeResult result =
                        ops.readResource(nodeAddr, org.wildfly.extras.creaper.core.online.operations.ReadResourceOption.INCLUDE_RUNTIME);

                    if (result.isSuccess()) {
                        org.jboss.dmr.ModelNode nodeInfo = result.value();
                        workerInfo.put(nodeName, nodeInfo);
                        log.debug("Node '{}' info: {}", nodeName, nodeInfo.toJSONString(false));
                    }
                }
            }

            client.close();
            return workerInfo;
        }

        @Override
        public List<String> getBalancerNames() throws Exception {
            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            List<String> balancers = ops.readChildrenNames(filterAddr, "balancer").stringListValue();
            log.debug("Balancer names: {}", balancers);

            client.close();
            return balancers;
        }

        @Override
        public void disableNode(String nodeName) throws Exception {
            invokeNodeOperation(nodeName, "disable");
        }

        @Override
        public void stopNode(String nodeName) throws Exception {
            invokeNodeOperation(nodeName, "stop");
        }

        @Override
        public void enableNode(String nodeName) throws Exception {
            invokeNodeOperation(nodeName, "enable");
        }

        @Override
        public void removeNode(String nodeName) throws Exception {
            log.debug("removeNode is a no-op on Undertow balancer (no stale entry issue)");
        }

        @Override
        public void enableMcmpSsl() {
            log.debug("enableMcmpSsl is a no-op on Undertow balancer (uses Creaper, not McmpClient)");
        }

        @Override
        public void disableLoadBalancingGroup(String groupName) throws Exception {
            invokeGroupOperation(groupName, "disable");
        }

        @Override
        public void stopLoadBalancingGroup(String groupName) throws Exception {
            invokeGroupOperation(groupName, "stop");
        }

        @Override
        public void enableLoadBalancingGroup(String groupName) throws Exception {
            invokeGroupOperation(groupName, "enable");
        }

        @Override
        public String getContextStatus(String nodeName, String contextPath) throws Exception {
            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            List<String> balancers = ops.readChildrenNames(filterAddr, "balancer").stringListValue();

            for (String balancerName : balancers) {
                Address balancerAddr = filterAddr.and("balancer", balancerName);
                List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();

                for (String node : nodes) {
                    if (node.equals(nodeName)) {
                        Address nodeAddr = balancerAddr.and("node", node);
                        List<String> contexts = ops.readChildrenNames(nodeAddr, "context").stringListValue();

                        String normalizedPath = contextPath.startsWith("/") ? contextPath : "/" + contextPath;

                        for (String ctx : contexts) {
                            if (ctx.equals(normalizedPath)) {
                                Address contextAddr = nodeAddr.and("context", ctx);
                                org.wildfly.extras.creaper.core.online.ModelNodeResult result =
                                    ops.readAttribute(contextAddr, "status");
                                client.close();
                                return result.stringValue();
                            }
                        }
                    }
                }
            }

            client.close();
            return null;
        }

        @Override
        public List<String> getRegisteredContexts(String nodeName) throws Exception {
            List<String> result = new ArrayList<>();

            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            List<String> balancers = ops.readChildrenNames(filterAddr, "balancer").stringListValue();

            for (String balancerName : balancers) {
                Address balancerAddr = filterAddr.and("balancer", balancerName);
                List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();

                if (nodes.contains(nodeName)) {
                    Address nodeAddr = balancerAddr.and("node", nodeName);
                    List<String> contexts = ops.readChildrenNames(nodeAddr, "context").stringListValue();
                    result.addAll(contexts);
                }
            }

            client.close();
            return result;
        }

        @Override
        public void disableContext(String nodeName, String contextPath) throws Exception {
            invokeContextOperation(nodeName, contextPath, "disable");
        }

        @Override
        public void stopContext(String nodeName, String contextPath) throws Exception {
            invokeContextOperation(nodeName, contextPath, "stop");
        }

        @Override
        public void enableContext(String nodeName, String contextPath) throws Exception {
            invokeContextOperation(nodeName, contextPath, "enable");
        }

        /**
         * Invoke an operation on a specific context of a node in the mod_cluster filter.
         * Finds the context across all balancers and invokes the specified operation.
         */
        private void invokeContextOperation(String nodeName, String contextPath, String operation) throws Exception {
            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            String normalizedPath = contextPath.startsWith("/") ? contextPath : "/" + contextPath;

            List<String> balancers = ops.readChildrenNames(filterAddr, "balancer").stringListValue();

            boolean found = false;
            for (String balancerName : balancers) {
                Address balancerAddr = filterAddr.and("balancer", balancerName);
                List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();

                if (nodes.contains(nodeName)) {
                    Address nodeAddr = balancerAddr.and("node", nodeName);
                    List<String> contexts = ops.readChildrenNames(nodeAddr, "context").stringListValue();

                    for (String ctx : contexts) {
                        if (ctx.equals(normalizedPath)) {
                            Address contextAddr = nodeAddr.and("context", ctx);
                            ops.invoke(operation, contextAddr).assertSuccess();
                            log.info("Invoked '{}' on context '{}' for node '{}' in balancer '{}'",
                                    operation, ctx, nodeName, balancerName);
                            found = true;
                            break;
                        }
                    }
                    if (found) break;
                }
            }

            client.close();

            if (!found) {
                log.warn("Context '{}' not found for node '{}' on balancer (may not be registered)",
                        normalizedPath, nodeName);
            }
        }

        @Override
        public void setMaxRetries(int maxRetries) throws Exception {
            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            ops.writeAttribute(filterAddr, "max-retries", maxRetries).assertSuccess();
            log.info("Set max-retries to {} on Undertow balancer", maxRetries);

            client.close();
        }

        @Override
        public void reload() throws Exception {
            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            log.info("Reloading Undertow balancer to apply configuration changes");
            new Administration(client).reload();
            client.close();
            log.info("Undertow balancer reloaded successfully");
        }

        /**
         * Invoke an operation on a node in the mod_cluster filter.
         * Finds the node across all balancers and invokes the specified operation.
         */
        private void invokeNodeOperation(String nodeName, String operation) throws Exception {
            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            List<String> balancers = ops.readChildrenNames(filterAddr, "balancer").stringListValue();

            boolean found = false;
            for (String balancerName : balancers) {
                Address balancerAddr = filterAddr.and("balancer", balancerName);
                List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();

                if (nodes.contains(nodeName)) {
                    Address nodeAddr = balancerAddr.and("node", nodeName);
                    ops.invoke(operation, nodeAddr).assertSuccess();
                    log.info("Invoked '{}' on node '{}' in balancer '{}'", operation, nodeName, balancerName);
                    found = true;
                    break;
                }
            }

            client.close();

            if (!found) {
                throw new IllegalStateException("Node '" + nodeName + "' not found on balancer");
            }
        }

        /**
         * Invoke an operation on all nodes belonging to a load-balancing group.
         * The Undertow management model does not support operations directly on
         * load-balancing-group resources, so this iterates over all nodes and invokes
         * the operation on each node whose load-balancing-group attribute matches.
         */
        private void invokeGroupOperation(String groupName, String operation) throws Exception {
            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            List<String> balancers = ops.readChildrenNames(filterAddr, "balancer").stringListValue();

            int matchedNodes = 0;
            for (String balancerName : balancers) {
                Address balancerAddr = filterAddr.and("balancer", balancerName);
                List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();

                for (String nodeName : nodes) {
                    Address nodeAddr = balancerAddr.and("node", nodeName);
                    org.wildfly.extras.creaper.core.online.ModelNodeResult groupResult =
                        ops.readAttribute(nodeAddr, "load-balancing-group");

                    if (groupResult.isSuccess()) {
                        String nodeGroup = groupResult.stringValue();
                        if (groupName.equals(nodeGroup)) {
                            ops.invoke(operation, nodeAddr).assertSuccess();
                            log.info("Invoked '{}' on node '{}' (group '{}') in balancer '{}'",
                                    operation, nodeName, groupName, balancerName);
                            matchedNodes++;
                        }
                    }
                }
            }

            client.close();

            if (matchedNodes == 0) {
                throw new IllegalStateException(
                    "No nodes found in load-balancing group '" + groupName + "' on balancer");
            }

            log.info("Invoked '{}' on {} nodes in group '{}'", operation, matchedNodes, groupName);
        }

        private void startFromImage(String networkAlias) {
            String customImage = System.getProperty("balancer.undertow.image");
            // Placeholder image — does not exist yet, override via -Dbalancer.undertow.image=
            String imageName = customImage != null ? customImage : "quay.io/modcluster/mod_cluster-undertow:latest";

            container = new GenericContainer<>(DockerImageName.parse(imageName))
                    .withNetwork(network)
                    .withNetworkAliases(networkAlias)
                    .withExposedPorts(HTTP_PORT, HTTPS_PORT, MCMP_PORT)
                    .waitingFor(Wait.forHttp("/").forPort(HTTP_PORT))
                    .withLogConsumer(outputFrame -> log.debug("[UNDERTOW-{}] {}",
                            networkAlias.toUpperCase(), outputFrame.getUtf8String().trim()));

            container.start();
            log.info("Undertow balancer '{}' started from pre-built image on network: {}",
                    networkAlias, network.getId());
        }

        private Path getWildFlyZipPath() {
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
         */
        private String getRequiredJavaVersion(String zipFileName) {
            // Check for explicit override first
            String javaVersionOverride = System.getProperty("container.java.version");
            if (javaVersionOverride != null && !javaVersionOverride.trim().isEmpty()) {
                String javaImage = "openjdk-" + javaVersionOverride;
                log.info("Using Java version from system property: {} ({})", javaVersionOverride, javaImage);
                return javaImage;
            }

            // Auto-detect based on filename
            if (zipFileName.startsWith("wildfly-")) {
                String versionPart = zipFileName.substring(8);
                String majorVersion = versionPart.split("\\.")[0];

                try {
                    int major = Integer.parseInt(majorVersion);
                    return major >= 31 ? "openjdk-17" : "openjdk-11";
                } catch (NumberFormatException e) {
                    log.warn("Could not parse WildFly version, defaulting to Java 17");
                    return "openjdk-17";
                }
            } else if (zipFileName.startsWith("jboss-eap-")) {
                String versionPart = zipFileName.substring(10);
                String majorVersion = versionPart.split("\\.")[0];

                try {
                    int major = Integer.parseInt(majorVersion);
                    return major >= 8 ? "openjdk-17" : "openjdk-11";
                } catch (NumberFormatException e) {
                    log.warn("Could not parse EAP version, defaulting to Java 17");
                    return "openjdk-17";
                }
            }

            log.warn("Unknown distribution format, defaulting to Java 17");
            return "openjdk-17";
        }
    }

    /**
     * Apache httpd with mod_proxy_cluster balancer.
     * Managed via MCMP (Mod Cluster Management Protocol) on a dedicated port (6666).
     */
    static class HttpdBalancerContainer extends BalancerContainer {

        private McmpClient mcmpClient;

        @Override
        public int getInternalMcmpPort() {
            return MCMP_PORT;
        }

        @Override
        public int getMcmpSslPort() {
            return MCMP_PORT;
        }

        @Override
        public void start() {
            type = BalancerType.HTTPD;
            network = Network.newNetwork();
            ownsNetwork = true;
            startContainer("balancer");
        }

        @Override
        public void start(final Network network, final String networkAlias) {
            type = BalancerType.HTTPD;
            this.network = network;
            ownsNetwork = false;
            startContainer(networkAlias);
        }

        /**
         * Starts the httpd container with mod_proxy_cluster configuration.
         * Copies the mod_proxy_cluster.conf into the container and configures httpd
         * to listen on port 8080 (data) and 6666 (MCMP management).
         * Includes retry logic for transient Podman socket errors (SIGPIPE).
         *
         * @param networkAlias network alias for this container
         */
        private void startContainer(final String networkAlias) {
            final String customImage = System.getProperty("balancer.httpd.image");
            final String imageName;
            if (customImage != null) {
                imageName = customImage;
            } else {
                imageName = HttpdImageBuilder.buildImage();
            }
            final int maxRetries = 5;
            final java.util.Random random = new java.util.Random();
            Exception lastException = null;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    container = new GenericContainer<>(DockerImageName.parse(imageName))
                            .withNetwork(network)
                            .withNetworkAliases(networkAlias)
                            .withExposedPorts(HTTP_PORT, HTTPS_PORT, MCMP_PORT)
                            .withCopyFileToContainer(
                                    MountableFile.forClasspathResource("httpd/mod_proxy_cluster.conf", 0644),
                                    "/usr/local/apache2/conf/extra/mod_proxy_cluster.conf")
                            .withCommand("/bin/sh", "-c",
                                    // Disable mod_proxy_balancer (conflicts with mod_proxy_cluster),
                                    // replace default Listen 80 with Listen 8080, include our config, and start httpd
                                    "sed -i 's/^LoadModule proxy_balancer_module/#LoadModule proxy_balancer_module/' " +
                                    "/usr/local/apache2/conf/httpd.conf && " +
                                    "sed -i 's/^\\(Listen 80\\)$/#\\1/' /usr/local/apache2/conf/httpd.conf && " +
                                    "echo 'Listen 8080' >> /usr/local/apache2/conf/httpd.conf && " +
                                    "echo 'Include conf/extra/mod_proxy_cluster.conf' >> /usr/local/apache2/conf/httpd.conf && " +
                                    "echo 'ErrorLog /proc/self/fd/2' >> /usr/local/apache2/conf/httpd.conf && " +
                                    "echo 'LogLevel info' >> /usr/local/apache2/conf/httpd.conf && " +
                                    "/usr/local/apache2/bin/httpd -DFOREGROUND")
                            .waitingFor(Wait.forHttp("/mod_cluster_manager").forPort(MCMP_PORT)
                                    .withStartupTimeout(Duration.ofMinutes(2)))
                            .withLogConsumer(outputFrame ->
                                    log.info("[HTTPD-{}] {}", networkAlias.toUpperCase(),
                                            outputFrame.getUtf8String().trim()));

                    container.start();

                    mcmpClient = new McmpClient(container.getHost(), container.getMappedPort(MCMP_PORT));
                    log.info("Httpd balancer '{}' started on network: {}{}",
                            networkAlias, network.getId(),
                            attempt > 1 ? " (attempt " + attempt + ")" : "");
                    return;

                } catch (Exception e) {
                    lastException = e;

                    if (ContainerUtils.isTransientDockerError(e) && attempt < maxRetries) {
                        final long baseDelay = attempt * 500L;
                        final long jitter = 100 + random.nextInt(200);
                        final long delayMs = baseDelay + jitter;

                        log.warn("Httpd container start failed with transient error on attempt {}/{}, retrying after {}ms",
                                attempt, maxRetries, delayMs);

                        if (container != null) {
                            try {
                                container.close();
                            } catch (Exception cleanupEx) {
                                log.debug("Error during cleanup: {}", cleanupEx.getMessage());
                            }
                            container = null;
                        }

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

            throw new RuntimeException("Failed to start httpd balancer '" + networkAlias +
                    "' after " + maxRetries + " attempts", lastException);
        }

        /**
         * Gets the MCMP client, creating it if needed (e.g., after reload).
         *
         * @return the MCMP client for this container
         */
        private McmpClient getMcmpClient() {
            if (mcmpClient == null) {
                mcmpClient = new McmpClient(container.getHost(), container.getMappedPort(MCMP_PORT));
            }
            return mcmpClient;
        }

        @Override
        public Map<String, org.jboss.dmr.ModelNode> getWorkerInfo() throws Exception {
            Map<String, org.jboss.dmr.ModelNode> workerInfo = new HashMap<>();

            String infoResponse = getMcmpClient().sendInfo();
            List<McmpClient.McmpNodeInfo> nodes = getMcmpClient().parseInfo(infoResponse);

            for (McmpClient.McmpNodeInfo node : nodes) {
                org.jboss.dmr.ModelNode nodeModel = new org.jboss.dmr.ModelNode();
                nodeModel.get("load").set(node.load);
                nodeModel.get("uri").set(node.type + "://" + node.host + ":" + node.port);
                nodeModel.get("load-balancing-group").set(node.lbGroup != null ? node.lbGroup : "");
                workerInfo.put(node.name, nodeModel);
                log.debug("Node '{}' info: load={}, uri={}://{}:{}", node.name, node.load, node.type, node.host, node.port);
            }

            return workerInfo;
        }

        @Override
        public List<String> getBalancerNames() throws Exception {
            String infoResponse = getMcmpClient().sendInfo();
            List<McmpClient.McmpNodeInfo> nodes = getMcmpClient().parseInfo(infoResponse);

            Set<String> balancerNames = new LinkedHashSet<>();
            for (McmpClient.McmpNodeInfo node : nodes) {
                if (node.balancer != null && !node.balancer.isEmpty()) {
                    balancerNames.add(node.balancer);
                }
            }

            List<String> result = new ArrayList<>(balancerNames);
            log.debug("Balancer names: {}", result);
            return result;
        }

        @Override
        public List<String> getRegisteredContexts(final String nodeName) throws Exception {
            String infoResponse = getMcmpClient().sendInfo();
            List<McmpClient.McmpNodeInfo> nodes = getMcmpClient().parseInfo(infoResponse);

            List<String> contexts = new ArrayList<>();
            for (McmpClient.McmpNodeInfo node : nodes) {
                if (nodeName.equals(node.name)) {
                    for (McmpClient.McmpContextInfo ctx : node.contexts) {
                        contexts.add(ctx.path);
                    }
                }
            }

            return contexts;
        }

        @Override
        public String getContextStatus(final String nodeName, final String contextPath) throws Exception {
            String infoResponse = getMcmpClient().sendInfo();
            List<McmpClient.McmpNodeInfo> nodes = getMcmpClient().parseInfo(infoResponse);

            String normalizedPath = contextPath.startsWith("/") ? contextPath : "/" + contextPath;

            for (McmpClient.McmpNodeInfo node : nodes) {
                if (nodeName.equals(node.name)) {
                    for (McmpClient.McmpContextInfo ctx : node.contexts) {
                        if (normalizedPath.equals(ctx.path)) {
                            return ctx.status;
                        }
                    }
                }
            }

            return null;
        }

        @Override
        public void disableNode(final String nodeName) throws Exception {
            getMcmpClient().disableNode(nodeName);
        }

        @Override
        public void stopNode(final String nodeName) throws Exception {
            getMcmpClient().stopNode(nodeName);
        }

        @Override
        public void enableNode(final String nodeName) throws Exception {
            getMcmpClient().enableNode(nodeName);
        }

        @Override
        public void removeNode(final String nodeName) throws Exception {
            getMcmpClient().removeNode(nodeName);
        }

        @Override
        public void enableMcmpSsl() {
            getMcmpClient().enableSsl();
        }

        @Override
        public void disableContext(final String nodeName, final String contextPath) throws Exception {
            getMcmpClient().disableApp(nodeName, contextPath, "default-host");
        }

        @Override
        public void stopContext(final String nodeName, final String contextPath) throws Exception {
            getMcmpClient().stopApp(nodeName, contextPath, "default-host");
        }

        @Override
        public void enableContext(final String nodeName, final String contextPath) throws Exception {
            getMcmpClient().enableApp(nodeName, contextPath, "default-host");
        }

        @Override
        public void disableLoadBalancingGroup(final String groupName) throws Exception {
            List<String> nodesInGroup = findNodesInGroup(groupName);
            if (nodesInGroup.isEmpty()) {
                throw new IllegalStateException(
                        "No nodes found in load-balancing group '" + groupName + "' on balancer");
            }
            for (String nodeName : nodesInGroup) {
                getMcmpClient().disableNode(nodeName);
            }
            log.info("Disabled {} nodes in group '{}'", nodesInGroup.size(), groupName);
        }

        @Override
        public void stopLoadBalancingGroup(final String groupName) throws Exception {
            List<String> nodesInGroup = findNodesInGroup(groupName);
            if (nodesInGroup.isEmpty()) {
                throw new IllegalStateException(
                        "No nodes found in load-balancing group '" + groupName + "' on balancer");
            }
            for (String nodeName : nodesInGroup) {
                getMcmpClient().stopNode(nodeName);
            }
            log.info("Stopped {} nodes in group '{}'", nodesInGroup.size(), groupName);
        }

        @Override
        public void enableLoadBalancingGroup(final String groupName) throws Exception {
            List<String> nodesInGroup = findNodesInGroup(groupName);
            if (nodesInGroup.isEmpty()) {
                throw new IllegalStateException(
                        "No nodes found in load-balancing group '" + groupName + "' on balancer");
            }
            for (String nodeName : nodesInGroup) {
                getMcmpClient().enableNode(nodeName);
            }
            log.info("Enabled {} nodes in group '{}'", nodesInGroup.size(), groupName);
        }

        @Override
        public void setMaxRetries(final int maxRetries) throws Exception {
            log.warn("setMaxRetries({}) is a no-op on httpd balancer (httpd does not support runtime max-retries)",
                    maxRetries);
        }

        @Override
        public void reload() throws Exception {
            log.info("Reloading httpd balancer (graceful restart)");
            container.execInContainer("/usr/local/apache2/bin/apachectl", "graceful");
            // Wait for httpd to finish graceful restart
            Thread.sleep(2000);
            log.info("Httpd balancer reloaded successfully");
        }

        /**
         * Finds all node names that belong to a specific load-balancing group.
         *
         * @param groupName the load-balancing group name to search for
         * @return list of node names in the group
         * @throws IOException if MCMP communication fails
         */
        private List<String> findNodesInGroup(final String groupName) throws IOException {
            String infoResponse = getMcmpClient().sendInfo();
            List<McmpClient.McmpNodeInfo> nodes = getMcmpClient().parseInfo(infoResponse);

            List<String> nodesInGroup = new ArrayList<>();
            for (McmpClient.McmpNodeInfo node : nodes) {
                if (groupName.equals(node.lbGroup)) {
                    nodesInGroup.add(node.name);
                }
            }
            return nodesInGroup;
        }
    }
}
