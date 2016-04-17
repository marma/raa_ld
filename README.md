# RAÄ LD för DAP

    DISCLAIMER: denna programvara, och framförallt dokumentationen av den, är under uppbyggnad
    och ännu inte användbar fullt ut.

## Krav

### Komponenter

* Java 8+

### Byggverktyg

* Gradle 2.1+ (<http://gradle.org/>)

### Mac OS X via HomeBrew (http://brew.sh/)

    # brew install gradle

### Linux

    # sudo apt-get install gradle

### Windows

* https://docs.gradle.org/current/userguide/installation.html

### Konfiguration

* Kopiera src/main/webapp/WEB-INF/web.xml.in till src/main/webapp/WEB-INF/web.xml och fyll i värden för bas-URI och SPARQL endpoint
* Kontext för JSON-LD-"fragment" finns under src/main/webapp/context.json

## Exempel
Bygg war-fil

    # gradle clean war

Starta servern lokalt mha Jetty

    # gradle clean jettyRun

Skicka in en fil med JSON-LD-"fragment" till triple-store

    # curl --data-urlencoded "entity=`cat examples/uppdrag.json`" http://localhost:8080/updateentity

Hämta ut Turtle via LOD med identifierare från filen ovan (minus bas-URI)

    # curl http://localhost:8080/aktivitet/uppdrag/01b2bce8-011b-4f7e-bca3-97dd84e50f14
