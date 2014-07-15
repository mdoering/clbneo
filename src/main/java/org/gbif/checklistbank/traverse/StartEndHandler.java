package org.gbif.checklistbank.traverse;

import org.neo4j.graphdb.Node;

/**
 *
 */
public interface StartEndHandler {
    void start(Node n);

    void end(Node n);
}
