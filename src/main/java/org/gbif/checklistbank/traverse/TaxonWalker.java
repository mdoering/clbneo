package org.gbif.checklistbank.traverse;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import org.gbif.dwc.terms.DwcTerm;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class TaxonWalker {
    private static final Logger LOG = LoggerFactory.getLogger(TaxonWalker.class);

    /**
     * Make sure you walk within an open transaction!
     * @param db
     * @param handler
     */
    public static void walkAll(GraphDatabaseService db, StartEndHandler handler) {
        Path lastPath = null;
        for (Path p : TaxonomicIterator.all(db)) {
            //logPath(p);
            if (lastPath != null) {
                PeekingIterator<Node> lIter = Iterators.peekingIterator(lastPath.nodes().iterator());
                PeekingIterator<Node> cIter = Iterators.peekingIterator(p.nodes().iterator());
                while (lIter.hasNext() && cIter.hasNext() && lIter.peek().equals(cIter.peek())) {
                    lIter.next();
                    cIter.next();
                }
                // only non shared nodes left.
                // first close all old nodes, then open new ones
                // reverse order for closing nodes...
                for (Node n : ImmutableList.copyOf(lIter).reverse()) {
                    handler.end(n);
                }
                while (cIter.hasNext()) {
                    handler.start(cIter.next());
                }

            } else {
                // only new nodes
                for (Node n : p.nodes()) {
                    handler.start(n);
                }
            }
            lastPath = p;
        }
    }

    private static void logPath(Path p) {
        StringBuilder sb = new StringBuilder();
        for (Node n : p.nodes()) {
            if (sb.length() > 0) {
                sb.append(" -- ");
            }
            sb.append((String) n.getProperty(DwcTerm.scientificName.simpleName()));
        }
        sb.append(", " + p.endNode().getProperty(DwcTerm.taxonRank.simpleName()));
        LOG.debug(sb.toString());
    }

}
