package org.jboss.modcluster.test.utils.balancer;

import org.jboss.modcluster.test.base.BalancerType;
import org.jboss.modcluster.test.utils.CommandResult;
import org.jboss.modcluster.test.utils.TestMode;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Abstract load balancer (Undertow or httpd with mod_cluster).
 * Platform-independent API — Docker and native implementations provide
 * concrete process management, file I/O, and networking.
 */
public abstract class Balancer {

    private static final Logger log = LoggerFactory.getLogger(Balancer.class);

    protected BalancerType type;

    protected static final int HTTP_PORT = 8080;
    protected static final int HTTPS_PORT = 8443;
    protected static final int MCMP_PORT = 8090;
    protected static final int MANAGEMENT_PORT = 9990;

    /**
     * Create a balancer for the given type and current test mode.
     *
     * <p>Dispatches based on the {@code test.mode} system property:
     * <ul>
     *   <li>{@link TestMode#DOCKER} (default): returns Docker-based implementations</li>
     *   <li>{@link TestMode#NATIVE}: returns native OS process implementations</li>
     * </ul>
     *
     * @param type the balancer type (UNDERTOW or HTTPD)
     * @return a new balancer instance for the current test mode
     */
    public static Balancer create(BalancerType type) {
        TestMode mode = TestMode.current();
        switch (type) {
            case UNDERTOW:
                return mode.isNative() ? new NativeUndertowBalancer() : new DockerUndertowBalancer();
            case HTTPD:
                return mode.isNative() ? new NativeHttpdBalancer() : new DockerHttpdBalancer();
            default:
                throw new IllegalArgumentException("Unknown balancer type: " + type);
        }
    }

    // ---- Abstract lifecycle methods ----

    public abstract void start();

    public abstract void stop();

    /**
     * Start this balancer on the same logical network as another balancer.
     * Docker: shares Docker network. Native: everything is on localhost already.
     *
     * @param other existing balancer to share network with
     * @param alias alias for this balancer on the network
     */
    public abstract void startOnSameNetworkAs(Balancer other, String alias);

    // ---- Abstract platform-specific methods ----

    public abstract String getHttpUrl();

    public abstract String getHttpsUrl();

    public abstract String getMcmpUrl();

    public abstract String getInternalHttpUrl();

    /**
     * Get the hostname this balancer is reachable at from workers.
     * Docker: returns the network alias (e.g. "balancer", "balancer2").
     * Native: returns "localhost".
     */
    public abstract String getProxyHost();

    /**
     * Get the management interface host for Creaper connections.
     */
    public abstract String getManagementHost();

    /**
     * Get the management interface port for Creaper connections.
     */
    public abstract int getManagementPort();

    public abstract boolean isRunning();

    /**
     * Get the server home directory path.
     *
     * <p>For WildFly-based balancers (Undertow): returns the WildFly installation root
     * (e.g. {@code "/opt/wildfly"} in Docker, or the extracted path in native mode).
     *
     * <p>For httpd-based balancers: returns the httpd installation root
     * (e.g. {@code "/usr/local/apache2"} in Docker).
     *
     * @return the absolute path to the server home directory
     */
    public abstract String getServerHome();

    /**
     * Get the directory containing the main configuration file (e.g. httpd.conf).
     *
     * <p>For httpd-based balancers the conf directory varies by distribution:
     * {@code /usr/local/apache2/conf} (Docker), {@code httpdHome/etc/httpd/conf} (JBCS Windows).
     * Defaults to {@code getServerHome() + "/conf"}.
     *
     * @return absolute path to the configuration directory
     */
    public String getConfDir() {
        return getServerHome() + "/conf";
    }

    /**
     * Execute a command inside the balancer environment.
     */
    public abstract CommandResult execCommand(String... command) throws Exception;

    /**
     * Copy a classpath resource to the balancer's filesystem.
     */
    public abstract void copyClasspathResource(String classpathResource, String destPath);

    /**
     * Copy a local file to the balancer's filesystem.
     */
    public abstract void copyLocalFile(Path hostPath, String destPath);

    /**
     * Get the balancer's logs.
     */
    public abstract String getLogs();

    // ---- Abstract mod_cluster operations ----

    public abstract int getInternalMcmpPort();

    public abstract int getMcmpSslPort();

    public abstract Map<String, org.jboss.dmr.ModelNode> getWorkerInfo() throws Exception;

    public abstract List<String> getBalancerNames() throws Exception;

    public abstract void disableNode(String nodeName) throws Exception;

    public abstract void stopNode(String nodeName) throws Exception;

    public abstract void enableNode(String nodeName) throws Exception;

    public abstract void removeNode(String nodeName) throws Exception;

    public abstract void disableLoadBalancingGroup(String groupName) throws Exception;

    public abstract void stopLoadBalancingGroup(String groupName) throws Exception;

    public abstract void enableLoadBalancingGroup(String groupName) throws Exception;

    public abstract String getContextStatus(String nodeName, String contextPath) throws Exception;

    public abstract List<String> getRegisteredContexts(String nodeName) throws Exception;

    public abstract void disableContext(String nodeName, String contextPath) throws Exception;

    public abstract void stopContext(String nodeName, String contextPath) throws Exception;

    public abstract void enableContext(String nodeName, String contextPath) throws Exception;

    public abstract void setMaxRetries(int maxRetries) throws Exception;

    public abstract void reload() throws Exception;

    public abstract void enableMcmpSsl();

    // ---- Concrete shared methods ----

    public BalancerType getType() {
        return type;
    }

    /**
     * Get the internal address (host:port) reachable from workers.
     */
    public String getInternalAddress() {
        return getProxyHost() + ":" + HTTP_PORT;
    }

    public void awaitContextRegistered(String nodeName, String contextPath) {
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    List<String> contexts = getRegisteredContexts(nodeName);
                    assertThat(contexts)
                            .as("Context '%s' should be registered for %s", contextPath, nodeName)
                            .contains(contextPath);
                });
    }

    public void awaitContextDeregistered(String nodeName, String contextPath) {
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    List<String> contexts = getRegisteredContexts(nodeName);
                    assertThat(contexts)
                            .as("Context '%s' should no longer be registered for %s", contextPath, nodeName)
                            .doesNotContain(contextPath);
                });
    }
}
