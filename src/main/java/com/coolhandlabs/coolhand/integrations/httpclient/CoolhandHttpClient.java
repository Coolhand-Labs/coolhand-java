package com.coolhandlabs.coolhand.integrations.httpclient;

import com.coolhandlabs.coolhand.Coolhand;
import com.coolhandlabs.coolhand.interception.CaptureSession;
import com.coolhandlabs.coolhand.interception.ResponseCapture;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * A {@link java.net.http.HttpClient} that logs the LLM API calls it makes to Coolhand and otherwise
 * behaves exactly like the client it wraps.
 *
 * <pre>{@code
 * HttpClient http = CoolhandHttpClient.wrap(HttpClient.newHttpClient(), coolhand);
 * }</pre>
 *
 * <p>For a captured call the request body is read once (so it can be both logged and sent) and the
 * declared context is snapshotted on the calling thread. The response body is copied as the host's
 * {@link HttpResponse.BodyHandler} consumes it, whatever handler that is; the call is logged once the
 * body completes, or, if the host cancels early, with what arrived so far (streamed bodies) or after
 * reading the rest (other bodies). Requests that are not LLM calls pass straight through. Push
 * promises are passed through unmonitored.
 */
public final class CoolhandHttpClient extends HttpClient {
    private static final long REQUEST_BODY_READ_TIMEOUT_SECONDS = 60;
    private static final long MAX_DRAIN_BYTES = 50L * 1024 * 1024;

    private final HttpClient delegate;
    private final Coolhand coolhand;

    private CoolhandHttpClient(HttpClient delegate, Coolhand coolhand) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
        this.coolhand = coolhand;
    }

    /** {@code delegate}, monitored by the global client ({@link Coolhand#global()}) at request time. */
    public static HttpClient wrap(HttpClient delegate) {
        return new CoolhandHttpClient(delegate, null);
    }

    /** {@code delegate}, monitored by {@code coolhand}. */
    public static HttpClient wrap(HttpClient delegate, Coolhand coolhand) {
        return new CoolhandHttpClient(delegate, coolhand);
    }

    /** The wrapped client. */
    public HttpClient delegate() {
        return delegate;
    }

    private Coolhand resolve() {
        return coolhand != null ? coolhand : Coolhand.global().orElse(null);
    }

    // ------------------------------------------------------------------ capture

    private record Prepared(HttpRequest request, CaptureSession session) {}

    private Optional<Prepared> prepare(HttpRequest original) {
        Coolhand client;
        CaptureSession session;
        try {
            client = resolve();
            if (client == null) {
                return Optional.empty();
            }
            Optional<CaptureSession> begun = client.monitor().begin(original.method(), original.uri().toString(),
                    original.headers().map());
            if (begun.isEmpty()) {
                return Optional.empty();
            }
            session = begun.get();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        HttpRequest request = original;
        Optional<HttpRequest.BodyPublisher> publisher = original.bodyPublisher();
        if (publisher.isPresent() && publisher.get().contentLength() != 0) {
            byte[] bytes = readBody(publisher.get());
            if (bytes != null) {
                session.requestBody(bytes);
                request = HttpRequest.newBuilder(original, (name, value) -> true)
                        .method(original.method(), HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build();
            }
        }
        return Optional.of(new Prepared(request, session));
    }

    /** Reads a body publisher fully; {@code null} if it can't be read (the request is then sent as is). */
    private static byte[] readBody(HttpRequest.BodyPublisher publisher) {
        CompletableFuture<byte[]> done = new CompletableFuture<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                out.write(chunk, 0, chunk.length);
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(out.toByteArray());
            }
        });
        try {
            return done.get(REQUEST_BODY_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        }
    }

    private static <T> HttpResponse.BodyHandler<T> tee(HttpResponse.BodyHandler<T> host, CaptureSession session) {
        return info -> {
            ResponseCapture capture = session.response(info.statusCode(), info.headers().map());
            return new TeeSubscriber<>(host.apply(info), capture);
        };
    }

    /** Forwards everything to the host's subscriber while copying the bytes into the capture. */
    private static final class TeeSubscriber<T> implements HttpResponse.BodySubscriber<T> {
        private final HttpResponse.BodySubscriber<T> host;
        private final ResponseCapture capture;
        private final AtomicBoolean hostCancelled = new AtomicBoolean();
        private final AtomicLong drained = new AtomicLong();
        private volatile Flow.Subscription upstream;

        TeeSubscriber(HttpResponse.BodySubscriber<T> host, ResponseCapture capture) {
            this.host = host;
            this.capture = capture;
        }

        @Override
        public CompletionStage<T> getBody() {
            return host.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            upstream = subscription;
            host.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    capture.touch();
                    subscription.request(n);
                }

                @Override
                public void cancel() {
                    if (!hostCancelled.compareAndSet(false, true)) {
                        return;
                    }
                    if (capture.isFinished() || capture.isStreaming()) {
                        capture.abandoned();
                        subscription.cancel();
                    } else {
                        // A finite body the host stopped reading early (e.g. a parser that stops at the
                        // end of the JSON value): read the rest for the log, bounded by the capture cap.
                        subscription.request(Long.MAX_VALUE);
                    }
                }
            });
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                ByteBuffer copy = item.duplicate();
                byte[] bytes = new byte[copy.remaining()];
                copy.get(bytes);
                capture.onBytes(bytes, 0, bytes.length);
                if (hostCancelled.get() && drained.addAndGet(bytes.length) > MAX_DRAIN_BYTES) {
                    capture.abandoned();
                    Flow.Subscription s = upstream;
                    if (s != null) {
                        s.cancel();
                    }
                    return;
                }
            }
            if (!hostCancelled.get()) {
                host.onNext(items);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            capture.abandoned();
            if (!hostCancelled.get()) {
                host.onError(throwable);
            }
        }

        @Override
        public void onComplete() {
            capture.complete();
            if (!hostCancelled.get()) {
                host.onComplete();
            }
        }
    }

    // ------------------------------------------------------------------ HttpClient

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
        Optional<Prepared> prepared = prepare(request);
        if (prepared.isEmpty()) {
            return delegate.send(request, responseBodyHandler);
        }
        CaptureSession session = prepared.get().session();
        try {
            return delegate.send(prepared.get().request(), tee(responseBodyHandler, session));
        } catch (IOException | InterruptedException | RuntimeException e) {
            session.failed(e);
            throw e;
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler) {
        return sendAsync(request, responseBodyHandler, null);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        Optional<Prepared> prepared = prepare(request);
        if (prepared.isEmpty()) {
            return pushPromiseHandler == null ? delegate.sendAsync(request, responseBodyHandler)
                    : delegate.sendAsync(request, responseBodyHandler, pushPromiseHandler);
        }
        CaptureSession session = prepared.get().session();
        HttpResponse.BodyHandler<T> handler = tee(responseBodyHandler, session);
        CompletableFuture<HttpResponse<T>> future = pushPromiseHandler == null
                ? delegate.sendAsync(prepared.get().request(), handler)
                : delegate.sendAsync(prepared.get().request(), handler, pushPromiseHandler);
        future.whenComplete((response, error) -> {
            if (error != null) {
                session.failed(error instanceof CompletionException && error.getCause() != null ? error.getCause() : error);
            }
        });
        return future;
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
        return delegate.newWebSocketBuilder();
    }

    // Lifecycle methods added to HttpClient in Java 21. Declared without @Override so this class
    // compiles for Java 17; on 21+ they override and forward to the delegate.

    /** Closes the delegate (Java 21+; a no-op on older runtimes, where HttpClient has no close). */
    public void close() {
        if (delegate instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Forwards to the delegate's {@code shutdown()} (Java 21+). */
    public void shutdown() {
        invoke("shutdown");
    }

    /** Forwards to the delegate's {@code shutdownNow()} (Java 21+). */
    public void shutdownNow() {
        invoke("shutdownNow");
    }

    /** Forwards to the delegate's {@code isTerminated()} (Java 21+); false on older runtimes. */
    public boolean isTerminated() {
        Object result = invoke("isTerminated");
        return result instanceof Boolean b && b;
    }

    /** Forwards to the delegate's {@code awaitTermination(Duration)} (Java 21+); true on older runtimes. */
    public boolean awaitTermination(Duration duration) throws InterruptedException {
        try {
            Method m = HttpClient.class.getMethod("awaitTermination", Duration.class);
            return (Boolean) m.invoke(delegate, duration);
        } catch (NoSuchMethodException e) {
            return true;
        } catch (ReflectiveOperationException e) {
            if (e.getCause() instanceof InterruptedException ie) {
                throw ie;
            }
            throw new IllegalStateException(e.getCause() != null ? e.getCause() : e);
        }
    }

    private Object invoke(String name) {
        try {
            return HttpClient.class.getMethod(name).invoke(delegate);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(cause);
        }
    }

    @Override
    public String toString() {
        return "CoolhandHttpClient[" + delegate + "]";
    }
}
