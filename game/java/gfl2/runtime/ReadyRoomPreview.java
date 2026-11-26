package gfl2.runtime;

import gfl2.motion.PmxDrawMesh;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import org.joml.Matrix4f;
import org.json.JSONObject;
import org.json.JSONArray;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.j2se.J2SEPlatform;
import zombie.ZomboidFileSystem;
import org.lwjgl.opengl.*;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.RenderThread;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import static gfl2.runtime.UiNotices.text;

/** Displays world snapshots and isolated, single-pose outfit previews. */
public class ReadyRoomPreview {
    public record StudioItem(
        int vboId, int eboId, int indexCount, int stride,
        int posOffset, int normalOffset, int uvOffset,
        int textureId, float[] pmxMatrix
    ) {}
    @FunctionalInterface
    public interface StudioRenderer {
        boolean render(
            String pmxSha256,
            float[] positions,
            float[] normals,
            float[] uv,
            float[] materials,
            float[] shadingHead,
            float unitScale,
            float cameraX, float cameraY, float cameraZ,
            float dist, float yaw, float pitch, float fovRad, float nearPlane, float farPlane,
            int x, int y, int width, int height, int screenHeight,
            List<StudioItem> items
        );
    }
    private static volatile StudioRenderer studioRenderer;
    private static volatile Runnable studioReleaseHook;
    private static volatile boolean studioEnabled = true;
    private static volatile float studioResolutionScale = 2.0f;

    public static void registerStudioRenderer(StudioRenderer renderer, Runnable releaseHook) {
        studioRenderer = renderer;
        studioReleaseHook = releaseHook;
    }
    public static boolean studioPreviewEnabled() { return studioEnabled; }
    public static void setStudioPreviewEnabled(boolean enabled) {
        boolean changed = studioEnabled != enabled;
        studioEnabled = enabled;
        if (changed) PortraitHudCache.invalidate();
        if (changed && !enabled) {
            Runnable hook = studioReleaseHook;
            if (hook != null) hook.run();
        }
    }
    public static float studioResolutionScale() { return studioResolutionScale; }
    public static int studioResolutionPercent() { return Math.round(studioResolutionScale * 100f); }
    public static void setStudioResolutionPercent(int percent) {
        int snapped = percent <= 125 ? 100 : (percent <= 175 ? 150 : 200);
        float next = snapped / 100f;
        if (Math.abs(studioResolutionScale - next) > 1e-3f) {
            studioResolutionScale = next;
            PortraitHudCache.invalidate();
        }
    }
    public static boolean studioPreviewAvailable() { return studioEnabled && studioRenderer != null; }

    private static final Object motionPathsLock=new Object();
    private static final class BrowsePreview {
        final String id;
        final ModelSession session;
        ModelSession appearanceSource;
        long appearanceRevision=Long.MIN_VALUE;
        long poseFrame;

        BrowsePreview(String id,ModelSession session) {
            this.id=id;
            this.session=session;
        }

        void update(ModelSession equipped, AnimationPlayer worldPlayer) {
            long revision=equipped==null?0:equipped.model.appearance().revision();
            if(poseFrame!=0&&equipped==appearanceSource&&revision==appearanceRevision)return;
            if(equipped!=null) {
                var appearance=new JSONObject(equipped.model.appearance().stateJson());
                session.applySettings(new JSONObject().put("appearance",appearance)
                    .put("appearance_controls",equipped.profile.optJSONObject("appearance_controls")),false);
                session.model.appearance().loadExpression(equipped.model.appearance().expressionSnapshot());
            }
            // Sample the game's standing idle on a separate player. The live tracks can be
            // holding a weapon, crouching, or still blending during the first render.
            var idle=AnimationPlayer.alloc(worldPlayer.getModel());
            try {
                var track=idle.play("Bob_Idle",true);
                if(track==null)return;
                track.setBlendWeight(1);
                track.setCurrentTimeValue(track.getDuration()*.25f);
                idle.Update(0);
                session.update(poseFrame++,System.nanoTime(),idle,false,0,0,0,false,true,false,null,false);
            } finally {
                idle.release();
            }
            appearanceSource=equipped;
            appearanceRevision=revision;
        }
    }
    private static final class BrowseState {
        BrowsePreview requested;
        ModelSession shown;
        boolean ownsShown;

        void discardRequest() {
            if (requested!=null && requested.session!=null && requested.session!=shown) requested.session.retire();
            requested=null;
        }

        void present(ModelSession next,boolean owned) {
            if (shown!=next && ownsShown) shown.retire();
            shown=next;
            ownsShown=owned;
        }
    }
    private static final IdentityHashMap<IsoGameCharacter,BrowseState> browsePreviews=new IdentityHashMap<>();
    private static final String SAMPLE_PREFIX="sample:";
    private static final class MotionInput extends IllegalArgumentException {
        MotionInput(String key) { super(text(key)); }
    }
    private static String motionError(Exception error,String fallback) {
        if(error instanceof MotionInput)return error.getMessage();
        Main.error(error);
        return text(fallback);
    }
    private static Path library() {
        return Path.of(ZomboidFileSystem.instance.getCacheDir(),"GFL2Exilium","Motions").toAbsolutePath().normalize();
    }
    public static String motionDirectory() { return library().toString(); }
    private static boolean motionFile(Path path) {
        return path.getFileName()!=null&&path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vmd")
            &&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS);
    }
    private static Path sampleLibrary() {
        String directory=ZomboidFileSystem.instance.getModDir(Main.MOD_ID);
        if(directory==null)throw new MotionInput("SampleLibrary");
        Path mod=Path.of(directory).toAbsolutePath().normalize();
        Path relative=Path.of("media/gfl2_pmx/motions");
        return Files.isDirectory(mod.resolve(relative))?mod.resolve(relative):mod.resolve("42").resolve(relative);
    }
    private static List<String> motionNames(Path directory, java.util.function.Predicate<Path> accept) throws IOException {
        if(!Files.isDirectory(directory))return List.of();
        try(var paths=Files.list(directory)){
            return paths.filter(ReadyRoomPreview::motionFile).filter(accept).map(p->p.getFileName().toString())
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())).toList();
        }
    }
    private static List<String> motionNames(Path directory) throws IOException {
        return motionNames(directory, p -> true);
    }
    private static Path namedMotion(Path directory,String name) {
        if(name.isBlank()||name.indexOf('/')>=0||name.indexOf('\\')>=0||name.indexOf(':')>=0)
            throw new MotionInput("MotionFileName");
        Path file=directory.resolve(name).normalize();
        if(!directory.equals(file.getParent()))throw new MotionInput("MotionFileName");
        return file;
    }
    private static Path localMotionPath(String value) {
        String text=value==null?"":value.strip();
        if(text.length()>=2&&text.startsWith("\"")&&text.endsWith("\""))text=text.substring(1,text.length()-1);
        if(text.startsWith("\\\\")||text.startsWith("//"))throw new MotionInput("MotionLocalDisk");
        Path path=Path.of(text);
        if(!path.isAbsolute())throw new MotionInput("MotionFullPath");
        return path.normalize();
    }
    private static LinkedHashSet<Path> savedMotionPaths() throws IOException {
        Path index=library().resolve("local-paths.json");
        var paths=new LinkedHashSet<Path>();
        if(Files.exists(index)){
            var data=new JSONArray(Files.readString(index));
            for(int i=0;i<data.length();i++)paths.add(localMotionPath(data.getString(i)));
        }
        return paths;
    }
    private static void saveMotionPaths(LinkedHashSet<Path> paths) throws IOException {
        Path dir=Files.createDirectories(library());
        Path staged=Files.createTempFile(dir,"local-paths-",".tmp");
        try {
            var data=new JSONArray();
            paths.forEach(path->data.put(path.toString()));
            Files.writeString(staged,data.toString(2));
            Files.move(staged,dir.resolve("local-paths.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(staged);}
    }
    public static KahluaTable addMotionPath(String value) {
        var result=new JSONObject();
        try {
            Path path=localMotionPath(value);
            if(!Files.isDirectory(path))throw new MotionInput("MotionDirectory");
            synchronized(motionPathsLock){
                var paths=savedMotionPaths();
                if(paths.add(path))saveMotionPaths(paths);
            }
            result.put("path",path.toString());
        }catch(Exception e){result.put("error",motionError(e,"MotionSavePath"));}
        return (KahluaTable)lua(result);
    }
    public static String removeMotionPath(String value) {
        try {
            Path path=localMotionPath(value);
            synchronized(motionPathsLock){
                var paths=savedMotionPaths();
                if(!paths.remove(path))return text("MotionUnknownPath");
                saveMotionPaths(paths);
            }
            return "";
        }catch(Exception e){return motionError(e,"MotionRemovePath");}
    }
    private static void collectVmdsInDir(Path root, java.util.function.Predicate<Path> accept, LinkedHashSet<String> out, boolean emitRelativeDirect) {
        if (root == null || !Files.isDirectory(root)) return;
        try {
            for (String name : motionNames(root, accept)) {
                out.add(emitRelativeDirect ? name : root.resolve(name).toString());
            }
            var subs = new java.util.ArrayList<Path>();
            try (var stream = Files.newDirectoryStream(root)) {
                for (Path p : stream) {
                    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && !isSkippableDir(p)) {
                        subs.add(p);
                        if (subs.size() >= 200) break;
                    }
                }
            }
            subs.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
            for (Path sub : subs) {
                try {
                    for (String name : motionNames(sub, accept)) {
                        out.add(sub.resolve(name).toString());
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            Main.error(e);
        }
    }
    private static LinkedHashSet<String> listVmdEntries(java.util.function.Predicate<Path> accept) {
        var entries = new LinkedHashSet<String>();
        try {
            Path defLib = Files.createDirectories(library());
            List<String> localDirect = motionNames(defLib, accept);
            for (String sample : motionNames(sampleLibrary(), accept))
                if (localDirect.stream().noneMatch(sample::equalsIgnoreCase)) entries.add(SAMPLE_PREFIX + sample);
            collectVmdsInDir(defLib, accept, entries, true);
        } catch (Exception e) { Main.error(e); }
        try {
            for (Path source : savedMotionPaths()) {
                if (motionFile(source) && accept.test(source)) entries.add(source.toString());
                else collectVmdsInDir(source, accept, entries, false);
            }
        } catch (Exception e) { Main.error(e); }
        return entries;
    }
    static KahluaTable listVmds(java.util.function.Predicate<Path> accept) {
        return (KahluaTable)lua(new JSONArray(listVmdEntries(accept)));
    }
    public static KahluaTable motionLibrary() {
        return listVmds(gfl2.motion.VmdCamera::hasBoneTrack);
    }
    public static KahluaTable cameraLibrary() {
        return listVmds(gfl2.motion.VmdCamera::hasCameraTrack);
    }
    private static boolean isAudioCompanion(Path p) {
        if (p == null || p.getFileName() == null || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return false;
        String lower = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".wav") || lower.endsWith(".mp3");
    }
    private static String fileStem(Path p) {
        String name = p == null || p.getFileName() == null ? "" : p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
    }
    private static String normalizedTokens(String stem) {
        return stem.replaceAll("[\\s_\\-\\.\\[\\]\\(\\)【】（）]+", " ").trim();
    }
    private static int similarityScore(String targetStem, String folderName, Path candidate, String[] hints) {
        String candStem = fileStem(candidate);
        int score = 0;
        if (candStem.equals(targetStem)) score += 100;
        if (!targetStem.isEmpty() && (candStem.startsWith(targetStem) || targetStem.startsWith(candStem))) score += 45;
        if (!folderName.isEmpty() && (candStem.contains(folderName) || folderName.contains(candStem))) score += 25;
        int prefix = 0;
        int limit = Math.min(targetStem.length(), candStem.length());
        while (prefix < limit && targetStem.charAt(prefix) == candStem.charAt(prefix)) prefix++;
        if (prefix >= 3) score += Math.min(30, prefix * 3);
        String[] tWords = normalizedTokens(targetStem).split(" ");
        String cNorm = normalizedTokens(candStem);
        for (String w : tWords) {
            if (w.length() >= 2 && cNorm.contains(w)) score += 15;
        }
        for (String h : hints) {
            if (candStem.contains(h)) score += 8;
        }
        return score;
    }
    private static Path pickCompanion(Path motion, List<Path> candidates, int danceCountInDir, String[] hints) {
        if (candidates.isEmpty()) return null;
        String targetStem = fileStem(motion);
        String folderName = motion.getParent() != null && motion.getParent().getFileName() != null
            ? motion.getParent().getFileName().toString().toLowerCase(Locale.ROOT) : "";
        Path best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Path c : candidates) {
            int sc = similarityScore(targetStem, folderName, c, hints);
            if (sc > bestScore) {
                bestScore = sc;
                best = c;
            }
        }
        // In a dedicated single-dance folder, always auto-link the companion even if filenames differ (e.g. "动作.vmd" + "镜头.vmd" + "bgm.wav").
        // In a flat folder containing multiple unrelated dances, require genuine filename similarity so dances don't cross-wire.
        if (danceCountInDir <= 1) return best;
        return bestScore >= 15 ? best : null;
    }
    private static JSONObject inspectBundleJson(String selectedMotion) {
        var out = new JSONObject();
        out.put("motion", selectedMotion == null ? "" : selectedMotion);
        out.put("folder", "");
        out.put("dir", "");
        out.put("embeddedFace", false);
        out.put("embeddedCamera", false);
        out.put("camera", "");
        out.put("face", "");
        out.put("music", "");
        try {
            Path motion = resolveMotionInput(selectedMotion);
            long[] selfCounts = gfl2.motion.VmdCamera.inspectTrackCounts(motion);
            boolean embeddedFace = selfCounts != null && selfCounts[1] > 0;
            boolean embeddedCam = selfCounts != null && selfCounts[2] > 0;
            out.put("embeddedFace", embeddedFace);
            out.put("embeddedCamera", embeddedCam);
            if (selectedMotion != null && selectedMotion.startsWith(SAMPLE_PREFIX)) {
                return out;
            }
            Path dir = motion.getParent();
            if (dir == null || !Files.isDirectory(dir)) return out;
            out.put("dir", dir.toString());
            Path defLib = library();
            if (!dir.equals(defLib) && dir.getFileName() != null) {
                out.put("folder", dir.getFileName().toString());
            }
            var cameras = new java.util.ArrayList<Path>();
            var faces = new java.util.ArrayList<Path>();
            var audios = new java.util.ArrayList<Path>();
            int danceCount = 0;
            try (var stream = Files.newDirectoryStream(dir)) {
                int seen = 0;
                for (Path p : stream) {
                    if (++seen > 400) break;
                    if (isAudioCompanion(p)) {
                        audios.add(p);
                    } else if (motionFile(p)) {
                        if (p.equals(motion)) {
                            danceCount++;
                            continue;
                        }
                        long[] c = gfl2.motion.VmdCamera.inspectTrackCounts(p);
                        if (c == null) continue;
                        if (c[2] > 0) cameras.add(p);
                        if (c[1] > 0 && c[0] <= 10) faces.add(p);
                        if (c[0] > 10) danceCount++;
                    }
                }
            }
            Comparator<Path> byName = Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);
            cameras.sort(byName);
            faces.sort(byName);
            audios.sort((a, b) -> {
                boolean aw = a.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav");
                boolean bw = b.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav");
                if (aw != bw) return aw ? -1 : 1;
                return byName.compare(a, b);
            });
            Path cam = pickCompanion(motion, cameras, danceCount, new String[]{"cam", "camera", "镜头", "カメラ"});
            Path face = pickCompanion(motion, faces, danceCount, new String[]{"face", "lip", "facial", "morph", "表情", "口型", "口パク", "リップ"});
            Path audio = pickCompanion(motion, audios, danceCount, new String[]{"bgm", "music", "audio", "song", "音乐", "音源"});
            if (cam != null) out.put("camera", cam.toString());
            else if (embeddedCam) out.put("camera", selectedMotion);
            if (face != null) out.put("face", face.toString());
            if (audio != null) out.put("music", audio.toString());
        } catch (Exception ignored) {}
        return out;
    }
    public static KahluaTable motionBundle(String selectedMotion) {
        return (KahluaTable)lua(inspectBundleJson(selectedMotion));
    }
    public static KahluaTable motionLibraryMeta() {
        var out = new JSONObject();
        for (String entry : listVmdEntries(gfl2.motion.VmdCamera::hasBoneTrack)) {
            out.put(entry, inspectBundleJson(entry));
        }
        return (KahluaTable)lua(out);
    }
    public static KahluaTable motionPaths() {
        var out=new JSONArray();
        try {
            synchronized(motionPathsLock){
                for(Path p:savedMotionPaths())out.put(p.toString());
            }
        }catch(Exception e){Main.error(e);}
        return (KahluaTable)lua(out);
    }
    private static int countDirectVmd(Path dir) {
        if(dir==null||!Files.isDirectory(dir))return 0;
        int count=0;
        try(var stream=Files.newDirectoryStream(dir)){
            int seen=0;
            int subDirs=0;
            for(Path p:stream){
                if(++seen>1000)break;
                if(motionFile(p)){
                    count++;
                }else if(subDirs<80&&Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)&&!isSkippableDir(p)){
                    subDirs++;
                    try(var inner=Files.newDirectoryStream(p)){
                        int innerSeen=0;
                        for(Path q:inner){
                            if(++innerSeen>200)break;
                            if(motionFile(q))count++;
                        }
                    }catch(Exception ignored){}
                }
            }
        }catch(Exception ignored){}
        return count;
    }
    private static boolean isSkippableDir(Path p) {
        Path namePath=p.getFileName();
        if(namePath==null)return true;
        String name=namePath.toString();
        if(name.isEmpty()||name.startsWith(".")||name.startsWith("$"))return true;
        if(name.equalsIgnoreCase("System Volume Information")||name.equalsIgnoreCase("Config.Msi")
            ||name.equalsIgnoreCase("Recovery")||name.equalsIgnoreCase("PerfLogs"))return true;
        try{if(Files.isHidden(p))return true;}catch(Exception ignored){}
        return false;
    }
    private static JSONObject dirEntry(String name,String key,String kind,Path path,LinkedHashSet<Path> saved,Path defLib) {
        var o=new JSONObject();
        o.put("name",name);
        if(key!=null)o.put("key",key);
        o.put("kind",kind);
        o.put("path",path.toString());
        o.put("vmdCount",countDirectVmd(path));
        o.put("added",saved.contains(path)||path.equals(defLib));
        return o;
    }
    public static KahluaTable browseMotionDirectory(String value) {
        var result=new JSONObject();
        try {
            Path defLib=Files.createDirectories(library());
            LinkedHashSet<Path> saved;
            synchronized(motionPathsLock){saved=savedMotionPaths();}
            String raw=value==null?"":value.strip();
            var dirs=new JSONArray();
            if(raw.isEmpty()){
                result.put("root",true);
                result.put("path","");
                result.put("parent","");
                result.put("vmdCount",0);
                result.put("added",false);
                dirs.put(dirEntry(defLib.getFileName().toString(),"MotionFolderDefault","quick",defLib,saved,defLib));
                String homeStr=System.getProperty("user.home","");
                if(!homeStr.isBlank()){
                    Path home=Path.of(homeStr).toAbsolutePath().normalize();
                    String[][] shortcuts={{"Desktop","MotionFolderDesktop"},{"Downloads","MotionFolderDownloads"},{"Documents","MotionFolderDocuments"}};
                    for(String[] sc:shortcuts){
                        Path sub=home.resolve(sc[0]);
                        if(Files.isDirectory(sub))dirs.put(dirEntry(sc[0],sc[1],"quick",sub,saved,defLib));
                    }
                }
                File[] roots=File.listRoots();
                if(roots!=null){
                    for(File r:roots){
                        Path rp=r.toPath().toAbsolutePath().normalize();
                        if(Files.isDirectory(rp))dirs.put(dirEntry(rp.toString(),null,"drive",rp,saved,defLib));
                    }
                }
                result.put("dirs",dirs);
                return (KahluaTable)lua(result);
            }
            Path dir=localMotionPath(raw);
            if(!Files.isDirectory(dir))throw new MotionInput("MotionDirectory");
            result.put("root",false);
            result.put("path",dir.toString());
            result.put("parent",dir.getParent()!=null?dir.getParent().toString():"");
            result.put("vmdCount",countDirectVmd(dir));
            result.put("added",saved.contains(dir)||dir.equals(defLib));
            var subs=new java.util.ArrayList<Path>();
            try(var stream=Files.newDirectoryStream(dir)){
                for(Path p:stream){
                    if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)&&!isSkippableDir(p))subs.add(p);
                }
            }catch(IOException ignored){}
            subs.sort(Comparator.comparing(p->p.getFileName().toString(),String.CASE_INSENSITIVE_ORDER));
            int limit=Math.min(subs.size(),400);
            for(int i=0;i<limit;i++){
                Path sub=subs.get(i);
                dirs.put(dirEntry(sub.getFileName().toString(),null,"dir",sub,saved,defLib));
            }
            result.put("dirs",dirs);
        }catch(Exception e){result.put("error",motionError(e,"MotionDirectory"));}
        return (KahluaTable)lua(result);
    }
    private static LinkedHashSet<String> savedMotionFavorites() throws IOException {
        Path index=library().resolve("favorites.json");
        var favs=new LinkedHashSet<String>();
        if(Files.exists(index)){
            var data=new JSONArray(Files.readString(index));
            for(int i=0;i<data.length();i++){
                String s=data.optString(i,"");
                if(!s.isBlank())favs.add(s);
            }
        }
        return favs;
    }
    private static void saveMotionFavorites(LinkedHashSet<String> favs) throws IOException {
        Path dir=Files.createDirectories(library());
        Path staged=Files.createTempFile(dir,"favorites-",".tmp");
        try {
            var data=new JSONArray();
            favs.forEach(data::put);
            Files.writeString(staged,data.toString(2));
            Files.move(staged,dir.resolve("favorites.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(staged);}
    }
    public static KahluaTable motionFavorites() {
        var out=new JSONArray();
        try {
            synchronized(motionPathsLock){
                for(String s:savedMotionFavorites())out.put(s);
            }
        }catch(Exception e){Main.error(e);}
        return (KahluaTable)lua(out);
    }
    public static boolean setMotionFavorite(String key,boolean favorite) {
        if(key==null||key.isBlank())return false;
        try {
            synchronized(motionPathsLock){
                var favs=savedMotionFavorites();
                boolean changed=favorite?favs.add(key):favs.remove(key);
                if(changed)saveMotionFavorites(favs);
            }
            return true;
        }catch(Exception e){Main.error(e);return false;}
    }
    static boolean motionAllowed(IsoPlayer actor) {
        return !actor.isDead()&&actor.getVehicle()==null&&!actor.isPlayerMoving()&&!actor.isAiming()&&!actor.isAttackStarted();
    }
    public static String gripFamily(IsoGameCharacter actor) {
        return Main.isReady(actor)?GripCalibration.itemKey(actor):"";
    }
    public static String gripItemName(IsoGameCharacter actor) {
        var item=GripCalibration.heldItem(actor);
        return item==null?"":item.getDisplayName();
    }
    public static boolean canAimGrip(IsoGameCharacter actor) {
        return GripCalibration.heldItem(actor)!=null;
    }
    public static KahluaTable gripValues(IsoGameCharacter actor,String key,boolean aiming) {
        ModelSession session=Main.session(actor);
        if(session==null||key==null||key.isEmpty()||!key.equals(gripFamily(actor)))
            return J2SEPlatform.getInstance().newTable();
        return GripCalibration.sliders(actor,session.entry.id(),key,aiming);
    }
    public static boolean gripSet(IsoGameCharacter actor,String id,String key,int axis,double value,boolean aiming) {
        ModelSession session=Main.session(actor);
        return session!=null&&session.entry.id().equals(id)&&Main.isReady(actor)&&GripCalibration.set(actor,id,key,axis,value,aiming);
    }
    public static synchronized boolean drawGripView(IsoGameCharacter actor,int x,int y,int width,int height,boolean aiming,float yaw,float pitch,float zoom) {
        return GripRoomPreview.draw(actor,x,y,width,height,aiming,yaw,pitch,zoom);
    }

    private static ModelSession expressionSession(IsoGameCharacter actor, String id) {
        if (!(actor instanceof IsoPlayer player) || !player.isLocalPlayer() || !Main.isReady(actor)) return null;
        ModelSession session = Main.session(actor);
        return session != null && session.entry.id().equals(id) ? session : null;
    }
    public static KahluaTable expressionRows(IsoGameCharacter actor, String id) {
        ModelSession s = expressionSession(actor, id);
        if (s != null) synchronized (s) {
            if (!s.retired && !s.failed) return (KahluaTable)lua(s.model.appearance().expressionRows());
        }
        return J2SEPlatform.getInstance().newTable();
    }
    public static String expressionSnapshot(IsoGameCharacter actor, String id) {
        ModelSession s = expressionSession(actor, id);
        if (s != null) synchronized (s) {
            if (!s.retired && !s.failed) return s.model.appearance().expressionSnapshot();
        }
        return "";
    }
    public static String expressionSet(IsoGameCharacter actor, String id, int index, double value) {
        ModelSession s = expressionSession(actor, id);
        if (s == null) return text("EnableDoll");
        synchronized (s) {
            if (s.retired || s.failed) return text("DollSwitching");
            try { s.model.appearance().setExpression(index, value); return ""; }
            catch (RuntimeException e) { Main.error(e);return text("ExpressionFailure"); }
        }
    }
    public static String expressionLoad(IsoGameCharacter actor, String id, String preset) {
        ModelSession s = expressionSession(actor, id);
        if (s == null) return text("EnableDoll");
        synchronized (s) {
            if (s.retired || s.failed) return text("DollSwitching");
            try { s.model.appearance().loadExpression(preset); return ""; }
            catch (RuntimeException e) { Main.error(e);return text("ExpressionFailure"); }
        }
    }
    public static void notifySpeaking(IsoGameCharacter actor, double durationSeconds) {
        notifySpeaking(actor, durationSeconds, null);
    }
    public static void notifySpeaking(IsoGameCharacter actor, double durationSeconds, String emotion) {
        ModelSession s = Main.session(actor);
        if (s != null && s.liveFacial != null && Double.isFinite(durationSeconds) && durationSeconds > 0) {
            s.liveFacial.notifySpeaking((float) durationSeconds, emotion);
        }
    }
    public static KahluaTable motionState(IsoGameCharacter actor) {
        ModelSession s=Main.session(actor);
        return (KahluaTable)lua(s==null
            ?new JSONObject().put("name","").put("paused",false).put("physics",false).put("frame",0).put("endFrame",0).put("speed",1).put("revision","").put("camera","").put("cameraEnabled",false)
            :s.model.motionTimeline());
    }
    static Path resolveMotionInput(String name) throws IOException {
        if(name==null||name.isBlank())throw new MotionInput("MotionSelect");
        Path file;
        if(name.startsWith(SAMPLE_PREFIX)){
            file=namedMotion(sampleLibrary(),name.substring(SAMPLE_PREFIX.length()));
        }else if(Path.of(name).isAbsolute()){
            file=localMotionPath(name);
            Path defLib=library();
            Path parent=file.getParent();
            Path grandParent=parent!=null?parent.getParent():null;
            boolean inDefault=defLib.equals(parent)||defLib.equals(grandParent);
            if(!inDefault){
                var sources=savedMotionPaths();
                boolean allowed=sources.contains(file)
                    ||(parent!=null&&sources.contains(parent))
                    ||(grandParent!=null&&sources.contains(grandParent));
                if(!allowed)throw new MotionInput("MotionRegister");
            }
        }else{
            file=namedMotion(library(),name);
        }
        if(!motionFile(file))throw new MotionInput("MotionFileMissing");
        return file;
    }
    public static String motionCommand(IsoGameCharacter actor,String command,String name) {
        if(!(actor instanceof IsoPlayer player)||!player.isLocalPlayer())return text("LocalMotionOnly");
        ModelSession s=Main.session(actor);
        if(s==null||!Main.isReady(actor))return text("EnableDoll");
        synchronized(s) {
            if(s.retired||s.failed)return text("DollSwitching");
            try {
                switch(command) {
                    case "play" -> {
                        if(!motionAllowed(player))return text("MotionStandStill");
                        s.model.playMotion(resolveMotionInput(name));
                    }
                    case "camera" -> s.model.loadCamera(resolveMotionInput(name));
                    case "cameraClear" -> {
                        s.model.clearCamera();
                        VmdCameraController.releaseIfHeld();
                    }
                    case "cameraToggle" -> {
                        s.model.toggleCamera();
                        if(!s.model.cameraActive())VmdCameraController.releaseIfHeld();
                    }
                    case "seek" -> {
                        if(!motionAllowed(player))return text("MotionStandStill");
                        String[] target=name==null?new String[0]:name.split(":",2);
                        if(target.length!=2||!s.model.seekMotion(Long.parseLong(target[0]),Float.parseFloat(target[1])))
                            return text("MotionSeekStale");
                    }
                    case "pause" -> s.model.toggleMotionPause();
                    case "speed" -> s.model.setMotionSpeed(Float.parseFloat(name));
                    case "restart" -> s.model.restartMotion();
                    case "stop" -> {
                        if(s.model.motionActive())s.model.stopMotion();
                        else s.model.clearCamera();
                        VmdCameraController.releaseIfHeld();
                    }
                    case "physics" -> {if(s.model.motionActive())s.model.setPhysicsEnabled(!s.model.physicsEnabled());}
                    case "reset" -> {if(s.model.motionActive())s.model.resetPhysics();}
                    default -> {return text("MotionUnknownCommand");}
                }
                return "";
            }catch(Exception e){return motionError(e,"MotionReadFailure");}
        }
    }
    public static synchronized KahluaTable data() throws Exception {
        Path root=Path.of(ZomboidFileSystem.instance.getModDir(Main.MOD_ID));
        Path relative=Path.of("media/ui/GFL2ReadyRoom/catalog.json");
        Path path=Files.isRegularFile(root.resolve(relative))?root.resolve(relative):root.resolve("42").resolve(relative);
        // UI tables belong to this Lua/window lifecycle, not to a previous game session.
        JSONObject catalog=new JSONObject(Files.readString(path));
        JSONObject models=catalog.getJSONObject("models"),labels=catalog.getJSONObject("labels");
        for(var pack:Main.enabledCharacterPacks()){
            Path file=pack.media().resolve("ui/GFL2ReadyRoom/packs/"+pack.id()+".json");
            try {
                JSONObject extra=new JSONObject(Files.readString(file)),packModels=extra.getJSONObject("models");
                for(String id:packModels.keySet()){
                    JSONObject card=models.optJSONObject(id);
                    if(card!=null&&pack.id().equals(card.optString("pack")))
                        models.put(id,packModels.getJSONObject(id).put("pack",pack.id()));
                }
                JSONObject packLabels=extra.getJSONObject("labels");
                for(String key:packLabels.keySet())if(!labels.has(key))labels.put(key,packLabels.getJSONObject(key));
            }catch(Exception e){System.err.println("[GFL2CharacterRuntime] Character pack UI "+pack.id()+": "+e);}
        }
        return (KahluaTable)lua(catalog);
    }
    private static Object lua(Object value){
        if(value instanceof JSONObject object){KahluaTable t=J2SEPlatform.getInstance().newTable();for(String key:object.keySet())t.rawset(key,lua(object.get(key)));return t;}
        if(value instanceof JSONArray array){KahluaTable t=J2SEPlatform.getInstance().newTable();for(int i=0;i<array.length();i++)t.rawset((double)i+1,lua(array.get(i)));return t;}
        if(value instanceof Number n)return n.doubleValue();return value==JSONObject.NULL?null:value;
    }
    public static synchronized boolean draw(IsoGameCharacter actor,int x,int y,int width,int height,float yaw,float zoom) {
        return drawCamera(actor,x,y,width,height,yaw,zoom,0,0);
    }
    public static synchronized boolean drawCamera(IsoGameCharacter actor,int x,int y,int width,int height,float yaw,float zoom,float pan,float focus) {
        ModelSession session=Main.session(actor);
        return drawSession(session,x,y,width,height,yaw,zoom,pan,focus,false,GripRoomPreview.collectLiveHeld(actor,session));
    }
    public static synchronized boolean drawPortraitHud(IsoGameCharacter actor,int x,int y,int width,int height,float yaw,float zoom,float pan,float focus,int refreshFps) {
        ModelSession session=Main.session(actor);
        if(session==null||!session.ready||session.retired||session.failed||width<16||height<16)return false;
        try {
            ModelSession.Snapshot snapshot=session.snapshot();if(snapshot==null)return false;
            Mesh mesh=session.plainMesh(snapshot.frame());
            List<GripRoomPreview.Part> heldParts=GripRoomPreview.collectLiveHeld(actor,session);
            if(refreshFps<=0) {
                SpriteRenderer.instance.drawGeneric(new Draw(mesh,snapshot.frame(),snapshot.materials(),x,y,width,height,Core.height,yaw,zoom,pan,focus,false,null,0f,true,heldParts));
            } else {
                SpriteRenderer.instance.drawGeneric(new PortraitHudDraw(mesh,snapshot.frame(),snapshot.materials(),x,y,width,height,Core.height,yaw,zoom,pan,focus,refreshFps,heldParts));
            }
            return true;
        }catch(Exception e){System.err.println("[GFL2ReadyRoom] Portrait HUD unavailable: "+e);return false;}
    }
    public static synchronized void releasePortraitHud() {
        PortraitHudCache.requestRelease();
    }
    public static synchronized boolean drawBrowse(IsoGameCharacter actor,String id,int x,int y,int width,int height,float yaw,float zoom) {
        return drawBrowseCamera(actor,id,x,y,width,height,yaw,zoom,0,0,false);
    }
    public static synchronized boolean drawBrowseCamera(IsoGameCharacter actor,String id,int x,int y,int width,int height,float yaw,float zoom,float pan,float focus,boolean locked) {
        return drawBrowse(actor,id,x,y,width,height,yaw,zoom,pan,focus,locked,false);
    }

    public static synchronized boolean drawOverviewCamera(IsoGameCharacter actor,String id,int x,int y,int width,int height,float yaw,float zoom,float pan,float focus,boolean locked) {
        return drawBrowse(actor,id,x,y,width,height,yaw,zoom,pan,focus,locked,true);
    }

    private static boolean drawBrowse(IsoGameCharacter actor,String id,int x,int y,int width,int height,float yaw,float zoom,float pan,float focus,boolean locked,boolean overview) {
        ModelSession equipped=Main.session(actor);
        if(equipped!=null&&!equipped.entry.id().equals(id))equipped=null;
        BrowseState state=browsePreviews.computeIfAbsent(actor,unused->new BrowseState());
        if(equipped!=null){
            state.discardRequest();
            List<GripRoomPreview.Part> heldParts=GripRoomPreview.collectLiveHeld(actor,equipped);
            if(drawSession(equipped,x,y,width,height,yaw,zoom,pan,focus,locked,heldParts)){
                state.present(equipped,false);
                return true;
            }
            return drawSession(state.shown,x,y,width,height,yaw,zoom,pan,focus,locked,List.of());
        }

        var worldPlayer=actor.getAnimationPlayer();
        if(worldPlayer==null||!worldPlayer.isReady())return false;
        BrowsePreview preview=state.requested;
        if(preview==null||!preview.id.equals(id)||preview.appearanceSource!=null&&equipped==null) {
            state.discardRequest();
            try {
                preview=new BrowsePreview(id,Main.createRoomPreview(id));
            }catch(Exception | LinkageError failure){
                System.err.println("[GFL2ReadyRoom] Browse preview unavailable: "+failure);
                preview=new BrowsePreview(id,null);
            }
            state.requested=preview;
        }
        if(preview.session==null||preview.session.failed)return false;
        try {
            preview.update(equipped,worldPlayer);
        }catch(Exception | LinkageError failure){
            preview.session.fail(failure);
            return false;
        }
        if(drawSession(preview.session,x,y,width,height,yaw,zoom,pan,focus,locked,List.of())){
            state.present(preview.session,true);
            return true;
        }
        return drawSession(state.shown,x,y,width,height,yaw,zoom,pan,focus,locked,List.of());
    }
    private static boolean drawSession(ModelSession session,int x,int y,int width,int height,float yaw,float zoom,float pan,float focus,boolean locked,List<GripRoomPreview.Part> heldParts) {
        if(session==null||!session.ready||session.retired||session.failed||width<16||height<16)return false;
        try {
            ModelSession.Snapshot snapshot=session.snapshot();if(snapshot==null)return false;
            Mesh mesh=session.plainMesh(snapshot.frame());
            SpriteRenderer.instance.drawGeneric(new Draw(mesh,snapshot.frame(),snapshot.materials(),x,y,width,height,Core.height,yaw,zoom,pan,focus,locked,null,0f,false,heldParts));
            return true;
        }catch(Exception e){System.err.println("[GFL2ReadyRoom] Preview unavailable: "+e);return false;}
    }
    public static synchronized void release(IsoGameCharacter actor) {
        GripCalibration.flush();
        GripRoomPreview.release(actor);
        BrowseState state=browsePreviews.remove(actor);
        if(state!=null){state.discardRequest();state.present(null,false);}
        Runnable hook=studioReleaseHook;
        if(hook!=null)hook.run();
    }
    private record PreviewProgram(int id,int vp,int turn,int image,int locked,int studio,int mode,int eye,int floorY,int heightSpan,int effectAlpha) {
        // LWJGL keeps these capabilities with their GL context. The context owns the program.
        private static final WeakHashMap<GLCapabilities,PreviewProgram> programs=new WeakHashMap<>();

        static synchronized PreviewProgram current() {
            return programs.computeIfAbsent(GL.getCapabilities(),context->link());
        }

        private static int shader(int kind,String source){
            int id=GL20.glCreateShader(kind);GL20.glShaderSource(id,source);GL20.glCompileShader(id);
            if(GL20.glGetShaderi(id,GL20.GL_COMPILE_STATUS)==0){String error=GL20.glGetShaderInfoLog(id);GL20.glDeleteShader(id);throw new IllegalStateException(error);}return id;
        }

        private static PreviewProgram link() {
            int vertex=shader(GL20.GL_VERTEX_SHADER,"""
                #version 330
                layout(location=0) in vec3 position;layout(location=1) in vec3 normal;layout(location=2) in vec2 texcoord;
                uniform mat4 vp;uniform mat4 turn;out vec3 n;out vec2 uv;out vec3 wPos;
                void main(){vec4 wp=turn*vec4(position,1.);gl_Position=vp*wp;wPos=wp.xyz;n=mat3(turn)*normal;uv=texcoord;}
                """);
            int fragment=0,program=0;
            try {
                fragment=shader(GL20.GL_FRAGMENT_SHADER,"""
                    #version 330
                    in vec3 n;in vec2 uv;in vec3 wPos;
                    uniform sampler2D image;uniform bool locked;uniform bool studio;uniform int mode;
                    uniform vec3 eye;uniform float floorY;uniform float heightSpan;uniform float effectAlpha;
                    out vec4 color;
                    void main(){
                        if(mode==2){
                            float dWide=dot(uv*vec2(1.,1.1),uv*vec2(1.,1.1));if(dWide>=1.)discard;
                            vec2 fL=(uv-vec2(-.38,0.))/vec2(.44,.58);
                            vec2 fR=(uv-vec2(.38,0.))/vec2(.44,.58);
                            float feet=max(exp(-dot(fL,fL)*3.8),exp(-dot(fR,fR)*3.8))*.48;
                            float pool=exp(-dWide*3.2)*.24+pow(1.-dWide,2.)*.16;
                            color=vec4(.02,.035,.045,clamp(feet+pool,0.,.65)*effectAlpha);
                            return;
                        }
                        vec4 c=texture(image,uv);if(c.a<.05)discard;
                        if(mode==1){
                            if(wPos.y>floorY+.002)discard;
                            float below=max(0.,floorY-wPos.y)/heightSpan;
                            float fade=pow(max(0.,1.-below/.36),2.)*.32*effectAlpha;
                            if(fade<.004)discard;
                            vec3 rc=mix(c.rgb*.66,vec3(.16,.26,.29),.34);
                            color=vec4(rc,c.a*fade);
                            return;
                        }
                        vec3 N=normalize(n);if(!gl_FrontFacing)N=-N;
                        if(locked){
                            float light=.76+.24*max(dot(N,normalize(vec3(.35,.8,.6))),0.);
                            float l=dot(c.rgb,vec3(.2126,.7152,.0722));
                            color=vec4(mix(vec3(.08,.16,.18),vec3(.57,.75,.76),l)*light,c.a*.47);
                            return;
                        }
                        if(!studio){
                            float light=.76+.24*max(dot(N,normalize(vec3(.35,.8,.6))),0.);
                            color=vec4(c.rgb*light,c.a);
                            return;
                        }
                        vec3 V=normalize(eye-wPos);
                        float h=clamp((wPos.y-floorY)/heightSpan,0.,1.);
                        float hemi=N.y*.5+.5;
                        vec3 ambient=mix(vec3(.64,.65,.67),vec3(.80,.81,.82),hemi);
                        float wrapKey=clamp((dot(N,normalize(vec3(.48,.52,.70)))+.45)/1.45,0.,1.);
                        vec3 keyLight=vec3(.22,.195,.165)*wrapKey;
                        float fresnel=pow(1.-max(dot(N,V),0.),3.4);
                        float rimSide=smoothstep(.12,.82,dot(N,normalize(vec3(.88,.18,-.44))));
                        vec3 rimWindow=vec3(.30,.24,.16)*fresnel*rimSide;
                        float leftSide=smoothstep(.15,.85,dot(N,normalize(vec3(-.88,-.20,.20))));
                        float lowerMask=1.-smoothstep(.12,.72,h);
                        vec3 rimAmber=vec3(.22,.13,.05)*fresnel*leftSide*lowerMask;
                        float groundAO=mix(.82,1.,smoothstep(0.,.13,h));
                        vec3 lit=c.rgb*(ambient+keyLight)*groundAO+(c.rgb*.35+vec3(.65))*(rimWindow+rimAmber);
                        lit=lit/(1.+max(lit-.94,0.)*.50);
                        color=vec4(lit,c.a);
                    }
                    """);
                program=GL20.glCreateProgram();GL20.glAttachShader(program,vertex);GL20.glAttachShader(program,fragment);GL20.glLinkProgram(program);
                if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
                return new PreviewProgram(program,
                    GL20.glGetUniformLocation(program,"vp"),GL20.glGetUniformLocation(program,"turn"),
                    GL20.glGetUniformLocation(program,"image"),GL20.glGetUniformLocation(program,"locked"),
                    GL20.glGetUniformLocation(program,"studio"),GL20.glGetUniformLocation(program,"mode"),
                    GL20.glGetUniformLocation(program,"eye"),GL20.glGetUniformLocation(program,"floorY"),
                    GL20.glGetUniformLocation(program,"heightSpan"),GL20.glGetUniformLocation(program,"effectAlpha"));
            } catch(RuntimeException|Error failure) {
                if(program!=0)GL20.glDeleteProgram(program);
                throw failure;
            } finally {GL20.glDeleteShader(vertex);if(fragment!=0)GL20.glDeleteShader(fragment);}
        }
    }
    private static ByteBuffer buffer(int bytes){return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());}
    static final class Mesh implements AutoCloseable {
        final ModelSession session;
        final float[] uv;
        final int[] indices,materials,firsts,counts;
        private ByteBuffer upload;
        float centerX,centerY,centerZ,span,faceY,minY,heightSpan;
        private float boneHeadY;
        private boolean boundsPending;
        int vao,vbo,ibo,uvBuffer,itemVao,shadowVao,shadowVbo;
        boolean closed,failed,gpuLayout;
        private PmxDrawMesh.FrameData uploaded;
        Mesh(ModelSession s,PmxDrawMesh.FrameData frame) {
            session=s;int count=frame.positions().length/3;
            ByteBuffer bytes=buffer(count*8);s.api.CopyDataToByteBuffer(bytes,s.api.GetUVs(s.model.handle()),bytes.capacity());
            uv=new float[count*2];bytes.asFloatBuffer().get(uv);
            int size=Math.toIntExact(s.api.GetIndexElementSize(s.model.handle()));
            if(size!=1&&size!=2&&size!=4)throw new IllegalArgumentException("PMX index size");
            indices=new int[Math.toIntExact(s.api.GetIndexCount(s.model.handle()))];bytes=buffer(indices.length*size);
            s.api.CopyDataToByteBuffer(bytes,s.api.GetIndices(s.model.handle()),bytes.capacity());
            for(int i=0;i<indices.length;i++)indices[i]=size==1?Byte.toUnsignedInt(bytes.get(i)):size==2?Short.toUnsignedInt(bytes.getShort(i*2)):bytes.getInt(i*4);
            int parts=Math.toIntExact(s.api.GetSubMeshCount(s.model.handle()));materials=new int[parts];firsts=new int[parts];counts=new int[parts];
            for(int i=0;i<parts;i++){materials[i]=s.api.GetSubMeshMaterialID(s.model.handle(),i);firsts[i]=s.api.GetSubMeshBeginIndex(s.model.handle(),i);counts[i]=s.api.GetSubMeshVertexCount(s.model.handle(),i);}
            float headBone=Float.NaN;
            if(s.targetHead>=0) {
                JSONArray bones=new JSONArray(s.api.GetBoneMetadata(s.model.handle()));
                JSONObject bone=bones.optJSONObject(s.targetHead);
                JSONArray pos=bone==null?null:bone.optJSONArray("initial_position");
                if(pos!=null&&pos.length()>1) {
                    float y=(float)pos.optDouble(1);
                    if(Float.isFinite(y)&&y>4f)headBone=y;
                }
            }
            boneHeadY=headBone;
            if(!tryUpdateBounds(frame)) {
                // On f:0 before PZ AnimationPlayer populates its first bone pose, vertices can collapse
                // near the feet (e.g. loY=-5.8, maxY=+1.2, centerX=+6.5). Seed canonical upright bounds
                // from the model's rest-pose head height and refine on the first healthy standing frame.
                float estMaxY=Float.isFinite(headBone)?headBone/0.86f:s.scale*0.95f;
                centerX=0f;centerY=estMaxY*0.5f;centerZ=0f;
                minY=0f;heightSpan=Math.max(1f,estMaxY);
                span=heightSpan*1.20f;
                faceY=Float.isFinite(headBone)?Math.min(estMaxY,headBone+0.027f*span):estMaxY*0.885f;
                boundsPending=true;
            }
        }
        private boolean tryUpdateBounds(PmxDrawMesh.FrameData frame) {
            if(frame==null||frame.positions()==null)return false;
            float[] p=frame.positions();
            if(p.length<3)return false;
            float minX=Float.POSITIVE_INFINITY,loY=minX,minZ=minX,maxX=-minX,maxY=-minX,maxZ=-minX;
            for(int i=0;i<p.length;i+=3){
                float px=p[i],py=p[i+1],pz=p[i+2];
                if(px<minX)minX=px;if(px>maxX)maxX=px;
                if(py<loY)loY=py;if(py>maxY)maxY=py;
                if(pz<minZ)minZ=pz;if(pz>maxZ)maxZ=pz;
            }
            float h=maxY-loY;
            float cx=(minX+maxX)*0.5f;
            float minValidH=Float.isFinite(boneHeadY)?boneHeadY*0.72f:10f;
            // Reject degenerate f:0 identity/collapsed poses where the model lies sideways near y=0.
            if(!Float.isFinite(h)||h<minValidH||loY<-2.5f||maxY<minValidH*0.8f||Math.abs(cx)>4.0f)return false;
            centerX=cx;centerY=(loY+maxY)*0.5f;centerZ=(minZ+maxZ)*0.5f;
            minY=loY;heightSpan=Math.max(1e-3f,h);
            span=Math.max(1f,h)*1.20f;
            float head=maxY-h*.115f;
            if(Float.isFinite(boneHeadY)&&boneHeadY>centerY&&boneHeadY<maxY)head=Math.min(maxY,boneHeadY+.027f*span);
            faceY=head;
            boundsPending=false;
            return true;
        }
        private void initialize(){
            if(upload==null)upload=buffer((uv.length/2)*32);
            vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);vbo=GL15.glGenBuffers();ibo=GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,upload.capacity(),GL15.GL_STREAM_DRAW);
            for(int i=0;i<3;i++)GL20.glEnableVertexAttribArray(i);
            bindCpuLayout();
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,ibo);GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,indices,GL15.GL_STATIC_DRAW);
        }
        private void bindCpuLayout() {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
            for(int i=0;i<3;i++)GL20.glVertexAttribPointer(i,i==2?2:3,GL11.GL_FLOAT,false,32,i==0?0L:i==1?12L:24L);
            gpuLayout=false;
        }
        void bindGeometry(PmxDrawMesh.FrameData frame) {
            if(vao==0)initialize();
            GL30.glBindVertexArray(vao);
            if(frame.gpuPose()!=null) {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,frame.gpuPose().vertexBuffer());
                GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,32,0L);
                GL20.glVertexAttribPointer(1,3,GL11.GL_FLOAT,false,32,16L);
                if(uvBuffer==0){uvBuffer=GL15.glGenBuffers();GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,uvBuffer);
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER,uv,GL15.GL_STATIC_DRAW);}
                else GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,uvBuffer);
                GL20.glVertexAttribPointer(2,2,GL11.GL_FLOAT,false,8,0L);
                gpuLayout=true;
                return;
            }
            if(gpuLayout)bindCpuLayout();else GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
            if(uploaded==frame)return;
            upload.clear();
            float[] p=frame.positions(),n=frame.normals();
            for(int i=0;i<p.length/3;i++)upload.putFloat(p[i*3]).putFloat(p[i*3+1]).putFloat(p[i*3+2]).putFloat(n[i*3]).putFloat(n[i*3+1]).putFloat(n[i*3+2]).putFloat(uv[i*2]).putFloat(uv[i*2+1]);
            upload.flip();GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER,0,upload);
            uploaded=frame;
        }
        void paint(PmxDrawMesh.FrameData frame,ModelSession.Materials state,int width,int height,float yaw,float zoom,float pan,float focus,boolean locked,GripRoomPreview.View gripView)throws Exception {
            paint(frame,state,0,0,width,height,height,yaw,zoom,pan,focus,locked,gripView,0f,false,List.of());
        }
        private void paintShadowQuad(PreviewProgram program,Matrix4f turn,float alpha) {
            if(shadowVao==0){
                shadowVao=GL30.glGenVertexArrays();GL30.glBindVertexArray(shadowVao);
                shadowVbo=GL15.glGenBuffers();GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,shadowVbo);
                float rx=heightSpan*.24f,rz=heightSpan*.18f,y=minY+.001f;
                float[] quad={
                    centerX-rx,y,centerZ-rz, 0,1,0, -1,-1,
                    centerX+rx,y,centerZ-rz, 0,1,0,  1,-1,
                    centerX+rx,y,centerZ+rz, 0,1,0,  1, 1,
                    centerX-rx,y,centerZ-rz, 0,1,0, -1,-1,
                    centerX+rx,y,centerZ+rz, 0,1,0,  1, 1,
                    centerX-rx,y,centerZ+rz, 0,1,0, -1, 1,
                };
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER,quad,GL15.GL_STATIC_DRAW);
                for(int i=0;i<3;i++){GL20.glEnableVertexAttribArray(i);GL20.glVertexAttribPointer(i,i==2?2:3,GL11.GL_FLOAT,false,32,i==0?0L:i==1?12L:24L);}
            } else {
                GL30.glBindVertexArray(shadowVao);
            }
            GL20.glUniformMatrix4fv(program.turn(),false,turn.get(new float[16]));
            GL20.glUniform1i(program.mode(),2);
            GL20.glUniform1f(program.effectAlpha(),alpha);
            GL11.glDepthMask(false);
            GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);
            GL11.glDepthMask(true);
        }
        private void drawSubMeshes(PmxDrawMesh.FrameData frame,Texture[] textures) {
            for(int i=0;i<materials.length;i++){
                int material=materials[i];if(frame.materials()[material*12+3]<=0)continue;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[material].getID());
                GL11.glDrawElements(GL11.GL_TRIANGLES,counts[i],GL11.GL_UNSIGNED_INT,(long)firsts[i]*4);
            }
        }
        void paint(PmxDrawMesh.FrameData frame,ModelSession.Materials state,int x,int y,int width,int height,int screenHeight,float yaw,float zoom,float pan,float focus,boolean locked,GripRoomPreview.View gripView,float pitch,boolean hud,List<GripRoomPreview.Part> heldParts)throws Exception {
            if(boundsPending&&frame.positions()!=null)tryUpdateBounds(frame);
            float clampedZoom=Math.clamp(zoom,.62f,4.2f);
            float extent=span/clampedZoom,aspect=(float)width/height;
            float baseCameraY=centerY+(faceY-centerY)*Math.clamp(focus,0,1)+span*Math.clamp(pan,-.8f,.8f);
            float cameraX=centerX,cameraY=baseCameraY,cameraZ=centerZ;
            if(hud&&frame.shadingHead()!=null&&frame.shadingHead().length>=11) {
                float[] sh=frame.shadingHead();
                float track=Math.clamp((focus-.25f)/.65f,0f,1f);
                if(track>1e-3f) {
                    float maxOff=heightSpan*.55f;
                    float hx=Math.clamp(sh[8],centerX-maxOff,centerX+maxOff);
                    float hy=Math.clamp(sh[9],faceY-maxOff,faceY+maxOff)+span*Math.clamp(pan,-.8f,.8f);
                    float hz=Math.clamp(sh[10],centerZ-maxOff,centerZ+maxOff);
                    cameraX=centerX+(hx-centerX)*track;
                    cameraY=baseCameraY+(hy-baseCameraY)*track;
                    cameraZ=centerZ+(hz-centerZ)*track;
                }
            }
            boolean studio=gripView==null;
            if(studio&&!locked&&studioEnabled&&studioRenderer!=null
                    &&frame.positions()!=null&&frame.normals()!=null&&frame.shadingHead()!=null) {
                float fov=(float)Math.toRadians(28.0);
                float dist=(extent*.5f)/(float)Math.tan(fov*.5f);
                float near=Math.max(.05f,dist*.1f),far=dist+span*4.5f;
                float eyeZ=cameraZ+dist;
                float floorAlpha=hud?0f:Math.clamp((2.2f-clampedZoom)/.8f,0f,1f);
                if(floorAlpha>.01f){
                    Matrix4f vp=new Matrix4f().perspective(fov,aspect,near,far)
                        .lookAt(cameraX,cameraY,eyeZ,cameraX,cameraY,cameraZ,0,1,0);
                    Matrix4f turn=new Matrix4f().translate(cameraX,cameraY,cameraZ).rotateX(pitch).rotateY(yaw).translate(-cameraX,-cameraY,-cameraZ);
                    PreviewProgram program=PreviewProgram.current();
                    GL20.glUseProgram(program.id());
                    GL20.glUniformMatrix4fv(program.vp(),false,vp.get(new float[16]));
                    GL20.glUniform1i(program.studio(),1);
                    GL20.glUniform1f(program.floorY(),minY);
                    GL20.glUniform1f(program.heightSpan(),heightSpan);
                    GL11.glDisable(GL11.GL_CULL_FACE);
                    GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LEQUAL);
                    GL11.glEnable(GL11.GL_BLEND);GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                    GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);
                    paintShadowQuad(program,turn,floorAlpha);
                }
                StudioRenderer renderer=studioRenderer;
                List<StudioItem> studioItems=GripRoomPreview.toStudioItems(heldParts);
                if(renderer!=null&&renderer.render(session.pmxSha256(),frame.positions(),frame.normals(),uv,
                        frame.materials(),frame.shadingHead(),session.scale,
                        cameraX,cameraY,cameraZ,dist,yaw,pitch,fov,near,far,x,y,width,height,screenHeight,studioItems)) {
                    return;
                }
            }
            bindGeometry(frame);
            Matrix4f vp;
            float eyeZ;
            if(!studio){
                var point=gripView.center();
                cameraX=point.x;cameraY=point.y;cameraZ=point.z;
                extent=span*Math.max(.72f,.90f/aspect)/clampedZoom;
                eyeZ=cameraZ+span*3;
                vp=new Matrix4f().ortho(-extent*aspect/2,extent*aspect/2,-extent/2,extent/2,.1f,span*8)
                    .lookAt(cameraX,cameraY,eyeZ,cameraX,cameraY,cameraZ,0,1,0);
            } else {
                float fov=(float)Math.toRadians(28.0);
                float dist=(extent*.5f)/(float)Math.tan(fov*.5f);
                float near=Math.max(.05f,dist*.1f),far=dist+span*4.5f;
                eyeZ=cameraZ+dist;
                vp=new Matrix4f().perspective(fov,aspect,near,far)
                    .lookAt(cameraX,cameraY,eyeZ,cameraX,cameraY,cameraZ,0,1,0);
            }
            Matrix4f turn=new Matrix4f().translate(cameraX,cameraY,cameraZ).rotateX(pitch).rotateY(yaw).translate(-cameraX,-cameraY,-cameraZ);
            PreviewProgram program=PreviewProgram.current();
            GL20.glUseProgram(program.id());
            GL20.glUniformMatrix4fv(program.vp(),false,vp.get(new float[16]));
            GL20.glUniform1i(program.image(),0);
            GL20.glUniform1i(program.locked(),locked?1:0);
            GL20.glUniform1i(program.studio(),studio?1:0);
            GL20.glUniform3f(program.eye(),cameraX,cameraY,eyeZ);
            GL20.glUniform1f(program.floorY(),minY);
            GL20.glUniform1f(program.heightSpan(),heightSpan);
            Texture[] textures=session.preparePlainMaterials(state);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL33.glBindSampler(0,0);GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LEQUAL);GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_BLEND);GL14.glBlendEquation(GL14.GL_FUNC_ADD);GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);
            float floorAlpha=hud?0f:(studio?Math.clamp((2.2f-clampedZoom)/.8f,0f,1f):0f);
            if(floorAlpha>.01f){
                if(!locked){
                    Matrix4f reflect=new Matrix4f().translate(0,2f*minY,0).scale(1f,-1f,1f).mul(turn);
                    GL20.glUniformMatrix4fv(program.turn(),false,reflect.get(new float[16]));
                    GL20.glUniform1i(program.mode(),1);
                    GL20.glUniform1f(program.effectAlpha(),floorAlpha);
                    drawSubMeshes(frame,textures);
                    GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                }
                paintShadowQuad(program,turn,locked?floorAlpha*.55f:floorAlpha);
                GL30.glBindVertexArray(vao);
            }
            GL20.glUniformMatrix4fv(program.turn(),false,turn.get(new float[16]));
            GL20.glUniform1i(program.mode(),0);
            GL20.glUniform1f(program.effectAlpha(),1f);
            drawSubMeshes(frame,textures);
            List<GripRoomPreview.Part> items=gripView!=null?gripView.parts():heldParts;
            if(items!=null&&!items.isEmpty()){
                if(itemVao==0)itemVao=GL30.glGenVertexArrays();
                GripRoomPreview.paintItems(items,itemVao,program.turn(),turn);
            }
        }
        @Override public void close(){if(closed)return;closed=true;uploaded=null;upload=null;if(uvBuffer!=0)GL15.glDeleteBuffers(uvBuffer);if(vbo!=0)GL15.glDeleteBuffers(vbo);if(ibo!=0)GL15.glDeleteBuffers(ibo);if(vao!=0)GL30.glDeleteVertexArrays(vao);if(itemVao!=0)GL30.glDeleteVertexArrays(itemVao);if(shadowVbo!=0)GL15.glDeleteBuffers(shadowVbo);if(shadowVao!=0)GL30.glDeleteVertexArrays(shadowVao);}
    }
    static final class Draw extends TextureDraw.GenericDrawer {
        final Mesh mesh;final PmxDrawMesh.FrameData frame;final ModelSession.Materials materials;
        final int x,y,width,height,screenHeight;final float yaw,zoom,pan,focus,pitch;final boolean locked,hud;
        final GripRoomPreview.View gripView;final List<GripRoomPreview.Part> heldParts;
        Draw(Mesh mesh,PmxDrawMesh.FrameData frame,ModelSession.Materials materials,int x,int y,int w,int h,int sh,float yaw,float zoom){
            this(mesh,frame,materials,x,y,w,h,sh,yaw,zoom,0,0,false);
        }
        Draw(Mesh mesh,PmxDrawMesh.FrameData frame,ModelSession.Materials materials,int x,int y,int w,int h,int sh,float yaw,float zoom,float pan,float focus,boolean locked){
            this(mesh,frame,materials,x,y,w,h,sh,yaw,zoom,pan,focus,locked,null,0,false,List.of());
        }
        Draw(Mesh mesh,PmxDrawMesh.FrameData frame,ModelSession.Materials materials,int x,int y,int w,int h,int sh,float yaw,float zoom,float pan,float focus,boolean locked,GripRoomPreview.View gripView,float pitch){
            this(mesh,frame,materials,x,y,w,h,sh,yaw,zoom,pan,focus,locked,gripView,pitch,false,List.of());
        }
        Draw(Mesh mesh,PmxDrawMesh.FrameData frame,ModelSession.Materials materials,int x,int y,int w,int h,int sh,float yaw,float zoom,float pan,float focus,boolean locked,GripRoomPreview.View gripView,float pitch,boolean hud,List<GripRoomPreview.Part> heldParts){
            this.gripView=gripView;this.pitch=pitch;this.hud=hud;this.heldParts=heldParts==null?List.of():heldParts;
            this.mesh=mesh;this.frame=frame;this.materials=materials;this.x=x;this.y=y;width=w;height=h;screenHeight=sh;this.yaw=yaw;this.zoom=zoom;
            this.pan=pan;this.focus=focus;this.locked=locked;
        }
        public void render(){
            paintNow();
        }
        boolean paintNow(){
            if(mesh.closed||mesh.failed||mesh.session.retired)return false;
            State saved=new State();
            try {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(x,screenHeight-y-height,width,height);GL11.glViewport(x,screenHeight-y-height,width,height);
                GL11.glDepthMask(true);GL11.glClearDepth(1);GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                mesh.paint(frame,materials,x,y,width,height,screenHeight,yaw,zoom,pan,focus,locked,gripView,pitch,hud,heldParts);
                return true;
            }catch(Exception|LinkageError e){
                if(frame.gpuPose()!=null)mesh.session.disableGpu(e);
                else {mesh.failed=true;System.err.println("[GFL2ReadyRoom] Preview draw failed: "+e);}
                return false;
            }
            finally {
                saved.restore();
                SpriteRenderer.ringBuffer.restoreVbos=true;SpriteRenderer.ringBuffer.restoreBoundTextures=true;
            }
        }
    }
    static final class PortraitHudDraw extends TextureDraw.GenericDrawer {
        final Mesh mesh;final PmxDrawMesh.FrameData frame;final ModelSession.Materials materials;
        final int x,y,width,height,screenHeight,refreshFps;final float yaw,zoom,pan,focus;
        final List<GripRoomPreview.Part> heldParts;
        PortraitHudDraw(Mesh mesh,PmxDrawMesh.FrameData frame,ModelSession.Materials materials,int x,int y,int w,int h,int sh,float yaw,float zoom,float pan,float focus,int refreshFps,List<GripRoomPreview.Part> heldParts){
            this.mesh=mesh;this.frame=frame;this.materials=materials;this.x=x;this.y=y;this.width=w;this.height=h;this.screenHeight=sh;
            this.yaw=yaw;this.zoom=zoom;this.pan=pan;this.focus=focus;this.refreshFps=refreshFps;
            this.heldParts=heldParts==null?List.of():heldParts;
        }
        @Override public void render() {
            PortraitHudCache.render(this);
        }
    }
    /** Offscreen RGBA8 cache for Mode A HUD so 24/30 FPS throttling blits a single premultiplied quad on skipped frames. */
    static final class PortraitHudCache {
        private static int fbo,colorTex,depthRbo,cachedW,cachedH;
        private static int quadProgram,quadVao,quadImageLoc;
        private static GLCapabilities quadCaps;
        private static PmxDrawMesh.FrameData lastFrame;
        private static Mesh lastMesh;
        private static float lastYaw,lastZoom,lastPan,lastFocus,lastScale;
        private static int lastHeldCount;
        private static long lastRevision,lastRenderNanos;
        private static volatile boolean valid;

        static void invalidate() {
            valid=false;
        }

        private static void releaseFboGl() {
            if(fbo!=0){GL30.glDeleteFramebuffers(fbo);fbo=0;}
            if(colorTex!=0){GL11.glDeleteTextures(colorTex);colorTex=0;}
            if(depthRbo!=0){GL30.glDeleteRenderbuffers(depthRbo);depthRbo=0;}
            cachedW=cachedH=0;valid=false;lastMesh=null;lastFrame=null;
        }
        static void releaseAllGl() {
            releaseFboGl();
            if(quadProgram!=0){GL20.glDeleteProgram(quadProgram);quadProgram=0;}
            if(quadVao!=0){GL30.glDeleteVertexArrays(quadVao);quadVao=0;}
            quadCaps=null;
        }
        static void requestRelease() {
            valid=false;lastMesh=null;lastFrame=null;
            try { RenderThread.queueInvokeOnRenderContext(PortraitHudCache::releaseAllGl); }
            catch(Throwable ignored) {}
        }
        private static void ensureQuadProgram() {
            GLCapabilities caps=GL.getCapabilities();
            if(quadProgram!=0&&quadCaps==caps)return;
            if(quadProgram!=0){GL20.glDeleteProgram(quadProgram);quadProgram=0;}
            if(quadVao!=0){GL30.glDeleteVertexArrays(quadVao);quadVao=0;}
            int vs=PreviewProgram.shader(GL20.GL_VERTEX_SHADER,"""
                #version 330
                out vec2 vUv;
                void main(){
                    vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);
                    vUv=p;
                    gl_Position=vec4(p*2.0-1.0,0.0,1.0);
                }
                """);
            int fs=0,prog=0;
            try {
                fs=PreviewProgram.shader(GL20.GL_FRAGMENT_SHADER,"""
                    #version 330
                    in vec2 vUv;
                    uniform sampler2D uImage;
                    out vec4 color;
                    void main(){
                        vec4 c=texture(uImage,vUv);
                        if(c.a<=0.002)discard;
                        color=c;
                    }
                    """);
                prog=GL20.glCreateProgram();
                GL20.glAttachShader(prog,vs);GL20.glAttachShader(prog,fs);GL20.glLinkProgram(prog);
                if(GL20.glGetProgrami(prog,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(prog));
                quadProgram=prog;quadImageLoc=GL20.glGetUniformLocation(prog,"uImage");
                quadVao=GL30.glGenVertexArrays();quadCaps=caps;
            } catch(RuntimeException|Error e) {
                if(prog!=0)GL20.glDeleteProgram(prog);
                throw e;
            } finally {
                GL20.glDeleteShader(vs);if(fs!=0)GL20.glDeleteShader(fs);
            }
        }
        private static void ensureFbo(int w,int h) {
            if(fbo!=0&&cachedW==w&&cachedH==h)return;
            releaseFboGl();
            fbo=GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
            colorTex=GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,colorTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,w,h,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,colorTex,0);
            depthRbo=GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,depthRbo);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER,GL30.GL_DEPTH24_STENCIL8,w,h);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_STENCIL_ATTACHMENT,GL30.GL_RENDERBUFFER,depthRbo);
            int status=GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if(status!=GL30.GL_FRAMEBUFFER_COMPLETE) {
                releaseFboGl();
                throw new IllegalStateException("Portrait HUD FBO incomplete: "+status);
            }
            cachedW=w;cachedH=h;
        }
        static void render(PortraitHudDraw d) {
            if(d.mesh.closed||d.mesh.failed||d.mesh.session.retired)return;
            State saved=new State();
            try {
                ensureQuadProgram();
                float resScale=studioResolutionScale();
                // When GF2 studio renderer handles SSAA internally, keep cache FBO at display size;
                // when plain GL33 fallback is active, apply resScale to the cache FBO so the setting still works.
                boolean studioActive=studioPreviewAvailable();
                int targetW=studioActive?d.width:Math.max(16,Math.round(d.width*resScale));
                int targetH=studioActive?d.height:Math.max(16,Math.round(d.height*resScale));
                ensureFbo(targetW,targetH);
                long now=System.nanoTime();
                long minInterval=1_000_000_000L/Math.max(1,d.refreshFps);
                long rev=d.mesh.session.model.appearance().revision();
                int heldCount=d.heldParts.size();
                boolean paramsChanged=!valid||lastMesh!=d.mesh||lastRevision!=rev||lastHeldCount!=heldCount
                    ||Math.abs(lastScale-resScale)>1e-3f
                    ||Math.abs(lastYaw-d.yaw)>1e-3f||Math.abs(lastZoom-d.zoom)>1e-3f
                    ||Math.abs(lastPan-d.pan)>1e-3f||Math.abs(lastFocus-d.focus)>1e-3f;
                boolean intervalElapsed=(now-lastRenderNanos)>=minInterval;
                if(paramsChanged||(lastFrame!=d.frame&&intervalElapsed)) {
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
                    GL11.glDisable(GL11.GL_SCISSOR_TEST);
                    GL11.glViewport(0,0,targetW,targetH);
                    GL11.glColorMask(true,true,true,true);
                    GL11.glClearColor(0f,0f,0f,0f);
                    GL11.glDepthMask(true);
                    GL11.glClearDepth(1.0);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
                    d.mesh.paint(d.frame,d.materials,0,0,targetW,targetH,targetH,d.yaw,d.zoom,d.pan,d.focus,false,null,0f,true,d.heldParts);
                    lastMesh=d.mesh;lastFrame=d.frame;lastRevision=rev;lastHeldCount=heldCount;lastScale=resScale;
                    lastYaw=d.yaw;lastZoom=d.zoom;lastPan=d.pan;lastFocus=d.focus;
                    lastRenderNanos=now;valid=true;
                }
                if(!valid)return;
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,saved.drawFbo);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,saved.readFbo);
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(d.x,d.screenHeight-d.y-d.height,d.width,d.height);
                GL11.glViewport(d.x,d.screenHeight-d.y-d.height,d.width,d.height);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glColorMask(true,true,true,true);
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                GL14.glBlendFuncSeparate(GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL20.glUseProgram(quadProgram);
                GL20.glUniform1i(quadImageLoc,0);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,colorTex);
                GL33.glBindSampler(0,0);
                GL30.glBindVertexArray(quadVao);
                GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
            } catch(Exception|LinkageError e) {
                if(d.frame.gpuPose()!=null)d.mesh.session.disableGpu(e);
                else {d.mesh.failed=true;System.err.println("[GFL2ReadyRoom] Portrait HUD draw failed: "+e);}
            } finally {
                saved.restore();
                SpriteRenderer.ringBuffer.restoreVbos=true;SpriteRenderer.ringBuffer.restoreBoundTextures=true;
            }
        }
    }
    // Core-profile-safe state scope; legacy glPushAttrib is unavailable on some hosts.
    static final class State {
        final int drawFbo=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),readFbo=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),vao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),vbo=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        final int unit=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),depthFunc=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        final int srcRGB=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dstRGB=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),srcAlpha=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstAlpha=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        final int equationRGB=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),equationAlpha=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        final boolean blend=GL11.glIsEnabled(GL11.GL_BLEND),depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final double clearDepth=GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        final int[] viewport=new int[4],box=new int[4];final int texture,sampler;
        State(){GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,box);GL13.glActiveTexture(GL13.GL_TEXTURE0);texture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);sampler=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,0);}
        static void enabled(int flag,boolean value){if(value)GL11.glEnable(flag);else GL11.glDisable(flag);}
        void restore(){
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,drawFbo);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,readFbo);
            GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);GL11.glScissor(box[0],box[1],box[2],box[3]);
            enabled(GL11.GL_BLEND,blend);enabled(GL11.GL_DEPTH_TEST,depth);enabled(GL11.GL_CULL_FACE,cull);enabled(GL11.GL_SCISSOR_TEST,scissor);
            GL14.glBlendFuncSeparate(srcRGB,dstRGB,srcAlpha,dstAlpha);GL20.glBlendEquationSeparate(equationRGB,equationAlpha);GL11.glDepthMask(depthMask);GL11.glDepthFunc(depthFunc);GL11.glClearDepth(clearDepth);
            GL20.glUseProgram(program);GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL33.glBindSampler(0,sampler);GL13.glActiveTexture(unit);
        }
    }
}
