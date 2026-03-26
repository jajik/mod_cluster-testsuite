package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages a native OS process (WildFly server or httpd) for the native test mode.
 *
 * <p>Wraps {@link ProcessBuilder} and {@link Process} to provide:
 * <ul>
 *   <li>Process lifecycle: {@link #start()}, {@link #stop()}, {@link #kill()}</li>
 *   <li>Startup detection: {@link #waitForStartup(String, Duration)} polls the
 *       process output log for a pattern (e.g. {@code WFLYSRV0025})</li>
 *   <li>Command execution: {@link #execCommand(Path, String...)} runs a subprocess
 *       and returns a {@link CommandResult}</li>
 *   <li>Automatic cleanup: a JVM shutdown hook ensures all tracked processes are
 *       destroyed on exit</li>
 * </ul>
 *
 * <p>Process stdout and stderr are merged and redirected to a log file in
 * the working directory ({@code process-output.log}). This file is used by
 * {@link #waitForStartup(String, Duration)} and can be read for diagnostics.
 *
 * <p>Thread safety: instances are not thread-safe. The static shutdown hook
 * uses a thread-safe list to track all active processes.
 *
 * @see TestMode
 * @see NativePortAllocator
 */
public class NativeProcessManager {

    private static final Logger log = LoggerFactory.getLogger(NativeProcessManager.class);

    /** All active processes, tracked for shutdown hook cleanup. */
    private static final List<Process> TRACKED_PROCESSES = new CopyOnWriteArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process p : TRACKED_PROCESSES) {
                if (p.isAlive()) {
                    log.info("Shutdown hook: destroying process tree (pid {})", p.pid());
                    destroyProcessTree(p);
                }
            }
        }, "native-process-cleanup"));
    }

    private final String name;
    private final List<String> command;
    private final Path workDir;
    private final Map<String, String> environment;
    private final Path outputLog;

    private Process process;

    /**
     * Create a new process manager.
     *
     * @param name        human-readable name for log messages (e.g. "worker1", "balancer")
     * @param command     the command and arguments to execute
     * @param workDir     working directory for the process
     * @param environment additional environment variables (merged with inherited environment)
     */
    public NativeProcessManager(String name, List<String> command, Path workDir,
                                Map<String, String> environment) {
        this.name = name;
        this.command = new ArrayList<>(command);
        this.workDir = workDir;
        this.environment = environment != null ? environment : Collections.emptyMap();
        this.outputLog = workDir.resolve("process-output.log");
    }

    /**
     * Start the process.
     *
     * <p>Stdout and stderr are merged and redirected to {@code process-output.log}
     * in the working directory. The process is registered with the global shutdown
     * hook for automatic cleanup.
     *
     * @throws IOException if the process cannot be started
     * @throws IllegalStateException if the process is already running
     */
    public void start() throws IOException {
        if (process != null && process.isAlive()) {
            throw new IllegalStateException("Process '" + name + "' is already running (pid "
                    + process.pid() + ")");
        }

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputLog.toFile());

        pb.environment().putAll(environment);

        log.info("Starting '{}': {} (workDir={})", name, String.join(" ", command), workDir);
        process = pb.start();
        TRACKED_PROCESSES.add(process);
        log.info("Process '{}' started (pid {}), output -> {}", name, process.pid(), outputLog);
    }

    /**
     * Wait for a log pattern to appear in the process output, indicating successful startup.
     *
     * <p>Polls the output log file at 1-second intervals, checking each new line
     * for the given pattern (substring match). Returns as soon as the pattern is found.
     *
     * @param pattern   substring to search for in each log line (e.g. "WFLYSRV0025")
     * @param timeout   maximum time to wait for the pattern
     * @throws RuntimeException if the pattern is not found within the timeout,
     *                          or if the process exits before the pattern appears
     */
    public void waitForStartup(String pattern, Duration timeout) {
        log.info("Waiting for '{}' startup pattern '{}' (timeout: {})", name, pattern, timeout);
        long deadline = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                String logContent = readOutputLog();
                throw new RuntimeException("Process '" + name + "' exited with code "
                        + process.exitValue() + " before startup pattern '" + pattern
                        + "' appeared. Output:\n" + logContent);
            }

            String logContent = readOutputLog();
            if (logContent.contains(pattern)) {
                log.info("Startup pattern '{}' detected for '{}'", pattern, name);
                return;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for '" + name + "' startup", e);
            }
        }

        String logContent = readOutputLog();
        throw new RuntimeException("Timeout waiting for '" + name + "' startup pattern '"
                + pattern + "' after " + timeout + ". Output:\n" + logContent);
    }

    /**
     * Gracefully stop the process and its entire process tree.
     *
     * <p>On Windows, batch scripts (e.g. {@code standalone.bat}) spawn child processes
     * (e.g. {@code java.exe}) that are not killed when the parent cmd.exe is destroyed.
     * This method kills all descendants first, then the root process.
     *
     * <p>Waits up to 30 seconds for the process to exit. If it does not exit
     * within that time, it is forcibly destroyed.
     */
    public void stop() {
        if (process == null || !process.isAlive()) {
            log.debug("Process '{}' is not running, nothing to stop", name);
            return;
        }

        log.info("Stopping process '{}' (pid {})", name, process.pid());
        destroyProcessTree(process);

        try {
            boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            if (!exited) {
                log.warn("Process '{}' did not exit within 30s after tree kill", name);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }

        TRACKED_PROCESSES.remove(process);
        log.info("Process '{}' stopped", name);
    }

    /**
     * Forcibly kill the process and its entire process tree.
     *
     * <p>Does not wait for graceful shutdown — all processes in the tree are
     * destroyed immediately. Use {@link #stop()} for graceful shutdown.
     */
    public void kill() {
        if (process == null || !process.isAlive()) {
            log.debug("Process '{}' is not running, nothing to kill", name);
            return;
        }

        log.info("Killing process '{}' (pid {})", name, process.pid());
        destroyProcessTree(process);

        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        TRACKED_PROCESSES.remove(process);
        log.info("Process '{}' killed", name);
    }

    /**
     * Check whether the process is currently running.
     *
     * @return {@code true} if the process has been started and has not yet exited
     */
    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * Get the path to the combined stdout/stderr output log.
     *
     * @return path to the process output log file
     */
    public Path getOutputLog() {
        return outputLog;
    }

    /**
     * Read the full contents of the process output log.
     *
     * @return the log file contents, or an empty string if the file does not exist
     */
    public String readOutputLog() {
        try {
            if (Files.exists(outputLog)) {
                return Files.readString(outputLog, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Failed to read output log for '{}': {}", name, e.getMessage());
        }
        return "";
    }

    /**
     * Execute a command as a subprocess and return the result.
     *
     * <p>This is a static utility method — it does not interact with the managed
     * process. It starts a new subprocess, waits for it to complete (up to 2 minutes),
     * and captures its stdout and stderr separately.
     *
     * @param workDir working directory for the command
     * @param command the command and arguments to execute
     * @return a {@link CommandResult} with exit code, stdout, and stderr
     * @throws Exception if the command cannot be started or times out
     */
    public static CommandResult execCommand(Path workDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile());

        Process proc = pb.start();
        proc.getOutputStream().close();

        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            } catch (IOException e) {
                return "";
            }
        });

        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            } catch (IOException e) {
                return "";
            }
        });

        long timeoutSeconds = TestTimeouts.EXEC_COMMAND.toSeconds();
        boolean completed = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            proc.destroyForcibly();
            throw new RuntimeException("Command timed out after " + timeoutSeconds + "s: "
                    + String.join(" ", command));
        }

        String stdout = stdoutFuture.get(10, TimeUnit.SECONDS);
        String stderr = stderrFuture.get(10, TimeUnit.SECONDS);

        return new CommandResult(proc.exitValue(), stdout, stderr);
    }

    /**
     * Destroy a process and all its descendants (entire process tree).
     *
     * <p>On Windows, a batch script like {@code standalone.bat} spawns {@code java.exe}
     * as a child process. Calling {@link Process#destroy()} only kills the parent
     * {@code cmd.exe}, leaving the child {@code java.exe} running and holding ports.
     * This method walks the process tree bottom-up, destroying descendants first,
     * then the root process.
     */
    private static void destroyProcessTree(Process process) {
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(descendant -> {
            log.info("Destroying descendant process (pid {})", descendant.pid());
            descendant.destroyForcibly();
        });
        process.destroyForcibly();
    }
}
