package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pre-builds Docker images from WildFly/EAP ZIPs to avoid Testcontainers
 * large file transfer issues (SIGPIPE errors).
 */
public class ImageBuilder {

    private static final Logger log = LoggerFactory.getLogger(ImageBuilder.class);

    /**
     * Build or reuse a Docker image from a WildFly/EAP ZIP distribution.
     * Determines the required Java version, generates a consistent image tag,
     * and builds the image only if it doesn't already exist locally.
     *
     * @param zipPath Path to the ZIP file
     * @return The image tag (either newly built or existing)
     */
    public static String ensureImage(Path zipPath) {
        String zipFileName = zipPath.getFileName().toString();
        String javaVersion = ContainerUtils.getRequiredJavaVersion(zipFileName);
        String imageTag = generateImageTag(zipFileName, javaVersion);
        if (!imageExists(imageTag)) {
            log.info("Building image from ZIP: {} (this may take a few minutes on first run)", zipFileName);
            buildImageFromZip(zipPath, javaVersion, imageTag);
        } else {
            log.info("Using existing image: {}", imageTag);
        }
        return imageTag;
    }

    /**
     * Build a Docker image from a WildFly/EAP ZIP using docker build directly.
     * This avoids Testcontainers' file transfer limitations.
     *
     * @param zipPath Path to the ZIP file
     * @param javaVersion Java version (e.g., "openjdk-17")
     * @param imageTag Tag for the resulting image
     * @return The image name/tag
     */
    public static String buildImageFromZip(Path zipPath, String javaVersion, String imageTag) {
        try {
            File zipFile = zipPath.toFile();
            File buildDir = zipFile.getParentFile(); // distributions/
            String zipFileName = zipFile.getName();

            log.info("Building Docker image from ZIP: {} with {}", zipFileName, javaVersion);

            // Check if custom load metric JAR exists — prefer Maven build output,
            // fall back to pre-built copy in distributions/ (the build context directory)
            File customMetricJar = new File("src/test/resources/custom-load-metric/target/custom-load-metric.jar");
            File customMetricModuleXml = new File("src/test/resources/custom-load-metric/module.xml");
            if (!customMetricJar.exists()) {
                customMetricJar = new File(buildDir, "custom-load-metric.jar");
            }
            if (!customMetricModuleXml.exists()) {
                customMetricModuleXml = new File(buildDir, "module.xml");
            }
            boolean hasCustomMetric = customMetricJar.exists() && customMetricModuleXml.exists();

            // Copy custom metric files to build context if they exist (and aren't already there)
            if (hasCustomMetric) {
                log.info("Including custom load metric module from: {}", customMetricJar.getPath());
                File destJar = new File(buildDir, "custom-load-metric.jar");
                File destXml = new File(buildDir, "module.xml");
                if (!customMetricJar.equals(destJar)) {
                    Files.copy(
                        customMetricJar.toPath(), destJar.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    );
                }
                if (!customMetricModuleXml.equals(destXml)) {
                    Files.copy(
                        customMetricModuleXml.toPath(), destXml.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    );
                }
            } else {
                log.warn("Custom load metric module not found — testCustomLoadMetrics will fail. " +
                         "Build it with: cd src/test/resources/custom-load-metric && mvn package");
                // Create empty placeholders so COPY instructions in the template don't fail
                new File(buildDir, "custom-load-metric.jar").createNewFile();
                new File(buildDir, "module.xml").createNewFile();
            }

            // Copy Containerfile template as Dockerfile into build context
            copyContainerfileTemplate("/containerfiles/Containerfile.wildfly-zip", buildDir.toPath());

            dockerBuild(buildDir, imageTag, 10, null,
                "JAVA_VERSION=" + javaVersion,
                "ZIP_FILENAME=" + zipFileName,
                "INCLUDE_CUSTOM_METRIC=" + hasCustomMetric);

            // Clean up generated Dockerfile
            new File(buildDir, "Dockerfile").delete();

            log.info("Successfully built image: {}", imageTag);
            return imageTag;

        } catch (Exception e) {
            throw new RuntimeException("Failed to build Docker image from ZIP", e);
        }
    }

    /**
     * Copy a Containerfile template from the classpath into the build directory as {@code Dockerfile}.
     */
    static void copyContainerfileTemplate(String classpathResource, Path buildDir) throws IOException {
        try (InputStream in = ImageBuilder.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IOException("Containerfile template not found on classpath: " + classpathResource);
            }
            Files.copy(in, buildDir.resolve("Dockerfile"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Run {@code docker build} in the given directory with optional {@code --build-arg} pairs.
     *
     * @param buildDir       the build context directory
     * @param imageTag       tag for the resulting image
     * @param timeoutMinutes maximum build time
     * @param dockerfileName custom Dockerfile name ({@code -f}), or {@code null} for the default
     * @param buildArgs      zero or more {@code KEY=VALUE} build-arg strings
     */
    static void dockerBuild(File buildDir, String imageTag, int timeoutMinutes,
                            String dockerfileName, String... buildArgs) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("build");
        if (dockerfileName != null) {
            cmd.add("-f");
            cmd.add(dockerfileName);
        }
        for (String arg : buildArgs) {
            cmd.add("--build-arg");
            cmd.add(arg);
        }
        cmd.add("-t");
        cmd.add(imageTag);
        cmd.add(".");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(buildDir);
        pb.redirectErrorStream(true);

        log.info("Running: {} in {}", String.join(" ", cmd), buildDir.getAbsolutePath());

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
     * Check if an image exists locally.
     */
    public static boolean imageExists(String imageTag) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "image", "inspect", imageTag);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read output (need to consume it)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Consume output
                }
            }

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Generate a consistent image tag from ZIP filename and Java version.
     */
    public static String generateImageTag(String zipFileName, String javaVersion) {
        // Remove .zip extension
        String base = zipFileName.replace(".zip", "");
        // Replace dots with dashes for Docker tag compatibility
        String normalized = base.toLowerCase().replace(".", "-");
        return "modcluster-test/" + normalized + ":" + javaVersion;
    }
}
