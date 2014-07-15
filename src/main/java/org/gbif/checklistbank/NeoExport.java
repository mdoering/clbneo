package org.gbif.checklistbank;

import org.gbif.dwc.terms.DwcTerm;
import org.gbif.dwc.terms.Term;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.schema.Schema;
import org.neo4j.helpers.collection.IteratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Reads a neo checklist and writes it to a postgres checklistbank.
 */
public class NeoExport {
  private static final Logger LOG = LoggerFactory.getLogger(NeoExport.class);
  private GraphDatabaseService db;
  private static final String PROP_LFT = "lft";
  private static final String PROP_RGT = "rgt";
  private int idx = 0;

  public void exportDwca(String name) {
    initNeo(name);
    initClb();
    syncClb();
    db.shutdown();
  }

  private void initClb() {
    //TODO: setup simple direct JDBC prepared statements
  }

  private void syncClb() {


  }

  private void syncNameUsage(Node n){
      // for the end event

  }
  private String prop(Node n, Term prop) {
    return (String) n.getProperty(prop.simpleName(), null);
  }


  private void initNeo(String name) {
    File storeDir = NeoUtils.neoDir(name);
    LOG.info("Connect to neo4j in {}", storeDir.getAbsolutePath());
    GraphDatabaseFactory factory = new GraphDatabaseFactory();
    db = factory.newEmbeddedDatabaseBuilder(storeDir.getAbsolutePath())
      //.setConfig()
      .newGraphDatabase();
  }

  public void cleanup(String name) {
    initNeo(name);
    try (Transaction tx = db.beginTx()) {
      LOG.info("Create index");
      Schema schema = db.schema();
      schema.indexFor(Labels.TAXON).on(DwcTerm.taxonID.simpleName()).create();
      tx.success();

      LOG.info("Search nodes");
      List<Node> nodes = IteratorUtil.asList(db.findNodesByLabelAndProperty(Labels.TAXON, DwcTerm.taxonID.simpleName(), "16842043"));
      Node n = nodes.get(1);
      n.setProperty(DwcTerm.taxonID.simpleName(), "16842043-dupl");
      tx.success();

      schema.constraintFor(Labels.TAXON).assertPropertyIsUnique(DwcTerm.taxonID.simpleName()).create();
      tx.success();
    }


    db.shutdown();
  }


  public static void main (String[] args) throws IOException {
    NeoExport neo = new NeoExport();
    neo.exportDwca(args[0]);
//    neo.cleanup(args[0]);
  }

  private void logPath(Path p) {
    StringBuilder sb = new StringBuilder();
    for (Node n : p.nodes()) {
      if (sb.length() > 0) {
        sb.append(" -- ");
      }
      sb.append((String)n.getProperty(DwcTerm.scientificName.simpleName()));
    }
    sb.append(", " + p.endNode().getProperty(DwcTerm.taxonRank.simpleName()));
    LOG.debug(sb.toString());
  }
}
