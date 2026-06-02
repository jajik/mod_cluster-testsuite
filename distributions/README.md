# WildFly/EAP Distributions

Place your WildFly or EAP ZIP distributions in this directory.

**Important**: The same ZIP is used for both:
- **Workers** (WildFly/EAP instances handling requests)
- **Undertow balancer** (WildFly/EAP configured as mod_cluster load balancer)

The difference is in the configuration and startup parameters, not the distribution itself.

## Supported Distributions

### WildFly
Download from: https://www.wildfly.org/downloads/

Place ZIP files like:
- `wildfly-31.0.1.Final.zip` (requires Java 17)
- `wildfly-30.0.1.Final.zip` (requires Java 17)

### JBoss EAP (Red Hat)
Download from Red Hat Customer Portal: https://access.redhat.com/

Place ZIP files like:
- `jboss-eap-8.0.0.zip` (requires Java 17)

Note: EAP 7.x is not supported — the test deployments use Jakarta EE 10 APIs.

## Usage

The test framework will automatically detect ZIP files in this directory:

1. **Automatic detection** (recommended):
   ```bash
   # Just place the ZIP here, tests will find it automatically
   cp ~/Downloads/wildfly-31.0.1.Final.zip distributions/
   mvn test
   ```

2. **Explicit path via system property**:
   ```bash
   mvn test -Dwildfly.zip.path=/path/to/wildfly-31.0.1.Final.zip
   ```

3. **Environment variable**:
   ```bash
   export WILDFLY_ZIP_PATH=/path/to/wildfly-31.0.1.Final.zip
   mvn test
   ```

## Fallback

If no ZIP is provided, the tests will attempt to pull pre-built container images (e.g. `quay.io/wildfly/wildfly:31.0.1.Final`). These image references are **placeholders that may not exist** — always provide a ZIP for reliable operation.

## Multiple Versions

If multiple ZIP files are present, the test framework will use the first one found.
To test with specific versions, either:
- Keep only one ZIP in this directory
- Use `-Dwildfly.zip.path=` to specify explicitly

## httpd Distributions (Native Mode)

For native httpd testing, you can either use a JBCS ZIP or the system httpd.

### JBCS ZIP
Place the httpd ZIP and optionally the connectors ZIP here:
- `jbcs-httpd24-httpd-*.zip` — JBCS httpd distribution
- `jbcs-httpd24-webserver-connectors-*.zip` — mod_proxy_cluster modules (auto-detected alongside httpd ZIP)

```bash
mvn test -Pnative -Dbalancer.type=httpd \
    -Dhttpd.zip.path=distributions/jbcs-httpd24-httpd-2.4.62-RHEL8-x86_64.zip
```

Or set explicitly: `-Dhttpd.connectors.zip.path=distributions/jbcs-httpd24-webserver-connectors-*.zip`

### System httpd
No ZIP needed — use `-Dhttpd.home=/usr` with mod_proxy_cluster modules built from source.
See the main [README.md](../README.md#system-httpd-no-zip-required) for build instructions.

## .gitignore

ZIP files in this directory are ignored by git (they're typically large).
Each developer/CI system should provide their own distributions.
