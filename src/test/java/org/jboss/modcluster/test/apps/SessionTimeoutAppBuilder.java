package org.jboss.modcluster.test.apps;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Builder for creating test applications with custom session timeout configuration.
 * Uses ShrinkWrap to dynamically generate WAR files with specified session timeout values.
 * Used for testing session timeout preservation across failover scenarios.
 */
public class SessionTimeoutAppBuilder {

    /**
     * Creates a WAR file with specified session timeout.
     * The generated WAR contains a simple JSP that creates a distributable session.
     *
     * @param timeoutMinutes Session timeout in minutes
     * @return File reference to generated WAR in temp directory
     */
    public static File createApp(final int timeoutMinutes) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL indexJsp = cl.getResource("apps/timeout-test/index.jsp");

        String webXmlTemplate;
        try (InputStream is = cl.getResourceAsStream("apps/timeout-test/web.xml")) {
            webXmlTemplate = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read apps/timeout-test/web.xml", e);
        }
        String webXml = webXmlTemplate.replace("%SESSION_TIMEOUT%", String.valueOf(timeoutMinutes));

        final WebArchive war = ShrinkWrap.create(WebArchive.class, "timeout-test.war")
            .addAsWebInfResource(new StringAsset(webXml), "web.xml")
            .addAsWebResource(indexJsp, "index.jsp");

        final File tempWar = new File(System.getProperty("java.io.tmpdir"), "timeout-test-" + timeoutMinutes + "min.war");
        war.as(ZipExporter.class).exportTo(tempWar, true);

        return tempWar;
    }
}
