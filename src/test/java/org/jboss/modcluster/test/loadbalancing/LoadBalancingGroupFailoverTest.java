package org.jboss.modcluster.test.loadbalancing;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.TestTimeouts;
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
 * Tests for load balancing group failover scenarios.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class LoadBalancingGroupFailoverTest {

    private static final Logger log = LoggerFactory.getLogger(LoadBalancingGroupFailoverTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that load is distributed across multiple workers by the balancer.
     * Passes if both workers receive requests and the distribution ratio is at least 0.55.
     */
    @Test
    public void testLoadDistributionAcrossWorkers(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register and receive traffic.
        // httpd's mod_proxy_cluster needs time to process CONFIG messages from all workers.
        await().atMost(TestTimeouts.CLUSTER_FORMATION).pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 20);
                    assertThat(dist)
                            .as("Both workers should receive requests")
                            .containsKeys("worker1", "worker2");
                });

        // Make 100 requests to test load balancing
        // Connection reuse is disabled in testLoadDistribution for accurate distribution
        Map<String, Integer> distribution = httpClient.testLoadDistribution(balancerUrl, 100);

        log.info("Load distribution: {}", distribution);

        // Verify relatively even distribution (within 30% of each other)
        // Since connection reuse is disabled, we should get good distribution
        int worker1Hits = distribution.getOrDefault("worker1", 0);
        int worker2Hits = distribution.getOrDefault("worker2", 0);

        double ratio = (double) Math.min(worker1Hits, worker2Hits) / Math.max(worker1Hits, worker2Hits);

        softly.assertThat(ratio)
                .as("Load should be relatively balanced (ratio >= 0.55)")
                .isGreaterThanOrEqualTo(0.55);
    }

    /**
     * Verifies that the balancer automatically fails over to remaining workers when one worker stops.
     * Passes if all traffic routes to worker2 within 60 seconds after worker1 is stopped.
     */
    @Test
    public void testFailoverWhenWorkerStops(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register and receive traffic
        Map<String, Integer> initialDistribution = httpClient.waitForWorkerRegistration(balancerUrl, 2, TestTimeouts.CLUSTER_FORMATION);

        log.info("Initial distribution: {}", initialDistribution);

        // Stop worker1
        log.info("Stopping worker1...");
        cluster.getWorker1().stop();

        // Wait for balancer to detect failure and route to worker2
        // Note: During transition, some requests may timeout as balancer detects worker1 failure
        await().atMost(TestTimeouts.FAILOVER)
                .pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    // Use testLoadDistribution which handles connection failures gracefully
                    Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 10);
                    assertThat(dist)
                            .as("All requests should go to worker2 after worker1 stops")
                            .containsOnlyKeys("worker2");
                    assertThat(dist.get("worker2"))
                            .as("worker2 should be receiving all successful requests")
                            .isGreaterThan(0);
                });

        // Verify all subsequent requests go to worker2
        Map<String, Integer> afterFailoverDistribution = httpClient.testLoadDistribution(balancerUrl, 20);

        log.info("After failover distribution: {}", afterFailoverDistribution);

        softly.assertThat(afterFailoverDistribution)
                .as("Only worker2 should receive requests after worker1 failure")
                .containsOnlyKeys("worker2");

        softly.assertThat(afterFailoverDistribution.get("worker2"))
                .as("Worker2 should receive all successful requests")
                .isGreaterThan(0);
    }

}
