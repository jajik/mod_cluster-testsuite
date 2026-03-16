package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
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

        String httpdVersion = System.getProperty("httpd.version");
        String repoUrl = System.getProperty("mod.proxy.cluster.repo.url");

        try {
            Path buildDir = Files.createTempDirectory("httpd-build-");
            File buildDirFile = buildDir.toFile();

            // Clone mod_proxy_cluster native sources into build context
            log.info("Cloning mod_proxy_cluster repository...");
            exec(buildDirFile, "git", "clone", "--depth", "1", repoUrl, "mod_proxy_cluster");

            // Copy Containerfile template as Dockerfile into build context
            ImageBuilder.copyContainerfileTemplate("/containerfiles/Containerfile.httpd-source", buildDir);

            ImageBuilder.dockerBuild(buildDirFile, DEFAULT_IMAGE_TAG, 20, null,
                "HTTPD_VERSION=" + httpdVersion);

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
                extraDeps = "openldap";
            }
        }

        log.info("Building httpd image from ZIP: {} (base: {}, tag: {})", zipFileName, baseImage, imageTag);

        try {
            Path buildDir = Files.createTempDirectory("httpd-zip-build-");
            File buildDirFile = buildDir.toFile();

            // Copy ZIP into build context
            Files.copy(zipFile.toPath(), buildDir.resolve(zipFileName));

            // Copy Containerfile template as Dockerfile into build context
            ImageBuilder.copyContainerfileTemplate("/containerfiles/Containerfile.httpd-zip", buildDir);

            ImageBuilder.dockerBuild(buildDirFile, imageTag, 20, null,
                "BASE_IMAGE=" + baseImage,
                "PCRE_PACKAGE=" + pcrePackage,
                "EXTRA_DEPS=" + extraDeps,
                "ZIP_FILENAME=" + zipFileName);

            // Clean up build directory
            deleteRecursive(buildDirFile);

            log.info("Successfully built httpd image from ZIP: {}", imageTag);
            return imageTag;

        } catch (Exception e) {
            throw new RuntimeException("Failed to build httpd image from ZIP: " + zipFile.getAbsolutePath(), e);
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
