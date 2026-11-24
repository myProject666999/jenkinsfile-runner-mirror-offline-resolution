package io.jenkins.jenkinsfile.runner.bootstrap.resolution;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One artifact the resolver decided must be fetched, together with where to fetch it from and where
 * to store it. Pure data; no I/O happens while producing it.
 */
public final class DownloadRequest {

    private final String description;
    private final URI source;
    private final Path target;

    public DownloadRequest(@NonNull String description, @NonNull URI source, @NonNull Path target) {
        this.description = description;
        this.source = source;
        this.target = target;
    }

    /** Human-readable label, e.g. {@code jenkins plugin git (5.2.0)}. */
    @NonNull
    public String description() {
        return description;
    }

    @NonNull
    public URI source() {
        return source;
    }

    @NonNull
    public Path target() {
        return target;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DownloadRequest)) {
            return false;
        }
        DownloadRequest that = (DownloadRequest) o;
        return description.equals(that.description)
                && source.equals(that.source)
                && target.equals(that.target);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, source, target);
    }

    @Override
    public String toString() {
        return description + " <- " + source + " -> " + target;
    }
}
