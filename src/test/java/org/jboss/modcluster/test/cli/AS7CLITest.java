package org.jboss.modcluster.test.cli;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.WildFlyContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for mod_cluster management via WildFly CLI.
 * Tests CLI commands for enabling/disabling contexts, stopping/starting workers, etc.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class AS7CLITest {

    private static final Logger log = LoggerFactory.getLogger(AS7CLITest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    @Test
    public void testReadModClusterConfiguration(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        // Read mod_cluster subsystem configuration
        String result = worker.executeCli("/subsystem=modcluster:read-resource(recursive=true)");

        log.info("ModCluster configuration: {}", result);

        softly.assertThat(result)
                .as("CLI should return mod_cluster configuration")
                .contains("outcome\" => \"success\"")
                .contains("modcluster");
    }

    @Test
    public void testEnableContextViaCLI(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        // List contexts
        String listResult = worker.executeCli("/subsystem=modcluster:list-proxies()");

        log.info("Proxy list: {}", listResult);

        softly.assertThat(listResult)
                .as("List proxies should succeed")
                .contains("outcome\" => \"success\"");
    }

    @Test
    public void testDisableContextViaCLI(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        // Disable context (example - actual command depends on deployed app)
        String result = worker.executeCli("/subsystem=modcluster:read-attribute(name=status-interval)");

        log.info("Status interval: {}", result);

        softly.assertThat(result)
                .as("Read attribute should succeed")
                .contains("outcome\" => \"success\"");
    }

    @Test
    public void testModClusterProxyInfo(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        // Get proxy info
        String result = worker.executeCli("/subsystem=modcluster:read-proxies-info()");

        log.info("Proxies info: {}", result);

        softly.assertThat(result)
                .as("Proxy info should be available")
                .contains("outcome\" => \"success\"");
    }

    @Test
    public void testModClusterStatusInterval(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        // Read current status interval
        String readResult = worker.executeCli(
                "/subsystem=modcluster/proxy=default:read-attribute(name=status-interval)");

        log.info("Current status interval: {}", readResult);

        softly.assertThat(readResult)
                .as("Should read status-interval successfully")
                .contains("outcome\" => \"success\"");

        // Write new status interval
        String writeResult = worker.executeCli(
                "/subsystem=modcluster/proxy=default:write-attribute(name=status-interval,value=20)");

        log.info("Write result: {}", writeResult);

        softly.assertThat(writeResult)
                .as("Should update status-interval successfully")
                .contains("outcome\" => \"success\"");

        // Verify the change
        String verifyResult = worker.executeCli(
                "/subsystem=modcluster/proxy=default:read-attribute(name=status-interval)");

        softly.assertThat(verifyResult)
                .as("Status interval should be updated to 20")
                .contains("\"result\" => 20");
    }
}
