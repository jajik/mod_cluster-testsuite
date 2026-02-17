package org.jboss.modcluster.test.loadbalancing;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

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

    @Test
    public void testLoadDistributionAcrossWorkers(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo";

        // Make 100 requests and verify distribution
        Map<String, Integer> distribution = httpClient.testLoadDistribution(balancerUrl, 100);

        log.info("Load distribution: {}", distribution);

        softly.assertThat(distribution)
                .as("Both workers should receive requests")
                .containsKeys("worker1", "worker2");

        // Verify relatively even distribution (within 30% of each other)
        int worker1Hits = distribution.getOrDefault("worker1", 0);
        int worker2Hits = distribution.getOrDefault("worker2", 0);

        double ratio = (double) Math.min(worker1Hits, worker2Hits) / Math.max(worker1Hits, worker2Hits);

        softly.assertThat(ratio)
                .as("Load should be relatively balanced (ratio >= 0.7)")
                .isGreaterThanOrEqualTo(0.7);
    }

    @Test
    public void testFailoverWhenWorkerStops(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo";

        // Verify both workers are receiving traffic
        Map<String, Integer> initialDistribution = httpClient.testLoadDistribution(balancerUrl, 20);
        softly.assertThat(initialDistribution)
                .as("Initially both workers should receive requests")
                .containsKeys("worker1", "worker2");

        log.info("Initial distribution: {}", initialDistribution);

        // Stop worker1
        log.info("Stopping worker1...");
        cluster.getWorker1().stop();

        // Wait for balancer to detect failure
        await().atMost(ofSeconds(30))
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    var response = httpClient.get(balancerUrl);
                    String worker = extractWorker(response.getBody());
                    softly.assertThat(worker)
                            .as("All requests should go to worker2 after worker1 stops")
                            .isEqualTo("worker2");
                });

        // Verify all subsequent requests go to worker2
        Map<String, Integer> afterFailoverDistribution = httpClient.testLoadDistribution(balancerUrl, 20);

        log.info("After failover distribution: {}", afterFailoverDistribution);

        softly.assertThat(afterFailoverDistribution)
                .as("Only worker2 should receive requests after worker1 failure")
                .containsOnlyKeys("worker2");

        softly.assertThat(afterFailoverDistribution.get("worker2"))
                .as("All 20 requests should go to worker2")
                .isEqualTo(20);
    }

    private String extractWorker(String body) {
        if (body.contains("worker1")) return "worker1";
        if (body.contains("worker2")) return "worker2";
        return "unknown";
    }
}
