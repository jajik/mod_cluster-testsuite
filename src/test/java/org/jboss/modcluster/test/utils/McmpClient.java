package org.jboss.modcluster.test.utils;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Client for the Mod Cluster Management Protocol (MCMP).
 * Sends MCMP commands to httpd's mod_proxy_cluster management port
 * using custom HTTP methods (INFO, DUMP, ENABLE-APP, DISABLE-APP, STOP-APP).
 *
 * <p>MCMP INFO response format (line-based):
 * <pre>
 * Node: [1],Name: worker1,Balancer: mycluster,LBGroup: ,Host: 10.89.1.3,Port: 8080,Type: http,...
 * Vhost: [1:1:1], Alias: default-host
 * Context: [1:1:1], Context: /demo, Status: ENABLED
 * </pre>
 */
public class McmpClient {

    private static final Logger log = LoggerFactory.getLogger(McmpClient.class);

    private static final MediaType FORM_URLENCODED = MediaType.parse("application/x-www-form-urlencoded");

    private String baseUrl;
    private OkHttpClient client;

    /**
     * Creates a new MCMP client.
     *
     * @param host the hostname or IP of the httpd MCMP management endpoint
     * @param port the mapped port for MCMP management (6666 internally)
     */
    public McmpClient(final String host, final int port) {
        this.baseUrl = "http://" + host + ":" + port;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Switches this client to use HTTPS with a trust-all certificate verifier.
     * Used when mTLS is configured on the MCMP port — the management queries
     * from test code need SSL but do not present a client certificate
     * (the VirtualHost uses {@code SSLVerifyClient optional}).
     */
    public void enableSsl() {
        try {
            X509TrustManager trustAllManager = new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                @Override
                public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
                    // trust all for test queries
                }

                @Override
                public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
                    // trust all for test queries
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAllManager}, null);

            this.client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .sslSocketFactory(sslContext.getSocketFactory(), trustAllManager)
                    .hostnameVerifier((hostname, session) -> true)
                    .build();

            this.baseUrl = baseUrl.replace("http://", "https://");
            log.info("McmpClient switched to HTTPS (trust-all)");
        } catch (Exception e) {
            throw new RuntimeException("Failed to enable SSL on McmpClient", e);
        }
    }

    /**
     * Sends an INFO command and returns the raw response text.
     * INFO returns structured information about all nodes, vhosts, and contexts.
     *
     * @return raw INFO response text
     * @throws IOException if the request fails
     */
    public String sendInfo() throws IOException {
        return sendCommand("INFO", "*", null);
    }

    /**
     * Sends a DUMP command and returns the raw response text.
     *
     * @return raw DUMP response text
     * @throws IOException if the request fails
     */
    public String sendDump() throws IOException {
        return sendCommand("DUMP", "*", null);
    }

    /**
     * Enables a specific application (context) on a node.
     *
     * @param jvmRoute the JVM route / node name (e.g., "worker1")
     * @param context the context path (e.g., "/demo")
     * @param alias the virtual host alias (e.g., "default-host")
     * @throws IOException if the request fails
     */
    public void enableApp(final String jvmRoute, final String context, final String alias) throws IOException {
        String normalizedContext = context.startsWith("/") ? context : "/" + context;
        String body = "JVMRoute=" + jvmRoute + "&Alias=" + alias + "&Context=" + normalizedContext;
        sendCommand("ENABLE-APP", "*", body);
        log.info("ENABLE-APP sent for JVMRoute={}, Context={}, Alias={}", jvmRoute, normalizedContext, alias);
    }

    /**
     * Disables a specific application (context) on a node.
     * The context will not receive new requests but existing sessions continue.
     *
     * @param jvmRoute the JVM route / node name
     * @param context the context path
     * @param alias the virtual host alias
     * @throws IOException if the request fails
     */
    public void disableApp(final String jvmRoute, final String context, final String alias) throws IOException {
        String normalizedContext = context.startsWith("/") ? context : "/" + context;
        String body = "JVMRoute=" + jvmRoute + "&Alias=" + alias + "&Context=" + normalizedContext;
        sendCommand("DISABLE-APP", "*", body);
        log.info("DISABLE-APP sent for JVMRoute={}, Context={}, Alias={}", jvmRoute, normalizedContext, alias);
    }

    /**
     * Stops a specific application (context) on a node.
     * The context will immediately stop receiving all requests.
     *
     * @param jvmRoute the JVM route / node name
     * @param context the context path
     * @param alias the virtual host alias
     * @throws IOException if the request fails
     */
    public void stopApp(final String jvmRoute, final String context, final String alias) throws IOException {
        String normalizedContext = context.startsWith("/") ? context : "/" + context;
        String body = "JVMRoute=" + jvmRoute + "&Alias=" + alias + "&Context=" + normalizedContext;
        sendCommand("STOP-APP", "*", body);
        log.info("STOP-APP sent for JVMRoute={}, Context={}, Alias={}", jvmRoute, normalizedContext, alias);
    }

    /**
     * Enables all contexts on a node.
     *
     * @param jvmRoute the JVM route / node name
     * @throws IOException if the request fails
     */
    public void enableNode(final String jvmRoute) throws IOException {
        String body = "JVMRoute=" + jvmRoute;
        sendCommand("ENABLE-APP", "*", body);
        log.info("ENABLE-APP (all contexts) sent for JVMRoute={}", jvmRoute);
    }

    /**
     * Disables all contexts on a node.
     *
     * @param jvmRoute the JVM route / node name
     * @throws IOException if the request fails
     */
    public void disableNode(final String jvmRoute) throws IOException {
        String body = "JVMRoute=" + jvmRoute;
        sendCommand("DISABLE-APP", "*", body);
        log.info("DISABLE-APP (all contexts) sent for JVMRoute={}", jvmRoute);
    }

    /**
     * Stops all contexts on a node.
     *
     * @param jvmRoute the JVM route / node name
     * @throws IOException if the request fails
     */
    public void stopNode(final String jvmRoute) throws IOException {
        String body = "JVMRoute=" + jvmRoute;
        sendCommand("STOP-APP", "*", body);
        log.info("STOP-APP (all contexts) sent for JVMRoute={}", jvmRoute);
    }

    /**
     * Removes all application contexts for a node.
     * After removal, the node will re-register its contexts via the next
     * STATUS/CONFIG cycle from the WildFly worker.
     *
     * @param jvmRoute the JVM route / node name
     * @throws IOException if the request fails
     */
    public void removeNode(final String jvmRoute) throws IOException {
        String body = "JVMRoute=" + jvmRoute;
        sendCommand("REMOVE-APP", "*", body);
        log.info("REMOVE-APP (all contexts) sent for JVMRoute={}", jvmRoute);
    }

    /**
     * Parses an INFO response into a list of structured node information objects.
     *
     * <p>MCMP INFO responses list all Node lines first, then all Vhost lines,
     * then all Context lines. Each Vhost and Context line contains an identifier
     * {@code [X:Y:Z]} where X is the node index matching the Node line's {@code [X]}.
     * This method uses that index to associate Vhost/Context lines with the correct node.
     *
     * @param infoResponse raw INFO response text
     * @return list of parsed node information
     */
    public List<McmpNodeInfo> parseInfo(final String infoResponse) {
        if (infoResponse == null || infoResponse.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // Map from node index (from "Node: [X],...") to parsed node info
        Map<Integer, McmpNodeInfo> nodesByIndex = new LinkedHashMap<>();

        String[] lines = infoResponse.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.startsWith("Node:")) {
                int nodeIndex = extractNodeIndex(trimmed);
                McmpNodeInfo node = parseNodeLine(trimmed);
                if (node != null && nodeIndex >= 0) {
                    nodesByIndex.put(nodeIndex, node);
                } else if (node != null) {
                    log.warn("Could not extract node index from line, using name as fallback: {}", trimmed);
                    nodesByIndex.put(nodesByIndex.size(), node);
                }
            } else if (trimmed.startsWith("Vhost:")) {
                int nodeIndex = extractFirstIndex(trimmed);
                McmpNodeInfo node = nodesByIndex.get(nodeIndex);
                if (node != null) {
                    parseVhostLine(trimmed, node);
                } else {
                    log.warn("Vhost line references unknown node index {}: {}", nodeIndex, trimmed);
                }
            } else if (trimmed.startsWith("Context:")) {
                int nodeIndex = extractFirstIndex(trimmed);
                McmpNodeInfo node = nodesByIndex.get(nodeIndex);
                if (node != null) {
                    parseContextLine(trimmed, node);
                } else {
                    log.warn("Context line references unknown node index {}: {}", nodeIndex, trimmed);
                }
            }
        }

        return new ArrayList<>(nodesByIndex.values());
    }

    /**
     * Extracts the node index from a Node line.
     * Example: "Node: [3],Name: worker4,..." returns 3.
     *
     * @param line the Node line
     * @return the node index, or -1 if not found
     */
    private int extractNodeIndex(final String line) {
        int openBracket = line.indexOf('[');
        int closeBracket = line.indexOf(']');
        if (openBracket >= 0 && closeBracket > openBracket) {
            try {
                return Integer.parseInt(line.substring(openBracket + 1, closeBracket).trim());
            } catch (NumberFormatException e) {
                log.warn("Could not parse node index from: {}", line);
            }
        }
        return -1;
    }

    /**
     * Extracts the first index (node index) from a Vhost or Context identifier.
     * Example: "Vhost: [2:1:4], Alias: default-host" returns 2.
     * Example: "Context: [0:1:0], Context: /demo, Status: ENABLED" returns 0.
     *
     * @param line the Vhost or Context line
     * @return the node index (first number in brackets), or -1 if not found
     */
    private int extractFirstIndex(final String line) {
        int openBracket = line.indexOf('[');
        int colon = line.indexOf(':', openBracket + 1);
        if (openBracket >= 0 && colon > openBracket) {
            try {
                return Integer.parseInt(line.substring(openBracket + 1, colon).trim());
            } catch (NumberFormatException e) {
                log.warn("Could not parse first index from: {}", line);
            }
        }
        return -1;
    }

    /**
     * Sends a raw MCMP command to the management endpoint.
     * Uses OkHttp because MCMP requires non-standard HTTP methods (INFO, DUMP, ENABLE-APP, etc.)
     * that {@code java.net.HttpURLConnection} does not support.
     *
     * @param method the HTTP method (INFO, DUMP, ENABLE-APP, etc.)
     * @param path the request path
     * @param body optional request body (for ENABLE-APP, DISABLE-APP, STOP-APP)
     * @return the response body as a string
     * @throws IOException if the request fails
     */
    private String sendCommand(final String method, final String path, final String body) throws IOException {
        String requestUrl = baseUrl + "/" + path;
        RequestBody requestBody = (body != null) ? RequestBody.create(body, FORM_URLENCODED) : null;

        Request request = new Request.Builder()
                .url(requestUrl)
                .method(method, requestBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            int responseCode = response.code();
            String responseText = response.body() != null ? response.body().string() : "";

            log.debug("MCMP {} {} -> {} ({} chars)", method, path, responseCode, responseText.length());

            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("MCMP " + method + " failed with status " + responseCode + ": " + responseText);
            }

            return responseText;
        }
    }

    /**
     * Parses a Node line from INFO response.
     * Example: Node: [1],Name: worker1,Balancer: mycluster,LBGroup: ,Host: 10.89.1.3,Port: 8080,Type: http,...,Load: 100
     */
    private McmpNodeInfo parseNodeLine(final String line) {
        McmpNodeInfo node = new McmpNodeInfo();

        String[] parts = line.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith("Name:")) {
                node.name = trimmed.substring("Name:".length()).trim();
            } else if (trimmed.startsWith("Balancer:")) {
                node.balancer = trimmed.substring("Balancer:".length()).trim();
            } else if (trimmed.startsWith("LBGroup:")) {
                node.lbGroup = trimmed.substring("LBGroup:".length()).trim();
            } else if (trimmed.startsWith("Host:")) {
                node.host = trimmed.substring("Host:".length()).trim();
            } else if (trimmed.startsWith("Port:")) {
                try {
                    node.port = Integer.parseInt(trimmed.substring("Port:".length()).trim());
                } catch (NumberFormatException e) {
                    log.warn("Could not parse port from: {}", trimmed);
                }
            } else if (trimmed.startsWith("Type:")) {
                node.type = trimmed.substring("Type:".length()).trim();
            } else if (trimmed.startsWith("Load:")) {
                try {
                    node.load = Integer.parseInt(trimmed.substring("Load:".length()).trim());
                } catch (NumberFormatException e) {
                    log.warn("Could not parse load from: {}", trimmed);
                }
            }
        }

        if (node.name == null || node.name.isEmpty()) {
            log.warn("Could not parse node name from line: {}", line);
            return null;
        }

        return node;
    }

    /**
     * Parses a Vhost line and adds the alias to the current node.
     * Example: Vhost: [1:1:1], Alias: default-host
     */
    private void parseVhostLine(final String line, final McmpNodeInfo node) {
        String[] parts = line.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith("Alias:")) {
                String alias = trimmed.substring("Alias:".length()).trim();
                if (!alias.isEmpty()) {
                    node.aliases.add(alias);
                }
            }
        }
    }

    /**
     * Parses a Context line and adds it to the current node.
     * Example: Context: [1:1:1], Context: /demo, Status: ENABLED
     */
    private void parseContextLine(final String line, final McmpNodeInfo node) {
        McmpContextInfo ctx = new McmpContextInfo();

        String[] parts = line.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            // Skip the "Context: [1:1:1]" identifier part
            if (trimmed.startsWith("Context:") && !trimmed.contains("[")) {
                ctx.path = trimmed.substring("Context:".length()).trim();
            } else if (trimmed.startsWith("Status:")) {
                ctx.status = trimmed.substring("Status:".length()).trim();
            }
        }

        if (ctx.path != null) {
            node.contexts.add(ctx);
        }
    }

    /**
     * Structured representation of a node from MCMP INFO response.
     */
    public static class McmpNodeInfo {

        /** Node name / JVM route (e.g., "worker1"). */
        public String name;

        /** Balancer name (e.g., "mycluster"). */
        public String balancer;

        /** Load-balancing group name. */
        public String lbGroup;

        /** Node host address. */
        public String host;

        /** Node port. */
        public int port;

        /** Connection type (e.g., "http", "https", "ajp"). */
        public String type;

        /** Current load value (0-100, where 100 = fully available). */
        public int load;

        /** Virtual host aliases for this node. */
        public final List<String> aliases = new ArrayList<>();

        /** Contexts registered on this node. */
        public final List<McmpContextInfo> contexts = new ArrayList<>();

        @Override
        public String toString() {
            return "McmpNodeInfo{name='" + name + "', balancer='" + balancer
                    + "', lbGroup='" + lbGroup + "', host='" + host
                    + "', port=" + port + ", type='" + type
                    + "', load=" + load + ", contexts=" + contexts.size() + "}";
        }
    }

    /**
     * Structured representation of a context from MCMP INFO response.
     */
    public static class McmpContextInfo {

        /** Context path (e.g., "/demo"). */
        public String path;

        /** Context status (e.g., "ENABLED", "DISABLED", "STOPPED"). */
        public String status;

        @Override
        public String toString() {
            return "McmpContextInfo{path='" + path + "', status='" + status + "'}";
        }
    }
}
