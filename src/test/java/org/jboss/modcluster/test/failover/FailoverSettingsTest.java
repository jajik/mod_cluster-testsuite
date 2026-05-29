package org.jboss.modcluster.test.failover;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.apps.ExitAppBuilder;
import org.jboss.modcluster.test.apps.SleepAppBuilder;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jboss.modcluster.test.utils.WildFlyDeploymentManager.DEMO_APP;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Tests for failover settings that control retry behavior when workers fail.
 * Covers max-attempts, max-retries (Undertow-specific), and node-timeout settings.
 *
 * <p>These tests deploy a "kill" application (exit.war) whose JSP calls
 * {@code Runtime.getRuntime().halt(1)} to immediately terminate the worker JVM.
 * When the balancer proxies a request to exit.war, the worker dies mid-request.
 * Depending on the max-attempts / max-retries configuration, the balancer may
 * retry the request on other workers — each of which also dies, creating a
 * cascading failure whose breadth is controlled by the settings under test.</p>
 *
 * <p>On Undertow, the effective retry behavior is governed by the balancer's
 * {@code max-retries} attribute. The worker's {@code max-attempts} is sent via MCMP STATUS
 * and may influence the balancer. For reliable testing, both values are set in sync.</p>
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class FailoverSettingsTest {

    private static final Logger log = LoggerFactory.getLogger(FailoverSettingsTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that max-attempts=10 causes the balancer to retry until all 4 workers are killed.
     * Sets max-retries=0 on balancer so only the worker's max-attempts governs retries.
     * On Undertow, the effective retry count is max(max-retries, max-attempts).
     * Passes if no workers remain alive after the cascading kill request.
     *
     * <p>Undertow-only: httpd's mod_proxy_cluster does not use the max-attempts value
     * from MCMP CONFIG/STATUS messages for retry routing decisions.</p>
     */
    @Tag("undertow")
    @Test
    public void testMaxAttemptsAll(TestCluster cluster, HttpClient httpClient) throws Exception {
        doMaxAttemptsRetriesTest(cluster, httpClient, 10, 0, 4, 0);
    }

    /**
     * Verifies that max-retries=10 (Undertow-specific) causes the balancer to retry
     * until all 4 workers are killed.
     * Sets max-attempts=0 on workers so only the balancer's max-retries governs retries.
     * Passes if no workers remain alive after the cascading kill request.
     */
    @Tag("undertow")
    @Test
    public void testMaxRetriesAll(TestCluster cluster, HttpClient httpClient) throws Exception {
        doMaxAttemptsRetriesTest(cluster, httpClient, 0, 10, 4, 0);
    }

    /**
     * Verifies that max-attempts=2 limits retries to approximately 2 workers killed.
     * Sets max-retries=0 on balancer so only the worker's max-attempts governs retries.
     * Due to non-deterministic TCP connection drops from Runtime.halt(), the balancer may
     * kill 2 or 3 workers (see MODCLUSTER-645 for Undertow vs httpd differences).
     * Passes if 1-2 workers remain alive (2-3 killed out of 4).
     *
     * <p>Undertow-only: httpd's mod_proxy_cluster does not use the max-attempts value
     * from MCMP CONFIG/STATUS messages for retry routing decisions.</p>
     */
    @Tag("undertow")
    @Test
    public void testMaxAttemptsHalf(TestCluster cluster, HttpClient httpClient) throws Exception {
        doMaxAttemptsRetriesTest(cluster, httpClient, 2, 0, 4, 1, 2);
    }

    /**
     * Verifies that max-retries=1 (Undertow-specific) causes the balancer to retry once,
     * killing 2 workers and leaving 2 alive.
     * Sets max-attempts=0 on workers so only the balancer's max-retries governs retries.
     * Passes if exactly 2 workers remain alive.
     */
    @Tag("undertow")
    @Test
    public void testMaxRetriesHalf(TestCluster cluster, HttpClient httpClient) throws Exception {
        doMaxAttemptsRetriesTest(cluster, httpClient, 0, 1, 4, 2);
    }

    /**
     * Verifies that max-attempts=0 and max-retries=0 prevent any retries:
     * only the first worker is killed.
     * Passes if exactly 3 workers remain alive.
     */
    @Test
    public void testMaxAttemptsZero(TestCluster cluster, HttpClient httpClient) throws Exception {
        doMaxAttemptsRetriesTest(cluster, httpClient, 0, 0, 4, 3);
    }

    /**
     * Verifies retry behavior with the default max-attempts value (1).
     * Explicitly sets max-attempts=1 (the WildFly default) on workers to ensure proper
     * MCMP CONFIG propagation. Sets max-retries=0 on balancer to isolate the max-attempts effect.
     * On Undertow, max-attempts=1 → 1 retry → initial + 1 retry = 2 killed.
     * Passes if exactly 2 workers remain alive.
     *
     * <p>Undertow-only: httpd's mod_proxy_cluster does not use the max-attempts value
     * from MCMP CONFIG/STATUS messages for retry routing decisions.</p>
     */
    @Tag("undertow")
    @Test
    public void testMaxAttemptsDefault(TestCluster cluster, HttpClient httpClient) throws Exception {
        doMaxAttemptsRetriesTest(cluster, httpClient, 1, 0, 4, 2);
    }

    /**
     * Verifies the default max-retries behavior (Undertow-specific, default is 1).
     * Sets max-attempts=0 on workers so only the balancer's default max-retries=1 applies.
     * On Undertow, max(1, 0) = 1 → initial + 1 retry = 2 killed.
     * Passes if exactly 2 workers remain alive.
     */
    @Tag("undertow")
    @Test
    public void testMaxRetriesDefault(TestCluster cluster, HttpClient httpClient) throws Exception {
        doMaxAttemptsRetriesTest(cluster, httpClient, 0, -1, 4, 2);
    }

    /**
     * Verifies that max-attempts=10 set via worker configuration kills all workers
     * when max-retries=0 is set on the balancer. Same as testMaxAttemptsAll but
     * tests the worker-side MCMP advertisement of max-attempts.
     * Passes if no workers remain alive.
     *
     * <p>Undertow-only: httpd's mod_proxy_cluster does not use the max-attempts value
     * from MCMP CONFIG/STATUS messages for retry routing decisions.</p>
     */
    @Tag("undertow")
    @Test
    public void testMaxAttemptsSystemProperty(TestCluster cluster, HttpClient httpClient) throws Exception {
        doMaxAttemptsRetriesTest(cluster, httpClient, 10, 0, 4, 0);
    }

    /**
     * Verifies that node-timeout controls how long the balancer waits for a backend response.
     * Deploys a "sleep" application that takes longer to respond than the node-timeout,
     * and verifies that the balancer returns a response within the expected timeframe.
     *
     * <p>The response should arrive after node-timeout seconds (the balancer cuts off the slow
     * backend) but before the app's sleep completes. Tolerance is +2 seconds per noe-tests.
     * See JBEAP-9624 for known issues with node-timeout on Undertow balancer.</p>
     *
     * <p>Undertow-only: httpd's mod_proxy_cluster does not apply the CONFIG message's
     * Timeout field to per-worker read timeouts. ProxyTimeout is the only way to control
     * the response read timeout in httpd, and it cannot be set per-worker.</p>
     */
    @Disabled("JBEAP-9624 / JBEAP-26262: node-timeout not applied correctly on Undertow balancer")
    @Tag("undertow")
    @Test
    public void testNodeTimeout(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        final WildFlyWorker worker = cluster.getWorker1();
        final int nodeTimeout = 10;
        final int appSleepSeconds = nodeTimeout + 5;
        final int marginOfErrorSeconds = 2;

        // Set node-timeout on the worker (sent to balancer via CONFIG message's Timeout field)
        worker.modCluster().setNodeTimeout(nodeTimeout);
        worker.reload();

        // Deploy a slow application that sleeps longer than the node-timeout
        final File sleepWar = SleepAppBuilder.createSleepApp(appSleepSeconds * 1000);
        worker.deployment().deploy(sleepWar, "sleepApp.war");

        // Wait for sleepApp context to be registered and accessible via balancer
        final String indexUrl = cluster.getBalancer().getHttpUrl() + "/sleepApp/";
        await().atMost(TestTimeouts.CLUSTER_FORMATION)
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse resp = httpClient.get(indexUrl);
                    assertThat(resp.getStatusCode()).isEqualTo(200);
                });

        // Measure response time for the slow endpoint
        final String balancerUrl = cluster.getBalancer().getHttpUrl() + "/sleepApp/sleep.jsp";
        final long startTime = System.currentTimeMillis();
        try {
            httpClient.getWithTimeout(balancerUrl, 60, TimeUnit.SECONDS);
        } catch (IOException e) {
            log.info("Request failed: {}", e.getMessage());
        }
        final long durationMs = System.currentTimeMillis() - startTime;
        final double durationSec = durationMs / 1000.0;
        log.info("Response/timeout received in {} seconds", durationSec);

        softly.assertThat(durationMs)
                .as("Response should not come before node-timeout (%d seconds), got response in %.1f seconds",
                        nodeTimeout, durationSec)
                .isGreaterThanOrEqualTo(nodeTimeout * 1000L);

        softly.assertThat(durationMs)
                .as("Response should come within node-timeout + %d s (%d seconds), not after app sleep (%d seconds). " +
                                "Got response in %.1f seconds. See JBEAP-9624.",
                        marginOfErrorSeconds, nodeTimeout + marginOfErrorSeconds, appSleepSeconds, durationSec)
                .isLessThan((nodeTimeout + marginOfErrorSeconds) * 1000L);

        try {
            worker.deployment().undeploy("sleepApp.war");
        } catch (Exception e) {
            log.warn("Failed to undeploy sleepApp: {}", e.getMessage());
        }
    }

    /**
     * Common implementation for max-attempts / max-retries tests.
     * Deploys an exit.war to all workers, sends a request through the balancer
     * to trigger cascading JVM kills, then counts surviving workers.
     *
     * <p>The max-retries attribute on the Undertow mod_cluster filter requires a server reload
     * to take effect. Therefore, max-retries is set on the balancer and the balancer is reloaded
     * BEFORE starting workers, to avoid disrupting MCMP connections.</p>
     *
     * @param cluster the test cluster
     * @param httpClient the HTTP client
     * @param maxAttempts max-attempts value to set on workers (-1 for default)
     * @param maxRetries max-retries value to set on balancer (-1 for default)
     * @param workerCount number of workers to start (up to 4)
     * @param expectedSurvivors expected number of workers still alive after the kill cascade
     */
    private void doMaxAttemptsRetriesTest(TestCluster cluster, HttpClient httpClient,
                                           int maxAttempts, int maxRetries,
                                           int workerCount, int expectedSurvivors) throws Exception {
        final int survivingWorkers = executeKillCascade(cluster, httpClient, maxAttempts, maxRetries, workerCount);

        log.info("After kill cascade: {} workers surviving out of {} (expected {})",
                survivingWorkers, workerCount, expectedSurvivors);

        softly.assertThat(survivingWorkers)
                .as("Expected %d surviving workers after maxAttempts=%d, maxRetries=%d cascade",
                        expectedSurvivors, maxAttempts, maxRetries)
                .isEqualTo(expectedSurvivors);
    }

    /**
     * Overload that accepts a range of expected survivors for non-deterministic kill cascades.
     * Used when TCP connection drop timing from Runtime.halt() makes exact survivor count unreliable.
     *
     * @param minSurvivors minimum acceptable number of surviving workers
     * @param maxSurvivors maximum acceptable number of surviving workers
     */
    private void doMaxAttemptsRetriesTest(TestCluster cluster, HttpClient httpClient,
                                           int maxAttempts, int maxRetries,
                                           int workerCount, int minSurvivors, int maxSurvivors)
                                           throws Exception {
        final int survivingWorkers = executeKillCascade(cluster, httpClient, maxAttempts, maxRetries, workerCount);

        log.info("After kill cascade: {} workers surviving out of {} (expected {}-{})",
                survivingWorkers, workerCount, minSurvivors, maxSurvivors);

        softly.assertThat(survivingWorkers)
                .as("Expected %d-%d surviving workers after maxAttempts=%d, maxRetries=%d cascade",
                        minSurvivors, maxSurvivors, maxAttempts, maxRetries)
                .isBetween(minSurvivors, maxSurvivors);
    }

    /**
     * Execute a kill cascade: deploy exit.war, send request through balancer, count survivors.
     *
     * @return number of surviving workers after the cascade
     */
    private int executeKillCascade(TestCluster cluster, HttpClient httpClient,
                                    int maxAttempts, int maxRetries,
                                    int workerCount) throws Exception {

        // Set max-retries on balancer BEFORE starting workers (requires reload to take effect)
        if (maxRetries >= 0) {
            cluster.getBalancer().setMaxRetries(maxRetries);
            cluster.getBalancer().reload();
        }

        // Pre-configure max-attempts on workers before start() — avoids disruptive reloads
        // in a running cluster that can trigger Infinispan state transfer deadlocks
        cluster.startWorkersWithMaxAttempts(workerCount, maxAttempts);

        final WildFlyWorker[] workers = new WildFlyWorker[workerCount];
        workers[0] = cluster.getWorker1();
        if (workerCount > 1) workers[1] = cluster.getWorker2();
        if (workerCount > 2) workers[2] = cluster.getWorker3();
        if (workerCount > 3) workers[3] = cluster.getWorker4();

        // Deploy exit.war to all workers (JSP that halts the JVM)
        final File exitWar = ExitAppBuilder.createExitApp();
        for (WildFlyWorker worker : workers) {
            worker.deployment().deploy(exitWar, "exit.war");
        }

        // Wait for exit context to register with balancer on ALL workers.
        // If a worker's /exit context is not yet registered when the kill cascade starts,
        // the balancer won't route the retry to that worker, leaving it alive.
        for (int i = 0; i < workerCount; i++) {
            final String workerName = "worker" + (i + 1);
            cluster.getBalancer().awaitContextRegistered(workerName, "/exit");
            log.info("Worker '{}' has /exit context registered on balancer", workerName);
        }

        // Allow MCMP STATUS messages to propagate worker settings (e.g., max-attempts)
        // to the balancer. Workers send STATUS periodically; this wait ensures the balancer
        // has processed the latest configuration before we send the kill cascade.
        Thread.sleep(5000);

        log.info("Sending kill request through balancer (maxAttempts={}, maxRetries={}, workers={})",
                maxAttempts, maxRetries, workerCount);

        // Send the cascading kill request with a long timeout
        // (the cascade can take several seconds per worker)
        final String exitUrl = cluster.getBalancer().getHttpUrl() + "/exit/exit.jsp";
        try {
            httpClient.getWithTimeout(exitUrl, 120, TimeUnit.SECONDS);
        } catch (IOException e) {
            log.info("Kill request resulted in expected error: {}", e.getMessage());
        }

        // Wait for survivor count to stabilize — cascade + container detection takes time.
        // Poll instead of hard sleep to fail fast when workers die quickly.
        final int[] lastCount = {-1};
        await().atMost(TestTimeouts.FAILOVER)
                .pollInterval(ofSeconds(5))
                .until(() -> {
                    int alive = countSurvivingWorkers(workers, httpClient);
                    log.info("Survivor poll: {} alive out of {} (previous: {})",
                            alive, workerCount, lastCount[0]);
                    if (alive == lastCount[0]) {
                        return true; // count stabilized
                    }
                    lastCount[0] = alive;
                    return false;
                });

        return countSurvivingWorkers(workers, httpClient);
    }

    /**
     * Count how many workers are still alive by directly checking each one.
     */
    private int countSurvivingWorkers(WildFlyWorker[] workers, HttpClient httpClient) {
        int surviving = 0;
        for (int i = 0; i < workers.length; i++) {
            final String workerName = "worker" + (i + 1);
            try {
                final String directUrl = workers[i].getHttpUrl() + "/" + DEMO_APP + "/";
                HttpResponse directResponse = httpClient.get(directUrl);
                if (directResponse.getStatusCode() == 200) {
                    surviving++;
                    log.debug("Worker '{}' is alive", workerName);
                } else {
                    log.debug("Worker '{}' returned status {}", workerName, directResponse.getStatusCode());
                }
            } catch (Exception e) {
                log.debug("Worker '{}' is dead: {}", workerName, e.getMessage());
            }
        }
        return surviving;
    }

}
