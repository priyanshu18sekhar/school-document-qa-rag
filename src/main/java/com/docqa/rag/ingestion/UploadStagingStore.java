package com.docqa.rag.ingestion;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * Holds uploaded bytes on disk between the HTTP response and the ingestion
 * worker picking them up.
 *
 * <p>The problem this solves: the upload handler returns 202 immediately, so the
 * bytes have to outlive the request. Keeping them in the queued task's memory
 * would mean a full queue holds {@code queue-capacity * max-file-size} in heap -
 * with the defaults, 100 x 20 MB = 2 GB - which turns a burst of uploads into an
 * OutOfMemoryError. Disk is the right place for bulk bytes that are read once.
 *
 * <p>The SHA-256 is computed <em>while streaming to disk</em>, in the same pass.
 * Reading the multipart stream twice is not possible without buffering it
 * anyway, so digesting inline is both faster and the only option that does not
 * reintroduce the memory problem.
 *
 * <p>Staged files are deleted after ingestion in a {@code finally} block. Because
 * "finally" does not survive {@code kill -9}, a startup sweep removes anything
 * older than the retention window - otherwise a crash slowly fills the disk.
 */
@Component
public class UploadStagingStore {

    private static final Logger log = LoggerFactory.getLogger(UploadStagingStore.class);
    private static final String PREFIX = "docqa-upload-";
    private static final Duration STALE_AFTER = Duration.ofHours(6);

    private final Path directory;

    public UploadStagingStore() {
        this.directory = Path.of(System.getProperty("java.io.tmpdir"), "docqa-staging");
    }

    UploadStagingStore(Path directory) {
        this.directory = directory;
    }

    @PostConstruct
    void prepare() throws IOException {
        Files.createDirectories(directory);
        sweepStaleFiles();
    }

    /** A staged upload: bytes on disk, plus the facts we needed anyway. */
    public record StagedUpload(Path path, String sha256, long sizeBytes) {}

    public StagedUpload stage(InputStream source) throws IOException {
        Path target = Files.createTempFile(directory, PREFIX, ".tmp");
        MessageDigest digest = sha256Digest();
        long size;
        try (DigestInputStream digesting = new DigestInputStream(source, digest);
             OutputStream out = Files.newOutputStream(target)) {
            size = digesting.transferTo(out);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
        return new StagedUpload(target, HexFormat.of().formatHex(digest.digest()), size);
    }

    public byte[] read(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    public void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete staged upload {}: {}", path.getFileName(), e.getMessage());
        }
    }

    private void sweepStaleFiles() {
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        try (Stream<Path> files = Files.list(directory)) {
            long removed = files
                    .filter(path -> path.getFileName().toString().startsWith(PREFIX))
                    .filter(path -> isOlderThan(path, cutoff))
                    .peek(this::deleteQuietly)
                    .count();
            if (removed > 0) {
                log.info("Removed {} stale staged upload(s) left by a previous run", removed);
            }
        } catch (IOException e) {
            log.warn("Could not sweep the staging directory: {}", e.getMessage());
        }
    }

    private static boolean isOlderThan(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JLS and must be present", e);
        }
    }
}
