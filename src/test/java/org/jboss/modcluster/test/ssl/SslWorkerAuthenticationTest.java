package org.jboss.modcluster.test.ssl;

import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.net.SocketException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jboss.modcluster.test.utils.WildFlyDeploymentManager.DEMO_APP;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Tests client certificate authentication (mTLS) on the data path.
 * Verifies that clients presenting a valid client certificate can access the application
 * through the balancer, while clients without a client certificate are rejected.
 *
 * <p>The balancer is configured with {@code need-client-auth=true} via mTLS,
 * requiring all connecting clients (both MCMP workers and HTTP clients) to present
 * a valid certificate signed by the trusted CA chain.</p>
 */
@ExtendWith(ModClusterTestExtension.class)
public class SslWorkerAuthenticationTest {

    private static final Logger log = LoggerFactory.getLogger(SslWorkerAuthenticationTest.class);

    private static final String TRUST_STORE_RESOURCE = "ssl/ca/intermediate/keystores/ca-chain.keystore.jks";
    private static final String CLIENT_KEYSTORE_RESOURCE = "ssl/ca/intermediate/keystores/node1.client.keystore.jks";
    private static final String KEYSTORE_PASSWORD = "testpass";

    /**
     * Verifies that an mTLS-enabled balancer accepts clients with valid client certificates
     * and rejects clients that only present a trust store (no client certificate).
     * Passes if the authenticated client receives 200 OK and the unauthenticated client
     * gets an SSL/socket exception.
     */
    @Test
    public void testClientCertificateAuthentication(final TestCluster cluster,
                                                    final HttpClient httpClient) throws Exception {
        final SSLConfigurator sslConfigurator = new SSLConfigurator();

        // Configure balancer with mTLS (need-client-auth=true)
        sslConfigurator.configureMtlsBalancer(cluster.getBalancer(), "node2.server", "node2.client");

        // Start 1 worker with mTLS (needed because balancer's MCMP is now on HTTPS)
        cluster.startWorkers(1);
        sslConfigurator.configureMtlsWorker(cluster.getWorker1(), "node1.server", "node1.client");

        final String httpsUrl = cluster.getBalancer().getHttpsUrl() + "/" + DEMO_APP + "/";

        // Configure authenticated mTLS client with both trust store and client certificate
        httpClient.configureMtlsClient(TRUST_STORE_RESOURCE, KEYSTORE_PASSWORD,
                CLIENT_KEYSTORE_RESOURCE, KEYSTORE_PASSWORD);

        // Wait for worker registration and HTTPS availability via authenticated client
        log.info("Waiting for worker to register and HTTPS to be available");
        await().atMost(TestTimeouts.CLUSTER_FORMATION)
                .pollInterval(ofSeconds(5))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.getHttpsMtls(httpsUrl);
                    assertThat(response.getStatusCode()).isEqualTo(200);
                });

        // Authenticated client: should get 200 OK with demo content
        final HttpResponse authenticatedResponse = httpClient.getHttpsMtls(httpsUrl);
        assertThat(authenticatedResponse.getStatusCode())
                .as("Authenticated client with valid client certificate should get 200 OK")
                .isEqualTo(200);
        assertThat(authenticatedResponse.getBody())
                .as("Response should contain demo application content")
                .contains("worker1");
        log.info("Authenticated client received 200 OK");

        // Unauthenticated client: trust store only, no client certificate
        // The balancer requires client auth, so this should fail with an SSL exception
        httpClient.configureTrustStore(TRUST_STORE_RESOURCE, KEYSTORE_PASSWORD);

        log.info("Testing unauthenticated client (no client certificate)");
        assertThatThrownBy(() -> httpClient.getHttpsTrusted(httpsUrl))
                .as("Unauthenticated client without client certificate should be rejected")
                .isInstanceOfAny(SSLException.class, SocketException.class);

        log.info("Unauthenticated client correctly rejected with SSL/socket exception");
    }
}
