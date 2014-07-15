package org.gbif.checklistbank;

import java.io.File;

/**
 *
 */
public class NeoUtils {
    public static File neoDir(String name) {
        return new File("/Users/markus/neodbs/" + name);
    }

}
