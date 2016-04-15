package se.kulturarvsdata.ld;

import java.io.*;
import java.net.*;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;

import com.github.jsonldjava.utils.*;
import com.github.jsonldjava.core.*;

import org.json.*;

/**
 * @author marma
 */
public class UpdateEntity extends HttpServlet {
    // @TODO static @context for now ...
    static Map context = new TreeMap();
    static {
        context.put("@vocab", "https://kulturarvsdata.se/egenskap/");
        context.put("org", "https://kulturarvsdata.se/aktör/organisation/");
        context.put("typ", "https://kulturarvsdata.se/kategori/");
        context.put("uppdrag", "https://kulturarvsdata.se/aktivitet/uppdrag/");
        context.put("rapport", "https://kulturarvsdata.se/dokumentation/rapport/");
        context.put("uppdragstyp", "https://kulturarvsdata.se/aktivitet/arkeologisktuppdrag/");

        Map amap = new TreeMap();
        amap.put("@id", "https://kulturarvsdata.se/aktivitet/ärende/");
        amap.put("@type", "@id");
        context.put("ärendenr", amap);

        Map omap = new TreeMap();
        omap.put("@id", "organisation");
        omap.put("@type", "@id");
        context.put("organisation", omap);

        Map aamap = new TreeMap();
        aamap.put("@id", "ärende");
        aamap.put("@type", "@id");
        context.put("ärende", aamap);
    }

    public static String toString(RDFDataset dataset) {
        StringBuffer sb = new StringBuffer();

        for (Object o: (ArrayList)dataset.get("@default")) {
            Map m = (Map)o;
            sb.append("<");
            sb.append(((Map)m.get("subject")).get("value"));
            sb.append("> <");
            sb.append(((Map)m.get("predicate")).get("value"));
            sb.append("> ");

            String t = (String)((Map)m.get("object")).get("type");

            if (t.equals("IRI")) {
                sb.append("<");
                sb.append(((Map)m.get("object")).get("value"));
                sb.append("> .");
            } else {
                sb.append("\"");
                sb.append(((Map)m.get("object")).get("value"));
                sb.append("\" .");
            }
        }

        return sb.toString();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("text/plain; charset=utf-8");
        String server = getServletContext().getInitParameter("SparqlEndpoint");
        String ret = null;

        try {
            // convert JSON-LD to RDF
            JSONObject obj = new JSONObject(request.getParameter("entity"));
            obj.put("@context", context);
            ret = obj.toString();
            Object jsonObject = JsonUtils.fromString(ret);
            Object normalized = JsonLdProcessor.normalize(jsonObject);
            String rdf = toString((RDFDataset)normalized);

            // POST RDF to SPARQL Endpoint
            // @TODO find actual URI
            String page = obj.get("@id") + "/data";
            String param = "delete { graph <" + page + "> { ?s ?o ?p . } } insert { graph <" + page + "> { " + rdf + " } } where { ?s ?o ?p . }";
            ret = param;

            URL url = new URL(server + "?update=" + URLEncoder.encode(param, "UTF-8"));
            ret = url.toString();
            URLConnection urlConnection = url.openConnection();
            BufferedReader in = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
            String inputLine;
            while ((inputLine = in.readLine()) != null)
                System.out.println(inputLine);
            in.close();
        } catch (Exception e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
        }

        PrintWriter out = response.getWriter();
        out.println(ret);
        out.close();
    }
}
