package org.jboss.modcluster.test.base;

/**
 * Enum representing the type of load balancer used in tests.
 * Corresponds to Jenkins matrix axis for balancer type.
 */
public enum BalancerType {
    UNDERTOW("undertow"),
    HTTPD("httpd");

    private final String name;

    BalancerType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /**
     * Get balancer type from system property or default to UNDERTOW.
     */
    public static BalancerType fromSystemProperty() {
        String property = System.getProperty("balancer.type", "undertow");
        return valueOf(property.toUpperCase());
    }

    @Override
    public String toString() {
        return name;
    }
}
