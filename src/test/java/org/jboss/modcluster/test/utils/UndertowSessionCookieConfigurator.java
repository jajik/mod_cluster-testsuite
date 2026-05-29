package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

/**
 * Utility for configuring session cookie settings via Undertow subsystem.
 * Supports custom cookie names, paths, and HTTP-only flags.
 * Uses Creaper Operations API for management operations.
 */
public class UndertowSessionCookieConfigurator {

    private static final Logger log = LoggerFactory.getLogger(UndertowSessionCookieConfigurator.class);

    /**
     * Sets a custom session cookie name on the specified worker.
     * If cookieName is null, uses default (JSESSIONID).
     * Requires server reload to take effect.
     *
     * @param worker Container to configure
     * @param cookieName Custom cookie name, or null for default
     * @throws Exception if configuration fails
     */
    public void setSessionCookieName(final WildFlyWorker worker, final String cookieName) throws Exception {
        log.info("Configuring session cookie name '{}' on worker '{}'", cookieName, worker.getName());

        final Operations ops = worker.getOperations();
        final Address sessionCookieAddr = Address.subsystem("undertow")
            .and("servlet-container", "default")
            .and("setting", "session-cookie");

        // Remove existing session-cookie setting to ensure clean state.
        // On EAP 8.1.4 under CI load (Podman rootless), writeAttribute after add
        // can silently fail to apply — using remove+add ensures atomic configuration.
        if (ops.exists(sessionCookieAddr)) {
            log.debug("Removing existing session-cookie configuration");
            ops.remove(sessionCookieAddr).assertSuccess();
        }

        // Add session-cookie setting with all attributes in one operation
        Values values = Values.of("http-only", true);
        if (cookieName != null) {
            values = values.and("name", cookieName);
        }
        log.debug("Creating session-cookie configuration with name '{}'", cookieName);
        ops.add(sessionCookieAddr, values).assertSuccess();

        // Reload to apply changes (lightweight: no proxy reconfiguration or demo redeploy needed)
        log.debug("Reloading server to apply session cookie configuration");
        worker.reloadServer();

        log.info("Session cookie name '{}' configured on worker '{}'", cookieName, worker.getName());
    }
}
