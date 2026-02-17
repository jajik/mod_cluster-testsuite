package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Container wrapper for WildFly/EAP workers with mod_cluster subsystem.
 * Builds container from ZIP distribution.
 */
public class WildFlyContainer {

    private static final Logger log = LoggerFactory.getLogger(WildFlyContainer.class);

    private static final int HTTP_PORT = 8080;
    private static final int HTTPS_PORT = 8443;
    private static final int MANAGEMENT_PORT = 9990;

    private final String name;
    private final BalancerContainer balancer;
    private GenericContainer<?> container;

    public WildFlyContainer(String name, BalancerContainer balancer) {
        this.name = name;
        this.balancer = balancer;
    }

    public void start() {
        Path zipPath = getWildFlyZipPath();

        if (zipPath != null && zipPath.toFile().exists()) {
            log.info("Building WildFly container from ZIP: {}", zipPath);
            startFromZip(zipPath);
        } else {
            log.info("No ZIP provided, using pre-built container image");
            startFromImage();
        }
    }

    /**
     * Start WildFly from a ZIP distribution (WildFly or EAP).
     */
    private void startFromZip(Path zipPath) {
        String zipFileName = zipPath.getFileName().toString();

        // Build custom image from ZIP
        ImageFromDockerfile image = new ImageFromDockerfile()
                .withDockerfileFromBuilder(builder -> builder
                        .from("registry.access.redhat.com/ubi9/openjdk-11:latest")
                        .user("root")
                        .run("microdnf install -y unzip && microdnf clean all")
                        .workDir("/opt")
                        .copy(zipFileName, "/opt/" + zipFileName)
                        .run("unzip -q /opt/" + zipFileName + " && rm /opt/" + zipFileName)
                        // Find the extracted directory (could be wildfly-* or jboss-eap-*)
                        .run("mv wildfly-* wildfly 2>/dev/null || mv jboss-eap-* wildfly 2>/dev/null || true")
                        .run("chown -R 185:0 /opt/wildfly && chmod -R g+rw /opt/wildfly")
                        .user("185")
                        .env("JBOSS_HOME", "/opt/wildfly")
                        .expose(HTTP_PORT, HTTPS_PORT, MANAGEMENT_PORT)
                        .build()
                )
                .withFileFromPath(zipFileName, zipPath);

        startWithImage(image);
    }

    /**
     * Start WildFly from pre-built container image (fallback).
     */
    private void startFromImage() {
        String wildflyVersion = System.getProperty("wildfly.version", "31.0.1.Final");
        ImageFromDockerfile image = new ImageFromDockerfile()
                .withDockerfileFromBuilder(builder -> builder
                        .from("quay.io/wildfly/wildfly:" + wildflyVersion)
                        .build()
                );

        startWithImage(image);
    }

    /**
     * Start container with given image.
     */
    private void startWithImage(ImageFromDockerfile image) {
        container = new GenericContainer<>(image)
                .withNetwork(balancer.getNetwork())
                .withNetworkAliases(name)
                .withExposedPorts(HTTP_PORT, HTTPS_PORT, MANAGEMENT_PORT)
                .withEnv("WILDFLY_MODCLUSTER_PROXY_LIST", "balancer:" + 6666)
                .withCommand("/opt/wildfly/bin/standalone.sh",
                            "-b", "0.0.0.0",
                            "-bmanagement", "0.0.0.0",
                            "-Djboss.node.name=" + name,
                            "-Djboss.server.default.config=standalone-ha.xml")
                .waitingFor(Wait.forLogMessage(".*WFLYSRV0025.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(3)))
                .withLogConsumer(outputFrame ->
                        log.debug("[{}] {}", name.toUpperCase(), outputFrame.getUtf8String().trim()));

        container.start();
        log.info("WildFly worker '{}' started", name);
    }

    /**
     * Get WildFly ZIP path from system property or environment variable.
     * Priority:
     * 1. System property: wildfly.zip.path
     * 2. Environment variable: WILDFLY_ZIP_PATH
     * 3. Convention: distributions/wildfly-*.zip or distributions/jboss-eap-*.zip
     */
    private Path getWildFlyZipPath() {
        // Check system property
        String zipPath = System.getProperty("wildfly.zip.path");
        if (zipPath != null) {
            return Paths.get(zipPath);
        }

        // Check environment variable
        zipPath = System.getenv("WILDFLY_ZIP_PATH");
        if (zipPath != null) {
            return Paths.get(zipPath);
        }

        // Check conventional location
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

    public void stop() {
        if (container != null && container.isRunning()) {
            container.stop();
            log.info("WildFly worker '{}' stopped", name);
        }
    }

    public String getHttpUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(HTTP_PORT);
    }

    public String getHttpsUrl() {
        return "https://" + container.getHost() + ":" + container.getMappedPort(HTTPS_PORT);
    }

    public String getManagementUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(MANAGEMENT_PORT);
    }

    public String getInternalHttpUrl() {
        return "http://" + name + ":" + HTTP_PORT;
    }

    public String getName() {
        return name;
    }

    public GenericContainer<?> getContainer() {
        return container;
    }

    /**
     * Execute a CLI command on this WildFly instance.
     */
    public String executeCli(String command) throws Exception {
        var execResult = container.execInContainer(
                "/opt/jboss/wildfly/bin/jboss-cli.sh",
                "--connect",
                "--controller=localhost:9990",
                "--command=" + command
        );

        if (execResult.getExitCode() != 0) {
            throw new RuntimeException("CLI command failed: " + execResult.getStderr());
        }

        return execResult.getStdout();
    }

    /**
     * Deploy an application to this worker.
     */
    public void deploy(File deploymentFile) throws Exception {
        container.copyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(deploymentFile.toPath()),
                "/opt/jboss/wildfly/standalone/deployments/" + deploymentFile.getName()
        );

        // Wait for deployment
        log.info("Deploying {} to worker '{}'", deploymentFile.getName(), name);
        Thread.sleep(5000); // Simple wait, can be improved with proper deployment checking
    }

    /**
     * Undeploy an application from this worker.
     */
    public void undeploy(String deploymentName) throws Exception {
        executeCli("undeploy " + deploymentName);
        log.info("Undeployed {} from worker '{}'", deploymentName, name);
    }
}
