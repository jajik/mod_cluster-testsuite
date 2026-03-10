package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.OutputFrame;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Utility for managing container log output.
 * Writes logs to file and optionally to stdout based on system properties.
 */
public class ContainerLogger {

    private static final Logger log = LoggerFactory.getLogger(ContainerLogger.class);

    private static final boolean STDOUT_LOGGING_ENABLED =
            Boolean.parseBoolean(System.getProperty("container.logs.stdout", "false"));
    private static final Path LOG_DIR = Paths.get(System.getProperty("container.logs.dir", "target/container-logs"));

    private final String containerName;
    private PrintWriter logWriter;

    public ContainerLogger(String containerName) {
        this.containerName = containerName;
    }

    /**
     * Create log consumer that writes to file and optionally to stdout.
     */
    public Consumer<OutputFrame> createLogConsumer() {
        try {
            // Ensure log directory exists
            Files.createDirectories(LOG_DIR);

            // Create log file with timestamp
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path logFile = LOG_DIR.resolve(containerName + "-" + timestamp + ".log");
            logWriter = new PrintWriter(new FileWriter(logFile.toFile(), true), true);

            log.info("Container '{}' logs will be written to: {}", containerName, logFile);

            return outputFrame -> {
                String logLine = "[" + containerName.toUpperCase() + "] " + outputFrame.getUtf8String().trim();

                // Always write to file
                if (logWriter != null) {
                    logWriter.println(logLine);
                }

                // Optionally write to stdout
                if (STDOUT_LOGGING_ENABLED) {
                    System.out.println(logLine);
                }
            };
        } catch (IOException e) {
            log.error("Failed to create log file for container '{}', falling back to stdout only", containerName, e);
            return outputFrame -> System.out.println("[" + containerName.toUpperCase() + "] " + outputFrame.getUtf8String().trim());
        }
    }

    /**
     * Close the log writer.
     */
    public void close() {
        if (logWriter != null) {
            logWriter.close();
            logWriter = null;
        }
    }
}
