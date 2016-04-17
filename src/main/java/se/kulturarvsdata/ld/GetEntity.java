package se.kulturarvsdata.ld;

import java.io.*;
import java.net.*;

import javax.servlet.*;
import javax.servlet.http.*;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;


/**
 * @author marma
 */
public class GetEntity extends HttpServlet {
    static String queryEndpoint = null;
    static String baseURI = null;

    @Override
    public void init(ServletConfig config) {
        queryEndpoint = config.getServletContext().getInitParameter("UpdateEndpoint");
        baseURI = config.getServletContext().getInitParameter("BaseURI");
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("text/plain; charset=UTF-8");
        String uri = baseURI + request.getRequestURI().substring(1);
        PrintWriter out = response.getWriter();

        // Build SPARQL query
        String sparql = "DESCRIBE <" + uri + ">";

        // Query SPARQL endpoint
        QueryExecution queryExecution = QueryExecutionFactory.sparqlService(queryEndpoint, sparql);
        Model model = queryExecution.execDescribe();

        // Serialize as Turtle
        StringWriter sw = new StringWriter();
        model.write(sw, "TURTLE", null);
        String turtle = sw.toString();

        out.println(turtle);

        out.close();
    }
}
