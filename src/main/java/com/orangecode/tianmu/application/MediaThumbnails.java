package com.orangecode.tianmu.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/** Versioned derivatives; callers must authorize the original before consulting this cache. */
@Service
public class MediaThumbnails {
    public static final Set<Integer> WIDTHS = Set.of(320, 640, 960);
    private static final long MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    private static final int CACHE_BYTES = 16 * 1024 * 1024;
    private final String ffmpeg;
    private final Semaphore slots = new Semaphore(2);
    private final Map<String, byte[]> cache = new LinkedHashMap<>(32, .75f, true);
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();
    private int cachedBytes;
    @FunctionalInterface public interface Source { InputStream open() throws Exception; }
    public MediaThumbnails(@Value("${TIANMU_FFMPEG_BINARY:ffmpeg}") String ffmpeg) { this.ffmpeg = ffmpeg; }

    /** Null means unsupported/temporarily unavailable: the original remains usable. */
    public byte[] get(String revision, int width, long sourceBytes, Source source) {
        if (!WIDTHS.contains(width) || sourceBytes < 1 || sourceBytes > MAX_SOURCE_BYTES) return null;
        String key = revision + ":webp-v1:" + width;
        synchronized (cache) { byte[] hit = cache.get(key); if (hit != null) return hit; }
        if (pending.size() >= 64) return null;
        var future = new CompletableFuture<byte[]>();
        var existing = pending.putIfAbsent(key, future);
        if (existing != null) {
            try { return existing.get(12, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
            catch (Exception e) { return null; }
        }
        byte[] result = null; boolean acquired = false;
        try {
            acquired = slots.tryAcquire(3, TimeUnit.SECONDS);
            if (acquired) result = generate(width, source);
            if (result != null) synchronized (cache) {
                while (cachedBytes + result.length > CACHE_BYTES && !cache.isEmpty()) {
                    var oldest = cache.entrySet().iterator(); var entry = oldest.next(); cachedBytes -= entry.getValue().length; oldest.remove();
                }
                cache.put(key, result); cachedBytes += result.length;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) { /* A failed derivative must not break an existing cover. */ }
        finally { if (acquired) slots.release(); future.complete(result); pending.remove(key, future); }
        return result;
    }

    private byte[] generate(int width, Source source) throws Exception {
        Path dir = Files.createTempDirectory("tianmu-thumbnail-");
        Path input = dir.resolve("source.image"), output = dir.resolve("cover.webp");
        try {
            try (InputStream stream = source.open(); var file = Files.newOutputStream(input)) {
                byte[] data = new byte[8192]; long total = 0; int count;
                while ((count = stream.read(data)) != -1) {
                    if ((total += count) > MAX_SOURCE_BYTES) return null;
                    file.write(data, 0, count);
                }
                if (total == 0) return null;
            }
            int w, h;
            try (var image = ImageIO.createImageInputStream(input.toFile())) {
                var readers = ImageIO.getImageReaders(image);
                if (!readers.hasNext()) return null;
                var reader = readers.next();
                try { reader.setInput(image, true, true); w = reader.getWidth(0); h = reader.getHeight(0); }
                finally { reader.dispose(); }
            }
            if (w < 1 || h < 1 || (long)w * h > 16_777_216) return null;
            double ratio = Math.min(1, (double)width / Math.max(w, h));
            String scale = Math.max(1, (int)Math.round(w * ratio)) + ":" + Math.max(1, (int)Math.round(h * ratio));
            Process process = new ProcessBuilder(ffmpeg, "-nostdin", "-v", "error", "-protocol_whitelist", "file,pipe",
                    "-threads", "1", "-i", input.toString(), "-vf", "scale=" + scale, "-frames:v", "1", "-an",
                    "-c:v", "libwebp", "-quality", "76", "-threads", "1", output.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                if (!process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0) return null;
            } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
            long size = Files.size(output);
            return size > 0 && size <= 1024 * 1024 ? Files.readAllBytes(output) : null;
        } finally { Files.deleteIfExists(output); Files.deleteIfExists(input); Files.deleteIfExists(dir); }
    }
}
