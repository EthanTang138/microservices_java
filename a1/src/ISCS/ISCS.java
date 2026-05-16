import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ISCS {
    private static String userIp     = "127.0.0.1";
    private static int    userPort   = 14001;
    private static String productIp  = "127.0.0.1";
    private static int    productPort = 15000;

    // AtomicIntegers ready for A2 round-robin
    private static final AtomicInteger userIdx    = new AtomicInteger(0);
    private static final AtomicInteger productIdx = new AtomicInteger(0);

    public static void main(String[] args) throws IOException {
        if (args.length != 1) { System.err.println("Usage: java ISCS config.json"); return; }
        Map<String, String> own  = loadServiceConfig(args[0], "InterServiceCommunication");
        Map<String, String> usr  = loadServiceConfig(args[0], "UserService");
        Map<String, String> prod = loadServiceConfig(args[0], "ProductService");

        int port = Integer.parseInt(own.get("port"));
        userIp      = usr.get("ip");
        userPort    = Integer.parseInt(usr.get("port"));
        productIp   = prod.get("ip");
        productPort = Integer.parseInt(prod.get("port"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(20));
        server.createContext("/user",    new ForwardHandler("user"));
        server.createContext("/product", new ForwardHandler("product"));
        server.start();
        System.out.println("ISCS started on port " + port);
        System.out.println("  -> UserService    at " + userIp    + ":" + userPort);
        System.out.println("  -> ProductService at " + productIp + ":" + productPort);
    }

    private static Map<String, String> loadServiceConfig(String filename, String section) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new FileReader(filename))) {
                String line; while ((line = r.readLine()) != null) sb.append(line).append("\n");
            }
            String content = sb.toString();
            int idx = content.indexOf("\"" + section + "\"");
            if (idx < 0) throw new RuntimeException("Section not found: " + section);
            int bs = content.indexOf("{", idx), be = content.indexOf("}", bs);
            String block = content.substring(bs, be + 1);
            Map<String, String> cfg = new HashMap<>();
            java.util.regex.Matcher pm = java.util.regex.Pattern.compile("\"port\"\\s*:\\s*(\\d+)").matcher(block);
            if (pm.find()) cfg.put("port", pm.group(1));
            java.util.regex.Matcher im = java.util.regex.Pattern.compile("\"ip\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
            if (im.find()) cfg.put("ip", im.group(1));
            return cfg;
        } catch (IOException e) { throw new RuntimeException("Failed to load config", e); }
    }

    static class ForwardHandler implements HttpHandler {
        private final String service; // "user" or "product"

        ForwardHandler(String service) { this.service = service; }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            String path   = ex.getRequestURI().getPath();
            String query  = ex.getRequestURI().getQuery();

            String backendBase = "user".equals(service)
                ? "http://" + userIp    + ":" + userPort
                : "http://" + productIp + ":" + productPort;

            String targetUrl = backendBase + path + (query != null ? "?" + query : "");

            HttpResp resp;
            if ("GET".equalsIgnoreCase(method)) {
                resp = doGet(targetUrl);
            } else if ("POST".equalsIgnoreCase(method)) {
                resp = doPost(targetUrl, readBody(ex));
            } else {
                sendResponse(ex, 405, "{}");
                return;
            }
            sendResponse(ex, resp.code, resp.body);
        }

        private HttpResp doGet(String url) {
            HttpResp r = new HttpResp();
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                r.code = conn.getResponseCode();
                r.body = readStream(r.code < 400 ? conn.getInputStream() : conn.getErrorStream());
                conn.disconnect();
            } catch (IOException e) { r.code = 500; r.body = "{}"; }
            return r;
        }

        private HttpResp doPost(String url, String body) {
            HttpResp r = new HttpResp();
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                byte[] bytes = (body != null ? body : "").getBytes(StandardCharsets.UTF_8);
                conn.getOutputStream().write(bytes);
                conn.getOutputStream().close();
                r.code = conn.getResponseCode();
                r.body = readStream(r.code < 400 ? conn.getInputStream() : conn.getErrorStream());
                conn.disconnect();
            } catch (IOException e) { r.code = 500; r.body = "{}"; }
            return r;
        }

        private String readStream(InputStream is) throws IOException {
            if (is == null) return "{}";
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        }

        private String readBody(HttpExchange ex) throws IOException {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        }

        private void sendResponse(HttpExchange ex, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    static class HttpResp { int code; String body; }
}
