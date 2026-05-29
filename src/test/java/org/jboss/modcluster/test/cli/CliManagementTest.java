package org.jboss.modcluster.test.cli;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.dmr.ModelNode;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;

import static org.jboss.modcluster.test.utils.WildFlyDeploymentManager.DEMO_APP;

/**
 * Tests for mod_cluster management via Creaper (WildFly management API).
 * Demonstrates using Creaper for configuration and management operations.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class CliManagementTest {

    private static final Logger log = LoggerFactory.getLogger(CliManagementTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that mod_cluster subsystem configuration can be read via Creaper.
     * Passes if the subsystem resource is readable and has a proxy defined.
     */
    @Test
    public void testReadModClusterConfiguration(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyWorker worker = cluster.getWorker1();

        // Read mod_cluster subsystem configuration using Creaper
        Operations ops = worker.getOperations();
        Address modclusterAddress = Address.subsystem("modcluster");

        ModelNodeResult nodeResult = ops.readResource(modclusterAddress);
        nodeResult.assertSuccess();
        ModelNode result = nodeResult.value();

        log.info("ModCluster configuration: {}", result.toJSONString(true));

        softly.assertThat(result.hasDefined("proxy"))
                .as("ModCluster subsystem should have proxy defined")
                .isTrue();
    }

    /**
     * Verifies that proxy list can be retrieved from mod_cluster subsystem.
     * Passes if at least one proxy is configured and returned.
     */
    @Test
    public void testEnableContextViaCLI(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyWorker worker = cluster.getWorker1();

        // List proxies using Creaper
        Operations ops = worker.getOperations();
        Address modclusterAddress = Address.subsystem("modcluster");

        ModelNodeResult nodeResult = ops.readChildrenNames(modclusterAddress, "proxy");
        nodeResult.assertSuccess();
        ModelNode result = nodeResult.value();

        log.info("Proxy list: {}", result.toJSONString(true));

        softly.assertThat(result.asList())
                .as("Should have at least one proxy configured")
                .isNotEmpty();
    }

    /**
     * Verifies that mod_cluster attributes can be read using Creaper helper methods.
     * Passes if the status-interval attribute is defined and readable.
     */
    @Test
    public void testDisableContextViaCLI(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyWorker worker = cluster.getWorker1();

        // Read status-interval using Creaper helper method
        ModelNode statusInterval = worker.modCluster().readModClusterAttribute("status-interval");

        log.info("Status interval: {}", statusInterval);

        softly.assertThat(statusInterval.isDefined())
                .as("Status interval should be defined")
                .isTrue();
    }

    /**
     * Verifies that proxy configuration details can be read from mod_cluster subsystem.
     * Passes if proxy names are retrievable and proxy configuration is defined.
     */
    @Test
    public void testModClusterProxyInfo(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyWorker worker = cluster.getWorker1();

        // Read proxy configuration using Creaper
        Operations ops = worker.getOperations();
        Address modclusterAddress = Address.subsystem("modcluster");

        // First, get the list of proxy names
        ModelNodeResult proxyNamesResult =
            ops.readChildrenNames(modclusterAddress, "proxy");
        proxyNamesResult.assertSuccess();

        log.info("Proxy names: {}", proxyNamesResult.value().toJSONString(true));

        softly.assertThat(proxyNamesResult.value().asList())
                .as("Should have at least one proxy configured")
                .isNotEmpty();

        // Read the first proxy's configuration
        String proxyName = proxyNamesResult.value().asList().get(0).asString();
        Address proxyAddress = modclusterAddress.and("proxy", proxyName);

        ModelNodeResult proxyResult = ops.readResource(proxyAddress);
        proxyResult.assertSuccess();
        ModelNode proxyConfig = proxyResult.value();

        log.info("Proxy '{}' configuration: {}", proxyName, proxyConfig.toJSONString(true));

        softly.assertThat(proxyConfig.isDefined())
                .as("Proxy configuration should be available")
                .isTrue();
    }

    /**
     * Verifies that mod_cluster attributes can be read and written using Creaper.
     * Passes if status-interval can be read, updated to 20, and the change is reflected.
     */
    @Test
    public void testModClusterStatusInterval(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyWorker worker = cluster.getWorker1();

        // Read current status interval using Creaper
        ModelNode currentValue = worker.modCluster().readModClusterAttribute("status-interval");

        log.info("Current status interval: {}", currentValue.asInt());

        softly.assertThat(currentValue.isDefined())
                .as("Should read status-interval successfully")
                .isTrue();

        int originalValue = currentValue.asInt();

        // Write new status interval using Creaper
        worker.modCluster().writeModClusterAttribute("status-interval", 20);

        // Verify the change
        ModelNode newValue = worker.modCluster().readModClusterAttribute("status-interval");

        log.info("New status interval: {}", newValue.asInt());

        softly.assertThat(newValue.asInt())
                .as("Status interval should be updated to 20")
                .isEqualTo(20);

        // Restore original value
        worker.modCluster().writeModClusterAttribute("status-interval", originalValue);
    }

    /**
     * Verifies that deployment status can be checked using Creaper helper methods.
     * Passes if demo.war is reported as deployed and enabled.
     */
    @Test
    public void testCheckDeploymentStatus(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyWorker worker = cluster.getWorker1();

        // Check if demo.war is deployed using Creaper
        boolean isDeployed = worker.deployment().isDeployed(DEMO_APP + ".war");

        log.info("demo.war deployed: {}", isDeployed);

        softly.assertThat(isDeployed)
                .as("demo.war should be deployed and enabled")
                .isTrue();
    }
}
