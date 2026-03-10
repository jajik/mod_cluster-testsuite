package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.OperationException;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.commands.deployments.Deploy;
import org.wildfly.extras.creaper.commands.deployments.Undeploy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Manages application deployment lifecycle for WildFly containers.
 * Handles deployment, undeployment, and deployment status checks.
 */
public class WildFlyDeploymentManager {

    public static final String DEMO_APP = "demo";

    private static final Logger log = LoggerFactory.getLogger(WildFlyDeploymentManager.class);
    private static final int MAX_DEPLOY_RETRIES = 5;
    private static final long DEPLOY_RETRY_BASE_DELAY_MS = 2000;

    private final WildFlyContainer container;

    WildFlyDeploymentManager(WildFlyContainer container) {
        this.container = container;
    }

    /**
     * Deploy an application to this worker using Creaper.
     *
     * @param deploymentFile The deployment file to deploy
     * @throws Exception if deployment fails
     */
    public void deploy(File deploymentFile) throws Exception {
        log.info("Deploying {} to worker '{}' using Creaper", deploymentFile.getName(), container.getName());

        OnlineManagementClient client = container.getManagementClient();

        // Deploy using Creaper deployment command
        client.apply(new Deploy.Builder(deploymentFile).build());

        log.info("Deployment {} succeeded on worker '{}'", deploymentFile.getName(), container.getName());
    }

    /**
     * Deploy an application with a custom deployment name.
     * Useful for deploying the same WAR file multiple times with different context paths.
     * Retries on transient deployment failures (e.g., distributable.route-locator not yet available).
     *
     * @param deploymentFile The deployment file to deploy
     * @param deploymentName The name under which to deploy (e.g., "app1.war" creates /app1 context)
     * @throws Exception if deployment fails after all retries
     */
    public void deploy(final File deploymentFile, final String deploymentName) throws Exception {
        log.info("Deploying {} as {} to worker '{}' using Creaper",
            deploymentFile.getName(), deploymentName, container.getName());

        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_DEPLOY_RETRIES; attempt++) {
            try {
                OnlineManagementClient client = container.getManagementClient();
                final FileInputStream fis = new FileInputStream(deploymentFile);
                final Deploy deployCommand = new Deploy.Builder(fis, deploymentName, true).build();
                client.apply(deployCommand);
                log.info("Deployment {} succeeded on worker '{}'{}",
                        deploymentName, container.getName(),
                        attempt > 1 ? " (attempt " + attempt + ")" : "");
                return;
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_DEPLOY_RETRIES && isTransientDeploymentError(e)) {
                    long delayMs = DEPLOY_RETRY_BASE_DELAY_MS * attempt;
                    log.warn("Transient deployment failure for '{}' on attempt {}/{}, retrying after {}ms: {}",
                            deploymentName, attempt, MAX_DEPLOY_RETRIES, delayMs, e.getMessage());
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during deployment retry backoff", ie);
                    }
                } else {
                    break;
                }
            }
        }

        throw lastException;
    }

    private static boolean isTransientDeploymentError(Exception e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("route-locator")
                || msg.contains("rolled back")
                || msg.contains("WFLYCTL0412"));
    }

    /**
     * Undeploy an application from this worker using Creaper.
     *
     * @param deploymentName The name of the deployment to undeploy
     * @throws Exception if undeployment fails
     */
    public void undeploy(String deploymentName) throws Exception {
        log.info("Undeploying {} from worker '{}'", deploymentName, container.getName());

        OnlineManagementClient client = container.getManagementClient();
        client.apply(new Undeploy.Builder(deploymentName).build());

        log.info("Undeployed {} from worker '{}'", deploymentName, container.getName());
    }

    /**
     * Check if a deployment exists and is enabled.
     *
     * @param deploymentName The name of the deployment to check
     * @return true if the deployment exists and is enabled, false otherwise
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public boolean isDeployed(String deploymentName) throws IOException, OperationException {
        Operations ops = container.getOperations();
        Address deploymentAddress = Address.deployment(deploymentName);

        if (!ops.exists(deploymentAddress)) {
            return false;
        }

        ModelNodeResult result = ops.readAttribute(deploymentAddress, "enabled");
        result.assertSuccess();
        return result.value().asBoolean();
    }

    /**
     * Deploy the demo application for testing.
     * Copies demo.war from resources and deploys it to the worker.
     * Checks if already deployed to avoid duplicate deployment errors.
     */
    public void deployDemoApp() {
        try {
            // Check if demo.war is already deployed
            if (isDeployed(DEMO_APP + ".war")) {
                log.debug("Demo application already deployed on worker '{}'", container.getName());
                return;
            }

            // Copy demo.war from resources
            File demoWar = new File("src/test/resources/deployments/" + DEMO_APP + ".war");
            if (demoWar.exists()) {
                log.info("Deploying demo application to worker '{}' using Creaper", container.getName());
                deploy(demoWar);
            } else {
                log.warn("Demo application not found at: {}", demoWar.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Failed to deploy demo application to worker '{}'", container.getName(), e);
        }
    }
}
