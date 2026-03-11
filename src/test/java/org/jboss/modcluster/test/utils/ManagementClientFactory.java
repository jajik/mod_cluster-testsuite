package org.jboss.modcluster.test.utils;

import org.wildfly.extras.creaper.core.ManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineOptions;

import java.io.IOException;

/**
 * Factory for creating Creaper {@link OnlineManagementClient} instances
 * with consistent auth credentials and timeout defaults.
 */
public class ManagementClientFactory {

    private static final int DEFAULT_CONNECTION_TIMEOUT = 10_000;
    private static final int DEFAULT_BOOT_TIMEOUT = 120_000;

    public static OnlineManagementClient create(String host, int port) throws IOException {
        return ManagementClient.online(
            OnlineOptions.standalone()
                .hostAndPort(host, port)
                .auth("admin", "admin")
                .connectionTimeout(DEFAULT_CONNECTION_TIMEOUT)
                .bootTimeout(DEFAULT_BOOT_TIMEOUT)
                .build()
        );
    }
}
