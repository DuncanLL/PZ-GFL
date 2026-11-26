package gfl2.runtime;

import gfl2.motion.VmdInput;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;

/** Combines VMD morph tracks before handing playback to the existing single motion layer. */
@Exposer.LuaClass
public final class GFL2VmdFace {
    private static final int HEADER_SIZE = 50;
    private static final int BONE_SIZE = 111;
    private static final int MORPH_SIZE = 23;
    private static final int NAME_SIZE = 15;
    private static final long MAX_VMD_SIZE = 128L * 1024 * 1024;
    private static final Charset NAMES = Charset.forName("windows-31j");

    private GFL2VmdFace() {}

    public static KahluaTable library() {
        try {
            return ReadyRoomPreview.listVmds(gfl2.motion.VmdCamera::hasMorphTrack);
        } catch (Throwable failure) {
            report(failure);
            return J2SEPlatform.getInstance().newTable();
        }
    }

    public static String validate(String selected) {
        try {
            Path face = ReadyRoomPreview.resolveMotionInput(selected);
            return VmdInput.inspect(face).isEmpty() ? UiNotices.text("MotionFaceEmpty") : "";
        } catch (Exception failure) {
            report(failure);
            return UiNotices.text("MotionFaceRead");
        }
    }

    public static String play(IsoGameCharacter character, String action, String selectedFace) {
        if (!(character instanceof IsoPlayer player) || !player.isLocalPlayer()) {
            return UiNotices.text("LocalMotionOnly");
        }
        ModelSession session = Main.session(character);
        if (session == null || !Main.isReady(character)) {
            return UiNotices.text("EnableDoll");
        }
        if (!ReadyRoomPreview.motionAllowed(player)) {
            return UiNotices.text("MotionStandStill");
        }
        try {
            Path motion = ReadyRoomPreview.resolveMotionInput(action);
            Path face = ReadyRoomPreview.resolveMotionInput(selectedFace);
            if (Files.isSameFile(motion, face)) {
                return ReadyRoomPreview.motionCommand(character, "play", action);
            }

            Path directory = Files.createTempDirectory("gfl2-vmd-face-");
            Path combined = directory.resolve(motion.getFileName());
            try {
                combine(motion, face, combined);
                synchronized (session) {
                    if (session.retired || session.failed) {
                        return UiNotices.text("DollSwitching");
                    }
                    if (!ReadyRoomPreview.motionAllowed(player)) {
                        return UiNotices.text("MotionStandStill");
                    }
                    session.model.playMotion(combined);
                }
            } finally {
                deleteTemp(combined);
                deleteTemp(directory);
            }
            return "";
        } catch (Exception failure) {
            report(failure);
            return UiNotices.text("MotionFaceRead");
        }
    }

    static void combine(Path motion, Path face, Path output) throws IOException {
        Map<String, NavigableMap<Long, byte[]>> overlay;
        try (DataInputStream input = open(face)) {
            long size = Files.size(face);
            int boneCount = skipHeader(input, size);
            input.skipNBytes((long) boneCount * BONE_SIZE);
            overlay = readMorphs(input, size - HEADER_SIZE - 8 - (long) boneCount * BONE_SIZE);
        }
        if (overlay.isEmpty()) {
            throw new IOException("No morph keyframes in the selected expression VMD");
        }

        try (DataInputStream input = open(motion);
             DataOutputStream combined = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output), 65536))) {
            long size = Files.size(motion);
            byte[] header = input.readNBytes(HEADER_SIZE);
            validateHeader(header, size);
            combined.write(header);
            int boneCount = readCount(input, size - HEADER_SIZE - 8, BONE_SIZE);
            writeInt(combined, boneCount);
            long end = 0;
            byte[] bone = new byte[BONE_SIZE];
            for (int i = 0; i < boneCount; i++) {
                input.readFully(bone);
                end = Math.max(end, frame(bone, 15));
                combined.write(bone);
            }
            Map<String, NavigableMap<Long, byte[]>> embedded =
                readMorphs(input, size - HEADER_SIZE - 8 - (long) boneCount * BONE_SIZE);
            int originalMorphCount = embedded.values().stream().mapToInt(Map::size).sum();
            for (NavigableMap<Long, byte[]> track : embedded.values()) {
                end = Math.max(end, track.lastKey());
            }
            for (var track : overlay.entrySet()) {
                embedded.put(track.getKey(), trimTrack(track.getValue(), end));
            }
            int mergedMorphCount = embedded.values().stream().mapToInt(Map::size).sum();
            if (size + (long) (mergedMorphCount - originalMorphCount) * MORPH_SIZE > MAX_VMD_SIZE) {
                throw new IOException("Combined VMD exceeds 128 MiB");
            }
            writeMorphs(combined, embedded, mergedMorphCount);
            input.transferTo(combined);
        }
    }

    private static NavigableMap<Long, byte[]> trimTrack(NavigableMap<Long, byte[]> track, long end) {
        if (track.lastKey() <= end) {
            return track;
        }
        var before = track.floorEntry(end);
        var after = track.higherEntry(end);
        NavigableMap<Long, byte[]> keys = new TreeMap<>(track.headMap(end, true));
        if (before != null && before.getKey() == end) {
            return keys;
        }
        byte[] last = (before == null ? after.getValue() : before.getValue()).clone();
        if (before == null) {
            byte[] first = last.clone();
            bytes(first).putInt(NAME_SIZE, 0);
            keys.put(0L, first);
        } else if (after != null) {
            double ratio = (double) (end - before.getKey()) / (after.getKey() - before.getKey());
            float startWeight = bytes(last).getFloat(19);
            float nextWeight = bytes(after.getValue()).getFloat(19);
            bytes(last).putFloat(19, (float) (startWeight + ((double) nextWeight - startWeight) * ratio));
        }
        bytes(last).putInt(NAME_SIZE, (int) end);
        keys.put(end, last);
        return keys;
    }

    private static void writeMorphs(DataOutputStream output, Map<String, NavigableMap<Long, byte[]>> tracks, int count)
        throws IOException {
        writeInt(output, count);
        for (var track : tracks.values()) {
            for (byte[] key : track.values()) {
                output.write(key);
            }
        }
    }

    private static Map<String, NavigableMap<Long, byte[]>> readMorphs(DataInputStream input, long remaining)
        throws IOException {
        int count = readCount(input, remaining, MORPH_SIZE);
        Map<String, NavigableMap<Long, byte[]>> tracks = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            byte[] key = new byte[MORPH_SIZE];
            input.readFully(key);
            if (!Float.isFinite(bytes(key).getFloat(19))) {
                throw new IOException("Invalid VMD morph weight");
            }
            int nameLength = 0;
            while (nameLength < NAME_SIZE && key[nameLength] != 0) {
                nameLength++;
            }
            String name = new String(key, 0, nameLength, NAMES);
            tracks.computeIfAbsent(name, ignored -> new TreeMap<>()).put(frame(key, NAME_SIZE), key);
        }
        return tracks;
    }

    private static boolean hasMorphs(Path path) {
        try (DataInputStream input = open(path)) {
            long size = Files.size(path);
            int boneCount = skipHeader(input, size);
            input.skipNBytes((long) boneCount * BONE_SIZE);
            return readCount(input, size - HEADER_SIZE - 8 - (long) boneCount * BONE_SIZE, MORPH_SIZE) > 0;
        } catch (IOException failure) {
            return false;
        }
    }

    private static DataInputStream open(Path path) throws IOException {
        return new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 65536));
    }

    private static int skipHeader(DataInputStream input, long size) throws IOException {
        validateHeader(input.readNBytes(HEADER_SIZE), size);
        return readCount(input, size - HEADER_SIZE - 8, BONE_SIZE);
    }

    private static void validateHeader(byte[] header, long size) throws IOException {
        if (header.length != HEADER_SIZE || size < HEADER_SIZE + 8 || size > MAX_VMD_SIZE) {
            throw new IOException("Incomplete VMD or larger than 128 MiB");
        }
        String signature = new String(header, 0, 25, StandardCharsets.US_ASCII);
        if (!signature.equals("Vocaloid Motion Data 0002") && !signature.equals("Vocaloid Motion Data file")) {
            throw new IOException("Invalid VMD header");
        }
    }

    private static int readCount(DataInputStream input, long remaining, int recordSize) throws IOException {
        long count = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
        if (remaining < 0 || count > remaining / recordSize) {
            throw new IOException("Truncated VMD track");
        }
        return (int) count;
    }

    private static long frame(byte[] key, int offset) {
        return Integer.toUnsignedLong(bytes(key).getInt(offset));
    }

    private static ByteBuffer bytes(byte[] key) {
        return ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void writeInt(DataOutputStream output, int value) throws IOException {
        output.writeInt(Integer.reverseBytes(value));
    }

    private static void deleteTemp(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            path.toFile().deleteOnExit();
        }
    }

    private static void report(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        System.err.println("[GFL2VmdFace] " + failure);
    }
}
