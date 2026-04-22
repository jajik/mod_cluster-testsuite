package org.jboss.modcluster.test.context;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.dmr.ModelNode;
import org.jboss.modcluster.test.apps.DemoAppBuilder;
import org.jboss.modcluster.test.base.BalancerType;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.jboss.modcluster.test.utils.WildFlyContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jboss.modcluster.test.utils.WildFlyDeploymentManager.DEMO_APP;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Tests for context lifecycle management in mod_cluster.
 * Covers auto-enable, exclusions, disable/enable operations, and multiple contexts per worker.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class ContextLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(ContextLifecycleTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that contexts are automatically enabled when auto-enable-contexts is true.
     * Passes if demo.war context is automatically enabled after deployment without manual intervention.
     */
    @Test
    public void testAutoEnableContexts(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        // Read auto-enable-contexts setting
        ModelNode autoEnable = worker.modCluster().readModClusterAttribute("auto-enable-contexts");
        log.info("auto-enable-contexts: {}", autoEnable);

        // The default should be true
        softly.assertThat(autoEnable.asBoolean())
                .as("auto-enable-contexts should be true by default")
                .isTrue();

        // Verify demo.war is deployed and accessible (auto-enabled)
        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Auto-enabled context should be accessible")
                            .isEqualTo(200);
                });

        log.info("Context auto-enabled successfully");
    }

    /**
     * Verifies that excluded contexts are not registered with the balancer.
     * Passes if excluded-contexts configuration prevents context registration.
     */
    @Test
    public void testExcludedContextsNotRegistered(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        final String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Verify demo is initially accessible via balancer
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode()).isEqualTo(200);
                });

        log.info("Demo is accessible, now setting excluded-contexts");

        // Set excluded-contexts to include "demo", preserving ROOT default to prevent
        // the "/" context from acting as a catch-all on the balancer (see doExcludedContextsTest).
        worker.modCluster().writeModClusterAttribute("excluded-contexts", "ROOT, " + DEMO_APP);

        // Full JVM restart instead of reload. The mod_cluster subsystem reinitializes
        // and re-registers with the balancer from scratch, sending ENABLE-APP only for
        // non-excluded contexts. No configureStaticProxy() needed — proxy config persists
        // in standalone.xml.
        worker.restartServer();

        // Verify demo is NOT accessible via balancer after exclusion.
        // Increased timeout to account for broken-node-timeout (10s) + CI slowness.
        await().atMost(TestTimeouts.CONTEXT_OPERATION)
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Excluded context should return 404 or 503 via balancer")
                            .isIn(404, 503);
                });

        log.info("Excluded context verified as inaccessible via balancer");

        // Verify demo is STILL accessible directly on worker (it's deployed, just not proxied)
        final String directUrl = worker.getHttpUrl() + "/" + DEMO_APP + "/";
        HttpResponse directResponse = httpClient.get(directUrl);
        softly.assertThat(directResponse.getStatusCode())
                .as("Excluded context should still be accessible directly")
                .isEqualTo(200);

        log.info("Excluded contexts configuration verified");
    }

    /**
     * Verifies that excluding a non-existent context does not affect real contexts.
     * All deployed contexts should remain accessible via the balancer.
     */
    @Test
    public void testExcludedContextsNonExistentContext(TestCluster cluster, HttpClient httpClient) throws Exception {
        doExcludedContextsTest(
                cluster, httpClient,
                Arrays.asList("doesntExist"),
                Arrays.asList(DEMO_APP, "simplecontext-111", "simplecontext-222")
        );
    }

    /**
     * Verifies that multiple contexts can be excluded simultaneously.
     * Only non-excluded contexts should remain accessible via the balancer.
     */
    @Test
    public void testExcludedContextsMultipleContexts(TestCluster cluster, HttpClient httpClient) throws Exception {
        doExcludedContextsTest(
                cluster, httpClient,
                Arrays.asList(DEMO_APP, "doesntExist", "simplecontext-111"),
                Arrays.asList("simplecontext-222")
        );
    }

    /**
     * Verifies that the main application context and a non-existent context can be excluded together.
     * Only the simple context applications should remain accessible via the balancer.
     */
    @Test
    public void testExcludedContextsMainAndNonExistent(TestCluster cluster, HttpClient httpClient) throws Exception {
        doExcludedContextsTest(
                cluster, httpClient,
                Arrays.asList(DEMO_APP, "doesntExist"),
                Arrays.asList("simplecontext-111", "simplecontext-222")
        );
    }

    /**
     * Verifies that excluding only the main application context works correctly.
     * The simple context applications should remain accessible via the balancer.
     */
    @Test
    public void testExcludedContextsMainOnly(TestCluster cluster, HttpClient httpClient) throws Exception {
        doExcludedContextsTest(
                cluster, httpClient,
                Arrays.asList(DEMO_APP),
                Arrays.asList("simplecontext-111", "simplecontext-222")
        );
    }

    /**
     * Verifies that excluded-contexts applies correctly on the default virtual host
     * when excluding a non-existent context. All deployed contexts should remain accessible.
     */
    @Test
    public void testVirtHostExcludedContextsVol1(TestCluster cluster, HttpClient httpClient) throws Exception {
        doExcludedContextsTest(
                cluster, httpClient,
                Arrays.asList("doesntExist"),
                Arrays.asList(DEMO_APP, "simplecontext-111", "simplecontext-222")
        );
    }

    /**
     * Verifies that excluded-contexts applies correctly on the default virtual host
     * when excluding multiple contexts including the main application.
     */
    @Test
    public void testVirtHostExcludedContextsVol2(TestCluster cluster, HttpClient httpClient) throws Exception {
        doExcludedContextsTest(
                cluster, httpClient,
                Arrays.asList(DEMO_APP, "doesntExist", "simplecontext-111"),
                Arrays.asList("simplecontext-222")
        );
    }

    /**
     * Verifies that excluded-contexts applies correctly on the default virtual host
     * when excluding the main application and a non-existent context.
     */
    @Test
    public void testVirtHostExcludedContextsVol3(TestCluster cluster, HttpClient httpClient) throws Exception {
        doExcludedContextsTest(
                cluster, httpClient,
                Arrays.asList(DEMO_APP, "doesntExist"),
                Arrays.asList("simplecontext-111", "simplecontext-222")
        );
    }

    /**
     * Verifies that excluded-contexts applies correctly on the default virtual host
     * when excluding only the main application context.
     */
    @Test
    public void testVirtHostExcludedContextsVol4(TestCluster cluster, HttpClient httpClient) throws Exception {
        doExcludedContextsTest(
                cluster, httpClient,
                Arrays.asList(DEMO_APP),
                Arrays.asList("simplecontext-111", "simplecontext-222")
        );
    }

    /**
     * Verifies that excluded-contexts with leading slashes are normalized correctly (JBEAP-11006).
     * Contexts specified with leading slashes should still be excluded properly.
     */
    @Test
    public void testExcludedContextsWithLeadingSlash(TestCluster cluster, HttpClient httpClient) throws Exception {
        doExcludedContextsTest(
                cluster, httpClient,
                Arrays.asList("/simplecontext-111", "/simplecontext-222", DEMO_APP),
                Arrays.asList()
        );
    }

    /**
     * Common helper for excluded-contexts tests.
     * Deploys additional test applications, configures excluded-contexts on the worker,
     * then reloads so the mod_cluster subsystem re-scans deployments respecting the exclusion list.
     *
     * <p>Uses the same simple approach as {@link #testExcludedContextsNotRegistered}:
     * set the attribute, then {@code reloadServer() + configureStaticProxy()}.
     * During reload, the mod_cluster subsystem shuts down (dropping the MCMP connection).
     * The balancer's broken-node-timeout (10s) clears old registrations while the worker
     * is reloading (~15s). When the worker reconnects via {@code configureStaticProxy()},
     * it sends ENABLE-APP only for non-excluded contexts.</p>
     *
     * @param cluster          the test cluster providing balancer and worker access
     * @param httpClient       the HTTP client for sending requests
     * @param excludedContexts context names to exclude (may include non-existent ones)
     * @param accessibleContexts context names expected to remain accessible via the balancer
     * @throws Exception if any operation fails
     */
    private void doExcludedContextsTest(TestCluster cluster, HttpClient httpClient,
                                        List<String> excludedContexts,
                                        List<String> accessibleContexts) throws Exception {
        cluster.startWorkers(1);
        final WildFlyContainer worker = cluster.getWorker1();
        final File demoWar = DemoAppBuilder.createDemoApp();
        final List<String> allDeployedContexts = Arrays.asList(DEMO_APP, "simplecontext-111", "simplecontext-222");

        // Deploy additional test applications
        worker.deployment().deploy(demoWar, "simplecontext-111.war");
        worker.deployment().deploy(demoWar, "simplecontext-222.war");
        log.info("Deployed additional test applications");

        // Wait for ALL contexts to register on the balancer
        for (String contextName : allDeployedContexts) {
            final String url = cluster.getBalancer().getHttpUrl() + "/" + contextName + "/";
            await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                    .untilAsserted(() -> {
                        HttpResponse response = httpClient.get(url);
                        assertThat(response.getStatusCode())
                                .as("Context '%s' should be accessible before exclusion", contextName)
                                .isEqualTo(200);
                    });
        }
        log.info("All contexts registered on balancer");

        // Set excluded-contexts on the worker.
        // IMPORTANT: Setting this attribute REPLACES the WildFly default "ROOT".
        // If ROOT is not excluded, the welcome content at "/" gets registered on the balancer as a
        // catch-all context that routes ALL request paths to the node, including excluded contexts.
        // Always prepend ROOT to prevent catch-all routing.
        final String excludedValue = "ROOT, " + String.join(", ", excludedContexts);
        log.info("Setting excluded-contexts to: '{}'", excludedValue);
        worker.modCluster().writeModClusterAttribute("excluded-contexts", excludedValue);

        // Full JVM restart instead of reload. The mod_cluster subsystem reinitializes
        // and re-registers with the balancer from scratch, sending ENABLE-APP only for
        // non-excluded contexts. No configureStaticProxy() needed — proxy config persists
        // in standalone.xml.
        worker.restartServer();

        // Verify accessible contexts are registered on the balancer
        if (!accessibleContexts.isEmpty()) {
            for (String contextName : accessibleContexts) {
                final String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + contextName + "/";
                await().atMost(TestTimeouts.CONTEXT_OPERATION)
                        .pollInterval(ofSeconds(2))
                        .untilAsserted(() -> {
                            HttpResponse response = httpClient.get(balancerUrl);
                            assertThat(response.getStatusCode())
                                    .as("Accessible context '%s' should return 200 via balancer", contextName)
                                    .isEqualTo(200);
                        });
                log.info("Context '{}' is accessible via balancer as expected", contextName);
            }
        } else {
            // All contexts are excluded; wait for worker to register on balancer (node only, no contexts)
            await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                    .untilAsserted(() -> assertThat(cluster.getBalancer().getWorkerInfo())
                            .as("Worker should register on balancer even with all contexts excluded")
                            .isNotEmpty());
        }

        // Verify excluded contexts are NOT accessible via balancer
        for (String contextName : excludedContexts) {
            final String normalizedContext = contextName.startsWith("/") ? contextName.substring(1) : contextName;
            final boolean contextExists = allDeployedContexts.contains(normalizedContext);

            final String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + normalizedContext + "/";

            // Poll until the excluded context is no longer routed (broken-node-timeout + CI delay)
            await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                    .untilAsserted(() -> {
                        HttpResponse resp = httpClient.get(balancerUrl);
                        assertThat(resp.getStatusCode())
                                .as("Excluded context '%s' should NOT return 200 via balancer", contextName)
                                .isNotEqualTo(200);
                    });
            log.info("Excluded context '{}' verified as not routed via balancer", contextName);

            // Verify excluded contexts that actually exist ARE still accessible directly on worker
            if (contextExists) {
                final String directUrl = worker.getHttpUrl() + "/" + normalizedContext + "/";
                HttpResponse directResponse = httpClient.get(directUrl);
                softly.assertThat(directResponse.getStatusCode())
                        .as("Excluded context '%s' should still be accessible directly on worker", contextName)
                        .isEqualTo(200);
                log.info("Context '{}' is accessible directly on worker as expected", contextName);
            }
        }
    }

    /**
     * Verifies that disabling a node via the balancer proxy prevents new requests from being routed to it.
     * Starts two workers, disables worker1 via the proxy, and verifies all subsequent requests
     * go to worker2. After re-enabling, verifies load distribution is balanced again.
     */
    @Test
    public void testDisableNodeViaProxy(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);
        final WildFlyContainer worker1 = cluster.getWorker1();
        final WildFlyContainer worker2 = cluster.getWorker2();
        final String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register and be accessible
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final Map<String, Integer> distribution = httpClient.testLoadDistribution(balancerUrl, 10);
                    assertThat(distribution).containsKey("worker1");
                    assertThat(distribution).containsKey("worker2");
                });

        log.info("Both workers are registered and serving requests");

        // Disable worker1 via the balancer proxy
        cluster.getBalancer().disableNode("worker1");

        // Verify all requests go to worker2
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final Map<String, Integer> distribution = httpClient.testLoadDistribution(balancerUrl, 20);
                    assertThat(distribution.getOrDefault("worker1", 0))
                            .as("Disabled worker1 should not receive new requests")
                            .isEqualTo(0);
                    assertThat(distribution.getOrDefault("worker2", 0))
                            .as("Worker2 should handle all requests")
                            .isGreaterThan(0);
                });

        log.info("Worker1 disabled via proxy - all requests routed to worker2");

        // Re-enable worker1
        cluster.getBalancer().enableNode("worker1");

        // Verify load distribution is balanced again (may take time for load factor update)
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    final Map<String, Integer> distribution = httpClient.testLoadDistribution(balancerUrl, 20);
                    assertThat(distribution).containsKey("worker1");
                    assertThat(distribution).containsKey("worker2");
                });

        log.info("Worker1 re-enabled - load distribution balanced again");
    }

    /**
     * Verifies that stopping a node via the balancer proxy immediately prevents all requests
     * from being routed to it, including existing sessions.
     * Starts two workers, stops worker1 via the proxy, and verifies all subsequent requests
     * go to worker2. After re-enabling, verifies load distribution is balanced again.
     */
    @Test
    public void testStopNodeViaProxy(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);
        final WildFlyContainer worker1 = cluster.getWorker1();
        final WildFlyContainer worker2 = cluster.getWorker2();
        final String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register and be accessible
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final Map<String, Integer> distribution = httpClient.testLoadDistribution(balancerUrl, 10);
                    assertThat(distribution).containsKey("worker1");
                    assertThat(distribution).containsKey("worker2");
                });

        log.info("Both workers are registered and serving requests");

        // Stop worker1 via the balancer proxy
        cluster.getBalancer().stopNode("worker1");

        // Verify all requests go to worker2
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final Map<String, Integer> distribution = httpClient.testLoadDistribution(balancerUrl, 20);
                    assertThat(distribution.getOrDefault("worker1", 0))
                            .as("Stopped worker1 should not receive any requests")
                            .isEqualTo(0);
                    assertThat(distribution.getOrDefault("worker2", 0))
                            .as("Worker2 should handle all requests")
                            .isGreaterThan(0);
                });

        log.info("Worker1 stopped via proxy - all requests routed to worker2");

        // Re-enable worker1
        cluster.getBalancer().enableNode("worker1");

        // Verify load distribution is balanced again (may take time for load factor update)
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    final Map<String, Integer> distribution = httpClient.testLoadDistribution(balancerUrl, 20);
                    assertThat(distribution).containsKey("worker1");
                    assertThat(distribution).containsKey("worker2");
                });

        log.info("Worker1 re-enabled - load distribution balanced again");
    }

    /**
     * Verifies that disabling a load-balancing group via the balancer proxy prevents all workers
     * in the group from receiving new requests (Undertow-only).
     * Assigns both workers to a single group, disables it, verifies requests return 503,
     * then re-enables and verifies accessibility is restored.
     */
    @Test
    public void testDisableGroupViaProxy(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);
        final WildFlyContainer worker1 = cluster.getWorker1();
        final WildFlyContainer worker2 = cluster.getWorker2();
        final String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Assign both workers to the same load-balancing group (lightweight reload, no proxy reconfig needed)
        worker1.modCluster().setLoadBalancingGroup("groupOne");
        worker2.modCluster().setLoadBalancingGroup("groupOne");
        worker1.reloadServer();
        worker2.reloadServer();

        // Wait for registration and verify accessible
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode()).isEqualTo(200);
                });

        log.info("Both workers registered in groupOne and serving requests");

        // Disable the group via the balancer proxy
        cluster.getBalancer().disableLoadBalancingGroup("groupOne");

        // Verify requests are rejected (no workers available for new sessions).
        // Undertow returns 503 (Service Unavailable), httpd returns 404 (context not routable).
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Requests to disabled group should not return 200")
                            .isIn(404, 503);
                });

        log.info("Group disabled - requests rejected");

        // Re-enable the group
        cluster.getBalancer().enableLoadBalancingGroup("groupOne");

        // Verify accessible again
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Requests should succeed after re-enabling group")
                            .isEqualTo(200);
                });

        log.info("Group re-enabled - requests succeed again");
    }

    /**
     * Verifies that stopping a load-balancing group via the balancer proxy immediately prevents
     * all workers in the group from receiving any requests (Undertow-only).
     * Assigns both workers to a single group, stops it, verifies requests return 503,
     * then re-enables and verifies accessibility is restored.
     */
    @Test
    public void testStopGroupViaProxy(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);
        final WildFlyContainer worker1 = cluster.getWorker1();
        final WildFlyContainer worker2 = cluster.getWorker2();
        final String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Assign both workers to the same load-balancing group (lightweight reload, no proxy reconfig needed)
        worker1.modCluster().setLoadBalancingGroup("groupOne");
        worker2.modCluster().setLoadBalancingGroup("groupOne");
        worker1.reloadServer();
        worker2.reloadServer();

        // Wait for registration and verify accessible
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode()).isEqualTo(200);
                });

        log.info("Both workers registered in groupOne and serving requests");

        // Stop the group via the balancer proxy
        cluster.getBalancer().stopLoadBalancingGroup("groupOne");

        // Verify requests are rejected (no workers available).
        // Undertow returns 503 (Service Unavailable), httpd returns 404 (context not routable).
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Requests to stopped group should not return 200")
                            .isIn(404, 503);
                });

        log.info("Group stopped - requests rejected");

        // Re-enable the group
        cluster.getBalancer().enableLoadBalancingGroup("groupOne");

        // Verify accessible again
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Requests should succeed after re-enabling group")
                            .isEqualTo(200);
                });

        log.info("Group re-enabled - requests succeed again");
    }

    /**
     * Verifies that the context status is displayed as STOPPED on the balancer when a node
     * is stopped via the proxy. Starts one worker, stops the node, queries context status,
     * and verifies it reports STOPPED. Re-enables and verifies accessibility is restored.
     */
    @Test
    public void testContextStatusDisplayedAsStoppedWhenStopped(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        final WildFlyContainer worker = cluster.getWorker1();
        final String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Verify demo is accessible
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode()).isEqualTo(200);
                });

        log.info("Demo context is accessible, stopping node via proxy");

        // Stop the node via balancer proxy
        cluster.getBalancer().stopNode("worker1");

        // Wait for stop to propagate and verify context status is STOPPED
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final String status = cluster.getBalancer().getContextStatus("worker1", "/" + DEMO_APP);
                    assertThat(status)
                            .as("Context status should be STOPPED after stopping node")
                            .isEqualToIgnoringCase("STOPPED");
                });

        log.info("Context status confirmed as STOPPED");

        // Re-enable the node
        cluster.getBalancer().enableNode("worker1");

        // Verify demo is accessible again
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Demo should be accessible after re-enabling node")
                            .isEqualTo(200);
                });

        log.info("Node re-enabled - context accessible again");
    }

    /**
     * Verifies that session draining works correctly when a node is disabled via the balancer proxy.
     * Disables worker1 on the balancer (existing sessions preserved, no new sessions),
     * verifies the session is still served by worker1, then stops worker1 and verifies
     * failover to worker2.
     */
    @Test
    public void testSessionDrainWithEnoughTime(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);
        final String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final Map<String, Integer> distribution = httpClient.testLoadDistribution(balancerUrl, 10);
                    assertThat(distribution).containsKey("worker1");
                    assertThat(distribution).containsKey("worker2");
                });

        log.info("Both workers registered, establishing session on worker1");

        // Establish a session specifically on worker1
        String sessionCookie = null;
        for (int i = 0; i < 20; i++) {
            HttpResponse sessionResponse = httpClient.get(balancerUrl);
            if (sessionResponse.getBody().contains("worker1")) {
                sessionCookie = sessionResponse.getCookie("JSESSIONID");
                break;
            }
        }

        if (sessionCookie == null) {
            throw new AssertionError("Could not establish a session on worker1 after 20 attempts");
        }

        final String jsessionId = sessionCookie;
        log.info("Session established on worker1: JSESSIONID={}", jsessionId);

        // Disable worker1 via balancer proxy (existing sessions preserved, no new sessions)
        cluster.getBalancer().disableNode("worker1");

        // Verify existing session is still served by worker1 during draining
        final HttpResponse drainingResponse = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + jsessionId);
        softly.assertThat(drainingResponse.getStatusCode())
                .as("Existing session should still be served during draining")
                .isEqualTo(200);
        softly.assertThat(drainingResponse.getBody())
                .as("Session should still be routed to worker1 during draining")
                .contains("worker1");

        // Verify new requests go to worker2 (worker1 disabled for new sessions)
        final HttpResponse newRequestResponse = httpClient.get(balancerUrl);
        softly.assertThat(newRequestResponse.getBody())
                .as("New requests should go to worker2 while worker1 is disabled")
                .contains("worker2");

        log.info("Session still served on worker1 during draining, new requests go to worker2");

        // Stop worker1 via balancer proxy (all routing ceases)
        cluster.getBalancer().stopNode("worker1");

        // Wait for session to fail over to worker2
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final HttpResponse failoverResponse = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + jsessionId);
                    assertThat(failoverResponse.getStatusCode()).isEqualTo(200);
                    assertThat(failoverResponse.getBody())
                            .as("After stop, session should fail over to worker2")
                            .contains("worker2");
                });

        log.info("Session successfully failed over to worker2 after stop");
    }

    /**
     * Verifies that stopping a node without first disabling it causes immediate session failover.
     * Establishes a session on worker1, stops worker1 via the balancer proxy (no prior disable),
     * and verifies the session is routed to worker2 promptly.
     */
    @Test
    public void testSessionDrainWithoutEnoughTime(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);
        final String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final Map<String, Integer> distribution = httpClient.testLoadDistribution(balancerUrl, 10);
                    assertThat(distribution).containsKey("worker1");
                    assertThat(distribution).containsKey("worker2");
                });

        log.info("Both workers registered, establishing session on worker1");

        // Establish a session specifically on worker1
        String sessionCookie = null;
        for (int i = 0; i < 20; i++) {
            HttpResponse sessionResponse = httpClient.get(balancerUrl);
            if (sessionResponse.getBody().contains("worker1")) {
                sessionCookie = sessionResponse.getCookie("JSESSIONID");
                break;
            }
        }

        if (sessionCookie == null) {
            throw new AssertionError("Could not establish a session on worker1 after 20 attempts");
        }

        final String jsessionId = sessionCookie;
        log.info("Session established on worker1: JSESSIONID={}", jsessionId);

        // Stop worker1 via balancer proxy immediately (no prior disable, no draining period)
        cluster.getBalancer().stopNode("worker1");

        // Verify session fails over to worker2
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final HttpResponse failoverResponse = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + jsessionId);
                    assertThat(failoverResponse.getStatusCode()).isEqualTo(200);
                    assertThat(failoverResponse.getBody())
                            .as("After stop, session should fail over to worker2")
                            .contains("worker2");
                });

        log.info("Session failed over to worker2 after immediate stop");
    }

    /**
     * Verifies that contexts can be dynamically disabled via management operations.
     * Passes if context becomes unavailable after disable operation.
     */
    @Test
    public void testDisableContext(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for context to be accessible via balancer
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode()).isEqualTo(200);
                });

        log.info("Context accessible, now invoking DISABLE-CONTEXT operation");

        // DISABLE the context using mod_cluster management operation
        worker.modCluster().disableContext(DEMO_APP, "default-host");

        // Wait for disabled state to propagate to balancer
        await().atMost(TestTimeouts.CONTEXT_OPERATION)
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("New requests to disabled context should fail")
                            .isIn(404, 503);
                });

        log.info("Context successfully disabled, verifying direct access still works");

        // Verify deployment still exists and is accessible directly
        String directUrl = worker.getHttpUrl() + "/" + DEMO_APP + "/";
        HttpResponse directResponse = httpClient.get(directUrl);
        softly.assertThat(directResponse.getStatusCode())
                .as("Disabled context should still be accessible directly")
                .isEqualTo(200);

        // Re-enable the context
        log.info("Re-enabling context");
        worker.modCluster().enableContext(DEMO_APP, "default-host");

        // Verify context is accessible again via balancer
        await().atMost(TestTimeouts.CONTEXT_OPERATION)
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Context should be accessible after re-enabling")
                            .isEqualTo(200);
                });

        log.info("Context disable/enable cycle verified successfully");
    }

    /**
     * Verifies that contexts can be stopped gracefully with proper timeout handling.
     * Passes if context stop operation completes within configured stop-context-timeout.
     */
    @Test
    public void testStopContext(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        // Read stop-context-timeout configuration
        ModelNode stopTimeout = worker.modCluster().readModClusterAttribute("stop-context-timeout");
        log.info("stop-context-timeout: {}", stopTimeout);

        softly.assertThat(stopTimeout.asInt())
                .as("stop-context-timeout should be defined")
                .isGreaterThan(0);

        int timeoutSeconds = stopTimeout.asInt();
        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for context to be accessible before stop
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode()).isEqualTo(200);
                });

        log.info("Invoking STOP-CONTEXT operation with timeout: {} seconds", timeoutSeconds);

        // Invoke STOP-CONTEXT operation
        worker.modCluster().stopContext(DEMO_APP, "default-host");

        // Verify context becomes unavailable via balancer
        await().atMost(ofSeconds(timeoutSeconds + 20))
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse stopResponse = httpClient.get(balancerUrl);
                    assertThat(stopResponse.getStatusCode())
                            .as("Stopped context should return 404 or 503 via balancer")
                            .isIn(404, 503);
                });

        log.info("Context stopped successfully - verified inaccessible via balancer");

        // Note: Could also check logs for draining messages, but verifying
        // the context is actually stopped (unavailable) is the key behavior
    }

    /**
     * Verifies that multiple contexts can be deployed and accessed on a single worker.
     * Tests that contexts coexist independently and one context's lifecycle doesn't affect others.
     *
     * Passes if:
     * - All contexts are accessible via balancer
     * - All contexts are accessible directly on worker
     * - Undeploying one context doesn't affect others
     */
    @Test
    public void testMultipleContextsPerWorker(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        // Define test contexts (using demo.war deployed with different names)
        final List<String> testContexts = Arrays.asList("app1.war", "app2.war", "app3.war");
        final File demoWar = DemoAppBuilder.createDemoApp();

        log.info("Deploying {} additional contexts to test multiple contexts per worker", testContexts.size());

        // Deploy multiple instances of demo.war with different names
        for (String contextName : testContexts) {
            worker.deployment().deploy(demoWar, contextName);
            log.info("Deployed context: {}", contextName);
        }

        // Wait for original demo context to be accessible
        String demoBalancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(demoBalancerUrl);
                    assertThat(response.getStatusCode()).isEqualTo(200);
                });

        // Verify all new contexts are accessible via balancer
        log.info("Verifying all contexts accessible via balancer");
        for (String contextName : testContexts) {
            String contextPath = contextName.replace(".war", "");
            String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + contextPath + "/";

            await().atMost(TestTimeouts.CONTEXT_OPERATION)
                    .pollInterval(ofSeconds(2))
                    .untilAsserted(() -> {
                        HttpResponse response = httpClient.get(balancerUrl);
                        assertThat(response.getStatusCode())
                                .as("Context %s should be accessible via balancer", contextPath)
                                .isEqualTo(200);
                    });

            log.info("Context {} accessible via balancer", contextPath);
        }

        // Verify all contexts are accessible directly on worker
        log.info("Verifying all contexts accessible directly on worker");
        for (String contextName : testContexts) {
            String contextPath = contextName.replace(".war", "");
            String directUrl = worker.getHttpUrl() + "/" + contextPath + "/";

            HttpResponse directResponse = httpClient.get(directUrl);
            softly.assertThat(directResponse.getStatusCode())
                    .as("Context %s should be accessible directly on worker", contextPath)
                    .isEqualTo(200);

            log.info("Context {} accessible directly on worker", contextPath);
        }

        // Test context independence: undeploy one context
        String testContext = testContexts.get(0); // "app1.war"
        String testContextPath = testContext.replace(".war", "");
        log.info("Testing context independence: undeploying {}", testContext);

        worker.deployment().undeploy(testContext);

        // Verify undeployed context is no longer accessible
        await().atMost(TestTimeouts.CONTEXT_OPERATION)
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(
                        cluster.getBalancer().getHttpUrl() + "/" + testContextPath + "/"
                    );
                    assertThat(response.getStatusCode())
                            .as("Undeployed context should return 404 or 503")
                            .isIn(404, 503);
                });

        log.info("Undeployed context {} no longer accessible", testContextPath);

        // Verify other contexts still work
        log.info("Verifying other contexts still accessible after undeploying one");
        List<String> remainingContexts = testContexts.subList(1, testContexts.size());

        for (String contextName : remainingContexts) {
            String contextPath = contextName.replace(".war", "");
            String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + contextPath + "/";

            HttpResponse response = httpClient.get(balancerUrl);
            softly.assertThat(response.getStatusCode())
                    .as("Context %s should still be accessible after undeploying %s",
                        contextPath, testContextPath)
                    .isEqualTo(200);

            log.info("Context {} still accessible", contextPath);
        }

        // Cleanup: undeploy remaining test contexts
        log.info("Cleaning up remaining test contexts");
        for (String contextName : remainingContexts) {
            try {
                worker.deployment().undeploy(contextName);
                log.info("Undeployed {}", contextName);
            } catch (Exception e) {
                log.warn("Failed to undeploy {} during cleanup: {}", contextName, e.getMessage());
            }
        }

        log.info("Multiple contexts per worker verified successfully");
    }

    /**
     * Verifies that contexts can be redeployed and automatically re-register with the balancer.
     * Passes if context becomes unavailable during redeployment and accessible again after completion.
     */
    @Test
    public void testContextRedeployment(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for initial deployment to be accessible
        await().atMost(TestTimeouts.CONTEXT_OPERATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode()).isEqualTo(200);
                });

        log.info("Initial deployment verified, now undeploying demo.war");

        // UNDEPLOY the application
        worker.deployment().undeploy(DEMO_APP + ".war");

        // Wait for context to unregister from balancer
        await().atMost(TestTimeouts.CONTEXT_OPERATION)
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Undeployed context should return 404 via balancer")
                            .isIn(404, 503);
                });

        log.info("Context unregistered from balancer after undeploy");

        // Verify deployment no longer exists
        boolean isDeployed = worker.deployment().isDeployed(DEMO_APP + ".war");
        softly.assertThat(isDeployed)
                .as("demo.war should not be deployed after undeploy")
                .isFalse();

        log.info("Redeploying demo.war");

        // REDEPLOY the application
        worker.deployment().deployDemoApp();

        // Wait for context to re-register with balancer
        await().atMost(TestTimeouts.CONTEXT_OPERATION)
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Redeployed context should be accessible via balancer")
                            .isEqualTo(200);
                });

        log.info("Context re-registered with balancer after redeploy");

        // Verify deployment exists again
        boolean isRedeployed = worker.deployment().isDeployed(DEMO_APP + ".war");
        softly.assertThat(isRedeployed)
                .as("demo.war should be deployed after redeploy")
                .isTrue();

        log.info("Context redeployment cycle verified successfully");
    }
}
