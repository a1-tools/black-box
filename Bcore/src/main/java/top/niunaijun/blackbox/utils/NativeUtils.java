package top.niunaijun.blackbox.utils;

import android.os.Build;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class NativeUtils {
    public static final String TAG = "VirtualM";

    private static boolean contains(String[] array, String value) {
        if (array == null || value == null) return false;
        for (String item : array) {
            if (value.equals(item)) return true;
        }
        return false;
    }

    /**
     * ABI names ordered by preference. Only the first ABI that actually contains a .so
     * is extracted: extracting several ABIs into the same flat directory would let a
     * 32-bit library overwrite the 64-bit one on a 64-bit device.
     */
    private static String[] wantedAbis() {
        String[] supported = Build.SUPPORTED_ABIS;
        int extra = 0;
        if (!contains(supported, Build.CPU_ABI) && Build.CPU_ABI != null) extra++;
        if (!contains(supported, "armeabi")) extra++;

        String[] result = new String[(supported == null ? 0 : supported.length) + extra];
        int i = 0;
        if (supported != null) {
            for (String abi : supported) {
                if (abi != null) result[i++] = abi;
            }
        }
        if (Build.CPU_ABI != null && !contains(supported, Build.CPU_ABI)) result[i++] = Build.CPU_ABI;
        if (!contains(supported, "armeabi")) result[i++] = "armeabi";
        return result;
    }

    /**
     * Extract native libraries from the APK into {@code nativeLibDir}.
     *
     * Some Android-on-non-AOSP compatibility layers (e.g. container based runtimes)
     * reject the random-access ZipFile open while still allowing a plain sequential
     * read. Try ZipFile first for speed, then fall back to ZipInputStream.
     */
    public static void copyNativeLib(File apk, File nativeLibDir) throws Exception {
        long startTime = System.currentTimeMillis();

        if (!nativeLibDir.exists() && !nativeLibDir.mkdirs()) {
            throw new IOException("Cannot create native lib dir: " + nativeLibDir
                    + " (isDirectory=" + nativeLibDir.isDirectory()
                    + ", parentWritable=" + (nativeLibDir.getParentFile() == null
                    ? "n/a" : String.valueOf(nativeLibDir.getParentFile().canWrite())) + ")");
        }
        if (!nativeLibDir.isDirectory()) {
            throw new IOException("Native lib path is not a directory: " + nativeLibDir);
        }

        String[] abis = wantedAbis();
        Log.d(TAG, "copyNativeLib apk=" + apk.getAbsolutePath()
                + ", size=" + apk.length() + ", libDir=" + nativeLibDir
                + ", abis=" + java.util.Arrays.toString(abis));

        Exception primaryFailure;
        try (ZipFile zipfile = new ZipFile(apk.getAbsolutePath())) {
            for (String abi : abis) {
                if (findAndCopyNativeLib(zipfile, abi, nativeLibDir)) {
                    Log.d(TAG, "Selected ABI: " + abi);
                    break;
                }
            }
            Log.d(TAG, "Done! +" + (System.currentTimeMillis() - startTime) + "ms (ZipFile)");
            return;
        } catch (Exception e) {
            primaryFailure = e;
            Log.w(TAG, "ZipFile extraction failed, retrying with ZipInputStream", e);
        }

        try {
            copyFromStream(apk, abis, nativeLibDir);
            Log.d(TAG, "Done! +" + (System.currentTimeMillis() - startTime) + "ms (ZipInputStream fallback)");
        } catch (Exception e) {
            if (primaryFailure != null) e.addSuppressed(primaryFailure);
            throw e;
        }
    }

    private static void copyFromStream(File apk, String[] abis, File nativeLibDir)
            throws IOException {
        for (String abi : abis) {
            boolean found;
            try (ZipInputStream zis = new ZipInputStream(
                    new BufferedInputStream(new FileInputStream(apk)))) {
                found = copyFromStreamOnce(zis, abi, nativeLibDir);
            }
            if (found) {
                Log.d(TAG, "Selected ABI (stream): " + abi);
                return;
            }
        }
    }

    private static boolean copyFromStreamOnce(ZipInputStream zis, String abi, File nativeLibDir)
            throws IOException {
        boolean found = false;
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            if (!name.startsWith("lib/") || !name.endsWith(".so")) {
                zis.closeEntry();
                continue;
            }
            String entryAbi = name.substring(4, name.lastIndexOf('/'));
            if (!abi.equals(entryAbi)) {
                zis.closeEntry();
                continue;
            }
            String libName = name.substring(name.lastIndexOf('/') + 1);
            Log.d(TAG, "Found so via stream: " + name);
            writeLib(nativeLibDir, libName, entry.getSize(), zis);
            found = true;
            zis.closeEntry();
        }
        return found;
    }

    private static boolean findAndCopyNativeLib(ZipFile zipfile, String cpuArch, File nativeLibDir)
            throws Exception {
        Log.d(TAG, "Try to copy plugin's cup arch: " + cpuArch);
        boolean findLib = false;
        boolean findSo = false;
        String libPrefix = "lib/" + cpuArch + "/";
        Enumeration<? extends ZipEntry> e = zipfile.entries();

        while (e.hasMoreElements()) {
            ZipEntry entry = e.nextElement();
            String entryName = entry.getName();
            if (!findLib && !entryName.startsWith("lib/")) {
                continue;
            }
            findLib = true;
            if (!entryName.endsWith(".so") || !entryName.startsWith(libPrefix)) {
                continue;
            }

            if (!findSo) {
                findSo = true;
                Log.d(TAG, "Found plugin's cup arch dir: " + cpuArch);
            }

            String libName = entryName.substring(entryName.lastIndexOf('/') + 1);
            Log.d(TAG, "verify so " + libName);
            try (InputStream in = zipfile.getInputStream(entry)) {
                writeLib(nativeLibDir, libName, entry.getSize(), in);
            }
        }

        if (!findLib) {
            Log.d(TAG, "Fast skip all!");
            return true;
        }
        return findSo;
    }

    private static void writeLib(File nativeLibDir, String libName, long expectedSize, InputStream in)
            throws IOException {
        File libFile = new File(nativeLibDir, libName);
        // The destination is rebuilt on every install, so an existing file means it was
        // already written from a preferred ABI. Never overwrite it with another ABI.
        if (libFile.exists()) {
            Log.d(TAG, libName + " skip copy (already present)");
            return;
        }

        Log.d(TAG, "copy so " + libName + " to " + nativeLibDir);
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(libFile))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0) {
                output.write(buffer, 0, count);
            }
        }
    }
}
