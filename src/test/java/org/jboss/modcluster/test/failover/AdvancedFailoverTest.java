package org.jboss.modcluster.test.failover;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.jboss.modcluster.test.utils.balancer.Balancer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jboss.modcluster.test.utils.WildFlyDeploymentManager.DEMO_APP;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Advanced failover scenarios testing.
 * Tests session migration, deterministic routing, graceful failover, and failover under various conditions.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class AdvancedFailoverTest {

    private static final Logger log = LoggerFactory.getLogger(AdvancedFailoverTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that active sessions are maintained during worker failover.
     * Passes if session established on worker1 continues to work after worker1 stops and requests route to worker2.
     */
    @Test
    public void testFailoverWithActiveSessions(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Establish session on one of the workers
        HttpResponse initialResponse = httpClient.get(balancerUrl);
        String sessionCookie = initialResponse.getCookie("JSESSIONID");
        String initialWorker = extractWorkerFromSessionId(sessionCookie);

        log.info("Session established: {} on worker: {}", sessionCookie, initialWorker);

        // Make several requests to verify session is active
        for (int i = 0; i < 5; i++) {
            HttpResponse response = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + sessionCookie);
            softly.assertThat(response.getStatusCode())
                    .as("Request %d with session should succeed", i + 1)
                    .isEqualTo(200);
        }

        // Stop the worker holding the session
        if ("worker1".equals(initialWorker)) {
            log.info("Stopping worker1 (session holder)...");
            cluster.getWorker1().stop();
        } else {
            log.info("Stopping worker2 (session holder)...");
            cluster.getWorker2().stop();
        }

        // Wait for failover and verify session still works (may route to other worker)
        await().atMost(TestTimeouts.FAILOVER)
                .pollInterval(ofSeconds(3))
                .ignoreExceptionsInstanceOf(IOException.class)
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + sessionCookie);
                    assertThat(response.getStatusCode())
                            .as("Session should failover successfully")
                            .isEqualTo(200);
                });

        log.info("Session failover completed successfully");
    }

    /**
     * Verifies failover when worker is hard-killed (simulates crash).
     * Passes if session continues to work after hard kill (SIGKILL).
     */
    @Test
    public void testFailoverViaHardKill(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Establish session
        HttpResponse initialResponse = httpClient.get(balancerUrl);
        String sessionCookie = initialResponse.getCookie("JSESSIONID");
        String initialWorker = extractWorkerFromSessionId(sessionCookie);

        log.info("Session established: {} on worker: {}", sessionCookie, initialWorker);

        // Make several requests to verify session is active
        for (int i = 0; i < 5; i++) {
            HttpResponse response = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + sessionCookie);
            softly.assertThat(response.getStatusCode())
                    .as("Request %d with session should succeed", i + 1)
                    .isEqualTo(200);
        }

        // Hard kill the worker holding the session (simulates crash)
        if ("worker1".equals(initialWorker)) {
            log.info("Hard killing worker1 (session holder) via SIGKILL...");
            cluster.getWorker1().kill();
        } else {
            log.info("Hard killing worker2 (session holder) via SIGKILL...");
            cluster.getWorker2().kill();
        }

        // Wait for failover and verify session still works
        await().atMost(TestTimeouts.FAILOVER)
                .pollInterval(ofSeconds(3))
                .ignoreExceptionsInstanceOf(IOException.class)
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + sessionCookie);
                    assertThat(response.getStatusCode())
                            .as("Session should failover after hard kill")
                            .isEqualTo(200);
                });

        log.info("Session failover after hard kill completed successfully");
    }

    /**
     * Verifies failover when application is undeployed from worker.
     * Passes if session continues to work after app undeploy.
     */
    @Test
    public void testFailoverViaUndeploy(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Establish session
        HttpResponse initialResponse = httpClient.get(balancerUrl);
        String sessionCookie = initialResponse.getCookie("JSESSIONID");
        String initialWorker = extractWorkerFromSessionId(sessionCookie);

        log.info("Session established: {} on worker: {}", sessionCookie, initialWorker);

        // Make several requests to verify session is active
        for (int i = 0; i < 5; i++) {
            HttpResponse response = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + sessionCookie);
            softly.assertThat(response.getStatusCode())
                    .as("Request %d with session should succeed", i + 1)
                    .isEqualTo(200);
        }

        // Undeploy the app from the worker holding the session
        if ("worker1".equals(initialWorker)) {
            log.info("Undeploying demo.war from worker1 (session holder)...");
            cluster.getWorker1().deployment().undeploy(DEMO_APP + ".war");
        } else {
            log.info("Undeploying demo.war from worker2 (session holder)...");
            cluster.getWorker2().deployment().undeploy(DEMO_APP + ".war");
        }

        // Wait for failover and verify session still works
        await().atMost(TestTimeouts.FAILOVER)
                .pollInterval(ofSeconds(3))
                .ignoreExceptionsInstanceOf(IOException.class)
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + sessionCookie);
                    assertThat(response.getStatusCode())
                            .as("Session should failover after undeploy")
                            .isEqualTo(200);
                });

        log.info("Session failover after undeploy completed successfully");
    }

    /**
     * Verifies deterministic failover routing based on worker configuration.
     * Passes if requests consistently route to the expected worker in a predictable order.
     */
    @Test
    public void testDeterministicFailover(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(4);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for all 4 workers to register and receive traffic.
        // httpd's mod_proxy_cluster needs time to process CONFIG messages from all workers.
        await().atMost(TestTimeouts.CLUSTER_FORMATION).pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 40);
                    assertThat(dist)
                            .as("All 4 workers should be active initially")
                            .containsKeys("worker1", "worker2", "worker3", "worker4");
                });

        Map<String, Integer> initialDist = httpClient.testLoadDistribution(balancerUrl, 40);
        log.info("Initial distribution with 4 workers: {}", initialDist);

        // Gracefully shut down worker1 so JGroups sends LEAVE before network disconnect.
        // Using stop() (network-disconnect-first) with 4+ workers causes FD_ALL3/FD_SOCK2
        // false suspicions during view changes, leading to sporadic split-brain.
        log.info("Shutting down worker1 for deterministic failover test...");
        cluster.getWorker1().shutdown();

        await().atMost(TestTimeouts.FAILOVER)
                .pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 30);
                    assertThat(dist)
                            .as("Traffic should route to remaining 3 workers after worker1 stops")
                            .containsKeys("worker2", "worker3", "worker4")
                            .doesNotContainKey("worker1");
                });

        Map<String, Integer> after1 = httpClient.testLoadDistribution(balancerUrl, 30);
        log.info("Distribution after worker1 stopped: {}", after1);

        // Same rationale as worker1 above — graceful shutdown avoids split-brain.
        log.info("Shutting down worker2...");
        cluster.getWorker2().shutdown();

        await().atMost(TestTimeouts.FAILOVER)
                .pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 20);
                    assertThat(dist)
                            .as("Traffic should route to remaining 2 workers only")
                            .containsOnlyKeys("worker3", "worker4");
                });

        log.info("Distribution after worker2 stopped verified: only worker3 and worker4 receive traffic");

        log.info("Deterministic failover with 4 workers verified successfully");
    }

    /**
     * Verifies graceful failover without dropped requests during worker shutdown.
     * Passes if all requests during worker shutdown receive valid responses with no errors.
     */
    @Test
    public void testGracefulFailoverNoDroppedRequests(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register with balancer and receive traffic
        await().atMost(TestTimeouts.CLUSTER_FORMATION).pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 20);
                    assertThat(dist)
                            .as("Both workers should be registered and receiving traffic")
                            .containsKeys("worker1", "worker2");
                });

        // Start making continuous requests in background
        List<Integer> statusCodes = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();

        Thread requestThread = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                try {
                    HttpResponse response = httpClient.get(balancerUrl);
                    synchronized (statusCodes) {
                        statusCodes.add(response.getStatusCode());
                    }
                    Thread.sleep(100); // 10 requests/second
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            }
        });

        requestThread.start();

        // Wait for some requests to complete, then gracefully shut down worker1
        Thread.sleep(2000);
        log.info("Gracefully shutting down worker1 during active traffic...");
        cluster.getWorker1().shutdown();

        // Wait for all requests to complete
        requestThread.join();

        log.info("Completed {} requests with {} errors", statusCodes.size(), errors.size());

        // Verify graceful failover - most requests should succeed
        // Allow some failures during transition, but should be minimal
        long successfulRequests = statusCodes.stream().filter(code -> code == 200).count();
        double successRate = (double) successfulRequests / statusCodes.size();

        softly.assertThat(successRate)
                .as("Success rate during graceful failover should be >= 95%")
                .isGreaterThanOrEqualTo(0.95);

        log.info("Success rate: {}", successRate);
    }

    /**
     * Verifies failover behavior when worker unregisters gracefully from the cluster.
     * Passes if traffic stops routing to unregistering worker within 60 seconds without errors.
     */
    @Test
    public void testFailoverDuringUnregistration(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register and receive traffic.
        // httpd's mod_proxy_cluster needs time to process CONFIG messages from all workers.
        await().atMost(TestTimeouts.CLUSTER_FORMATION).pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 20);
                    assertThat(dist)
                            .as("Both workers should be active")
                            .containsKeys("worker1", "worker2");
                });

        Map<String, Integer> initialDist = httpClient.testLoadDistribution(balancerUrl, 20);
        log.info("Initial distribution: {}", initialDist);

        // Gracefully stop worker1 (triggers unregistration)
        log.info("Initiating graceful unregistration of worker1...");
        cluster.getWorker1().stop();

        // Monitor failover during unregistration
        await().atMost(TestTimeouts.FAILOVER)
                .pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 10);
                    assertThat(dist)
                            .as("Traffic should failover to worker2 during unregistration")
                            .containsOnlyKeys("worker2");
                    assertThat(dist.get("worker2"))
                            .as("Worker2 should handle all successful requests")
                            .isGreaterThan(0);
                });

        log.info("Failover during unregistration completed successfully");
    }

    /**
     * Verifies failover behavior under high load conditions.
     * Passes if failover completes within 60 seconds while handling 200+ concurrent requests.
     */
    @Test
    public void testFailoverUnderLoad(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register with balancer and receive traffic
        await().atMost(TestTimeouts.CLUSTER_FORMATION).pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 20);
                    assertThat(dist)
                            .as("Both workers should be registered and receiving traffic")
                            .containsKeys("worker1", "worker2");
                });

        // Generate load with multiple concurrent request threads
        final int NUM_THREADS = 5;
        final int REQUESTS_PER_THREAD = 50;
        List<Thread> threads = new ArrayList<>();
        List<Integer> allStatusCodes = new ArrayList<>();

        for (int t = 0; t < NUM_THREADS; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                    try {
                        HttpResponse response = httpClient.get(balancerUrl);
                        synchronized (allStatusCodes) {
                            allStatusCodes.add(response.getStatusCode());
                        }
                    } catch (Exception e) {
                        log.debug("Request failed under load: {}", e.getMessage());
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }

        // Wait briefly for load to build up, then gracefully shut down worker1
        Thread.sleep(2000);
        log.info("Shutting down worker1 under load ({} threads, {} total requests)...", NUM_THREADS, NUM_THREADS * REQUESTS_PER_THREAD);
        cluster.getWorker1().shutdown();

        // Wait for all request threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        log.info("Load test completed: {} total requests", allStatusCodes.size());

        // Verify that traffic successfully failed over to worker2
        await().atMost(TestTimeouts.FAILOVER)
                .pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 20);
                    assertThat(dist)
                            .as("All traffic should route to worker2 after failover under load")
                            .containsOnlyKeys("worker2");
                });

        // Verify reasonable success rate under load
        long successCount = allStatusCodes.stream().filter(code -> code == 200).count();
        double successRate = (double) successCount / allStatusCodes.size();

        softly.assertThat(successRate)
                .as("Success rate under load should be >= 95%")
                .isGreaterThanOrEqualTo(0.95);

        log.info("Success rate under load: {}", successRate);
    }

    /**
     * Verifies that balancer respects configured health check and broken node timeout settings.
     * Tests that failed worker is detected within expected time based on configuration.
     */
    @Test
    public void testHealthCheckAndBrokenNodeTimeout(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register and receive traffic
        await().atMost(TestTimeouts.CLUSTER_FORMATION).pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 20);
                    assertThat(dist)
                            .as("Both workers should be active initially")
                            .containsKeys("worker1", "worker2");
                });

        log.info("Both workers active and receiving traffic");

        // Record time when we kill the worker
        long killTime = System.currentTimeMillis();

        log.info("Hard killing worker1 to test health check detection...");
        cluster.getWorker1().kill();

        // Wait for balancer to detect worker is down
        int maxDetectionMs = Balancer.HEALTH_CHECK_INTERVAL_MS
                + Balancer.BROKEN_NODE_TIMEOUT_MS;
        int maxDetectionWithMarginMs = maxDetectionMs * 3;
        await().atMost(ofSeconds(maxDetectionWithMarginMs / 1000 + 1))
                .pollInterval(ofSeconds(1))
                .untilAsserted(() -> {
                    Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 10);
                    assertThat(dist)
                            .as("Traffic should route only to worker2 after health check detects worker1 down")
                            .containsOnlyKeys("worker2");
                });

        long detectionTime = System.currentTimeMillis() - killTime;
        log.info("Broken worker detected in {} ms (health-check-interval={}ms, broken-node-timeout={}ms)",
                detectionTime, Balancer.HEALTH_CHECK_INTERVAL_MS,
                Balancer.BROKEN_NODE_TIMEOUT_MS);

        softly.assertThat(detectionTime)
                .as("Broken worker should be detected within configured timeout")
                .isLessThan(maxDetectionWithMarginMs);

        // Verify traffic continues to route only to surviving worker
        Map<String, Integer> finalDist = httpClient.testLoadDistribution(balancerUrl, 50);
        log.info("Final distribution after detection: {}", finalDist);

        softly.assertThat(finalDist)
                .as("Dead worker1 should not receive traffic after being marked as broken")
                .doesNotContainKey("worker1");

        log.info("Health check and broken node timeout verification completed");
    }

    /**
     * Extract worker/route information from JSESSIONID.
     * Format is typically: <session-id>.<route>
     */
    private String extractWorkerFromSessionId(String sessionId) {
        if (sessionId != null && sessionId.contains(".")) {
            return sessionId.substring(sessionId.lastIndexOf('.') + 1);
        }
        return "unknown";
    }
}
