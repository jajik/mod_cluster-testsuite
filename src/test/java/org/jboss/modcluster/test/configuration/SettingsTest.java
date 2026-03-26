package org.jboss.modcluster.test.configuration;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.balancer.Balancer;
import org.jboss.modcluster.test.utils.HttpClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jboss.modcluster.test.utils.ManagementClientFactory;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

/**
 * Tests for various mod_cluster settings and configuration options.
 * Undertow-only: tests exercise Undertow mod_cluster handler behavior on the balancer.
 */
@Tag("undertow")
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class SettingsTest {

    private static final Logger log = LoggerFactory.getLogger(SettingsTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that setting the management host address to wildcard (0.0.0.0) throws
     * an IllegalArgumentException in the Undertow balancer (JBEAP-5541).
     * The Undertow mod_cluster handler requires a specific bind address, not a wildcard.
     *
     * Passes if the balancer's server log contains an IllegalArgumentException for the wildcard address.
     */
    @Test
    public void testWildcardAddressThrowsException(TestCluster cluster, HttpClient httpClient) throws Exception {
        final Balancer balancer = cluster.getBalancer();

        // Get the balancer's management client to modify the public interface
        OnlineManagementClient client = ManagementClientFactory.create(
                balancer.getManagementHost(), balancer.getManagementPort());

        Operations ops = new Operations(client);

        try {
            // Set public interface to wildcard 0.0.0.0
            final Address publicInterfaceAddr = Address.of("interface", "public");
            ops.writeAttribute(publicInterfaceAddr, "inet-address", "0.0.0.0").assertSuccess();
            log.info("Set public interface to 0.0.0.0, reloading balancer");

            // Reload the balancer - this should cause an IllegalArgumentException
            try {
                new Administration(client).reload();
            } catch (Exception e) {
                log.info("Reload threw exception as expected: {}", e.getMessage());
            }

            // Close old client and wait for the server to restart
            try {
                client.close();
            } catch (Exception e) {
                log.debug("Error closing old management client: {}", e.getMessage());
            }

            Thread.sleep(15000);

            // Reconnect management client after reload
            client = ManagementClientFactory.create(
                    balancer.getManagementHost(), balancer.getManagementPort());

            // Check the balancer's server log for IllegalArgumentException
            String serverLog = balancer.getLogs();

            softly.assertThat(serverLog)
                    .as("(JBEAP-5541) Wildcard/0.0.0.0 management host address should cause IllegalArgumentException")
                    .containsPattern(".*IllegalArgumentException.*UT005082.*|.*IllegalArgumentException.*0\\.0\\.0\\.0.*");

            log.info("Wildcard address test completed - IllegalArgumentException verified in logs");

        } finally {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("Error closing management client: {}", e.getMessage());
            }
        }
    }
}
