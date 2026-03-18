package org.jboss.modcluster.test.utils;

import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.OperationException;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

import java.io.IOException;

import org.jboss.modcluster.test.base.BalancerType;

/**
 * Manages ModCluster subsystem configuration for WildFly containers.
 * Handles proxy configuration and attribute management.
 */
public class WildFlyModClusterManager {

    private static final Logger log = LoggerFactory.getLogger(WildFlyModClusterManager.class);

    private final WildFlyContainer container;

    private String mcmpListener = "default";
    private int mcmpPort = -1;
    private String mcmpSslContext;

    WildFlyModClusterManager(WildFlyContainer container) {
        this.container = container;
    }

    /**
     * Configure MCMP channel to use SSL/TLS.
     * Settings persist across reloads since {@link #configureStaticProxy()} uses these values.
     *
     * @param listener Undertow listener name ("default" for HTTP, "https" for HTTPS)
     * @param port port for outbound-socket-binding to balancer
     * @param sslContext Elytron client-ssl-context name for MCMP, or null for plain HTTP
     */
    public void setMcmpSslConfig(final String listener, final int port, final String sslContext) {
        this.mcmpListener = listener;
        this.mcmpPort = port;
        this.mcmpSslContext = sslContext;
        log.info("MCMP SSL config set: listener='{}', port={}, sslContext='{}' on worker '{}'",
                listener, port, sslContext, container.getName());
    }

    /**
     * Configure static proxy connection to the balancer.
     * Creates an outbound-socket-binding and configures mod_cluster to use it.
     * Uses configurable listener, port, and SSL context set via {@link #setMcmpSslConfig}.
     */
    public void configureStaticProxy() {
        try {
            OnlineManagementClient client = container.getManagementClient();
            Operations ops = container.getOperations();

            // Determine effective MCMP port:
            // If mcmpPort was explicitly set via setMcmpSslConfig(), use that value.
            // Otherwise, use the balancer's internal MCMP port (8080 for Undertow, 8090 for httpd).
            int effectiveMcmpPort = (mcmpPort >= 0) ? mcmpPort : container.getBalancer().getInternalMcmpPort();

            // Step 1: Create outbound-socket-binding to balancer
            log.info("Creating outbound-socket-binding for balancer (port={})", effectiveMcmpPort);

            Address socketBindingAddr = Address.of("socket-binding-group", "standard-sockets")
                    .and("remote-destination-outbound-socket-binding", "modcluster-balancer");

            ModelNode addSocketBinding = new ModelNode();
            ModelNode address = addSocketBinding.get("address");
            address.add("socket-binding-group", "standard-sockets");
            address.add("remote-destination-outbound-socket-binding", "modcluster-balancer");
            addSocketBinding.get("operation").set("add");
            addSocketBinding.get("host").set("balancer");
            addSocketBinding.get("port").set(effectiveMcmpPort);

            ModelNode result = client.execute(addSocketBinding);
            if (!result.get("outcome").asString().equals("success")) {
                log.debug("Socket binding may already exist: {}", result.get("failure-description").asString());

                // Update the existing binding to the correct port
                ops.writeAttribute(socketBindingAddr, "port", effectiveMcmpPort).assertSuccess();
                log.info("Updated existing socket binding port to {}", effectiveMcmpPort);
            }

            // Step 2: Set proxy list to use the outbound-socket-binding
            Address mcProxyAddress = Address.subsystem("modcluster").and("proxy", "default");
            ModelNode proxyList = new ModelNode();
            proxyList.add("modcluster-balancer");

            ModelNodeResult writeResult =
                ops.writeAttribute(mcProxyAddress, "proxies", proxyList);
            writeResult.assertSuccess();

            // Step 3: Set listener for MCMP communication
            ModelNodeResult listenerResult =
                ops.writeAttribute(mcProxyAddress, "listener", mcmpListener);
            listenerResult.assertSuccess();

            // Step 4: Tune mod_cluster attributes for httpd balancers
            if (container.getBalancer().getType() == BalancerType.HTTPD) {
                // The 'ping' attribute controls two mod_proxy_cluster worker timeouts:
                //   conn_timeout — TCP connect timeout to backend (how long to wait for SYN-ACK)
                //   ping_timeout — CPING/CPONG health check timeout before forwarding a request
                // Default is 10 seconds, which is too long for failover — httpd hangs on TCP connect
                // to a dead worker for 10s, exceeding the HTTP client's read timeout.
                // 3 seconds gives fast failover while still tolerating normal network latency.
                ops.writeAttribute(mcProxyAddress, "ping", 3).assertSuccess();

                // WildFly defaults max-attempts to 1, which means mod_proxy_cluster only tries one
                // backend per request — no failover to other workers when the sticky target is down.
                // Only override the WildFly default (1); tests may have set a specific value
                // (including 0) that should not be overridden by the reload's configureStaticProxy() call.
                ModelNodeResult currentMaxAttempts = ops.readAttribute(mcProxyAddress, "max-attempts");
                currentMaxAttempts.assertSuccess();
                if (currentMaxAttempts.intValue() == 1) {
                    ops.writeAttribute(mcProxyAddress, "max-attempts", 3).assertSuccess();
                    log.info("Set max-attempts=3, ping=3 for httpd failover on worker '{}'", container.getName());
                } else {
                    log.info("Set ping=3 for httpd failover on worker '{}' (max-attempts={} preserved)",
                            container.getName(), currentMaxAttempts.intValue());
                }
            }

            // Step 5: Set SSL context on mod_cluster proxy if configured
            if (mcmpSslContext != null) {
                ModelNodeResult sslResult =
                    ops.writeAttribute(mcProxyAddress, "ssl-context", mcmpSslContext);
                sslResult.assertSuccess();
                log.info("MCMP SSL context set to '{}' on worker '{}'", mcmpSslContext, container.getName());
            }

            log.info("Mod_cluster static proxy configured successfully on worker '{}' (listener='{}', port={})",
                    container.getName(), mcmpListener, effectiveMcmpPort);

            // Wait for the proxy connection to establish
            Thread.sleep(5000);

        } catch (Exception e) {
            log.error("Failed to configure static proxy on worker '{}'", container.getName(), e);
        }
    }

    /**
     * Read a mod_cluster subsystem attribute.
     *
     * @param attributeName The name of the attribute to read
     * @return The attribute value as a ModelNode
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public ModelNode readModClusterAttribute(String attributeName) throws IOException, OperationException {
        Operations ops = container.getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");
        ModelNodeResult result = ops.readAttribute(modclusterAddress, attributeName);
        result.assertSuccess();
        return result.value();
    }

    /**
     * Write a mod_cluster subsystem attribute and reload if required.
     * All mod_cluster proxy attributes use {@code ReloadRequiredWriteAttributeHandler},
     * so any write puts the server in {@code reload-required} state. This method
     * automatically reloads to apply the change and clear that state.
     *
     * @param attributeName The name of the attribute to write
     * @param value The value to set (supports Boolean, Integer, Long, String, ModelNode)
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void writeModClusterAttribute(String attributeName, Object value) throws IOException, OperationException {
        Operations ops = container.getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");

        ModelNodeResult result;

        // Handle different value types
        if (value instanceof Boolean) {
            result = ops.writeAttribute(modclusterAddress, attributeName, (Boolean) value);
        } else if (value instanceof Integer) {
            result = ops.writeAttribute(modclusterAddress, attributeName, (Integer) value);
        } else if (value instanceof Long) {
            result = ops.writeAttribute(modclusterAddress, attributeName, (Long) value);
        } else if (value instanceof String) {
            result = ops.writeAttribute(modclusterAddress, attributeName, (String) value);
        } else if (value instanceof ModelNode) {
            result = ops.writeAttribute(modclusterAddress, attributeName, (ModelNode) value);
        } else {
            throw new IllegalArgumentException("Unsupported attribute value type: " + value.getClass());
        }

        result.assertSuccess();
        log.info("Set mod_cluster attribute '{}' to '{}' on worker '{}'", attributeName, value, container.getName());

        try {
            if (container.getAdministration().isReloadRequired()) {
                container.reloadServer();
                log.info("Reloaded worker '{}' to apply mod_cluster attribute '{}'", container.getName(), attributeName);
            }
        } catch (Exception e) {
            throw new IOException("Failed to reload after writing attribute '" + attributeName + "'", e);
        }
    }

    /**
     * Set the balancer name this worker registers under on the balancer.
     * Controls which load-balancing group the worker belongs to.
     * Requires a server reload to take effect.
     *
     * @param balancerName the balancer group name (e.g., "balancerXXX1")
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void setBalancerName(String balancerName) throws IOException, OperationException {
        writeModClusterAttribute("balancer", balancerName);
        log.info("Set balancer name to '{}' on worker '{}'", balancerName, container.getName());
    }

    /**
     * Set the session draining strategy on this worker's mod_cluster subsystem.
     * Controls whether sessions are drained before stopping a context.
     *
     * @param strategy The strategy to use: "ALWAYS", "NEVER", or "DEFAULT"
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void setSessionDrainingStrategy(String strategy) throws IOException, OperationException {
        writeModClusterAttribute("session-draining-strategy", strategy);
        log.info("Set session-draining-strategy to '{}' on worker '{}'", strategy, container.getName());
    }

    /**
     * Disable a context on this worker. The context will reject new sessions
     * but continue serving existing sessions.
     *
     * @param contextPath Context path (e.g., "demo" or "/demo")
     * @param virtualHost Virtual host name (e.g., "default-host")
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void disableContext(String contextPath, String virtualHost)
        throws IOException, OperationException {
        Operations ops = container.getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");

        // Normalize context path to have leading slash
        String normalizedContext = contextPath.startsWith("/") ? contextPath : "/" + contextPath;

        ModelNodeResult result = ops.invoke(
            "disable-context",
            modclusterAddress,
            Values.of("context", normalizedContext)
                  .and("virtualhost", virtualHost)
        );

        result.assertSuccess();
        log.info("Disabled context '{}' on virtualhost '{}' for worker '{}'",
                 normalizedContext, virtualHost, container.getName());
    }

    /**
     * Enable a previously disabled context on this worker.
     *
     * @param contextPath Context path (e.g., "demo" or "/demo")
     * @param virtualHost Virtual host name (e.g., "default-host")
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void enableContext(String contextPath, String virtualHost)
        throws IOException, OperationException {
        Operations ops = container.getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");

        String normalizedContext = contextPath.startsWith("/") ? contextPath : "/" + contextPath;

        ModelNodeResult result = ops.invoke(
            "enable-context",
            modclusterAddress,
            Values.of("context", normalizedContext)
                  .and("virtualhost", virtualHost)
        );

        result.assertSuccess();
        log.info("Enabled context '{}' on virtualhost '{}' for worker '{}'",
                 normalizedContext, virtualHost, container.getName());
    }

    /**
     * Stop a context on this worker. The context will drain sessions
     * according to stop-context-timeout and session-draining-strategy.
     *
     * @param contextPath Context path (e.g., "demo" or "/demo")
     * @param virtualHost Virtual host name (e.g., "default-host")
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void stopContext(String contextPath, String virtualHost)
        throws IOException, OperationException {
        Operations ops = container.getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");

        String normalizedContext = contextPath.startsWith("/") ? contextPath : "/" + contextPath;

        ModelNodeResult result = ops.invoke(
            "stop-context",
            modclusterAddress,
            Values.of("context", normalizedContext)
                  .and("virtualhost", virtualHost)
        );

        result.assertSuccess();
        log.info("Stopped context '{}' on virtualhost '{}' for worker '{}'",
                 normalizedContext, virtualHost, container.getName());
    }

    /**
     * Disable this node via mod_cluster CLI operation.
     * The node will not receive new requests but will continue serving existing sessions.
     *
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void disableNode() throws IOException, OperationException {
        Operations ops = container.getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");

        ModelNodeResult result = ops.invoke("disable", modclusterAddress);
        result.assertSuccess();
        log.info("Disabled node '{}' via mod_cluster CLI", container.getName());
    }

    /**
     * Stop this node via mod_cluster CLI operation.
     * The node will drain sessions and then stop accepting requests.
     *
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void stopNode() throws IOException, OperationException {
        Operations ops = container.getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");

        ModelNodeResult result = ops.invoke("stop", modclusterAddress);
        result.assertSuccess();
        log.info("Stopped node '{}' via mod_cluster CLI", container.getName());
    }

    /**
     * Enable this node via mod_cluster CLI operation.
     * The node will start receiving new requests again.
     *
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void enableNode() throws IOException, OperationException {
        Operations ops = container.getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");

        ModelNodeResult result = ops.invoke("enable", modclusterAddress);
        result.assertSuccess();
        log.info("Enabled node '{}' via mod_cluster CLI", container.getName());
    }

    /**
     * Set the load-balancing-group for this worker.
     * Controls which load-balancing group the worker belongs to within a balancer.
     * Requires a server reload to take effect.
     *
     * @param groupName the load-balancing group name (e.g., "groupOne")
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void setLoadBalancingGroup(String groupName) throws IOException, OperationException {
        writeModClusterAttribute("load-balancing-group", groupName);
        log.info("Set load-balancing-group to '{}' on worker '{}'", groupName, container.getName());
    }

    /**
     * Set the sticky-session-force attribute on this worker's mod_cluster subsystem.
     * When true, the balancer returns 503 instead of failover when the sticky node is down.
     * Requires a server reload to take effect.
     *
     * @param force whether to force sticky session (true returns 503, false allows failover)
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void setStickySessionForce(boolean force) throws IOException, OperationException {
        writeModClusterAttribute("sticky-session-force", force);
        log.info("Set sticky-session-force to '{}' on worker '{}'", force, container.getName());
    }

    /**
     * Set the sticky-session attribute on this worker's mod_cluster subsystem.
     * When true, requests with existing sessions are routed to the same worker.
     * Requires a server reload to take effect.
     *
     * @param sticky whether to enable sticky sessions
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void setStickySession(boolean sticky) throws IOException, OperationException {
        writeModClusterAttribute("sticky-session", sticky);
        log.info("Set sticky-session to '{}' on worker '{}'", sticky, container.getName());
    }

    /**
     * Set the sticky-session-remove attribute on this worker's mod_cluster subsystem.
     * When true, session cookies are removed on failover.
     * Requires a server reload to take effect.
     *
     * @param remove whether to remove session cookies on failover
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void setStickySessionRemove(boolean remove) throws IOException, OperationException {
        writeModClusterAttribute("sticky-session-remove", remove);
        log.info("Set sticky-session-remove to '{}' on worker '{}'", remove, container.getName());
    }

    /**
     * Set the max-attempts attribute on this worker's mod_cluster subsystem.
     * Controls how many times the balancer will retry a request on different workers.
     * Requires a server reload to take effect.
     *
     * @param maxAttempts the maximum number of retry attempts
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void setMaxAttempts(int maxAttempts) throws IOException, OperationException {
        writeModClusterAttribute("max-attempts", maxAttempts);
        log.info("Set max-attempts to '{}' on worker '{}'", maxAttempts, container.getName());
    }

    /**
     * Disconnect this worker from all configured proxies.
     * Clears the proxies list and disables advertise to prevent automatic discovery.
     * After a subsequent reload, the worker will start without any proxy connection,
     * allowing the balancer's broken-node-timeout to clear old node/context registrations.
     * Call {@link #configureStaticProxy()} to re-establish the proxy connection.
     *
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void disconnectFromProxy() throws IOException, OperationException {
        ModelNode emptyList = new ModelNode();
        emptyList.setEmptyList();
        writeModClusterAttribute("proxies", emptyList);
        writeModClusterAttribute("advertise", false);
        log.info("Disconnected worker '{}' from proxy (cleared proxies, disabled advertise)", container.getName());
    }

    /**
     * Set the node-timeout attribute on this worker's mod_cluster subsystem.
     * Time in seconds to wait for a response from a backend node before timing out.
     * Requires a server reload to take effect.
     *
     * @param timeoutSeconds timeout in seconds
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void setNodeTimeout(int timeoutSeconds) throws IOException, OperationException {
        writeModClusterAttribute("node-timeout", timeoutSeconds);
        log.info("Set node-timeout to '{}' on worker '{}'", timeoutSeconds, container.getName());
    }
}
