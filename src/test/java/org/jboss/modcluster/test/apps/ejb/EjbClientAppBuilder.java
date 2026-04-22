package org.jboss.modcluster.test.apps.ejb;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;

import java.io.File;

/**
 * Builder for creating the EJB client JAR at runtime using ShrinkWrap.
 * Packages {@link EjbClient}, {@link EjbJvmRouteInterface}, and an embedded
 * {@code wildfly-config.xml} (Elytron DIGEST-MD5 authentication) into {@code ejb-client.jar}.
 *
 * <p>The client JAR is designed to run inside a WildFly container with
 * {@code jboss-client.jar} providing the naming factory and EJB client infrastructure.
 */
public final class EjbClientAppBuilder {

    private EjbClientAppBuilder() {
    }

    /**
     * Creates the {@code ejb-client.jar} with embedded Elytron authentication configuration.
     *
     * @param user     the application user name for DIGEST-MD5 authentication
     * @param password the application user password
     * @return File reference to the generated JAR in the temp directory
     */
    public static File createClientApp(final String user, final String password) {
        final String wildflyConfig = generateWildflyConfig(user, password);

        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "ejb-client.jar")
                .addClass(EjbClient.class)
                .addClass(EjbJvmRouteInterface.class)
                .addAsResource(new StringAsset(wildflyConfig), "wildfly-config.xml");

        final File tempJar = new File(System.getProperty("java.io.tmpdir"), "ejb-client.jar");
        archive.as(ZipExporter.class).exportTo(tempJar, true);
        tempJar.deleteOnExit();

        return tempJar;
    }

    private static String generateWildflyConfig(final String user, final String password) {
        return "<configuration>\n"
                + "    <authentication-client xmlns=\"urn:elytron:1.0\">\n"
                + "        <authentication-rules>\n"
                + "            <rule use-configuration=\"default\"/>\n"
                + "        </authentication-rules>\n"
                + "        <authentication-configurations>\n"
                + "            <configuration name=\"default\">\n"
                + "                <sasl-mechanism-selector selector=\"DIGEST-MD5 -JBOSS-LOCAL-USER\"/>\n"
                + "                <set-user-name name=\"" + user + "\"/>\n"
                + "                <credentials>\n"
                + "                    <clear-password password=\"" + password + "\"/>\n"
                + "                </credentials>\n"
                + "                <providers>\n"
                + "                    <use-service-loader/>\n"
                + "                </providers>\n"
                + "            </configuration>\n"
                + "        </authentication-configurations>\n"
                + "    </authentication-client>\n"
                + "    <http-client xmlns=\"urn:wildfly-http-client:1.0\">\n"
                + "        <defaults>\n"
                + "            <eagerly-acquire-session value=\"true\"/>\n"
                + "            <enable-http2 value=\"false\"/>\n"
                + "        </defaults>\n"
                + "    </http-client>\n"
                + "</configuration>\n";
    }
}
