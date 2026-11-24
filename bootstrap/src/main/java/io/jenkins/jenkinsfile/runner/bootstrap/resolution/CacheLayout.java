package io.jenkins.jenkinsfile.runner.bootstrap.resolution;

import edu.umd.cs.findbugs.annotations.NonNull;

import java.io.File;
import java.nio.file.Path;

/**
 * Deterministic layout of the on-disk artifact cache.
 *
 * <p>The layout is shared by the online downloader and the offline resolver so that a cache
 * populated in one mode can be reused in the other:
 * <pre>
 *   &lt;cache&gt;/war/latest.txt
 *   &lt;cache&gt;/war/&lt;version&gt;/jenkins-war-&lt;version&gt;.war
 *   &lt;cache&gt;/plugins/&lt;name&gt;/&lt;name&gt;-&lt;version&gt;.hpi
 * </pre>
 * The plugin file name uses the requested version verbatim, so a plugin requested as
 * {@code latest} is cached as {@code <name>-latest.hpi}.
 *
 * <p>This class only computes paths; it performs no I/O.
 */
public final class CacheLayout {

    private final Path root;

    public CacheLayout(@NonNull Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @NonNull
    public Path root() {
        return root;
    }

    /** {@code war/latest.txt}, holding the latest LTS version number. */
    @NonNull
    public Path warLatestFile() {
        return root.resolve("war").resolve("latest.txt");
    }

    /** {@code war/<version>/jenkins-war-<version>.war}. */
    @NonNull
    public Path warFile(@NonNull String version) {
        return root.resolve("war").resolve(version).resolve("jenkins-war-" + version + ".war");
    }

    /** {@code plugins/<name>/<name>-<version>.hpi}. */
    @NonNull
    public Path pluginFile(@NonNull String name, @NonNull String version) {
        return root.resolve("plugins").resolve(name).resolve(name + "-" + version + ".hpi");
    }

    /**
     * Renders a cache file path relative to the cache root using {@code /} separators, which is
     * the form used in error messages (e.g. {@code plugins/git/git-5.2.0.hpi}).
     */
    @NonNull
    public String relative(@NonNull Path file) {
        final Path relativized = root.relativize(file.toAbsolutePath().normalize());
        return relativized.toString().replace(File.separatorChar, '/');
    }
}
