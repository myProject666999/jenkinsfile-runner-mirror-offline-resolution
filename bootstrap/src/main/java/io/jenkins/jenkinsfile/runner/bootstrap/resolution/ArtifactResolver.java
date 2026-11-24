package io.jenkins.jenkinsfile.runner.bootstrap.resolution;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Pure resolution logic: given mirror settings, a requested version and plugin list, decides what
 * must be downloaded and where each artifact lives in the cache, or — in offline mode — which
 * artifacts are missing.
 *
 * <p>The resolver never opens a network connection and never reads artifact contents. It only
 * consults file presence and modification times through a {@link CacheProbe}, and reads the
 * current time through an injectable {@link LongSupplier}, so cache-expiry behaviour is testable
 * without wall-clock dependence.
 *
 * <p>Cache policy, matching the historical behaviour except that the one-day expiry is now measured
 * against the injected clock rather than a value frozen at class-load time:
 * <ul>
 *   <li>an explicitly versioned WAR or plugin is reused whenever the cache file exists;</li>
 *   <li>{@code war/latest.txt} and {@code latest}-versioned plugins expire after 24 hours;</li>
 *   <li>in {@link ResolutionMode#OFFLINE} nothing expires and nothing is fetched.</li>
 * </ul>
 */
public final class ArtifactResolver {

    /** Default lifetime of volatile artifacts (latest LTS metadata and {@code latest} plugins). */
    public static final long DEFAULT_MAX_AGE_MILLIS = 24L * 3600L * 1000L;

    private static final String LATEST_CORE_DESCRIPTION = "latest LTS version metadata";

    private final CacheLayout layout;
    private final ArtifactSources sources;
    private final CacheProbe probe;
    private final LongSupplier clock;
    private final ResolutionMode mode;
    private final long maxAgeMillis;

    public ArtifactResolver(@NonNull CacheLayout layout,
                            @NonNull ArtifactSources sources,
                            @NonNull CacheProbe probe,
                            @NonNull LongSupplier clock,
                            @NonNull ResolutionMode mode,
                            long maxAgeMillis) {
        this.layout = layout;
        this.sources = sources;
        this.probe = probe;
        this.clock = clock;
        this.mode = mode;
        this.maxAgeMillis = maxAgeMillis;
    }

    /** Convenience factory using the real filesystem and system clock. */
    @NonNull
    public static ArtifactResolver create(@NonNull CacheLayout layout,
                                          @NonNull ArtifactSources sources,
                                          @NonNull ResolutionMode mode) {
        return new ArtifactResolver(layout, sources, CacheProbe.fileSystem(), System::currentTimeMillis, mode, DEFAULT_MAX_AGE_MILLIS);
    }

    /** The cache layout this resolver reads. */
    @NonNull
    public CacheLayout layout() {
        return layout;
    }

    // ---------------------------------------------------------------- latest LTS metadata

    /** Whether {@code war/latest.txt} exists in the cache. */
    public boolean hasCachedLatestLts() {
        return probe.exists(layout.warLatestFile());
    }

    /**
     * In online mode, the request to (re)fetch {@code war/latest.txt}, if it is missing or expired.
     * Always empty in offline mode.
     */
    @NonNull
    public Optional<DownloadRequest> latestLtsRefreshRequest() {
        if (mode == ResolutionMode.OFFLINE) {
            return Optional.empty();
        }
        if (isUsable(layout.warLatestFile(), true)) {
            return Optional.empty();
        }
        return Optional.of(new DownloadRequest(
                LATEST_CORE_DESCRIPTION,
                sources.latestCoreUrl(),
                layout.warLatestFile()));
    }

    // ---------------------------------------------------------------- WAR

    /** Whether the WAR for this version is cached. */
    public boolean hasCachedWar(@NonNull String version) {
        return probe.exists(layout.warFile(version));
    }

    /** In online mode, the request to fetch the WAR for this version, if it is not cached. */
    @NonNull
    public Optional<DownloadRequest> warRequest(@NonNull String version) {
        if (mode == ResolutionMode.OFFLINE || hasCachedWar(version)) {
            return Optional.empty();
        }
        return Optional.of(new DownloadRequest(
                "jenkins " + version,
                sources.warUrl(version),
                layout.warFile(version)));
    }

    // ---------------------------------------------------------------- plugins

    /** Whether the plugin artifact for this spec is cached. */
    public boolean hasCachedPlugin(@NonNull PluginSpec spec) {
        return probe.exists(layout.pluginFile(spec.name(), spec.version()));
    }

    /**
     * In online mode, the request to fetch this plugin, if it is missing or — for a {@code latest}
     * version — expired.
     */
    @NonNull
    public Optional<DownloadRequest> pluginRequest(@NonNull PluginSpec spec) {
        if (mode == ResolutionMode.OFFLINE) {
            return Optional.empty();
        }
        final Path file = layout.pluginFile(spec.name(), spec.version());
        if (isUsable(file, spec.isLatest())) {
            return Optional.empty();
        }
        return Optional.of(new DownloadRequest(
                "jenkins plugin " + spec.name() + " (" + spec.version() + ")",
                sources.pluginUrl(spec.name(), spec.version()),
                file));
    }

    // ---------------------------------------------------------------- offline preflight

    /**
     * Collects every artifact that is required but absent from the cache, so offline mode can
     * report all of them at once before any plugin is installed.
     *
     * @param warRequired         whether a WAR is needed at all (false when {@code --jenkins-war}
     *                            already points to an exploded WAR)
     * @param requestedWarVersion the {@code --jenkins-version} value, or {@code null}
     * @param cachedLatestVersion the content of {@code war/latest.txt} when present, or {@code null}
     * @param plugins             plugins requested from {@code plugins.txt}
     */
    @NonNull
    public List<MissingArtifact> missingFor(boolean warRequired,
                                            @CheckForNull String requestedWarVersion,
                                            @CheckForNull String cachedLatestVersion,
                                            @NonNull List<PluginSpec> plugins) {
        if (mode != ResolutionMode.OFFLINE) {
            throw new IllegalStateException("missingFor is only meaningful in offline mode");
        }
        final List<MissingArtifact> missing = new ArrayList<>();

        if (warRequired) {
            String warVersion = requestedWarVersion;
            if (warVersion == null) {
                if (!hasCachedLatestLts() || cachedLatestVersion == null) {
                    missing.add(new MissingArtifact(LATEST_CORE_DESCRIPTION, layout.relative(layout.warLatestFile())));
                    warVersion = null;
                } else {
                    warVersion = cachedLatestVersion;
                }
            }
            if (warVersion != null && !hasCachedWar(warVersion)) {
                missing.add(new MissingArtifact("jenkins " + warVersion, layout.relative(layout.warFile(warVersion))));
            }
        }
        for (PluginSpec spec : plugins) {
            if (!hasCachedPlugin(spec)) {
                missing.add(new MissingArtifact(
                        "jenkins plugin " + spec.name() + " (" + spec.version() + ")",
                        layout.relative(layout.pluginFile(spec.name(), spec.version()))));
            }
        }
        return missing;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A cached file is usable when it exists and either it must not expire or it is within its
     * lifetime. The boundary is inclusive: a file whose age equals the limit is still fresh.
     */
    private boolean isUsable(@NonNull Path file, boolean expiring) {
        if (!probe.exists(file)) {
            return false;
        }
        if (!expiring) {
            return true;
        }
        try {
            final long age = clock.getAsLong() - probe.lastModified(file);
            return age <= maxAgeMillis;
        } catch (IOException ex) {
            // Cannot tell how old the file is; treat it as stale so the caller refreshes it.
            return false;
        }
    }
}
