package org.gbif.checklistbank;

import org.gbif.checklistbank.traverse.StartEndHandler;
import org.gbif.dwc.terms.Term;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a nested set index for parent child related nodes.
 */
public class ImportTaxonMetricsHandler implements StartEndHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ImportTaxonMetricsHandler.class);
    private static final String PROP_LFT = "lft";
    private static final String PROP_RGT = "rgt";

    private final GraphDatabaseService db;
    int idx = 0;

    public ImportTaxonMetricsHandler(GraphDatabaseService db) {
        this.db = db;
    }

    @Override
    public void start(Node n) {
        if (idx % 5000 == 0) {
            LOG.debug("idx = {}", idx);
        }
        n.setProperty(PROP_LFT, idx++);
    }

    @Override
    public void end(Node n) {
        if (idx % 5000 == 0) {
            LOG.debug("idx = {}", idx);
        }
        n.setProperty(PROP_RGT, idx++);
        //LOG.debug("[{}] {}   {}-{}", prop(n, DwcTerm.taxonRank), prop(n, DwcTerm.scientificName), n.getProperty(PROP_LFT), n.getProperty(PROP_RGT));
    }

    private String prop(Node n, Term prop) {
        return (String) n.getProperty(prop.simpleName(), null);
    }
}
