# Configuration Reference

Complete reference for all configuration options.

## System Properties

All configuration is done via Maven system properties (`-D` flags).

### WildFly/EAP Distribution

| Property | Description | Default | Example |
|----------|-------------|---------|---------|
| `wildfly.zip.path` | Path to WildFly/EAP ZIP | Auto-detect in `distributions/` | `-Dwildfly.zip.path=/opt/wildfly-31.0.1.Final.zip` |
| `wildfly.version` | Version for pre-built images (fallback) | `31.0.1.Final` | `-Dwildfly.version=30.0.1.Final` |

### httpd Distribution

| Property | Description | Default | Example |
|----------|-------------|---------|---------|
| `httpd.zip.path` | Path to pre-built httpd ZIP (e.g. JBCS) | Build from source | `-Dhttpd.zip.path=/opt/jbcs-httpd24-2.4.57-RHEL9-x86_64.zip` |
| `httpd.version` | httpd version for building from source | `2.4.66` | `-Dhttpd.version=2.4.62` |
| `mod.proxy.cluster.repo.url` | Git repo for mod_proxy_cluster sources | `https://github.com/modcluster/mod_proxy_cluster.git` | `-Dmod.proxy.cluster.repo.url=https://github.com/myfork/mod_proxy_cluster.git` |

### Container Configuration

| Property | Description | Default | Example |
|----------|-------------|---------|---------|
| `container.base.image` | Full base image for WildFly containers | `registry.access.redhat.com/ubi9/openjdk-17:latest` | `-Dcontainer.base.image=registry.access.redhat.com/ubi10/openjdk-17:latest` |
| `container.java.home` | Path to host JDK to inject into container image (for base images without Java) | _(not set)_ | `-Dcontainer.java.home=/usr/lib/jvm/java-17-openjdk` |
| `wildfly.java.opts` | JVM options for WildFly workers | `-Xms64m -Xmx512m` | `-Dwildfly.java.opts="-Xms128m -Xmx1g"` |

**JVM options priority** (highest to lowest):
1. Per-instance `WildFlyContainer.withJavaOpts()` — used by tests that need specific heap (e.g., heap load metric test uses `-Xmx2g`)
2. System property `wildfly.java.opts` — for CI-wide tuning
3. Default in `pom.xml`: `-Xms64m -Xmx512m`

### Balancer Configuration

| Property | Description | Default | Example |
|----------|-------------|---------|---------|
| `balancer.type` | Balancer type | `undertow` | `-Dbalancer.type=httpd` |
| `balancer.undertow.image` | Custom Undertow image | `quay.io/modcluster/mod_cluster-undertow:latest` (placeholder, does not exist) | `-Dbalancer.undertow.image=my-registry.com/undertow:1.0` |
| `balancer.httpd.image` | Custom httpd image | `quay.io/modcluster/mod_cluster-httpd:latest` (placeholder, does not exist) | `-Dbalancer.httpd.image=my-registry.com/httpd:2.4` |

### Test Execution

| Property | Description | Default | Example |
|----------|-------------|---------|---------|
| `testcontainers.reuse.enable` | Reuse containers between runs | `false` | `-Dtestcontainers.reuse.enable=true` |
| `test` | Specific test to run | All tests | `-Dtest=StickySessionTest` |

## Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `WILDFLY_ZIP_PATH` | Path to WildFly/EAP ZIP | `export WILDFLY_ZIP_PATH=/opt/wildfly.zip` |
| `HTTPD_ZIP_PATH` | Path to httpd ZIP (fallback for `httpd.zip.path`) | `export HTTPD_ZIP_PATH=/opt/jbcs.zip` |
| `DOCKER_HOST` | Docker/Podman socket | `export DOCKER_HOST=unix:///run/user/1000/podman/podman.sock` |

## Maven Profiles

Activate profiles with `-P<profile>`.

| Profile | Purpose | Properties Set |
|---------|---------|----------------|
| `undertow` | Use Undertow balancer (default) | `balancer.type=undertow` |
| `httpd` | Use httpd balancer | `balancer.type=httpd` |
| `ci` | CI/CD mode | `testcontainers.reuse.enable=false` |

## Configuration Files

### testcontainers.properties

Location: `src/test/resources/testcontainers.properties`

```properties
# Container reuse (dev only, disable for CI)
testcontainers.reuse.enable=false

# Ryuk timeout (cleanup helper)
ryuk.container.timeout=30
```

### logback-test.xml

Location: `src/test/resources/logback-test.xml`

Controls logging levels:

```xml
<!-- Test framework logging -->
<logger name="org.jboss.modcluster.test" level="INFO"/>

<!-- Testcontainers logging -->
<logger name="org.testcontainers" level="INFO"/>

<!-- HTTP client logging -->
<logger name="okhttp3" level="WARN"/>
```

## Common Configuration Scenarios

### Scenario 1: Local Development with Custom EAP

```bash
# Place your EAP ZIP
cp ~/Downloads/jboss-eap-8.0.0-custom.zip distributions/

# Run tests (uses default base image from pom.xml)
mvn test

# Or override base image if needed
mvn test -Dcontainer.base.image=registry.access.redhat.com/ubi9/openjdk-17:latest
```

### Scenario 2: Test Specific WildFly Version

```bash
# Download specific version
wget https://github.com/wildfly/wildfly/releases/download/30.0.1.Final/wildfly-30.0.1.Final.zip -P distributions/

# Run tests
mvn test -Dwildfly.zip.path=distributions/wildfly-30.0.1.Final.zip
```

### Scenario 3: CI/CD Pipeline

```bash
# Jenkins/GitHub Actions
mvn test \
  -Pci \
  -Dbalancer.type=undertow \
  -Dwildfly.zip.path=/opt/artifacts/wildfly-31.0.1.Final.zip \
  -Dtestcontainers.reuse.enable=false

# On memory-constrained CI nodes, reduce worker heap further
mvn test -Pci -Dwildfly.java.opts="-Xms32m -Xmx256m"

# On beefy CI nodes, give workers more room
mvn test -Pci -Dwildfly.java.opts="-Xms256m -Xmx1g"
```

### Scenario 4: Quick Iteration (Development)

```bash
# Enable container reuse
echo "testcontainers.reuse.enable=true" >> src/test/resources/testcontainers.properties

# First run: slow (builds containers)
mvn test -Dtest=StickySessionTest

# Subsequent runs: fast (reuses containers)
mvn test -Dtest=LoadBalancingGroupFailoverTest
mvn test -Dtest=SSLTest

# Cleanup when done
docker stop $(docker ps -aq)
```

### Scenario 5: Test Both Balancers

```bash
# Sequential
mvn test -Pundertow && mvn test -Phttpd

# Or use the Jenkins matrix approach
```

### Scenario 6: Custom Builds / Non-Standard ZIPs

```bash
# Your ZIP doesn't match naming convention
mvn test \
  -Dwildfly.zip.path=/path/to/my-custom-build.zip

# Or rename it to follow convention
cp /path/to/my-custom-build.zip distributions/wildfly-31.0.0.Custom.zip
mvn test
```

### Scenario 7: Test on Different UBI Version

```bash
# Use UBI 10 instead of the default UBI 9
mvn test -Dcontainer.base.image=registry.access.redhat.com/ubi10/openjdk-17:latest

# Use a completely custom base image
mvn test -Dcontainer.base.image=my-registry.com/custom-jdk17:1.0
```

### Scenario 7b: Test on UBI 10 (no OpenJDK image available)

When the base image does not include Java (e.g., UBI 10 `ubi-minimal`), inject the
host machine's JDK into the container image at build time:

```bash
mvn test -Dtest=StickySessionTest \
  -Dcontainer.base.image=registry.access.redhat.com/ubi10/ubi-minimal:latest \
  -Dcontainer.java.home=/usr/lib/jvm/java-17-openjdk
```

The host JDK is copied into the image during `docker build` and `JAVA_HOME` is set
automatically at container runtime. The image is cached with a `-hostjdk` tag suffix
so it does not collide with images built from a base that already includes Java.

### Scenario 8: Testing with Podman

```bash
# Setup Podman socket
systemctl --user enable --now podman.socket
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock

# Run tests
mvn test
```

### Scenario 9: Debugging Failures

```bash
# Enable debug logging
mvn test -X -Dtest=FailingTest

# Run with more verbose output
mvn test -Dsurefire.printSummary=true

# Check specific test class
mvn test -Dtest=StickySessionTest#testStickySessionsMaintainedAcrossRequests
```

## Configuration Priority

When multiple configuration sources exist, priority is:

1. **System properties** (`-D` flags) - highest priority
2. **Environment variables**
3. **Convention** (`distributions/` directory)
4. **Default values** - lowest priority

Example:
```bash
# All of these are checked in order
-Dwildfly.zip.path=/explicit/path.zip           # 1. System property (wins)
export WILDFLY_ZIP_PATH=/env/path.zip           # 2. Environment variable
distributions/wildfly-31.0.1.Final.zip          # 3. Convention
quay.io/wildfly/wildfly:31.0.1.Final           # 4. Default fallback (placeholder, may not exist)
```

## Validation

### Check Current Configuration

```bash
# Show active properties
mvn help:effective-pom | grep -A 5 systemPropertyVariables

# Show active profiles
mvn help:active-profiles
```

### Verify ZIP is Detected

```bash
# List ZIPs
ls -lh distributions/

# Test will log:
# "Building WildFly image from ZIP: wildfly-31.0.1.Final.zip"
# "WildFly 31 requires openjdk-17"
```

### Verify Base Image

The base image is set via `container.base.image` in `pom.xml` (default: `registry.access.redhat.com/ubi9/openjdk-17:latest`).
Override with `-Dcontainer.base.image=...` for a different UBI or Java version.

## Performance Tuning

### Container Reuse

**Development**:
```properties
# src/test/resources/testcontainers.properties
testcontainers.reuse.enable=true
```

**CI/CD**:
```bash
mvn test -Dtestcontainers.reuse.enable=false
```

### Parallel Execution

```bash
# Run 2 test classes in parallel
mvn test -DforkCount=2

# Use 2 forks per CPU core
mvn test -DforkCount=2C
```

### Worker JVM Memory

Each WildFly worker defaults to `-Xms64m -Xmx512m`. The JVM starts small and grows on demand, so 4 workers use at most 2GB of heap (vs. 8GB with the old fixed 2GB setting). Override globally with `-Dwildfly.java.opts` for CI, or per-instance with `WildFlyContainer.withJavaOpts()` for individual tests that need more heap (e.g., the heap load metric test uses `-Xmx2g` to accommodate its 500MB memory allocation).

### Docker Resources

Recommended Docker settings:
- **Memory**: 4GB minimum (6GB+ for 4-worker tests)
- **CPUs**: 2+ cores
- **Disk**: 20GB free

## Troubleshooting Configuration

### Issue: ZIP Not Found

```bash
# Check what's being looked for
mvn test -X 2>&1 | grep -i "wildfly.zip"

# Verify file exists
ls -la distributions/*.zip

# Use explicit path
mvn test -Dwildfly.zip.path=$(pwd)/distributions/wildfly-31.0.1.Final.zip
```

### Issue: Wrong Java Version

```bash
# Override base image to use a different Java version
mvn test -Dcontainer.base.image=registry.access.redhat.com/ubi9/openjdk-11:latest
```

### Issue: Profile Not Active

```bash
# Check active profiles
mvn help:active-profiles

# Explicitly activate
mvn test -Phttpd

# Or via property
mvn test -Dbalancer.type=httpd
```

## Environment-Specific Configuration

### Local Development

```bash
# ~/.mavenrc or project .mvn/maven.config
-Dtestcontainers.reuse.enable=true
-Dlogback.configurationFile=src/test/resources/logback-debug.xml
```

### CI Environment

```bash
# Jenkinsfile / GitHub Actions
-Dtestcontainers.reuse.enable=false
-Dbalancer.type=${BALANCER_TYPE}
-Dwildfly.zip.path=${WORKSPACE}/artifacts/wildfly.zip
```

### Production-Like Testing

```bash
# Use httpd balancer for production scenarios
mvn test -Phttpd

# Use specific EAP version
mvn test -Dwildfly.zip.path=/certified/jboss-eap-8.0.0.zip
```

## Complete Example

```bash
# Full configuration example
mvn clean test \
  -Pci,httpd \
  -Dwildfly.zip.path=/opt/distributions/jboss-eap-8.0.0.zip \
  -Dcontainer.base.image=registry.access.redhat.com/ubi9/openjdk-17:latest \
  -Dbalancer.httpd.image=registry.redhat.io/jboss-webserver-5/jws58-httpd24-openshift-rhel8:latest \
  -Dtestcontainers.reuse.enable=false \
  -Dtest=org.jboss.modcluster.test.failover.* \
  -DforkCount=1
```

This runs:
- ✅ CI profile (no container reuse)
- ✅ httpd balancer
- ✅ JBoss EAP 8.0.0 from ZIP
- ✅ Custom base image (UBI 9 with OpenJDK 17)
- ✅ Custom httpd image
- ✅ Only failover tests
- ✅ Sequential execution (no parallel)
