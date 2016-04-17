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
public class UpdateRawJsonLD extends HttpServlet {
    static String updateEndpoint = null;
    static JSONObject context = null;

    @Override
    public void init(ServletConfig config) {
        updateEndpoint = config.getServletContext().getInitParameter("UpdateEndpoint");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        response.setContentType("text/plain; charset=utf-8");
        PrintWriter out = response.getWriter();

        try {
            // find page used for removing old statements from triple store
            // @TODO Either enforce complete IRI in @id or expand it
            JSONObject entity = new JSONObject(request.getParameter("entity"));
            String page = request.getParameter("page") != null? request.getParameter("page"):entity.get("@id").toString() + "/data";

            // Read JSON-LD into a Model
            Model model = ModelFactory.createDefaultModel();
            model.read(new StringReader(entity.toString()), null, "JSON-LD");

            // Serialize as triples
            StringWriter sw = new StringWriter();
            model.write(sw, "N-TRIPLES", null);
            String triples = sw.toString();

            // Create SPARQL Update statements
            // @TODO Investigate how to do this in ONE step (notice the ';')
            String sparql = "clear graph <" + page + ">\n;" +
                            "insert data {\n  graph <" + page + "> {    \n" + triples + "  }\n}\n";

            // POST update to server, print result
            out.println("RESPONSE:" + Request.Post(updateEndpoint).bodyForm(Form.form().add("update", sparql).build()).execute().returnContent().asString());
        } catch (Exception e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
            out.println(e.getMessage());
        }

        out.close();
    }
}
