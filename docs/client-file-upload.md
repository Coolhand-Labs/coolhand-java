# Client Files

Slide decks, reports and documents stored with Coolhand.

## Uploading (private API key)

```java
Optional<ClientFile> uploaded = coolhand.uploadClientFile(new ClientFileUpload(
        "Q3 review",                       // name
        "slide_deck",                      // file_type: slide_deck, report or document (optional)
        "Quarterly review deck",           // description (optional)
        Files.readAllBytes(Path.of("q3.pdf")),
        "q3.pdf",                          // filename sent with the file part
        Map.of("quarter", "Q3", "pages", 12)));   // metadata (optional); non-strings sent as JSON text
```

- `multipart/form-data` to `POST /api/v2/client_files` with `client_file[name]`,
  `client_file[file_type]`, `client_file[description]`, `client_file[file]` and
  `client_file[metadata][<key>]` parts. Uploads always land as `draft`.
- Metadata keys must be non-empty and free of `[`, `]` and control characters
  (`IllegalArgumentException` otherwise).
- Like `createFeedback`, never throws for API or network failures: empty instead (and in dry-run
  mode). Uploads get a 10-minute timeout.

## Administering (a client admin's per-user key, `ch_priv_...`)

```java
Page<ClientFile> page = coolhand.listClientFiles(new ListClientFilesParams("published", "report", 1, 25));
DownloadedClientFile file = coolhand.downloadClientFile("cf_1");   // bytes, contentType, filename
Optional<ClientFile> updated = coolhand.updateClientFileStatus("cf_1", "archived");
```

- `listClientFiles` returns every status, newest first; pagination from the response headers.
- `downloadClientFile` returns the raw bytes, never decoded, plus the `Content-Type` and the
  filename from `Content-Disposition` (RFC 5987 `filename*` preferred).
- `updateClientFileStatus` sets `draft`, `published` or `archived` from any status; re-setting is a
  no-op. Empty in dry-run mode.
- These throw `CoolhandHttpException`: `401` missing/invalid key, `403` not a client admin's key,
  `404` unknown id, `422` unknown filter or status.
