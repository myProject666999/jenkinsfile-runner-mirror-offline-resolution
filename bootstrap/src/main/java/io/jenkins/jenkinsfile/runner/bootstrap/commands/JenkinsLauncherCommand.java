package io.jenkins.jenkinsfile.runner.bootstrap.commands;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jenkins.jenkinsfile.runner.bootstrap.ClassLoaderBuilder;
import io.jenkins.jenkinsfile.runner.bootstrap.IApp;
import io.jenkins.jenkinsfile.runner.bootstrap.SideClassLoader;
import io.jenkins.jenkinsfile.runner.bootstrap.Util;
import io.jenkins.jenkinsfile.runner.bootstrap.resolution.ArtifactResolver;
import io.jenkins.jenkinsfile.runner.bootstrap.resolution.ArtifactSources;
import io.jenkins.jenkinsfile.runner.bootstrap.resolution.CacheLayout;
import io.jenkins.jenkinsfile.runner.bootstrap.resolution.CacheProbe;
import io.jenkins.jenkinsfile.runner.bootstrap.resolution.DownloadRequest;
import io.jenkins.jenkinsfile.runner.bootstrap.resolution.MissingArtifact;
import io.jenkins.jenkinsfile.runner.bootstrap.resolution.PluginSpec;
import io.jenkins.jenkinsfile.runner.bootstrap.resolution.ResolutionMode;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Command that requires launching a Jenkins instance,
 * including all required steps like Jenkins WAR and plugins resolution.
 */
public abstract class JenkinsLauncherCommand implements Callable<Integer> {

    private static final Collection<Integer> REDIRECT_STATUSES = Arrays.asList(
        301, // Moved permanently
        302, // Found (HTTP 1.1) / Moved temporarily (HTTP 1.0)
        303, // See other
        307, // Temporarily Redirect
        308  // Permanent Redirect
    );

    @CommandLine.Mixin
    public JenkinsLauncherOptions launcherOptions;

    public JenkinsLauncherOptions getLauncherOptions() {
        return launcherOptions;
    }

    /**
     * Gets classname of the App which should be launched
     */
    @NonNull
    public abstract String getAppClassName();

    @Override
    @SuppressFBWarnings("DM_EXIT")
    public Integer call() throws IllegalStateException {
        try {
            postConstruct();
            return runJenkinsfileRunnerApp();
        } catch (Throwable ex) {
            throw new RuntimeException("Unhandled exception", ex);
        }
    }

    /**
     * Directory used as cache for downloaded artifacts.
     */
    private File cache = new File(System.getProperty("user.home") + "/.jenkinsfile-runner/");

    private CacheLayout cacheLayout;

    @SuppressFBWarnings("DM_EXIT")
    public void postConstruct() throws IOException {
        final JenkinsLauncherOptions settings = getLauncherOptions();

        // Process the Jenkins version
        if (settings.version != null && settings.warDir != null) {
            System.err.print("Error: --jenkins-war and --jenkins-version are mutually exclusive options");
            System.exit(-1);
        }
        if (settings.version != null && !Util.isJenkinsVersionSupported(settings.version)) {
            System.err.printf("Jenkins version [%s] not supported by this jenkinsfile-runner version (requires %s). %n",
                    settings.version,
                    Util.getMininumJenkinsVersion());
            System.exit(-1);
        }

        CacheLayout layout = cacheLayout();
        ResolutionMode mode = settings.offline ? ResolutionMode.OFFLINE : ResolutionMode.ONLINE;
        final ArtifactSources sources;
        try {
            sources = ArtifactSources.from(settings.mirror, settings.mirrorCore);
        } catch (IllegalArgumentException ex) {
            System.err.println("Error: " + ex.getMessage());
            System.exit(-1);
            return;
        }
        ArtifactResolver resolver = new ArtifactResolver(layout, sources, CacheProbe.fileSystem(),
                System::currentTimeMillis, mode, ArtifactResolver.DEFAULT_MAX_AGE_MILLIS);

        if (settings.pluginsDir == null) {
            // TODO: avoid modifying the option
            settings.pluginsDir = new File("plugins.txt");
        } else if (!settings.pluginsDir.exists()) {
            System.err.println("invalid plugins file.");
            System.exit(-1);
        }

        // Parse a plugin list file up-front so offline preflight can account for every artifact.
        List<PluginSpec> specs = null;
        if (settings.pluginsDir.isFile()) {
            specs = new ArrayList<>();
            for (String line : FileUtils.readLines(settings.pluginsDir, UTF_8)) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                specs.add(PluginSpec.parse(line));
            }
        }

        if (mode == ResolutionMode.OFFLINE) {
            preflightOffline(resolver, settings.warDir == null, settings.version,
                    specs != null ? specs : new ArrayList<>());
        }

        if (settings.warDir == null) {
            // TODO: avoid modifying the option
            settings.warDir = getJenkinsWar(settings.version, resolver);
        }
        // Explode war if necessary
        String warPath = settings.warDir.getAbsolutePath();
        if(FilenameUtils.getExtension(warPath).equals("war") && new File(warPath).isFile()) {
            System.out.println("Exploding " + warPath +  ", this might take some time.");
            settings.warDir = Util.explodeWar(warPath);
        }

        if (specs != null) {
            // This is a plugin list file
            settings.pluginsDir = Files.createTempDirectory("plugins").toFile();
            for (PluginSpec spec : specs) {
                installPlugin(settings.pluginsDir, spec, resolver);
            }
        }
    }

    /**
     * In offline mode, reports every artifact that is required but absent from the cache in one go,
     * before anything is installed, then exits with an input-error status.
     */
    @SuppressFBWarnings("DM_EXIT")
    private void preflightOffline(ArtifactResolver resolver, boolean warRequired,
                                  @CheckForNull String requestedWarVersion,
                                  List<PluginSpec> specs) throws IOException {
        String latest = null;
        if (warRequired && requestedWarVersion == null) {
            Path latestFile = cacheLayout().warLatestFile();
            if (Files.isRegularFile(latestFile)) {
                latest = FileUtils.readFileToString(latestFile.toFile(), StandardCharsets.US_ASCII).trim();
            }
        }
        List<MissingArtifact> missing = resolver.missingFor(warRequired, requestedWarVersion, latest, specs);
        if (!missing.isEmpty()) {
            System.err.println("Error: --offline was specified but the following artifacts are not present in the cache:");
            for (MissingArtifact artifact : missing) {
                System.err.println("  - " + artifact.description());
                System.err.println("      expected at: " + cacheLayout().root().resolve(artifact.cachePath()));
            }
            System.exit(-1);
        }
    }

    /**
     * Opens a connection for the given address, handling cross-protocol redirects for HTTP(S) and
     * reading {@code file:} addresses without casting. The connection is not restricted to
     * {@link HttpURLConnection}, so {@code file:} mirrors work.
     */
    private void copyURLToFile(URL url, File file) throws IOException {
        URLConnection connection = url.openConnection();
        if (connection instanceof HttpURLConnection) {
            HttpURLConnection http = (HttpURLConnection) connection;
            http.setInstanceFollowRedirects(true);
            int status = http.getResponseCode();

            // Connections won't automatically redirect across different protocols, i.e. going
            // from http to https. We have to handle that ourselves.
            if (REDIRECT_STATUSES.contains(status)) {
                String newUrl = http.getHeaderField("Location");
                System.out.println("Following redirect...");
                http = (HttpURLConnection) new URL(newUrl).openConnection();
                http.setInstanceFollowRedirects(true);
                connection = http;
            }
        }

        try (InputStream stream = connection.getInputStream()) {
            FileUtils.copyInputStreamToFile(stream, file);
        }
    }

    private File getJenkinsWar(final @CheckForNull String requiredVersion, ArtifactResolver resolver) throws IOException {
        final String versionToUse;
        if (requiredVersion == null) {
            System.out.println("No explicit version has been selected, using latest LTS");

            Optional<DownloadRequest> refresh = resolver.latestLtsRefreshRequest();
            if (refresh.isPresent()) {
                cacheLayout().warLatestFile().getParent().toFile().mkdirs();
                copyURLToFile(refresh.get().source().toURL(), refresh.get().target().toFile());
            }
            if (!Files.isRegularFile(cacheLayout().warLatestFile())) {
                throw new IOException("Cannot resolve the latest LTS version: " + cacheLayout().warLatestFile() + " is missing");
            }
            versionToUse = FileUtils.readFileToString(cacheLayout().warLatestFile().toFile(), StandardCharsets.US_ASCII).trim();
        } else {
            versionToUse = requiredVersion;
        }

        System.out.printf("Running pipeline on jenkins %s%n", versionToUse);

        Optional<DownloadRequest> warDownload = resolver.warRequest(versionToUse);
        if (warDownload.isPresent()) {
            DownloadRequest request = warDownload.get();
            request.target().getParent().toFile().mkdirs();
            System.out.printf("Downloading jenkins %s...%n", versionToUse);
            copyURLToFile(request.source().toURL(), request.target().toFile());
        }

        File war = cacheLayout().warFile(versionToUse).toFile();
        if (!war.isFile()) {
            throw new IOException("Jenkins WAR is missing from the cache: " + war);
        }
        return war;
    }

    private void installPlugin(File pluginsDir, PluginSpec spec, ArtifactResolver resolver) throws IOException {
        final File install = new File(pluginsDir, spec.name() + ".jpi");

        Optional<DownloadRequest> download = resolver.pluginRequest(spec);
        if (download.isPresent()) {
            DownloadRequest request = download.get();
            request.target().getParent().toFile().mkdirs();
            System.out.printf("Downloading jenkins plugin %s (%s)...%n", spec.name(), spec.version());
            copyURLToFile(request.source().toURL(), request.target().toFile());
        }

        File plugin = cacheLayout().pluginFile(spec.name(), spec.version()).toFile();
        if (!plugin.isFile()) {
            throw new IOException("Plugin artifact is missing from the cache: " + plugin);
        }
        Files.createSymbolicLink(install.toPath(), plugin.toPath());
    }

    /** Lazily initialises the cache layout from the configured cache directory. */
    private CacheLayout cacheLayout() {
        if (cacheLayout == null) {
            cacheLayout = new CacheLayout(cache.toPath());
        }
        return cacheLayout;
    }

    public ClassLoader createJenkinsWarClassLoader() throws PrivilegedActionException {
        return AccessController.doPrivileged((PrivilegedExceptionAction<ClassLoader>) () -> new ClassLoaderBuilder(new SideClassLoader(getPlatformClassloader()))
                .collectJars(new File(getLauncherOptions().warDir, "WEB-INF/lib"))
                // In this mode we also take Jetty from the Jenkins core
                .collectJars(new File(getLauncherOptions().warDir, "executable/winstone.jar"))
                // servlet API needs to be visible to jenkins.war
                .collectJars(new File(getAppRepo(), "javax/servlet"))
                .make());
    }

    public ClassLoader createSetupClassLoader(ClassLoader jenkins) throws IOException {
        return new ClassLoaderBuilder(jenkins)
                .collectJars(getAppRepo())
                .collectJars(getSetupJarDir())
                // Payload should be skipped, otherwise it will be loaded with a wrong classloader when no bundled plugin jars
                // NOTE: Not relevant for slim JARs
                .excludeJars(new File(getAppRepo(), "io/jenkins/jenkinsfile-runner/payload"))
                .make();
    }

    public int runJenkinsfileRunnerApp() throws Throwable {
        String appClassName = getAppClassName();
        if (hasClass(appClassName)) {
            Class<?> c = Class.forName(appClassName);
            return  ((IApp) c.getDeclaredConstructor().newInstance()).run(this);
        }

        // Slim packaging (no bundled WAR or plugins)
        ClassLoader jenkins = createJenkinsWarClassLoader();
        ClassLoader setup = createSetupClassLoader(jenkins);

        Thread.currentThread().setContextClassLoader(setup);    // or should this be 'jenkins'?

        try {
            Class<?> c = setup.loadClass(appClassName);
            return ((IApp) c.getDeclaredConstructor().newInstance()).run(this);
        } catch (ClassNotFoundException e) {
            if (setup instanceof URLClassLoader) {
                throw new ClassNotFoundException(e.getMessage() + " not found in " + getAppRepo() + ","
                        + new File(getLauncherOptions().warDir, "WEB-INF/lib") + " " + Arrays.toString(((URLClassLoader) setup).getURLs()),
                        e);
            } else {
                throw e;
            }
        }
    }

    /**
     * In JDK 11, platform classes are not accessible by default but through the platform classloader.
     */
    private ClassLoader getPlatformClassloader() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if (isPostJava8()) {
            return (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
        }

        return null;
    }

    private static boolean isPostJava8() {
        String javaVersion = System.getProperty("java.version");
        return !javaVersion.startsWith("1.");
    }

    public boolean hasClass(String className) {
        try  {
            Class.forName(className);
            return true;
        }  catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        }
    }

    /**
     * Location of the app repo packaged by AppAssembler.
     * Depending on the configuration, it might include full or slim packaging.
     * @return Path to the app repo
     */
    public File getAppRepo() {
        return new File(System.getProperty("app.repo"));
    }

    /**
     * Location of the app repo packaged by AppAssembler.
     * Depending on the configuration, it might include full or slim packaging.
     * @return Path to the app repo
     */
    public File getLibDirectory() {
        if (launcherOptions.libPath != null) {
            return launcherOptions.libPath;
        }
        return new File(System.getProperty("app.repo"), "../lib");
    }

    public File getSetupJarDir() throws IOException {
        File setupJar = new File(getLibDirectory(), "setup/");
        if (!setupJar.exists()) {
            throw new IOException("Setup JAR is missing: " + setupJar);
        }
        return setupJar;
    }

    public File getPayloadJarDir() throws IOException {
        File payloadJar = new File(getLibDirectory(), "payload/");
        if (!payloadJar.exists()) {
            throw new IOException("Payload JAR is missing: " + payloadJar);
        }
        return payloadJar;
    }
}
