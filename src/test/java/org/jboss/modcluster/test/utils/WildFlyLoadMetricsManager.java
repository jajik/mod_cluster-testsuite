package org.jboss.modcluster.test.utils;

import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.ReadResourceOption;
import org.wildfly.extras.creaper.core.online.operations.Values;

import java.util.Random;

/**
 * Manages load metrics configuration and management for WildFly containers.
 * Handles custom load metric deployment, configuration, and load value updates.
 */
public class WildFlyLoadMetricsManager {

    private static final Logger log = LoggerFactory.getLogger(WildFlyLoadMetricsManager.class);

    private final WildFlyContainer container;

    WildFlyLoadMetricsManager(WildFlyContainer container) {
        this.container = container;
    }

    /**
     * Configure which built-in load metric to use.
     * By default, WildFly has "cpu" metric. This only changes config if needed.
     *
     * @param metricName Name of the metric to use ("cpu" or "heap")
     * @throws Exception if configuration fails
     */
    public void configureLoadMetric(String metricName) throws Exception {
        log.info("Configuring worker '{}' to use '{}' load metric", container.getName(), metricName);

        Operations ops = container.getOperations();
        Address dynamicProviderAddr = Address.subsystem("modcluster").and("proxy", "default").and("load-provider", "dynamic");

        if (metricName.equals("cpu")) {
            // CPU is already the default, nothing to do
            log.info("CPU metric is already configured by default");
            return;
        }

        // For non-CPU metrics: remove CPU and add the desired metric
        Address cpuMetricAddr = dynamicProviderAddr.and("load-metric", "cpu");
        if (ops.exists(cpuMetricAddr)) {
            ops.remove(cpuMetricAddr);
            log.info("Removed default CPU metric");
        }

        Address metricAddr = dynamicProviderAddr.and("load-metric", metricName);
        // Add metric with required attributes: type and weight (matching noe-tests)
        ops.add(metricAddr, Values.of("type", metricName).and("weight", 1))
                .assertSuccess("Failed to add load metric: " + metricName);
        log.info("Added load metric: {} with type={} and weight=1", metricName, metricName);

        // Reload to apply changes — uses container.reload() which properly resets the
        // management client and reconfigures the static proxy connection to the balancer.
        log.info("Reloading server to apply load metric configuration...");
        container.reload();

        log.info("Worker '{}' configured to use '{}' metric", container.getName(), metricName);
    }

    /**
     * Configure custom load metric in mod_cluster subsystem.
     * Adds the custom load metric to the dynamic load provider.
     * The custom metric module must already be available (pre-baked in image or deployed).
     * Stops and restarts the mod_cluster proxy to force metric reload.
     *
     * @param loadFilePath Path to file containing load data
     * @param capacity Maximum load value for normalization
     * @param weight Weight of this metric in load calculation
     * @throws Exception if configuration fails
     */
    public void configureCustomLoadMetric(String loadFilePath, int capacity, int weight) throws Exception {
        log.info("Configuring custom load metric on worker '{}' (file={}, capacity={}, weight={})",
                container.getName(), loadFilePath, capacity, weight);

        Operations ops = container.getOperations();

        Address dynamicProviderAddr = Address.subsystem("modcluster").and("proxy", "default").and("load-provider", "dynamic");

        // FIRST: Set history=0 and decay=0 for immediate load reflection (BEFORE adding custom metric)
        log.info("Setting history=0 and decay=0 for immediate load reflection");
        ops.writeAttribute(dynamicProviderAddr, "history", 0).assertSuccess();
        ops.writeAttribute(dynamicProviderAddr, "decay", 0).assertSuccess();

        // SECOND: Remove CPU metric (following noe-tests approach: only custom metric, no built-in metrics)
        log.info("Removing all built-in load metrics to use only custom metric");
        Address cpuMetricAddr = dynamicProviderAddr.and("load-metric", "cpu");
        ops.remove(cpuMetricAddr).assertSuccess();

        // THIRD: Add the custom load metric
        Address metricAddress = dynamicProviderAddr.and("custom-load-metric", "file-based");

        // Build properties for the custom load metric (lowercase names to match setters)
        ModelNode properties = new ModelNode();
        properties.get("loadfile").set(loadFilePath);
        properties.get("parseexpression").set("^LOAD: ([0-9]+)$");

        // Add the custom load metric using Creaper Operations
        ModelNodeResult result = ops.add(metricAddress,
                Values.empty()
                        .and("class", "org.jboss.modcluster.test.metric.FileBasedLoadMetric")
                        .and("module", "org.jboss.modcluster.test.metric")
                        .and("capacity", capacity)
                        .and("weight", weight)
                        .and("property", properties));
        result.assertSuccess();

        ops.removeIfExists(Address.subsystem("modcluster").and("proxy", "default").and("load-provider", "simple"));


        log.info("Custom load metric added to configuration, reloading server to activate module...");

        // Use container.reload() which properly closes/nullifies the cached management client,
        // creates a fresh connection, reconfigures the static proxy, and redeploys the demo app.
        // The custom metric module is pre-baked into the container image, so a reload is sufficient
        // to load it — a full JVM restart is not needed.
        container.reload();

        // Verify final configuration from management model
        Operations verifyOps = container.getOperations();
        ModelNodeResult finalConfig = verifyOps.readResource(
            Address.subsystem("modcluster").and("proxy", "default").and("load-provider", "dynamic"),
            ReadResourceOption.INCLUDE_RUNTIME, ReadResourceOption.RECURSIVE);
        log.info("Final load-provider configuration after reload (from management): {}", finalConfig.value().toJSONString(true));

        log.info("Custom load metric activated on worker '{}'", container.getName());
    }

    /**
     * Write load value to a specific file in the container.
     * Uses execInContainer to write the file directly inside the container,
     * avoiding Testcontainers' copyFileToContainer which has dependency issues
     * with commons-compress/commons-io version conflicts.
     * Includes retry logic for transient Podman SIGPIPE errors.
     *
     * @param loadValue The load value to write
     * @param filePath Path to the load file in the container
     * @throws Exception if writing the load value fails after all retries
     */
    public void writeLoadValue(int loadValue, String filePath) throws Exception {
        log.info("Setting load value {} on worker '{}' (file: {})", loadValue, container.getName(), filePath);

        final int maxRetries = 3;
        final Random random = new Random();
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Container.ExecResult result = container.getContainer().execInContainer(
                        "sh", "-c", String.format("echo 'LOAD: %d' > %s", loadValue, filePath));

                if (result.getExitCode() != 0) {
                    throw new RuntimeException("Failed to write load value to " + filePath +
                            " on worker '" + container.getName() + "': " + result.getStderr());
                }

                log.debug("Load value {} written to {} on worker '{}'", loadValue, filePath, container.getName());
                return;

            } catch (Exception e) {
                lastException = e;

                if (ContainerUtils.isTransientDockerError(e) && attempt < maxRetries) {
                    final long delayMs = attempt * 500L + random.nextInt(300);
                    log.warn("writeLoadValue failed with transient error on attempt {}/{}, retrying after {}ms: {}",
                            attempt, maxRetries, delayMs, getRootCauseMessage(e));
                    Thread.sleep(delayMs);
                } else {
                    throw e;
                }
            }
        }

        throw lastException;
    }

    /**
     * Check if custom load metric module files exist in the container.
     *
     * @return true if module files are present
     * @throws Exception if check fails
     */
    public boolean hasCustomLoadMetricModule() throws Exception {
        try {
            Container.ExecResult jarCheck = container.getContainer().execInContainer(
                "test", "-f", "/opt/wildfly/modules/org/jboss/modcluster/test/metric/main/custom-load-metric.jar"
            );
            Container.ExecResult xmlCheck = container.getContainer().execInContainer(
                "test", "-f", "/opt/wildfly/modules/org/jboss/modcluster/test/metric/main/module.xml"
            );
            return jarCheck.getExitCode() == 0 && xmlCheck.getExitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * List custom load metric module files.
     *
     * @return Directory listing or error message
     * @throws Exception if listing fails
     */
    public String listCustomLoadMetricModule() throws Exception {
        Container.ExecResult result = container.getContainer().execInContainer(
            "sh", "-c",
            "ls -la /opt/wildfly/modules/org/jboss/modcluster/test/metric/main/ 2>&1"
        );
        return result.getStdout();
    }

    /**
     * Sets a fixed load value for hot-standby testing.
     * Load of 0 marks worker as hot standby (only receives traffic when others unavailable).
     * Removes built-in metrics and configures simple load provider with fixed capacity.
     *
     * @param loadValue Fixed load value (0 = hot standby, 100 = fully available)
     * @throws Exception if configuration fails
     */
    public void setFixedLoad(final int loadValue) throws Exception {
        log.info("Setting fixed load value {} on worker '{}'", loadValue, container.getName());

        final Operations ops = container.getOperations();

        // Step 1: Remove dynamic load provider (contains CPU and other built-in metrics)
        final Address dynamicProviderAddr = Address.subsystem("modcluster")
            .and("proxy", "default")
            .and("load-provider", "dynamic");

        if (ops.exists(dynamicProviderAddr)) {
            log.debug("Removing dynamic load provider");
            ops.remove(dynamicProviderAddr).assertSuccess();
        }

        // Step 2: Add simple load provider with fixed capacity
        final Address simpleProviderAddr = Address.subsystem("modcluster")
            .and("proxy", "default")
            .and("load-provider", "simple");

        if (!ops.exists(simpleProviderAddr)) {
            log.debug("Adding simple load provider with factor={}", loadValue);
            ops.add(simpleProviderAddr, Values.of("factor", loadValue)).assertSuccess();
        } else {
            log.debug("Updating simple load provider factor to {}", loadValue);
            ops.writeAttribute(simpleProviderAddr, "factor", loadValue).assertSuccess();
        }

        // Reload to apply changes
        log.debug("Reloading server to apply fixed load configuration");
        container.reload();

        log.info("Fixed load {} configured on worker '{}'", loadValue, container.getName());
    }


    /**
     * Get the root cause message from an exception chain.
     *
     * @param throwable Exception to traverse
     * @return Root cause message or top-level message if no cause
     */
    private String getRootCauseMessage(Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        return rootCause.getMessage() != null ? rootCause.getMessage() : rootCause.toString();
    }
}
