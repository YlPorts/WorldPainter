package com.ylports.worldpainter;

public final class NativeBedrock {
    private static final boolean LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary("worldpainter_bedrock");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
        }
        LOADED = loaded;
    }

    private NativeBedrock() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    public static native String exportWorld(
            String outputDirectory,
            String worldName,
            int width,
            int depth,
            int seaLevel,
            int[] heights,
            byte[] materials,
            byte[] waterMask);
}
