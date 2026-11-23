package gfl2.runtime;

import gfl2.motion.WorldPoseRetargeter.GripAdjustment;
import java.lang.ref.WeakReference;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.joml.Matrix4f;
import org.json.JSONArray;
import org.json.JSONObject;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.vm.KahluaTable;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.WeaponType;

/** Persistent per-character calibration keyed by the held item's full type. */
final class GripCalibration {
    private static final String STORE="GFL2GripCalibration";
    private static final int POSITION_AXES=6, ITEM_AXIS=12, CURL_AXIS=15, COUNT=17;
    private static final long TRANSITION_NANOS=150_000_000L;
    private static final Map<IsoGameCharacter,AimBlend> blends=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Object storeLock=new Object();
    private static final Map<String,Map<String,Object>> standingStore=new HashMap<>();
    private static final Map<String,Map<String,Object>> aimingStore=new HashMap<>();
    private static volatile Path overrideStoreFile;
    private static boolean loadedStore;
    private static long dirtyRevision, savedRevision, lastEditNanos;
    private static Thread flushThread;

    private static final class AimBlend {
        final String modelId;
        final WeakReference<InventoryItem> item;
        private long started;
        private float from;
        private boolean aim;

        AimBlend(String modelId,InventoryItem item,boolean aim,long now) {
            this.modelId=modelId;this.item=new WeakReference<>(item);this.aim=aim;
            from=aim?1:0;started=now;
        }

        float advance(boolean target,long now) {
            float progress=Math.clamp((now-started)/(float)TRANSITION_NANOS,0,1);
            float smooth=progress*progress*(3-2*progress);
            float current=from+((aim?1:0)-from)*smooth;
            if(target!=aim){
                from=current;
                aim=target;
                started=now;
            }
            return current;
        }
    }

    private record Character(String key,List<String> skins) {}
    private static volatile Map<String,Character> characters;

    static synchronized void loadCharacters(Path registry) throws IOException {
        var packs=new JSONObject(Files.readString(registry)).optJSONObject("packs");
        var bySkin=new HashMap<String,Character>();
        if(packs!=null)for(String key:packs.keySet()){
            var ids=packs.getJSONArray(key).toList().stream().map(Object::toString).toList();
            var character=new Character(key,ids);
            for(String id:ids)bySkin.put(id,character);
        }
        characters=Map.copyOf(bySkin);
    }

    private static Character character(String id) {
        var bySkin=characters;
        if(bySkin==null){
            String mod=ZomboidFileSystem.instance.getModDir(Main.MOD_ID);
            if(mod==null)return null;
            Path root=Path.of(mod);
            Path registry=root.resolve("media/gfl2_pmx/registry.json");
            if(!Files.isRegularFile(registry))registry=root.resolve("42/media/gfl2_pmx/registry.json");
            try{loadCharacters(registry);}
            catch(IOException | RuntimeException failure){
                System.err.println("[GFL2Exilium] Grip calibration registry: "+failure);
                characters=Map.of();
            }
            bySkin=characters;
        }
        return bySkin.get(id);
    }

    private static int limit(int axis){return axis>=CURL_AXIS?100:axis>=ITEM_AXIS?20:axis>=POSITION_AXES?90:12;}

    static int heldSide(IsoGameCharacter actor) {
        if(!(actor instanceof IsoPlayer player)||!player.isLocalPlayer())return -1;
        if(actor.getPrimaryHandItem()!=null)return 1;
        return actor.getSecondaryHandItem()!=null?0:-1;
    }

    static InventoryItem heldItem(IsoGameCharacter actor) {
        int side=heldSide(actor);
        return side==1?actor.getPrimaryHandItem():side==0?actor.getSecondaryHandItem():null;
    }

    static String itemKey(IsoGameCharacter actor) {
        InventoryItem item=heldItem(actor);
        return item==null?"":item.getFullType();
    }

    private static KahluaTable table(KahluaTable parent,String key,boolean create) {
        if(parent==null)return null;
        if(parent.rawget(key) instanceof KahluaTable child)return child;
        if(!create)return null;
        KahluaTable child=J2SEPlatform.getInstance().newTable();
        parent.rawset(key,child);
        return child;
    }

    private static KahluaTable legacy(KahluaTable model,InventoryItem item) {
        if(!(item instanceof HandWeapon weapon)||!weapon.isRanged())return null;
        String family=(weapon.isTwoHandWeapon()?WeaponType.FIREARM:WeaponType.HANDGUN).getType();
        return table(model,family,false);
    }

    private static Object savedRow(KahluaTable mode,InventoryItem item,boolean aiming) {
        if(mode==null)return null;
        Object saved=mode.rawget(item.getFullType());
        return saved==null&&!aiming?legacy(mode,item):saved;
    }

    private static Object characterRow(KahluaTable store,String id,InventoryItem item,boolean aiming) {
        if(store==null)return null;
        Character character=character(id);
        if(character==null)return savedRow(table(store,id,false),item,aiming);
        String weapon=item.getFullType();
        KahluaTable shared=table(store,character.key(),false);
        Object saved=shared==null?null:shared.rawget(weapon);
        if(saved!=null)return saved;

        // Preserve the current skin's earlier edits before looking at its siblings.
        saved=savedRow(table(store,id,false),item,aiming);
        if(saved==null)for(String skin:character.skins()){
            if(skin.equals(id))continue;
            saved=savedRow(table(store,skin,false),item,aiming);
            if(saved!=null)break;
        }
        if(saved instanceof KahluaTable old){
            KahluaTable copy=J2SEPlatform.getInstance().newTable();
            for(int axis=0;axis<COUNT;axis++){
                double v=value(old,axis);
                if(v!=0)copy.rawset(axis+1,Double.valueOf(v));
            }
            saved=copy;
        }
        if(saved!=null)table(store,character.key(),true).rawset(weapon,saved);
        return saved;
    }

    static void setStoreFileForTest(Path file) {
        synchronized(storeLock){
            overrideStoreFile=file;
            loadedStore=false;
            dirtyRevision=savedRevision=0;
            standingStore.clear();
            aimingStore.clear();
        }
    }

    private static Path storeFile() {
        Path override=overrideStoreFile;
        if(override!=null)return override;
        var fs=ZomboidFileSystem.instance;
        if(fs==null)return null;
        String cache=fs.getCacheDir();
        if(cache==null||cache.isBlank())return null;
        return Path.of(cache,"GFL2Exilium","grip-calibration.json").toAbsolutePath().normalize();
    }

    private static Map<String,Map<String,Object>> modeStore(boolean aiming) {
        return aiming?aimingStore:standingStore;
    }

    private static KahluaTable copyRow(KahluaTable old) {
        KahluaTable copy=J2SEPlatform.getInstance().newTable();
        boolean edited=false;
        for(int axis=0;axis<COUNT;axis++){
            double v=value(old,axis);
            if(v!=0){
                copy.rawset(axis+1,Double.valueOf(v));
                edited=true;
            }
        }
        return edited?copy:null;
    }

    private static void loadModeJsonLocked(JSONObject section,Map<String,Map<String,Object>> target) {
        if(section==null)return;
        for(String owner:section.keySet()){
            JSONObject weapons=section.optJSONObject(owner);
            if(weapons==null)continue;
            var map=new HashMap<String,Object>();
            for(String weapon:weapons.keySet()){
                Object raw=weapons.get(weapon);
                if(Boolean.FALSE.equals(raw)){
                    map.put(weapon,Boolean.FALSE);
                }else if(raw instanceof JSONArray arr){
                    KahluaTable row=J2SEPlatform.getInstance().newTable();
                    boolean edited=false;
                    int n=Math.min(arr.length(),COUNT);
                    for(int axis=0;axis<n;axis++){
                        double v=arr.optDouble(axis,0);
                        if(Double.isFinite(v)&&Math.abs(v)<=limit(axis)&&v!=0){
                            row.rawset(axis+1,Double.valueOf(v));
                            edited=true;
                        }
                    }
                    if(edited)map.put(weapon,row);
                }
            }
            if(!map.isEmpty())target.put(owner,map);
        }
    }

    private static JSONObject dumpModeJsonLocked(Map<String,Map<String,Object>> source) {
        var section=new JSONObject();
        for(var ownerEntry:source.entrySet()){
            var weapons=new JSONObject();
            for(var weaponEntry:ownerEntry.getValue().entrySet()){
                Object val=weaponEntry.getValue();
                if(Boolean.FALSE.equals(val)){
                    weapons.put(weaponEntry.getKey(),false);
                }else if(val instanceof KahluaTable row){
                    var arr=new JSONArray();
                    boolean edited=false;
                    for(int axis=0;axis<COUNT;axis++){
                        double v=value(row,axis);
                        arr.put(v);
                        if(v!=0)edited=true;
                    }
                    if(edited)weapons.put(weaponEntry.getKey(),arr);
                }
            }
            if(!weapons.isEmpty())section.put(ownerEntry.getKey(),weapons);
        }
        return section;
    }

    private static void ensureLoadedLocked() {
        if(loadedStore)return;
        loadedStore=true;
        Path file=storeFile();
        if(file==null||!Files.isRegularFile(file))return;
        try {
            JSONObject root=new JSONObject(Files.readString(file));
            loadModeJsonLocked(root.optJSONObject("standing"),standingStore);
            loadModeJsonLocked(root.optJSONObject("aiming"),aimingStore);
        }catch(Exception e){
            System.err.println("[GFL2Exilium] Grip calibration load: "+e);
        }
    }

    private static Object persistentRowLocked(Map<String,Map<String,Object>> store,Character character,String id,String weapon) {
        String owner=character==null?id:character.key();
        var ownerMap=store.get(owner);
        if(ownerMap!=null&&ownerMap.containsKey(weapon))return ownerMap.get(weapon);
        if(character!=null){
            var selfMap=store.get(id);
            Object found=selfMap==null?null:selfMap.get(weapon);
            if(found==null)for(String skin:character.skins()){
                if(skin.equals(id))continue;
                var sib=store.get(skin);
                if(sib!=null&&sib.containsKey(weapon)){found=sib.get(weapon);break;}
            }
            if(found instanceof KahluaTable old){
                KahluaTable copy=copyRow(old);
                if(copy!=null){
                    store.computeIfAbsent(owner,unused->new HashMap<>()).put(weapon,copy);
                    return copy;
                }
            }else if(Boolean.FALSE.equals(found)){
                store.computeIfAbsent(owner,unused->new HashMap<>()).put(weapon,Boolean.FALSE);
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static void scheduleSaveLocked() {
        if(storeFile()==null)return;
        dirtyRevision++;
        lastEditNanos=System.nanoTime();
        if(flushThread==null||!flushThread.isAlive()){
            flushThread=new Thread(GripCalibration::backgroundFlushLoop,"GFL2-GripCalibration-Writer");
            flushThread.setDaemon(true);
            flushThread.start();
        }
    }

    private static void backgroundFlushLoop() {
        while(true){
            long waitMs;
            synchronized(storeLock){
                if(savedRevision==dirtyRevision){
                    flushThread=null;
                    return;
                }
                long elapsedMs=(System.nanoTime()-lastEditNanos)/1_000_000L;
                waitMs=250L-elapsedMs;
            }
            if(waitMs>0){
                try{Thread.sleep(Math.min(waitMs,250L));}
                catch(InterruptedException ignored){return;}
                continue;
            }
            flush();
        }
    }

    static void flush() {
        Path file;
        long targetRevision;
        String json;
        synchronized(storeLock){
            if(!loadedStore||savedRevision==dirtyRevision)return;
            file=storeFile();
            if(file==null){savedRevision=dirtyRevision;return;}
            targetRevision=dirtyRevision;
            json=new JSONObject()
                .put("version",1)
                .put("standing",dumpModeJsonLocked(standingStore))
                .put("aiming",dumpModeJsonLocked(aimingStore))
                .toString(2);
        }
        try {
            Path dir=Files.createDirectories(file.getParent());
            Path staged=Files.createTempFile(dir,"grip-calibration-",".tmp");
            try {
                Files.writeString(staged,json);
                try {
                    Files.move(staged,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                }catch(AtomicMoveNotSupportedException ignored){
                    Files.move(staged,file,StandardCopyOption.REPLACE_EXISTING);
                }
            }finally{
                Files.deleteIfExists(staged);
            }
        }catch(Exception e){
            System.err.println("[GFL2Exilium] Grip calibration save: "+e);
        }finally{
            synchronized(storeLock){
                if(savedRevision<targetRevision)savedRevision=targetRevision;
            }
        }
    }

    static KahluaTable currentRow(IsoGameCharacter actor,String id,InventoryItem item,boolean aiming) {
        Character character=character(id);
        String weapon=item.getFullType();
        synchronized(storeLock){
            ensureLoadedLocked();
            var pStore=modeStore(aiming);
            Object cached=persistentRowLocked(pStore,character,id,weapon);
            if(cached instanceof KahluaTable row)return row;
            if(Boolean.FALSE.equals(cached))return null;
            KahluaTable modData=actor==null?null:actor.getModData();
            var mStore=table(modData,aiming?STORE+"Aim":STORE,false);
            Object fromModData=characterRow(mStore,id,item,aiming);
            if(fromModData instanceof KahluaTable legacyRow){
                KahluaTable imported=copyRow(legacyRow);
                if(imported!=null){
                    String owner=character==null?id:character.key();
                    pStore.computeIfAbsent(owner,unused->new HashMap<>()).put(weapon,imported);
                    scheduleSaveLocked();
                    return imported;
                }
                return legacyRow;
            }
            return null;
        }
    }

    private static double value(KahluaTable row,int axis) {
        Object raw=row==null?null:row.rawget(axis+1);
        return raw instanceof Number n&&Double.isFinite(n.doubleValue())&&Math.abs(n.doubleValue())<=limit(axis)?n.doubleValue():0;
    }

    static KahluaTable sliders(IsoGameCharacter actor,String id,String key,boolean aiming) {
        InventoryItem item=heldItem(actor);
        KahluaTable row=item!=null&&key.equals(item.getFullType())?currentRow(actor,id,item,aiming):null;
        KahluaTable values=J2SEPlatform.getInstance().newTable();
        for(int i=0;i<COUNT;i++)values.rawset(i+1,Double.valueOf(value(row,i)));
        return values;
    }

    static boolean set(IsoGameCharacter actor,String id,String key,int axis,double offset,boolean aiming) {
        InventoryItem item=heldItem(actor);
        if(item==null||!item.getFullType().equals(key))return false;
        if(axis!=-1&&(axis<0||axis>=COUNT||!Double.isFinite(offset)||Math.abs(offset)>limit(axis)))return false;
        KahluaTable previous=currentRow(actor,id,item,aiming);
        if(axis!=-1&&value(previous,axis)==offset)return true;
        KahluaTable next=J2SEPlatform.getInstance().newTable();
        boolean edited=false;
        if(axis!=-1)for(int i=0;i<COUNT;i++){
            double v=i==axis?offset:value(previous,i);
            if(v!=0){next.rawset(i+1,Double.valueOf(v));edited=true;}
        }
        Character character=character(id);
        String owner=character==null?id:character.key();
        synchronized(storeLock){
            ensureLoadedLocked();
            modeStore(aiming).computeIfAbsent(owner,unused->new HashMap<>()).put(key,edited?next:Boolean.FALSE);
            scheduleSaveLocked();
        }
        KahluaTable modData=actor==null?null:actor.getModData();
        if(modData!=null){
            KahluaTable store=table(modData,aiming?STORE+"Aim":STORE,true);
            KahluaTable saved=table(store,owner,true);
            if(edited)saved.rawset(key,next);
            else saved.rawset(key,character!=null||!aiming&&legacy(saved,item)!=null?Boolean.FALSE:null);
        }
        return true;
    }

    static Matrix4f handPlacement(AnimationPlayer pose,int side) {
        var matrix=new Matrix4f();
        ModelInstanceRenderData.makeBoneTransform(pose,side==1?"Bip01_Prop1":"Bip01_Prop2",matrix);
        return matrix;
    }

    static GripAdjustment request(IsoGameCharacter actor,String id,Matrix4f weaponMatrix,AnimationPlayer pose) {
        InventoryItem item=heldItem(actor);
        if(pose==null||item==null||actor.getVariableBoolean("isLoading")||actor.getVariableBoolean("isRacking")){
            blends.remove(actor);
            return null;
        }
        String reload=actor.getVariableString("WeaponReloadType");
        if(reload!=null&&!reload.isEmpty()){
            blends.remove(actor);
            return null;
        }
        KahluaTable standing=currentRow(actor,id,item,false);
        KahluaTable aiming=currentRow(actor,id,item,true);
        if(standing==null&&aiming==null){
            blends.remove(actor);
            return null;
        }
        if(weaponMatrix==null)weaponMatrix=handPlacement(pose,heldSide(actor));
        if(standing==aiming){
            blends.remove(actor);
            return adjustment(actor,item,weaponMatrix,standing,standing,0);
        }
        float weight=aimWeight(actor,id,item,actor.isAiming(),System.nanoTime());
        return adjustment(actor,item,weaponMatrix,standing,aiming,weight);
    }

    static float aimWeight(IsoGameCharacter actor,String id,InventoryItem item,boolean aiming,long now) {
        synchronized(blends){
            var blend=blends.get(actor);
            if(blend==null||blend.item.get()!=item||!blend.modelId.equals(id)){
                blend=new AimBlend(id,item,aiming,now);
                blends.put(actor,blend);
            }
            return blend.advance(aiming,now);
        }
    }

    static GripAdjustment previewRequest(IsoGameCharacter actor,InventoryItem item,Matrix4f weaponMatrix,KahluaTable row) {
        return adjustment(actor,item,weaponMatrix,row,row,0);
    }

    private static GripAdjustment adjustment(IsoGameCharacter actor,InventoryItem item,Matrix4f matrix,KahluaTable standing,KahluaTable aiming,float weight) {
        if(weight==0)aiming=standing;
        if(weight==1)standing=aiming;
        if(standing==null&&aiming==null||matrix==null||!matrix.isFinite()||Math.abs(matrix.determinant())<1e-8f)return null;
        float[] offsets=new float[POSITION_AXES],wrists=new float[POSITION_AXES];
        boolean edited=false;
        for(int i=0;i<POSITION_AXES;i++){
            double offset=value(standing,i),angle=value(standing,POSITION_AXES+i);
            if(standing!=aiming){
                offset+=(value(aiming,i)-offset)*weight;
                angle+=(value(aiming,POSITION_AXES+i)-angle)*weight;
            }
            offsets[i]=(float)(offset*.01);
            wrists[i]=(float)Math.toRadians(angle);
            edited|=offsets[i]!=0||wrists[i]!=0;
        }
        float[] itemOffset=new float[3],curl=new float[2];
        for(int i=0;i<itemOffset.length;i++){
            itemOffset[i]=(float)(blend(standing,aiming,ITEM_AXIS+i,weight)*.01);
            edited|=itemOffset[i]!=0;
        }
        for(int i=0;i<curl.length;i++){
            curl[i]=(float)(blend(standing,aiming,CURL_AXIS+i,weight)*.01);
            edited|=curl[i]!=0;
        }
        return edited?new GripAdjustment(matrix,offsets,wrists,item.getType(),heldSide(actor),itemOffset,curl):null;
    }

    private static double blend(KahluaTable standing,KahluaTable aiming,int axis,float weight) {
        double initial=value(standing,axis);
        return initial+(value(aiming,axis)-initial)*weight;
    }
}
