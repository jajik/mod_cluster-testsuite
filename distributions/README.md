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
- `wildfly-31.0.1.Final.zip`
- `wildfly-30.0.1.Final.zip`

### JBoss EAP (Red Hat)
Download from Red Hat Customer Portal: https://access.redhat.com/

Place ZIP files like:
- `jboss-eap-8.0.0.zip`
- `jboss-eap-7.4.0.zip`

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

If no ZIP is provided, the tests will fall back to using pre-built container images from:
- `quay.io/wildfly/wildfly:31.0.1.Final`

## Multiple Versions

If multiple ZIP files are present, the test framework will use the first one found.
To test with specific versions, either:
- Keep only one ZIP in this directory
- Use `-Dwildfly.zip.path=` to specify explicitly

## .gitignore

ZIP files in this directory are ignored by git (they're typically large).
Each developer/CI system should provide their own distributions.
