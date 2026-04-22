package org.jboss.modcluster.test.ssl;

import org.jboss.dmr.ModelNode;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Tests Certificate Revocation List (CRL) enforcement on the MCMP management channel.
 * Verifies that revoked certificates cause affected nodes to disconnect from the cluster
 * when CRL checking is enabled on either the balancer or worker side.
 *
 * <p>Both tests use mutual TLS (mTLS) — workers and balancer present client certificates
 * for MCMP communication over HTTPS on port 8443. CRL is applied dynamically via Elytron
 * without server reload, taking effect on the next TLS handshake.</p>
 *
 * <p>Certificate mapping from {@code generate-trustchain.sh}:
 * <ul>
 *   <li>node3.server.revoked — server cert revoked in CRL (serial 1004)</li>
 *   <li>node4.client.revoked — client cert revoked in CRL (serial 1007)</li>
 *   <li>CRL file: {@code ca/intermediate/crl/intermediate.crl.pem}</li>
 * </ul></p>
 */
@ExtendWith(ModClusterTestExtension.class)
public class SslCrlTest {

    private static final Logger log = LoggerFactory.getLogger(SslCrlTest.class);

    /**
     * Verifies that a CRL on the balancer rejects a worker with a revoked client certificate.
     * Worker1 uses node4.client.revoked (revoked in CRL), worker2 uses node5.client (valid).
     * After CRL is applied to the balancer's trust-manager, worker1 should disconnect
     * while worker2 remains registered.
     */
    @Test
    public void testCrlOnBalancerRejectsRevokedWorkerCert(final TestCluster cluster) throws Exception {
        final SSLConfigurator sslConfigurator = new SSLConfigurator();

        // Configure balancer with mTLS using valid node2 certs
        sslConfigurator.configureMtlsBalancer(cluster.getBalancer(), "node2.server", "node2.client");

        // Start workers and configure mTLS
        cluster.startWorkers(2);
        sslConfigurator.configureMtlsWorker(cluster.getWorker1(), "node4.server", "node4.client.revoked");
        sslConfigurator.configureMtlsWorker(cluster.getWorker2(), "node5.server", "node5.client");

        // Wait for both workers to register with the balancer
        log.info("Waiting for both workers to register with balancer over mTLS");
        await().atMost(TestTimeouts.CLUSTER_FORMATION)
                .pollInterval(ofSeconds(5))
                .untilAsserted(() -> {
                    Map<String, ModelNode> workers = cluster.getBalancer().getWorkerInfo();
                    assertThat(workers).hasSize(2);
                });
        log.info("Both workers registered successfully");

        // Apply CRL to balancer — revokes node4.client.revoked
        sslConfigurator.addCrlToBalancer(cluster.getBalancer());
        log.info("CRL applied to balancer, waiting for worker1 to be rejected");

        // Await: worker1 (revoked cert) disconnects, worker2 (valid cert) stays
        await().atMost(TestTimeouts.CLUSTER_FORMATION)
                .pollInterval(ofSeconds(5))
                .untilAsserted(() -> {
                    Map<String, ModelNode> workers = cluster.getBalancer().getWorkerInfo();
                    assertThat(workers).doesNotContainKey("worker1");
                    assertThat(workers).containsKey("worker2");
                });

        log.info("CRL on balancer correctly rejected worker1 with revoked client cert");
    }

    /**
     * Verifies that a CRL on a worker rejects a balancer with a revoked server certificate.
     * The balancer uses node3.server.revoked (revoked in CRL), both workers use valid certs.
     * After CRL is applied to worker1's trust-manager, worker1 should disconnect
     * while worker2 remains registered.
     */
    @Test
    public void testCrlOnWorkerRejectsRevokedBalancerCert(final TestCluster cluster) throws Exception {
        final SSLConfigurator sslConfigurator = new SSLConfigurator();

        // Configure balancer with mTLS using revoked node3 server cert
        sslConfigurator.configureMtlsBalancer(cluster.getBalancer(), "node3.server.revoked", "node3.client");

        // Start workers and configure mTLS with valid certs
        cluster.startWorkers(2);
        sslConfigurator.configureMtlsWorker(cluster.getWorker1(), "node1.server", "node1.client");
        sslConfigurator.configureMtlsWorker(cluster.getWorker2(), "node2.server", "node2.client");

        // Wait for both workers to register with the balancer
        log.info("Waiting for both workers to register with balancer over mTLS");
        await().atMost(TestTimeouts.CLUSTER_FORMATION)
                .pollInterval(ofSeconds(5))
                .untilAsserted(() -> {
                    Map<String, ModelNode> workers = cluster.getBalancer().getWorkerInfo();
                    assertThat(workers).hasSize(2);
                });
        log.info("Both workers registered successfully");

        // Apply CRL to worker1 — revokes node3.server.revoked (balancer's server cert)
        sslConfigurator.addCrlToWorker(cluster.getWorker1());
        log.info("CRL applied to worker1, waiting for worker1 to disconnect");

        // Await: worker1 (rejects balancer cert) disconnects, worker2 (no CRL) stays
        await().atMost(TestTimeouts.CLUSTER_FORMATION)
                .pollInterval(ofSeconds(5))
                .untilAsserted(() -> {
                    Map<String, ModelNode> workers = cluster.getBalancer().getWorkerInfo();
                    assertThat(workers).doesNotContainKey("worker1");
                    assertThat(workers).containsKey("worker2");
                });

        log.info("CRL on worker1 correctly rejected balancer with revoked server cert");
    }
}
