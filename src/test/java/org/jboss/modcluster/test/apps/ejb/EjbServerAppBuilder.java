package org.jboss.modcluster.test.apps.ejb;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;

import java.io.File;

/**
 * Builder for creating the EJB server JAR at runtime using ShrinkWrap.
 * Packages {@link StatefulJvmRoute}, {@link StatelessJvmRoute}, and
 * {@link EjbJvmRouteInterface} into {@code server.jar} for deployment to WildFly workers.
 */
public final class EjbServerAppBuilder {

    private EjbServerAppBuilder() {
    }

    /**
     * Creates the {@code server.jar} file containing all EJB bean classes.
     *
     * @return File reference to the generated JAR in the temp directory
     */
    public static File createServerApp() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "server.jar")
                .addClass(StatefulJvmRoute.class)
                .addClass(StatelessJvmRoute.class)
                .addClass(EjbJvmRouteInterface.class);

        final File tempJar = new File(System.getProperty("java.io.tmpdir"), "server.jar");
        archive.as(ZipExporter.class).exportTo(tempJar, true);
        tempJar.deleteOnExit();

        return tempJar;
    }
}
