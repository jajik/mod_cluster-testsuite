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
 * Builder for creating the sleep WAR application at runtime using ShrinkWrap.
 * The sleep app contains a JSP that sleeps for a configurable duration,
 * used to test node-timeout behavior.
 */
public class SleepAppBuilder {

    /**
     * Creates a sleep WAR file with a JSP that sleeps for the specified duration.
     *
     * @param sleepMs sleep duration in milliseconds
     * @return File reference to generated WAR in temp directory
     */
    public static File createSleepApp(int sleepMs) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL webXml = cl.getResource("apps/sleep/web.xml");
        URL indexJsp = cl.getResource("apps/sleep/index.jsp");

        String sleepJspTemplate;
        try (InputStream is = cl.getResourceAsStream("apps/sleep/sleep.jsp")) {
            sleepJspTemplate = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read apps/sleep/sleep.jsp", e);
        }
        String sleepJsp = sleepJspTemplate.replace("%SLEEP_MS%", String.valueOf(sleepMs));

        final WebArchive war = ShrinkWrap.create(WebArchive.class, "sleep.war")
                .addAsWebResource(new StringAsset(sleepJsp), "sleep.jsp")
                .addAsWebResource(indexJsp, "index.jsp")
                .setWebXML(webXml);

        final File tempWar = new File(System.getProperty("java.io.tmpdir"), "sleep-" + sleepMs + "ms.war");
        war.as(ZipExporter.class).exportTo(tempWar, true);
        tempWar.deleteOnExit();

        return tempWar;
    }
}
