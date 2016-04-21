package se.kulturarvsdata.ld;

import java.io.*;
import java.net.*;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;

import org.json.*;
import org.apache.jena.rdf.model.*;
import org.apache.http.client.fluent.*;

/**
 * @author marma
 */
public class UpdateEntity extends HttpServlet {
    static String updateEndpoint = null;
    static JSONObject context = null;

    @Override
    public void init(ServletConfig config) {
        updateEndpoint = config.getServletContext().getInitParameter("UpdateEndpoint");

        // Load @context from file
        JSONTokener tokener = new JSONTokener(config.getServletContext().getResourceAsStream("/context.json"));
        context = new JSONObject(tokener);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        response.setContentType("text/plain; charset=utf-8");
        String ret = null;

        try {
            // Add @context to JSON-LD
            JSONObject entity = new JSONObject(request.getParameter("entity"));
            entity.put("@context", context);
            String jsonld = entity.toString();

            // find page used for removing old statements from triple store
            // @TODO Either enforce complete IRI in @id or expand it
            String page = entity.get("@id").toString() + "/data";

            // Read JSON-LD into a Model
            Model model = ModelFactory.createDefaultModel();
            model.read(new StringReader(jsonld), null, "JSON-LD");

            // Find coordinates and add Blazegraph geo triples
            // @TODO Make this configurable to support not just Blazegraph
            // (https://wiki.blazegraph.com/wiki/index.php/GeoSpatial)
            Model geomodel = ModelFactory.createDefaultModel();
            StmtIterator iter = model.listStatements();
            while (iter.hasNext()) {
                Statement statement = iter.nextStatement();
                Resource resource = statement.getSubject();
                org.apache.jena.rdf.model.Property predicate = statement.getPredicate();
                RDFNode node = statement.getObject();

                if (node.isLiteral() && node.asLiteral().getDatatypeURI().equals("http://www.opengis.net/ont/sf#wktLiteral")) {
                    String s = node.asLiteral().getString();
                    System.out.println(node.asLiteral().getDatatype().getClass());

                    // Is it a point?
                    if (s.startsWith("POINT(")) {
                        System.out.println(statement);

                        String p = s.substring(6).replace(' ', '#').substring(0, s.length()-7);
                        geomodel.add(
                            geomodel.createStatement(
                                resource,
                                predicate,
                                model.createTypedLiteral(p, new org.apache.jena.datatypes.BaseDatatype("http://www.bigdata.com/rdf/geospatial/literals/v1#lat-lon"))
                            )
                        );

                        System.out.println(p);
                    }
                }
            }
            model.add(geomodel);

            // Serialize as triples
            StringWriter sw = new StringWriter();
            model.write(sw, "N-TRIPLES", null);
            String triples = sw.toString();

            // Create SPARQL Update statements
            // @TODO Investigate how to do this in ONE step (notice the ';')
            String sparql = "clear graph <" + page + ">\n;" +
                            "insert data {\n  graph <" + page + "> {    \n" + triples + "  }\n}\n";

            // POST update to server
            ret = "REPONSE:" + Request.Post(updateEndpoint).bodyForm(Form.form().add("update", sparql).build()).execute().returnContent().asString();
        } catch (Exception e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
            ret = e.getMessage();
        }

        PrintWriter out = response.getWriter();
        out.println(ret);
        out.close();
    }
}
