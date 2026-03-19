package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        String baseImage = resolveBaseImage();
        String containerJavaHome = getContainerJavaHome();
        String imageTag = generateImageTag(zipFileName, baseImage, containerJavaHome != null);
        if (!imageExists(imageTag)) {
            log.info("Building image from ZIP: {} (this may take a few minutes on first run)", zipFileName);
            buildImageFromZip(zipPath, baseImage, imageTag, containerJavaHome);
        } else {
            log.info("Using existing image: {}", imageTag);
        }
        return imageTag;
    }

    private static String resolveBaseImage() {
        return System.getProperty("container.base.image");
    }

    /**
     * Build a Docker image from a WildFly/EAP ZIP using docker build directly.
     * This avoids Testcontainers' file transfer limitations.
     *
     * @param zipPath Path to the ZIP file
     * @param baseImage Base image reference (e.g., "registry.access.redhat.com/ubi9/openjdk-17:latest")
     * @param imageTag Tag for the resulting image
     * @return The image name/tag
     */
    public static String buildImageFromZip(Path zipPath, String baseImage, String imageTag) {
        return buildImageFromZip(zipPath, baseImage, imageTag, getContainerJavaHome());
    }

    static String buildImageFromZip(Path zipPath, String baseImage, String imageTag, String containerJavaHome) {
        File buildDir = zipPath.toFile().getParentFile(); // distributions/
        try {
            File zipFile = zipPath.toFile();
            String zipFileName = zipFile.getName();

            log.info("Building Docker image from ZIP: {} with base image {}", zipFileName, baseImage);

            File customMetricJar = new File("src/test/resources/custom-load-metric/target/custom-load-metric.jar");
            File customMetricModuleXml = new File("src/test/resources/custom-load-metric/module.xml");
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

            // Host JDK injection: copy JDK tree or create empty placeholder directory
            boolean injectJavaHome = false;
            if (containerJavaHome != null) {
                copyJavaHome(buildDir, containerJavaHome);
                injectJavaHome = true;
            } else {
                new File(buildDir, "java-home").mkdirs();
            }

            // Copy Containerfile template as Dockerfile into build context
            copyContainerfileTemplate("/containerfiles/Containerfile.wildfly-zip", buildDir.toPath());

            dockerBuild(buildDir, imageTag, 10, null,
                "BASE_IMAGE=" + baseImage,
                "ZIP_FILENAME=" + zipFileName,
                "INCLUDE_CUSTOM_METRIC=" + hasCustomMetric,
                "INJECT_JAVA_HOME=" + injectJavaHome);

            log.info("Successfully built image: {}", imageTag);
            return imageTag;

        } catch (Exception e) {
            throw new RuntimeException("Failed to build Docker image from ZIP", e);
        } finally {
            // Clean up generated Dockerfile and java-home copy
            new File(buildDir, "Dockerfile").delete();
            deleteRecursive(new File(buildDir, "java-home"));
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
     * Generate a consistent image tag from ZIP filename and base image.
     * Extracts a tag-safe suffix from the base image reference.
     */
    public static String generateImageTag(String zipFileName, String baseImage) {
        return generateImageTag(zipFileName, baseImage, false);
    }

    public static String generateImageTag(String zipFileName, String baseImage, boolean hasHostJdk) {
        String base = zipFileName.replace(".zip", "");
        String normalized = base.toLowerCase().replace(".", "-");
        // Extract tag-safe suffix from base image
        // e.g. "registry.access.redhat.com/ubi9/openjdk-17:latest" → "ubi9-openjdk-17"
        String imageSuffix = baseImage
            .replaceAll(".*/(ubi\\d+/)", "$1")  // keep from ubiN/ onwards
            .replaceAll(":.*", "")               // strip tag
            .replace("/", "-");                  // ubi9/openjdk-17 → ubi9-openjdk-17
        if (hasHostJdk) {
            imageSuffix += "-hostjdk";
        }
        return "modcluster-test/" + normalized + ":" + imageSuffix;
    }

    static String getContainerJavaHome() {
        String javaHome = System.getProperty("container.java.home");
        if (javaHome == null || javaHome.isEmpty()) {
            return null;
        }
        File javaDir = new File(javaHome);
        if (!javaDir.isDirectory()) {
            throw new RuntimeException("container.java.home directory does not exist: " + javaHome);
        }
        if (!new File(javaDir, "bin/java").exists()) {
            throw new RuntimeException("container.java.home does not contain bin/java: " + javaHome);
        }
        return javaHome;
    }

    static void copyJavaHome(File buildDir, String javaHomePath) {
        try {
            long start = System.currentTimeMillis();
            Path source = Paths.get(javaHomePath).toRealPath();
            Path dest = buildDir.toPath().resolve("java-home");

            log.info("Copying host JDK from {} into build context", source);

            Files.walk(source, FileVisitOption.FOLLOW_LINKS).forEach(srcPath -> {
                try {
                    Path relative = source.relativize(srcPath);
                    Path target = dest.resolve(relative);
                    if (Files.isDirectory(srcPath)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(srcPath, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to copy JDK file: " + srcPath, e);
                }
            });

            long elapsed = System.currentTimeMillis() - start;
            log.info("Host JDK copied in {}ms", elapsed);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy host JDK from: " + javaHomePath, e);
        }
    }

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
