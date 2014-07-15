package org.gbif.checklistbank;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.yammer.metrics.*;
import com.yammer.metrics.jvm.MemoryUsageGaugeSet;
import org.gbif.checklistbank.traverse.TaxonWalker;
import org.gbif.dwc.record.Record;
import org.gbif.dwc.terms.DwcTerm;
import org.gbif.dwc.terms.Term;
import org.gbif.dwc.text.Archive;
import org.gbif.dwc.text.ArchiveFactory;
import org.gbif.dwc.text.StarRecord;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.schema.Schema;
import org.neo4j.helpers.collection.IteratorUtil;
import org.neo4j.helpers.collection.MapUtil;
import org.neo4j.index.lucene.unsafe.batchinsert.LuceneBatchInserterIndexProvider;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.impl.core.NodeManager;
import org.neo4j.tooling.GlobalGraphOperations;
import org.neo4j.unsafe.batchinsert.BatchInserter;
import org.neo4j.unsafe.batchinsert.BatchInserterIndex;
import org.neo4j.unsafe.batchinsert.BatchInserterIndexProvider;
import org.neo4j.unsafe.batchinsert.BatchInserters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Reads a good id based dwc archive and produces a neo4j graph from it.
 */
public class NeoImport {
    private static final Logger LOG = LoggerFactory.getLogger(NeoImport.class);

    private static final Pattern NULL_PATTERN = Pattern.compile("^\\s*(\\\\N|\\\\?NULL)\\s*$");
    private final File dwca;
    private final File storeDir;
    private GraphDatabaseService db;
    private boolean useCoreID = false;
    private int idx = 0;
    private final int BATCH_SIZE = 10000;
    private final MetricRegistry registry = new MetricRegistry("clbneo");
    private final Meter insertMeter = registry.meter("taxon inserts");
    private final Meter relationMeter = registry.meter("taxon relations");
    private final Meter metricsMeter = registry.meter("taxon metrics");
    private final Gauge memory;
    private final ScheduledReporter reporter;

    public NeoImport(File dwca) {
        this.dwca = dwca;
        storeDir = NeoUtils.neoDir(dwca.getName());
        // Sets up performance metrics reporting
        MemoryUsageGaugeSet mgs = new MemoryUsageGaugeSet();
        registry.registerAll(mgs);
        memory = (Gauge) mgs.getMetrics().get("heap.usage");
        reporter = ConsoleReporter.forRegistry(registry)
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .build();
        //reporter.start(60, TimeUnit.SECONDS);
    }

    public void run() throws IOException {
//        batchInsertData();
        initDb();
        setupTaxonIdIndex();
//        setupRelations();
        buildMetrics();

        reporter.report(registry.getGauges(), registry.getCounters(), registry.getHistograms(), registry.getMeters(), registry.getTimers());
    }


    private void batchInsertData() throws IOException {
        Archive arch = ArchiveFactory.openArchive(dwca);
        if (!arch.getCore().hasTerm(DwcTerm.taxonID)) {
            LOG.warn("Using core ID for TAXON_ID_PROP");
            useCoreID = true;
        }

        final BatchInserter inserter = BatchInserters.inserter(storeDir.getAbsolutePath());

        final BatchInserterIndexProvider indexProvider = new LuceneBatchInserterIndexProvider(inserter);
        final BatchInserterIndex taxonIdx = indexProvider.nodeIndex(DwcTerm.taxonID.simpleName(), MapUtil.stringMap("type", "exact"));
        taxonIdx.setCacheCapacity(DwcTerm.taxonID.simpleName(), 10000);

        final long startSort = System.currentTimeMillis();
        LOG.debug("Sorted archive in {} seconds", (System.currentTimeMillis() - startSort) / 1000);

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
            props.put(DwcTerm.taxonID.simpleName(), taxonID(core));
            // ... and into neo
            long node = inserter.createNode(props, Labels.TAXON);
            taxonIdx.add(node, props);

            insertMeter.mark();
            if (counter % (BATCH_SIZE*10) == 0) {
                LOG.debug("insert: {}", counter);
            }
        }
        LOG.info("Data insert completed, {} nodes created", counter);
        LOG.info("Insert metrics: {}", insertMeter.getMeanRate());

        indexProvider.shutdown();
        inserter.shutdown();
        LOG.info("Neo shutdown, data flushed to disk", counter);
    }

    private void initDb() throws IOException {
        GraphDatabaseFactory factory = new GraphDatabaseFactory();
        db = factory.newEmbeddedDatabaseBuilder(storeDir.getAbsolutePath())
            //TODO: make this configurable
            .loadPropertiesFromFile("src/main/resources/neo.properties")
            .newGraphDatabase();
        LOG.info("Starting neo4j database from {}", storeDir.getAbsolutePath());
    }

    private void setupTaxonIdIndex() {
        // setup unique index for TAXON_ID if not yet existing
        try (Transaction tx = db.beginTx()) {
            Schema schema = db.schema();
            if (IteratorUtil.count(schema.getIndexes(Labels.TAXON)) == 0) {
                LOG.debug("Create db indices ...");
                schema.constraintFor(Labels.TAXON).assertPropertyIsUnique(DwcTerm.taxonID.simpleName()).create();
                schema.indexFor(Labels.TAXON).on(DwcTerm.scientificName.simpleName()).create();
                tx.success();
            } else {
                LOG.debug("Neo indices existing already");
            }
        }
    }

    private void deleteAllRelations() {
        LOG.debug("Delete any existing relations");
        try (Transaction tx = db.beginTx()) {
            int counter = 0;
            for (Relationship rel : GlobalGraphOperations.at(db).getAllRelationships()) {
                rel.delete();
                counter++;
                if (counter % BATCH_SIZE == 0) {
                    tx.success();
                    LOG.debug("Deleted {} relations", counter);
                }
            }
            tx.success();
            LOG.debug("Deleted all {} relations", counter);
        }
    }

    /**
     * Creates implicit nodes and sets up relations between taxa.
     */
    private void setupRelations() {
        LOG.debug("Start processing ...");
        long counter = 0;

        Transaction tx = db.beginTx();
        try {
            for (Node n : GlobalGraphOperations.at(db).getAllNodes()) {
                if (counter % BATCH_SIZE == 0) {
                    tx.success();
                    tx.close();
                    LOG.debug("Relations processed for taxa: {}", counter);
                    logMemory();
                    tx = db.beginTx();
                }

                final String taxonID = (String) n.getProperty(DwcTerm.taxonID.simpleName());

                boolean isSynonym = setupAcceptedRel(n, taxonID);
                setupParentRel(n, isSynonym, taxonID);
                setupBasionymRel(n, taxonID);

                counter++;
                relationMeter.mark();
            }

        } finally {
            tx.close();
        }
        LOG.info("Import completed, {} nodes processed", counter);
        LOG.info("Relation setup metrics: {}", relationMeter.getMeanRate());
    }

    private void buildMetrics() {
        ImportTaxonMetricsHandler handler = new ImportTaxonMetricsHandler(db);

        Transaction tx = db.beginTx();
        try {
            TaxonWalker.walkAll(db, handler);
            tx.success();
        } finally {
            tx.close();
        }
    }



    private void logMemory() {
        LOG.debug("Heap usage: {}", memory.getValue());
    }

    @Deprecated
    private long getMaxNodeId(){
        NodeManager nodeManager = ((GraphDatabaseAPI) db).getDependencyResolver().resolveDependency(NodeManager.class);
        return nodeManager.getHighestPossibleIdInUse(Node.class);
    }

    private boolean setupAcceptedRel(Node n, String taxonID) {
        final String aId = value(n, DwcTerm.acceptedNameUsageID);
        if (aId != null && !aId.equals(taxonID)) {
            Node accepted = nodeByTaxonId(aId);
            if (accepted != null) {
                n.createRelationshipTo(accepted, RelType.SYNONYM_OF);
                n.addLabel(Labels.SYNONYM);
            } else {
                LOG.warn("acceptedNameUsageID {} not existing", aId);
            }
            return true;
        }
        return false;
    }

    private void setupParentRel(Node n, boolean isSynonym, String taxonID) {
        final String pId = value(n, DwcTerm.parentNameUsageID);
        if (pId != null && !pId.equals(taxonID)) {
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

    private void setupBasionymRel(Node n, String taxonID) {
        final String id = value(n, DwcTerm.originalNameUsageID);
        if (id != null && !id.equals(taxonID)) {
            Node parent = nodeByTaxonId(id);
            if (parent != null) {
                parent.createRelationshipTo(n, RelType.BASIONYM_OF);
            } else {
                LOG.warn("originalNameUsageID {} not existing", id);
            }
        }
    }

    private String value(Node n, Term term) {
        return (String) n.getProperty(term.simpleName(), null);
    }

    private String taxonID(Record core) {
        if (useCoreID) {
            return norm(core.id());
        } else {
            return norm(core.value(DwcTerm.taxonID));
        }
    }

    private Node nodeByTaxonId(String taxonID) {
        return IteratorUtil.firstOrNull(db.findNodesByLabelAndProperty(Labels.TAXON, DwcTerm.taxonID.simpleName(), taxonID));
    }

    private String norm(String x) {
        if (Strings.isNullOrEmpty(x) || NULL_PATTERN.matcher(x).find()) {
            return null;
        }
        return x.trim();
    }

    public static void main(String[] args) throws IOException {
        File dwca = new File(args[0]);
        NeoImport neo = new NeoImport(dwca);
        neo.run();
    }
}
