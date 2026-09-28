package com.ylports.worldpainter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class McworldExporter {
    private McworldExporter() {
    }

    public static void zipWorld(File sourceDirectory, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("No se pudo crear la carpeta de exportación");
        }
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(destination)))) {
            addDirectory(zip, sourceDirectory, sourceDirectory.getAbsolutePath().length() + 1);
        }
    }

    private static void addDirectory(ZipOutputStream zip, File directory, int prefixLength) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        byte[] buffer = new byte[64 * 1024];
        for (File file : files) {
            if (file.isDirectory()) {
                addDirectory(zip, file, prefixLength);
                continue;
            }
            String relative = file.getAbsolutePath().substring(prefixLength).replace(File.separatorChar, '/');
            ZipEntry entry = new ZipEntry(relative);
            zip.putNextEntry(entry);
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    zip.write(buffer, 0, read);
                }
            }
            zip.closeEntry();
        }
    }

    public static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
