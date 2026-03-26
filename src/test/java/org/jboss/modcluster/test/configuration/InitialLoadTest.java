package org.jboss.modcluster.test.configuration;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.WildFlyContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;

import static org.jboss.modcluster.test.utils.WildFlyDeploymentManager.DEMO_APP;

/**
 * Tests for initial-load parameter validation in mod_cluster configuration.
 * Verifies boundary values and invalid input handling.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class InitialLoadTest {

    private static final Logger log = LoggerFactory.getLogger(InitialLoadTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    private static final Address DYNAMIC_LOAD_PROVIDER_ADDRESS =
            Address.subsystem("modcluster").and("proxy", "default").and("load-provider", "dynamic");

    /**
     * Verifies that setting initial-load to invalid negative value (< -1) is rejected.
     */
    @Test
    public void testSetInitialLoadToInvalidNegative(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker1 = cluster.getWorker1();

        Operations ops = worker1.getOperations();

        log.info("Attempting to set initial-load to invalid value: -100");
        ModelNodeResult result = ops.writeAttribute(DYNAMIC_LOAD_PROVIDER_ADDRESS, "initial-load", -100);

        softly.assertThat(result.isSuccess())
                .as("Setting initial-load to -100 should fail")
                .isFalse();

        log.info("Invalid negative value correctly rejected");
    }

    /**
     * Verifies that setting initial-load to -1 (special value) is accepted.
     * -1 means "use calculated load immediately"
     */
    @Test
    public void testSetInitialLoadToNegative(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker1 = cluster.getWorker1();

        Operations ops = worker1.getOperations();

        log.info("Setting initial-load to special value: -1");
        ModelNodeResult result = ops.writeAttribute(DYNAMIC_LOAD_PROVIDER_ADDRESS, "initial-load", -1);

        softly.assertThat(result.isSuccess())
                .as("Setting initial-load to -1 should succeed")
                .isTrue();

        // Verify the value was set
        ModelNodeResult readResult = ops.readAttribute(DYNAMIC_LOAD_PROVIDER_ADDRESS, "initial-load");
        int actualValue = readResult.intValue();

        softly.assertThat(actualValue)
                .as("initial-load should be set to -1")
                .isEqualTo(-1);

        log.info("Special value -1 accepted, actual value: {}", actualValue);
    }

    /**
     * Verifies that setting initial-load to valid positive value (0-100) is accepted.
     */
    @Test
    public void testSetInitialLoadToPositive(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker1 = cluster.getWorker1();

        Operations ops = worker1.getOperations();

        int testValue = 50;
        log.info("Setting initial-load to valid positive value: {}", testValue);
        ModelNodeResult result = ops.writeAttribute(DYNAMIC_LOAD_PROVIDER_ADDRESS, "initial-load", testValue);

        softly.assertThat(result.isSuccess())
                .as("Setting initial-load to {} should succeed", testValue)
                .isTrue();

        // Verify the value was set
        ModelNodeResult readResult = ops.readAttribute(DYNAMIC_LOAD_PROVIDER_ADDRESS, "initial-load");
        int actualValue = readResult.intValue();

        softly.assertThat(actualValue)
                .as("initial-load should be set to {}", testValue)
                .isEqualTo(testValue);

        log.info("Valid positive value accepted, actual value: {}", actualValue);
    }

    /**
     * Verifies that setting initial-load to invalid positive value (> 100) is rejected.
     */
    @Test
    public void testSetInitialLoadToInvalidPositive(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker1 = cluster.getWorker1();

        Operations ops = worker1.getOperations();

        log.info("Attempting to set initial-load to invalid value: 150");
        ModelNodeResult result = ops.writeAttribute(DYNAMIC_LOAD_PROVIDER_ADDRESS, "initial-load", 150);

        softly.assertThat(result.isSuccess())
                .as("Setting initial-load to 150 should fail")
                .isFalse();

        log.info("Invalid positive value correctly rejected");
    }

    /**
     * Verifies that default initial-load value is used when worker starts.
     * Default should be 0 (meaning worker reports 0 load initially, allowing traffic immediately).
     */
    @Test
    public void testInitialLoadDefault(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker1 = cluster.getWorker1();

        Operations ops = worker1.getOperations();

        // Read default initial-load value
        ModelNodeResult readResult = ops.readAttribute(DYNAMIC_LOAD_PROVIDER_ADDRESS, "initial-load");
        int defaultValue = readResult.intValue();

        log.info("Default initial-load value: {}", defaultValue);

        softly.assertThat(defaultValue)
                .as("Default initial-load should be 0")
                .isEqualTo(0);

        // Verify worker is accessible (initial load allows traffic)
        String workerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";
        cluster.getHttpClient().get(workerUrl);

        log.info("Worker accessible with default initial-load");
    }
}
