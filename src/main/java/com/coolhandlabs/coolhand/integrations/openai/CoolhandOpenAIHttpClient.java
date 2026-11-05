package com.coolhandlabs.coolhand.integrations.openai;

import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;
import com.coolhandlabs.coolhand.Coolhand;
import com.coolhandlabs.coolhand.interception.CaptureSession;
import com.coolhandlabs.coolhand.interception.ResponseCapture;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * An OpenAI Java SDK {@link HttpClient} that logs every LLM call it makes to Coolhand, then behaves
 * exactly like the {@code delegate} it wraps. The request body is buffered so it can be both logged
 * and sent, the declared context is snapshotted on the calling thread, and the response body is teed
 * as the SDK reads it (streamed responses included) and logged once it is complete.
 */
public final class CoolhandOpenAIHttpClient implements HttpClient {
    private final HttpClient delegate;
    private final Coolhand coolhand;

    /** Wraps {@code delegate}, logging through the global client ({@link Coolhand#global()}) per request. */
    public CoolhandOpenAIHttpClient(HttpClient delegate) {
        this(delegate, null);
    }

    /** Wraps {@code delegate}, logging through {@code coolhand} ({@code null}: the global client). */
    public CoolhandOpenAIHttpClient(HttpClient delegate, Coolhand coolhand) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
        this.coolhand = coolhand;
    }

    public HttpClient delegate() {
        return delegate;
    }

    private record Prepared(HttpRequest request, CaptureSession session) {}

    private Optional<Prepared> prepare(HttpRequest original) {
        CaptureSession session;
        try {
            Coolhand client = coolhand != null ? coolhand : Coolhand.global().orElse(null);
            if (client == null) {
                return Optional.empty();
            }
            Optional<CaptureSession> begun = client.monitor().begin(original.method().name(), original.url(),
                    headerMap(original.headers()));
            if (begun.isEmpty()) {
                return Optional.empty();
            }
            session = begun.get();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        HttpRequest request = original;
        HttpRequestBody body = original.body();
        if (body != null) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                body.writeTo(out);
                byte[] bytes = out.toByteArray();
                session.requestBody(bytes);
                request = original.toBuilder().body(new BufferedBody(bytes, body.contentType())).build();
            } catch (RuntimeException e) {
                request = original; // capture must never break the call
            }
        }
        return Optional.of(new Prepared(request, session));
    }

    /** A request body held in memory, so it can be written for the log and again for the wire. */
    private static final class BufferedBody implements HttpRequestBody {
        private final byte[] bytes;
        private final String contentType;

        BufferedBody(byte[] bytes, String contentType) {
            this.bytes = bytes;
            this.contentType = contentType;
        }

        @Override
        public void writeTo(java.io.OutputStream out) {
            try {
                out.write(bytes);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public String contentType() {
            return contentType;
        }

        @Override
        public long contentLength() {
            return bytes.length;
        }

        @Override
        public boolean repeatable() {
            return true;
        }

        @Override
        public void close() {}
    }

    static Map<String, List<String>> headerMap(Headers headers) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (headers != null) {
            for (String name : headers.names()) {
                out.put(name, headers.values(name));
            }
        }
        return out;
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
        Optional<Prepared> prepared = prepare(request);
        if (prepared.isEmpty()) {
            return delegate.execute(request, requestOptions);
        }
        HttpResponse response;
        try {
            response = delegate.execute(prepared.get().request(), requestOptions);
        } catch (RuntimeException e) {
            prepared.get().session().failed(e);
            throw e;
        }
        return wrap(response, prepared.get().session());
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions requestOptions) {
        Optional<Prepared> prepared = prepare(request);
        if (prepared.isEmpty()) {
            return delegate.executeAsync(request, requestOptions);
        }
        CaptureSession session = prepared.get().session();
        CompletableFuture<HttpResponse> future;
        try {
            future = delegate.executeAsync(prepared.get().request(), requestOptions);
        } catch (RuntimeException e) {
            session.failed(e);
            throw e;
        }
        return future.handle((response, error) -> {
            if (error != null) {
                session.failed(error instanceof CompletionException && error.getCause() != null ? error.getCause() : error);
                throw error instanceof CompletionException ce ? ce : new CompletionException(error);
            }
            return wrap(response, session);
        });
    }

    private static HttpResponse wrap(HttpResponse response, CaptureSession session) {
        try {
            return new TeeResponse(response, session.response(response.statusCode(), headerMap(response.headers())));
        } catch (RuntimeException e) {
            return response;
        }
    }

    @Override
    public void close() {
        delegate.close();
    }

    /** The SDK's response with its body copied into the capture as it is read. */
    private static final class TeeResponse implements HttpResponse {
        private final HttpResponse original;
        private final ResponseCapture capture;
        private InputStream body;

        TeeResponse(HttpResponse original, ResponseCapture capture) {
            this.original = original;
            this.capture = capture;
        }

        @Override
        public int statusCode() {
            return original.statusCode();
        }

        @Override
        public Headers headers() {
            return original.headers();
        }

        @Override
        public synchronized InputStream body() {
            if (body == null) {
                body = capture.wrap(original.body());
            }
            return body;
        }

        @Override
        public void close() {
            try {
                body().close();
            } catch (IOException | RuntimeException ignored) {
                // closing the original below is what matters to the SDK
            } finally {
                original.close();
            }
        }
    }
}
