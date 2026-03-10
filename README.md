# ModCluster Test Suite

[![ModCluster Tests](../../actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)

Comprehensive test suite for mod_cluster with WildFly/EAP workers and Undertow/httpd balancers.

## Architecture

This test suite uses:
- **JUnit 5** for test framework
- **AssertJ** for soft assertions
- **Testcontainers** for container-based testing
- **Creaper** for WildFly/EAP management (clean, type-safe API)
- **Dependency Injection** pattern (no abstract base classes)

## Project Structure

```
src/test/java/org/jboss/modcluster/test/
├── apps/                      # Test application endpoints (e.g. WebSocket)
├── base/                      # Core test infrastructure
│   ├── BalancerType.java     # Balancer type enum
│   └── ModClusterTestExtension.java  # JUnit 5 extension for DI
├── cli/                       # CLI & management tests
│   ├── CliManagementTest.java
│   └── MultipleUndertowServerSupportTest.java
├── configuration/             # Configuration tests
│   ├── DynamicReconfTest.java
│   ├── InitialLoadTest.java
│   ├── SettingsTest.java
│   └── WorkerWithOneNotRespondingProxyTest.java
├── context/                   # Context lifecycle tests
│   └── ContextLifecycleTest.java
├── failover/                  # Failover scenarios
│   ├── AdvancedFailoverTest.java
│   ├── FailoverSettingsTest.java
│   ├── StickySessionTest.java
│   └── WebSocketsTest.java
├── ha/                        # High availability & soak tests
│   ├── HighAvailabilityTest.java
│   └── SoakTest.java
├── integration/               # End-to-end integration tests
├── loadbalancing/             # Load balancing tests
│   ├── LoadBalancingGroupFailoverTest.java
│   └── LoadMetricsTest.java
├── session/                   # Session management tests
│   └── SessionManagementTest.java
├── ssl/                       # SSL/TLS tests
│   ├── SslCrlTest.java
│   ├── SslFailoverTest.java
│   └── SslWorkerAuthenticationTest.java
└── utils/                     # Utilities
    ├── BalancerContainer.java
    ├── WildFlyContainer.java
    ├── HttpClient.java
    └── ...
```

## Running Tests

### Prerequisites

- Java 11 or higher
- Maven 3.6+
- Docker or Podman
- WildFly or EAP ZIP distribution (optional, will use pre-built images as fallback)

### Quick Start

1. **Place your WildFly/EAP ZIP in the distributions directory**:
   ```bash
   cp ~/Downloads/wildfly-39.0.1.Final.zip distributions/
   # or
   cp ~/Downloads/jboss-eap-8.0.0.zip distributions/
   ```

   Alternatively, download WildFly from Maven Central:
   ```bash
   mvn generate-test-resources -Pdownload-wildfly -Dwildfly.version=34.0.1.Final -DskipTests
   ```

2. **Check prerequisites** (optional):
   ```bash
   ./setup.sh
   ```

3. **Run tests** (Docker images are built automatically on first run):
   ```bash
   mvn test
   ```

### Run all tests with Undertow balancer (default)

```bash
mvn test
```

### Using specific ZIP distribution

```bash
# Via system property
mvn test -Dwildfly.zip.path=/path/to/wildfly-31.0.1.Final.zip

# Via environment variable
export WILDFLY_ZIP_PATH=/path/to/jboss-eap-8.0.0.zip
mvn test
```

### Override Java version

```bash
# During setup
CONTAINER_JAVA_VERSION=17 ./setup.sh

# During tests
mvn test -Dcontainer.java.version=17

# Or combine both
CONTAINER_JAVA_VERSION=17 ./setup.sh
mvn test -Dcontainer.java.version=17
```

### Run tests with httpd balancer

```bash
mvn test -Phttpd
```

or

```bash
mvn test -Dbalancer.type=httpd
```

### Run specific test class

```bash
mvn test -Dtest=StickySessionTest
```

### Run with specific WildFly version

```bash
mvn test -Dwildfly.version=31.0.1.Final
```

## Writing Tests

### Basic Test Structure

Tests use dependency injection via JUnit 5 extensions:

```java
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class MyTest {

    @InjectSoftAssertions
    private SoftAssertions softly;

    @Test
    public void testSomething(TestCluster cluster, HttpClient httpClient) throws Exception {
        // Start workers
        cluster.startWorkers(2);

        // Get balancer URL
        String url = cluster.getBalancer().getHttpUrl() + "/demo";

        // Make requests
        HttpResponse response = httpClient.get(url);

        // Assertions
        softly.assertThat(response.getStatusCode()).isEqualTo(200);
    }
}
```

### Injected Dependencies

- `TestCluster cluster` - Provides access to balancer and workers
- `HttpClient httpClient` - HTTP client for making requests
- `BalancerContainer balancer` - Direct balancer access
- `@InjectSoftAssertions SoftAssertions softly` - Soft assertions

### Starting Workers

```java
// Start 1 worker
cluster.startWorkers(1);
WildFlyContainer worker = cluster.getWorker1();

// Start 2 workers
cluster.startWorkers(2);
WildFlyContainer worker1 = cluster.getWorker1();
WildFlyContainer worker2 = cluster.getWorker2();
```

### Making HTTP Requests

```java
// Simple GET
HttpResponse response = httpClient.get(url);

// GET with session
HttpResponse response = httpClient.getWithSession(url, "JSESSIONID=" + sessionId);

// HTTPS request
HttpResponse response = httpClient.getHttps(httpsUrl);

// Test load distribution
Map<String, Integer> distribution = httpClient.testLoadDistribution(url, 100);
```

### Using WildFly CLI

```java
WildFlyContainer worker = cluster.getWorker1();
String result = worker.executeCli("/subsystem=modcluster:read-resource");
```

## Test Categories

### CLI & Management Tests
- **CliManagementTest** - CLI operations, configuration read/write, deployment status
- **MultipleUndertowServerSupportTest** - Multiple Undertow server instances

### Failover Tests
- **AdvancedFailoverTest** - Failover with active sessions, deterministic and graceful failover
- **FailoverSettingsTest** - Failover configuration options
- **StickySessionTest** - Session affinity across requests
- **WebSocketsTest** - WebSocket proxying and failover

### SSL Tests
- **SslCrlTest** - Certificate Revocation List validation
- **SslFailoverTest** - SSL with failover scenarios
- **SslWorkerAuthenticationTest** - Mutual SSL authentication

### Load Balancing Tests
- **LoadBalancingGroupFailoverTest** - Load distribution and group failover
- **LoadMetricsTest** - Load metrics calculation and custom metrics

### Configuration Tests
- **DynamicReconfTest** - Dynamic worker registration and reconfiguration
- **InitialLoadTest** - Initial load reporting
- **SettingsTest** - Configuration settings validation
- **WorkerWithOneNotRespondingProxyTest** - Proxy resilience

### Context Tests
- **ContextLifecycleTest** - Context enable/disable, exclusion patterns, deployment registration

### Session Tests
- **SessionManagementTest** - Session timeout, custom cookies, JVM routes

### High Availability Tests
- **HighAvailabilityTest** - Hot standby, multiple balancers
- **SoakTest** - Long-running stability testing

## Test Coverage

### Current Status vs noe-tests

This test suite aims for feature parity with `noe-tests/modcluster` (64 test files). The following table shows coverage status:

| Area | Implemented | Key Test Classes |
|------|-------------|-----------------|
| CLI & Management | Yes | CliManagementTest, MultipleUndertowServerSupportTest |
| Sticky Sessions | Yes | StickySessionTest |
| Advanced Failover | Yes | AdvancedFailoverTest, FailoverSettingsTest |
| Load Balancing | Yes | LoadBalancingGroupFailoverTest, LoadMetricsTest |
| SSL/TLS | Yes | SslCrlTest, SslFailoverTest, SslWorkerAuthenticationTest |
| Dynamic Reconfiguration | Yes | DynamicReconfTest, SettingsTest |
| Context Lifecycle | Yes | ContextLifecycleTest |
| Session Management | Yes | SessionManagementTest |
| High Availability | Yes | HighAvailabilityTest |
| WebSockets | Yes | WebSocketsTest |
| Initial Load | Yes | InitialLoadTest |
| Soak/Stress Testing | Yes | SoakTest |

### Not Yet Implemented

| Area | noe-tests Reference |
|------|-------------------|
| AJP Protocol | ModClusterAJP.groovy |
| EJB over HTTP | EjbViaHttpTest.groovy |
| mod_proxy / mod_rewrite | ModProxyTest.groovy, ModRewriteTest.groovy |
| Bug-specific regressions | JBCS*, JBQA* test files |

## How It Works

### WildFly/EAP Distribution (Single ZIP for Everything!)

**The same WildFly/EAP ZIP is used for both workers AND the Undertow balancer** - just with different configurations:

1. Tests look for ZIP distributions in the `distributions/` directory
2. If found, builds Docker images using `docker build` (avoids Testcontainers large file limitations):
   - Checks if image already exists (reuses if available)
   - If not, runs `docker build` directly with the ZIP
   - Uses Red Hat UBI9 with OpenJDK (version auto-detected based on WildFly/EAP version)
     - **WildFly 31+ / EAP 8+**: Uses OpenJDK 17
     - **WildFly 30 and earlier / EAP 7.x**: Uses OpenJDK 11
   - Extracts the ZIP inside the image
   - **Same image used for both workers and balancer** (configuration differs at runtime)
   - **For workers**: Starts with `standalone-ha.xml`, connects to balancer
   - **For Undertow balancer**: Starts with `standalone-ha.xml`, acts as load balancer (advertise enabled)
3. If no ZIP is found, falls back to pre-built container images

**Image naming**: `modcluster-test/wildfly-31-0-1-final:openjdk-17`

### Container Clustering (JGroups)

WildFly uses JGroups for worker-to-worker session replication. The default `standalone-ha.xml` uses UDP multicast for cluster discovery, which does not work in Docker/Podman networks. To solve this, `WildFlyContainer` automatically reconfigures JGroups at startup:

1. **Binds the private interface to `0.0.0.0`** (`-bprivate 0.0.0.0`) so JGroups TCP listens on the container's network interface instead of `127.0.0.1`
2. **Switches from UDP to the TCP stack** and replaces MPING (multicast discovery) with **TCPPING** using container network aliases (`worker1[7600]`, `worker2[7600]`, etc.)

This is transparent to the tests — JGroups handles internal session replication while mod_cluster handles balancer-to-worker communication via MCMP over HTTP. The two layers are independent.

> **Note:** The reference noe-tests achieve the same result differently — they set `AS7_PRIVATE_IP_ADDRESS` to a real IP and rely on native multicast, which works on bare metal/VM networks.

### Balancers
- **Undertow balancer**:
  - **With ZIP**: Builds from your WildFly/EAP ZIP (same as workers)
  - **Without ZIP**: Falls back to a pre-built image (placeholder: `quay.io/modcluster/mod_cluster-undertow:latest` — does not exist yet, provide your own via `-Dbalancer.undertow.image=`)
  - Customizable via `-Dbalancer.undertow.image=`
- **httpd balancer**:
  - Uses pre-built image (placeholder: `quay.io/modcluster/mod_cluster-httpd:latest` — does not exist yet, provide your own via `-Dbalancer.httpd.image=`)
  - Customizable via `-Dbalancer.httpd.image=`

### ZIP Distribution Priority
1. System property: `-Dwildfly.zip.path=/path/to/wildfly.zip`
2. Environment variable: `WILDFLY_ZIP_PATH=/path/to/wildfly.zip`
3. Convention: First ZIP found in `distributions/` directory
4. Fallback: Pre-built container images

## Container Images

Default fallback images (when no ZIP provided). The `quay.io/modcluster/` images are **placeholders that do not exist yet** — provide a ZIP or override with your own images.

| Component | Default Image (placeholder) | Override |
|-----------|---------------------------|----------|
| Undertow balancer | `quay.io/modcluster/mod_cluster-undertow:latest` | `-Dbalancer.undertow.image=` |
| httpd balancer | `quay.io/modcluster/mod_cluster-httpd:latest` | `-Dbalancer.httpd.image=` |
| WildFly workers | `quay.io/wildfly/wildfly:<version>` | Provide a ZIP in `distributions/` |

In practice, always provide a WildFly/EAP ZIP — the fallback images are not published.

## Contributing

When adding new tests:
1. Choose appropriate package based on test category
2. Use `@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})`
3. Inject `SoftAssertions` for assertions
4. Inject `TestCluster` and `HttpClient` as needed
5. Document test purpose in class javadoc
6. Follow existing naming conventions

See [CONTRIBUTING.adoc](CONTRIBUTING.adoc) for detailed guidelines.

## License

See LICENSE file for details.
