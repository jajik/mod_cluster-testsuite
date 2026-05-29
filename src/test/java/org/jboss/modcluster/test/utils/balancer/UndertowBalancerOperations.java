package org.jboss.modcluster.test.utils.balancer;

import org.jboss.modcluster.test.utils.ManagementClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.ReadResourceOption;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creaper-based MCMP operations for an Undertow mod_cluster balancer.
 *
 * <p>This helper encapsulates all management model operations used to query and control
 * the Undertow mod_cluster filter: listing workers, enabling/disabling/stopping nodes,
 * managing contexts, load-balancing groups, and retries. It operates through the WildFly
 * management API (Creaper) and is independent of the deployment platform.
 *
 * <p>Both {@link DockerUndertowBalancer} and {@code NativeUndertowBalancer} compose this
 * helper to avoid duplicating the ~300 lines of Creaper operations. The caller provides
 * management host and port; this class manages the Creaper client lifecycle.
 *
 * <p>Thread safety: instances are not thread-safe. The management client is lazily
 * created and cached until explicitly invalidated via {@link #invalidateClient()}.
 *
 * @see DockerUndertowBalancer
 */
class UndertowBalancerOperations {

    private static final Logger log = LoggerFactory.getLogger(UndertowBalancerOperations.class);

    /** Management model address for the mod_cluster filter. */
    static final Address MOD_CLUSTER_FILTER_ADDR = Address.subsystem("undertow")
            .and("configuration", "filter")
            .and("mod-cluster", "modcluster");


    private final ManagementHostProvider hostProvider;
    private OnlineManagementClient managementClient;

    /**
     * Provides the management host and port for Creaper connections.
     *
     * <p>This is a callback interface because Docker-based balancers resolve the
     * host/port from container port mappings (which change on restart), while
     * native balancers use fixed localhost + port.
     */
    @FunctionalInterface
    interface ManagementHostProvider {
        /**
         * Get the management connection info.
         *
         * @return a two-element array: {@code [host, portString]}
         */
        String[] getHostAndPort();
    }

    /**
     * Create a new operations helper.
     *
     * @param hostProvider callback that supplies the management host and port
     */
    UndertowBalancerOperations(ManagementHostProvider hostProvider) {
        this.hostProvider = hostProvider;
    }

    /**
     * Get or create the Creaper management client.
     *
     * @return an active management client
     * @throws IOException if the connection cannot be established
     */
    private OnlineManagementClient getManagementClient() throws IOException {
        if (managementClient == null) {
            String[] hp = hostProvider.getHostAndPort();
            managementClient = ManagementClientFactory.create(hp[0], Integer.parseInt(hp[1]));
        }
        return managementClient;
    }

    /**
     * Close and discard the cached management client.
     * Call after a reload or stop to force reconnection on next use.
     */
    void invalidateClient() {
        if (managementClient != null) {
            try {
                managementClient.close();
            } catch (Exception e) {
                log.debug("Ignoring error closing management client: {}", e.getMessage());
            }
            managementClient = null;
        }
    }

    /**
     * Close the cached management client without discarding it silently.
     * Used during shutdown to release resources.
     */
    void close() {
        invalidateClient();
    }

    /**
     * Get information about all registered worker nodes.
     *
     * <p>Iterates over all balancers and their nodes in the mod_cluster filter,
     * reading runtime resource attributes for each node.
     *
     * @return map of node name to its management model resource
     * @throws Exception if any management operation fails
     */
    Map<String, org.jboss.dmr.ModelNode> getWorkerInfo() throws Exception {
        Map<String, org.jboss.dmr.ModelNode> workerInfo = new HashMap<>();
        Operations ops = new Operations(getManagementClient());

        List<String> balancers = ops.readChildrenNames(MOD_CLUSTER_FILTER_ADDR, "balancer").stringListValue();
        log.debug("Balancers: {}", balancers);

        for (String balancerName : balancers) {
            Address balancerAddr = MOD_CLUSTER_FILTER_ADDR.and("balancer", balancerName);
            List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();
            log.debug("Balancer '{}' has nodes: {}", balancerName, nodes);

            for (String nodeName : nodes) {
                Address nodeAddr = balancerAddr.and("node", nodeName);
                ModelNodeResult result = ops.readResource(nodeAddr, ReadResourceOption.INCLUDE_RUNTIME);

                if (result.isSuccess()) {
                    org.jboss.dmr.ModelNode nodeInfo = result.value();
                    workerInfo.put(nodeName, nodeInfo);
                    log.debug("Node '{}' info: {}", nodeName, nodeInfo.toJSONString(false));
                }
            }
        }

        return workerInfo;
    }

    /**
     * Get the names of all balancers registered in the mod_cluster filter.
     *
     * @return list of balancer names
     * @throws Exception if the management operation fails
     */
    List<String> getBalancerNames() throws Exception {
        Operations ops = new Operations(getManagementClient());
        List<String> balancers = ops.readChildrenNames(MOD_CLUSTER_FILTER_ADDR, "balancer").stringListValue();
        log.debug("Balancer names: {}", balancers);
        return balancers;
    }

    /**
     * Invoke an operation (enable/disable/stop) on a specific node.
     *
     * @param nodeName  the node name to operate on
     * @param operation the operation name ("enable", "disable", or "stop")
     * @throws Exception if the node is not found or the operation fails
     */
    void invokeNodeOperation(String nodeName, String operation) throws Exception {
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
     *
     * <p>The Undertow management model does not support operations directly on
     * load-balancing-group resources, so this iterates over all nodes and invokes
     * the operation on each node whose {@code load-balancing-group} attribute matches.
     *
     * @param groupName the load-balancing group name
     * @param operation the operation name ("enable", "disable", or "stop")
     * @throws Exception if no nodes are found in the group or any operation fails
     */
    void invokeGroupOperation(String groupName, String operation) throws Exception {
        Operations ops = new Operations(getManagementClient());

        List<String> balancers = ops.readChildrenNames(MOD_CLUSTER_FILTER_ADDR, "balancer").stringListValue();

        int matchedNodes = 0;
        for (String balancerName : balancers) {
            Address balancerAddr = MOD_CLUSTER_FILTER_ADDR.and("balancer", balancerName);
            List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();

            for (String nodeName : nodes) {
                Address nodeAddr = balancerAddr.and("node", nodeName);
                ModelNodeResult groupResult = ops.readAttribute(nodeAddr, "load-balancing-group");

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

    /**
     * Get the status of a specific context on a node.
     *
     * @param nodeName    the node name
     * @param contextPath the context path (with or without leading slash)
     * @return the context status string, or {@code null} if not found
     * @throws Exception if any management operation fails
     */
    String getContextStatus(String nodeName, String contextPath) throws Exception {
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
                            ModelNodeResult result = ops.readAttribute(contextAddr, "status");
                            return result.stringValue();
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Get all registered contexts for a specific node.
     *
     * @param nodeName the node name
     * @return list of context paths registered for the node
     * @throws Exception if any management operation fails
     */
    List<String> getRegisteredContexts(String nodeName) throws Exception {
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

    /**
     * Invoke an operation on a specific context of a node.
     *
     * @param nodeName    the node name
     * @param contextPath the context path (with or without leading slash)
     * @param operation   the operation name ("enable", "disable", or "stop")
     * @throws Exception if any management operation fails
     */
    void invokeContextOperation(String nodeName, String contextPath, String operation) throws Exception {
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

    /**
     * Set the {@code max-retries} attribute on the mod_cluster filter.
     *
     * @param maxRetries the maximum number of failover retries
     * @throws Exception if the management operation fails
     */
    void setMaxRetries(int maxRetries) throws Exception {
        Operations ops = new Operations(getManagementClient());
        ops.writeAttribute(MOD_CLUSTER_FILTER_ADDR, "max-retries", maxRetries).assertSuccess();
        log.info("Set max-retries to {} on Undertow balancer", maxRetries);
    }

    /**
     * Reload the Undertow balancer via the management API.
     * Invalidates the cached management client since reload drops the connection.
     *
     * @throws Exception if the reload fails
     */
    void reload() throws Exception {
        log.info("Reloading Undertow balancer to apply configuration changes");
        new Administration(getManagementClient()).reload();
        managementClient = null;
        log.info("Undertow balancer reloaded successfully");
    }
}
