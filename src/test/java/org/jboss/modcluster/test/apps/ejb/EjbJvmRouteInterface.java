package org.jboss.modcluster.test.apps.ejb;

import jakarta.ejb.Remote;

/**
 * Remote EJB interface for retrieving the JVM route of the handling worker.
 * Used by EJB-via-HTTP tests to verify which worker processed the invocation.
 */
@Remote
public interface EjbJvmRouteInterface {

    /**
     * Returns the JVM route (node name) of the WildFly instance handling this call.
     *
     * @return the value of the {@code jboss.node.name} system property
     */
    String getJvmroute();
}
