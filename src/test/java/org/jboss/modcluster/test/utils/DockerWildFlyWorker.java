package org.jboss.modcluster.test.utils;

import org.jboss.modcluster.test.utils.balancer.Balancer;
import org.jboss.modcluster.test.utils.balancer.DockerBalancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.Duration;

import static java.time.Duration.ofSeconds;
import static java.time.Duration.ofMillis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Docker/Testcontainers-based WildFly worker implementation.
 * Runs WildFly inside a Docker/Podman container.
 */
public class DockerWildFlyWorker extends WildFlyWorker {

    private static final Logger log = LoggerFactory.getLogger(DockerWildFlyWorker.class);

    private static final int HTTP_PORT = 8080;
    private static final int HTTPS_PORT = 8443;
    private static final int MANAGEMENT_PORT = 9990;
    private static final int JGROUPS_TCP_PORT = 7600;
    private static final int JGROUPS_FD_PORT = 57600;

    private GenericContainer<?> container;

    DockerWildFlyWorker(String name, Balancer balancer) {
        super(name, balancer);
    }

    /**
     * Get the underlying Docker container. Only available on Docker-based workers.
     * Use for Docker-specific operations that cannot be expressed through the abstract API.
     */
    public GenericContainer<?> getDockerContainer() {
        return container;
    }

    private DockerBalancer getDockerBalancer() {
        return (DockerBalancer) getBalancer();
    }

    @Override
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

    private void startFromZip(Path zipPath) {
        String imageTag = ImageBuilder.ensureImage(zipPath);
        startFromPreBuiltImage(imageTag);
    }

    private void startFromImage() {
        String wildflyVersion = System.getProperty("wildfly.version", "31.0.1.Final");
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
                    .withNetwork(getDockerBalancer().getNetwork())
                    .withNetworkAliases(getName())
                    .withCreateContainerCmdModifier(cmd -> cmd.withHostName(getName()))
                    .withExposedPorts(HTTP_PORT, HTTPS_PORT, MANAGEMENT_PORT, JGROUPS_TCP_PORT, JGROUPS_FD_PORT)
                    .withEnv("JAVA_OPTS", javaOpts != null ? javaOpts : System.getProperty("wildfly.java.opts"))
                    .withCommand("/opt/wildfly/bin/standalone.sh",
                                "-b", "0.0.0.0",
                                "-bmanagement", "0.0.0.0",
                                "-bprivate", "0.0.0.0",
                                "-Djboss.node.name=" + getName(),
                                "-Djboss.server.default.config=standalone-ha.xml",
                                "-Djboss.modcluster.multicast.address=224.0.1.105",
                                "-Djboss.modcluster.multicast.port=23364")
                    .waitingFor(Wait.forLogMessage(".*WFLYSRV0025.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(5)))
                    .withLogConsumer(outputFrame ->
                            System.out.println("[" + getName().toUpperCase() + "] " + outputFrame.getUtf8String().trim()));

            ContainerUtils.applyJavaHomeIfNeeded(container);
            container.start();
            log.info("WildFly worker '{}' started", getName());

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
        }, "WildFly worker '" + getName() + "'");
    }

    @Override
    public void stop() {
        closeManagementClient();

        if (container != null) {
            String containerId = container.getContainerId();

            // Step 1: Disconnect from network FIRST
            if (containerId != null && getBalancer() != null && getDockerBalancer().getNetwork() != null) {
                ContainerUtils.retryOnTransientError(() ->
                        container.getDockerClient()
                            .disconnectFromNetworkCmd()
                            .withContainerId(containerId)
                            .withNetworkId(getDockerBalancer().getNetwork().getId())
                            .withForce(true)
                            .exec(),
                        "disconnect worker '" + getName() + "' from network", 3);
            }

            // Step 2: Stop container
            ContainerUtils.retryOnTransientError(() -> {
                if (container.isRunning()) {
                    container.stop();
                    log.info("WildFly worker '{}' stopped", getName());
                }
            }, "stop worker '" + getName() + "'", 3);

            // Step 3: Remove container
            if (containerId != null) {
                ContainerUtils.retryOnTransientError(() ->
                        container.getDockerClient()
                            .removeContainerCmd(containerId)
                            .withForce(true)
                            .exec(),
                        "remove worker '" + getName() + "'", 3);
            }

            container = null;
            clearCachedManagers();
        }
    }

    @Override
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
                            getName(), e.getMessage());
                    running = true; // assume running, try to kill
                } else {
                    throw e;
                }
            }

            if (!running) {
                log.info("WildFly worker '{}' container already stopped", getName());
                return;
            }

            String containerId = container.getContainerId();

            // SIGKILL with retry — Podman socket can SIGPIPE transiently
            ContainerUtils.retryOrThrow(() ->
                    container.getDockerClient()
                        .killContainerCmd(containerId)
                        .withSignal("KILL")
                        .exec(),
                    "kill worker '" + getName() + "'", 3);
            log.info("WildFly worker '{}' killed (hard stop)", getName());

            // Verify container is actually dead (Podman may need a moment after SIGKILL)
            await().atMost(ofSeconds(10))
                .pollInterval(ofMillis(500))
                .untilAsserted(() ->
                    assertThat(container.isRunning())
                        .as("Container for worker '%s' should be dead after SIGKILL", getName())
                        .isFalse()
                );
        } finally {
            if (container != null) {
                String containerId = container.getContainerId();

                // Disconnect from network before cleanup — prevents MCMP contamination
                if (containerId != null && getBalancer() != null && getDockerBalancer().getNetwork() != null) {
                    ContainerUtils.retryOnTransientError(() ->
                            container.getDockerClient()
                                .disconnectFromNetworkCmd()
                                .withContainerId(containerId)
                                .withNetworkId(getDockerBalancer().getNetwork().getId())
                                .withForce(true)
                                .exec(),
                            "disconnect killed worker '" + getName() + "' from network", 3);
                }

                // Force-remove the dead container (no need for SIGTERM via stop())
                if (containerId != null) {
                    ContainerUtils.retryOnTransientError(() ->
                            container.getDockerClient()
                                .removeContainerCmd(containerId)
                                .withForce(true)
                                .exec(),
                            "remove killed worker '" + getName() + "'", 3);
                }
            }
            container = null;
            clearCachedManagers();
        }
    }

    @Override
    public boolean isRunning() {
        return container != null && container.isRunning();
    }

    @Override
    public String getHttpUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(HTTP_PORT);
    }

    @Override
    public String getHttpsUrl() {
        return "https://" + container.getHost() + ":" + container.getMappedPort(HTTPS_PORT);
    }

    @Override
    public String getManagementUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(MANAGEMENT_PORT);
    }

    @Override
    public String getInternalHttpUrl() {
        return "http://" + getName() + ":" + HTTP_PORT;
    }

    @Override
    public String getServerHome() {
        return "/opt/wildfly";
    }

    @Override
    public String getTempDirectory() {
        return "/tmp";
    }

    @Override
    public String getProxyHost() {
        return "balancer";
    }

    @Override
    protected String getManagementHost() {
        return container.getHost();
    }

    @Override
    protected int getManagementPort() {
        return container.getMappedPort(MANAGEMENT_PORT);
    }

    @Override
    public CommandResult execCommand(String... command) throws Exception {
        Container.ExecResult result = container.execInContainer(command);
        return new CommandResult(result.getExitCode(), result.getStdout(), result.getStderr());
    }

    @Override
    public void copyClasspathResource(String classpathResource, String destPath) {
        ContainerUtils.retryOrThrow(() ->
                container.copyFileToContainer(
                        MountableFile.forClasspathResource(classpathResource, 0644),
                        destPath),
                "copy classpath resource '" + classpathResource + "' to worker '" + getName() + "'", 5);
    }

    @Override
    public void copyLocalFile(Path hostPath, String destPath) {
        ContainerUtils.retryOrThrow(() ->
                container.copyFileToContainer(
                        MountableFile.forHostPath(hostPath),
                        destPath),
                "copy local file '" + hostPath + "' to worker '" + getName() + "'", 5);
    }

    @Override
    public String readFile(String path) throws Exception {
        Container.ExecResult result = container.execInContainer("cat", path);
        return result.getStdout();
    }

    @Override
    public String getServerLog() throws Exception {
        Container.ExecResult result = container.execInContainer(
            "cat", "/opt/wildfly/standalone/log/server.log");
        return result.getStdout();
    }

    @Override
    public String getServerLog(int lines) throws Exception {
        Container.ExecResult result = container.execInContainer(
            "sh", "-c",
            String.format("tail -%d /opt/wildfly/standalone/log/server.log 2>/dev/null || echo 'Log file not found'", lines));
        return result.getStdout();
    }

    @Override
    public String grepServerLog(String pattern) throws Exception {
        Container.ExecResult result = container.execInContainer(
            "sh", "-c",
            String.format("grep -i '%s' /opt/wildfly/standalone/log/server.log || echo 'No matches found'", pattern));
        return result.getStdout();
    }
}
