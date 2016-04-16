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

            // Serialize as triples
            StringWriter sw = new StringWriter();
            model.write(sw, "N-TRIPLES", null);
            String triples = sw.toString();

            // Create SPARQL Update statements
            // @TODO Investigate how to do this in ONE step (notice the ';')
            String sparql = "clear graph <" + page + ">\n;" +
                            "insert data { graph <" + page + "> {\n" + triples + "  }\n}\n";

            // POST update to server
            ret = Request.Post(updateEndpoint).bodyForm(Form.form().add("update", sparql).build()).execute().returnContent().asString();
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
