package org.jboss.modcluster.test.utils.balancer;

import org.jboss.modcluster.test.base.BalancerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import java.util.List;
import java.util.Map;

/**
 * Container wrapper for load balancers (Undertow or httpd with mod_cluster).
 */
public abstract class BalancerContainer {

    private static final Logger log = LoggerFactory.getLogger(BalancerContainer.class);

    /**
     * Shared network reused across all tests to avoid exhausting Podman/Docker subnet pools.
     * Ryuk cleans it up on JVM exit. Test isolation comes from fresh containers, not networks.
     */
    private static Network sharedNetwork;

    static synchronized Network getSharedNetwork() {
        if (sharedNetwork == null) {
            sharedNetwork = Network.newNetwork();
            log.info("Created shared test network: {}", sharedNetwork.getId());
        }
        return sharedNetwork;
    }

    protected GenericContainer<?> container;
    protected Network network;
    protected BalancerType type;
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
