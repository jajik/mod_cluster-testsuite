package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Enumeration;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts a WildFly or JBCS httpd distribution ZIP to a per-instance directory
 * for native (non-Docker) test mode.
 *
 * <p>Each WildFly worker needs its own extracted copy because workers modify
 * configuration files (e.g. JGroups TCP, mod_cluster proxy settings), maintain
 * independent data directories, and write separate server logs. Sharing a single
 * extracted directory would cause conflicts.
 *
 * <p>The extraction target is {@code target/native-servers/{instanceName}/}.
 * If the target already exists, it is reused without re-extracting. This speeds
 * up repeated test runs during development.
 *
 * <p>After extraction, on POSIX systems, shell scripts in {@code bin/} are made
 * executable. A management user ({@code admin/admin}) is created via
 * {@code add-user.sh} to allow Creaper management connections.
 *
 * <p>Usage:
 * <pre>{@code
 *   Path serverHome = NativeServerExtractor.extract("worker1");
 *   // serverHome = target/native-servers/worker1/wildfly-39.0.1.Final
 * }</pre>
 *
 * @see TestMode
 * @see NativePortAllocator
 */
public final class NativeServerExtractor {

    private static final Logger log = LoggerFactory.getLogger(NativeServerExtractor.class);

    /** Root directory for all native server extractions. */
    private static final Path NATIVE_SERVERS_DIR = Path.of("target", "native-servers");

    /** Default management user credentials, matching those in Docker mode. */
    private static final String MGMT_USER = "admin";
    private static final String MGMT_PASSWORD = "admin";

    private NativeServerExtractor() {
    }

    /**
     * Extract the WildFly distribution ZIP to a per-instance directory and prepare it
     * for use as a test server.
     *
     * <p>If the target directory already contains an extracted server (detected by
     * the presence of a {@code bin/} subdirectory), extraction is skipped and the
     * existing path is returned.
     *
     * <p>Post-extraction setup:
     * <ol>
     *   <li>Makes all {@code bin/*.sh} scripts executable (POSIX systems only)</li>
     *   <li>Creates a management user ({@code admin/admin}) for Creaper connections</li>
     * </ol>
     *
     * @param instanceName unique name for this server instance (e.g. "worker1", "balancer")
     * @return the server home directory path (e.g. {@code target/native-servers/worker1/wildfly-39.0.1.Final})
     * @throws RuntimeException if extraction fails or no ZIP is found
     */
    public static Path extract(String instanceName) {
        Path zipPath = ContainerUtils.getWildFlyZipPath();
        if (zipPath == null || !zipPath.toFile().exists()) {
            throw new RuntimeException("No WildFly ZIP found. Set -Dwildfly.zip.path or "
                    + "place a wildfly-*.zip / jboss-eap-*.zip in distributions/");
        }

        return extractZip(zipPath, instanceName);
    }

    /**
     * Extract a distribution ZIP to a per-instance directory.
     *
     * @param zipPath      path to the distribution ZIP file
     * @param instanceName unique name for the instance directory
     * @return the server home directory (root directory inside the ZIP)
     * @throws RuntimeException if extraction fails
     */
    public static Path extractZip(Path zipPath, String instanceName) {
        Path instanceDir = NATIVE_SERVERS_DIR.resolve(instanceName);
        String rootDir = ImageBuilder.detectZipRootDir(zipPath.toFile());

        if (rootDir == null) {
            throw new RuntimeException("Could not detect root directory in ZIP: " + zipPath);
        }

        Path serverHome = instanceDir.resolve(rootDir);

        if (Files.isDirectory(serverHome.resolve("bin"))) {
            log.info("Reusing existing extraction for '{}': {}", instanceName, serverHome);
            try {
                backupOriginalConfig(serverHome);
            } catch (IOException e) {
                log.warn("Failed to ensure config backups for '{}': {}", instanceName, e.getMessage());
            }
            deployCustomLoadMetricModule(serverHome);
            return serverHome;
        }

        log.info("Extracting {} to {} for instance '{}'", zipPath.getFileName(), instanceDir, instanceName);

        try {
            Files.createDirectories(instanceDir);
            unzip(zipPath, instanceDir);
            makeScriptsExecutable(serverHome.resolve("bin"));
            addManagementUser(serverHome);
            backupOriginalConfig(serverHome);
            deployCustomLoadMetricModule(serverHome);
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract WildFly ZIP for '" + instanceName + "'", e);
        }

        log.info("Extraction complete for '{}': {}", instanceName, serverHome);
        return serverHome;
    }

    /**
     * Extract all entries from a ZIP file into the target directory.
     *
     * <p>Preserves the directory structure from the ZIP. Creates parent
     * directories as needed.
     *
     * @param zipPath   path to the ZIP file
     * @param targetDir directory to extract into
     * @throws IOException if extraction fails
     */
    private static void unzip(Path zipPath, Path targetDir) throws IOException {
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path entryPath = targetDir.resolve(entry.getName()).normalize();

                // Zip-slip protection
                if (!entryPath.startsWith(targetDir)) {
                    throw new IOException("ZIP entry outside target directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (InputStream is = zf.getInputStream(entry)) {
                        Files.copy(is, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /**
     * Make shell scripts in the given directory executable (POSIX systems only).
     *
     * <p>On Windows, {@code .bat} scripts don't need execute permission, so this
     * method is a no-op if POSIX file permissions are not supported.
     *
     * @param binDir the {@code bin/} directory containing shell scripts
     */
    private static void makeScriptsExecutable(Path binDir) {
        if (!Files.isDirectory(binDir)) {
            return;
        }

        try {
            Set<PosixFilePermission> execPerms = PosixFilePermissions.fromString("rwxr-xr-x");
            Files.list(binDir)
                    .filter(p -> p.toString().endsWith(".sh"))
                    .forEach(script -> {
                        try {
                            Files.setPosixFilePermissions(script, execPerms);
                        } catch (UnsupportedOperationException e) {
                            // Not a POSIX filesystem (Windows) — .bat scripts don't need +x
                        } catch (IOException e) {
                            log.warn("Failed to make {} executable: {}", script.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list scripts in {}: {}", binDir, e.getMessage());
        }
    }

    /**
     * Create a management user for Creaper connections.
     *
     * <p>Runs {@code add-user.sh} (or {@code add-user.bat} on Windows) to create
     * a management user with the default credentials ({@code admin/admin}).
     *
     * @param serverHome the WildFly server home directory
     * @throws Exception if the add-user command fails
     */
    private static void addManagementUser(Path serverHome) throws Exception {
        String script = TestMode.isWindows() ? "add-user.bat" : "add-user.sh";
        Path scriptPath = serverHome.resolve("bin").resolve(script);

        if (!Files.exists(scriptPath)) {
            log.warn("add-user script not found: {}", scriptPath);
            return;
        }

        CommandResult result = NativeProcessManager.execCommand(
                serverHome.toAbsolutePath(),
                scriptPath.toAbsolutePath().toString(), "-u", MGMT_USER, "-p", MGMT_PASSWORD);

        if (!result.isSuccess()) {
            if (result.getStderr().contains("already exists")) {
                log.debug("Management user '{}' already exists in {}", MGMT_USER, serverHome);
            } else {
                log.warn("add-user failed for '{}' (exit {}): {}",
                        serverHome, result.getExitCode(), result.getStderr());
            }
        } else {
            log.info("Created management user '{}' in {}", MGMT_USER, serverHome);
        }
    }

    /**
     * Save backups of original configuration files so they can be restored
     * before each test run, ensuring clean server state.
     *
     * <p>Both files are backed up because different server roles use different configs:
     * the balancer uses {@code standalone.xml} (default) while workers use
     * {@code standalone-ha.xml}.
     */
    private static void backupOriginalConfig(Path serverHome) throws IOException {
        for (String configFile : new String[]{"standalone.xml", "standalone-ha.xml"}) {
            Path config = serverHome.resolve("standalone/configuration/" + configFile);
            Path backup = serverHome.resolve("standalone/configuration/" + configFile + ".original");
            if (Files.exists(config) && !Files.exists(backup)) {
                Files.copy(config, backup);
                log.info("Backed up original {} in {}", configFile, serverHome);
            }
        }
    }

    /**
     * Deploy the custom load metric module into the WildFly modules directory.
     *
     * <p>In Docker mode, this module is baked into the image via Containerfile.
     * In native mode, we copy the JAR and module.xml from the test resources
     * into the server's module path.
     *
     * @param serverHome the WildFly server home directory
     */
    private static void deployCustomLoadMetricModule(Path serverHome) {
        Path jarSource = Path.of("src/test/resources/custom-load-metric/target/custom-load-metric.jar");
        Path moduleXmlSource = Path.of("src/test/resources/custom-load-metric/module.xml");

        if (!Files.exists(jarSource)) {
            log.debug("Custom load metric JAR not found at {}, skipping module deployment", jarSource);
            return;
        }

        Path moduleDir = serverHome.resolve("modules/org/jboss/modcluster/test/metric/main");
        try {
            Files.createDirectories(moduleDir);
            Files.copy(jarSource, moduleDir.resolve("custom-load-metric.jar"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(moduleXmlSource, moduleDir.resolve("module.xml"), StandardCopyOption.REPLACE_EXISTING);
            log.info("Deployed custom load metric module to {}", moduleDir);
        } catch (IOException e) {
            log.warn("Failed to deploy custom load metric module: {}", e.getMessage());
        }
    }

}
