package org.jboss.modcluster.test.apps;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import java.io.File;
import java.net.URL;

/**
 * Builder for creating the WebSocket echo WAR application at runtime using ShrinkWrap.
 * Packages EchoWebSocketEndpoint, web.xml, and index.html into ws-echo.war.
 */
public class WebSocketAppBuilder {

    /**
     * Creates the ws-echo.war file with the WebSocket endpoint and web resources.
     *
     * @return File reference to generated WAR in temp directory
     */
    public static File createWebSocketApp() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL webXml = cl.getResource("apps/ws-echo/web.xml");
        URL indexHtml = cl.getResource("apps/ws-echo/index.html");

        final WebArchive war = ShrinkWrap.create(WebArchive.class, "ws-echo.war")
                .addClass(EchoWebSocketEndpoint.class)
                .setWebXML(webXml)
                .addAsWebResource(indexHtml, "index.html");

        final File tempWar = new File(System.getProperty("java.io.tmpdir"), "ws-echo.war");
        war.as(ZipExporter.class).exportTo(tempWar, true);

        return tempWar;
    }
}
