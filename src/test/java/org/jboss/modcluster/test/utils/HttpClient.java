package org.jboss.modcluster.test.utils;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client utility for making requests through the balancer.
 */
public class HttpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpClient.class);

    private final OkHttpClient client;
    private final OkHttpClient insecureClient;

    public HttpClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(false)
                .build();

        this.insecureClient = createInsecureClient();
    }

    /**
     * Perform a GET request and return the response.
     */
    public HttpResponse get(String url) throws IOException {
        return get(url, new HashMap<>());
    }

    /**
     * Perform a GET request with custom headers.
     */
    public HttpResponse get(String url, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        headers.forEach(builder::addHeader);

        try (Response response = client.newCall(builder.build()).execute()) {
            return new HttpResponse(
                    response.code(),
                    response.body() != null ? response.body().string() : "",
                    extractCookies(response),
                    extractHeaders(response)
            );
        }
    }

    /**
     * Perform a GET request with session cookie (sticky sessions).
     */
    public HttpResponse getWithSession(String url, String sessionCookie) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", sessionCookie);
        return get(url, headers);
    }

    /**
     * Perform an HTTPS GET request (ignoring certificate validation).
     */
    public HttpResponse getHttps(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();

        try (Response response = insecureClient.newCall(request).execute()) {
            return new HttpResponse(
                    response.code(),
                    response.body() != null ? response.body().string() : "",
                    extractCookies(response),
                    extractHeaders(response)
            );
        }
    }

    /**
     * Make multiple requests to test load balancing distribution.
     */
    public Map<String, Integer> testLoadDistribution(String url, int requestCount) throws IOException {
        Map<String, Integer> workerHits = new HashMap<>();

        for (int i = 0; i < requestCount; i++) {
            HttpResponse response = get(url);
            String worker = extractWorkerName(response.getBody());

            workerHits.merge(worker, 1, Integer::sum);
            log.debug("Request {} -> Worker: {}", i + 1, worker);
        }

        return workerHits;
    }

    /**
     * Extract worker name from response body (assumes app returns worker identity).
     */
    private String extractWorkerName(String body) {
        // This is a simple implementation - adjust based on actual app response format
        if (body.contains("worker1")) return "worker1";
        if (body.contains("worker2")) return "worker2";
        return "unknown";
    }

    private Map<String, String> extractCookies(Response response) {
        Map<String, String> cookies = new HashMap<>();
        response.headers("Set-Cookie").forEach(cookie -> {
            String[] parts = cookie.split(";")[0].split("=", 2);
            if (parts.length == 2) {
                cookies.put(parts[0].trim(), parts[1].trim());
            }
        });
        return cookies;
    }

    private Map<String, String> extractHeaders(Response response) {
        Map<String, String> headers = new HashMap<>();
        response.headers().toMultimap().forEach((key, values) -> {
            if (!values.isEmpty()) {
                headers.put(key, values.get(0));
            }
        });
        return headers;
    }

    /**
     * Create an insecure HTTP client that trusts all certificates (for testing only).
     */
    private OkHttpClient createInsecureClient() {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[]{};
                        }
                    }
            };

            final SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create insecure HTTP client", e);
        }
    }

    /**
     * Response wrapper containing status, body, cookies, and headers.
     */
    public static class HttpResponse {
        private final int statusCode;
        private final String body;
        private final Map<String, String> cookies;
        private final Map<String, String> headers;

        public HttpResponse(int statusCode, String body, Map<String, String> cookies, Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = body;
            this.cookies = cookies;
            this.headers = headers;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }

        public Map<String, String> getCookies() {
            return cookies;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public String getCookie(String name) {
            return cookies.get(name);
        }

        public String getHeader(String name) {
            return headers.get(name);
        }
    }
}
