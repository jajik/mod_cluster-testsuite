# ModCluster Test Suite

Comprehensive test suite for mod_cluster with WildFly/EAP workers and Undertow/httpd balancers.

## Architecture

This test suite uses:
- **JUnit 5** for test framework
- **AssertJ** for soft assertions
- **Testcontainers** for container-based testing
- **Dependency Injection** pattern (no abstract base classes)

## Project Structure

```
src/test/java/org/jboss/modcluster/test/
├── base/                      # Core test infrastructure
│   ├── BalancerType.java     # Balancer type enum
│   └── ModClusterTestExtension.java  # JUnit 5 extension for DI
├── cli/                       # CLI-based tests
│   └── AS7CLITest.java
├── failover/                  # Failover scenarios
│   └── StickySessionTest.java
├── loadbalancing/             # Load balancing tests
│   └── LoadBalancingGroupFailoverTest.java
├── ssl/                       # SSL/TLS tests
│   └── SSLTest.java
├── configuration/             # Configuration tests
│   └── DynamicReconfTest.java
└── utils/                     # Utilities
    ├── BalancerContainer.java
    ├── WildFlyContainer.java
    └── HttpClient.java
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
   cp ~/Downloads/wildfly-31.0.1.Final.zip distributions/
   # or
   cp ~/Downloads/jboss-eap-8.0.0.zip distributions/
   ```

2. **Run tests**:
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

## Jenkins Matrix

The test suite supports matrix builds in Jenkins for testing against both balancer types:

```groovy
matrix {
    axes {
        axis {
            name 'BALANCER_TYPE'
            values 'undertow', 'httpd'
        }
    }
}
```

See `Jenkinsfile` for complete pipeline configuration.

## Test Categories

Based on the existing test matrix:

### CLI Tests
- AS7CLITest - CLI command testing
- AS7LegacyOperationsSmokeTest - Legacy operations compatibility
- AS7RestartOrNotTest - Restart scenarios

### Failover Tests
- DeterministicFailoverTest - Predictable failover behavior
- FailoverTest - General failover scenarios
- FailoverUnregisterTest - Unregistration during failover
- SmoothFailoverTest - Graceful failover
- StickySessionTest - Session affinity

### SSL Tests
- SSLTest - Basic SSL connectivity
- SslCrlTest - Certificate revocation lists
- SslFailoverElytronTest - SSL with Elytron
- SslWorkerAuthenticationTest - Worker authentication

### Load Balancing Tests
- LoadBalancingGroupFailoverTest - Group failover
- LoadCalculationTest - Load metrics calculation
- InitialLoadTest - Initial load distribution

### Configuration Tests
- DynamicReconfTest - Dynamic reconfiguration
- SettingsTest - Configuration settings
- TwoBalancerSettingsTest - Multiple balancers

### Session Tests
- SessionTimeoutTest - Session timeout handling
- CookieNameTest - Custom cookie names

### Context Tests
- AutoEnableContextsTest - Automatic context enablement
- ContextDelimiterTest - Context path delimiters
- ExcludedContextsTest - Context exclusion
- LocationContextTest - Location-based contexts
- ManyContextsTest - High context count

### Integration Tests
- EjbViaHttpTest - EJB over HTTP
- WebSocketsTest - WebSocket support
- ModProxyTest - mod_proxy integration
- ModRewriteTest - mod_rewrite integration

## How It Works

### WildFly/EAP Distribution (Single ZIP for Everything!)

**The same WildFly/EAP ZIP is used for both workers AND the Undertow balancer** - just with different configurations:

1. Tests look for ZIP distributions in the `distributions/` directory
2. If found, Testcontainers builds custom images on-the-fly:
   - Uses Red Hat UBI9 with OpenJDK 11 as base
   - Extracts the ZIP
   - **For workers**: Starts with `standalone-ha.xml`, connects to balancer
   - **For Undertow balancer**: Starts with `standalone-ha.xml`, acts as load balancer (advertise enabled)
3. If no ZIP is found, falls back to pre-built container images

### Balancers
- **Undertow balancer**:
  - **With ZIP**: Builds from your WildFly/EAP ZIP (same as workers)
  - **Without ZIP**: Falls back to `quay.io/modcluster/mod_cluster-undertow:latest`
  - Customizable via `-Dbalancer.undertow.image=`
- **httpd balancer**:
  - Always uses pre-built image: `quay.io/modcluster/mod_cluster-httpd:latest`
  - Customizable via `-Dbalancer.httpd.image=`

### ZIP Distribution Priority
1. System property: `-Dwildfly.zip.path=/path/to/wildfly.zip`
2. Environment variable: `WILDFLY_ZIP_PATH=/path/to/wildfly.zip`
3. Convention: First ZIP found in `distributions/` directory
4. Fallback: Pre-built container images

## Container Images

Default images used (when no ZIP provided):
- **Undertow balancer**: `quay.io/modcluster/mod_cluster-undertow:latest`
- **httpd balancer**: `quay.io/modcluster/mod_cluster-httpd:latest`
- **WildFly workers**: `quay.io/wildfly/wildfly:31.0.1.Final`

Custom balancer images:
```bash
mvn test -Dbalancer.undertow.image=my-custom-undertow:1.0
mvn test -Dbalancer.httpd.image=my-custom-httpd:1.0
```

## Contributing

When adding new tests:
1. Choose appropriate package based on test category
2. Use `@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})`
3. Inject `SoftAssertions` for assertions
4. Inject `TestCluster` and `HttpClient` as needed
5. Document test purpose in class javadoc
6. Follow existing naming conventions

## License

See LICENSE file for details.
