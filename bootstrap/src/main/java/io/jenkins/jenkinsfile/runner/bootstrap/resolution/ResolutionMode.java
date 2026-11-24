package io.jenkins.jenkinsfile.runner.bootstrap.resolution;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Whether resolution may reach the network.
 */
public enum ResolutionMode {
    /** Present artifacts are reused according to the cache policy; missing or stale ones are fetched. */
    ONLINE,
    /** Nothing is fetched. Any required artifact absent from the cache is reported as missing. */
    OFFLINE
}
