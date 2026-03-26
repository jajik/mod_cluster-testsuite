package org.jboss.modcluster.test.apps.ejb;

import jakarta.ejb.Stateless;

/**
 * Stateless session bean that returns the JVM route of the handling worker.
 * Stateless beans have no session affinity guarantee, so calls may be
 * distributed across available workers.
 */
@Stateless
public class StatelessJvmRoute implements EjbJvmRouteInterface {

    @Override
    public String getJvmroute() {
        return System.getProperty("jboss.node.name");
    }
}
