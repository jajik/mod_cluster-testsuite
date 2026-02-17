package org.jboss.modcluster.test.base;

import org.jboss.modcluster.test.utils.BalancerContainer;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.WildFlyContainer;
import org.junit.jupiter.api.extension.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit 5 Extension for mod_cluster test lifecycle management.
 * Provides dependency injection for test infrastructure.
 */
public class ModClusterTestExtension implements BeforeEachCallback, AfterEachCallback,
        ParameterResolver, TestInstancePostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ModClusterTestExtension.class);

    private static final String BALANCER_KEY = "balancer";
    private static final String WORKER1_KEY = "worker1";
    private static final String WORKER2_KEY = "worker2";
    private static final String HTTP_CLIENT_KEY = "httpClient";

    @Override
    public void beforeEach(ExtensionContext context) {
        log.info("=== Starting test: {} ===", context.getDisplayName());

        BalancerType balancerType = BalancerType.fromSystemProperty();
        log.info("Balancer type: {}", balancerType);

        ExtensionContext.Store store = getStore(context);

        // Create and start balancer
        BalancerContainer balancer = BalancerContainer.create(balancerType);
        balancer.start();
        store.put(BALANCER_KEY, balancer);

        // Create HTTP client
        store.put(HTTP_CLIENT_KEY, new HttpClient());

        log.info("Balancer started: {}", balancer.getHttpUrl());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ExtensionContext.Store store = getStore(context);

        // Stop workers if started
        WildFlyContainer worker1 = store.get(WORKER1_KEY, WildFlyContainer.class);
        if (worker1 != null) {
            try {
                worker1.stop();
            } catch (Exception e) {
                log.error("Error stopping worker1", e);
            }
        }

        WildFlyContainer worker2 = store.get(WORKER2_KEY, WildFlyContainer.class);
        if (worker2 != null) {
            try {
                worker2.stop();
            } catch (Exception e) {
                log.error("Error stopping worker2", e);
            }
        }

        // Stop balancer
        BalancerContainer balancer = store.get(BALANCER_KEY, BalancerContainer.class);
        if (balancer != null) {
            try {
                balancer.stop();
            } catch (Exception e) {
                log.error("Error stopping balancer", e);
            }
        }

        log.info("=== Finished test: {} ===", context.getDisplayName());
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        return type == TestCluster.class ||
               type == BalancerContainer.class ||
               type == HttpClient.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        ExtensionContext.Store store = getStore(extensionContext);

        if (type == TestCluster.class) {
            return new TestCluster(store);
        } else if (type == BalancerContainer.class) {
            return store.get(BALANCER_KEY, BalancerContainer.class);
        } else if (type == HttpClient.class) {
            return store.get(HTTP_CLIENT_KEY, HttpClient.class);
        }

        return null;
    }

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
        // Can be used for field injection if needed
    }

    private ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getStore(ExtensionContext.Namespace.create(getClass(), context.getRequiredTestMethod()));
    }

    /**
     * Test cluster context providing access to balancer and workers.
     */
    public static class TestCluster {
        private final ExtensionContext.Store store;

        TestCluster(ExtensionContext.Store store) {
            this.store = store;
        }

        public BalancerContainer getBalancer() {
            return store.get(BALANCER_KEY, BalancerContainer.class);
        }

        public HttpClient getHttpClient() {
            return store.get(HTTP_CLIENT_KEY, HttpClient.class);
        }

        /**
         * Start worker nodes.
         */
        public void startWorkers(int count) {
            BalancerContainer balancer = getBalancer();

            if (count >= 1) {
                WildFlyContainer worker1 = new WildFlyContainer("worker1", balancer);
                worker1.start();
                store.put(WORKER1_KEY, worker1);
            }

            if (count >= 2) {
                WildFlyContainer worker2 = new WildFlyContainer("worker2", balancer);
                worker2.start();
                store.put(WORKER2_KEY, worker2);
            }
        }

        public WildFlyContainer getWorker1() {
            return store.get(WORKER1_KEY, WildFlyContainer.class);
        }

        public WildFlyContainer getWorker2() {
            return store.get(WORKER2_KEY, WildFlyContainer.class);
        }
    }
}
