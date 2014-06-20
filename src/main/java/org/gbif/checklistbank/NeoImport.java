package org.gbif.checklistbank;

import org.gbif.dwc.record.Record;
import org.gbif.dwc.terms.DwcTerm;
import org.gbif.dwc.terms.Term;
import org.gbif.dwc.text.Archive;
import org.gbif.dwc.text.ArchiveFactory;
import org.gbif.dwc.text.StarRecord;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

import com.google.common.base.Strings;
import org.apache.commons.io.FileUtils;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.schema.Schema;
import org.neo4j.helpers.collection.IteratorUtil;
import org.neo4j.tooling.GlobalGraphOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a good id based dwc archive and produces a neo4j graph from it.
 */
public class NeoImport {
  private static final Logger LOG = LoggerFactory.getLogger(NeoImport.class);

  private GraphDatabaseService db;
  private static final String TAXON_ID_PROP = DwcTerm.taxonID.simpleName();
  private static final String SCINAME_PROP = DwcTerm.scientificName.simpleName();
  private static final Pattern NULL_PATTERN = Pattern.compile("^\\s*(\\\\N|\\\\?NULL)\\s*$");
  private boolean useCoreID = false;
  private Archive arch;

  public void importDwca(File dwca) throws IOException {
    initNeo(dwca.getName());

    arch = ArchiveFactory.openArchive(dwca);
    if (!arch.getCore().hasTerm(DwcTerm.taxonID)) {
      LOG.warn("Using core ID for TAXON_ID_PROP");
      useCoreID = true;
    }

    insertData();
    processNodes();

    LOG.info("Import completed");
    db.shutdown();
    LOG.info("Neo shutdown");
  }

  /**
   * Creates a node for each explicit record with all core properties
   */
  private void insertData() {
    Transaction tx = db.beginTx();
    int counter = 0;
    for (StarRecord star : arch) {
      counter++;

      Node n = db.createNode(Labels.TAXON);
      Record core = star.core();

      final String id = taxonID(core);
      n.setProperty(TAXON_ID_PROP, id);

      for (Term t : core.terms()) {
        if (DwcTerm.taxonID == t) continue;

        String val = norm(core.value(t));
        if (val != null) {
          n.setProperty(t.simpleName(), val);
        }
      }

      if (counter % 1000 == 0) {
        tx.success();
        LOG.debug("insert: {}", counter);
      }
    }
    tx.success();
    LOG.info("Data insert completed, {} nodes created", counter);
  }

  /**
   * Creates implicit nodes and sets up relations between taxa.
   */
  private void processNodes() {
    try (Transaction tx = db.beginTx()) {
      for (Node n : GlobalGraphOperations.at(db).getAllNodes()) {
        boolean isSynonym = false;
        final String aId = value(n, DwcTerm.acceptedNameUsageID);
        if (aId != null) {
          isSynonym = true;
          Node accepted = nodeByTaxonId(aId);
          if (accepted != null) {
            n.createRelationshipTo(accepted, RelType.SYNONYM_OF);
            n.addLabel(Labels.SYNONYM);
          } else {
            LOG.warn("acceptedNameUsageID {} not existing", aId);
          }
        }

        final String pId = value(n, DwcTerm.parentNameUsageID);
        if (pId != null) {
          Node parent = nodeByTaxonId(pId);
          if (parent != null) {
            parent.createRelationshipTo(n, RelType.PARENT_OF);
          } else {
            LOG.warn("parentNameUsageID {} not existing", pId);
          }
        } else if (!isSynonym) {
          n.addLabel(Labels.ROOT);
        }
      }
    }
  }

  private String value(Node n, Term term) {
    return (String) n.getProperty(term.simpleName(), null);
  }

  private String taxonID(Record core){
    if (useCoreID) {
      return norm(core.id());
    } else {
      return norm(core.value(DwcTerm.taxonID));
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

    // setup unique index for TAXON_ID
    try (Transaction tx = db.beginTx()) {
      Schema schema = db.schema();
      schema.constraintFor(Labels.TAXON).assertPropertyIsUnique(TAXON_ID_PROP).create();
      schema.indexFor(Labels.TAXON).on(SCINAME_PROP).create();
      tx.success();
    }
  }

  private String norm(String x) {
    if (Strings.isNullOrEmpty(x) || NULL_PATTERN.matcher(x).find()){
      return null;
    }
    return x.trim();
  }

  public static void main (String[] args) throws IOException {
    File dwca = new File(args[0]);
    NeoImport neo = new NeoImport();
    neo.importDwca(dwca);
  }
}
