package io.jenkins.jenkinsfile.runner.bootstrap.commands;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import picocli.CommandLine;

import java.io.File;

/**
 * Arguments needed to launch a Jenkins instance.
 * @see JenkinsLauncherCommand
 */
public class JenkinsLauncherOptions {

    /**
     * Exploded jenkins.war
     */
    @CommandLine.Option(names = { "-w", "--jenkins-war" },
            description = "Path to exploded jenkins war directory." +
                    "Depending on packaging, it may contain the entire WAR " +
                    "or just resources to be loaded by the WAR file, for example Groovy hooks or extra libraries.")
    @CheckForNull
    public File warDir;

    /**
     * Where to load plugins from?
     */
    @CommandLine.Option(names = { "-p", "--plugins" },
            description = "Plugins required to run pipeline. Either a plugins.txt file or a /plugins installation directory. Defaults to plugins.txt")
    public File pluginsDir;

    @CommandLine.Option(names = { "-jv", "--jenkins-version"},
            description = "Jenkins version to use if Jenkins WAR is not specified by --jenkins-war. Defaults to the latest LTS")
    @CheckForNull
    public String version;

    @CommandLine.Option(names = { "-m", "--mirror"},
            description = "Base URL used to download the Jenkins WAR and plugins when they are not cached. " +
                    "The trailing slash is optional. Supported schemes: http, https, file. " +
                    "It does not affect stable/latestCore.txt; use --mirrorCore for that. " +
                    "Get the mirror list from http://mirrors.jenkins-ci.org/status.html")
    public String mirror;

    @CommandLine.Option(names = "--mirrorCore",
            description = "Base URL used to download stable/latestCore.txt (the latest LTS version number). " +
                    "Defaults to http://updates.jenkins.io. Only set this when the mirror serves the same 'stable' path.")
    public String mirrorCore;

    @CommandLine.Option(names = "--offline",
            description = "Never use the network. All required artifacts must already be present in the cache; " +
                    "if any are missing, they are all reported before anything is installed and the run fails. " +
                    "Without --jenkins-version the cached war/latest.txt is used regardless of its age, " +
                    "and 'latest' plugins use the cached 'latest' entry regardless of its age.")
    public boolean offline;

    /**
     * Jenkins Home for the Run.
     */
    @CheckForNull
    @CommandLine.Option(names = {"--jenkinsHome", "--runHome"},
            description = "Path to the empty Jenkins Home directory to use for this run. If not specified a temporary directory will be created. " +
                    "Note that the specified folder will not be disposed after the run")
    public File jenkinsHome;

    @CheckForNull
    @CommandLine.Option(names = "--withInitHooks",
            description = "Path to a directory containing Groovy Init Hooks to copy into init.groovy.d")
    public File withInitHooks;

    @CommandLine.Option(names = "--skipShutdown",
            description = "Forces Jenkinsfile Runner to skip the shutdown logic. " +
                    "It reduces the instance termination time but may lead to unexpected behavior in plugins " +
                    "which release external resources on clean up synchronous task queues on shutdown.")
    public boolean skipShutdown;

    @CheckForNull
    @CommandLine.Option(names = "--libPath",
            description = "When a slim packaging is used, points to the library directory which contains payload.jar and setup.jar files")
    public File libPath;

    @CheckForNull
    @CommandLine.Option(names = "--httpPort",
            description = "Port for exposing the web server and Jenkins Web UI from Jenkinsfile Runner. Disabled by default")
    public Integer httpPort;

    @CheckForNull
    @CommandLine.Option(names = "--httpPath",
                        description = "Path (including leading / but no trailing /) for exposing the web server and Jenkins Web UI from Jenkinsfile Runner. Root path (/) by default")
    public String httpPath;

    @CommandLine.Option(names = "--openWebUI",
            description = "Open Jenkins Web UI in the default browser, '--httpPort' is expected to be defined together with this option")
    public boolean openWebUI;

    @CommandLine.Option(names = "--waitOnExit",
            description = "Keep Jenkinsfile Runner running upon job completion without various sleep() hacks in the Pipeline")
    public boolean waitOnExit;

    @CheckForNull
    @CommandLine.Option(names = "--agentPort",
            description = "Port for connecting inbound Jenkins agents (over JNLP or WebSockets). Disabled by default")
    public Integer agentPort;
}
