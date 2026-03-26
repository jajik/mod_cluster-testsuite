package org.jboss.modcluster.test.utils.balancer;

import org.jboss.modcluster.test.base.BalancerType;
import org.jboss.modcluster.test.utils.ContainerUtils;
import static org.jboss.modcluster.test.utils.ContainerUtils.applyJavaHomeIfNeeded;
import org.jboss.modcluster.test.utils.ImageBuilder;
import org.jboss.modcluster.test.utils.ManagementClientFactory;
import org.jboss.modcluster.test.utils.TestTimeouts;
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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Docker/Testcontainers-based Undertow mod_cluster balancer.
 *
 * <p>Uses the same WildFly/EAP ZIP as workers, started in {@code --admin-only} mode,
 * then configured with a mod_cluster filter and reloaded to normal mode.
 *
 * <p>MCMP operations (node/context/group management) are delegated to
 * {@link UndertowBalancerOperations}, which is shared with the native implementation.
 */
class DockerUndertowBalancer extends DockerBalancer {

    private static final Logger log = LoggerFactory.getLogger(DockerUndertowBalancer.class);

    private final UndertowBalancerOperations ops = new UndertowBalancerOperations(
            () -> new String[]{
                    container.getHost(),
                    String.valueOf(container.getMappedPort(MANAGEMENT_PORT))
            });

    @Override
    public void stop() {
        ops.close();
        super.stop();
    }

    @Override
    public String getServerHome() {
        return "/opt/wildfly";
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
        this.networkAlias = networkAlias;

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
                            .withStartupTimeout(TestTimeouts.CONTAINER_STARTUP))
                    .withLogConsumer(outputFrame ->
                            log.debug("[UNDERTOW-BALANCER-{}] {}", networkAlias.toUpperCase(),
                                    outputFrame.getUtf8String().trim()));

            applyJavaHomeIfNeeded(container);
            container.start();
            log.info("Undertow balancer '{}' started in admin-only mode on network: {}", networkAlias, network.getId());

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
     * Server must be started in {@code --admin-only} mode.
     */
    private void configureAsBalancer() {
        try {
            OnlineManagementClient client = ManagementClientFactory.create(
                    container.getHost(), container.getMappedPort(MANAGEMENT_PORT));

            Operations creaperOps = new Operations(client);

            log.info("Configuring Undertow mod_cluster filter on balancer (admin-only mode)");

            String containerIp = container.getContainerInfo().getNetworkSettings().getNetworks()
                .values().iterator().next().getIpAddress();
            log.info("Container IP: {}", containerIp);

            Address publicInterfaceAddr = Address.of("interface", "public");
            creaperOps.undefineAttribute(publicInterfaceAddr, "any-address");
            creaperOps.writeAttribute(publicInterfaceAddr, "inet-address", containerIp)
                .assertSuccess("Failed to configure public interface");
            log.info("Public interface configured to: {}", containerIp);

            Address multicastAddr = Address
                    .of("socket-binding-group", "standard-sockets")
                    .and("socket-binding", "modcluster");

            creaperOps.add(multicastAddr,
                Values.of("port", 0)
                    .and("multicast-address", "224.0.1.105")
                    .and("multicast-port", 23364))
                .assertSuccess("Failed to add multicast socket binding");
            log.info("Multicast socket binding created");

            creaperOps.add(UndertowBalancerOperations.MOD_CLUSTER_FILTER_ADDR,
                Values.of("management-socket-binding", "http")
                    .and("advertise-socket-binding", "modcluster")
                    .and("health-check-interval", 5)
                    .and("broken-node-timeout", 10)
                    .and("max-retries", 1)
                    .and("failover-strategy", "LOAD_BALANCED"))
                .assertSuccess("Failed to add mod_cluster filter");
            log.info("Mod_cluster filter created with health checks and failover enabled");

            Address filterRefAddr = Address.subsystem("undertow")
                    .and("server", "default-server")
                    .and("host", "default-host")
                    .and("filter-ref", "modcluster");

            creaperOps.add(filterRefAddr).assertSuccess("Failed to add filter-ref");
            log.info("Filter-ref added to default-host");

            log.info("Reloading server to transition from admin-only to normal mode");
            new Administration(client).reload();
            client.close();

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

    // ---- MCMP operations delegated to UndertowBalancerOperations ----

    @Override
    public Map<String, org.jboss.dmr.ModelNode> getWorkerInfo() throws Exception {
        return ops.getWorkerInfo();
    }

    @Override
    public List<String> getBalancerNames() throws Exception {
        return ops.getBalancerNames();
    }

    @Override
    public void disableNode(String nodeName) throws Exception {
        ops.invokeNodeOperation(nodeName, "disable");
    }

    @Override
    public void stopNode(String nodeName) throws Exception {
        ops.invokeNodeOperation(nodeName, "stop");
    }

    @Override
    public void enableNode(String nodeName) throws Exception {
        ops.invokeNodeOperation(nodeName, "enable");
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
        ops.invokeGroupOperation(groupName, "disable");
    }

    @Override
    public void stopLoadBalancingGroup(String groupName) throws Exception {
        ops.invokeGroupOperation(groupName, "stop");
    }

    @Override
    public void enableLoadBalancingGroup(String groupName) throws Exception {
        ops.invokeGroupOperation(groupName, "enable");
    }

    @Override
    public String getContextStatus(String nodeName, String contextPath) throws Exception {
        return ops.getContextStatus(nodeName, contextPath);
    }

    @Override
    public List<String> getRegisteredContexts(String nodeName) throws Exception {
        return ops.getRegisteredContexts(nodeName);
    }

    @Override
    public void disableContext(String nodeName, String contextPath) throws Exception {
        ops.invokeContextOperation(nodeName, contextPath, "disable");
    }

    @Override
    public void stopContext(String nodeName, String contextPath) throws Exception {
        ops.invokeContextOperation(nodeName, contextPath, "stop");
    }

    @Override
    public void enableContext(String nodeName, String contextPath) throws Exception {
        ops.invokeContextOperation(nodeName, contextPath, "enable");
    }

    @Override
    public void setMaxRetries(int maxRetries) throws Exception {
        ops.setMaxRetries(maxRetries);
    }

    @Override
    public void reload() throws Exception {
        ops.reload();
    }

    private void startFromImage(String networkAlias) {
        String customImage = System.getProperty("balancer.undertow.image");
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
