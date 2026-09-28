package com.ylports.worldpainter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

public final class WorldModel {
    public static final int MODE_RAISE = 0;
    public static final int MODE_LOWER = 1;
    public static final int MODE_SMOOTH = 2;
    public static final int MODE_GRASS = 3;
    public static final int MODE_SAND = 4;
    public static final int MODE_STONE = 5;
    public static final int MODE_WATER = 6;

    public static final byte MAT_GRASS = 0;
    public static final byte MAT_SAND = 1;
    public static final byte MAT_STONE = 2;

    private static final int MAGIC = 0x57504241;
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_HISTORY = 20;

    public final int width;
    public final int depth;
    public int seaLevel;
    public final int[] heights;
    public final byte[] materials;
    public final byte[] water;

    private final ArrayDeque<Snapshot> undo = new ArrayDeque<>();
    private final ArrayDeque<Snapshot> redo = new ArrayDeque<>();
    private Snapshot strokeStart;

    public WorldModel(int width, int depth, int seaLevel) {
        this.width = width;
        this.depth = depth;
        this.seaLevel = seaLevel;
        this.heights = new int[width * depth];
        this.materials = new byte[width * depth];
        this.water = new byte[width * depth];
        reset();
    }

    public void reset() {
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int i = index(x, z);
                double nx = (x - width / 2.0) / width;
                double nz = (z - depth / 2.0) / depth;
                double ridge = Math.sin(x * 0.095) * 2.2 + Math.cos(z * 0.083) * 2.0;
                double island = Math.max(0.0, 1.0 - Math.sqrt(nx * nx + nz * nz) * 1.55) * 5.0;
                heights[i] = clampHeight((int) Math.round(62 + ridge + island));
                materials[i] = MAT_GRASS;
                water[i] = 0;
            }
        }
        undo.clear();
        redo.clear();
        strokeStart = null;
    }

    public int index(int x, int z) {
        return z * width + x;
    }

    public boolean inside(int x, int z) {
        return x >= 0 && z >= 0 && x < width && z < depth;
    }

    public void beginStroke() {
        if (strokeStart == null) {
            strokeStart = snapshot();
        }
    }

    public void endStroke() {
        if (strokeStart == null) {
            return;
        }
        if (!sameAs(strokeStart)) {
            undo.addLast(strokeStart);
            while (undo.size() > MAX_HISTORY) {
                undo.removeFirst();
            }
            redo.clear();
        }
        strokeStart = null;
    }

    public boolean undo() {
        endStroke();
        if (undo.isEmpty()) {
            return false;
        }
        redo.addLast(snapshot());
        restore(undo.removeLast());
        return true;
    }

    public boolean redo() {
        endStroke();
        if (redo.isEmpty()) {
            return false;
        }
        undo.addLast(snapshot());
        restore(redo.removeLast());
        return true;
    }

    public void applyBrush(float centerX, float centerZ, int radius, int mode) {
        int minX = Math.max(0, (int) Math.floor(centerX - radius));
        int maxX = Math.min(width - 1, (int) Math.ceil(centerX + radius));
        int minZ = Math.max(0, (int) Math.floor(centerZ - radius));
        int maxZ = Math.min(depth - 1, (int) Math.ceil(centerZ + radius));
        float rr = radius * radius;
        int[] originalHeights = mode == MODE_SMOOTH ? heights.clone() : null;

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                float dx = x - centerX;
                float dz = z - centerZ;
                float distSq = dx * dx + dz * dz;
                if (distSq > rr) {
                    continue;
                }
                float dist = (float) Math.sqrt(distSq);
                float falloff = radius <= 1 ? 1f : Math.max(0.15f, 1f - dist / radius);
                int i = index(x, z);

                switch (mode) {
                    case MODE_RAISE:
                        heights[i] = clampHeight(heights[i] + Math.max(1, Math.round(3f * falloff)));
                        break;
                    case MODE_LOWER:
                        heights[i] = clampHeight(heights[i] - Math.max(1, Math.round(3f * falloff)));
                        break;
                    case MODE_SMOOTH:
                        heights[i] = smoothHeight(originalHeights, x, z, falloff);
                        break;
                    case MODE_GRASS:
                        materials[i] = MAT_GRASS;
                        water[i] = 0;
                        break;
                    case MODE_SAND:
                        materials[i] = MAT_SAND;
                        water[i] = 0;
                        break;
                    case MODE_STONE:
                        materials[i] = MAT_STONE;
                        water[i] = 0;
                        break;
                    case MODE_WATER:
                        water[i] = 1;
                        if (heights[i] >= seaLevel) {
                            heights[i] = seaLevel - 2;
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private int smoothHeight(int[] source, int x, int z, float falloff) {
        int sum = 0;
        int count = 0;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int px = x + dx;
                int pz = z + dz;
                if (!inside(px, pz)) {
                    continue;
                }
                sum += source[index(px, pz)];
                count++;
            }
        }
        int current = source[index(x, z)];
        int avg = count == 0 ? current : Math.round(sum / (float) count);
        return clampHeight(Math.round(current + (avg - current) * Math.max(0.25f, falloff)));
    }

    private int clampHeight(int value) {
        return Math.max(4, Math.min(120, value));
    }

    private Snapshot snapshot() {
        return new Snapshot(heights.clone(), materials.clone(), water.clone(), seaLevel);
    }

    private void restore(Snapshot snapshot) {
        System.arraycopy(snapshot.heights, 0, heights, 0, heights.length);
        System.arraycopy(snapshot.materials, 0, materials, 0, materials.length);
        System.arraycopy(snapshot.water, 0, water, 0, water.length);
        seaLevel = snapshot.seaLevel;
    }

    private boolean sameAs(Snapshot snapshot) {
        return snapshot.seaLevel == seaLevel
                && Arrays.equals(snapshot.heights, heights)
                && Arrays.equals(snapshot.materials, materials)
                && Arrays.equals(snapshot.water, water);
    }

    public void save(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("No se pudo crear " + parent);
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeInt(width);
            out.writeInt(depth);
            out.writeInt(seaLevel);
            for (int height : heights) {
                out.writeShort(height);
            }
            out.write(materials);
            out.write(water);
        }
    }

    public static WorldModel loadOrNew(File file) {
        if (!file.isFile()) {
            return new WorldModel(128, 128, 62);
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Formato desconocido");
            }
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Versión de proyecto no compatible: " + version);
            }
            int width = in.readInt();
            int depth = in.readInt();
            int seaLevel = in.readInt();
            if (width < 16 || depth < 16 || width > 2048 || depth > 2048) {
                throw new IOException("Dimensiones inválidas");
            }
            WorldModel model = new WorldModel(width, depth, seaLevel);
            for (int i = 0; i < model.heights.length; i++) {
                model.heights[i] = model.clampHeight(in.readUnsignedShort());
            }
            in.readFully(model.materials);
            in.readFully(model.water);
            model.undo.clear();
            model.redo.clear();
            return model;
        } catch (IOException e) {
            return new WorldModel(128, 128, 62);
        }
    }

    private static final class Snapshot {
        final int[] heights;
        final byte[] materials;
        final byte[] water;
        final int seaLevel;

        Snapshot(int[] heights, byte[] materials, byte[] water, int seaLevel) {
            this.heights = heights;
            this.materials = materials;
            this.water = water;
            this.seaLevel = seaLevel;
        }
    }
}
