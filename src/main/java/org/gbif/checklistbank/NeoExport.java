package org.gbif.checklistbank;

import java.io.File;
import java.io.IOException;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a neo checklist and writes it to a postgres checklistbank.
 */
public class NeoExport {
  private static final Logger LOG = LoggerFactory.getLogger(NeoExport.class);
  private GraphDatabaseService db;

  public void exportDwca(String name) {
    initNeo(name);
  }

  private void initNeo(String name) {
    File storeDir = new File("/tmp/neoclb/"+name);
    LOG.info("Create a new neo4j database in {}", storeDir.getAbsolutePath());
    GraphDatabaseFactory factory = new GraphDatabaseFactory();
    db = factory.newEmbeddedDatabaseBuilder(storeDir.getAbsolutePath())
      //.setConfig()
      .newGraphDatabase();
  }

  public static void main (String[] args) throws IOException {
    NeoExport neo = new NeoExport();
    neo.exportDwca(args[0]);
  }
}
