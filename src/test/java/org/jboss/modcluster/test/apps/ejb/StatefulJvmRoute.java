package org.jboss.modcluster.test.apps.ejb;

import jakarta.ejb.Stateful;

/**
 * Stateful session bean that returns the JVM route of the handling worker.
 * Stateful beans maintain session affinity, so repeated calls from the same
 * client proxy should always reach the same WildFly instance.
 */
@Stateful
public class StatefulJvmRoute implements EjbJvmRouteInterface {

    @Override
    public String getJvmroute() {
        return System.getProperty("jboss.node.name");
    }
}
