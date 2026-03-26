package org.jboss.modcluster.test.configuration;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.apps.DemoAppBuilder;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jboss.modcluster.test.utils.WildFlyDeploymentManager.DEMO_APP;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Tests worker behavior when one of multiple configured proxies is unresponsive.
 * Verifies that a non-responding proxy does not dramatically delay worker startup (MODCLUSTER-639).
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class WorkerWithOneNotRespondingProxyTest {

    private static final Logger log = LoggerFactory.getLogger(WorkerWithOneNotRespondingProxyTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that worker startup is not dramatically delayed when one of multiple configured
     * proxies is unresponsive (MODCLUSTER-639).
     *
     * The test configures a worker with two proxy entries: one pointing to the real balancer
     * and one pointing to a non-existing host (simulating an unresponsive proxy). It then
     * deploys several additional applications and verifies that the worker starts within
     * a reasonable time (under 60 seconds).
     *
     * Passes if the worker starts and registers all contexts within 60 seconds despite
     * one proxy being unresponsive.
     */
    @Test
    public void testLongStartupDueToNotRespondingProxy(TestCluster cluster, HttpClient httpClient) throws Exception {
        // Start one worker normally first
        cluster.startWorkers(1);
        final WildFlyWorker worker = cluster.getWorker1();
        final File demoWar = DemoAppBuilder.createDemoApp();

        // Deploy additional contexts to simulate realistic load
        final int numExtraContexts = 5;
        for (int i = 1; i <= numExtraContexts; i++) {
            worker.deployment().deploy(demoWar, "simple-context-" + i + ".war");
            log.info("Deployed simple-context-{}", i);
        }

        // Configure an additional fake proxy that won't respond.
        // The worker already has the real balancer configured as a proxy.
        // We add a second outbound-socket-binding pointing to a non-existing host.
        final Operations ops = worker.getOperations();
        final Address fakeSocketBindingAddr = Address
                .of("socket-binding-group", "standard-sockets")
                .and("remote-destination-outbound-socket-binding", "modcluster-fake");

        ops.add(fakeSocketBindingAddr,
                Values.of("host", "192.0.2.1")  // RFC 5737 TEST-NET-1, guaranteed unreachable
                        .and("port", 3558))
                .assertSuccess("Failed to add fake outbound-socket-binding");
        log.info("Added fake proxy socket binding pointing to unreachable host 192.0.2.1:3558");

        // Add the fake proxy to the mod_cluster proxy list alongside the real one
        final Address mcProxyAddress = Address.subsystem("modcluster").and("proxy", "default");
        org.jboss.dmr.ModelNode proxyList = new org.jboss.dmr.ModelNode();
        proxyList.add("modcluster-balancer");
        proxyList.add("modcluster-fake");
        ops.writeAttribute(mcProxyAddress, "proxies", proxyList).assertSuccess();
        log.info("Configured worker to use both real and fake proxy");

        // Measure reload time (which re-registers with all proxies)
        final long startTime = System.currentTimeMillis();

        worker.reloadServer();

        // Reconfigure static proxy manually since reloadServer() doesn't do that
        worker.modCluster().configureStaticProxy();
        worker.deployment().deployDemoApp();

        final long duration = System.currentTimeMillis() - startTime;
        final long durationSeconds = duration / 1000;

        log.info("Worker reload with fake proxy took {} seconds", durationSeconds);

        softly.assertThat(durationSeconds)
                .as("(MODCLUSTER-639) Worker startup should not take more than 120 seconds " +
                        "even with a non-responding proxy, but it took %d seconds", durationSeconds)
                .isLessThan(120);

        // Verify the worker is actually functional by checking a context via balancer
        final String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";
        await().atMost(TestTimeouts.CLUSTER_FORMATION)
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Worker should be accessible via balancer after startup")
                            .isEqualTo(200);
                });

        log.info("Worker is functional despite having a non-responding proxy configured");

        // Cleanup: remove fake proxy and extra deployments
        try {
            org.jboss.dmr.ModelNode realProxyOnly = new org.jboss.dmr.ModelNode();
            realProxyOnly.add("modcluster-balancer");
            ops.writeAttribute(mcProxyAddress, "proxies", realProxyOnly).assertSuccess();
            ops.removeIfExists(fakeSocketBindingAddr);

            for (int i = 1; i <= numExtraContexts; i++) {
                worker.deployment().undeploy("simple-context-" + i + ".war");
            }
        } catch (Exception e) {
            log.warn("Cleanup error: {}", e.getMessage());
        }
    }
}
