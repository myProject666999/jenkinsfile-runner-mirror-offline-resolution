package io.jenkins.jenkinsfile.runner.bootstrap.resolution;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Builds download addresses for the Jenkins WAR, plugins, and core update metadata.
 *
 * <p>This class is pure: it performs no I/O and never contacts the network. All addresses are
 * validated while the mirror is parsed, so an invalid mirror fails immediately instead of when a
 * connection is attempted.
 *
 * <p>Mirror coverage:
 * <ul>
 *   <li>{@code --mirror} applies to both the Jenkins WAR and the plugin downloads.
 *       The trailing slash, if any, is stripped; the scheme ({@code http}, {@code https}, or
 *       {@code file}) is preserved and a path prefix is kept as-is.</li>
 *   <li>{@code stable/latestCore.txt} is only taken from a mirror when a separate metadata base
 *       is given explicitly ({@code --mirrorCore}). It is deliberately not derived from
 *       {@code --mirror}, because not every mirror serves the same {@code stable} path.</li>
 * </ul>
 *
 * <p>A {@code file:} mirror points at a local directory that has been pre-populated with the same
 * layout as the HTTP mirror. Constructing such addresses is pure logic; fetching them requires the
 * download step to accept non-HTTP URLs (it must not blindly cast the connection to
 * {@link java.net.HttpURLConnection}). That relaxation is applied together with this class, so a
 * {@code file:} mirror works end to end.
 */
public final class ArtifactSources {

    /** Default base used for the Jenkins WAR, kept as HTTP to preserve the historical behaviour. */
    public static final String DEFAULT_WAR_BASE = "http://updates.jenkins.io/download";

    /** Default base used for plugins, kept as HTTPS to preserve the historical behaviour. */
    public static final String DEFAULT_PLUGIN_BASE = "https://updates.jenkins.io/download";

    /** Default base used for {@code stable/latestCore.txt}. */
    public static final String DEFAULT_CORE_METADATA_BASE = "http://updates.jenkins.io";

    private static final Set<String> SUPPORTED_SCHEMES = Set.of("http", "https", "file");

    private final String warBase;
    private final String pluginBase;
    private final String coreMetadataBase;

    private ArtifactSources(String warBase, String pluginBase, String coreMetadataBase) {
        this.warBase = warBase;
        this.pluginBase = pluginBase;
        this.coreMetadataBase = coreMetadataBase;
    }

    /**
     * Parses the mirror settings.
     *
     * @param mirror       {@code --mirror} value, may be {@code null} or blank
     * @param coreMetadataMirror {@code --mirrorCore} value, may be {@code null} or blank
     * @throws IllegalArgumentException if a non-blank value is not a supported absolute URL
     */
    @NonNull
    public static ArtifactSources from(@CheckForNull String mirror, @CheckForNull String coreMetadataMirror) {
        final String downloadBase = normalizeBase(mirror, "--mirror");
        final String coreBase = normalizeBase(coreMetadataMirror, "--mirrorCore");
        return new ArtifactSources(
                downloadBase != null ? downloadBase : DEFAULT_WAR_BASE,
                downloadBase != null ? downloadBase : DEFAULT_PLUGIN_BASE,
                coreBase != null ? coreBase : DEFAULT_CORE_METADATA_BASE);
    }

    /** URL of {@code war/<version>/jenkins.war}. */
    @NonNull
    public URI warUrl(@NonNull String version) {
        return join(warBase, "war/" + version + "/jenkins.war");
    }

    /** URL of {@code plugins/<name>/<version>/<name>.hpi}. */
    @NonNull
    public URI pluginUrl(@NonNull String name, @NonNull String version) {
        return join(pluginBase, "plugins/" + name + "/" + version + "/" + name + ".hpi");
    }

    /** URL of {@code stable/latestCore.txt}. */
    @NonNull
    public URI latestCoreUrl() {
        return join(coreMetadataBase, "stable/latestCore.txt");
    }

    @NonNull
    private static URI join(@NonNull String base, @NonNull String path) {
        try {
            return new URI(base + "/" + path);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Cannot build a valid artifact URL from " + base + " and " + path, ex);
        }
    }

    /**
     * Validates and normalizes a mirror base: trailing slashes are removed, the scheme is kept.
     *
     * @return the normalized base, or {@code null} when the input is null or blank
     */
    @CheckForNull
    static String normalizeBase(@CheckForNull String raw, @NonNull String option) {
        if (raw == null) {
            return null;
        }
        final String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }

        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException(option + " is not a valid URL: " + raw, ex);
        }

        if (!uri.isAbsolute() || uri.isOpaque() || uri.getScheme() == null) {
            throw new IllegalArgumentException(option + " must be an absolute URL with a scheme: " + raw);
        }
        final String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException(option + " must use the http, https, or file scheme: " + raw);
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(option + " must not contain a query string or fragment: " + raw);
        }
        if ("file".equals(scheme)) {
            final String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                throw new IllegalArgumentException(option + " file mirror must point to a directory: " + raw);
            }
        } else if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException(option + " URL is missing a host: " + raw);
        }

        String normalized = value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
