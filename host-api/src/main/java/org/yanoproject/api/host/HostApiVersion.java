package org.yanoproject.api.host;

/** Independent host contract identity, unrelated to the Yano or extension release number. */
public record HostApiVersion(int major, int level) {
    public static final HostApiVersion CURRENT = new HostApiVersion(1, 1);

    public HostApiVersion {
        if (major < 1 || level < 1) throw new IllegalArgumentException("Host API major and level must be positive");
    }

    public void requireCompatible(int requiredMajor, int minimumLevel) {
        if (requiredMajor < 1 || minimumLevel < 1) {
            throw new IllegalArgumentException("Invalid required Host API version");
        }
        if (major != requiredMajor || level < minimumLevel) {
            throw new IllegalStateException("Extension requires Yano Host API " + requiredMajor + "." + minimumLevel
                    + " or a newer level in that major; host provides " + major + "." + level);
        }
    }
}
