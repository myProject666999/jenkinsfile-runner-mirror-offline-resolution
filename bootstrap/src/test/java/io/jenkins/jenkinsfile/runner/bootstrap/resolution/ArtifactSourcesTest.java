package io.jenkins.jenkinsfile.runner.bootstrap.resolution;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * Table-driven checks of address construction. These run entirely offline: no connection is opened,
 * and {@code updates.jenkins.io} is never contacted.
 */
public class ArtifactSourcesTest {

    private static final String VERSION = "2.440.3";
    private static final String PLUGIN = "git";
    private static final String PLUGIN_VERSION = "5.2.0";

    @Test
    public void buildsUrlsWithoutMirror() {
        assertMirror(null,
                "http://updates.jenkins.io/download/war/2.440.3/jenkins.war",
                "https://updates.jenkins.io/download/plugins/git/5.2.0/git.hpi",
                "http://updates.jenkins.io/stable/latestCore.txt");
        assertMirror("   ",
                "http://updates.jenkins.io/download/war/2.440.3/jenkins.war",
                "https://updates.jenkins.io/download/plugins/git/5.2.0/git.hpi",
                "http://updates.jenkins.io/stable/latestCore.txt");
    }

    @Test
    public void mirrorRewritesWarAndPlugins() {
        assertMirror("https://mirror.example.com/jenkins",
                "https://mirror.example.com/jenkins/war/2.440.3/jenkins.war",
                "https://mirror.example.com/jenkins/plugins/git/5.2.0/git.hpi",
                // latestCore.txt does not go through --mirror
                "http://updates.jenkins.io/stable/latestCore.txt");
    }

    @Test
    public void trailingSlashIsRemoved() {
        // One and two trailing slashes both normalise to a single separator in the built URLs.
        assertMirror("https://mirror.example.com/download/",
                "https://mirror.example.com/download/war/2.440.3/jenkins.war",
                "https://mirror.example.com/download/plugins/git/5.2.0/git.hpi",
                "http://updates.jenkins.io/stable/latestCore.txt");
        assertMirror("https://mirror.example.com/download//",
                "https://mirror.example.com/download/war/2.440.3/jenkins.war",
                "https://mirror.example.com/download/plugins/git/5.2.0/git.hpi",
                "http://updates.jenkins.io/stable/latestCore.txt");
    }

    @Test
    public void httpAndHttpsSchemesArePreserved() {
        assertMirror("http://mirror.example.com/download",
                "http://mirror.example.com/download/war/2.440.3/jenkins.war",
                "http://mirror.example.com/download/plugins/git/5.2.0/git.hpi",
                "http://updates.jenkins.io/stable/latestCore.txt");
        assertMirror("https://mirror.example.com/download",
                "https://mirror.example.com/download/war/2.440.3/jenkins.war",
                "https://mirror.example.com/download/plugins/git/5.2.0/git.hpi",
                "http://updates.jenkins.io/stable/latestCore.txt");
    }

    @Test
    public void fileSchemeMirrorPointsAtLocalDirectory() {
        assertMirror("file:///srv/jenkins-mirror",
                "file:///srv/jenkins-mirror/war/2.440.3/jenkins.war",
                "file:///srv/jenkins-mirror/plugins/git/5.2.0/git.hpi",
                "http://updates.jenkins.io/stable/latestCore.txt");
    }

    @Test
    public void mirrorWithPathPrefixIsKept() {
        assertMirror("https://mirror.example.com/mirrors/jenkins/download",
                "https://mirror.example.com/mirrors/jenkins/download/war/2.440.3/jenkins.war",
                "https://mirror.example.com/mirrors/jenkins/download/plugins/git/5.2.0/git.hpi",
                "http://updates.jenkins.io/stable/latestCore.txt");
    }

    @Test
    public void coreMetadataMirrorIsOptIn() {
        ArtifactSources sources = ArtifactSources.from("https://mirror.example.com/download",
                "https://mirror.example.com/");
        assertEquals("https://mirror.example.com/stable/latestCore.txt", sources.latestCoreUrl().toString());
        // The WAR/plugin base is unaffected by the metadata mirror.
        assertEquals("https://mirror.example.com/download/war/2.440.3/jenkins.war", sources.warUrl(VERSION).toString());
    }

    @Test
    public void invalidMirrorsFailAtParseTime() {
        assertThrows(IllegalArgumentException.class, () -> ArtifactSources.from("mirror.example.com/download", null));
        assertThrows(IllegalArgumentException.class, () -> ArtifactSources.from("ftp://mirror.example.com/download", null));
        assertThrows(IllegalArgumentException.class, () -> ArtifactSources.from("https://mirror.example.com/download?x=1", null));
        assertThrows(IllegalArgumentException.class, () -> ArtifactSources.from("file://", null));
        assertThrows(IllegalArgumentException.class, () -> ArtifactSources.from(null, "not a url"));
    }

    private static void assertMirror(String mirror, String expectedWar, String expectedPlugin, String expectedLatestCore) {
        ArtifactSources sources = ArtifactSources.from(mirror, null);
        assertEquals("WAR URL for mirror [" + mirror + "]", expectedWar, sources.warUrl(VERSION).toString());
        assertEquals("plugin URL for mirror [" + mirror + "]", expectedPlugin,
                sources.pluginUrl(PLUGIN, PLUGIN_VERSION).toString());
        assertEquals("latestCore URL for mirror [" + mirror + "]", expectedLatestCore, sources.latestCoreUrl().toString());
    }
}
