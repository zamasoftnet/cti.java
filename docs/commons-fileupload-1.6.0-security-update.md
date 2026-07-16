# Commons FileUpload 1.6.0 security update

## Summary

`cti-server-rest` upgrades Apache Commons FileUpload from 1.4 to 1.6.0 and applies explicit limits to every multipart request handled by `RestRequest`.

This addresses the following denial-of-service issues:

- [CVE-2023-24998](https://commons.apache.org/proper/commons-fileupload/security): Commons FileUpload before 1.5 had no configurable request-part count limit. FileUpload 1.6.0 supplies `setFileCountMax`, but its streaming `getItemIterator()` path does not enforce that setting ([FILEUPLOAD-360](https://issues.apache.org/jira/browse/FILEUPLOAD-360)), so `RestRequest` also counts every file and form-field part before accepting it.
- [CVE-2025-48976](https://commons.apache.org/proper/commons-fileupload/security): Commons FileUpload 1.x before 1.6.0 used a fixed 10 KiB multipart-part header allowance. Version 1.6.0 adds `setPartHeaderSizeMax`; this project explicitly keeps Apache's secure 512-byte default.

## Limits

No previous REST upload-size limit or production-size requirement was present in this repository. The byte limits below are therefore conservative interim defaults. They retain substantially more capacity for streamed document data than for in-memory form fields.

| Limit | Default | System property | Rationale |
| --- | ---: | --- | --- |
| All multipart parts | 128 | `jp.cssj.server.rest.multipart.maxParts` | Counts files and form fields. Existing REST driver requests normally contain a small set of metadata fields and one document/resource; 128 also leaves room for the documented multi-resource request form. |
| Headers for each part | 512 bytes | `jp.cssj.server.rest.multipart.maxPartHeaderBytes` | Apache 1.6.0 secure default. It accommodates the driver-generated `Content-Disposition` and `Content-Type` headers without returning to the vulnerable 10 KiB allowance. Configurable values are capped at 8 KiB. |
| Complete multipart request | 512 MiB | `jp.cssj.server.rest.multipart.maxRequestBytes` | Larger than the single-file allowance and bounded even when Content-Length is absent. |
| Each file part | 256 MiB | `jp.cssj.server.rest.multipart.maxFileBytes` | Keeps document/resource input streamed while placing a finite ceiling on a single upload. |
| Each form-field part | 1 MiB | `jp.cssj.server.rest.multipart.maxFormFieldBytes` | Form fields are materialized as byte arrays by the existing REST design, so their allowance is much smaller than a file's. |

All properties are positive base-10 byte/count values supplied as JVM `-D` options. `maxRequestBytes` must be greater than `maxFileBytes`, and `maxFormFieldBytes` must be smaller than `maxFileBytes`. Invalid values fail initialization instead of silently disabling a limit. Change a value only after checking representative production documents and the servlet container's own request limits.

## Enforcement and error handling

`ServletFileUpload` is created in one helper which always calls `setFileCountMax`, `setPartHeaderSizeMax`, `setSizeMax`, and `setFileSizeMax`. The application additionally:

- uses a `long` counter for every item returned by the streaming iterator because FileUpload 1.6.0 does not enforce `fileCountMax` in `getItemIterator()`;
- copies multipart form fields with a fixed 8 KiB buffer and stops after detecting the first byte beyond the field allowance;
- wraps each file stream with a second bounded stream while retaining FileUpload's streaming file-size enforcement;
- closes form-field and file streams with try-with-resources, including exceptional paths; and
- returns the existing generic REST error body with HTTP 413 for all size/count/header limits, HTTP 400 for malformed multipart, and HTTP 500 for multipart I/O failures. Exception messages, paths, configured values, and request content are not copied into the response.

## Verification

The automated `cti-server-rest` tests cover:

- normal multipart fields, multiple fields, and a file upload;
- part count at the limit, mixed parts at limit + 1, and form-field-only parts at limit + 1;
- part headers at the limit and at limit + 1;
- single-file, complete-request, and form-field limit + 1 cases;
- a complete-request overrun without Content-Length (chunked-equivalent input);
- a missing/malformed multipart boundary;
- stopping subsequent processing after a limit failure;
- closing a file stream after an exceptional bounded read; and
- safe HTTP 413 and 400 mappings without leaking exception details.

Commands used for local verification:

```text
gradlew.bat :cti-server-rest:compileJava
gradlew.bat :cti-server-rest:test
gradlew.bat :cti-server-rest:dependencyInsight --dependency commons-fileupload --configuration runtimeClasspath
gradlew.bat :cti-server-rest:dependencyInsight --dependency commons-io --configuration runtimeClasspath
gradlew.bat :cti-server-rest:dependencies --configuration runtimeClasspath
gradlew.bat :cti-server-rest:build
gradlew.bat build
```

The Copper PDF server-dependent integration flow is not run by the unit tests and must be exercised in a dedicated local test environment before deployment. Do not send limit-probing traffic to a production or staging service.

## Rollback

Revert the `cti-server-rest` dependency and the multipart-limit changes together, then rebuild and redeploy through the normal release process. Returning to FileUpload 1.4 reintroduces CVE-2023-24998 and CVE-2025-48976 and is not a safe long-term rollback; prefer correcting an overly strict system-property value while retaining 1.6.0.

## Residual risks

- Because FileUpload's streaming iterator parses the next part header during `hasNext()`, the application part-count check can reject the extra part only after that header is parsed. The independent 512-byte header limit bounds this work and the body is not processed.
- Application limits cover multipart input. Servlet-container limits remain responsible for URL-encoded forms, raw non-multipart request bodies, connection timeouts, and transport-level slow-client attacks.
- The chosen size defaults are interim because this repository contains no authoritative maximum production document size. Monitor legitimate 413 responses and adjust the JVM properties deliberately rather than disabling a limit.
- Apache [FILEUPLOAD-368](https://issues.apache.org/jira/browse/FILEUPLOAD-368) reports resource retention with FileUpload 1.6.0's disk-backed parse API when applications fail to close item streams. `RestRequest` uses the streaming API (no `DiskFileItem` temporary files) and explicitly closes every opened part stream, but server-level repeated-load monitoring of descriptors and temporary storage is still recommended.
