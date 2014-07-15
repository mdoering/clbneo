package org.gbif.checklistbank;

import com.yammer.metrics.Gauge;
import com.yammer.metrics.Meter;
import com.yammer.metrics.MetricRegistry;
import com.yammer.metrics.jvm.MemoryUsageGaugeSet;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.tooling.GlobalGraphOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Reads a good id based dwc archive and produces a neo4j graph from it.
 */
public class RelationCreateTest {
    private static final Logger LOG = LoggerFactory.getLogger(RelationCreateTest.class);

    private GraphDatabaseService db;

    private final MetricRegistry registry = new MetricRegistry("neotest");
    private final Meter meter = registry.meter("relation meter");
    private final Gauge memory;

    enum RelType implements RelationshipType{ PREVIOUS }

    public RelationCreateTest(File neoDir) {
        LOG.debug("Setting up embeeded neo database in dir {}", neoDir.getAbsolutePath());

        GraphDatabaseFactory factory = new GraphDatabaseFactory();
        db = factory.newEmbeddedDatabaseBuilder(neoDir.getAbsolutePath())
            .loadPropertiesFromFile("src/main/resources/neo.properties")
            .newGraphDatabase();

        // Sets up performance metrics reporting
        MemoryUsageGaugeSet mgs = new MemoryUsageGaugeSet();
        registry.registerAll(mgs);
        memory = (Gauge) mgs.getMetrics().get("heap.usage");
    }

    /**
     * Creates implicit nodes and sets up relations between taxa.
     */
    public void createRelations() {
        LOG.debug("Start creating relations ...");
        long counter = 0;
        Node prev = null;

        Transaction tx = db.beginTx();
        try {
            for (Node n : GlobalGraphOperations.at(db).getAllNodes()) {
                if (counter % 10000 == 0) {
                    tx.success();
                    LOG.debug("Relations created={}, rate={}", counter, meter.getMeanRate());
                    LOG.debug("Heap usage: {}", memory.getValue());
                }
                // create relation to previous
                if (prev != null){
                    n.createRelationshipTo(prev, RelType.PREVIOUS);
                    counter++;
                    meter.mark();
                }
                prev = n;
            }
            tx.success();

        } finally {
            tx.close();
        }

        LOG.info("All nodes processed, {} relations created", counter);
        LOG.info("Relation metrics: {}", meter.getMeanRate());
        LOG.debug("Heap usage: {}", memory.getValue());
    }

    public void createNodes(int amount) {
        LOG.debug("Start creating {} nodes");

        Transaction tx = db.beginTx();
        try {
            for (int idx=1; idx<=amount; idx++) {
                if (idx % 10000 == 0) {
                    tx.success();
//                    tx.close();
                    LOG.debug("Node created={}, rate={}", idx, meter.getMeanRate());
                    LOG.debug("Heap usage: {}", memory.getValue());
//                    tx = db.beginTx();
                }
                Node n = db.createNode();
                meter.mark();
            }
            tx.success();

        } finally {
            tx.close();
        }

        LOG.info("All {} nodes created", amount);
        LOG.info("Mean creation rate={}", meter.getMeanRate());
        LOG.debug("Heap usage={}", memory.getValue());

        db.shutdown();
    }

    public static void main(String[] args) throws IOException {
        RelationCreateTest neo = new RelationCreateTest(NeoUtils.neoDir("test"));
        //neo.createRelations();
        neo.createNodes(10000000);
    }
}
