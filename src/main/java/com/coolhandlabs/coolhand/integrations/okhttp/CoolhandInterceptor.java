package com.coolhandlabs.coolhand.integrations.okhttp;

import com.coolhandlabs.coolhand.Coolhand;
import com.coolhandlabs.coolhand.DeclaredContext;
import com.coolhandlabs.coolhand.interception.CaptureSession;
import com.coolhandlabs.coolhand.interception.ResponseCapture;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.Okio;

/**
 * An OkHttp {@link Interceptor} that logs the LLM API calls going through a client to Coolhand.
 *
 * <pre>{@code
 * OkHttpClient http = new OkHttpClient.Builder().addInterceptor(new CoolhandInterceptor(coolhand)).build();
 * }</pre>
 *
 * <p>Add it as an application interceptor ({@code addInterceptor}), so each call is logged once with
 * the body the application sees. The declared context is read from the request's
 * {@code DeclaredContext} tag when present (see {@link #tagCurrentContext(Request.Builder)}, needed for
 * {@code enqueue}, whose interceptors run on OkHttp's dispatcher threads), otherwise from the thread
 * running the interceptor. Duplex request bodies are not captured.
 */
public final class CoolhandInterceptor implements Interceptor {
    private final Coolhand coolhand;

    /** Logs through the global client ({@link Coolhand#global()}), resolved per request. */
    public CoolhandInterceptor() {
        this(null);
    }

    public CoolhandInterceptor(Coolhand coolhand) {
        this.coolhand = coolhand;
    }

    /**
     * Tags the request with the declared context active on this thread, so a call sent with
     * {@code enqueue} carries it.
     */
    public static Request.Builder tagCurrentContext(Request.Builder builder) {
        return builder.tag(DeclaredContext.class, Coolhand.currentContext().orElse(null));
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Optional<CaptureSession> begun = Optional.empty();
        try {
            Coolhand client = coolhand != null ? coolhand : Coolhand.global().orElse(null);
            if (client != null) {
                DeclaredContext tagged = request.tag(DeclaredContext.class);
                DeclaredContext declared = tagged != null ? tagged : Coolhand.currentContext().orElse(null);
                Map<String, List<String>> headers = new LinkedHashMap<>(request.headers().toMultimap());
                RequestBody requestBody = request.body();
                if (requestBody != null && requestBody.contentType() != null && request.header("Content-Type") == null) {
                    // what BridgeInterceptor will send; application interceptors run before it adds the header
                    headers.put("content-type", List.of(requestBody.contentType().toString()));
                }
                begun = client.monitor().begin(request.method(), request.url().toString(), headers, declared);
            }
        } catch (RuntimeException e) {
            begun = Optional.empty();
        }
        if (begun.isEmpty()) {
            return chain.proceed(request);
        }
        CaptureSession session = begun.get();
        RequestBody body = request.body();
        if (body != null && !body.isDuplex()) {
            try {
                Buffer buffer = new Buffer();
                body.writeTo(buffer);
                byte[] bytes = buffer.readByteArray();
                session.requestBody(bytes);
                if (body.isOneShot()) {
                    request = request.newBuilder().method(request.method(), RequestBody.create(bytes, body.contentType())).build();
                }
            } catch (IOException | RuntimeException e) {
                // capture must never break the call; a one-shot body that failed to copy is sent as is
            }
        }
        Response response;
        try {
            response = chain.proceed(request);
        } catch (IOException | RuntimeException e) {
            session.failed(e);
            throw e;
        }
        try {
            ResponseCapture capture = session.response(response.code(), response.headers().toMultimap());
            ResponseBody original = response.body();
            if (original == null) {
                capture.complete();
                return response;
            }
            InputStream tee = capture.wrap(original.byteStream());
            ResponseBody wrapped = ResponseBody.create(Okio.buffer(Okio.source(tee)), original.contentType(),
                    original.contentLength());
            return response.newBuilder().body(wrapped).build();
        } catch (RuntimeException e) {
            return response;
        }
    }
}
