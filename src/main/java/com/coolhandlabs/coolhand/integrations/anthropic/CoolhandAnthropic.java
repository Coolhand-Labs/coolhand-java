package com.coolhandlabs.coolhand.integrations.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.HttpClient;
import com.anthropic.core.http.Interceptor;
import com.coolhandlabs.coolhand.Coolhand;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Builds Anthropic SDK clients whose calls are logged to Coolhand. Requires
 * {@code com.anthropic:anthropic-java} (3.5.0 or later) on the classpath, an optional dependency of
 * this library.
 *
 * <pre>{@code
 * AnthropicClient client = CoolhandAnthropic.client(b -> b.maxRetries(3));
 * }</pre>
 *
 * <p>Capture is installed as an SDK {@link Interceptor}, which wraps the transport below the SDK's
 * retry layer, so every attempt is logged (credential headers redacted). To build the client
 * yourself, add {@link #interceptor()} to {@code AnthropicOkHttpClient.builder()} or
 * {@code ClientOptions.builder()}.
 */
public final class CoolhandAnthropic {
    private CoolhandAnthropic() {}

    /** {@link #client(Consumer)} with no customisation. */
    public static AnthropicClient client() {
        return client(null, null);
    }

    /**
     * An {@link AnthropicClient} built with {@code AnthropicOkHttpClient.builder().fromEnv()}, then
     * {@code customizer} (e.g. {@code b -> b.maxRetries(3)}). When the global Coolhand client exists
     * ({@link Coolhand#global()}: {@code COOLHAND_API_KEY} is set, or {@code initializeGlobalMonitoring}
     * was called) the Coolhand interceptor is added; otherwise the client is returned unmonitored.
     */
    public static AnthropicClient client(Consumer<AnthropicOkHttpClient.Builder> customizer) {
        return client(null, customizer);
    }

    /** As {@link #client(Consumer)}, monitored by {@code coolhand} ({@code null}: the global client, if any). */
    public static AnthropicClient client(Coolhand coolhand, Consumer<AnthropicOkHttpClient.Builder> customizer) {
        Optional<Coolhand> monitor = coolhand != null ? Optional.of(coolhand) : Coolhand.global();
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().fromEnv();
        if (customizer != null) {
            customizer.accept(builder);
        }
        monitor.ifPresent(c -> builder.addInterceptor(interceptor(c)));
        return builder.build();
    }

    /** An interceptor logging through the global client, resolved per request (pass-through without one). */
    public static Interceptor interceptor() {
        return delegate -> new CoolhandAnthropicHttpClient(delegate, null);
    }

    /** An interceptor logging through {@code coolhand}. */
    public static Interceptor interceptor(Coolhand coolhand) {
        return delegate -> new CoolhandAnthropicHttpClient(delegate, coolhand);
    }

    /** {@code delegate} wrapped for the global client when one exists, else {@code delegate} unchanged. */
    public static HttpClient wrap(HttpClient delegate) {
        return Coolhand.global().<HttpClient>map(c -> new CoolhandAnthropicHttpClient(delegate, c)).orElse(delegate);
    }

    /** {@code delegate} wrapped for {@code coolhand}. */
    public static HttpClient wrap(HttpClient delegate, Coolhand coolhand) {
        return new CoolhandAnthropicHttpClient(delegate, coolhand);
    }
}
