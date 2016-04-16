package se.kulturarvsdata.ld;

import java.io.*;
import java.net.*;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;

import org.json.*;
import org.apache.jena.rdf.model.*;

/**
 * @author marma
 */
public class UpdateEntity extends HttpServlet {
    static String sparqlEndpoint = null;
    static JSONObject context = null;

    @Override
    public void init(ServletConfig config) {
        sparqlEndpoint = config.getServletContext().getInitParameter("SparqlEndpoint");

        // Load @context from file
        JSONTokener tokener = new JSONTokener(config.getServletContext().getResourceAsStream("/context.json"));
        context = new JSONObject(tokener);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("text/plain; charset=utf-8");
        String ret = null;

        try {
            // Add @context to JSON-LD
            JSONObject entity = new JSONObject(request.getParameter("entity"));
            entity.put("@context", context);
            String jsonld = entity.toString();

            // Read JSON-LD into a Model
            Model model = ModelFactory.createDefaultModel();
            model.read(new StringReader(jsonld), null, "JSON-LD");

            // Serialize as triples
            StringWriter sw = new StringWriter();
            model.write(sw, "N-TRIPLES", null);

            ret = sw.toString();

/*
            Object jsonObject = JsonUtils.fromString(ret);
            Object normalized = JsonLdProcessor.normalize(jsonObject);
            String rdf = toString((RDFDataset)normalized);
            // POST RDF to SPARQL Endpoint
            // @TODO find actual URI
            String page = obj.get("@id") + "/data";
            String param = "delete { graph <" + page + "> { ?s ?o ?p . } } insert { graph <" + page + "> { " + rdf + " } } where { ?s ?o ?p . }";
            ret = JsonUtils.toPrettyString(jsonObject) + "\n\n\n" + param;
*/
/*
            URL url = new URL(server + "?update=" + URLEncoder.encode(param, "UTF-8"));
            ret = url.toString();
            URLConnection urlConnection = url.openConnection();
            BufferedReader in = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
            String inputLine;
            while ((inputLine = in.readLine()) != null)
                System.out.println(inputLine);
            in.close();
            */
        } catch (Exception e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
        }

        PrintWriter out = response.getWriter();
        out.println(ret);
        out.close();
    }
}
