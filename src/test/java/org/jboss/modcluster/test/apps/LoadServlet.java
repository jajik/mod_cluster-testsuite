package org.jboss.modcluster.test.apps;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Servlet for generating system load for testing load metrics.
 * Supports both CPU and memory load generation.
 */
@WebServlet(urlPatterns = {"/load/cpu", "/load/memory", "/load/memory/release"})
public class LoadServlet extends HttpServlet {

    // Static volatile field — GC root, impossible to collect prematurely
    private static volatile List<byte[]> heldMemory;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String path = request.getServletPath() + (request.getPathInfo() != null ? request.getPathInfo() : "");
        response.setContentType("text/plain");
        PrintWriter out = response.getWriter();

        try {
            if (path.endsWith("/memory/release")) {
                int released = releaseMemory();
                out.println("Released " + released + "MB of held memory");
            } else if (path.endsWith("/memory")) {
                int megabytes = getIntParameter(request, "megabytes", 100);
                generateMemoryLoad(megabytes);
                out.println("Memory load generated: " + megabytes + "MB (held in static field)");
            } else if (path.endsWith("/cpu")) {
                int durationMs = getIntParameter(request, "duration", 5000);
                generateCpuLoad(durationMs);
                out.println("CPU load generated for " + durationMs + "ms");
            } else {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
        } catch (Exception e) {
            out.println("Error generating load: " + e.getMessage());
            e.printStackTrace(out);
        }
    }

    private int getIntParameter(HttpServletRequest request, String name, int defaultValue) {
        String value = request.getParameter(name);
        if (value != null && !value.isEmpty()) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                // Use default
            }
        }
        return defaultValue;
    }

    /**
     * Generate CPU load by running busy loops.
     */
    private void generateCpuLoad(int durationMs) {
        int numThreads = Runtime.getRuntime().availableProcessors();
        List<Thread> threads = new ArrayList<>();

        long endTime = System.currentTimeMillis() + durationMs;

        for (int i = 0; i < numThreads; i++) {
            Thread thread = new Thread(() -> {
                // Burn CPU until duration expires
                while (System.currentTimeMillis() < endTime) {
                    Math.sqrt(Math.random());
                }
            });
            thread.start();
            threads.add(thread);
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Generate memory load by allocating large byte arrays and holding them in a static field.
     * Memory stays allocated until explicitly released via /load/memory/release.
     */
    private void generateMemoryLoad(int megabytes) {
        releaseMemory(); // release any previous allocation
        List<byte[]> memory = new ArrayList<>();

        for (int i = 0; i < megabytes; i++) {
            byte[] chunk = new byte[1024 * 1024]; // 1 MB
            // Fill with data to ensure allocation
            for (int j = 0; j < chunk.length; j += 4096) {
                chunk[j] = (byte) j;
            }
            memory.add(chunk);
        }

        heldMemory = memory; // store in static field — GC root
    }

    private static int releaseMemory() {
        List<byte[]> mem = heldMemory;
        heldMemory = null;
        return mem != null ? mem.size() : 0;
    }
}
