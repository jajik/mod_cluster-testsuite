package org.jboss.modcluster.test.failover;

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
 * Tests for sticky session functionality with mod_cluster.
 * Verifies that requests with session cookies are routed to the same worker.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class StickySessionTest {

    private static final Logger log = LoggerFactory.getLogger(StickySessionTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    @Test
    public void testStickySessionsMaintainedAcrossRequests(TestCluster cluster, HttpClient httpClient) throws Exception {
        // Start two workers
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo";

        // Make initial request to establish session
        HttpResponse initialResponse = httpClient.get(balancerUrl);
        softly.assertThat(initialResponse.getStatusCode())
                .as("Initial request should return 200 OK")
                .isEqualTo(200);

        String sessionCookie = initialResponse.getCookie("JSESSIONID");
        softly.assertThat(sessionCookie)
                .as("Session cookie should be set")
                .isNotNull();

        log.info("Session established: {}", sessionCookie);

        // Extract route/worker from session cookie
        String initialWorker = extractWorkerFromSessionId(sessionCookie);
        log.info("Initial worker: {}", initialWorker);

        // Make 10 subsequent requests with the same session cookie
        for (int i = 0; i < 10; i++) {
            HttpResponse response = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + sessionCookie);

            softly.assertThat(response.getStatusCode())
                    .as("Request %d should return 200 OK", i + 1)
                    .isEqualTo(200);

            String currentWorker = extractWorkerFromResponse(response);
            softly.assertThat(currentWorker)
                    .as("Request %d should route to same worker", i + 1)
                    .isEqualTo(initialWorker);

            log.debug("Request {} -> Worker: {}", i + 1, currentWorker);
        }
    }

    @Test
    public void testSessionAffinityWithMultipleClients(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo";

        // Simulate 5 different clients with different sessions
        for (int client = 1; client <= 5; client++) {
            HttpResponse initialResponse = httpClient.get(balancerUrl);
            String sessionCookie = initialResponse.getCookie("JSESSIONID");
            String assignedWorker = extractWorkerFromSessionId(sessionCookie);

            log.info("Client {} assigned to worker: {}", client, assignedWorker);

            // Each client makes 5 requests with their session
            for (int req = 1; req <= 5; req++) {
                HttpResponse response = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + sessionCookie);
                String currentWorker = extractWorkerFromResponse(response);

                softly.assertThat(currentWorker)
                        .as("Client %d request %d should route to worker %s", client, req, assignedWorker)
                        .isEqualTo(assignedWorker);
            }
        }
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

    /**
     * Extract worker from response (assumes app returns worker identity).
     */
    private String extractWorkerFromResponse(HttpResponse response) {
        String body = response.getBody();
        if (body.contains("worker1")) return "worker1";
        if (body.contains("worker2")) return "worker2";

        // Fallback to session ID route
        String sessionCookie = response.getCookie("JSESSIONID");
        if (sessionCookie != null) {
            return extractWorkerFromSessionId(sessionCookie);
        }

        return "unknown";
    }
}
