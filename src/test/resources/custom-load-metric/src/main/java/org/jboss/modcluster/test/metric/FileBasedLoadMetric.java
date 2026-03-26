package org.jboss.modcluster.test.metric;

import org.jboss.logging.Logger;
import org.jboss.modcluster.container.Engine;
import org.jboss.modcluster.load.metric.impl.AbstractLoadMetric;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom load metric that reads load value from a file.
 * This allows external control of reported load for testing purposes.
 *
 * The load file should contain a line matching the pattern: LOAD: <number>
 * For example: "LOAD: 900" reports a load of 900.
 *
 * Following noe-tests implementation:
 * - Extends AbstractLoadMetric (handles capacity normalization internally)
 * - Returns raw load values (not pre-normalized)
 * - Uses lowercase property names matching JavaBean conventions
 */
public class FileBasedLoadMetric extends AbstractLoadMetric {

    private static final Logger log = Logger.getLogger(FileBasedLoadMetric.class);

    private String loadfile = "/tmp/modcluster-load.txt";
    private Pattern pattern;

    public FileBasedLoadMetric() {
        this.pattern = Pattern.compile("^LOAD: ([0-9]+)$");
        log.info("***** FileBasedLoadMetric Constructor called *****");
    }

    /**
     * Set the path to the file containing load information.
     * Property name: "loadfile" (lowercase to match noe-tests)
     */
    public void setLoadfile(String loadfile) {
        this.loadfile = loadfile;
        log.info("***** setLoadfile: " + loadfile + " *****");
    }

    /**
     * Set the regex pattern for parsing the load value from file.
     * Property name: "parseexpression" (lowercase to match noe-tests)
     */
    public void setParseexpression(String parseexpression) {
        try {
            this.pattern = Pattern.compile(parseexpression);
            log.info("***** setParseexpression: " + parseexpression + " *****");
        } catch (Exception e) {
            log.error("***** Invalid regex pattern: " + parseexpression + " *****", e);
        }
    }

    @Override
    public double getLoad(Engine engine) throws Exception {
        Scanner scanner = null;
        try {
            scanner = new Scanner(new FileInputStream(loadfile), "UTF-8");
            log.info("***** getLoad() called for file: " + loadfile + " *****");

            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                Matcher matcher = pattern.matcher(line);

                if (matcher.matches() && matcher.group(1) != null) {
                    String loadStr = matcher.group(1);
                    double load = Double.parseDouble(loadStr);

                    log.info("***** Parsed raw load value: " + load + " from file: " + loadfile + " *****");

                    // Return RAW load value - AbstractLoadMetric handles capacity normalization
                    return load;
                }
            }
        } catch (FileNotFoundException e) {
            log.warn("***** File not found: " + loadfile + " *****", e);
        } catch (NumberFormatException e) {
            log.error("***** Error parsing load value *****", e);
        } finally {
            if (scanner != null) {
                scanner.close();
            }
        }

        log.warn("***** No load found in file, returning 0.0 *****");
        return 0.0;
    }
}
