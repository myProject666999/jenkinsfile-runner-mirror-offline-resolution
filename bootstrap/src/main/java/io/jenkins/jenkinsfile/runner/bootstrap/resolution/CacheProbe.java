package io.jenkins.jenkinsfile.runner.bootstrap.resolution;

import edu.umd.cs.findbugs.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * Read-only view over the cache used to decide whether an artifact is already present and how old
 * it is. Exists so the cache-hit logic can be exercised without touching real files.
 */
public interface CacheProbe {

    boolean exists(@NonNull Path file);

    /** Last modification time in epoch millis. Only meaningful when {@link #exists(Path)} is true. */
    long lastModified(@NonNull Path file) throws IOException;

    /** Cache probe backed by the real filesystem. */
    @NonNull
    static CacheProbe fileSystem() {
        return new CacheProbe() {
            @Override
            public boolean exists(@NonNull Path file) {
                return Files.isRegularFile(file);
            }

            @Override
            public long lastModified(@NonNull Path file) throws IOException {
                final FileTime time = Files.getLastModifiedTime(file);
                return time.toMillis();
            }
        };
    }
}
