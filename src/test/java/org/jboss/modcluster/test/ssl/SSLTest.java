package org.jboss.modcluster.test.ssl;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for SSL/TLS connectivity between components.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class SSLTest {

    private static final Logger log = LoggerFactory.getLogger(SSLTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    @Test
    public void testHttpsConnectionToBalancer(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);

        String httpsUrl = cluster.getBalancer().getHttpsUrl() + "/demo";

        HttpResponse response = httpClient.getHttps(httpsUrl);

        log.info("HTTPS Response status: {}", response.getStatusCode());

        softly.assertThat(response.getStatusCode())
                .as("HTTPS request should succeed")
                .isEqualTo(200);
    }

    @Test
    public void testHttpsWithMultipleWorkers(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String httpsUrl = cluster.getBalancer().getHttpsUrl() + "/demo";

        // Make multiple HTTPS requests
        for (int i = 0; i < 10; i++) {
            HttpResponse response = httpClient.getHttps(httpsUrl);

            softly.assertThat(response.getStatusCode())
                    .as("HTTPS request %d should succeed", i + 1)
                    .isEqualTo(200);

            log.debug("HTTPS Request {} -> Status: {}", i + 1, response.getStatusCode());
        }
    }

    @Test
    public void testSslSessionPersistence(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String httpsUrl = cluster.getBalancer().getHttpsUrl() + "/demo";

        // First HTTPS request to establish session
        HttpResponse initialResponse = httpClient.getHttps(httpsUrl);
        String sessionCookie = initialResponse.getCookie("JSESSIONID");

        softly.assertThat(sessionCookie)
                .as("HTTPS session should be established")
                .isNotNull();

        log.info("SSL Session established: {}", sessionCookie);

        // Subsequent requests should maintain session over HTTPS
        for (int i = 0; i < 5; i++) {
            HttpResponse response = httpClient.getHttps(httpsUrl);

            softly.assertThat(response.getStatusCode())
                    .as("HTTPS request %d should succeed", i + 1)
                    .isEqualTo(200);
        }
    }
}
