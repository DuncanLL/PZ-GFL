package gfl2.motion;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Parser and cubic-Bezier evaluator for MMD VMD v2 camera tracks. */
public final class VmdCamera {
    private static final long MAX_BYTES = 128L * 1024 * 1024;

    public record Sample(
            float targetX, float targetY, float targetZ,
            float rotX, float rotY, float rotZ,
            float distance, float fovDeg, boolean cut) {}

    private record Key(
            int frame, float distance,
            float x, float y, float z,
            float rx, float ry, float rz,
            byte[] interp, int fov, boolean ortho, int order) {}

    private final Key[] keys;
    private final int endFrame;

    private VmdCamera(Key[] keys) {
        this.keys = keys;
        this.endFrame = keys.length == 0 ? 0 : keys[keys.length - 1].frame;
    }

    public int keyCount() { return keys.length; }
    public int endFrame() { return endFrame; }

    private static long readU32(DataInputStream stream) throws IOException {
        return Integer.toUnsignedLong(Integer.reverseBytes(stream.readInt()));
    }

    private static boolean validHeader(DataInputStream stream) throws IOException {
        byte[] raw = stream.readNBytes(30);
        if (raw.length < 30) return false;
        String header = new String(raw, 0, 25, StandardCharsets.US_ASCII);
        return header.equals("Vocaloid Motion Data 0002") || header.equals("Vocaloid Motion Data file");
    }

    private static void checkFinite(ByteBuffer data, int start, int end) throws IOException {
        for (int at = start; at < end; at += 4) {
            if (!Float.isFinite(data.getFloat(at))) throw new IOException("VMD 镜头含无效数值");
        }
    }

    /** Fast header/section count check without allocating keyframes. */
    public static boolean hasMotionTrack(Path path) {
        try {
            long size = Files.size(path);
            if (size < 58 || size > MAX_BYTES) return false;
            try (var stream = new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 8192))) {
                if (!validHeader(stream)) return false;
                stream.skipNBytes(20);
                long bones = readU32(stream);
                if (bones > (size - 58) / 111) return false;
                if (bones > 0) return true;
                long morphs = readU32(stream);
                return morphs > 0 && morphs <= (size - 58) / 23;
            }
        } catch (IOException e) {
            return false;
        }
    }

    /** Fast check for whether a VMD file contains at least one camera keyframe. */
    public static boolean hasCameraTrack(Path path) {
        try {
            long size = Files.size(path);
            if (size < 62 || size > MAX_BYTES) return false;
            try (var stream = new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 8192))) {
                if (!validHeader(stream)) return false;
                stream.skipNBytes(20);
                long bones = readU32(stream);
                long remain = size - 54;
                if (bones > remain / 111) return false;
                stream.skipNBytes(bones * 111);
                remain -= bones * 111;
                if (remain < 4) return false;
                long morphs = readU32(stream);
                remain -= 4;
                if (morphs > remain / 23) return false;
                stream.skipNBytes(morphs * 23);
                remain -= morphs * 23;
                if (remain < 4) return false;
                long cameras = readU32(stream);
                remain -= 4;
                return cameras > 0 && cameras <= remain / 61;
            }
        } catch (IOException e) {
            return false;
        }
    }

    public static VmdCamera load(Path path) throws IOException {
        long size = Files.size(path);
        if (size < 62 || size > MAX_BYTES) throw new IOException("VMD 文件不完整或超过 128 MiB");
        try (var stream = new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 65536))) {
            if (!validHeader(stream)) throw new IOException("文件没有有效的 VMD 文件头");
            stream.skipNBytes(20);
            long remain = size - 50;
            if (remain < 4) throw new IOException("VMD 骨骼区不完整");
            long bones = readU32(stream);
            remain -= 4;
            if (bones > remain / 111) throw new IOException("VMD 骨骼区不完整");
            stream.skipNBytes(bones * 111);
            remain -= bones * 111;
            if (remain < 4) throw new IOException("VMD 表情区不完整");
            long morphs = readU32(stream);
            remain -= 4;
            if (morphs > remain / 23) throw new IOException("VMD 表情区不完整");
            stream.skipNBytes(morphs * 23);
            remain -= morphs * 23;
            if (remain < 4) throw new IOException("该 VMD 不包含镜头数据");
            long cameras = readU32(stream);
            remain -= 4;
            if (cameras == 0) throw new IOException("该 VMD 不包含镜头数据");
            if (cameras > remain / 61) throw new IOException("VMD 镜头区不完整");

            byte[] buf = new byte[61];
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            List<Key> parsed = new ArrayList<>((int) Math.min(cameras, 65536));
            for (long i = 0; i < cameras; i++) {
                stream.readFully(buf);
                int frame = bb.getInt(0);
                if (frame < 0) frame = 0;
                checkFinite(bb, 4, 32);
                float dist = bb.getFloat(4);
                float x = bb.getFloat(8);
                float y = bb.getFloat(12);
                float z = bb.getFloat(16);
                float rx = bb.getFloat(20);
                float ry = bb.getFloat(24);
                float rz = bb.getFloat(28);
                byte[] interp = new byte[24];
                System.arraycopy(buf, 32, interp, 0, 24);
                int fov = bb.getInt(56);
                boolean ortho = buf[60] != 0;
                parsed.add(new Key(frame, dist, x, y, z, rx, ry, rz, interp, fov, ortho, (int) i));
            }
            parsed.sort(Comparator.comparingInt(Key::frame).thenComparingInt(Key::order));
            // Deduplicate identical frame numbers, keeping the latest record (MMD behavior).
            List<Key> dedup = new ArrayList<>(parsed.size());
            for (Key k : parsed) {
                if (!dedup.isEmpty() && dedup.get(dedup.size() - 1).frame == k.frame) {
                    dedup.set(dedup.size() - 1, k);
                } else {
                    dedup.add(k);
                }
            }
            return new VmdCamera(dedup.toArray(Key[]::new));
        }
    }

    public Sample sample(float frame) {
        if (keys.length == 0) {
            return new Sample(0, 10, 0, 0, 0, 0, -45, 30, false);
        }
        if (keys.length == 1 || frame <= keys[0].frame) {
            Key k = keys[0];
            return new Sample(k.x, k.y, k.z, k.rx, k.ry, k.rz, k.distance, clampFov(k.fov), false);
        }
        Key last = keys[keys.length - 1];
        if (frame >= last.frame) {
            return new Sample(last.x, last.y, last.z, last.rx, last.ry, last.rz, last.distance, clampFov(last.fov), false);
        }
        int lo = 0, hi = keys.length - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (keys[mid].frame <= frame) lo = mid;
            else hi = mid;
        }
        Key a = keys[lo], b = keys[hi];
        int span = b.frame - a.frame;
        if (span <= 1) {
            Key pick = (frame - a.frame) >= 0.5f ? b : a;
            return new Sample(pick.x, pick.y, pick.z, pick.rx, pick.ry, pick.rz, pick.distance, clampFov(pick.fov), true);
        }
        float u = Math.clamp((frame - a.frame) / (float) span, 0f, 1f);
        float tx = evalCurve(u, b.interp, 0);
        float ty = evalCurve(u, b.interp, 4);
        float tz = evalCurve(u, b.interp, 8);
        float tr = evalCurve(u, b.interp, 12);
        float td = evalCurve(u, b.interp, 16);
        float tf = evalCurve(u, b.interp, 20);
        return new Sample(
                lerp(a.x, b.x, tx),
                lerp(a.y, b.y, ty),
                lerp(a.z, b.z, tz),
                lerp(a.rx, b.rx, tr),
                lerp(a.ry, b.ry, tr),
                lerp(a.rz, b.rz, tr),
                lerp(a.distance, b.distance, td),
                clampFov(lerp(a.fov, b.fov, tf)),
                false
        );
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clampFov(float deg) {
        if (!Float.isFinite(deg) || deg <= 0f) return 30f;
        return Math.clamp(deg, 12f, 120f);
    }

    /** MMD VMD camera interpolation bytes per channel: [x1, x2, y1, y2] in 0..127. */
    private static float evalCurve(float u, byte[] interp, int offset) {
        if (u <= 0f) return 0f;
        if (u >= 1f) return 1f;
        float x1 = (interp[offset] & 0xFF) / 127.0f;
        float x2 = (interp[offset + 1] & 0xFF) / 127.0f;
        float y1 = (interp[offset + 2] & 0xFF) / 127.0f;
        float y2 = (interp[offset + 3] & 0xFF) / 127.0f;
        if (Math.abs(x1 - y1) < 1e-4f && Math.abs(x2 - y2) < 1e-4f) return u;
        float lo = 0f, hi = 1f, t = u;
        for (int i = 0; i < 12; i++) {
            t = 0.5f * (lo + hi);
            float x = bezier(t, x1, x2);
            if (x < u) lo = t;
            else hi = t;
        }
        return Math.clamp(bezier(0.5f * (lo + hi), y1, y2), 0f, 1f);
    }

    private static float bezier(float t, float p1, float p2) {
        float inv = 1f - t;
        return 3f * inv * inv * t * p1 + 3f * inv * t * t * p2 + t * t * t;
    }
}
