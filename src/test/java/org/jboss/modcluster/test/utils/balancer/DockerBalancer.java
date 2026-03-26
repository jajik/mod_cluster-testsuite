package org.jboss.modcluster.test.utils.balancer;

import org.jboss.modcluster.test.utils.CommandResult;
import org.jboss.modcluster.test.utils.ContainerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

/**
 * Docker/Testcontainers-based intermediate abstract balancer.
 * Holds Docker-specific fields (container, network) and implements
 * platform-specific methods from {@link Balancer} using Docker APIs.
 */
public abstract class DockerBalancer extends Balancer {

    private static final Logger log = LoggerFactory.getLogger(DockerBalancer.class);

    protected GenericContainer<?> container;
    protected Network network;
    protected boolean ownsNetwork;
    protected String networkAlias;

    /**
     * Get the underlying Docker container.
     * Only available on Docker-based balancers.
     */
    public GenericContainer<?> getDockerContainer() {
        return container;
    }

    /**
     * Get the Docker network.
     * Only available on Docker-based balancers.
     */
    public Network getNetwork() {
        return network;
    }

    /**
     * Start the balancer on an existing Docker network with a custom alias.
     * Used when multiple balancers share the same Docker network.
     */
    public abstract void start(Network network, String networkAlias);

    @Override
    public void startOnSameNetworkAs(Balancer other, String alias) {
        if (!(other instanceof DockerBalancer)) {
            throw new IllegalArgumentException("Cannot share network with non-Docker balancer");
        }
        DockerBalancer dockerOther = (DockerBalancer) other;
        start(dockerOther.getNetwork(), alias);
    }

    @Override
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

    @Override
    public String getHttpUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(HTTP_PORT);
    }

    @Override
    public String getHttpsUrl() {
        return "https://" + container.getHost() + ":" + container.getMappedPort(HTTPS_PORT);
    }

    @Override
    public String getMcmpUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(MCMP_PORT);
    }

    @Override
    public String getInternalHttpUrl() {
        return "http://" + container.getContainerInfo().getConfig().getHostName() + ":" + HTTP_PORT;
    }

    @Override
    public String getProxyHost() {
        return networkAlias;
    }

    @Override
    public String getManagementHost() {
        return container.getHost();
    }

    @Override
    public int getManagementPort() {
        return container.getMappedPort(MANAGEMENT_PORT);
    }

    @Override
    public boolean isRunning() {
        return container != null && container.isRunning();
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
                "copy classpath resource '" + classpathResource + "' to balancer", 5);
    }

    @Override
    public void copyLocalFile(Path hostPath, String destPath) {
        ContainerUtils.retryOrThrow(() ->
                container.copyFileToContainer(
                        MountableFile.forHostPath(hostPath.toAbsolutePath().toString(), 0644),
                        destPath),
                "copy local file '" + hostPath + "' to balancer", 5);
    }

    @Override
    public String getLogs() {
        return container != null ? container.getLogs() : "";
    }
}
