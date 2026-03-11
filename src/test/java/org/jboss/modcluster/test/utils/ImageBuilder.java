package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
            }

            // Create Dockerfile
            File dockerfile = new File(buildDir, "Dockerfile.tmp");
            try (FileWriter writer = new FileWriter(dockerfile)) {
                writer.write(String.format(
                    "FROM registry.access.redhat.com/ubi9/%s:latest\n" +
                    "USER root\n" +
                    "RUN microdnf install -y unzip findutils && microdnf clean all\n" +
                    "WORKDIR /opt\n" +
                    "COPY %s /opt/%s\n" +
                    "RUN echo 'Extracting %s...' && \\\n" +
                    "    unzip -q /opt/%s && \\\n" +
                    "    rm /opt/%s && \\\n" +
                    "    EXTRACTED=$(find /opt -maxdepth 1 -mindepth 1 -type d ! -name wildfly | head -1) && \\\n" +
                    "    echo \"Detected server directory: $EXTRACTED\" && \\\n" +
                    "    if [ -n \"$EXTRACTED\" ]; then mv \"$EXTRACTED\" /opt/wildfly; fi && \\\n" +
                    "    chmod +x /opt/wildfly/bin/*.sh && \\\n" +
                    "    echo 'Creating management user...' && \\\n" +
                    "    /opt/wildfly/bin/add-user.sh -u admin -p admin -r ManagementRealm && \\\n" +
                    "    echo 'User creation output:' && \\\n" +
                    "    cat /opt/wildfly/standalone/configuration/mgmt-users.properties && \\\n" +
                    "    chown -R 185:0 /opt/wildfly && \\\n" +
                    "    chmod -R g+rw /opt/wildfly\n",
                    javaVersion,
                    zipFileName, zipFileName,
                    zipFileName,
                    zipFileName,
                    zipFileName
                ));

                // Add custom load metric module if available
                if (hasCustomMetric) {
                    log.info("Including custom load metric module in image");
                    writer.write(
                        "# Add custom load metric module\n" +
                        "RUN mkdir -p /opt/wildfly/modules/org/jboss/modcluster/test/metric/main\n" +
                        "COPY custom-load-metric.jar /opt/wildfly/modules/org/jboss/modcluster/test/metric/main/\n" +
                        "COPY module.xml /opt/wildfly/modules/org/jboss/modcluster/test/metric/main/\n" +
                        "RUN chown -R 185:0 /opt/wildfly/modules/org/jboss/modcluster/test/metric && \\\n" +
                        "    chmod -R g+rw /opt/wildfly/modules/org/jboss/modcluster/test/metric\n"
                    );
                }

                writer.write(
                    "USER 185\n" +
                    "ENV JBOSS_HOME=/opt/wildfly\n" +
                    "ENV PATH=\"/opt/wildfly/bin:${PATH}\"\n" +
                    "EXPOSE 8080 8443 9990 6666\n"
                );
            }

            // Build image using docker build
            ProcessBuilder pb = new ProcessBuilder(
                "docker", "build",
                "-f", "Dockerfile.tmp",
                "-t", imageTag,
                "."
            );
            pb.directory(buildDir);
            pb.inheritIO(); // Show build output

            log.info("Running: docker build -t {} (this may take a few minutes)", imageTag);

            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);

            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Docker build timed out after 10 minutes");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("Docker build failed with exit code: " + exitCode);
            }

            // Clean up temp Dockerfile
            dockerfile.delete();

            log.info("Successfully built image: {}", imageTag);
            return imageTag;

        } catch (Exception e) {
            throw new RuntimeException("Failed to build Docker image from ZIP", e);
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
