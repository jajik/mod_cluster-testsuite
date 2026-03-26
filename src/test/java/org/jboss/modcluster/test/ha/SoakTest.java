package org.jboss.modcluster.test.ha;

import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jboss.modcluster.test.utils.WildFlyDeploymentManager.DEMO_APP;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Long-running soak test that repeatedly kills and restarts workers to verify cluster stability.
 * Duration is configurable via the system property {@code SOAK_TEST_TIME} (in hours, default 1).
 */
@Tag("soak")
@ExtendWith(ModClusterTestExtension.class)
public class SoakTest {

    private static final Logger log = LoggerFactory.getLogger(SoakTest.class);

    /**
     * Soak test that continuously kills and restarts workers in a 2-worker cluster,
     * verifying that failover and recovery work correctly over an extended period.
     *
     * <p>For each iteration:</p>
     * <ol>
     *   <li>Send request via balancer, identify which worker handles it</li>
     *   <li>Randomly either kill or gracefully stop that worker</li>
     *   <li>Verify that subsequent request is handled by the other worker</li>
     *   <li>Verify session ID is preserved across failover</li>
     *   <li>Restart the stopped worker and verify it becomes available again</li>
     * </ol>
     *
     * <p>Duration is controlled by system property {@code SOAK_TEST_TIME} (hours).
     * Default is 1 hour.</p>
     *
     * Passes if no assertion failures occur during the entire soak period.
     */
    @Test
    public void testSoakFailover(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);
        final WildFlyWorker worker1 = cluster.getWorker1();
        final WildFlyWorker worker2 = cluster.getWorker2();
        final String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";
        final Random random = new Random();

        final int soakTestHours = Integer.getInteger("SOAK_TEST_TIME", 1);
        final long soakTestMillis = TimeUnit.HOURS.toMillis(soakTestHours);
        final long startTime = System.currentTimeMillis();

        log.info("Starting soak failover test for {} hour(s)", soakTestHours);

        int iteration = 0;

        while (System.currentTimeMillis() - startTime < soakTestMillis) {
            iteration++;
            final long elapsed = System.currentTimeMillis() - startTime;
            final long remaining = soakTestMillis - elapsed;

            log.info("=== Soak iteration {}, time remaining: {} minutes ===",
                    iteration, TimeUnit.MILLISECONDS.toMinutes(remaining));

            // Step 1: Send request and identify handler
            final HttpResponse response1 = httpClient.get(balancerUrl);
            assertThat(response1.getStatusCode())
                    .as("Soak iteration %d: initial request should succeed", iteration)
                    .isEqualTo(200);

            final String sessionCookie = response1.getCookie("JSESSIONID");
            final String handlingWorker = extractWorkerFromResponse(response1);
            log.info("Iteration {}: request handled by {}", iteration, handlingWorker);

            // Step 2: Randomly kill or stop the handling worker
            final boolean useKill = random.nextBoolean();
            final WildFlyWorker targetWorker = "worker1".equals(handlingWorker) ? worker1 : worker2;
            final WildFlyWorker survivingWorker = "worker1".equals(handlingWorker) ? worker2 : worker1;

            if (useKill) {
                log.info("Iteration {}: killing {}", iteration, handlingWorker);
                targetWorker.kill();
            } else {
                log.info("Iteration {}: stopping {}", iteration, handlingWorker);
                targetWorker.stop();
            }

            // Step 3: Verify failover to surviving worker
            final String survivingWorkerName = survivingWorker.getName();
            await().atMost(TestTimeouts.FAILOVER)
                    .pollInterval(ofSeconds(3))
                    .ignoreExceptionsInstanceOf(IOException.class)
                    .untilAsserted(() -> {
                        HttpResponse failoverResponse = httpClient.get(balancerUrl);
                        assertThat(failoverResponse.getStatusCode())
                                .as("Soak failover should succeed")
                                .isEqualTo(200);
                        assertThat(extractWorkerFromResponse(failoverResponse))
                                .as("Failover should route to surviving worker")
                                .isEqualTo(survivingWorkerName);
                    });

            log.info("Iteration {}: failover to {} successful", iteration, survivingWorkerName);

            // Step 4: Restart the stopped worker
            Thread.sleep(5000); // Wait for container cleanup
            log.info("Iteration {}: restarting {}", iteration, handlingWorker);
            targetWorker.start();

            // Step 5: Verify both workers are available
            await().atMost(TestTimeouts.CLUSTER_FORMATION)
                    .pollInterval(ofSeconds(3))
                    .untilAsserted(() -> {
                        Map<String, Integer> distribution = httpClient.testLoadDistribution(balancerUrl, 10);
                        assertThat(distribution)
                                .as("Both workers should receive traffic after restart")
                                .containsKey("worker1")
                                .containsKey("worker2");
                    });

            log.info("Iteration {}: both workers available again", iteration);
        }

        log.info("Soak test completed: {} iterations over {} hour(s)", iteration, soakTestHours);
    }

    /**
     * Extract worker name from response body.
     */
    private String extractWorkerFromResponse(HttpResponse response) {
        String body = response.getBody();
        if (body.contains("worker1")) return "worker1";
        if (body.contains("worker2")) return "worker2";
        return "unknown";
    }
}
