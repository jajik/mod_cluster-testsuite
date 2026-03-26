# Custom Load Metric Implementation

## Overview

This module provides a file-based custom load metric implementation for mod_cluster testing.

**Class:** `org.jboss.modcluster.test.metric.FileBasedLoadMetric`

**Purpose:** Allows external control of reported load values for testing load-based routing scenarios.

## How It Works

The custom load metric reads load values from a file on disk, allowing you to artificially set worker load for testing purposes.

### Load File Format

The load file should contain a line matching the pattern:
```
LOAD: <number>
```

**Example:**
```
LOAD: 75
```
This reports a load of 75 (out of capacity 1000, normalized to 0.075).

### Configuration

The metric supports these configurable properties:
- **loadFile**: Path to file containing load data (default: `/tmp/modcluster-load.txt`)
- **parseExpression**: Regex pattern to extract load value (default: `^LOAD: ([0-9]+)$`)
- **capacity**: Maximum load value for normalization (default: `1000`)
- **weight**: Weight of this metric in load calculation (default: `1`)

## Deployment to WildFly

### Step 1: Build the JAR

```bash
cd src/test/resources/custom-load-metric
mvn clean package
```

This creates `target/custom-load-metric.jar`.

### Step 2: Deploy as WildFly Module

1. Create module directory structure:
   ```bash
   mkdir -p $WILDFLY_HOME/modules/org/jboss/modcluster/test/metric/main
   ```

2. Copy files:
   ```bash
   cp target/custom-load-metric.jar $WILDFLY_HOME/modules/org/jboss/modcluster/test/metric/main/
   cp module.xml $WILDFLY_HOME/modules/org/jboss/modcluster/test/metric/main/
   ```

### Step 3: Configure mod_cluster

Add custom load metric to mod_cluster configuration in `standalone-ha.xml`:

```xml
<subsystem xmlns="urn:jboss:domain:modcluster:9.0">
    <proxy name="default" ... >
        <dynamic-load-provider>
            <custom-load-metric class="org.jboss.modcluster.test.metric.FileBasedLoadMetric"
                                module="org.jboss.modcluster.test.metric"
                                weight="1"
                                capacity="1000">
                <property name="loadFile" value="/tmp/modcluster-load.txt"/>
                <property name="parseExpression" value="^LOAD: ([0-9]+)$"/>
            </custom-load-metric>
        </dynamic-load-provider>
    </proxy>
</subsystem>
```

## Usage in Tests

### Set Custom Load

```bash
# Set worker load to 500 (50% of capacity)
echo "LOAD: 500" > /tmp/modcluster-load.txt

# Set worker load to 900 (90% of capacity - highly loaded)
echo "LOAD: 900" > /tmp/modcluster-load.txt

# Set worker load to 100 (10% of capacity - lightly loaded)
echo "LOAD: 100" > /tmp/modcluster-load.txt
```

### Test Load-Based Routing

```java
// Set worker1 to high load
writeLoadFile(worker1, 900);

// Set worker2 to low load
writeLoadFile(worker2, 100);

// Verify traffic routes primarily to worker2 (less loaded)
Map<String, Integer> distribution = httpClient.testLoadDistribution(url, 100);

// worker2 should receive more traffic due to lower load
assertThat(distribution.get("worker2")).isGreaterThan(distribution.get("worker1"));
```

## Testcontainers Integration

To use with Testcontainers, you would need to:

1. **Copy module files** to container:
   ```java
   container.copyFileToContainer(
       MountableFile.forHostPath("src/test/resources/custom-load-metric/target/custom-load-metric.jar"),
       "/opt/wildfly/modules/org/jboss/modcluster/test/metric/main/custom-load-metric.jar"
   );

   container.copyFileToContainer(
       MountableFile.forHostPath("src/test/resources/custom-load-metric/module.xml"),
       "/opt/wildfly/modules/org/jboss/modcluster/test/metric/main/module.xml"
   );
   ```

2. **Configure mod_cluster** via Creaper to add the custom metric

3. **Write load values** to the container:
   ```java
   container.execInContainer("sh", "-c", "echo 'LOAD: 500' > /tmp/modcluster-load.txt");
   ```

## Implementation Status

- ✅ Custom LoadMetric class implemented
- ✅ JAR successfully builds
- ✅ WildFly module.xml created
- ⚠️  Testcontainers deployment integration (requires additional work)
- ⚠️  Dynamic configuration via Creaper (requires custom metric setup methods)

## Alternative: Use Built-in Load Metrics

For most testing scenarios, WildFly's built-in dynamic load metrics may be sufficient:
- **cpu**: CPU usage
- **mem**: Memory usage
- **heap**: JVM heap usage
- **sessions**: Active sessions
- **requests**: Request count
- **send-traffic**: Bytes sent
- **receive-traffic**: Bytes received
- **busyness**: Thread pool busyness
- **connection-pool**: Database connection pool usage

These can be configured without custom code:
```xml
<dynamic-load-provider>
    <load-metric type="cpu" weight="2"/>
    <load-metric type="mem" weight="1"/>
    <load-metric type="sessions" capacity="1000" weight="1"/>
</dynamic-load-provider>
```

## References

- mod_cluster documentation: https://www.modcluster.io/
- Load Metric API: `org.jboss.modcluster.load.metric.LoadMetric`
- WildFly module system: https://docs.wildfly.org/

---

Generated: 2026-02-17
Status: JAR built, ready for manual deployment testing
