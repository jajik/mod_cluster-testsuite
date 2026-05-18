package org.jboss.modcluster.test.utils.balancer;

import org.jboss.modcluster.test.base.BalancerType;
import org.jboss.modcluster.test.utils.CommandResult;
import org.jboss.modcluster.test.utils.ManagementClientFactory;
import org.jboss.modcluster.test.utils.NativePortAllocator;
import org.jboss.modcluster.test.utils.NativeProcessManager;
import org.jboss.modcluster.test.utils.NativeServerExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Native (non-Docker) Undertow-based mod_cluster balancer.
 *
 * <p>Runs WildFly as a local OS process, configured with a mod_cluster filter.
 * Uses the same Creaper-based configuration as {@link DockerUndertowBalancer},
 * but without any Docker/container dependency.
 *
 * <p>Startup flow:
 * <ol>
 *   <li>Extract WildFly ZIP to {@code target/native-servers/balancer/}</li>
 *   <li>Start WildFly in {@code --admin-only} mode (no port offset for balancer)</li>
 *   <li>Configure mod_cluster filter via Creaper management API</li>
 *   <li>Reload from admin-only to normal mode</li>
 * </ol>
 *
 * <p>MCMP operations are delegated to {@link UndertowBalancerOperations},
 * shared with the Docker implementation.
 *
 * @see DockerUndertowBalancer
 * @see UndertowBalancerOperations
 */
class NativeUndertowBalancer extends Balancer {

    private static final Logger log = LoggerFactory.getLogger(NativeUndertowBalancer.class);

    private static final String STARTUP_PATTERN = "WFLYSRV0025";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

    private String instanceName = "balancer";
    private Path serverHome;
    private NativeProcessManager processManager;
    private UndertowBalancerOperations ops;

    private UndertowBalancerOperations ops() {
        if (ops == null) {
            ops = new UndertowBalancerOperations(
                    () -> new String[]{"localhost", String.valueOf(NativePortAllocator.managementPort(instanceName))});
        }
        return ops;
    }

    @Override
    public void start() {
        type = BalancerType.UNDERTOW;

        try {
            serverHome = NativeServerExtractor.extract(instanceName);
            restoreCleanState();

            List<String> command = buildAdminOnlyCommand();
            processManager = new NativeProcessManager(instanceName, command, serverHome, null);
            processManager.start();
            processManager.waitForStartup(STARTUP_PATTERN, STARTUP_TIMEOUT);

            log.info("Undertow balancer '{}' started in admin-only mode at {}", instanceName, serverHome);

            configureAsBalancer();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start native Undertow balancer '" + instanceName + "'", e);
        }
    }

    @Override
    public void stop() {
        if (ops != null) {
            ops.close();
            ops = null;
        }
        if (processManager != null) {
            processManager.stop();
            processManager = null;
        }
        log.info("Undertow balancer '{}' stopped", instanceName);
    }

    @Override
    public void startOnSameNetworkAs(Balancer other, String alias) {
        instanceName = alias;
        start();
    }

    private List<String> buildAdminOnlyCommand() {
        String script = isWindows() ? "standalone.bat" : "standalone.sh";
        Path scriptPath = serverHome.resolve("bin").resolve(script);

        int offset = NativePortAllocator.offset(instanceName);
        List<String> cmd = new ArrayList<>();
        cmd.add(scriptPath.toAbsolutePath().toString());
        cmd.add("-Djboss.node.name=" + instanceName);
        cmd.add("-bmanagement");
        cmd.add("0.0.0.0");
        if (offset != 0) {
            cmd.add("-Djboss.socket.binding.port-offset=" + offset);
        }
        cmd.add("--admin-only");
        return cmd;
    }

    /**
     * Restore the server to a clean state before each test run.
     * The extraction directory is reused across tests, so previous config changes
     * (socket bindings, mod_cluster filter, interface settings) persist on disk.
     * Restoring original configs and clearing data/tmp prevents stale state.
     *
     * <p>The balancer uses {@code standalone.xml} (no {@code -Djboss.server.default.config}
     * flag), so that file must be restored. Tests like {@code SettingsTest} modify the
     * public interface in {@code standalone.xml} and those changes would corrupt
     * subsequent balancer instances if not reverted.
     */
    private void restoreCleanState() throws IOException {
        Path configDir = serverHome.resolve("standalone/configuration");

        for (String configFile : new String[]{"standalone.xml", "standalone-ha.xml"}) {
            Path backup = configDir.resolve(configFile + ".original");
            Path config = configDir.resolve(configFile);
            if (Files.exists(backup)) {
                Files.copy(backup, config, StandardCopyOption.REPLACE_EXISTING);
                log.info("Restored original {} from backup", configFile);
            }
        }

        // Clear data and tmp directories to remove stale runtime state
        for (String dir : new String[]{"standalone/data", "standalone/tmp",
                "standalone/configuration/standalone_xml_history"}) {
            Path dirPath = serverHome.resolve(dir);
            if (Files.isDirectory(dirPath)) {
                Files.walk(dirPath)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                if (!p.equals(dirPath)) {
                                    Files.delete(p);
                                }
                            } catch (IOException e) {
                                log.warn("Failed to clean {}: {}", p, e.getMessage());
                            }
                        });
                log.debug("Cleared {}", dirPath);
            }
        }
    }

    /**
     * Configure this WildFly instance as a mod_cluster balancer.
     * Mirrors the Docker implementation's {@code configureAsBalancer()} logic.
     */
    private void configureAsBalancer() throws Exception {
        OnlineManagementClient client = ManagementClientFactory.create(
                "localhost", NativePortAllocator.managementPort(instanceName));

        Operations creaperOps = new Operations(client);

        log.info("Configuring Undertow mod_cluster filter on native balancer (admin-only mode)");

        // Multicast socket binding for advertisement
        Address multicastAddr = Address
                .of("socket-binding-group", "standard-sockets")
                .and("socket-binding", "modcluster");

        if (!creaperOps.exists(multicastAddr)) {
            creaperOps.add(multicastAddr,
                    Values.of("port", 0)
                            .and("multicast-address", "224.0.1.105")
                            .and("multicast-port", 23364))
                    .assertSuccess("Failed to add multicast socket binding");
        }

        // Add mod_cluster filter
        if (!creaperOps.exists(UndertowBalancerOperations.MOD_CLUSTER_FILTER_ADDR)) {
            creaperOps.add(UndertowBalancerOperations.MOD_CLUSTER_FILTER_ADDR,
                    Values.of("management-socket-binding", "http")
                            .and("advertise-socket-binding", "modcluster")
                            .and("health-check-interval", Balancer.HEALTH_CHECK_INTERVAL_MS)
                            .and("broken-node-timeout", Balancer.BROKEN_NODE_TIMEOUT_MS)
                            .and("max-retries", 1)
                            .and("failover-strategy", "LOAD_BALANCED"))
                    .assertSuccess("Failed to add mod_cluster filter");
        }

        // Add filter-ref to default-host
        Address filterRefAddr = Address.subsystem("undertow")
                .and("server", "default-server")
                .and("host", "default-host")
                .and("filter-ref", "modcluster");

        if (!creaperOps.exists(filterRefAddr)) {
            creaperOps.add(filterRefAddr).assertSuccess("Failed to add filter-ref");
        }

        // Reload from admin-only to normal mode
        log.info("Reloading from admin-only to normal mode");
        new Administration(client).reload();
        client.close();

        // Wait until running
        OnlineManagementClient readyClient = ManagementClientFactory.create(
                "localhost", NativePortAllocator.managementPort(instanceName));
        new Administration(readyClient).waitUntilRunning();
        readyClient.close();

        log.info("Native Undertow balancer configured successfully. MCMP on HTTP port {}",
                NativePortAllocator.httpPort(instanceName));
    }

    // ---- Networking methods ----

    @Override
    public String getHttpUrl() {
        return "http://localhost:" + NativePortAllocator.httpPort(instanceName);
    }

    @Override
    public String getHttpsUrl() {
        return "https://localhost:" + NativePortAllocator.httpsPort(instanceName);
    }

    @Override
    public String getMcmpUrl() {
        return "http://localhost:" + NativePortAllocator.httpPort(instanceName);
    }

    @Override
    public String getInternalHttpUrl() {
        return "http://localhost:" + NativePortAllocator.httpPort(instanceName);
    }

    @Override
    public String getProxyHost() {
        return "localhost";
    }

    @Override
    public String getManagementHost() {
        return "localhost";
    }

    @Override
    public int getManagementPort() {
        return NativePortAllocator.managementPort(instanceName);
    }

    @Override
    public String getServerHome() {
        return serverHome != null ? serverHome.toAbsolutePath().toString() : null;
    }

    @Override
    public boolean isRunning() {
        return processManager != null && processManager.isRunning();
    }

    @Override
    public int getInternalMcmpPort() {
        return NativePortAllocator.httpPort(instanceName);
    }

    @Override
    public int getMcmpSslPort() {
        return NativePortAllocator.httpsPort(instanceName);
    }

    // ---- File I/O and command execution ----

    @Override
    public CommandResult execCommand(String... command) throws Exception {
        return NativeProcessManager.execCommand(serverHome, command);
    }

    @Override
    public void copyClasspathResource(String classpathResource, String destPath) {
        try {
            Path dest = Path.of(destPath);
            if (!dest.isAbsolute()) {
                dest = serverHome.resolve(destPath);
            }
            Files.createDirectories(dest.getParent());

            URL resource = Thread.currentThread().getContextClassLoader().getResource(classpathResource);
            if (resource == null) {
                throw new RuntimeException("Classpath resource not found: " + classpathResource);
            }

            try (InputStream is = resource.openStream()) {
                Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy classpath resource '" + classpathResource + "'", e);
        }
    }

    @Override
    public void copyLocalFile(Path hostPath, String destPath) {
        try {
            Path dest = Path.of(destPath);
            if (!dest.isAbsolute()) {
                dest = serverHome.resolve(destPath);
            }
            Files.createDirectories(dest.getParent());
            Files.copy(hostPath, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy local file '" + hostPath + "'", e);
        }
    }

    @Override
    public String getLogs() {
        return processManager != null ? processManager.readOutputLog() : "";
    }

    // ---- MCMP operations delegated to UndertowBalancerOperations ----

    @Override
    public Map<String, org.jboss.dmr.ModelNode> getWorkerInfo() throws Exception {
        return ops().getWorkerInfo();
    }

    @Override
    public List<String> getBalancerNames() throws Exception {
        return ops().getBalancerNames();
    }

    @Override
    public void disableNode(String nodeName) throws Exception {
        ops().invokeNodeOperation(nodeName, "disable");
    }

    @Override
    public void stopNode(String nodeName) throws Exception {
        ops().invokeNodeOperation(nodeName, "stop");
    }

    @Override
    public void enableNode(String nodeName) throws Exception {
        ops().invokeNodeOperation(nodeName, "enable");
    }

    @Override
    public void removeNode(String nodeName) throws Exception {
        log.debug("removeNode is a no-op on Undertow balancer (no stale entry issue)");
    }

    @Override
    public void disableLoadBalancingGroup(String groupName) throws Exception {
        ops().invokeGroupOperation(groupName, "disable");
    }

    @Override
    public void stopLoadBalancingGroup(String groupName) throws Exception {
        ops().invokeGroupOperation(groupName, "stop");
    }

    @Override
    public void enableLoadBalancingGroup(String groupName) throws Exception {
        ops().invokeGroupOperation(groupName, "enable");
    }

    @Override
    public String getContextStatus(String nodeName, String contextPath) throws Exception {
        return ops().getContextStatus(nodeName, contextPath);
    }

    @Override
    public List<String> getRegisteredContexts(String nodeName) throws Exception {
        return ops().getRegisteredContexts(nodeName);
    }

    @Override
    public void disableContext(String nodeName, String contextPath) throws Exception {
        ops().invokeContextOperation(nodeName, contextPath, "disable");
    }

    @Override
    public void stopContext(String nodeName, String contextPath) throws Exception {
        ops().invokeContextOperation(nodeName, contextPath, "stop");
    }

    @Override
    public void enableContext(String nodeName, String contextPath) throws Exception {
        ops().invokeContextOperation(nodeName, contextPath, "enable");
    }

    @Override
    public void setMaxRetries(int maxRetries) throws Exception {
        ops().setMaxRetries(maxRetries);
    }

    @Override
    public void reload() throws Exception {
        ops().reload();
    }

    @Override
    public void enableMcmpSsl() {
        log.debug("enableMcmpSsl is a no-op on Undertow balancer (uses Creaper, not McmpClient)");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
