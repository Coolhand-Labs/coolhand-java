package com.coolhandlabs.coolhand.integrations.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.client.okhttp.OkHttpClient;
import com.openai.core.ClientOptions;
import com.openai.core.http.HttpClient;
import com.coolhandlabs.coolhand.Coolhand;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Builds OpenAI SDK clients whose calls are logged to Coolhand. Requires
 * {@code com.openai:openai-java} on the classpath (an optional dependency of this library).
 *
 * <pre>{@code
 * OpenAIClient client = CoolhandOpenAI.client(b -> b.maxRetries(3));
 * }</pre>
 */
public final class CoolhandOpenAI {
    private CoolhandOpenAI() {}

    /** {@link #client(Consumer)} with no customisation. */
    public static OpenAIClient client() {
        return client(null, b -> {});
    }

    /**
     * An {@link OpenAIClient} configured with {@code ClientOptions.builder().fromEnv()} and then
     * {@code customizer}, sending through the SDK's OkHttp client. When the global Coolhand client
     * exists ({@link Coolhand#global()}: {@code COOLHAND_API_KEY} set, or
     * {@code initializeGlobalMonitoring} called) that OkHttp client is wrapped in a
     * {@link CoolhandOpenAIHttpClient}; otherwise the OpenAI client is returned unmonitored.
     */
    public static OpenAIClient client(Consumer<ClientOptions.Builder> customizer) {
        return client(null, customizer);
    }

    /** As {@link #client(Consumer)}, monitored by {@code coolhand} ({@code null}: the global client, if any). */
    public static OpenAIClient client(Coolhand coolhand, Consumer<ClientOptions.Builder> customizer) {
        Optional<Coolhand> monitor = coolhand != null ? Optional.of(coolhand) : Coolhand.global();
        HttpClient http = OkHttpClient.builder().build();
        if (monitor.isPresent()) {
            http = new CoolhandOpenAIHttpClient(http, monitor.get());
        }
        ClientOptions.Builder builder = ClientOptions.builder().httpClient(http).fromEnv();
        if (customizer != null) {
            customizer.accept(builder);
        }
        return new OpenAIClientImpl(builder.build());
    }

    /** {@code delegate} wrapped for the global client when one exists, else {@code delegate} unchanged. */
    public static HttpClient wrap(HttpClient delegate) {
        return Coolhand.global().<HttpClient>map(c -> new CoolhandOpenAIHttpClient(delegate, c)).orElse(delegate);
    }

    /** {@code delegate} wrapped for {@code coolhand}. */
    public static HttpClient wrap(HttpClient delegate, Coolhand coolhand) {
        return new CoolhandOpenAIHttpClient(delegate, coolhand);
    }
}
