package org.jboss.modcluster.test.apps;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import java.io.File;
import java.net.URL;

/**
 * Builder for creating the demo WAR application at runtime using ShrinkWrap.
 * Packages index.jsp, LoadServlet, and a distributable web.xml into demo.war.
 */
public class DemoAppBuilder {

    /**
     * Creates the demo.war file with JSP, LoadServlet, and distributable web.xml.
     *
     * @return File reference to generated WAR in temp directory
     */
    public static File createDemoApp() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL webXml = cl.getResource("apps/demo/web.xml");
        URL indexJsp = cl.getResource("apps/demo/index.jsp");

        final WebArchive war = ShrinkWrap.create(WebArchive.class, "demo.war")
                .addClass(LoadServlet.class)
                .addAsWebResource(indexJsp, "index.jsp")
                .setWebXML(webXml);

        final File tempWar = new File(System.getProperty("java.io.tmpdir"), "demo.war");
        war.as(ZipExporter.class).exportTo(tempWar, true);

        return tempWar;
    }
}
