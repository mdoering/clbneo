package org.gbif.checklistbank;

import org.gbif.dwc.record.Record;
import org.gbif.dwc.terms.DwcTerm;
import org.gbif.dwc.terms.Term;
import org.gbif.dwc.text.Archive;
import org.gbif.dwc.text.ArchiveFactory;
import org.gbif.dwc.text.StarRecord;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import org.apache.commons.io.FileUtils;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.schema.Schema;
import org.neo4j.helpers.collection.IteratorUtil;
import org.neo4j.helpers.collection.MapUtil;
import org.neo4j.index.lucene.unsafe.batchinsert.LuceneBatchInserterIndexProvider;
import org.neo4j.tooling.GlobalGraphOperations;
import org.neo4j.unsafe.batchinsert.BatchInserter;
import org.neo4j.unsafe.batchinsert.BatchInserterIndex;
import org.neo4j.unsafe.batchinsert.BatchInserterIndexProvider;
import org.neo4j.unsafe.batchinsert.BatchInserters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a good id based dwc archive and produces a neo4j graph from it.
 */
public class NeoImport {
  private static final Logger LOG = LoggerFactory.getLogger(NeoImport.class);

  private static final String TAXON_ID_PROP = DwcTerm.taxonID.simpleName();
  private static final String SCINAME_PROP = DwcTerm.scientificName.simpleName();
  private static final Pattern NULL_PATTERN = Pattern.compile("^\\s*(\\\\N|\\\\?NULL)\\s*$");
  private final File dwca;
  private final File storeDir;
  private GraphDatabaseService db;
  private boolean useCoreID = false;

  public NeoImport(File dwca) {
    this.dwca = dwca;
    storeDir = new File("/tmp/neoclb/"+dwca.getName());
  }

  public void run() throws IOException {
    initDataDir();
    batchInsertData();
    processNodes();
  }

  /**
   * Creates a node for each explicit record with all core properties
   */
  private void insertData() throws IOException {
    Archive arch = ArchiveFactory.openArchive(dwca);
    if (!arch.getCore().hasTerm(DwcTerm.taxonID)) {
      LOG.warn("Using core ID for TAXON_ID_PROP");
      useCoreID = true;
    }

    final long start = System.currentTimeMillis();
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
        int rec = counter * 1000 / (int) (System.currentTimeMillis()-start);
        LOG.debug("insert: {}, rec/s={}", counter, rec);
      }
    }
    tx.success();
    LOG.info("Data insert completed, {} nodes created", counter);
  }

  private void batchInsertData() throws IOException {
    Archive arch = ArchiveFactory.openArchive(dwca);
    if (!arch.getCore().hasTerm(DwcTerm.taxonID)) {
      LOG.warn("Using core ID for TAXON_ID_PROP");
      useCoreID = true;
    }

    final BatchInserter inserter = BatchInserters.inserter(storeDir.getAbsolutePath());

    final BatchInserterIndexProvider indexProvider = new LuceneBatchInserterIndexProvider(inserter);
    final BatchInserterIndex taxonIdx = indexProvider.nodeIndex( TAXON_ID_PROP, MapUtil.stringMap("type", "exact") );
    taxonIdx.setCacheCapacity( TAXON_ID_PROP, 100000 );
    final BatchInserterIndex nameIdx = indexProvider.nodeIndex( SCINAME_PROP, MapUtil.stringMap("type", "exact") );
    taxonIdx.setCacheCapacity( SCINAME_PROP, 100000 );

    final long start = System.currentTimeMillis();
    int counter = 0;
    for (StarRecord star : arch) {
      counter++;

      Record core = star.core();
      Map<String, Object> props = Maps.newHashMap();

      for (Term t : core.terms()) {
        String val = norm(core.value(t));
        if (val != null) {
          props.put(t.simpleName(), val);
        }
      }
      // make sure this is last to override already put taxonID keys
      props.put(TAXON_ID_PROP, taxonID(core));
      // ... and into neo
      long node = inserter.createNode(props, Labels.TAXON);
      taxonIdx.add( node, props );
      nameIdx.add( node, props );

      if (counter % 1000 == 0) {
        int rec = counter * 1000 / (int) (System.currentTimeMillis()-start);
        LOG.debug("insert: {}, rec/s={}", counter, rec);
      }
    }
    LOG.info("Data insert completed, {} nodes created", counter);

    indexProvider.shutdown();
    inserter.shutdown();
    LOG.info("Neo shutdown, data flushed to disk", counter);
  }

  /**
   * Creates implicit nodes and sets up relations between taxa.
   */
  private void processNodes() {
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


    final long start = System.currentTimeMillis();
    int counter = 0;
    try (Transaction tx = db.beginTx()) {
      for (Node n : GlobalGraphOperations.at(db).getAllNodes()) {
        counter++;
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

        if (counter % 1000 == 0) {
          tx.success();
          int rec = counter * 1000 / (int) (System.currentTimeMillis()-start);
          LOG.debug("processed: {}, rec/s={}", counter, rec);
        }
      }
      tx.success();
    }

    db.shutdown();
    LOG.info("Import completed, {} nodes processed", counter);
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

  private void initDataDir() throws IOException {
    if (storeDir.exists()) {
      FileUtils.deleteDirectory(storeDir);
    }
    LOG.info("Create a new neo4j database in {}", storeDir.getAbsolutePath());
  }

  private String norm(String x) {
    if (Strings.isNullOrEmpty(x) || NULL_PATTERN.matcher(x).find()){
      return null;
    }
    return x.trim();
  }

  public static void main (String[] args) throws IOException {
    File dwca = new File(args[0]);
    NeoImport neo = new NeoImport(dwca);
    neo.run();
  }
}
