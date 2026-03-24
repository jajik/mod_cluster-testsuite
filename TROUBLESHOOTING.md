# Troubleshooting Guide

## Common Issues and Solutions

### Container Fails to Start

#### Error: UnsupportedClassVersionError

```
java.lang.UnsupportedClassVersionError: ... has been compiled by a more recent version of the Java Runtime
(class file version 61.0), this version of the Java Runtime only recognizes class file versions up to 55.0
```

**Cause**: Java version mismatch. Your WildFly/EAP version requires a newer Java.

**Solution**: The framework should auto-detect this, but if you see this error:

1. Check your ZIP file name matches expected format:
   - ✅ `wildfly-31.0.1.Final.zip`
   - ✅ `jboss-eap-8.0.0.zip`
   - ❌ `wildfly.zip` (missing version)
   - ❌ `my-custom-wildfly-build.zip` (non-standard name)

2. If using custom ZIP names, set Java version manually in the Dockerfile

**Java Version Requirements**:
- WildFly 27+: Java 17
- EAP 8+: Java 17

#### Error: Wait strategy failed. Container exited with code 1

**Cause**: Container failed to start, check logs for actual error.

**Solution**:
```bash
# Find the container
docker ps -a | grep wildfly

# Check logs
docker logs <container-id>

# Common issues in logs:
# - Java version mismatch (see above)
# - Port conflicts
# - Memory issues
# - Configuration errors
```

#### Error: Timed out waiting for log output matching '.*WFLYSRV0025.*'

**Cause**: Container started but WildFly didn't finish booting in time.

**Solutions**:

1. **Increase timeout** (if your system is slow):
   ```java
   // In WildFlyContainer.java or BalancerContainer.java
   .withStartupTimeout(Duration.ofMinutes(10))  // Default is 5
   ```

2. **Check Docker resources**:
   ```bash
   docker stats  # Ensure enough memory
   ```

3. **Check for actual errors**:
   ```bash
   docker logs <container-id> | grep -i error
   ```

### Build Issues

#### Error: SIGPIPE / Pipe closed when transferring large ZIP

```
java.io.IOException: Broken pipe (SIGPIPE)
Error when copying TAR file entry: wildfly-39.0.1.Final.zip
java.io.IOException: Pipe closed
```

**Cause**: Docker daemon closes connection when Testcontainers tries to transfer very large ZIP files (300MB+).

**Solution**: ✅ **Already implemented!**

The framework now uses direct `docker build` instead of Testcontainers' file transfer:

1. First run: Builds image using `docker build` directly
   ```
   INFO - Building WildFly image from ZIP: wildfly-39.0.1.Final.zip
   Running: docker build -t modcluster-test/wildfly-39-0-1-final:openjdk-17
   ```

2. Subsequent runs: Reuses the built image
   ```
   INFO - Using existing image: modcluster-test/wildfly-39-0-1-final:openjdk-17
   ```

**Manual cleanup** (if needed):
```bash
# List built images
docker images | grep modcluster-test

# Remove if you want to rebuild
docker rmi modcluster-test/wildfly-39-0-1-final:openjdk-17
```

**Benefits**:
- ✅ Works with any size ZIP file
- ✅ Image is cached between test runs
- ✅ Same image used for both balancer and workers (efficient!)
- ✅ No Testcontainers transfer size limits

#### Error: No space left on device

**Cause**: Docker ran out of disk space.

**Solutions**:
```bash
# Check disk usage
docker system df

# Clean up unused images/containers
docker system prune -a

# Clean up build cache
docker builder prune -a

# On Linux, check Docker's data root
df -h /var/lib/docker
```

#### Error: Cannot connect to the Docker daemon

**Cause**: Docker not running or permission issues.

**Solutions**:

1. **Start Docker**:
   ```bash
   # Linux
   sudo systemctl start docker

   # Mac/Windows
   # Start Docker Desktop
   ```

2. **Fix permissions** (Linux):
   ```bash
   sudo usermod -aG docker $USER
   newgrp docker
   # Or logout and login again
   ```

3. **Check Docker socket**:
   ```bash
   ls -la /var/run/docker.sock
   # Should be writable by your user or docker group
   ```

### Test Execution Issues

#### Error: Connection refused when accessing balancer

**Cause**: Balancer not fully started or network issues.

**Solutions**:

1. **Check balancer logs**:
   ```java
   // In your test, add:
   String logs = cluster.getBalancer().getContainer().getLogs();
   System.out.println(logs);
   ```

2. **Verify network**:
   ```bash
   docker network ls
   docker network inspect <network-id>
   ```

3. **Check port mappings**:
   ```bash
   docker ps
   # Verify ports are mapped correctly
   ```

#### Tests are flaky/intermittent failures

**Possible causes**:

1. **Container reuse enabled in CI**:
   ```bash
   # Disable for CI
   mvn test -Dtestcontainers.reuse.enable=false
   ```

2. **Timing issues**:
   ```java
   // Use Awaitility for async operations
   await().atMost(30, SECONDS)
          .pollInterval(2, SECONDS)
          .untilAsserted(() -> {
              // your assertion
          });
   ```

3. **Resource constraints**:
   ```bash
   # Increase Docker memory to 6GB+
   # Reduce parallel test execution
   mvn test -DforkCount=1
   ```

#### Soft assertions not showing all failures

**Cause**: Missing `assertAll()` call.

**Solution**: The test extension handles this automatically, but verify:
```java
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class MyTest {
    @InjectSoftAssertions  // ← Make sure this is present
    private SoftAssertions softly;

    // softly.assertAll() is called automatically in @AfterEach
}
```

### Performance Issues

#### Tests taking > 5 minutes

**Expected behavior**:
- First run: 3-5 minutes (building images)
- Subsequent runs: 1-2 minutes (cached)

**If slower**:

1. **Check Docker resources**:
   ```bash
   docker stats
   # CPU should be < 80%
   # Memory should have 2GB+ free
   ```

2. **Check disk I/O**:
   ```bash
   iostat -x 2
   # Look for high await times
   ```

3. **Enable container reuse** (dev only):
   ```properties
   # src/test/resources/testcontainers.properties
   testcontainers.reuse.enable=true
   ```

See [PERFORMANCE.md](PERFORMANCE.md) for detailed optimization guide.

### ZIP/Distribution Issues

#### Error: No ZIP distribution found

**Symptoms**:
```
INFO - No ZIP provided, using pre-built container image
```

**Solutions**:

1. **Check file location**:
   ```bash
   ls -la distributions/*.zip
   ```

2. **Verify file name format**:
   ```bash
   # Must start with "wildfly-" or "jboss-eap-"
   mv my-wildfly.zip wildfly-31.0.1.Final.zip
   ```

3. **Use explicit path**:
   ```bash
   mvn test -Dwildfly.zip.path=/full/path/to/wildfly-31.0.1.Final.zip
   ```

#### Error: ZIP file corrupted

**Symptoms**:
```
Error extracting ZIP
unzip: invalid zip file
```

**Solutions**:

1. **Verify ZIP integrity**:
   ```bash
   unzip -t distributions/wildfly-31.0.1.Final.zip
   ```

2. **Re-download if corrupted**:
   ```bash
   rm distributions/wildfly-31.0.1.Final.zip
   # Download again from https://wildfly.org
   ```

3. **Check file permissions**:
   ```bash
   chmod 644 distributions/wildfly-31.0.1.Final.zip
   ```

### Network Issues

#### Error: Workers can't connect to balancer

**Symptoms**:
```
No route to host
Connection refused to balancer:8090
```

**Solutions**:

1. **Check network creation**:
   ```bash
   docker network ls
   # Should see testcontainers network
   ```

2. **Verify network aliases**:
   ```bash
   docker network inspect <network-id>
   # Should show "balancer" alias
   ```

3. **Check firewall** (Linux):
   ```bash
   sudo firewall-cmd --list-all
   # Docker should be in trusted zone
   ```

#### Error: Cannot access balancer from host

**Cause**: Using internal URL instead of mapped port.

**Solution**:
```java
// ❌ Wrong - internal URL
String url = "http://balancer:8080";

// ✅ Correct - mapped port
String url = cluster.getBalancer().getHttpUrl();
```

### Podman-Specific Issues

#### Error: Podman socket not found

**Solution**:
```bash
# Enable Podman socket
systemctl --user enable --now podman.socket

# Set environment
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock

# Verify
curl -H "Content-Type: application/json" \
     --unix-socket /run/user/$(id -u)/podman/podman.sock \
     http://localhost/_ping
```

#### Testcontainers doesn't recognize Podman

**Solution**:
```bash
# Create Docker socket symlink
sudo ln -s /run/user/$(id -u)/podman/podman.sock /var/run/docker.sock

# Or set DOCKER_HOST
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true  # If ryuk fails with Podman
```

### CI/CD Issues

#### Jenkins: Tests fail but work locally

**Common causes**:

1. **Container reuse** (should be disabled in CI):
   ```groovy
   // In Jenkinsfile
   sh 'mvn test -Dtestcontainers.reuse.enable=false'
   ```

2. **Docker socket permissions**:
   ```groovy
   // Add Jenkins user to docker group
   sh 'sudo usermod -aG docker jenkins'
   ```

3. **Resource limits**:
   ```groovy
   // Increase memory in Jenkinsfile
   options {
       timeout(time: 2, HOURS)
   }
   ```

#### GitHub Actions: Cannot connect to Docker

**Solution**:
```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - name: Start Docker
        run: |
          sudo systemctl start docker
          sudo chmod 666 /var/run/docker.sock

      - name: Run tests
        run: mvn test
```

## Debug Checklist

When troubleshooting, check in this order:

1. ✅ **Docker running?**
   ```bash
   docker ps
   ```

2. ✅ **Enough resources?**
   ```bash
   docker stats
   free -h
   ```

3. ✅ **ZIP file present and valid?**
   ```bash
   ls -lh distributions/
   unzip -t distributions/*.zip
   ```

4. ✅ **Container logs?**
   ```bash
   docker ps -a
   docker logs <container-id>
   ```

5. ✅ **Java version correct?**
   ```bash
   # Check logs for "requires openjdk-XX"
   # Should match your WildFly/EAP version
   ```

6. ✅ **Network connectivity?**
   ```bash
   docker network inspect <network-id>
   ```

7. ✅ **Test logs?**
   ```bash
   cat target/surefire-reports/*.txt
   ```

## Getting More Help

### Enable Debug Logging

```bash
# Maven debug mode
mvn test -X

# More verbose test output
mvn test -Dsurefire.printSummary=true
```

### Capture Container Logs

```java
// In your test
@Test
public void debug(TestCluster cluster) {
    cluster.startWorkers(1);

    // Get container logs
    String balancerLogs = cluster.getBalancer().getContainer().getLogs();
    String workerLogs = cluster.getWorker1().getContainer().getLogs();

    System.out.println("=== Balancer Logs ===");
    System.out.println(balancerLogs);
    System.out.println("=== Worker Logs ===");
    System.out.println(workerLogs);
}
```

### Common Log Patterns to Search For

```bash
# In container logs
grep -i "error" logs.txt
grep -i "exception" logs.txt
grep -i "failed" logs.txt
grep -i "WFLYSRV0025" logs.txt  # Server started message
grep -i "java.lang" logs.txt    # Java errors

# In test output
grep "Failures:" target/surefire-reports/*.txt
grep "Errors:" target/surefire-reports/*.txt
```

## Still Stuck?

If none of the above helps:

1. Create a minimal reproducible test case
2. Capture full logs (Maven -X, Docker logs)
3. Note your environment (OS, Docker version, Java version)
4. Check if issue occurs with pre-built images (no ZIP)
5. Try with a different WildFly/EAP version

Example bug report template:
```markdown
**Environment**:
- OS: Fedora 39
- Docker: 24.0.5
- Java: OpenJDK 17
- Maven: 3.9.1

**WildFly Version**:
- wildfly-31.0.1.Final.zip (auto-detected Java 17)

**Steps to Reproduce**:
1. Place ZIP in distributions/
2. Run: mvn test -Dtest=MyTest

**Expected**: Test passes
**Actual**: Container exits with code 1

**Logs**: [attach docker logs and test output]
```
