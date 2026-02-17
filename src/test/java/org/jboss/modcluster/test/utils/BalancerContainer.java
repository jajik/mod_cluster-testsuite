package org.jboss.modcluster.test.utils;

import org.jboss.modcluster.test.base.BalancerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Container wrapper for load balancers (Undertow or httpd with mod_cluster).
 */
public abstract class BalancerContainer {

    private static final Logger log = LoggerFactory.getLogger(BalancerContainer.class);

    protected GenericContainer<?> container;
    protected Network network;
    protected BalancerType type;

    protected static final int HTTP_PORT = 8080;
    protected static final int HTTPS_PORT = 8443;
    protected static final int MCMP_PORT = 6666;

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

    public void stop() {
        if (container != null && container.isRunning()) {
            container.stop();
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
     * Undertow-based mod_cluster balancer.
     * Uses the same WildFly/EAP ZIP as workers, but configured as a load balancer.
     */
    static class UndertowBalancerContainer extends BalancerContainer {

        @Override
        public void start() {
            type = BalancerType.UNDERTOW;
            network = Network.newNetwork();

            java.nio.file.Path zipPath = getWildFlyZipPath();

            if (zipPath != null && zipPath.toFile().exists()) {
                log.info("Building Undertow balancer from ZIP: {}", zipPath);
                startFromZip(zipPath);
            } else {
                log.info("No ZIP provided, using pre-built Undertow balancer image");
                startFromImage();
            }
        }

        private void startFromZip(Path zipPath) {
            String zipFileName = zipPath.getFileName().toString();

            // Build undertow balancer from same ZIP as workers, but with balancer config
            ImageFromDockerfile image = new ImageFromDockerfile()
                    .withDockerfileFromBuilder(builder -> builder
                            .from("registry.access.redhat.com/ubi9/openjdk-11:latest")
                            .user("root")
                            .run("microdnf install -y unzip && microdnf clean all")
                            .workDir("/opt")
                            .copy(zipFileName, "/opt/" + zipFileName)
                            .run("unzip -q /opt/" + zipFileName + " && rm /opt/" + zipFileName)
                            .run("mv wildfly-* wildfly 2>/dev/null || mv jboss-eap-* wildfly 2>/dev/null || true")
                            .run("chown -R 185:0 /opt/wildfly && chmod -R g+rw /opt/wildfly")
                            .user("185")
                            .env("JBOSS_HOME", "/opt/wildfly")
                            .expose(HTTP_PORT, HTTPS_PORT, MCMP_PORT)
                            .build()
                    )
                    .withFileFromPath(zipFileName, zipPath);

            // Start with balancer configuration (advertise enabled, no workers)
            container = new GenericContainer<>(image)
                    .withNetwork(network)
                    .withNetworkAliases("balancer")
                    .withExposedPorts(HTTP_PORT, HTTPS_PORT, MCMP_PORT)
                    .withCommand("/opt/wildfly/bin/standalone.sh",
                                "-b", "0.0.0.0",
                                "-bmanagement", "0.0.0.0",
                                "-Djboss.node.name=balancer",
                                // Use standalone-ha for mod_cluster subsystem
                                "-Djboss.server.default.config=standalone-ha.xml",
                                // Configure as load balancer (advertise enabled)
                                "-Djboss.modcluster.advertise=true")
                    .waitingFor(Wait.forLogMessage(".*WFLYSRV0025.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(3)))
                    .withLogConsumer(outputFrame ->
                            log.debug("[UNDERTOW-BALANCER] {}", outputFrame.getUtf8String().trim()));

            container.start();
            log.info("Undertow balancer started from ZIP on network: {}", network.getId());
        }

        private void startFromImage() {
            String customImage = System.getProperty("balancer.undertow.image");
            String imageName = customImage != null ? customImage : "quay.io/modcluster/mod_cluster-undertow:latest";

            container = new GenericContainer<>(DockerImageName.parse(imageName))
                    .withNetwork(network)
                    .withNetworkAliases("balancer")
                    .withExposedPorts(HTTP_PORT, HTTPS_PORT, MCMP_PORT)
                    .waitingFor(Wait.forHttp("/").forPort(HTTP_PORT))
                    .withLogConsumer(outputFrame -> log.debug("[UNDERTOW] {}", outputFrame.getUtf8String().trim()));

            container.start();
            log.info("Undertow balancer started from pre-built image on network: {}", network.getId());
        }

        private Path getWildFlyZipPath() {
            String zipPath = System.getProperty("wildfly.zip.path");
            if (zipPath != null) {
                return Paths.get(zipPath);
            }

            zipPath = System.getenv("WILDFLY_ZIP_PATH");
            if (zipPath != null) {
                return Paths.get(zipPath);
            }

            File distDir = new File("distributions");
            if (distDir.exists() && distDir.isDirectory()) {
                File[] zips = distDir.listFiles((dir, name) ->
                    name.startsWith("wildfly-") && name.endsWith(".zip") ||
                    name.startsWith("jboss-eap-") && name.endsWith(".zip"));

                if (zips != null && zips.length > 0) {
                    return zips[0].toPath();
                }
            }

            return null;
        }
    }

    /**
     * Apache httpd with mod_cluster balancer.
     */
    static class HttpdBalancerContainer extends BalancerContainer {

        @Override
        public void start() {
            type = BalancerType.HTTPD;
            network = Network.newNetwork();

            // Try custom image first, fall back to default
            String customImage = System.getProperty("balancer.httpd.image");
            String imageName = customImage != null ? customImage : "quay.io/modcluster/mod_cluster-httpd:latest";

            container = new GenericContainer<>(DockerImageName.parse(imageName))
                    .withNetwork(network)
                    .withNetworkAliases("balancer")
                    .withExposedPorts(HTTP_PORT, HTTPS_PORT, MCMP_PORT)
                    .waitingFor(Wait.forHttp("/").forPort(HTTP_PORT))
                    .withLogConsumer(outputFrame -> log.debug("[HTTPD] {}", outputFrame.getUtf8String().trim()));

            container.start();
            log.info("Httpd balancer started on network: {}", network.getId());
        }
    }
}
