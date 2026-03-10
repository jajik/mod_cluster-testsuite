package org.jboss.modcluster.test.session;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import java.io.File;

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
        final WebArchive war = ShrinkWrap.create(WebArchive.class, "timeout-test.war")
            .addAsWebInfResource(new StringAsset(createWebXml(timeoutMinutes)), "web.xml")
            .addAsWebResource(new StringAsset(createIndexJsp()), "index.jsp");

        final File tempWar = new File(System.getProperty("java.io.tmpdir"), "timeout-test-" + timeoutMinutes + "min.war");
        war.as(ZipExporter.class).exportTo(tempWar, true);

        return tempWar;
    }

    /**
     * Creates web.xml content with distributable flag and custom session timeout.
     *
     * @param timeoutMinutes Session timeout in minutes
     * @return web.xml content as string
     */
    private static String createWebXml(final int timeoutMinutes) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\"\n" +
               "         version=\"3.1\">\n" +
               "    <distributable/>\n" +
               "    <session-config>\n" +
               "        <session-timeout>" + timeoutMinutes + "</session-timeout>\n" +
               "    </session-config>\n" +
               "</web-app>";
    }

    /**
     * Creates index.jsp content that displays session information.
     * JSP creates a session and stores worker name and timestamp.
     *
     * @return JSP content as string
     */
    private static String createIndexJsp() {
        return "<%@ page session=\"true\" %>\n" +
               "<%@ page import=\"java.util.*\" %>\n" +
               "<%\n" +
               "    String worker = System.getProperty(\"jboss.node.name\");\n" +
               "    session.setAttribute(\"worker\", worker);\n" +
               "    session.setAttribute(\"timestamp\", System.currentTimeMillis());\n" +
               "%>\n" +
               "<html>\n" +
               "<head><title>Timeout Test</title></head>\n" +
               "<body>\n" +
               "<p><strong>Session ID:</strong> <%= session.getId() %></p>\n" +
               "<p><strong>Worker:</strong> <%= worker %></p>\n" +
               "<p><strong>Timestamp:</strong> <%= session.getAttribute(\"timestamp\") %></p>\n" +
               "<p><strong>Max Inactive Interval:</strong> <%= session.getMaxInactiveInterval() %> seconds</p>\n" +
               "</body>\n" +
               "</html>";
    }
}
