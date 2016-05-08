# SOCH-Fetch

*A script for scraping RDF linked data from the Swedish Open Cultural Heritage index and its UGC hub, via their web-APIs.*

The SOCH API is pretty great, but if you want to run SPARQL queries on SOCH data – especially federated queries! – it can be useful to have a local copy of the triples to play with.

For each organisation/service, SOCH objects are fetched and parsed in parallel, then cached. The caches are then serialised to Turtle and written to disk (this part is very slow).

To use this script, you will need API keys for SOCH and its UGC hub, and a Redis server to cache the triples prior to serialisation.

(It turns out that Redis does a really fast approximation of a triplestore for certain types of applications!)
