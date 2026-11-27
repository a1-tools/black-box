package top.niunaijun.blackbox.utils.compat;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Minimal reader for the compiled {@code resources.arsc} of an APK.
 *
 * <p>Why this exists: manifest {@code <meta-data android:value="@integer/x"/>} keeps a
 * <em>resource reference</em> in the binary AndroidManifest.xml. The platform resolves it, and
 * SDKs rely on that — play-services-basement compares
 * {@code Bundle.getInt("com.google.android.gms.version")} against {@code 12451000}, not against
 * {@code 0x7f0a000f}. A virtual framework that only copies the raw id makes every GMS app throw
 * {@code GooglePlayServicesIncorrectManifestValueException}.</p>
 *
 * <p>Only simple (non-bag) values are read, which covers integers, booleans, floats, colors and
 * string resources — everything a meta-data value can sensibly point at. No Android APIs and no
 * hidden APIs are used, so it works in any process including API 33+ containers where
 * {@code AssetManager.addAssetPath} and friends are restricted.</p>
 */
public final class ApkResourceTable {
    private static final String TAG = "ApkResourceTable";

    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_TABLE = 0x0002;
    private static final int CHUNK_PACKAGE = 0x0200;
    private static final int CHUNK_TYPE = 0x0201;

    private static final int TYPE_NULL = 0x00;
    private static final int TYPE_REFERENCE = 0x01;
    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_FLOAT = 0x04;
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_INT_HEX = 0x11;
    private static final int TYPE_INT_BOOLEAN = 0x12;
    private static final int TYPE_FIRST_COLOR_INT = 0x1c;
    private static final int TYPE_LAST_COLOR_INT = 0x1f;

    private static final int TYPE_FLAG_SPARSE = 0x01;
    private static final int TYPE_FLAG_OFFSET16 = 0x02;
    private static final int ENTRY_FLAG_COMPLEX = 0x0001;
    private static final int ENTRY_FLAG_COMPACT = 0x0008;
    private static final int NO_ENTRY = -1;

    /** Reference chains longer than this are treated as broken data. */
    private static final int MAX_DEPTH = 3;

    private ApkResourceTable() {
    }

    /** A resolved resource value. {@code string} is non-null only for TYPE_STRING. */
    public static final class Value {
        public final int type;
        public final int data;
        public final String string;

        Value(int type, int data, String string) {
            this.type = type;
            this.data = data;
            this.string = string;
        }

        public boolean isInt() {
            return (type >= TYPE_INT_DEC && type <= TYPE_INT_BOOLEAN)
                    || (type >= TYPE_FIRST_COLOR_INT && type <= TYPE_LAST_COLOR_INT);
        }

        public boolean isFloat() {
            return type == TYPE_FLOAT;
        }

        public boolean isBoolean() {
            return type == TYPE_INT_BOOLEAN;
        }

        @Override
        public String toString() {
            return "Value{type=0x" + Integer.toHexString(type) + ", data=" + data
                    + (string != null ? ", string=" + string : "") + "}";
        }
    }

    /**
     * Resolve a resource id inside the APK's resource table.
     *
     * @return the typed value, or null when the APK has no resource table or the id is missing
     */
    public static Value lookup(String apkPath, int resId) {
        if (apkPath == null || resId == 0) return null;
        Table table;
        try {
            table = loadTable(apkPath);
        } catch (Throwable t) {
            Log.w(TAG, "read resources.arsc failed for " + apkPath + ": " + t);
            return null;
        }
        if (table == null) return null;
        try {
            return lookupInternal(table, resId, MAX_DEPTH);
        } catch (Throwable t) {
            Log.w(TAG, "lookup 0x" + Integer.toHexString(resId) + " failed: " + t);
            return null;
        }
    }

    /**
     * One APK's resources.arsc plus its lazily decoded global string pool. A manifest with several
     * {@code @ref} meta-data used to re-read the whole table (often MBs) and re-decode the pool
     * (up to 200k strings) for every single reference, although integers never need the pool.
     */
    private static final class Table {
        final String key;
        final byte[] data;
        private int poolStart = -1;
        private int poolSize;
        private String[] pool;

        Table(String key, byte[] data) {
            this.key = key;
            this.data = data;
        }

        synchronized String string(int index) {
            if (pool == null) {
                pool = poolStart < 0 ? new String[0]
                        : ApkManifestParser.readStringPool(data, poolStart, poolSize);
            }
            return ApkManifestParser.str(pool, index);
        }
    }

    /** Last table only, softly held: it is large and only needed while one manifest is parsed. */
    private static java.lang.ref.SoftReference<Table> sLast;

    private static synchronized Table loadTable(String apkPath) throws IOException {
        java.io.File f = new java.io.File(apkPath);
        String key = apkPath + "|" + f.length() + "|" + f.lastModified();
        Table cached = sLast == null ? null : sLast.get();
        if (cached != null && cached.key.equals(key)) return cached;
        byte[] bytes = readResourceTable(apkPath);
        if (bytes == null) return null;
        Table t = new Table(key, bytes);
        sLast = new java.lang.ref.SoftReference<>(t);
        return t;
    }

    private static byte[] readResourceTable(String apkPath) throws IOException {
        ZipFile zip = null;
        try {
            zip = new ZipFile(apkPath);
            ZipEntry entry = zip.getEntry("resources.arsc");
            if (entry == null) return null;
            InputStream in = zip.getInputStream(entry);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, (int) Math.min(entry.getSize(), 1 << 20)));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toByteArray();
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static Value lookupInternal(Table table, int resId, int depth) {
        if (table == null || depth <= 0) return null;
        byte[] arsc = table.data;
        if (arsc.length < 12 || ApkManifestParser.u16(arsc, 0) != CHUNK_TABLE) return null;
        int total = Math.min(ApkManifestParser.u32(arsc, 4), arsc.length);
        int pkgId = (resId >>> 24) & 0xff;
        int typeId = (resId >>> 16) & 0xff;
        int entryIdx = resId & 0xffff;

        int p = ApkManifestParser.u16(arsc, 2);
        if (p < 8) p = 8;
        while (p + 8 <= total) {
            int type = ApkManifestParser.u16(arsc, p);
            int headerSize = ApkManifestParser.u16(arsc, p + 2);
            int size = ApkManifestParser.u32(arsc, p + 4);
            if (size < 8 || headerSize < 8 || (long) p + size > total) break;
            if (type == CHUNK_STRING_POOL) {
                if (table.poolStart < 0) { // 只记位置，真正需要字符串时才解码
                    table.poolStart = p;
                    table.poolSize = size;
                }
            } else if (type == CHUNK_PACKAGE) {
                Value v = lookupInPackage(arsc, p, size, pkgId, typeId, entryIdx);
                if (v != null) {
                    if (v.type == TYPE_STRING) {
                        v = new Value(v.type, v.data, table.string(v.data));
                    }
                    if (v.type == TYPE_REFERENCE && depth > 1) {
                        Value target = lookupInternal(table, v.data, depth - 1);
                        if (target != null) return target;
                    }
                    return v;
                }
            }
            p += size;
        }
        return null;
    }

    private static Value lookupInPackage(byte[] arsc, int start, int size,
                                         int pkgId, int typeId, int entryIdx) {
        if (start + 288 > arsc.length) return null;
        if (ApkManifestParser.u32(arsc, start + 8) != pkgId) return null;
        int headerSize = ApkManifestParser.u16(arsc, start + 2);
        if (headerSize < 288) headerSize = 288;
        int end = Math.min(start + size, arsc.length);
        Value fallback = null;
        int p = start + headerSize;
        while (p + 8 <= end) {
            int type = ApkManifestParser.u16(arsc, p);
            int chunkHeader = ApkManifestParser.u16(arsc, p + 2);
            int chunkSize = ApkManifestParser.u32(arsc, p + 4);
            if (chunkSize < 8 || chunkHeader < 8 || (long) p + chunkSize > end) break;
            if (type == CHUNK_TYPE) {
                Value v = valueFromTypeChunk(arsc, p, chunkSize, typeId, entryIdx);
                if (v != null) {
                    // Prefer the default configuration; keep the first match as a fallback.
                    if (isDefaultConfig(arsc, p)) return v;
                    if (fallback == null) fallback = v;
                }
            }
            p += chunkSize;
        }
        return fallback;
    }

    private static boolean isDefaultConfig(byte[] arsc, int chunkStart) {
        int configSize = ApkManifestParser.u32(arsc, chunkStart + 20);
        if (configSize < 4) return true;
        int end = Math.min(chunkStart + 20 + configSize, arsc.length);
        for (int i = chunkStart + 24; i < end; i++) {
            if (arsc[i] != 0) return false;
        }
        return true;
    }

    private static Value valueFromTypeChunk(byte[] arsc, int start, int size,
                                            int typeId, int entryIdx) {
        int type = arsc[start + 8] & 0xff;
        if (type != typeId) return null;
        int flags = arsc[start + 9] & 0xff;
        int entryCount = ApkManifestParser.u32(arsc, start + 12);
        int entriesStart = ApkManifestParser.u32(arsc, start + 16);
        int configSize = ApkManifestParser.u32(arsc, start + 20);
        int offsetsPos = start + 20 + configSize;

        int entryOffset = NO_ENTRY;
        if ((flags & TYPE_FLAG_SPARSE) != 0) {
            // Sparse encoding (Android 12+): uint16 index + uint16 offset/4 per entry.
            for (int i = 0; i < entryCount; i++) {
                int o = offsetsPos + i * 4;
                if (o + 4 > arsc.length) break;
                if (ApkManifestParser.u16(arsc, o) == entryIdx) {
                    entryOffset = ApkManifestParser.u16(arsc, o + 2) * 4;
                    break;
                }
            }
        } else if ((flags & TYPE_FLAG_OFFSET16) != 0) {
            // 16 位偏移（单位 4 字节），0xFFFF 表示没有这个 entry
            if (entryIdx < 0 || entryIdx >= entryCount) return null;
            int o = offsetsPos + entryIdx * 2;
            if (o + 2 > arsc.length) return null;
            int v = ApkManifestParser.u16(arsc, o);
            entryOffset = v == 0xFFFF ? NO_ENTRY : v * 4;
        } else {
            if (entryIdx < 0 || entryIdx >= entryCount) return null;
            int o = offsetsPos + entryIdx * 4;
            if (o + 4 > arsc.length) return null;
            entryOffset = ApkManifestParser.u32(arsc, o);
        }
        if (entryOffset == NO_ENTRY) return null;

        int entryPos = start + entriesStart + entryOffset;
        if (entryPos + 8 > arsc.length) return null;
        int entrySize = ApkManifestParser.u16(arsc, entryPos);
        int entryFlags = ApkManifestParser.u16(arsc, entryPos + 2);
        if ((entryFlags & ENTRY_FLAG_COMPACT) != 0) {
            // compact entry：key 占原 size 字段，数据类型在 flags 高字节，数据紧跟其后
            if (entryPos + 8 > arsc.length) return null;
            int compactType = (entryFlags >> 8) & 0xff;
            if (compactType == TYPE_NULL) return null;
            return new Value(compactType, ApkManifestParser.u32(arsc, entryPos + 4), null);
        }
        if ((entryFlags & ENTRY_FLAG_COMPLEX) != 0) return null; // bag/array, not a simple value

        int valuePos = entryPos + entrySize;
        if (valuePos + 8 > arsc.length) return null;
        int dataType = arsc[valuePos + 3] & 0xff;
        int data = ApkManifestParser.u32(arsc, valuePos + 4);
        if (dataType == TYPE_NULL) return null;
        return new Value(dataType, data, null); // 字符串值由 lookupInternal 按需补上
    }
}
