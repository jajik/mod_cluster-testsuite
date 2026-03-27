package org.jboss.modcluster.test.utils.balancer;

import org.jboss.modcluster.test.base.BalancerType;
import org.jboss.modcluster.test.utils.ContainerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Container wrapper for load balancers (Undertow or httpd with mod_cluster).
 */
public abstract class BalancerContainer {

    private static final Logger log = LoggerFactory.getLogger(BalancerContainer.class);

    protected GenericContainer<?> container;
    protected Network network;
    protected String networkAlias;
    protected BalancerType type;
    protected boolean ownsNetwork;
    protected static final int HTTP_PORT = 8080;
    protected static final int HTTPS_PORT = 8443;
    protected static final int MCMP_PORT = 8090;
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
        if (container != null) {
            String containerId = container.getContainerId();

            // Step 1: Disconnect from network FIRST — immediately prevents
            // cross-test MCMP contamination even if stop/remove is slow
            if (containerId != null && network != null) {
                ContainerUtils.retryOnTransientError(() ->
                        container.getDockerClient()
                            .disconnectFromNetworkCmd()
                            .withContainerId(containerId)
                            .withNetworkId(network.getId())
                            .withForce(true)
                            .exec(),
                        "disconnect balancer from network", 3);
            }

            // Step 2: Stop container
            ContainerUtils.retryOnTransientError(() -> {
                if (container.isRunning()) {
                    container.stop();
                    log.debug("Balancer container stopped");
                }
            }, "stop balancer container", 3);

            // Step 3: Remove container
            if (containerId != null) {
                ContainerUtils.retryOnTransientError(() ->
                        container.getDockerClient()
                            .removeContainerCmd(containerId)
                            .withForce(true)
                            .exec(),
                        "remove balancer container", 3);
            }
        }

        // Network cleanup — runs even if container was null (handles start-failure cleanup)
        if (ownsNetwork && network != null) {
            ContainerUtils.disconnectAllFromNetwork(container != null
                    ? container.getDockerClient()
                    : org.testcontainers.DockerClientFactory.instance().client(), network.getId());
            try {
                network.close();
                log.debug("Test network closed");
            } catch (Exception e) {
                log.debug("Error closing test network: {}", e.getMessage());
            }
            network = null;
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
        return "http://" + networkAlias + ":" + HTTP_PORT;
    }

    /**
     * Returns the balancer's internal address (host:port) as seen from within the Docker network.
     * Uses the network alias (e.g. "balancer") rather than the container hostname.
     */
    public String getInternalAddress() {
        return networkAlias + ":" + HTTP_PORT;
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
     * @return 8080 for Undertow (shares HTTP port), 8090 for httpd (dedicated MCMP port)
     */
    public abstract int getInternalMcmpPort();

    /**
     * Get the internal MCMP port used when SSL/TLS is enabled on the MCMP channel.
     * On Undertow, MCMP switches from HTTP (8080) to HTTPS (8443) when SSL is enabled.
     * On httpd, MCMP stays on the same port (8090) with SSL overlaid.
     *
     * @return 8443 for Undertow, 8090 for httpd
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
     * Waits until the given context path is registered on this balancer for the specified node.
     * Polls every 2 seconds, times out after 60 seconds.
     *
     * @param nodeName    the name of the node (e.g., "worker1")
     * @param contextPath the context path to wait for (e.g., "/wildfly-services")
     */
    public void awaitContextRegistered(String nodeName, String contextPath) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    List<String> contexts = getRegisteredContexts(nodeName);
                    assertThat(contexts)
                            .as("Context '%s' should be registered for %s", contextPath, nodeName)
                            .contains(contextPath);
                });
    }

    /**
     * Waits until the given context path is no longer registered on this balancer for the specified node.
     * Polls every 2 seconds, times out after 60 seconds.
     *
     * @param nodeName    the name of the node (e.g., "worker1")
     * @param contextPath the context path to wait for removal (e.g., "/wildfly-services")
     */
    public void awaitContextDeregistered(String nodeName, String contextPath) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    List<String> contexts = getRegisteredContexts(nodeName);
                    assertThat(contexts)
                            .as("Context '%s' should no longer be registered for %s", contextPath, nodeName)
                            .doesNotContain(contextPath);
                });
    }

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
}
