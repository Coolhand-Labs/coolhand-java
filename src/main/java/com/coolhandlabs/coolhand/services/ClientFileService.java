package com.coolhandlabs.coolhand.services;

import com.coolhandlabs.coolhand.internal.JsText;
import com.coolhandlabs.coolhand.internal.Json;
import com.coolhandlabs.coolhand.model.ClientFile;
import com.coolhandlabs.coolhand.model.ClientFileUpload;
import com.coolhandlabs.coolhand.model.DownloadedClientFile;
import com.coolhandlabs.coolhand.model.ListClientFilesParams;
import com.coolhandlabs.coolhand.model.Page;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client files (slide decks, reports, documents) at {@code /api/v2/client_files}. Uploading needs the
 * private key; listing, downloading and status changes need a client admin's per-user key.
 */
public final class ClientFileService extends BaseService {
    private static final Pattern EXTENDED_FILENAME =
            Pattern.compile("(?:^|[;\\s])filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAIN_FILENAME =
            Pattern.compile("(?:^|[;\\s])filename\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^;]+))", Pattern.CASE_INSENSITIVE);

    public ClientFileService(ServiceConfig config) {
        super(config, "/api/v2/client_files");
    }

    private static boolean hasBracketOrControlChar(String key) {
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '[' || c == ']' || c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    static String filenameFromContentDisposition(String header) {
        if (header == null || header.isEmpty()) {
            return null;
        }
        Matcher extended = EXTENDED_FILENAME.matcher(header);
        if (extended.find()) {
            try {
                return JsText.decode(JsText.trim(extended.group(1)), false);
            } catch (IllegalArgumentException ignored) {
                // fall back to the plain parameter
            }
        }
        Matcher plain = PLAIN_FILENAME.matcher(header);
        if (!plain.find()) {
            return null;
        }
        String value = plain.group(1) != null ? plain.group(1).replaceAll("\\\\(.)", "$1") : JsText.trim(plain.group(2));
        return value.isEmpty() ? null : value;
    }

    private static final class Multipart {
        final String boundary = "----coolhand-java-" + UUID.randomUUID();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void field(String name, String value) {
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + escape(name) + "\"\r\n\r\n");
            write(value);
            write("\r\n");
        }

        void file(String name, String filename, byte[] content) {
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + escape(name) + "\"; filename=\""
                    + escape(filename) + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
            out.write(content, 0, content.length);
            write("\r\n");
        }

        byte[] finish() {
            write("--" + boundary + "--\r\n");
            return out.toByteArray();
        }

        private static String escape(String s) {
            return s.replace("\r", "%0D").replace("\n", "%0A").replace("\"", "%22");
        }

        private void write(String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
        }
    }

    /**
     * Uploads a file (always as {@code draft}). Never throws for API or network failures: resolves to
     * empty instead, and on a dry run.
     *
     * @throws IllegalArgumentException for a metadata key that is empty or contains brackets or control characters
     */
    public CompletableFuture<Optional<ClientFile>> createClientFileAsync(ClientFileUpload payload) {
        Multipart form = new Multipart();
        form.field("client_file[name]", payload.name());
        if (payload.fileType() != null && !payload.fileType().isEmpty()) {
            form.field("client_file[file_type]", payload.fileType());
        }
        if (payload.description() != null && !payload.description().isEmpty()) {
            form.field("client_file[description]", payload.description());
        }
        form.file("client_file[file]", payload.filename(), payload.file());
        if (payload.metadata() != null) {
            for (Map.Entry<String, Object> e : payload.metadata().entrySet()) {
                String key = e.getKey();
                if (key == null || key.isEmpty() || hasBracketOrControlChar(key)) {
                    throw new IllegalArgumentException("Invalid client file metadata key " + Json.stringify(key)
                            + ": keys must be non-empty and must not contain \"[\", \"]\" or control characters");
                }
                Object value = e.getValue();
                form.field("client_file[metadata][" + key + "]",
                        value instanceof String s ? s : Json.stringify(value));
            }
        }
        return sendMultipartAsync(form.finish(), form.boundary, "✅ Successfully uploaded client file: " + payload.name())
                .thenApply(result -> {
                    logSeparator();
                    return result.filter(n -> n.isObject()).map(ClientFile::fromJson);
                });
    }

    public Optional<ClientFile> createClientFile(ClientFileUpload payload) {
        return await(createClientFileAsync(payload));
    }

    public CompletableFuture<Page<ClientFile>> listClientFilesAsync(ListClientFilesParams params) {
        ListClientFilesParams p = params == null ? ListClientFilesParams.NONE : params;
        Query q = new Query().set("status", p.status()).set("file_type", p.fileType()).set("page", p.page()).set("per", p.per());
        return listPage(q.appendTo(apiEndpoint), "Client file", p.page(), p.per())
                .thenApply(page -> new Page<>(page.items().stream().map(ClientFile::fromJson).toList(), page.pagination()));
    }

    public Page<ClientFile> listClientFiles(ListClientFilesParams params) {
        return await(listClientFilesAsync(params));
    }

    /** The file's raw bytes, never JSON-decoded. */
    public CompletableFuture<DownloadedClientFile> downloadClientFileAsync(String id) {
        String url = buildResourceUrl(id, "downloadClientFile: id must be a non-empty string") + "/content";
        return fetchBytes(request(url).GET().build(), "Client file request failed").thenApply(r ->
                new DownloadedClientFile(r.bytes(), r.headers().firstValue("content-type"),
                        Optional.ofNullable(filenameFromContentDisposition(
                                r.headers().firstValue("content-disposition").orElse(null)))));
    }

    public DownloadedClientFile downloadClientFile(String id) {
        return await(downloadClientFileAsync(id));
    }

    /** Sets {@code draft}, {@code published} or {@code archived}; empty on a dry run. */
    public CompletableFuture<Optional<ClientFile>> updateClientFileStatusAsync(String id, String status) {
        String url = buildResourceUrl(id, "updateClientFileStatus: id must be a non-empty string");
        if (dryRun) {
            log.info("🚫 DRY RUN: Skipping updateClientFileStatus for client file " + id);
            return CompletableFuture.completedFuture(Optional.empty());
        }
        HttpRequest request = request(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        Json.stringify(Map.of("client_file", Map.of("status", status))), StandardCharsets.UTF_8))
                .build();
        return fetchOrThrow(request, "Client file request failed")
                .thenApply(text -> Optional.of(ClientFile.fromJson(parseJsonObject(text, "Client file"))));
    }

    public Optional<ClientFile> updateClientFileStatus(String id, String status) {
        return await(updateClientFileStatusAsync(id, status));
    }
}
