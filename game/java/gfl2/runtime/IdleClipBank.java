package gfl2.runtime;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.joml.Quaternionf;

/** Compact shared bank of quantized rest-relative idle micro-action clips bundled in the runtime JAR. */
final class IdleClipBank {
    private static final String RESOURCE = "/gfl2/runtime/idle_clips.bin";
    private static final float INV_SHORT = 1f / 32767f;
    private static volatile IdleClipBank INSTANCE;
    private static volatile boolean LOADED;

    static final class Clip {
        final String name;
        final float fps;
        final int frameCount;
        final int boneCount;
        final short[] quats; // [frame * boneCount * 4 + bone * 4 + {x,y,z,w}]

        Clip(String name, float fps, int frameCount, int boneCount, short[] quats) {
            this.name = name;
            this.fps = fps;
            this.frameCount = frameCount;
            this.boneCount = boneCount;
            this.quats = quats;
        }

        float duration() {
            return frameCount <= 1 ? 0f : (frameCount - 1) / fps;
        }

        void sample(int boneIndex, float seconds, Quaternionf tmpA, Quaternionf tmpB, Quaternionf dest) {
            float pos = Math.clamp(seconds * fps, 0f, frameCount - 1);
            int f0 = (int) pos;
            int f1 = Math.min(frameCount - 1, f0 + 1);
            float t = pos - f0;
            readQuat(f0, boneIndex, tmpA);
            if (f0 == f1 || t <= 1e-5f) {
                dest.set(tmpA);
                return;
            }
            readQuat(f1, boneIndex, tmpB);
            tmpA.slerp(tmpB, t, dest).normalize();
        }

        private void readQuat(int frame, int bone, Quaternionf dest) {
            int off = (frame * boneCount + bone) * 4;
            dest.set(quats[off] * INV_SHORT, quats[off + 1] * INV_SHORT,
                     quats[off + 2] * INV_SHORT, quats[off + 3] * INV_SHORT).normalize();
        }
    }

    final String[] boneNames;
    final Clip[] clips;

    private IdleClipBank(String[] boneNames, Clip[] clips) {
        this.boneNames = boneNames;
        this.clips = clips;
    }

    static IdleClipBank get() {
        if (!LOADED) {
            synchronized (IdleClipBank.class) {
                if (!LOADED) {
                    INSTANCE = load();
                    LOADED = true;
                }
            }
        }
        return INSTANCE;
    }

    private static IdleClipBank load() {
        try (InputStream raw = IdleClipBank.class.getResourceAsStream(RESOURCE)) {
            if (raw == null) return null;
            DataInputStream in = new DataInputStream(new BufferedInputStream(raw));
            byte[] magic = new byte[8];
            in.readFully(magic);
            if (!"GFL2IDLE".equals(new String(magic, StandardCharsets.US_ASCII))) return null;
            int version = in.readUnsignedShort();
            if (version != 1) return null;
            int boneCount = in.readUnsignedShort();
            String[] bones = new String[boneCount];
            for (int i = 0; i < boneCount; i++) bones[i] = readString(in);
            int clipCount = in.readUnsignedShort();
            Clip[] clips = new Clip[clipCount];
            for (int c = 0; c < clipCount; c++) {
                String name = readString(in);
                float fps = in.readFloat();
                int frameCount = in.readUnsignedShort();
                short[] data = new short[frameCount * boneCount * 4];
                for (int i = 0; i < data.length; i++) data[i] = in.readShort();
                clips[c] = new Clip(name, fps, frameCount, boneCount, data);
            }
            return new IdleClipBank(bones, clips);
        } catch (IOException e) {
            System.err.println("[GFL2CharacterRuntime] Idle clips unavailable: " + e);
            return null;
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
