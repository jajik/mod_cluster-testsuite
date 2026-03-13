package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a Docker image containing httpd with mod_proxy_cluster modules compiled from source.
 *
 * <p>Two paths:
 * <ul>
 *   <li><b>Default</b> — builds httpd from source and compiles the four mod_proxy_cluster modules
 *       (advertise, mod_proxy_cluster, balancers, mod_manager). Mirrors the upstream
 *       {@code test/httpd/Containerfile} approach.</li>
 *   <li><b>Custom httpd ZIP</b> ({@code -Dhttpd.zip.path=} or {@code HTTPD_ZIP_PATH} env var) —
 *       extracts the ZIP, locates {@code apxs}, and compiles only the modules against it.</li>
 * </ul>
 *
 * <p>Built images are cached — a rebuild only happens if the image doesn't exist locally.
 */
public class HttpdImageBuilder {

    private static final Logger log = LoggerFactory.getLogger(HttpdImageBuilder.class);

    private static final String DEFAULT_IMAGE_TAG = "modcluster-test/httpd-mod-proxy-cluster:latest";
    private static final String HTTPD_VERSION = "2.4.66";
    private static final String MOD_PROXY_CLUSTER_REPO = "https://github.com/modcluster/mod_proxy_cluster.git";

    /**
     * Build (or reuse) an httpd image with mod_proxy_cluster modules.
     * Checks {@code -Dhttpd.zip.path} / {@code HTTPD_ZIP_PATH} to decide between Path A and Path B.
     *
     * @return the Docker image tag
     */
    public static String buildImage() {
        String zipPath = System.getProperty("httpd.zip.path");
        if (zipPath == null || zipPath.isEmpty()) {
            zipPath = System.getenv("HTTPD_ZIP_PATH");
        }

        if (zipPath != null && !zipPath.isEmpty()) {
            return buildFromZip(new File(zipPath));
        } else {
            return buildDefault();
        }
    }

    /**
     * Path A — build httpd from source and compile mod_proxy_cluster modules.
     */
    private static String buildDefault() {
        if (ImageBuilder.imageExists(DEFAULT_IMAGE_TAG)) {
            log.info("httpd image already exists: {}", DEFAULT_IMAGE_TAG);
            return DEFAULT_IMAGE_TAG;
        }

        log.info("Building httpd image from source (tag: {})", DEFAULT_IMAGE_TAG);

        try {
            Path buildDir = Files.createTempDirectory("httpd-build-");
            File buildDirFile = buildDir.toFile();

            // Clone mod_proxy_cluster native sources into build context
            log.info("Cloning mod_proxy_cluster repository...");
            exec(buildDirFile, "git", "clone", "--depth", "1", MOD_PROXY_CLUSTER_REPO, "mod_proxy_cluster");

            // Generate Dockerfile
            File dockerfile = new File(buildDirFile, "Dockerfile");
            try (FileWriter w = new FileWriter(dockerfile)) {
                w.write(
                    "FROM fedora:42 AS builder\n" +
                    "RUN dnf install -y gcc apr-devel apr-util-devel openssl-devel pcre-devel \\\n" +
                    "    redhat-rpm-config autoconf wcstools make\n" +
                    "ADD https://dlcdn.apache.org/httpd/httpd-" + HTTPD_VERSION + ".tar.gz .\n" +
                    "RUN mkdir /httpd && tar xf httpd-" + HTTPD_VERSION + ".tar.gz --strip 1 -C /httpd\n" +
                    "WORKDIR /httpd\n" +
                    "RUN ./configure --prefix=/usr/local/apache2 --enable-proxy --enable-proxy-http \\\n" +
                    "    --enable-proxy-ajp --enable-proxy-wstunnel --enable-proxy-hcheck\n" +
                    "RUN make && make install\n" +
                    "RUN sed -i 's/\\(Listen 80\\)/#\\1/' /usr/local/apache2/conf/httpd.conf\n" +
                    "COPY mod_proxy_cluster/native /native\n" +
                    "WORKDIR /native\n" +
                    "RUN for m in advertise mod_proxy_cluster balancers mod_manager; do \\\n" +
                    "      cd $m; ./buildconf; \\\n" +
                    "      ./configure --with-apxs=/usr/local/apache2/bin/apxs; \\\n" +
                    "      make clean; make || exit 1; \\\n" +
                    "      cp *.so /usr/local/apache2/modules; cd ..; \\\n" +
                    "    done\n" +
                    "\n" +
                    "FROM fedora:42\n" +
                    "RUN dnf install -y pcre apr-util && dnf clean all\n" +
                    "COPY --from=builder /usr/local/apache2 /usr/local/apache2\n" +
                    "EXPOSE 8080 8443 6666\n"
                );
            }

            dockerBuild(buildDirFile, DEFAULT_IMAGE_TAG, 20);

            // Clean up build directory
            deleteRecursive(buildDirFile);

            log.info("Successfully built httpd image: {}", DEFAULT_IMAGE_TAG);
            return DEFAULT_IMAGE_TAG;

        } catch (Exception e) {
            throw new RuntimeException("Failed to build default httpd image", e);
        }
    }

    /**
     * Path B — use a pre-built httpd ZIP (e.g. JBCS) that already contains httpd and all
     * required modules (including mod_proxy_cluster). No compilation needed.
     *
     * <p>The ZIP is expected to contain a directory tree with {@code sbin/httpd} and
     * {@code modules/}. The builder auto-detects the httpd root, symlinks it to
     * {@code /usr/local/apache2} (the path expected by {@link BalancerContainer}),
     * and ensures {@code bin/httpd} and {@code conf/httpd.conf} exist.
     */
    private static String buildFromZip(File zipFile) {
        if (!zipFile.exists()) {
            throw new RuntimeException("httpd ZIP not found: " + zipFile.getAbsolutePath());
        }

        String zipFileName = zipFile.getName();
        String imageTag = "modcluster-test/" + zipFileName.replace(".zip", "").toLowerCase() + ":latest";

        if (ImageBuilder.imageExists(imageTag)) {
            log.info("httpd image already exists: {}", imageTag);
            return imageTag;
        }

        // Detect RHEL version from ZIP filename (e.g. "RHEL8", "RHEL9") to pick matching base image
        String baseImage = "fedora:42";
        String pcrePackage = "pcre";
        String extraDeps = "yajl";
        Matcher rhelMatcher = Pattern.compile("RHEL(\\d+)").matcher(zipFileName);
        if (rhelMatcher.find()) {
            int rhelVersion = Integer.parseInt(rhelMatcher.group(1));
            baseImage = "registry.access.redhat.com/ubi" + rhelVersion + "/ubi:latest";
            if (rhelVersion >= 10) {
                pcrePackage = "pcre2";
                extraDeps = "openldap-libs";
            }
        }

        log.info("Building httpd image from ZIP: {} (base: {}, tag: {})", zipFileName, baseImage, imageTag);

        try {
            Path buildDir = Files.createTempDirectory("httpd-zip-build-");
            File buildDirFile = buildDir.toFile();

            // Copy ZIP into build context
            Files.copy(zipFile.toPath(), buildDir.resolve(zipFileName));

            // Generate Dockerfile
            File dockerfile = new File(buildDirFile, "Dockerfile");
            try (FileWriter w = new FileWriter(dockerfile)) {
                w.write(
                    "FROM " + baseImage + "\n" +
                    "RUN dnf install -y " + pcrePackage + " apr-util openssl unzip findutils hostname jansson mailcap brotli" + (extraDeps.isEmpty() ? "" : " " + extraDeps) + " && dnf clean all\n" +
                    "COPY " + zipFileName + " /opt/" + zipFileName + "\n" +
                    "RUN set -e && \\\n" +
                    "    unzip -q /opt/" + zipFileName + " -d /opt && rm /opt/" + zipFileName + " && \\\n" +
                    "    # Auto-detect httpd root (directory containing sbin/httpd)\n" +
                    "    HTTPD_BIN=$(find /opt -name httpd -path '*/sbin/httpd' -type f 2>/dev/null | head -1) && \\\n" +
                    "    if [ -z \"$HTTPD_BIN\" ]; then echo 'ERROR: sbin/httpd not found in extracted ZIP' >&2; exit 1; fi && \\\n" +
                    "    HTTPD_ROOT=$(dirname \"$(dirname \"$HTTPD_BIN\")\") && \\\n" +
                    "    echo \"Detected httpd root: $HTTPD_ROOT\" && \\\n" +
                    "    # Run .postinstall (generates conf/httpd.conf from templates, creates dirs/symlinks)\n" +
                    "    cd \"$HTTPD_ROOT\" && bash .postinstall && \\\n" +
                    "    # Register bundled libs so httpd finds them at runtime\n" +
                    "    echo \"$HTTPD_ROOT/lib\" > /etc/ld.so.conf.d/jbcs-httpd.conf && ldconfig && \\\n" +
                    "    # Symlink compiled-in HTTPD_ROOT to actual extracted location\n" +
                    "    COMPILED_ROOT=$(\"$HTTPD_ROOT/sbin/httpd\" -V 2>/dev/null | grep -oP 'HTTPD_ROOT=\"\\K[^\"]+') && \\\n" +
                    "    echo \"Compiled-in HTTPD_ROOT: $COMPILED_ROOT\" && \\\n" +
                    "    if [ -n \"$COMPILED_ROOT\" ] && [ \"$COMPILED_ROOT\" != \"$HTTPD_ROOT\" ]; then \\\n" +
                    "        mkdir -p \"$(dirname \"$COMPILED_ROOT\")\" && \\\n" +
                    "        ln -sfn \"$HTTPD_ROOT\" \"$COMPILED_ROOT\"; \\\n" +
                    "    fi && \\\n" +
                    "    # Symlink to /usr/local/apache2 (expected by BalancerContainer)\n" +
                    "    ln -sfn \"$HTTPD_ROOT\" /usr/local/apache2 && \\\n" +
                    "    mkdir -p /usr/local/apache2/bin /usr/local/apache2/conf/extra && \\\n" +
                    "    for f in /usr/local/apache2/sbin/*; do \\\n" +
                    "        ln -sf ../sbin/$(basename $f) /usr/local/apache2/bin/$(basename $f); \\\n" +
                    "    done && \\\n" +
                    "    # Disable proxy_balancer (conflicts with mod_proxy_cluster)\n" +
                    "    find /usr/local/apache2 -name '*.conf' -exec \\\n" +
                    "        sed -i 's/^\\(LoadModule proxy_balancer_module\\)/#\\1/' {} \\; 2>/dev/null; \\\n" +
                    "    # Remove shipped mod_proxy_cluster config — the test provides its own\n" +
                    "    rm -f /usr/local/apache2/conf.d/mod_proxy_cluster.conf && \\\n" +
                    "    echo '--- httpd version ---' && /usr/local/apache2/bin/httpd -v\n" +
                    "EXPOSE 8080 8443 6666\n"
                );
            }

            dockerBuild(buildDirFile, imageTag, 20);

            // Clean up build directory
            deleteRecursive(buildDirFile);

            log.info("Successfully built httpd image from ZIP: {}", imageTag);
            return imageTag;

        } catch (Exception e) {
            throw new RuntimeException("Failed to build httpd image from ZIP: " + zipFile.getAbsolutePath(), e);
        }
    }

    /**
     * Run {@code docker build} in the given directory.
     */
    private static void dockerBuild(File buildDir, String imageTag, int timeoutMinutes) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("docker", "build", "-t", imageTag, ".");
        pb.directory(buildDir);
        pb.redirectErrorStream(true);

        log.info("Running: docker build -t {} in {}", imageTag, buildDir.getAbsolutePath());

        Process process = pb.start();

        // Stream build output to log
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[docker-build] {}", line);
            }
        }

        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Docker build timed out after " + timeoutMinutes + " minutes");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("Docker build failed with exit code: " + exitCode);
        }
    }

    /**
     * Execute a command in the given directory, waiting for completion and checking the exit code.
     */
    private static void exec(File workDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);
        pb.inheritIO();

        Process process = pb.start();
        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Command timed out: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException("Command failed (exit " + process.exitValue() + "): " + String.join(" ", command));
        }
    }

    /**
     * Recursively delete a directory tree.
     */
    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
