# CLB Indexing with Neo4j
Evaluation of checklist bank indexing using Neo4j to read dwc archive, normalize the data and establish taxon relationships.


## Java VM
run with: -server -Xmx3g -XX:+UseConcMarkSweepGC

## Performance notes for current gbif nub in neo
 - iterate over all nodes doing nothing else: 400k/sec, 50MB heap
 - iterate over all nodes and load taxonID property: 35k/sec, 50MB heap
