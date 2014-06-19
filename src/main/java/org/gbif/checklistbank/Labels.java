package org.gbif.checklistbank;

import org.neo4j.graphdb.Label;

/**
 *
 */
public enum Labels implements Label {
  TAXON,
  SYNONYM,
  ROOT
}
