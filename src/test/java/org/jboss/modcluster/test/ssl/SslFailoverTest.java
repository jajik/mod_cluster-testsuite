package org.jboss.modcluster.test.ssl;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jboss.modcluster.test.utils.WildFlyDeploymentManager.DEMO_APP;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Tests SSL/TLS failover scenarios with proper PKI certificate chain.
 * Workers are configured with Elytron SSL using node-specific server keystores
 * signed by a real CA chain (root CA + intermediate CA).
 *
 * <p>The balancer is also configured with SSL using the localhost server certificate,
 * and the HTTP client validates server certificates against the CA chain trust store.
 * Each test starts 2 workers with SSL configured, establishes an HTTPS session,
 * triggers a failure action (shutdown, kill, or undeploy), and verifies that
 * the session fails over to the surviving worker. This cycle repeats 3 times.</p>
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class SslFailoverTest {

    private static final Logger log = LoggerFactory.getLogger(SslFailoverTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies HTTPS session failover during graceful worker shutdown.
     * Passes if HTTPS sessions are preserved across 3 shutdown/restart cycles.
     */
    @Test
    public void testSslFailoverViaShutdown(final TestCluster cluster, final HttpClient httpClient) throws Exception {
        sslFailoverPattern(cluster, httpClient, WildFlyWorker::stop, "shutdown");
    }

    /**
     * Verifies HTTPS session failover during hard worker kill (SIGKILL).
     * Passes if HTTPS sessions are preserved across 3 kill/restart cycles.
     */
    @Test
    public void testSslFailoverViaKill(final TestCluster cluster, final HttpClient httpClient) throws Exception {
        sslFailoverPattern(cluster, httpClient, WildFlyWorker::kill, "kill");
    }

    /**
     * Verifies HTTPS session failover during application undeploy.
     * Passes if HTTPS sessions are preserved across 3 undeploy/redeploy cycles.
     */
    @Test
    public void testSslFailoverViaUndeploy(final TestCluster cluster, final HttpClient httpClient) throws Exception {
        sslFailoverPattern(cluster, httpClient,
                worker -> worker.deployment().undeploy(DEMO_APP + ".war"), "undeploy");
    }

    /**
     * Common SSL failover test pattern shared by all 3 tests.
     * Configures SSL on balancer and both workers, sets up certificate validation
     * on the HTTP client, then loops 3 iterations of:
     * establish session, trigger failure, verify failover, restore worker.
     *
     * @param cluster test cluster
     * @param httpClient HTTP client
     * @param failureAction action to trigger failover (shutdown, kill, or undeploy)
     * @param actionName name of the failure action for logging
     * @throws Exception if test fails
     */
    private void sslFailoverPattern(final TestCluster cluster, final HttpClient httpClient,
                                    final FailureAction failureAction, final String actionName) throws Exception {
        final SSLConfigurator sslConfigurator = new SSLConfigurator();

        sslConfigurator.configureBalancer(cluster.getBalancer());
        httpClient.configureTrustStore("ssl/ca/intermediate/keystores/ca-chain.keystore.jks", "testpass");

        cluster.startWorkers(2);
        sslConfigurator.configureWorker(cluster.getWorker1());
        sslConfigurator.configureWorker(cluster.getWorker2());

        final String httpsUrl = cluster.getBalancer().getHttpsUrl() + "/" + DEMO_APP + "/";

        // Wait for HTTPS cluster to be functional with certificate validation
        await().atMost(TestTimeouts.CLUSTER_FORMATION)
                .pollInterval(ofSeconds(3))
                .ignoreExceptionsInstanceOf(IOException.class)
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.getHttpsTrusted(httpsUrl);
                    assertThat(response.getStatusCode()).isEqualTo(200);
                });

        for (int iteration = 1; iteration <= 3; iteration++) {
            log.info("SSL failover via {} - iteration {}/3", actionName, iteration);

            // Establish HTTPS session with certificate validation
            final HttpResponse initial = httpClient.getHttpsTrusted(httpsUrl);
            final String sessionId = initial.getCookie("JSESSIONID");
            final String initialWorker = extractWorkerFromResponse(initial);

            softly.assertThat(initial.getStatusCode())
                    .as("Iteration %d: HTTPS connection should succeed", iteration)
                    .isEqualTo(200);
            softly.assertThat(sessionId)
                    .as("Iteration %d: session should be established", iteration)
                    .isNotNull();

            log.info("Iteration {}: session established on {} (JSESSIONID={})", iteration, initialWorker, sessionId);

            // Trigger failure on session-holder worker
            final WildFlyWorker failedWorker = cluster.getWorkerByName(initialWorker);
            final WildFlyWorker survivingWorker = getOtherWorker(cluster, initialWorker);
            log.info("Iteration {}: executing {} on {}", iteration, actionName, initialWorker);
            failureAction.execute(failedWorker);

            // Await HTTPS failover to surviving worker
            await().atMost(TestTimeouts.FAILOVER)
                    .pollInterval(ofSeconds(3))
                    .ignoreExceptionsInstanceOf(IOException.class)
                    .untilAsserted(() -> {
                        HttpResponse response = httpClient.getHttpsTrustedWithSession(httpsUrl, "JSESSIONID=" + sessionId);
                        assertThat(response.getStatusCode()).isEqualTo(200);
                        assertThat(extractWorkerFromResponse(response))
                                .as("Request should be handled by surviving worker")
                                .isEqualTo(survivingWorker.getName());
                    });

            // Verify session ID is preserved after failover
            final HttpResponse afterFailover = httpClient.getHttpsTrustedWithSession(httpsUrl, "JSESSIONID=" + sessionId);
            softly.assertThat(afterFailover.getStatusCode())
                    .as("Iteration %d: post-failover HTTPS request should succeed", iteration)
                    .isEqualTo(200);
            softly.assertThat(extractWorkerFromResponse(afterFailover))
                    .as("Iteration %d: different worker should handle request after failover", iteration)
                    .isNotEqualTo(initialWorker);

            log.info("Iteration {}: HTTPS failover completed to {}", iteration, survivingWorker.getName());

            // Restore worker for next iteration
            if (iteration < 3) {
                restoreWorker(failedWorker, sslConfigurator, actionName, cluster);
            }
        }

        log.info("SSL failover via {} completed successfully (3/3 iterations)", actionName);
    }

    /**
     * Restores a failed worker for the next iteration.
     * For undeploy: redeploys the demo app.
     * For stop/kill: restarts the container and reconfigures SSL.
     */
    private void restoreWorker(final WildFlyWorker worker, final SSLConfigurator sslConfigurator,
                               final String actionName, final TestCluster cluster) throws Exception {
        if ("undeploy".equals(actionName)) {
            log.debug("Re-deploying demo.war on {}", worker.getName());
            worker.deployment().deployDemoApp();
            await().atMost(TestTimeouts.CONTEXT_OPERATION)
                    .pollInterval(ofSeconds(2))
                    .untilAsserted(() -> {
                        assertThat(worker.deployment().isDeployed(DEMO_APP + ".war")).isTrue();
                    });
        } else {
            log.debug("Restarting {} and reconfiguring SSL", worker.getName());
            worker.start();
            sslConfigurator.configureWorker(worker);
        }

        // Wait for both workers to be registered with the balancer
        await().atMost(TestTimeouts.CLUSTER_FORMATION)
                .pollInterval(ofSeconds(2))
                .until(() -> cluster.getBalancer().getWorkerInfo().size() == 2);

        log.debug("Both workers registered with balancer");
    }

    /**
     * Extracts worker name from HTTP response body.
     *
     * @param response HTTP response
     * @return worker name (e.g., "worker1")
     */
    private String extractWorkerFromResponse(final HttpResponse response) {
        final String body = response.getBody();
        if (body.contains("worker1")) {
            return "worker1";
        }
        if (body.contains("worker2")) {
            return "worker2";
        }
        return "unknown";
    }


    /**
     * Gets the other worker (the one not named by workerName).
     *
     * @param cluster test cluster
     * @param workerName name of the current worker
     * @return WildFlyWorker for the other worker
     */
    private WildFlyWorker getOtherWorker(final TestCluster cluster, final String workerName) {
        switch (workerName) {
            case "worker1":
                return cluster.getWorker2();
            case "worker2":
                return cluster.getWorker1();
            default:
                throw new IllegalArgumentException("Unknown worker: " + workerName);
        }
    }

    /**
     * Functional interface for worker failure actions.
     */
    @FunctionalInterface
    private interface FailureAction {

        /**
         * Executes a failure action on a worker.
         *
         * @param worker worker to act on
         * @throws Exception if the action fails
         */
        void execute(WildFlyWorker worker) throws Exception;
    }
}
