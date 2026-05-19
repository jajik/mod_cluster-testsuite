package org.jboss.modcluster.test.ssl;

import org.jboss.dmr.ModelNode;
import org.jboss.modcluster.test.base.BalancerType;
import org.jboss.modcluster.test.utils.balancer.Balancer;
import org.jboss.modcluster.test.utils.CommandResult;
import org.jboss.modcluster.test.utils.ManagementClientFactory;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    private static final String SSL_SUBPATH = "standalone/configuration/ssl";
    private static final String KEYSTORES_RESOURCE_DIR = "ssl/ca/intermediate/keystores/";
    private static final String CERTS_RESOURCE_DIR = "ssl/ca/intermediate/certs/";
    private static final String KEYS_RESOURCE_DIR = "ssl/ca/intermediate/private/";
    private static final String CRL_RESOURCE_PATH = "ssl/ca/intermediate/crl/intermediate.crl.pem";
    private static final int MANAGEMENT_PORT = 9990;

    static final String SSL_DATA_CONF = "ssl-data.conf";
    static final String SSL_CRL_CONF = "ssl-crl.conf";

    /** All SSL config files written to httpd's conf/extra/ directory. */
    public static final List<String> HTTPD_SSL_CONF_FILES = List.of(SSL_DATA_CONF, SSL_CRL_CONF);

    private static String httpdSslDir(Balancer balancer) {
        return balancer.getServerHome() + "/ssl";
    }

    private static String httpdConfExtra(Balancer balancer) {
        return balancer.getConfDir() + "/extra";
    }

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
    public void configureWorker(final WildFlyWorker worker) throws Exception {
        log.info("Configuring SSL on worker '{}'", worker.getName());

        final String sslDir = sslDir(worker.getServerHome());
        copyKeystoresToWorker(worker, sslDir);

        final Operations ops = worker.getOperations();
        createElytronResources(ops, sslDir);
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
    public void configureMtlsWorker(final WildFlyWorker worker, final String serverKeystore,
                                     final String clientKeystore) throws Exception {
        log.info("Configuring mTLS + MCMP-over-SSL on worker '{}' (server={}, client={})",
                worker.getName(), serverKeystore, clientKeystore);

        final String sslDir = sslDir(worker.getServerHome());
        copyMtlsKeystoresToWorker(worker, serverKeystore, clientKeystore, sslDir);

        final Operations ops = worker.getOperations();
        createMtlsElytronResources(ops, sslDir);
        linkToHttpsListener(ops);

        // Write MCMP-over-SSL settings to management model BEFORE reload —
        // socket binding port changes only take effect after a reload.
        final Address mcProxy = Address.subsystem("modcluster").and("proxy", "default");
        ops.writeAttribute(mcProxy, "ssl-context", "clientSSLContext").assertSuccess();

        // Use balancer-type-specific MCMP SSL port (8443 for Undertow, 8090 for httpd)
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
    public void addCrlToWorker(final WildFlyWorker worker) throws Exception {
        log.info("Adding CRL to worker '{}'", worker.getName());

        final String sslDir = sslDir(worker.getServerHome());
        worker.copyClasspathResource(CRL_RESOURCE_PATH, sslDir + "/intermediate.crl.pem");

        final Operations ops = worker.getOperations();
        writeCrlAttribute(ops, sslDir);

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
    public void configureBalancer(final Balancer balancer) throws Exception {
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
    public void configureMtlsBalancer(final Balancer balancer, final String serverKeystore,
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
    public void addCrlToBalancer(final Balancer balancer) throws Exception {
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
    private void configureUndertowBalancerSsl(final Balancer balancer) throws Exception {
        log.info("Configuring SSL on Undertow balancer");

        final String sslDir = sslDir(balancer.getServerHome());
        copyKeystoresToBalancer(balancer, sslDir);

        try (OnlineManagementClient client = ManagementClientFactory.create(
                balancer.getManagementHost(), balancer.getManagementPort())) {
            final Operations ops = new Operations(client);
            createElytronResources(ops, sslDir);
            linkToHttpsListener(ops);

            new Administration(client).reload();
        }

        log.info("SSL configured successfully on Undertow balancer");
    }

    /**
     * Configures mTLS + MCMP-over-SSL on an Undertow balancer.
     */
    private void configureUndertowMtlsBalancer(final Balancer balancer, final String serverKeystore,
                                                final String clientKeystore) throws Exception {
        log.info("Configuring mTLS + MCMP-over-SSL on Undertow balancer (server={}, client={})",
                serverKeystore, clientKeystore);

        final String sslDir = sslDir(balancer.getServerHome());
        copyMtlsKeystoresToBalancer(balancer, serverKeystore, clientKeystore, sslDir);

        try (OnlineManagementClient client = ManagementClientFactory.create(
                balancer.getManagementHost(), balancer.getManagementPort())) {
            final Operations ops = new Operations(client);
            createMtlsElytronResources(ops, sslDir);
            linkToHttpsListener(ops);
            configureMcmpOverSslOnUndertowBalancer(ops);

            new Administration(client).reload();
        }

        log.info("mTLS + MCMP-over-SSL configured successfully on Undertow balancer");
    }

    /**
     * Adds CRL to an Undertow balancer via Elytron trust-manager.
     */
    private void addCrlToUndertowBalancer(final Balancer balancer) throws Exception {
        log.info("Adding CRL to Undertow balancer");

        final String sslDir = sslDir(balancer.getServerHome());
        balancer.copyClasspathResource(CRL_RESOURCE_PATH, sslDir + "/intermediate.crl.pem");

        try (OnlineManagementClient client = ManagementClientFactory.create(
                balancer.getManagementHost(), balancer.getManagementPort())) {
            final Operations ops = new Operations(client);
            writeCrlAttribute(ops, sslDir);

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
    private void configureHttpdBalancerSsl(final Balancer balancer) throws Exception {
        log.info("Configuring SSL on httpd balancer (data path)");

        String sslDir = httpdSslDir(balancer);
        String confExtra = httpdConfExtra(balancer);

        // Copy PEM certificates into balancer
        String certPrefix = "localhost.server";
        copyPemCertsToBalancer(balancer, certPrefix, sslDir);

        // Strip key passphrase (httpd needs unencrypted key)
        stripKeyPassphraseOnBalancer(balancer, certPrefix, sslDir);

        // Write SSL VirtualHost config for data path (port 8443)
        String sslConfig =
                "LoadModule ssl_module modules/mod_ssl.so\n" +
                "Listen 8443\n" +
                "<VirtualHost *:8443>\n" +
                "    SSLEngine on\n" +
                "    SSLCertificateFile " + sslDir + "/server.cert.pem\n" +
                "    SSLCertificateKeyFile " + sslDir + "/server.nopass.key.pem\n" +
                "    SSLCACertificateFile " + sslDir + "/ca-chain.cert.pem\n" +
                "</VirtualHost>\n";

        writeConfigToBalancer(balancer, sslConfig, confExtra + "/" + SSL_DATA_CONF);

        // Graceful restart to pick up SSL config
        balancer.reload();

        log.info("SSL configured successfully on httpd balancer");
    }

    /**
     * Configures mTLS on an httpd balancer (both MCMP port 8090 and data path 8443).
     * SSLVerifyClient require forces client certificate authentication.
     */
    private void configureHttpdMtlsBalancer(final Balancer balancer, final String serverKeystore,
                                             final String clientKeystore) throws Exception {
        log.info("Configuring mTLS on httpd balancer (server={}, client={})", serverKeystore, clientKeystore);

        String sslDir = httpdSslDir(balancer);
        String confExtra = httpdConfExtra(balancer);

        // Copy PEM certificates (use the server keystore prefix to find cert/key)
        copyPemCertsToBalancer(balancer, serverKeystore, sslDir);

        // Strip key passphrase
        stripKeyPassphraseOnBalancer(balancer, serverKeystore, sslDir);

        // Replace mod_proxy_cluster.conf with the mTLS variant.
        // The SSL variant is a complete replacement (same module loads, directives, etc.)
        // with the plain-HTTP VirtualHost on 8090 replaced by an SSL one.
        // See src/test/resources/httpd/mod_proxy_cluster_ssl.conf for the full config.
        String sslTemplate = new String(
                getClass().getClassLoader().getResourceAsStream("httpd/mod_proxy_cluster_ssl.conf")
                        .readAllBytes());
        String sslConfig = sslTemplate.replace("@@SSL_DIR@@", sslDir);
        writeConfigToBalancer(balancer, sslConfig, balancer.getModProxyClusterConfPath());

        // Switch the internal McmpClient to HTTPS so the reload health check works on the SSL port
        balancer.enableMcmpSsl();

        // Graceful restart to pick up mTLS config
        balancer.reload();

        log.info("mTLS configured successfully on httpd balancer");
    }

    /**
     * Adds CRL to an httpd balancer by appending SSLCARevocationFile directives
     * and performing a graceful restart.
     */
    private void addCrlToHttpdBalancer(final Balancer balancer) throws Exception {
        log.info("Adding CRL to httpd balancer");

        String sslDir = httpdSslDir(balancer);
        String confExtra = httpdConfExtra(balancer);

        // Copy CRL file into balancer
        balancer.copyClasspathResource(CRL_RESOURCE_PATH, sslDir + "/intermediate.crl.pem");

        // Write CRL config that applies to all SSL VirtualHosts.
        // Use 'leaf' mode because we only have the intermediate CA's CRL, not the root CA's.
        // 'chain' mode would reject ALL certs because the root CA CRL is missing.
        String crlConfig =
                "# CRL configuration (applied globally)\n" +
                "SSLCARevocationFile " + sslDir + "/intermediate.crl.pem\n" +
                "SSLCARevocationCheck leaf\n";

        writeConfigToBalancer(balancer, crlConfig, confExtra + "/" + SSL_CRL_CONF);

        // Graceful restart to force new TLS handshakes with CRL checking
        balancer.reload();

        log.info("CRL added successfully to httpd balancer");
    }

    // ---- httpd SSL helpers (platform-independent) ----

    /**
     * Copies PEM certificate, key, and CA chain files into the httpd balancer.
     */
    private void copyPemCertsToBalancer(final Balancer balancer, final String certPrefix,
                                         final String sslDir) throws Exception {
        String certResource = CERTS_RESOURCE_DIR + certPrefix + ".cert.pem";
        String keyResource = KEYS_RESOURCE_DIR + certPrefix + ".key.pem";
        String caChainResource = CERTS_RESOURCE_DIR + "ca-chain.cert.pem";

        // copyClasspathResource creates parent directories automatically
        log.debug("Copying server cert '{}' to httpd balancer", certResource);
        balancer.copyClasspathResource(certResource, sslDir + "/server.cert.pem");

        log.debug("Copying server key '{}' to httpd balancer", keyResource);
        balancer.copyClasspathResource(keyResource, sslDir + "/server.key.pem");

        log.debug("Copying CA chain to httpd balancer");
        balancer.copyClasspathResource(caChainResource, sslDir + "/ca-chain.cert.pem");
    }

    /**
     * Strips the passphrase from the server key using openssl.
     * httpd mod_ssl requires unencrypted keys (or SSLPassPhraseDialog which is harder to automate).
     *
     * <p>First attempts to run openssl inside the balancer. If it lacks openssl,
     * falls back to running openssl on the host and copying the unencrypted key.</p>
     */
    private void stripKeyPassphraseOnBalancer(final Balancer balancer, final String certPrefix,
                                                final String sslDir) throws Exception {
        // Try inside the balancer first (some images include openssl)
        CommandResult result = balancer.execCommand(
                "openssl", "rsa",
                "-in", sslDir + "/server.key.pem",
                "-out", sslDir + "/server.nopass.key.pem",
                "-passin", "pass:" + KEYSTORE_PASSWORD);

        if (result.isSuccess()) {
            log.debug("Key passphrase stripped in balancer");
            return;
        }

        log.info("Balancer lacks openssl, stripping passphrase on host");

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

        balancer.copyLocalFile(tempKey.toPath(), sslDir + "/server.nopass.key.pem");
        tempKey.delete();
        log.debug("Key passphrase stripped on host and copied to balancer");
    }

    /**
     * Writes a configuration string to a file inside the balancer.
     * Uses a temp file + copyLocalFile to work on both Docker and native (Windows) balancers.
     */
    private void writeConfigToBalancer(final Balancer balancer, final String content,
                                        final String destPath) throws Exception {
        File tempFile = File.createTempFile("ssl-config-", ".conf");
        tempFile.deleteOnExit();
        try {
            Files.writeString(tempFile.toPath(), content);
            balancer.copyLocalFile(tempFile.toPath(), destPath);
            log.debug("Config written to {}", destPath);
        } finally {
            tempFile.delete();
        }
    }

    // ---- Private helpers for keystore operations ----

    /**
     * Copies server keystore and CA chain trust store to a worker.
     *
     * @param worker the worker to copy keystores to
     * @param sslDir the SSL directory path on the worker's filesystem
     */
    private void copyKeystoresToWorker(final WildFlyWorker worker, final String sslDir) {
        final String nodeName = mapToNodeName(worker.getName());
        final String serverKeystoreResource = KEYSTORES_RESOURCE_DIR + nodeName + ".server.keystore.jks";
        final String trustStoreResource = KEYSTORES_RESOURCE_DIR + "ca-chain.keystore.jks";

        log.debug("Copying server keystore '{}' to worker '{}'", serverKeystoreResource, worker.getName());
        worker.copyClasspathResource(serverKeystoreResource, sslDir + "/server.keystore.jks");

        log.debug("Copying CA chain trust store to worker '{}'", worker.getName());
        worker.copyClasspathResource(trustStoreResource, sslDir + "/ca-chain.keystore.jks");
    }

    /**
     * Copies server, client, and CA chain trust keystores to a worker for mTLS.
     *
     * @param worker         the worker to copy keystores to
     * @param serverKeystore server keystore name prefix
     * @param clientKeystore client keystore name prefix
     * @param sslDir         the SSL directory path on the worker's filesystem
     */
    private void copyMtlsKeystoresToWorker(final WildFlyWorker worker, final String serverKeystore,
                                            final String clientKeystore, final String sslDir) {
        final String serverResource = KEYSTORES_RESOURCE_DIR + serverKeystore + ".keystore.jks";
        final String clientResource = KEYSTORES_RESOURCE_DIR + clientKeystore + ".keystore.jks";
        final String trustResource = KEYSTORES_RESOURCE_DIR + "ca-chain.keystore.jks";

        log.debug("Copying server keystore '{}'", serverResource);
        worker.copyClasspathResource(serverResource, sslDir + "/server.keystore.jks");

        log.debug("Copying client keystore '{}'", clientResource);
        worker.copyClasspathResource(clientResource, sslDir + "/client.keystore.jks");

        log.debug("Copying CA chain trust store");
        worker.copyClasspathResource(trustResource, sslDir + "/ca-chain.keystore.jks");
    }

    /**
     * Copies server keystore and CA chain trust store to a balancer (always uses localhost keystore).
     *
     * @param balancer the balancer to copy keystores to
     * @param sslDir   the SSL directory path on the balancer's filesystem
     */
    private void copyKeystoresToBalancer(final Balancer balancer, final String sslDir) {
        final String serverKeystoreResource = KEYSTORES_RESOURCE_DIR + "localhost.server.keystore.jks";
        final String trustStoreResource = KEYSTORES_RESOURCE_DIR + "ca-chain.keystore.jks";

        log.debug("Copying server keystore '{}' to balancer", serverKeystoreResource);
        balancer.copyClasspathResource(serverKeystoreResource, sslDir + "/server.keystore.jks");

        log.debug("Copying CA chain trust store to balancer");
        balancer.copyClasspathResource(trustStoreResource, sslDir + "/ca-chain.keystore.jks");
    }

    /**
     * Copies server, client, and CA chain trust keystores to a balancer for mTLS.
     *
     * @param balancer       the balancer to copy keystores to
     * @param serverKeystore server keystore name prefix
     * @param clientKeystore client keystore name prefix
     * @param sslDir         the SSL directory path on the balancer's filesystem
     */
    private void copyMtlsKeystoresToBalancer(final Balancer balancer, final String serverKeystore,
                                              final String clientKeystore, final String sslDir) {
        final String serverResource = KEYSTORES_RESOURCE_DIR + serverKeystore + ".keystore.jks";
        final String clientResource = KEYSTORES_RESOURCE_DIR + clientKeystore + ".keystore.jks";
        final String trustResource = KEYSTORES_RESOURCE_DIR + "ca-chain.keystore.jks";

        log.debug("Copying server keystore '{}'", serverResource);
        balancer.copyClasspathResource(serverResource, sslDir + "/server.keystore.jks");

        log.debug("Copying client keystore '{}'", clientResource);
        balancer.copyClasspathResource(clientResource, sslDir + "/client.keystore.jks");

        log.debug("Copying CA chain trust store");
        balancer.copyClasspathResource(trustResource, sslDir + "/ca-chain.keystore.jks");
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
     * @param ops    Creaper operations handle
     * @param sslDir the SSL directory path in the WildFly management model
     * @throws Exception if any management operation fails
     */
    private void createElytronResources(final Operations ops, final String sslDir) throws Exception {
        final ModelNode credentialRef = new ModelNode();
        credentialRef.get("clear-text").set(KEYSTORE_PASSWORD);

        // Trust key-store (CA chain)
        final Address trustKeyStoreAddr = Address.subsystem("elytron").and("key-store", "trustKeyStore");
        if (!ops.exists(trustKeyStoreAddr)) {
            log.debug("Creating trustKeyStore key-store");
            ops.add(trustKeyStoreAddr, Values.of("path", sslDir + "/ca-chain.keystore.jks")
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
            ops.add(serverKeyStoreAddr, Values.of("path", sslDir + "/server.keystore.jks")
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
     * @param ops    Creaper operations handle
     * @param sslDir the SSL directory path in the WildFly management model
     * @throws Exception if any management operation fails
     */
    private void createMtlsElytronResources(final Operations ops, final String sslDir) throws Exception {
        final ModelNode credentialRef = new ModelNode();
        credentialRef.get("clear-text").set(KEYSTORE_PASSWORD);

        // Trust key-store (CA chain)
        final Address trustKeyStoreAddr = Address.subsystem("elytron").and("key-store", "trustKeyStore");
        if (!ops.exists(trustKeyStoreAddr)) {
            log.debug("Creating trustKeyStore key-store");
            ops.add(trustKeyStoreAddr, Values.of("path", sslDir + "/ca-chain.keystore.jks")
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
            ops.add(serverKeyStoreAddr, Values.of("path", sslDir + "/server.keystore.jks")
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
            ops.add(clientKeyStoreAddr, Values.of("path", sslDir + "/client.keystore.jks")
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
     * @param ops    Creaper operations handle
     * @param sslDir the SSL directory path in the WildFly management model
     * @throws Exception if the management operation fails
     */
    private void writeCrlAttribute(final Operations ops, final String sslDir) throws Exception {
        final Address trustManagerAddr = Address.subsystem("elytron").and("trust-manager", "trustStoreManager");

        final ModelNode crlValue = new ModelNode();
        crlValue.get("path").set(sslDir + "/intermediate.crl.pem");

        log.debug("Setting certificate-revocation-list on trustStoreManager");
        ops.writeAttribute(trustManagerAddr, "certificate-revocation-list", crlValue).assertSuccess();
    }

    /**
     * Compute the SSL directory path from a server home directory.
     *
     * @param serverHome the server home directory (e.g. {@code "/opt/wildfly"})
     * @return the SSL directory path (e.g. {@code "/opt/wildfly/standalone/configuration/ssl"})
     */
    private static String sslDir(final String serverHome) {
        return Path.of(serverHome, SSL_SUBPATH).toString();
    }
}
