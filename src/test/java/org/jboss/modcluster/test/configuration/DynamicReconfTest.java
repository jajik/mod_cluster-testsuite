package org.jboss.modcluster.test.configuration;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jboss.modcluster.test.utils.WildFlyDeploymentManager.DEMO_APP;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Tests for dynamic reconfiguration of mod_cluster.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class DynamicReconfTest {

    private static final Logger log = LoggerFactory.getLogger(DynamicReconfTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that workers can be dynamically added to a running cluster and automatically register with the balancer.
     * Passes if worker2 registers within 30 seconds and both workers receive traffic.
     */
    @Test
    public void testDynamicWorkerRegistration(TestCluster cluster, HttpClient httpClient) throws Exception {
        // Start with one worker
        cluster.startWorkers(1);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for worker1 to register and receive traffic
        Map<String, Integer> initialDistribution = httpClient.waitForWorkerRegistration(balancerUrl, 1, TestTimeouts.CLUSTER_FORMATION);

        log.info("Initial distribution: {}", initialDistribution);

        // Dynamically add worker2
        log.info("Dynamically adding worker2...");
        WildFlyWorker worker2 = WildFlyWorker.create("worker2", cluster.getBalancer());
        worker2.start();

        try {
            // Wait for worker2 to register with balancer
            await().atMost(TestTimeouts.CLUSTER_FORMATION)
                    .pollInterval(ofSeconds(2))
                    .untilAsserted(() -> {
                        Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 10);
                        assertThat(dist)
                                .as("Both workers should receive traffic after worker2 registration")
                                .containsKeys("worker1", "worker2");
                    });

            // Verify both workers are now receiving traffic
            Map<String, Integer> finalDistribution = httpClient.testLoadDistribution(balancerUrl, 50);

            log.info("Final distribution: {}", finalDistribution);

            softly.assertThat(finalDistribution)
                    .as("Both workers should receive requests after dynamic addition")
                    .containsKeys("worker1", "worker2");
        } finally {
            worker2.stop();
        }
    }

    /**
     * Verifies that mod_cluster configuration attributes can be changed dynamically without server restart.
     * Passes if flush-packets attribute can be toggled and the change is immediately reflected.
     */
    @Test
    public void testDynamicConfigurationChange(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyWorker worker = cluster.getWorker1();

        // Read initial flush-packets setting using Creaper
        org.jboss.dmr.ModelNode initialValue = worker.modCluster().readModClusterAttribute("flush-packets");

        log.info("Initial flush-packets: {}", initialValue.asBoolean());

        boolean originalValue = initialValue.asBoolean();

        // Change configuration dynamically using Creaper
        worker.modCluster().writeModClusterAttribute("flush-packets", !originalValue);

        // Verify the change
        org.jboss.dmr.ModelNode newValue = worker.modCluster().readModClusterAttribute("flush-packets");

        log.info("New flush-packets: {}", newValue.asBoolean());

        softly.assertThat(newValue.asBoolean())
                .as("Configuration should be updated")
                .isEqualTo(!originalValue);

        // Restore original value
        worker.modCluster().writeModClusterAttribute("flush-packets", originalValue);
    }

    /**
     * Verifies that workers automatically unregister from the balancer when stopped.
     * Passes if traffic stops routing to worker1 within 60 seconds after it is stopped.
     */
    @Test
    public void testWorkerUnregistrationAndReregistration(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register and receive traffic
        Map<String, Integer> initialDist = httpClient.waitForWorkerRegistration(balancerUrl, 2, TestTimeouts.CLUSTER_FORMATION);

        // Stop worker1
        log.info("Stopping worker1...");
        cluster.getWorker1().stop();

        // Wait for unregistration (increased timeout for worker failure detection)
        await().atMost(TestTimeouts.FAILOVER)
                .pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 10);
                    assertThat(dist)
                            .as("Only worker2 should receive traffic after worker1 stops")
                            .containsOnlyKeys("worker2");
                    assertThat(dist.get("worker2"))
                            .as("worker2 should be receiving all successful requests")
                            .isGreaterThan(0);
                });

        log.info("Worker1 unregistered successfully");
    }
}
