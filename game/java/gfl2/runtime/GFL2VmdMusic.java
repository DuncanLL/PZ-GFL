package gfl2.runtime;

import fmod.fmod.FMODManager;
import fmod.javafmod;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.vm.KahluaTable;

/** Local, client-side playback of a user-selected MP3 or WAV through PZ's FMOD bindings. */
@Exposer.LuaClass
public final class GFL2VmdMusic {
    private static final int MAX_ENTRIES = 500;

    private static long soundHandle;
    private static long channelHandle;
    private static long durationMillis;
    private static long positionMillis;
    private static boolean paused;
    private static String playingFile = "";

    private GFL2VmdMusic() {
    }

    public static KahluaTable browseDirectory(String requestedPath) {
        KahluaTable result = newTable();
        KahluaTable directories = newTable();
        KahluaTable files = newTable();
        result.rawset("dirs", directories);
        result.rawset("files", files);

        String pathText = cleanPathText(requestedPath);
        if (pathText.isEmpty()) {
            result.rawset("root", true);
            result.rawset("path", "");
            result.rawset("parent", "");
            addQuickDirectories(directories);
            return result;
        }
        if (isNetworkPath(pathText)) {
            return directoryError(result, directories, files);
        }

        try {
            Path current = Path.of(pathText).toAbsolutePath().normalize();
            String selectedPath = "";
            if (Files.isRegularFile(current)) {
                if (!isSupportedAudioFile(current)) {
                    System.err.println("[GFL2VmdMusic] Unsupported selected file: " + current);
                    return directoryError(result, directories, files);
                }
                selectedPath = current.toString();
                current = current.getParent();
            }
            if (current == null || !Files.isDirectory(current)) {
                System.err.println("[GFL2VmdMusic] Music folder is unavailable: " + current);
                return directoryError(result, directories, files);
            }

            result.rawset("root", false);
            result.rawset("path", current.toString());
            result.rawset("parent", current.getParent() == null ? "" : current.getParent().toString());
            result.rawset("selected", selectedPath);

            ArrayList<Path> childDirectories = new ArrayList<>();
            ArrayList<Path> audioFiles = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(current)) {
                for (Path entry : entries) {
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        childDirectories.add(entry);
                    } else if (Files.isRegularFile(entry) && isSupportedAudioFile(entry)) {
                        audioFiles.add(entry);
                    }
                }
            }
            Comparator<Path> byName = Comparator.comparing(
                entry -> entry.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);
            childDirectories.sort(byName);
            audioFiles.sort(byName);

            addEntries(directories, childDirectories, "dir");
            addEntries(files, audioFiles, "file");
            return result;
        } catch (IOException | RuntimeException exception) {
            System.err.println("[GFL2VmdMusic] Could not browse " + pathText + ": " + exception);
            return directoryError(result, directories, files);
        }
    }

    public static synchronized String play(String selectedPath) {
        Path file = audioFile(selectedPath);
        if (file == null) {
            return UiNotices.text("MotionMusicFile");
        }

        long nextSound = 0L;
        long nextChannel = 0L;
        try {
            long createFlags = (long)(FMODManager.FMOD_CREATESTREAM | FMODManager.FMOD_2D);
            nextSound = javafmod.FMOD_System_CreateSound(file.toString(), createFlags);
            if (nextSound == 0L) {
                return UiNotices.text("MotionMusicPlayback");
            }

            nextChannel = javafmod.FMOD_System_PlaySound(nextSound, false);
            if (nextChannel == 0L) {
                javafmod.FMOD_Sound_Release(nextSound);
                return UiNotices.text("MotionMusicPlayback");
            }
            long nextDuration = javafmod.FMOD_Sound_GetLength(nextSound, FMODManager.FMOD_TIMEUNIT_MS);

            stopCurrent();
            soundHandle = nextSound;
            channelHandle = nextChannel;
            durationMillis = nextDuration;
            positionMillis = 0L;
            paused = false;
            playingFile = file.toString();
            return "";
        } catch (LinkageError | RuntimeException exception) {
            if (nextChannel != 0L) {
                try {
                    javafmod.FMOD_Channel_Stop(nextChannel);
                } catch (LinkageError | RuntimeException ignored) {
                }
            }
            if (nextSound != 0L) {
                try {
                    javafmod.FMOD_Sound_Release(nextSound);
                } catch (LinkageError | RuntimeException ignored) {
                }
            }
            System.err.println("[GFL2VmdMusic] FMOD could not play " + file + ": " + exception);
            return UiNotices.text("MotionMusicPlayback");
        }
    }

    public static synchronized String pause() {
        if (channelHandle == 0L) {
            return "";
        }
        try {
            int result = javafmod.FMOD_Channel_SetPaused(channelHandle, true);
            if (result != 0) {
                return UiNotices.text("MotionMusicPlayback");
            }
            paused = true;
            return "";
        } catch (LinkageError | RuntimeException exception) {
            System.err.println("[GFL2VmdMusic] FMOD could not pause playback: " + exception);
            return UiNotices.text("MotionMusicPlayback");
        }
    }

    public static synchronized String resume() {
        if (channelHandle == 0L) {
            return "";
        }
        try {
            int result = javafmod.FMOD_Channel_SetPaused(channelHandle, false);
            if (result != 0) {
                return UiNotices.text("MotionMusicPlayback");
            }
            paused = false;
            return "";
        } catch (LinkageError | RuntimeException exception) {
            System.err.println("[GFL2VmdMusic] FMOD could not resume playback: " + exception);
            return UiNotices.text("MotionMusicPlayback");
        }
    }

    public static synchronized void stop() {
        stopCurrent();
    }

    public static synchronized String seek(double requestedMillis) {
        if (channelHandle == 0L || durationMillis <= 0L || !Double.isFinite(requestedMillis)) {
            return "";
        }
        long targetMillis = Math.max(0L, Math.min(durationMillis, Math.round(requestedMillis)));
        try {
            int result = javafmod.FMOD_Channel_SetPosition(channelHandle, targetMillis);
            if (result != 0) {
                return UiNotices.text("MotionMusicPlayback");
            }
            positionMillis = targetMillis;
            return "";
        } catch (LinkageError | RuntimeException exception) {
            System.err.println("[GFL2VmdMusic] FMOD could not seek playback: " + exception);
            return UiNotices.text("MotionMusicPlayback");
        }
    }

    public static synchronized KahluaTable state() {
        if (channelHandle != 0L && !paused) {
            try {
                if (!javafmod.FMOD_Channel_IsPlaying(channelHandle)) {
                    releaseEndedTrack();
                } else {
                    positionMillis = javafmod.FMOD_Channel_GetPosition(
                        channelHandle, FMODManager.FMOD_TIMEUNIT_MS);
                }
            } catch (LinkageError | RuntimeException exception) {
                System.err.println("[GFL2VmdMusic] FMOD state query failed: " + exception);
                stopCurrent();
            }
        }

        KahluaTable result = newTable();
        result.rawset("file", playingFile);
        result.rawset("playing", channelHandle != 0L && !paused);
        result.rawset("paused", channelHandle != 0L && paused);
        result.rawset("loaded", channelHandle != 0L);
        result.rawset("duration", (double)durationMillis);
        result.rawset("position", (double)positionMillis);
        return result;
    }

    private static void stopCurrent() {
        long oldChannel = channelHandle;
        long oldSound = soundHandle;
        channelHandle = 0L;
        soundHandle = 0L;
        durationMillis = 0L;
        positionMillis = 0L;
        paused = false;
        playingFile = "";

        try {
            if (oldChannel != 0L) {
                javafmod.FMOD_Channel_Stop(oldChannel);
            }
        } catch (LinkageError | RuntimeException exception) {
            System.err.println("[GFL2VmdMusic] FMOD could not stop playback: " + exception);
        } finally {
            try {
                if (oldSound != 0L) {
                    javafmod.FMOD_Sound_Release(oldSound);
                }
            } catch (LinkageError | RuntimeException exception) {
                System.err.println("[GFL2VmdMusic] FMOD could not release a sound: " + exception);
            }
        }
    }

    private static void releaseEndedTrack() {
        long oldSound = soundHandle;
        channelHandle = 0L;
        soundHandle = 0L;
        positionMillis = durationMillis;
        paused = false;
        try {
            if (oldSound != 0L) {
                javafmod.FMOD_Sound_Release(oldSound);
            }
        } catch (LinkageError | RuntimeException exception) {
            System.err.println("[GFL2VmdMusic] FMOD could not release a finished sound: " + exception);
        }
    }

    private static String cleanPathText(String value) {
        String text = value == null ? "" : value.strip();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).strip();
        }
        return text;
    }

    private static boolean isNetworkPath(String text) {
        return text.startsWith("\\\\") || text.startsWith("//");
    }

    private static Path audioFile(String value) {
        String text = cleanPathText(value);
        if (text.isEmpty() || isNetworkPath(text)) {
            return null;
        }
        try {
            Path path = Path.of(text).toAbsolutePath().normalize();
            boolean supported = path.isAbsolute() && Files.isRegularFile(path) && isSupportedAudioFile(path);
            if (!supported) {
                System.err.println("[GFL2VmdMusic] Audio file is unavailable: " + path);
            }
            return supported ? path : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean isSupportedAudioFile(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp3") || name.endsWith(".wav");
    }

    private static void addQuickDirectories(KahluaTable directories) {
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            Path userDirectory = Path.of(home).toAbsolutePath().normalize();
            String[][] shortcuts = {
                {"Music", "MotionFolderMusic"},
                {"Desktop", "MotionFolderDesktop"},
                {"Downloads", "MotionFolderDownloads"},
                {"Documents", "MotionFolderDocuments"}
            };
            int index = 1;
            for (String[] shortcut : shortcuts) {
                Path path = userDirectory.resolve(shortcut[0]);
                if (Files.isDirectory(path)) {
                    directories.rawset((double)index++, directoryEntry(
                        shortcut[0], path, "quick", shortcut[1]));
                }
            }
        }

        File[] roots = File.listRoots();
        if (roots == null) {
            return;
        }
        int index = (int)directories.len() + 1;
        for (File root : roots) {
            Path path = root.toPath().toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                directories.rawset((double)index++, directoryEntry(
                    root.getPath(), path, "drive", ""));
            }
        }
    }

    private static void addEntries(KahluaTable destination, List<Path> paths, String kind) {
        int count = Math.min(paths.size(), MAX_ENTRIES);
        for (int index = 0; index < count; index++) {
            Path path = paths.get(index);
            destination.rawset((double)index + 1.0,
                directoryEntry(path.getFileName().toString(), path, kind, ""));
        }
    }

    private static KahluaTable directoryEntry(String name, Path path, String kind, String key) {
        KahluaTable entry = newTable();
        entry.rawset("name", name);
        entry.rawset("path", path.toString());
        entry.rawset("kind", kind);
        if (!key.isEmpty()) {
            entry.rawset("key", key);
        }
        return entry;
    }

    private static KahluaTable directoryError(
        KahluaTable result, KahluaTable directories, KahluaTable files) {
        result.rawset("root", true);
        result.rawset("path", "");
        result.rawset("parent", "");
        result.rawset("selected", "");
        result.rawset("error", UiNotices.text("MotionMusicDirectory"));
        directories.wipe();
        files.wipe();
        return result;
    }

    private static KahluaTable newTable() {
        return J2SEPlatform.getInstance().newTable();
    }
}
