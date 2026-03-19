package org.jboss.modcluster.test.utils.balancer;

import org.jboss.modcluster.test.base.BalancerType;
import org.jboss.modcluster.test.utils.ContainerUtils;
import static org.jboss.modcluster.test.utils.ContainerUtils.applyJavaHomeIfNeeded;
import org.jboss.modcluster.test.utils.ImageBuilder;
import org.jboss.modcluster.test.utils.ManagementClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Undertow-based mod_cluster balancer.
 * Uses the same WildFly/EAP ZIP as workers, but configured as a load balancer.
 */
class UndertowBalancerContainer extends BalancerContainer {

    private static final Logger log = LoggerFactory.getLogger(UndertowBalancerContainer.class);

    private static final Address MOD_CLUSTER_FILTER_ADDR = Address.subsystem("undertow")
        .and("configuration", "filter")
        .and("mod-cluster", "modcluster");

    private OnlineManagementClient managementClient;

    private OnlineManagementClient getManagementClient() throws IOException {
        if (managementClient == null) {
            managementClient = ManagementClientFactory.create(
                    container.getHost(), container.getMappedPort(MANAGEMENT_PORT));
        }
        return managementClient;
    }

    @Override
    public void stop() {
        if (managementClient != null) {
            try {
                managementClient.close();
            } catch (Exception e) {
                log.debug("Ignoring error closing management client: {}", e.getMessage());
            }
            managementClient = null;
        }
        super.stop();
    }

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
        Network freshNetwork = Network.newNetwork();
        ownsNetwork = true;
        this.start(freshNetwork, "balancer");
    }

    @Override
    public void start(Network network, String networkAlias) {
        type = BalancerType.UNDERTOW;
        this.network = network;

        Path zipPath = ContainerUtils.getWildFlyZipPath();

        if (zipPath != null && zipPath.toFile().exists()) {
            log.info("Building Undertow balancer from ZIP: {}", zipPath);
            startFromZip(zipPath, networkAlias);
        } else {
            log.info("No ZIP provided, using pre-built Undertow balancer image");
            startFromImage(networkAlias);
        }
    }

    private void startFromZip(Path zipPath, String networkAlias) {
        String imageTag = ImageBuilder.ensureImage(zipPath);

        ContainerUtils.startWithRetry(() -> {
            // Start with balancer configuration in --admin-only mode (like noe-tests)
            // Use standalone.xml (NOT standalone-ha.xml) - we'll configure mod_cluster filter
            container = new GenericContainer<>(imageTag)
                    .withNetwork(network)
                    .withNetworkAliases(networkAlias)
                    .withExposedPorts(HTTP_PORT, HTTPS_PORT, MANAGEMENT_PORT)
                    .withEnv("JAVA_OPTS", System.getProperty("wildfly.java.opts"))
                    .withCommand("/opt/wildfly/bin/standalone.sh",
                                "-Djboss.node.name=" + networkAlias,
                                "-bmanagement", "0.0.0.0",
                                "--admin-only")
                    .waitingFor(Wait.forLogMessage(".*WFLYSRV0025.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(5)))
                    .withLogConsumer(outputFrame ->
                            log.debug("[UNDERTOW-BALANCER-{}] {}", networkAlias.toUpperCase(),
                                    outputFrame.getUtf8String().trim()));

            applyJavaHomeIfNeeded(container);
            container.start();
            log.info("Undertow balancer '{}' started in admin-only mode on network: {}", networkAlias, network.getId());

            // Configure the balancer to act as a balancer (not a worker) - all changes in admin-only mode
            configureAsBalancer();
        }, () -> {
            if (container != null) {
                try {
                    container.close();
                } catch (Exception e) {
                    log.debug("Error during cleanup: {}", e.getMessage());
                }
                container = null;
            }
        }, "Undertow balancer '" + networkAlias + "'");
    }

    /**
     * Configure this WildFly instance to act as a mod_cluster balancer.
     * Uses Creaper Operations API following the order from noe-tests CLILib.
     * Server must be started in --admin-only mode to avoid reload-required state.
     */
    private void configureAsBalancer() {
        try {
            OnlineManagementClient client = ManagementClientFactory.create(
                    container.getHost(), container.getMappedPort(MANAGEMENT_PORT));

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
            ops.add(MOD_CLUSTER_FILTER_ADDR,
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
            OnlineManagementClient readyClient = ManagementClientFactory.create(
                    container.getHost(), container.getMappedPort(MANAGEMENT_PORT));
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

        Operations ops = new Operations(getManagementClient());

        // Get list of balancers
        List<String> balancers = ops.readChildrenNames(MOD_CLUSTER_FILTER_ADDR, "balancer").stringListValue();
        log.debug("Balancers: {}", balancers);

        // For each balancer, get its nodes (workers)
        for (String balancerName : balancers) {
            Address balancerAddr = MOD_CLUSTER_FILTER_ADDR.and("balancer", balancerName);
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

        return workerInfo;
    }

    @Override
    public List<String> getBalancerNames() throws Exception {
        Operations ops = new Operations(getManagementClient());

        List<String> balancers = ops.readChildrenNames(MOD_CLUSTER_FILTER_ADDR, "balancer").stringListValue();
        log.debug("Balancer names: {}", balancers);

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
        Operations ops = new Operations(getManagementClient());

        List<String> balancers = ops.readChildrenNames(MOD_CLUSTER_FILTER_ADDR, "balancer").stringListValue();

        for (String balancerName : balancers) {
            Address balancerAddr = MOD_CLUSTER_FILTER_ADDR.and("balancer", balancerName);
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
                            return result.stringValue();
                        }
                    }
                }
            }
        }

        return null;
    }

    @Override
    public List<String> getRegisteredContexts(String nodeName) throws Exception {
        List<String> result = new ArrayList<>();

        Operations ops = new Operations(getManagementClient());

        List<String> balancers = ops.readChildrenNames(MOD_CLUSTER_FILTER_ADDR, "balancer").stringListValue();

        for (String balancerName : balancers) {
            Address balancerAddr = MOD_CLUSTER_FILTER_ADDR.and("balancer", balancerName);
            List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();

            if (nodes.contains(nodeName)) {
                Address nodeAddr = balancerAddr.and("node", nodeName);
                List<String> contexts = ops.readChildrenNames(nodeAddr, "context").stringListValue();
                result.addAll(contexts);
            }
        }

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
        Operations ops = new Operations(getManagementClient());

        String normalizedPath = contextPath.startsWith("/") ? contextPath : "/" + contextPath;

        List<String> balancers = ops.readChildrenNames(MOD_CLUSTER_FILTER_ADDR, "balancer").stringListValue();

        boolean found = false;
        for (String balancerName : balancers) {
            Address balancerAddr = MOD_CLUSTER_FILTER_ADDR.and("balancer", balancerName);
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

        if (!found) {
            log.warn("Context '{}' not found for node '{}' on balancer (may not be registered)",
                    normalizedPath, nodeName);
        }
    }

    @Override
    public void setMaxRetries(int maxRetries) throws Exception {
        Operations ops = new Operations(getManagementClient());

        ops.writeAttribute(MOD_CLUSTER_FILTER_ADDR, "max-retries", maxRetries).assertSuccess();
        log.info("Set max-retries to {} on Undertow balancer", maxRetries);
    }

    @Override
    public void reload() throws Exception {
        log.info("Reloading Undertow balancer to apply configuration changes");
        new Administration(getManagementClient()).reload();
        managementClient = null;
        log.info("Undertow balancer reloaded successfully");
    }

    /**
     * Invoke an operation on a node in the mod_cluster filter.
     * Finds the node across all balancers and invokes the specified operation.
     */
    private void invokeNodeOperation(String nodeName, String operation) throws Exception {
        Operations ops = new Operations(getManagementClient());

        List<String> balancers = ops.readChildrenNames(MOD_CLUSTER_FILTER_ADDR, "balancer").stringListValue();

        boolean found = false;
        for (String balancerName : balancers) {
            Address balancerAddr = MOD_CLUSTER_FILTER_ADDR.and("balancer", balancerName);
            List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();

            if (nodes.contains(nodeName)) {
                Address nodeAddr = balancerAddr.and("node", nodeName);
                ops.invoke(operation, nodeAddr).assertSuccess();
                log.info("Invoked '{}' on node '{}' in balancer '{}'", operation, nodeName, balancerName);
                found = true;
                break;
            }
        }

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
        Operations ops = new Operations(getManagementClient());

        List<String> balancers = ops.readChildrenNames(MOD_CLUSTER_FILTER_ADDR, "balancer").stringListValue();

        int matchedNodes = 0;
        for (String balancerName : balancers) {
            Address balancerAddr = MOD_CLUSTER_FILTER_ADDR.and("balancer", balancerName);
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
}
