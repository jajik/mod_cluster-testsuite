package org.jboss.modcluster.test.ssl;

import org.jboss.dmr.ModelNode;
import org.jboss.modcluster.test.base.BalancerType;
import org.jboss.modcluster.test.utils.balancer.BalancerContainer;
import org.jboss.modcluster.test.utils.ContainerUtils;
import org.jboss.modcluster.test.utils.ManagementClientFactory;
import org.jboss.modcluster.test.utils.WildFlyContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

import java.io.File;
import java.net.URL;

/**
 * Configures SSL/TLS on both workers and balancers.
 *
 * <p>For workers (always WildFly/EAP): uses Elytron to create key-stores, key-managers,
 * trust-managers, and SSL contexts linked to the Undertow HTTPS listener.</p>
 *
 * <p>For balancers: dispatches based on balancer type.
 * <ul>
 *   <li><b>Undertow</b>: uses Elytron (same as workers)</li>
 *   <li><b>httpd</b>: uses mod_ssl with PEM certificates, configured via Apache config files</li>
 * </ul></p>
 *
 * <p>Supports server-only SSL, mutual TLS (mTLS) with MCMP-over-SSL, and CRL enforcement.</p>
 */
public class SSLConfigurator {

    private static final Logger log = LoggerFactory.getLogger(SSLConfigurator.class);

    private static final String KEYSTORE_PASSWORD = "testpass";
    private static final String SSL_DIR = "/opt/wildfly/standalone/configuration/ssl";
    private static final String KEYSTORES_RESOURCE_DIR = "ssl/ca/intermediate/keystores/";
    private static final String CERTS_RESOURCE_DIR = "ssl/ca/intermediate/certs/";
    private static final String KEYS_RESOURCE_DIR = "ssl/ca/intermediate/private/";
    private static final String CRL_RESOURCE_PATH = "ssl/ca/intermediate/crl/intermediate.crl.pem";
    private static final int MANAGEMENT_PORT = 9990;

    private static final String HTTPD_SSL_DIR = "/usr/local/apache2/ssl";
    private static final String HTTPD_CONF_EXTRA = "/usr/local/apache2/conf/extra";

    // ---- Worker SSL (always Elytron) ----

    /**
     * Configures Elytron SSL on a worker using proper PKI certificates.
     * Copies the correct node-specific server keystore and CA chain trust store into the container,
     * creates Elytron resources (key-store, key-manager, trust-manager, server-ssl-context),
     * and links the SSL context to the Undertow HTTPS listener.
     *
     * @param worker container to configure
     * @throws Exception if configuration fails
     */
    public void configureWorker(final WildFlyContainer worker) throws Exception {
        log.info("Configuring SSL on worker '{}'", worker.getName());

        copyKeystores(worker.getContainer(), worker.getName());

        final Operations ops = worker.getOperations();
        createElytronResources(ops);
        linkToHttpsListener(ops);

        worker.reload();

        log.info("SSL configured successfully on worker '{}'", worker.getName());
    }

    /**
     * Configures full mutual TLS and MCMP-over-SSL on a worker.
     * Copies server, client, and trust keystores into the container, creates Elytron resources
     * for both server and client SSL contexts with {@code need-client-auth=true},
     * links the server SSL context to the HTTPS listener, and switches the mod_cluster
     * proxy's MCMP management channel to use TLS on the balancer's MCMP SSL port.
     *
     * <p>The worker's listener stays as "default" (HTTP) so the balancer's health-check and
     * proxy connections go to the worker's plain HTTP port. Only the MCMP management channel
     * (worker → balancer) uses TLS, which is where CRL enforcement takes effect.</p>
     *
     * @param worker worker container to configure
     * @param serverKeystore server keystore name prefix (e.g., "node1.server" or "node4.server")
     * @param clientKeystore client keystore name prefix (e.g., "node1.client" or "node4.client.revoked")
     * @throws Exception if configuration fails
     */
    public void configureMtlsWorker(final WildFlyContainer worker, final String serverKeystore,
                                     final String clientKeystore) throws Exception {
        log.info("Configuring mTLS + MCMP-over-SSL on worker '{}' (server={}, client={})",
                worker.getName(), serverKeystore, clientKeystore);

        copyMtlsKeystores(worker.getContainer(), serverKeystore, clientKeystore);

        final Operations ops = worker.getOperations();
        createMtlsElytronResources(ops);
        linkToHttpsListener(ops);

        // Write MCMP-over-SSL settings to management model BEFORE reload —
        // socket binding port changes only take effect after a reload.
        final Address mcProxy = Address.subsystem("modcluster").and("proxy", "default");
        ops.writeAttribute(mcProxy, "ssl-context", "clientSSLContext").assertSuccess();

        // Use balancer-type-specific MCMP SSL port (8443 for Undertow, 6666 for httpd)
        int mcmpSslPort = worker.getBalancer().getMcmpSslPort();
        final Address outboundSocket = Address.of("socket-binding-group", "standard-sockets")
                .and("remote-destination-outbound-socket-binding", "modcluster-balancer");
        ops.writeAttribute(outboundSocket, "port", mcmpSslPort).assertSuccess();

        // Also set on the manager so any future reload() calls preserve these settings
        worker.modCluster().setMcmpSslConfig("default", mcmpSslPort, "clientSSLContext");

        // reloadServer() applies all changes without re-running configureStaticProxy()
        worker.reloadServer();

        log.info("mTLS + MCMP-over-SSL configured successfully on worker '{}'", worker.getName());
    }

    /**
     * Adds a Certificate Revocation List (CRL) to the trust-manager on a worker.
     * Copies the CRL file into the container, configures the trust-manager to use it,
     * and reloads the server to force existing TLS connections to drop.
     * Workers reconnect with a new TLS handshake that checks the CRL.
     *
     * @param worker worker container to add CRL to
     * @throws Exception if configuration fails
     */
    public void addCrlToWorker(final WildFlyContainer worker) throws Exception {
        log.info("Adding CRL to worker '{}'", worker.getName());

        copyFileWithRetry(worker.getContainer(), CRL_RESOURCE_PATH, SSL_DIR + "/intermediate.crl.pem");

        final Operations ops = worker.getOperations();
        writeCrlAttribute(ops);

        // Reload to drop existing TLS connections — new handshakes will check the CRL
        worker.reloadServer();

        log.info("CRL added successfully to worker '{}'", worker.getName());
    }

    // ---- Balancer SSL (dispatched by type) ----

    /**
     * Configures SSL on the balancer's data path (port 8443).
     * Dispatches to Undertow Elytron or httpd mod_ssl based on balancer type.
     *
     * @param balancer balancer container to configure
     * @throws Exception if configuration fails
     */
    public void configureBalancer(final BalancerContainer balancer) throws Exception {
        if (balancer.getType() == BalancerType.HTTPD) {
            configureHttpdBalancerSsl(balancer);
        } else {
            configureUndertowBalancerSsl(balancer);
        }
    }

    /**
     * Configures mutual TLS (mTLS) and MCMP-over-SSL on the balancer.
     * Dispatches to Undertow Elytron or httpd mod_ssl based on balancer type.
     *
     * @param balancer balancer container to configure
     * @param serverKeystore server keystore name prefix (e.g., "node2.server")
     * @param clientKeystore client keystore name prefix (e.g., "node2.client")
     * @throws Exception if configuration fails
     */
    public void configureMtlsBalancer(final BalancerContainer balancer, final String serverKeystore,
                                       final String clientKeystore) throws Exception {
        if (balancer.getType() == BalancerType.HTTPD) {
            configureHttpdMtlsBalancer(balancer, serverKeystore, clientKeystore);
        } else {
            configureUndertowMtlsBalancer(balancer, serverKeystore, clientKeystore);
        }
    }

    /**
     * Adds a Certificate Revocation List (CRL) to the balancer.
     * Dispatches to Undertow Elytron or httpd mod_ssl based on balancer type.
     *
     * @param balancer balancer container to add CRL to
     * @throws Exception if configuration fails
     */
    public void addCrlToBalancer(final BalancerContainer balancer) throws Exception {
        if (balancer.getType() == BalancerType.HTTPD) {
            addCrlToHttpdBalancer(balancer);
        } else {
            addCrlToUndertowBalancer(balancer);
        }
    }

    // ---- Undertow balancer SSL (Elytron) ----

    /**
     * Configures Elytron SSL on an Undertow balancer using the localhost server certificate.
     */
    private void configureUndertowBalancerSsl(final BalancerContainer balancer) throws Exception {
        log.info("Configuring SSL on Undertow balancer");

        copyKeystores(balancer.getContainer(), "balancer");

        try (OnlineManagementClient client = ManagementClientFactory.create(
                balancer.getContainer().getHost(),
                balancer.getContainer().getMappedPort(MANAGEMENT_PORT))) {
            final Operations ops = new Operations(client);
            createElytronResources(ops);
            linkToHttpsListener(ops);

            new Administration(client).reload();
        }

        log.info("SSL configured successfully on Undertow balancer");
    }

    /**
     * Configures mTLS + MCMP-over-SSL on an Undertow balancer.
     */
    private void configureUndertowMtlsBalancer(final BalancerContainer balancer, final String serverKeystore,
                                                final String clientKeystore) throws Exception {
        log.info("Configuring mTLS + MCMP-over-SSL on Undertow balancer (server={}, client={})",
                serverKeystore, clientKeystore);

        copyMtlsKeystores(balancer.getContainer(), serverKeystore, clientKeystore);

        try (OnlineManagementClient client = ManagementClientFactory.create(
                balancer.getContainer().getHost(),
                balancer.getContainer().getMappedPort(MANAGEMENT_PORT))) {
            final Operations ops = new Operations(client);
            createMtlsElytronResources(ops);
            linkToHttpsListener(ops);
            configureMcmpOverSslOnUndertowBalancer(ops);

            new Administration(client).reload();
        }

        log.info("mTLS + MCMP-over-SSL configured successfully on Undertow balancer");
    }

    /**
     * Adds CRL to an Undertow balancer via Elytron trust-manager.
     */
    private void addCrlToUndertowBalancer(final BalancerContainer balancer) throws Exception {
        log.info("Adding CRL to Undertow balancer");

        copyFileWithRetry(balancer.getContainer(), CRL_RESOURCE_PATH, SSL_DIR + "/intermediate.crl.pem");

        try (OnlineManagementClient client = ManagementClientFactory.create(
                balancer.getContainer().getHost(),
                balancer.getContainer().getMappedPort(MANAGEMENT_PORT))) {
            final Operations ops = new Operations(client);
            writeCrlAttribute(ops);

            new Administration(client).reload();
        }

        log.info("CRL added successfully to Undertow balancer");
    }

    // ---- httpd balancer SSL (mod_ssl with PEM certs) ----

    /**
     * Configures SSL on an httpd balancer's data path (port 8443).
     * Copies PEM certificates into the container, strips key passphrase,
     * writes an Apache SSL config, and performs a graceful restart.
     */
    private void configureHttpdBalancerSsl(final BalancerContainer balancer) throws Exception {
        log.info("Configuring SSL on httpd balancer (data path)");

        GenericContainer<?> container = balancer.getContainer();

        // Copy PEM certificates into container
        String certPrefix = "localhost.server";
        copyPemCerts(container, certPrefix);

        // Strip key passphrase (httpd needs unencrypted key)
        stripKeyPassphrase(container, certPrefix);

        // Write SSL VirtualHost config for data path (port 8443)
        String sslConfig =
                "LoadModule ssl_module modules/mod_ssl.so\n" +
                "Listen 8443\n" +
                "<VirtualHost *:8443>\n" +
                "    SSLEngine on\n" +
                "    SSLCertificateFile " + HTTPD_SSL_DIR + "/server.cert.pem\n" +
                "    SSLCertificateKeyFile " + HTTPD_SSL_DIR + "/server.nopass.key.pem\n" +
                "    SSLCACertificateFile " + HTTPD_SSL_DIR + "/ca-chain.cert.pem\n" +
                "</VirtualHost>\n";

        writeConfigToContainer(container, sslConfig, HTTPD_CONF_EXTRA + "/ssl-data.conf");

        // Graceful restart to pick up SSL config
        balancer.reload();

        log.info("SSL configured successfully on httpd balancer");
    }

    /**
     * Configures mTLS on an httpd balancer (both MCMP port 6666 and data path 8443).
     * SSLVerifyClient require forces client certificate authentication.
     */
    private void configureHttpdMtlsBalancer(final BalancerContainer balancer, final String serverKeystore,
                                             final String clientKeystore) throws Exception {
        log.info("Configuring mTLS on httpd balancer (server={}, client={})", serverKeystore, clientKeystore);

        GenericContainer<?> container = balancer.getContainer();

        // Copy PEM certificates (use the server keystore prefix to find cert/key)
        copyPemCerts(container, serverKeystore);

        // Strip key passphrase
        stripKeyPassphrase(container, serverKeystore);

        // Comment out the non-SSL VirtualHost on port 6666 in mod_proxy_cluster.conf.
        // Apache cannot mix SSL and non-SSL VirtualHosts on the same port — the non-SSL
        // VirtualHost would be matched first and reject SSL connections from workers.
        container.execInContainer("sh", "-c",
                "sed -i '/<VirtualHost \\*:6666>/,/<\\/VirtualHost>/s/^/#/' " +
                "/usr/local/apache2/conf/extra/mod_proxy_cluster.conf");

        // Write SSL config for mTLS on both MCMP (6666) and data path (8443).
        // MCMP port uses SSLVerifyClient optional — workers present client certs (validated
        // against CA chain and CRL), but the test-code McmpClient can query without one.
        // Data path uses SSLVerifyClient require — clients must present a valid client cert.
        String sslConfig =
                "LoadModule ssl_module modules/mod_ssl.so\n" +
                "Listen 8443\n" +
                "\n" +
                "# MCMP mTLS on port 6666 (replaces the non-SSL VirtualHost)\n" +
                "<VirtualHost *:6666>\n" +
                "    SSLEngine on\n" +
                "    SSLCertificateFile " + HTTPD_SSL_DIR + "/server.cert.pem\n" +
                "    SSLCertificateKeyFile " + HTTPD_SSL_DIR + "/server.nopass.key.pem\n" +
                "    SSLCACertificateFile " + HTTPD_SSL_DIR + "/ca-chain.cert.pem\n" +
                "    SSLVerifyClient optional\n" +
                "    SSLVerifyDepth 3\n" +
                "    EnableMCMPReceive\n" +
                "    <Location />\n" +
                "        Require all granted\n" +
                "    </Location>\n" +
                "    <Location /mod_cluster_manager>\n" +
                "        SetHandler mod_cluster-manager\n" +
                "        Require all granted\n" +
                "    </Location>\n" +
                "</VirtualHost>\n" +
                "\n" +
                "# Data path mTLS on port 8443\n" +
                "<VirtualHost *:8443>\n" +
                "    SSLEngine on\n" +
                "    SSLCertificateFile " + HTTPD_SSL_DIR + "/server.cert.pem\n" +
                "    SSLCertificateKeyFile " + HTTPD_SSL_DIR + "/server.nopass.key.pem\n" +
                "    SSLCACertificateFile " + HTTPD_SSL_DIR + "/ca-chain.cert.pem\n" +
                "    SSLVerifyClient require\n" +
                "    SSLVerifyDepth 3\n" +
                "</VirtualHost>\n";

        writeConfigToContainer(container, sslConfig, HTTPD_CONF_EXTRA + "/ssl-mtls.conf");

        // Graceful restart to pick up mTLS config
        balancer.reload();

        // Switch the internal McmpClient to HTTPS so test-code queries work on the SSL port
        balancer.enableMcmpSsl();

        log.info("mTLS configured successfully on httpd balancer");
    }

    /**
     * Adds CRL to an httpd balancer by appending SSLCARevocationFile directives
     * and performing a graceful restart.
     */
    private void addCrlToHttpdBalancer(final BalancerContainer balancer) throws Exception {
        log.info("Adding CRL to httpd balancer");

        GenericContainer<?> container = balancer.getContainer();

        // Copy CRL file into container
        copyFileWithRetry(container, CRL_RESOURCE_PATH, HTTPD_SSL_DIR + "/intermediate.crl.pem");

        // Write CRL config that applies to all SSL VirtualHosts.
        // Use 'leaf' mode because we only have the intermediate CA's CRL, not the root CA's.
        // 'chain' mode would reject ALL certs because the root CA CRL is missing.
        String crlConfig =
                "# CRL configuration (applied globally)\n" +
                "SSLCARevocationFile " + HTTPD_SSL_DIR + "/intermediate.crl.pem\n" +
                "SSLCARevocationCheck leaf\n";

        writeConfigToContainer(container, crlConfig, HTTPD_CONF_EXTRA + "/ssl-crl.conf");

        // Graceful restart to force new TLS handshakes with CRL checking
        balancer.reload();

        log.info("CRL added successfully to httpd balancer");
    }

    // ---- httpd SSL helpers ----

    /**
     * Copies PEM certificate, key, and CA chain files into the httpd container.
     *
     * @param container the httpd container
     * @param certPrefix certificate name prefix (e.g., "localhost.server", "node2.server")
     */
    private void copyPemCerts(final GenericContainer<?> container, final String certPrefix) {
        String certResource = CERTS_RESOURCE_DIR + certPrefix + ".cert.pem";
        String keyResource = KEYS_RESOURCE_DIR + certPrefix + ".key.pem";
        String caChainResource = CERTS_RESOURCE_DIR + "ca-chain.cert.pem";

        // Create SSL directory in container
        try {
            container.execInContainer("mkdir", "-p", HTTPD_SSL_DIR);
        } catch (Exception e) {
            log.debug("SSL dir may already exist: {}", e.getMessage());
        }

        log.debug("Copying server cert '{}' to httpd container", certResource);
        copyFileWithRetry(container, certResource, HTTPD_SSL_DIR + "/server.cert.pem");

        log.debug("Copying server key '{}' to httpd container", keyResource);
        copyFileWithRetry(container, keyResource, HTTPD_SSL_DIR + "/server.key.pem");

        log.debug("Copying CA chain to httpd container");
        copyFileWithRetry(container, caChainResource, HTTPD_SSL_DIR + "/ca-chain.cert.pem");
    }

    /**
     * Strips the passphrase from the server key using openssl.
     * httpd mod_ssl requires unencrypted keys (or SSLPassPhraseDialog which is harder to automate).
     *
     * <p>First attempts to run openssl inside the container. If the container lacks openssl,
     * falls back to running openssl on the host and copying the unencrypted key into the container.</p>
     *
     * @param container the httpd container
     * @param certPrefix the certificate prefix (e.g., "localhost.server", "node2.server")
     * @throws Exception if stripping fails both in-container and on the host
     */
    private void stripKeyPassphrase(final GenericContainer<?> container, final String certPrefix) throws Exception {
        // Try in-container first (some images include openssl)
        org.testcontainers.containers.Container.ExecResult result = container.execInContainer(
                "openssl", "rsa",
                "-in", HTTPD_SSL_DIR + "/server.key.pem",
                "-out", HTTPD_SSL_DIR + "/server.nopass.key.pem",
                "-passin", "pass:" + KEYSTORE_PASSWORD);

        if (result.getExitCode() == 0) {
            log.debug("Key passphrase stripped in container");
            return;
        }

        log.info("Container lacks openssl, stripping passphrase on host");

        String keyResource = KEYS_RESOURCE_DIR + certPrefix + ".key.pem";
        URL keyUrl = Thread.currentThread().getContextClassLoader().getResource(keyResource);
        if (keyUrl == null) {
            throw new RuntimeException("Cannot find key resource '" + keyResource + "' on classpath");
        }

        File tempKey = File.createTempFile("server-nopass", ".key.pem");
        tempKey.deleteOnExit();

        ProcessBuilder pb = new ProcessBuilder(
                "openssl", "rsa",
                "-in", new File(keyUrl.toURI()).getAbsolutePath(),
                "-out", tempKey.getAbsolutePath(),
                "-passin", "pass:" + KEYSTORE_PASSWORD);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        int exitCode = proc.waitFor();

        if (exitCode != 0) {
            String output = new String(proc.getInputStream().readAllBytes());
            throw new RuntimeException("Failed to strip key passphrase on host: " + output);
        }

        container.copyFileToContainer(
                MountableFile.forHostPath(tempKey.getAbsolutePath(), 0644),
                HTTPD_SSL_DIR + "/server.nopass.key.pem");
        tempKey.delete();
        log.debug("Key passphrase stripped on host and copied to container");
    }

    /**
     * Writes a configuration string to a file inside the container.
     *
     * @param container the target container
     * @param content the configuration content
     * @param containerPath the destination file path inside the container
     * @throws Exception if writing fails
     */
    private void writeConfigToContainer(final GenericContainer<?> container, final String content,
                                         final String containerPath) throws Exception {
        // Use sh -c with heredoc to write the config file
        org.testcontainers.containers.Container.ExecResult result = container.execInContainer(
                "sh", "-c", "cat > " + containerPath + " << 'SSLEOF'\n" + content + "SSLEOF");

        if (result.getExitCode() != 0) {
            throw new RuntimeException("Failed to write config to " + containerPath + ": " + result.getStderr());
        }
        log.debug("Config written to {}", containerPath);
    }

    // ---- Private helpers for keystore operations ----

    private static final int MAX_COPY_RETRIES = 5;
    private static final long COPY_RETRY_BASE_DELAY_MS = 500;

    /**
     * Copies the appropriate server keystore and CA chain trust store into the container.
     * Maps container name to the corresponding node keystore:
     * worker1 to node1, worker2 to node2, balancer to localhost.
     * Uses file mode 0644 so the WildFly process can read the files regardless of container user.
     * Retries on transient Podman SIGPIPE errors.
     */
    private void copyKeystores(final GenericContainer<?> container, final String containerName) {
        final String nodeName = mapToNodeName(containerName);
        final String serverKeystoreResource = KEYSTORES_RESOURCE_DIR + nodeName + ".server.keystore.jks";
        final String trustStoreResource = KEYSTORES_RESOURCE_DIR + "ca-chain.keystore.jks";

        log.debug("Copying server keystore '{}' into container '{}'", serverKeystoreResource, containerName);
        copyFileWithRetry(container, serverKeystoreResource, SSL_DIR + "/server.keystore.jks");

        log.debug("Copying CA chain trust store into container '{}'", containerName);
        copyFileWithRetry(container, trustStoreResource, SSL_DIR + "/ca-chain.keystore.jks");
    }

    /**
     * Copies server, client, and CA chain trust keystores into the container for mTLS.
     *
     * @param container target container
     * @param serverKeystore server keystore name prefix (e.g., "node1.server" or "node3.server.revoked")
     * @param clientKeystore client keystore name prefix (e.g., "node1.client" or "node4.client.revoked")
     */
    private void copyMtlsKeystores(final GenericContainer<?> container, final String serverKeystore,
                                    final String clientKeystore) {
        final String serverResource = KEYSTORES_RESOURCE_DIR + serverKeystore + ".keystore.jks";
        final String clientResource = KEYSTORES_RESOURCE_DIR + clientKeystore + ".keystore.jks";
        final String trustResource = KEYSTORES_RESOURCE_DIR + "ca-chain.keystore.jks";

        log.debug("Copying server keystore '{}'", serverResource);
        copyFileWithRetry(container, serverResource, SSL_DIR + "/server.keystore.jks");

        log.debug("Copying client keystore '{}'", clientResource);
        copyFileWithRetry(container, clientResource, SSL_DIR + "/client.keystore.jks");

        log.debug("Copying CA chain trust store");
        copyFileWithRetry(container, trustResource, SSL_DIR + "/ca-chain.keystore.jks");
    }

    /**
     * Copies a classpath resource into the container with retry logic for transient Podman SIGPIPE errors.
     *
     * @param container target container
     * @param classpathResource resource path on classpath
     * @param containerPath destination path inside the container
     */
    private void copyFileWithRetry(final GenericContainer<?> container, final String classpathResource,
                                   final String containerPath) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_COPY_RETRIES; attempt++) {
            try {
                container.copyFileToContainer(
                        MountableFile.forClasspathResource(classpathResource, 0644),
                        containerPath);
                return;
            } catch (Exception e) {
                lastException = e;

                if (ContainerUtils.isTransientDockerError(e) && attempt < MAX_COPY_RETRIES) {
                    long delay = COPY_RETRY_BASE_DELAY_MS * attempt;
                    log.warn("Transient error copying '{}' on attempt {}/{}, retrying after {}ms",
                            classpathResource, attempt, MAX_COPY_RETRIES, delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during copy retry", ie);
                    }
                } else {
                    break;
                }
            }
        }

        throw new RuntimeException("Failed to copy '" + classpathResource + "' after " + MAX_COPY_RETRIES + " attempts",
                lastException);
    }

    /**
     * Maps container name to the corresponding node name used in keystore filenames.
     *
     * @param containerName container name (e.g. "worker1", "worker2", "balancer")
     * @return node name (e.g. "node1", "node2", "localhost")
     */
    private String mapToNodeName(final String containerName) {
        if ("balancer".equals(containerName)) {
            return "localhost";
        }
        return containerName.replace("worker", "node");
    }

    // ---- Elytron resource creation ----

    /**
     * Creates Elytron key-store, key-manager, trust-manager, and server-ssl-context resources.
     * Used for server-only SSL (no client certificate, no mTLS).
     *
     * @param ops Creaper operations handle
     * @throws Exception if any management operation fails
     */
    private void createElytronResources(final Operations ops) throws Exception {
        final ModelNode credentialRef = new ModelNode();
        credentialRef.get("clear-text").set(KEYSTORE_PASSWORD);

        // Trust key-store (CA chain)
        final Address trustKeyStoreAddr = Address.subsystem("elytron").and("key-store", "trustKeyStore");
        if (!ops.exists(trustKeyStoreAddr)) {
            log.debug("Creating trustKeyStore key-store");
            ops.add(trustKeyStoreAddr, Values.of("path", SSL_DIR + "/ca-chain.keystore.jks")
                    .and("credential-reference", credentialRef)
                    .and("type", "JKS"))
                    .assertSuccess();
        }

        // Trust manager
        final Address trustManagerAddr = Address.subsystem("elytron").and("trust-manager", "trustStoreManager");
        if (!ops.exists(trustManagerAddr)) {
            log.debug("Creating trustStoreManager trust-manager");
            ops.add(trustManagerAddr, Values.of("key-store", "trustKeyStore"))
                    .assertSuccess();
        }

        // Server key-store
        final Address serverKeyStoreAddr = Address.subsystem("elytron").and("key-store", "serverKeyStore");
        if (!ops.exists(serverKeyStoreAddr)) {
            log.debug("Creating serverKeyStore key-store");
            ops.add(serverKeyStoreAddr, Values.of("path", SSL_DIR + "/server.keystore.jks")
                    .and("credential-reference", credentialRef)
                    .and("type", "JKS"))
                    .assertSuccess();
        }

        // Server key-manager
        final Address serverKeyManagerAddr = Address.subsystem("elytron").and("key-manager", "serverKeyManager");
        if (!ops.exists(serverKeyManagerAddr)) {
            log.debug("Creating serverKeyManager key-manager");
            ops.add(serverKeyManagerAddr, Values.of("key-store", "serverKeyStore")
                    .and("credential-reference", credentialRef))
                    .assertSuccess();
        }

        // Server SSL context
        final Address sslContextAddr = Address.subsystem("elytron").and("server-ssl-context", "serverSSLContext");
        if (!ops.exists(sslContextAddr)) {
            log.debug("Creating serverSSLContext server-ssl-context");
            ops.add(sslContextAddr, Values.of("key-manager", "serverKeyManager")
                    .and("trust-manager", "trustStoreManager"))
                    .assertSuccess();
        }
    }

    /**
     * Creates Elytron resources for full mutual TLS: trust store, trust-manager,
     * server key-store/key-manager with need-client-auth, client key-store/key-manager,
     * server-ssl-context, and client-ssl-context.
     *
     * @param ops Creaper operations handle
     * @throws Exception if any management operation fails
     */
    private void createMtlsElytronResources(final Operations ops) throws Exception {
        final ModelNode credentialRef = new ModelNode();
        credentialRef.get("clear-text").set(KEYSTORE_PASSWORD);

        // Trust key-store (CA chain)
        final Address trustKeyStoreAddr = Address.subsystem("elytron").and("key-store", "trustKeyStore");
        if (!ops.exists(trustKeyStoreAddr)) {
            log.debug("Creating trustKeyStore key-store");
            ops.add(trustKeyStoreAddr, Values.of("path", SSL_DIR + "/ca-chain.keystore.jks")
                    .and("credential-reference", credentialRef)
                    .and("type", "JKS"))
                    .assertSuccess();
        }

        // Trust manager
        final Address trustManagerAddr = Address.subsystem("elytron").and("trust-manager", "trustStoreManager");
        if (!ops.exists(trustManagerAddr)) {
            log.debug("Creating trustStoreManager trust-manager");
            ops.add(trustManagerAddr, Values.of("key-store", "trustKeyStore"))
                    .assertSuccess();
        }

        // Server key-store
        final Address serverKeyStoreAddr = Address.subsystem("elytron").and("key-store", "serverKeyStore");
        if (!ops.exists(serverKeyStoreAddr)) {
            log.debug("Creating serverKeyStore key-store");
            ops.add(serverKeyStoreAddr, Values.of("path", SSL_DIR + "/server.keystore.jks")
                    .and("credential-reference", credentialRef)
                    .and("type", "JKS"))
                    .assertSuccess();
        }

        // Server key-manager
        final Address serverKeyManagerAddr = Address.subsystem("elytron").and("key-manager", "serverKeyManager");
        if (!ops.exists(serverKeyManagerAddr)) {
            log.debug("Creating serverKeyManager key-manager");
            ops.add(serverKeyManagerAddr, Values.of("key-store", "serverKeyStore")
                    .and("credential-reference", credentialRef))
                    .assertSuccess();
        }

        // Server SSL context with need-client-auth for mutual TLS
        final Address serverSslContextAddr = Address.subsystem("elytron").and("server-ssl-context", "serverSSLContext");
        if (!ops.exists(serverSslContextAddr)) {
            log.debug("Creating serverSSLContext server-ssl-context with need-client-auth");
            ops.add(serverSslContextAddr, Values.of("key-manager", "serverKeyManager")
                    .and("trust-manager", "trustStoreManager")
                    .and("need-client-auth", true))
                    .assertSuccess();
        }

        // Client key-store
        final Address clientKeyStoreAddr = Address.subsystem("elytron").and("key-store", "clientKeyStore");
        if (!ops.exists(clientKeyStoreAddr)) {
            log.debug("Creating clientKeyStore key-store");
            ops.add(clientKeyStoreAddr, Values.of("path", SSL_DIR + "/client.keystore.jks")
                    .and("credential-reference", credentialRef)
                    .and("type", "JKS"))
                    .assertSuccess();
        }

        // Client key-manager
        final Address clientKeyManagerAddr = Address.subsystem("elytron").and("key-manager", "clientKeyManager");
        if (!ops.exists(clientKeyManagerAddr)) {
            log.debug("Creating clientKeyManager key-manager");
            ops.add(clientKeyManagerAddr, Values.of("key-store", "clientKeyStore")
                    .and("credential-reference", credentialRef))
                    .assertSuccess();
        }

        // Client SSL context (for outbound MCMP connections)
        final Address clientSslContextAddr = Address.subsystem("elytron").and("client-ssl-context", "clientSSLContext");
        if (!ops.exists(clientSslContextAddr)) {
            log.debug("Creating clientSSLContext client-ssl-context");
            ops.add(clientSslContextAddr, Values.of("key-manager", "clientKeyManager")
                    .and("trust-manager", "trustStoreManager"))
                    .assertSuccess();
        }
    }

    /**
     * Links the server SSL context to the Undertow HTTPS listener.
     * Removes the legacy security-realm attribute first, as it conflicts with ssl-context.
     *
     * @param ops Creaper operations handle
     * @throws Exception if any management operation fails
     */
    private void linkToHttpsListener(final Operations ops) throws Exception {
        final Address httpsListenerAddr = Address.subsystem("undertow")
                .and("server", "default-server")
                .and("https-listener", "https");

        log.debug("Removing legacy security-realm from HTTPS listener");
        ops.undefineAttribute(httpsListenerAddr, "security-realm").assertSuccess();

        log.debug("Linking serverSSLContext to HTTPS listener");
        ops.writeAttribute(httpsListenerAddr, "ssl-context", "serverSSLContext").assertSuccess();
    }

    // ---- MCMP-over-SSL configuration for Undertow ----

    /**
     * Switches the mod_cluster filter's management channel from HTTP to HTTPS on Undertow.
     * Changes the {@code management-socket-binding} from {@code http} (port 8080)
     * to {@code https} (port 8443).
     *
     * @param ops Creaper operations handle
     * @throws Exception if any management operation fails
     */
    private void configureMcmpOverSslOnUndertowBalancer(final Operations ops) throws Exception {
        final Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

        // Switch management channel from http (HTTP/8080) to https (HTTPS/8443)
        ops.writeAttribute(filterAddr, "management-socket-binding", "https").assertSuccess();

        // Set client SSL context for outbound connections from balancer to workers
        ops.writeAttribute(filterAddr, "ssl-context", "clientSSLContext").assertSuccess();

        log.info("MCMP management channel switched to HTTPS socket binding with clientSSLContext");
    }

    // ---- CRL configuration for Elytron ----

    /**
     * Writes the certificate-revocation-list attribute on the trust-manager
     * to enable CRL checking.
     *
     * @param ops Creaper operations handle
     * @throws Exception if the management operation fails
     */
    private void writeCrlAttribute(final Operations ops) throws Exception {
        final Address trustManagerAddr = Address.subsystem("elytron").and("trust-manager", "trustStoreManager");

        final ModelNode crlValue = new ModelNode();
        crlValue.get("path").set(SSL_DIR + "/intermediate.crl.pem");

        log.debug("Setting certificate-revocation-list on trustStoreManager");
        ops.writeAttribute(trustManagerAddr, "certificate-revocation-list", crlValue).assertSuccess();
    }
}
