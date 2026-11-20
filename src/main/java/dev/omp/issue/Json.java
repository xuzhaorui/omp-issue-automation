package dev.omp.issue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import static java.nio.file.StandardOpenOption.*;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    private Json() {}
    public static JsonNode read(Path path) throws IOException { return MAPPER.readTree(path.toFile()); }
    public static void privateDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) { /* Windows uses inherited user ACLs. */ }
    }
    public static void write(Path path, Object value) throws IOException {
        privateDirectory(path.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), ".write-", ".tmp");
        try {
            try (var channel = FileChannel.open(temp, WRITE, TRUNCATE_EXISTING)) {
                var bytes = ByteBuffer.wrap(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
