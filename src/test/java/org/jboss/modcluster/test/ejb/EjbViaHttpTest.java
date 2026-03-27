package org.jboss.modcluster.test.ejb;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.apps.ejb.EjbClientAppBuilder;
import org.jboss.modcluster.test.apps.ejb.EjbServerAppBuilder;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.ContainerUtils;
import org.jboss.modcluster.test.utils.WildFlyContainer;
import org.jboss.modcluster.test.utils.balancer.BalancerContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for EJB invocation over HTTP through the mod_cluster balancer.
 * Verifies that the HTTP invoker endpoint registers correctly on the balancer,
 * that stateful EJB calls maintain session stickiness, and that stateless
 * EJB calls succeed both directly and through the balancer.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class EjbViaHttpTest {

    private static final Logger log = LoggerFactory.getLogger(EjbViaHttpTest.class);

    private static final String DEFAULT_CONTEXT = "/wildfly-services";
    private static final String USER = "Bobo";
    private static final String PASSWORD = "qwerty";
    private static final int INVOCATION_COUNT = 10;

    private static final Address HTTP_INVOKER_ADDRESS = Address.subsystem("undertow")
            .and("server", "default-server")
            .and("host", "default-host")
            .and("setting", "http-invoker");

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that the HTTP invoker endpoint registers on the balancer and that
     * renaming the invoker path causes the old path to deregister and the new one to appear.
     */
    @Test
    public void testEndpointRegistration(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        final WildFlyContainer worker = cluster.getWorker1();
        final BalancerContainer balancer = cluster.getBalancer();

        // Wait for the default /wildfly-services context to register on the balancer
        balancer.awaitContextRegistered(worker.getName(), DEFAULT_CONTEXT);

        log.info("Default endpoint {} registered on balancer", DEFAULT_CONTEXT);

        // Rename the invoker path
        final String newPath = "IhaveNoMeaning";
        final Operations ops = worker.getOperations();
        ops.writeAttribute(HTTP_INVOKER_ADDRESS, "path", newPath).assertSuccess();
        worker.reloadServer();

        log.info("Invoker path changed to '{}', waiting for balancer update", newPath);

        // Wait for the new context to appear
        balancer.awaitContextRegistered(worker.getName(), "/" + newPath);

        // Verify the old default path is no longer registered
        final List<String> contexts = balancer.getRegisteredContexts(worker.getName());
        softly.assertThat(contexts)
                .as("Renamed context should be registered")
                .contains("/" + newPath);
        softly.assertThat(contexts)
                .as("Default context should no longer be registered after rename")
                .doesNotContain(DEFAULT_CONTEXT);

        log.info("Endpoint registration test passed: {} replaced by /{}", DEFAULT_CONTEXT, newPath);
    }

    /**
     * Verifies that stateful EJB invocations via HTTP maintain session stickiness.
     * With 3 workers, each round of 10 invocations should all go to the same worker.
     * After killing the handling worker, the next round should stick to a different one.
     *
     * <p>Undertow-only: httpd's mod_proxy_cluster does not see a JSESSIONID cookie in
     * EJB-over-HTTP invocations, so it cannot maintain session affinity for stateful beans.
     * The Undertow mod_cluster filter handles EJB session stickiness internally.
     */
    @Tag("undertow")
    @Test
    public void testStatefulEjbStickiness(TestCluster cluster) throws Exception {
        cluster.startWorkers(3);
        final BalancerContainer balancer = cluster.getBalancer();

        final File serverJar = EjbServerAppBuilder.createServerApp();
        final File clientJar = EjbClientAppBuilder.createClientApp(USER, PASSWORD);

        final List<String> workerNames = Arrays.asList("worker1", "worker2", "worker3");

        // Set up all workers: deploy EJB, add user, reload
        for (String name : workerNames) {
            setupEjbWorker(cluster.getWorkerByName(name), serverJar);
        }

        // Wait for all workers to register /wildfly-services on the balancer
        for (String workerName : workerNames) {
            balancer.awaitContextRegistered(workerName, DEFAULT_CONTEXT);
        }

        log.info("All 3 workers registered with EJB endpoint, starting stickiness test");

        final Set<String> killedWorkers = new HashSet<>();

        // Run 3 rounds: each round invokes stateful EJB, verifies stickiness, then kills the handler
        for (int round = 1; round <= 3; round++) {
            // Find a live worker to run the client from
            final String liveWorkerName = workerNames.stream()
                    .filter(n -> !killedWorkers.contains(n))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No live workers remaining"));
            final WildFlyContainer clientRunner = cluster.getWorkerByName(liveWorkerName);

            final List<String> routes = runEjbClient(clientRunner, clientJar,
                    balancer.getInternalAddress(), true);

            softly.assertThat(routes)
                    .as("Round %d: should have %d responses", round, INVOCATION_COUNT)
                    .hasSize(INVOCATION_COUNT);

            final Set<String> uniqueRoutes = new HashSet<>(routes);
            softly.assertThat(uniqueRoutes)
                    .as("Round %d: all stateful calls should go to the same worker (stickiness)", round)
                    .hasSize(1);

            final String handlingWorker = routes.get(0);
            log.info("Round {}: all {} calls handled by {}", round, INVOCATION_COUNT, handlingWorker);

            // Kill the handling worker so the next round fails over
            killedWorkers.add(handlingWorker);
            log.info("Killing worker {}", handlingWorker);
            cluster.getWorkerByName(handlingWorker).kill();

            // Wait for the balancer to detect the dead worker and deregister its contexts.
            // Without this, the next round starts while JGroups is still processing the
            // view change, which can disrupt the mod_cluster filter's routing mid-invocation.
            if (round < 3) {
                balancer.awaitContextDeregistered(handlingWorker, DEFAULT_CONTEXT);
                log.info("Worker {} deregistered from balancer, cluster stable", handlingWorker);
            }
        }

        log.info("Stateful EJB stickiness test passed with failover through all 3 workers");
    }

    /**
     * Verifies that stateless EJB invocations succeed when targeting a worker directly.
     */
    @Test
    public void testStatelessEjbDirect(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        final WildFlyContainer worker = cluster.getWorker1();
        final BalancerContainer balancer = cluster.getBalancer();

        final File serverJar = EjbServerAppBuilder.createServerApp();
        final File clientJar = EjbClientAppBuilder.createClientApp(USER, PASSWORD);

        setupEjbWorker(worker, serverJar);
        balancer.awaitContextRegistered(worker.getName(), DEFAULT_CONTEXT);

        log.info("Worker registered, testing stateless EJB direct invocation");

        final List<String> routes = runEjbClient(worker, clientJar,
                worker.getName() + ":8080", false);
        softly.assertThat(routes)
                .as("Direct invocation: all %d requests should succeed", INVOCATION_COUNT)
                .hasSize(INVOCATION_COUNT);

        log.info("Direct worker invocation succeeded: {} responses", routes.size());
    }

    /**
     * Verifies that stateless EJB invocations succeed when routing through the balancer.
     */
    @Test
    public void testStatelessEjbViaBalancer(TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        final WildFlyContainer worker = cluster.getWorker1();
        final BalancerContainer balancer = cluster.getBalancer();

        final File serverJar = EjbServerAppBuilder.createServerApp();
        final File clientJar = EjbClientAppBuilder.createClientApp(USER, PASSWORD);

        setupEjbWorker(worker, serverJar);
        balancer.awaitContextRegistered(worker.getName(), DEFAULT_CONTEXT);

        log.info("Worker registered, testing stateless EJB invocation via balancer");

        final List<String> routes = runEjbClient(worker, clientJar,
                balancer.getInternalAddress(), false);
        softly.assertThat(routes)
                .as("Balancer invocation: all %d requests should succeed", INVOCATION_COUNT)
                .hasSize(INVOCATION_COUNT);

        log.info("Balancer invocation succeeded: {} responses", routes.size());
    }

    /**
     * Deploys the EJB server JAR, adds an application user, and reloads the worker.
     *
     * @param worker    the WildFly worker container
     * @param serverJar the EJB server JAR file to deploy
     */
    private void setupEjbWorker(final WildFlyContainer worker, final File serverJar) throws Exception {
        worker.deployment().deploy(serverJar);
        log.info("Deployed server.jar to {}", worker.getName());

        final Container.ExecResult addUserResult = ContainerUtils.execInContainerWithRetry(
                worker.getContainer(),
                "/opt/wildfly/bin/add-user.sh", "-a", "-g", "users", "-u", USER, "-p", PASSWORD);

        if (addUserResult.getExitCode() != 0) {
            throw new RuntimeException("Failed to add user '" + USER + "' on " + worker.getName()
                    + ": " + addUserResult.getStderr());
        }
        log.info("Added application user '{}' to {}", USER, worker.getName());
    }

    /**
     * Runs the EJB client inside a WildFly container and parses the output into JVM route names.
     *
     * @param worker   the container to run the client in
     * @param clientJar the EJB client JAR file
     * @param address  the target address (host:port) for the EJB HTTP endpoint
     * @param stateful whether to invoke the stateful or stateless bean
     * @return list of JVM route names returned by the bean
     */
    private List<String> runEjbClient(final WildFlyContainer worker, final File clientJar,
                                      final String address, final boolean stateful) throws Exception {
        // Copy client JAR into the container (retry on transient Podman SIGPIPE)
        ContainerUtils.copyFileToContainerWithRetry(
                worker.getContainer(), clientJar.toPath(), "/tmp/client.jar");

        final Container.ExecResult result = ContainerUtils.execInContainerWithRetry(
                worker.getContainer(),
                "java",
                "-cp", "/opt/wildfly/bin/client/jboss-client.jar:/tmp/client.jar",
                "-Dremote.server.address=" + address,
                "-Dremote.endpoint.path=" + DEFAULT_CONTEXT,
                "-Dstateful=" + stateful,
                "org.jboss.modcluster.test.apps.ejb.EjbClient");

        log.info("EJB client output from {} -> {}: stdout='{}', stderr='{}'",
                worker.getName(), address,
                result.getStdout().trim(),
                result.getStderr().length() > 200
                        ? result.getStderr().substring(0, 200) + "..."
                        : result.getStderr().trim());

        assertThat(result.getExitCode())
                .as("EJB client invocation from %s to %s should succeed (exit code 0). stderr: %s",
                        worker.getName(), address, result.getStderr())
                .isZero();

        final String output = result.getStdout().trim();
        final String cleaned = output.endsWith(";") ? output.substring(0, output.length() - 1) : output;
        return Arrays.asList(cleaned.split(";"));
    }

}
