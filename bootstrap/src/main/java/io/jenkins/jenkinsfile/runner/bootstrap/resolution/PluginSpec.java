package io.jenkins.jenkinsfile.runner.bootstrap.resolution;

import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.Objects;

/**
 * A single plugin requested for installation, as parsed from a {@code plugins.txt} line.
 *
 * <p>The accepted format is {@code name[:version]}. When the version is omitted it defaults to
 * {@code latest}, matching the historical behaviour. Parsing performs no I/O.
 */
public final class PluginSpec {

    public static final String LATEST = "latest";

    private final String name;
    private final String version;

    public PluginSpec(@NonNull String name, @NonNull String version) {
        this.name = requireToken(name, "plugin name");
        this.version = requireToken(version, "plugin version");
    }

    /** Parses a {@code plugins.txt} line: {@code name} or {@code name:version}. */
    @NonNull
    public static PluginSpec parse(@NonNull String line) {
        String name = line;
        String version = LATEST;
        final int i = line.indexOf(':');
        if (i != -1) {
            name = line.substring(0, i);
            version = line.substring(i + 1);
        }
        return new PluginSpec(name, version);
    }

    @NonNull
    public String name() {
        return name;
    }

    @NonNull
    public String version() {
        return version;
    }

    public boolean isLatest() {
        return LATEST.equals(version);
    }

    @NonNull
    private static String requireToken(@NonNull String value, @NonNull String what) {
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Empty " + what + " in plugin specification");
        }
        return trimmed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PluginSpec)) {
            return false;
        }
        PluginSpec that = (PluginSpec) o;
        return name.equals(that.name) && version.equals(that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, version);
    }

    @Override
    public String toString() {
        return name + ":" + version;
    }
}
