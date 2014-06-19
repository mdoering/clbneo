package org.gbif.checklistbank;

import org.gbif.dwc.record.Record;
import org.gbif.dwc.terms.DwcTerm;
import org.gbif.dwc.text.Archive;
import org.gbif.dwc.text.ArchiveFactory;
import org.gbif.dwc.text.StarRecord;

import java.io.File;
import java.io.IOException;

import com.google.common.base.Strings;
import org.apache.commons.io.FileUtils;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.schema.Schema;
import org.neo4j.helpers.collection.IteratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a good id based dwc archive and produces a neo4j graph from it.
 */
public class NeoImport {
  private static final Logger LOG = LoggerFactory.getLogger(NeoImport.class);

  private GraphDatabaseService db;
  private static final String TAXON_ID_PROP = "TAXON_ID_PROP";
  private static final String SCINAME_PROP = "scientificName";
  private boolean useCoreID = false;
  public void importDwca(File dwca) throws IOException {
    initNeo(dwca.getName());

    Archive arch = ArchiveFactory.openArchive(dwca);
    if (!arch.getCore().hasTerm(DwcTerm.taxonID)) {
      LOG.warn("Using core ID for TAXON_ID_PROP");
      useCoreID = true;
    }

    int counter = 0;
    Transaction tx = db.beginTx();
    // pass1
    for (StarRecord star : arch) {
        counter++;
        pass1(star);
        if (counter % 1000 == 0) {
          tx.success();
          LOG.debug("pass1: {}", counter);
        }
    }
    // flush all pass1 nodes
    tx.success();
    LOG.info("Pass 1 completed");

    // pass 2, relations
    counter = 0;
    for (StarRecord star : arch) {
      counter++;
      pass2(star);
      if (counter % 1000 == 0) {
        tx.success();
        LOG.debug("pass2: {}", counter);
      }
    }
    tx.success();
    LOG.info("Import completed");
  }

  private String taxonID(Record core){
    if (useCoreID) {
      return core.id();
    } else {
      return core.value(DwcTerm.taxonID);
    }
  }

  /**
   * Creates a node for each explicit record with a given TAXON_ID_PROP
   */
  private void pass1(StarRecord star) {
    Node n = db.createNode(Labels.TAXON);
    Record core = star.core();

    final String id = taxonID(core);
    final String sciname = core.value(DwcTerm.scientificName);
    n.setProperty(TAXON_ID_PROP, id);
    n.setProperty(SCINAME_PROP, sciname);
    n.setProperty("rank", core.value(DwcTerm.taxonRank));
  }

  /**
   * Creates implicit nodes and sets up relations between taxa.
   */
  private void pass2(StarRecord star) {
    Record core = star.core();

    final String id = taxonID(core);
    Node curr = nodeByTaxonId(id);

    if (curr == null) {
      LOG.warn("node not found for taxonID={}", id);
      return;
    }

    String pId = core.value(DwcTerm.parentNameUsageID);
    if (!Strings.isNullOrEmpty(pId)) {
      Node parent = nodeByTaxonId(pId);
      if (parent != null) {
        parent.createRelationshipTo(curr, RelType.PARENT_OF);
      } else {
        LOG.warn("parentNameUsageID {} not existing", pId);
      }
    } else {
      curr.addLabel(Labels.ROOT);
    }


    String aId = core.value(DwcTerm.acceptedNameUsageID);
    if (!Strings.isNullOrEmpty(aId)) {
      Node accepted = nodeByTaxonId(aId);
      if (accepted != null) {
        curr.createRelationshipTo(accepted, RelType.SYNONYM_OF);
        curr.addLabel(Labels.SYNONYM);
      } else {
        LOG.warn("acceptedNameUsageID {} not existing", aId);
      }
    }
  }

  private Node nodeByTaxonId(String taxonID) {
    return IteratorUtil.firstOrNull(db.findNodesByLabelAndProperty(Labels.TAXON, TAXON_ID_PROP, taxonID));
  }

  private void initNeo(String name) throws IOException {
    File storeDir = new File("/tmp/neoclb/"+name);
    if (storeDir.exists()) {
      FileUtils.deleteDirectory(storeDir);
    }

    LOG.info("Create a new neo4j database in {}", storeDir.getAbsolutePath());
    GraphDatabaseFactory factory = new GraphDatabaseFactory();
    db = factory.newEmbeddedDatabaseBuilder(storeDir.getAbsolutePath())
      //.setConfig()
      .newGraphDatabase();

    // setup unique index for TAXON_ID_PROP
    try (Transaction tx = db.beginTx()) {
      Schema schema = db.schema();
      schema.constraintFor(Labels.TAXON).assertPropertyIsUnique(TAXON_ID_PROP).create();
      schema.indexFor(Labels.TAXON).on(SCINAME_PROP).create();
      tx.success();
    }
  }

  public static void main (String[] args) throws IOException {
    File dwca = new File(args[0]);
    NeoImport neo = new NeoImport();
    neo.importDwca(dwca);
  }
}
