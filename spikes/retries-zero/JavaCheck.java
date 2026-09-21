// Gate item 1, Java: does `Integer retries` (java/src/main/java/io/github/muthuishere/
// toolnexus/LlmClient.java:92) already distinguish "unset" from "explicit 0"?
// The field is boxed (nullable), and the defaulting is
//   line 2166: private int retries() { return opts.retries != null ? opts.retries : 2; }
// -- a null check, not a `> 0`/truthiness check, so 0 survives. Prove it against a
// real local HTTP server that always answers 500, counting requests.
//
// Run with: java -cp <toolnexus-jar-or-classes>:JavaCheck.java JavaCheck
// (compiled via the driver script in this directory, which puts the built
// classes on the classpath).

import com.sun.net.httpserver.HttpServer;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.Toolkit;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

public class JavaCheck {
    public static void main(String[] args) throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] body = "boom".getBytes();
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        Toolkit toolkit = Toolkit.create(new Toolkit.Options());

        // explicit zero
        LlmClient.Options opts0 = new LlmClient.Options();
        opts0.baseUrl = baseUrl;
        opts0.style = "openai";
        opts0.model = "test-model";
        opts0.apiKey = "x";
        opts0.retries = 0;
        LlmClient client0 = LlmClient.create(opts0);
        try {
            client0.run("hi", toolkit);
        } catch (Exception e) {
            // expected
        }
        int c0 = calls.getAndSet(0);
        System.out.println("calls with retries=0 -> " + c0);
        if (c0 != 1) {
            System.out.println("FAIL: expected 1 call, got " + c0);
            System.exit(1);
        }

        // unset -> documented default of 2 retries = 3 total attempts
        LlmClient.Options opts1 = new LlmClient.Options();
        opts1.baseUrl = baseUrl;
        opts1.style = "openai";
        opts1.model = "test-model";
        opts1.apiKey = "x";
        // opts1.retries left null
        LlmClient client1 = LlmClient.create(opts1);
        try {
            client1.run("hi", toolkit);
        } catch (Exception e) {
            // expected
        }
        int c1 = calls.get();
        System.out.println("calls with retries UNSET -> " + c1);
        if (c1 != 3) {
            System.out.println("FAIL: expected 3 calls, got " + c1);
            System.exit(1);
        }

        server.stop(0);
        System.out.println("JAVA VERDICT: retries=0 != unset. Boxed Integer null-check already distinguishes them. No -1 sentinel needed.");
    }
}
