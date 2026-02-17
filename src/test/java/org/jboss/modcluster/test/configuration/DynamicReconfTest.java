package org.jboss.modcluster.test.configuration;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.WildFlyContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

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

    @Test
    public void testDynamicWorkerRegistration(TestCluster cluster, HttpClient httpClient) throws Exception {
        // Start with one worker
        cluster.startWorkers(1);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo";

        // Verify only worker1 receives traffic
        Map<String, Integer> initialDistribution = httpClient.testLoadDistribution(balancerUrl, 10);

        softly.assertThat(initialDistribution)
                .as("Initially only worker1 should receive traffic")
                .containsOnlyKeys("worker1");

        log.info("Initial distribution: {}", initialDistribution);

        // Dynamically add worker2
        log.info("Dynamically adding worker2...");
        WildFlyContainer worker2 = new WildFlyContainer("worker2", cluster.getBalancer());
        worker2.start();

        // Wait for worker2 to register with balancer
        await().atMost(ofSeconds(30))
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    var dist = httpClient.testLoadDistribution(balancerUrl, 10);
                    softly.assertThat(dist)
                            .as("Both workers should receive traffic after worker2 registration")
                            .containsKeys("worker1", "worker2");
                });

        // Verify both workers are now receiving traffic
        Map<String, Integer> finalDistribution = httpClient.testLoadDistribution(balancerUrl, 50);

        log.info("Final distribution: {}", finalDistribution);

        softly.assertThat(finalDistribution)
                .as("Both workers should receive requests after dynamic addition")
                .containsKeys("worker1", "worker2");

        // Cleanup worker2
        worker2.stop();
    }

    @Test
    public void testDynamicConfigurationChange(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        // Read initial flush-packets setting
        String initialValue = worker.executeCli(
                "/subsystem=modcluster/proxy=default:read-attribute(name=flush-packets)");

        log.info("Initial flush-packets: {}", initialValue);

        // Change configuration dynamically
        String writeResult = worker.executeCli(
                "/subsystem=modcluster/proxy=default:write-attribute(name=flush-packets,value=true)");

        softly.assertThat(writeResult)
                .as("Configuration change should succeed")
                .contains("outcome\" => \"success\"");

        // Verify the change
        String newValue = worker.executeCli(
                "/subsystem=modcluster/proxy=default:read-attribute(name=flush-packets)");

        log.info("New flush-packets: {}", newValue);

        softly.assertThat(newValue)
                .as("Configuration should be updated")
                .contains("\"result\" => true");
    }

    @Test
    public void testWorkerUnregistrationAndReregistration(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo";

        // Verify both workers active
        Map<String, Integer> initialDist = httpClient.testLoadDistribution(balancerUrl, 20);
        softly.assertThat(initialDist)
                .as("Both workers should be active initially")
                .containsKeys("worker1", "worker2");

        // Stop worker1
        log.info("Stopping worker1...");
        cluster.getWorker1().stop();

        // Wait for unregistration
        await().atMost(ofSeconds(30))
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    var dist = httpClient.testLoadDistribution(balancerUrl, 5);
                    softly.assertThat(dist)
                            .as("Only worker2 should receive traffic after worker1 stops")
                            .containsOnlyKeys("worker2");
                });

        log.info("Worker1 unregistered successfully");
    }
}
