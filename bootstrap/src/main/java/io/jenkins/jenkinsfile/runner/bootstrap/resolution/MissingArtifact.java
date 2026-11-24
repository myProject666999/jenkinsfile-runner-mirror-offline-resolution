package io.jenkins.jenkinsfile.runner.bootstrap.resolution;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A cache artifact that is required but absent in offline mode.
 *
 * <p>The expected cache path is reported so the user can pre-place the file (for example through a
 * {@code file:} mirror or a manually populated cache) before retrying.
 */
public final class MissingArtifact {

    private final String description;
    private final String cachePath;

    public MissingArtifact(@NonNull String description, @NonNull String cachePath) {
        this.description = description;
        this.cachePath = cachePath;
    }

    @NonNull
    public String description() {
        return description;
    }

    /** Path relative to the cache root, using {@code /} separators. */
    @NonNull
    public String cachePath() {
        return cachePath;
    }

    @Override
    public String toString() {
        return cachePath + " (" + description + ")";
    }
}
