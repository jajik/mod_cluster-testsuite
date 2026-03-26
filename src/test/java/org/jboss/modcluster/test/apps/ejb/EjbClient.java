package org.jboss.modcluster.test.apps.ejb;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.util.Properties;

/**
 * Standalone EJB client that invokes a remote EJB via HTTP.
 * Designed to run inside a WildFly container using {@code jboss-client.jar} on the classpath.
 *
 * <p>System properties:
 * <ul>
 *   <li>{@code remote.server.address} - host:port of the target (default: {@code 127.0.0.1:8080})</li>
 *   <li>{@code remote.endpoint.path} - HTTP invoker path (default: {@code /wildfly-services})</li>
 *   <li>{@code stateful} - if {@code true}, looks up the stateful bean; otherwise stateless</li>
 * </ul>
 *
 * <p>Output: semicolon-separated JVM routes, e.g. {@code worker1;worker1;worker1;}
 */
public class EjbClient {

    /** Number of EJB invocations per run. */
    public static final int TIMES = 10;

    /**
     * Entry point. Performs {@link #TIMES} EJB invocations and prints the JVM route for each.
     *
     * @param args unused
     * @throws NamingException if JNDI lookup fails
     */
    public static void main(String[] args) throws NamingException {
        final String addr = System.getProperty("remote.server.address", "127.0.0.1:8080");
        final String path = System.getProperty("remote.endpoint.path", "/wildfly-services");
        final boolean stateful = Boolean.getBoolean("stateful");
        final String url = "http://" + addr + path;

        InitialContext ctx = new InitialContext(getCtxProperties(url));
        String lookupName = stateful
                ? "ejb:/server/StatefulJvmRoute!" + EjbJvmRouteInterface.class.getName() + "?stateful"
                : "ejb:/server/StatelessJvmRoute!" + EjbJvmRouteInterface.class.getName();

        EjbJvmRouteInterface bean = (EjbJvmRouteInterface) ctx.lookup(lookupName);
        try {
            for (int i = 0; i < TIMES; i++) {
                System.out.print(bean.getJvmroute() + ";");
            }
        } finally {
            ctx.close();
        }
    }

    private static Properties getCtxProperties(String url) {
        Properties props = new Properties();
        props.put(Context.INITIAL_CONTEXT_FACTORY, "org.wildfly.naming.client.WildFlyInitialContextFactory");
        props.put(Context.PROVIDER_URL, url);
        return props;
    }
}
