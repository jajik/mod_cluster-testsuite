package org.jboss.modcluster.test.apps;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import java.io.File;
import java.net.URL;

/**
 * Builder for creating the exit WAR application at runtime using ShrinkWrap.
 * The exit app contains a JSP that immediately halts the JVM via {@code Runtime.getRuntime().halt(1)},
 * used to simulate cascading worker failures in failover tests.
 */
public class ExitAppBuilder {

    /**
     * Creates the exit.war file with exit.jsp, index.jsp, and web.xml.
     *
     * @return File reference to generated WAR in temp directory
     */
    public static File createExitApp() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL webXml = cl.getResource("apps/exit/web.xml");
        URL indexJsp = cl.getResource("apps/exit/index.jsp");
        URL exitJsp = cl.getResource("apps/exit/exit.jsp");

        final WebArchive war = ShrinkWrap.create(WebArchive.class, "exit.war")
                .addAsWebResource(exitJsp, "exit.jsp")
                .addAsWebResource(indexJsp, "index.jsp")
                .setWebXML(webXml);

        final File tempWar = new File(System.getProperty("java.io.tmpdir"), "exit.war");
        war.as(ZipExporter.class).exportTo(tempWar, true);
        tempWar.deleteOnExit();

        return tempWar;
    }
}
