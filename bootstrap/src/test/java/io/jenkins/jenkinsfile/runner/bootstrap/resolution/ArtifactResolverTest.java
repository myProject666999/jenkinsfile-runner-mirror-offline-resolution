package io.jenkins.jenkinsfile.runner.bootstrap.resolution;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Cache-hit and offline-missing decisions, using a temporary directory with the real cache layout
 * and an injectable clock. Nothing here opens a network connection.
 */
public class ArtifactResolverTest {

    private static final long DAY = 24L * 3600L * 1000L;
    private static final long NOW = 1_700_000_000_000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final AtomicLong clock = new AtomicLong(NOW);

    private Path layoutRoot(boolean offline) throws IOException {
        return tmp.newFolder().toPath();
    }

    private CacheLayout layout(Path root) {
        return new CacheLayout(root);
    }

    private ArtifactResolver resolver(CacheLayout layout, ResolutionMode mode) {
        return new ArtifactResolver(layout, ArtifactSources.from(null, null), CacheProbe.fileSystem(),
                clock::get, mode, ArtifactResolver.DEFAULT_MAX_AGE_MILLIS);
    }

    private static void place(Path file, long modified) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
    }

    // ---------------------------------------------------------------- online cache policy

    @Test
    public void explicitVersionIsReusedRegardlessOfAge() throws IOException {
        CacheLayout layout = layout(layoutRoot(false));
        Path war = layout.warFile("2.440.3");
        // A year old, but explicitly versioned artifacts never expire.
        place(war, NOW - 365 * DAY);

        ArtifactResolver resolver = resolver(layout, ResolutionMode.ONLINE);
        assertTrue(resolver.hasCachedWar("2.440.3"));
        assertEquals(Optional.empty(), resolver.warRequest("2.440.3"));

        PluginSpec pinned = new PluginSpec("git", "5.2.0");
        place(layout.pluginFile("git", "5.2.0"), NOW - 365 * DAY);
        assertEquals(Optional.empty(), resolver.pluginRequest(pinned));
    }

    @Test
    public void latestPluginExpiresAfterOneDay() throws IOException {
        CacheLayout layout = layout(layoutRoot(false));
        PluginSpec latest = PluginSpec.parse("workflow-aggregator");

        // Just inside the boundary: fresh, no download.
        place(layout.pluginFile("workflow-aggregator", "latest"), NOW - DAY + 1);
        ArtifactResolver resolver = resolver(layout, ResolutionMode.ONLINE);
        assertEquals(Optional.empty(), resolver.pluginRequest(latest));

        // Just outside the boundary: stale, a download is requested.
        place(layout.pluginFile("workflow-aggregator", "latest"), NOW - DAY - 1);
        Optional<DownloadRequest> request = resolver.pluginRequest(latest);
        assertTrue(request.isPresent());
        assertEquals(layout.pluginFile("workflow-aggregator", "latest"), request.get().target());
    }

    @Test
    public void latestLtsMetadataExpiresAfterOneDay() throws IOException {
        CacheLayout layout = layout(layoutRoot(false));

        place(layout.warLatestFile(), NOW - DAY + 1);
        ArtifactResolver fresh = resolver(layout, ResolutionMode.ONLINE);
        assertEquals(Optional.empty(), fresh.latestLtsRefreshRequest());

        place(layout.warLatestFile(), NOW - DAY - 1);
        ArtifactResolver stale = resolver(layout, ResolutionMode.ONLINE);
        assertTrue(stale.latestLtsRefreshRequest().isPresent());
    }

    @Test
    public void boundaryExactlyOneDayIsFresh() throws IOException {
        CacheLayout layout = layout(layoutRoot(false));
        place(layout.warLatestFile(), NOW - DAY);
        ArtifactResolver resolver = resolver(layout, ResolutionMode.ONLINE);
        assertEquals(Optional.empty(), resolver.latestLtsRefreshRequest());
    }

    @Test
    public void missingArtifactIsRequestedOnline() throws IOException {
        CacheLayout layout = layout(layoutRoot(false));
        ArtifactResolver resolver = resolver(layout, ResolutionMode.ONLINE);

        Optional<DownloadRequest> war = resolver.warRequest("2.440.3");
        assertTrue(war.isPresent());
        assertEquals("http://updates.jenkins.io/download/war/2.440.3/jenkins.war", war.get().source().toString());
        assertEquals(layout.warFile("2.440.3"), war.get().target());
    }

    // ---------------------------------------------------------------- offline

    @Test
    public void offlineNeverRequestsDownloadsEvenWhenStale() throws IOException {
        CacheLayout layout = layout(layoutRoot(false));
        ArtifactResolver resolver = resolver(layout, ResolutionMode.OFFLINE);

        assertEquals(Optional.empty(), resolver.latestLtsRefreshRequest());
        assertEquals(Optional.empty(), resolver.warRequest("2.440.3"));
        assertEquals(Optional.empty(), resolver.pluginRequest(PluginSpec.parse("git")));
    }

    @Test
    public void offlineReportsOnlyTheMissingLatestPlugins() throws IOException {
        Path root = layoutRoot(true);
        CacheLayout layout = layout(root);
        // Layout from the task statement: WAR and a pinned git plugin cached, one stale 'latest' plugin.
        place(layout.warFile("2.440.3"), NOW - 365 * DAY);
        place(layout.pluginFile("git", "5.2.0"), NOW - 365 * DAY);

        ArtifactResolver resolver = resolver(layout, ResolutionMode.OFFLINE);
        List<PluginSpec> requested = Arrays.asList(
                new PluginSpec("git", "5.2.0"),
                PluginSpec.parse("workflow-aggregator"));

        List<MissingArtifact> missing = resolver.missingFor(true, "2.440.3", null, requested);

        assertEquals(1, missing.size());
        assertEquals("plugins/workflow-aggregator/workflow-aggregator-latest.hpi", missing.get(0).cachePath());
        assertTrue(missing.get(0).description().contains("workflow-aggregator"));
    }

    @Test
    public void offlineListsEveryMissingArtifactAtOnce() throws IOException {
        CacheLayout layout = layout(layoutRoot(true));
        ArtifactResolver resolver = resolver(layout, ResolutionMode.OFFLINE);

        // Nothing cached, no explicit version: latest.txt is missing, so the WAR version is unknown;
        // both requested plugins are still reported in the same pass.
        List<MissingArtifact> missing = resolver.missingFor(true, null, null,
                Arrays.asList(PluginSpec.parse("git"), PluginSpec.parse("workflow-aggregator")));

        assertEquals(3, missing.size());
        assertEquals("war/latest.txt", missing.get(0).cachePath());
        assertEquals("plugins/git/git-latest.hpi", missing.get(1).cachePath());
        assertEquals("plugins/workflow-aggregator/workflow-aggregator-latest.hpi", missing.get(2).cachePath());
    }

    @Test
    public void offlineUsesCachedLatestLtsWithoutExpiry() throws IOException {
        CacheLayout layout = layout(layoutRoot(true));
        place(layout.warLatestFile(), NOW - 10 * DAY);
        place(layout.warFile("2.440.3"), NOW - 10 * DAY);

        ArtifactResolver resolver = resolver(layout, ResolutionMode.OFFLINE);
        // version left null; caller supplies the (very old) cached latest value, which must still be honoured.
        List<MissingArtifact> missing = resolver.missingFor(true, null, "2.440.3", List.of());

        assertTrue(missing.isEmpty());
    }

    @Test
    public void offlineWithExplodedWarDoesNotRequireWarArtifacts() throws IOException {
        CacheLayout layout = layout(layoutRoot(true));
        place(layout.pluginFile("git", "5.2.0"), NOW);

        ArtifactResolver resolver = resolver(layout, ResolutionMode.OFFLINE);
        List<MissingArtifact> missing = resolver.missingFor(false, null, null,
                List.of(new PluginSpec("git", "5.2.0")));

        assertTrue(missing.isEmpty());
    }

    @Test
    public void onlineModeRejectsOfflinePreflight() {
        CacheLayout layout = layout(tmp.getRoot().toPath());
        ArtifactResolver resolver = resolver(layout, ResolutionMode.ONLINE);
        org.junit.Assert.assertThrows(IllegalStateException.class,
                () -> resolver.missingFor(true, null, null, List.of()));
    }

    // ---------------------------------------------------------------- plugin spec parsing

    @Test
    public void pluginSpecParsesNameAndOptionalVersion() {
        assertEquals(new PluginSpec("git", "5.2.0"), PluginSpec.parse("git:5.2.0"));
        assertEquals(new PluginSpec("git", "latest"), PluginSpec.parse("git"));
        assertEquals(new PluginSpec("workflow-aggregator", "latest"), PluginSpec.parse("workflow-aggregator"));
        assertEquals(new PluginSpec("git", "5.2.0"), PluginSpec.parse("git:5.2.0"));
        assertEquals(new PluginSpec("git", "5.2.0"), PluginSpec.parse(" git:5.2.0 "));
    }

    @Test
    public void pluginSpecRejectsEmptyName() {
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> PluginSpec.parse(":1.0"));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> PluginSpec.parse("git:"));
    }

    // ---------------------------------------------------------------- cache layout

    @Test
    public void cacheLayoutMatchesDocumentedPaths() {
        CacheLayout layout = new CacheLayout(tmp.getRoot().toPath());
        assertEquals("war/latest.txt", layout.relative(layout.warLatestFile()));
        assertEquals("war/2.440.3/jenkins-war-2.440.3.war", layout.relative(layout.warFile("2.440.3")));
        assertEquals("plugins/git/git-5.2.0.hpi", layout.relative(layout.pluginFile("git", "5.2.0")));
        assertEquals("plugins/workflow-aggregator/workflow-aggregator-latest.hpi",
                layout.relative(layout.pluginFile("workflow-aggregator", "latest")));
    }

    @Test
    public void explicitlyProvidedExplodedWarSkipsWarPreflight() throws IOException {
        CacheLayout layout = layout(layoutRoot(true));
        // A plugins directory (not a plugins.txt list here) still needs its plugins checked.
        place(layout.pluginFile("git", "5.2.0"), NOW);
        ArtifactResolver resolver = resolver(layout, ResolutionMode.OFFLINE);

        assertTrue(resolver.missingFor(false, "2.440.3", null, List.of(new PluginSpec("git", "5.2.0"))).isEmpty());
    }
}
