package com.coolhandlabs.coolhand.support;

import com.coolhandlabs.coolhand.internal.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A local {@link HttpServer} standing in for an LLM provider and/or the Coolhand API. It records every
 * request and answers with a per-path responder (longest matching path prefix), defaulting to
 * {@code 200 {}}.
 */
public final class FakeServer implements AutoCloseable {
    /** One recorded request. */
    public record Received(String method, String path, String rawQuery, Map<String, List<String>> headers, byte[] body) {
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public JsonNode json() {
            try {
                return Json.parse(text());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }

        /** Path plus query, as sent. */
        public String target() {
            return rawQuery == null ? path : path + "?" + rawQuery;
        }
    }

    /** Writes a response. */
    @FunctionalInterface
    public interface Responder {
        void respond(Received request, HttpExchange exchange) throws IOException;
    }

    private final HttpServer server;
    private final LinkedBlockingQueue<Received> received = new LinkedBlockingQueue<>();
    private final Map<String, Responder> routes = new ConcurrentHashMap<>();

    public FakeServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-server");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", exchange -> {
            try {
                byte[] body;
                try (InputStream in = exchange.getRequestBody()) {
                    body = in.readAllBytes();
                }
                Map<String, List<String>> headers = new LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, List.copyOf(v)));
                Received r = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                        exchange.getRequestURI().getRawQuery(), headers, body);
                received.add(r);
                Responder responder = route(r.path());
                try {
                    responder.respond(r, exchange);
                } catch (IOException e) {
                    // client went away mid-response; fine for tests that abandon streams
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private Responder route(String path) {
        String best = null;
        for (String prefix : routes.keySet()) {
            if (path.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best == null ? json(200, "{}") : routes.get(best);
    }

    public FakeServer on(String pathPrefix, Responder responder) {
        routes.put(pathPrefix, responder);
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public Received take() {
        return take(Duration.ofSeconds(10));
    }

    public Received take(Duration timeout) {
        try {
            Received r = received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (r == null) {
                throw new AssertionError("no request reached the fake server");
            }
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** The next request, or null if none arrives within {@code timeout}. */
    public Received poll(Duration timeout) {
        try {
            return received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public List<Received> drain() {
        List<Received> out = new ArrayList<>();
        received.drainTo(out);
        return out;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ responders

    public static Responder json(int status, String body) {
        return json(status, body, Map.of());
    }

    public static Responder json(int status, String body, Map<String, String> headers) {
        return (req, ex) -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
        };
    }

    public static Responder raw(int status, String contentType, byte[] body, Map<String, String> headers) {
        return (req, ex) -> {
            ex.getResponseHeaders().add("Content-Type", contentType);
            headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
        };
    }

    /** A {@code text/event-stream} response written one event at a time (chunked). */
    public static Responder sse(List<String> events, Duration gap) {
        return (req, ex) -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                for (String event : events) {
                    os.write(event.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    if (!gap.isZero()) {
                        try {
                            Thread.sleep(gap.toMillis());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        };
    }
}
