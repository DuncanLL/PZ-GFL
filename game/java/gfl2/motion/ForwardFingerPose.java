package gfl2.motion;

import com.shiroha.mmdskin.NativeFunc;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Allocate bend while preserving the chain's end rotation. */
final class ForwardFingerPose {
    // Natural anatomical cascade from index(0) to pinky(3) layered during empty-hand idle.
    private static final float[] IDLE_MCP_CURL={-.04f,.06f,.16f,.25f};
    private static final float[] IDLE_PIP_CURL={.06f,.18f,.30f,.42f};
    private static final float[] IDLE_DIP_CURL={.14f,.22f,.30f,.36f};
    private static final float[] IDLE_SPLAY={-.035f,-.005f,.015f,.040f};
    private record Bend(int rootOffset,int childOffset,int tipOffset,float fraction,int side,int fingerIndex,Vector3f curlAxis,Vector3f splayAxis) {}
    private record Thumb(int rootOffset,int midOffset,int tipOffset,int side,Vector3f baseAxis,Vector3f flexAxis) {}
    private final int[] destination;
    private final Bend[] bends;
    private final Thumb[] thumbs;
    private final int inputSize;
    private final float[] frame;
    private final Quaternionf total=new Quaternionf(),rest=new Quaternionf(),change=new Quaternionf(),assigned=new Quaternionf(),remainder=new Quaternionf(),tipRot=new Quaternionf();
    ForwardFingerPose(int[] base,int[] parents,int[] roots,int[] tips,float[] fractions,float[] positions,int[] knuckles) {
        this(base,parents,roots,tips,new int[0],fractions,positions,knuckles,new int[0],new int[0],new int[0],new int[0]);
    }
    ForwardFingerPose(int[] base,int[] parents,int[] roots,int[] tips,float[] fractions,float[] positions,int[] knuckles,
            int[] thumbRoots,int[] thumbMids,int[] thumbTips,int[] thumbSides) {
        this(base,parents,roots,tips,new int[0],fractions,positions,knuckles,thumbRoots,thumbMids,thumbTips,thumbSides);
    }
    ForwardFingerPose(int[] base,int[] parents,int[] roots,int[] tips,int[] ends,float[] fractions,float[] positions,int[] knuckles,
            int[] thumbRoots,int[] thumbMids,int[] thumbTips,int[] thumbSides) {
        if(roots.length!=tips.length||tips.length!=fractions.length)throw new IllegalArgumentException("Finger description dimensions");
        if(ends.length!=0&&ends.length!=tips.length)throw new IllegalArgumentException("Finger tip description dimensions");
        if(thumbRoots.length!=thumbMids.length||thumbMids.length!=thumbTips.length||thumbTips.length!=thumbSides.length)
            throw new IllegalArgumentException("Thumb description dimensions");
        var slots=new HashMap<Integer,Integer>();var occupied=new HashSet<Integer>();
        for(int i=0;i<base.length;i++){slots.put(base[i],i*10);occupied.add(base[i]);}
        int endCount=0;
        for(int end:ends)if(end>=0)endCount++;
        destination=Arrays.copyOf(base,base.length+tips.length+endCount+thumbRoots.length*2);
        inputSize=base.length*10;frame=new float[destination.length*10];
        bends=new Bend[tips.length];
        Vector3f[] palmInward=new Vector3f[2],palmAcross=new Vector3f[2],palmForward=new Vector3f[2];
        int endCursor=base.length+tips.length;
        for(int i=0;i<tips.length;i++) {
            Integer at=slots.get(roots[i]);float f=fractions[i];int tip=tips[i];
            if(at==null||tip<0||tip>=parents.length||parents[tip]!=roots[i]||!occupied.add(tip)||!Float.isFinite(f)||f<=0||f>=1)throw new IllegalArgumentException("Finger description is not a two-joint chain");
            int end=i<ends.length?ends[i]:-1,endOffset=-1;
            if(end>=0){
                if(end>=parents.length||parents[end]!=tip||!occupied.add(end))throw new IllegalArgumentException("Finger description is not a three-joint chain");
                destination[endCursor]=end;
                endOffset=endCursor*10;
                endCursor++;
            }
            int knuckle=0;
            while(knuckle<knuckles.length&&knuckles[knuckle]!=roots[i])knuckle++;
            int side=knuckle<4?0:1;
            int fingerIndex=knuckle<knuckles.length?knuckle%4:-1;
            Vector3f curlAxis=null,splayAxis=null;
            if(knuckle<knuckles.length){
                Vector3f direction=point(positions,tip).sub(point(positions,roots[i]));
                Vector3f across=point(positions,knuckles[side*4+3]).sub(point(positions,knuckles[side*4]));
                Vector3f rawNormal=new Vector3f(direction).cross(across);
                if(rawNormal.lengthSquared()>1e-12f)splayAxis=new Vector3f(rawNormal).normalize();
                Vector3f inward=new Vector3f(rawNormal);
                if(inward.y>0)inward.negate();
                if(palmInward[side]==null&&direction.lengthSquared()>1e-12f&&inward.lengthSquared()>1e-12f&&across.lengthSquared()>1e-12f){
                    palmForward[side]=new Vector3f(direction).normalize();
                    palmInward[side]=new Vector3f(inward).normalize();
                    palmAcross[side]=new Vector3f(across).normalize();
                }
                Vector3f axis=direction.cross(inward);
                if(axis.lengthSquared()>1e-12f)curlAxis=axis.normalize();
            }
            destination[base.length+i]=tip;bends[i]=new Bend(at,inputSize+i*10,endOffset,f,side,fingerIndex,curlAxis,splayAxis);
        }
        thumbs=new Thumb[thumbRoots.length];
        int thumbDst=endCursor;
        for(int i=0;i<thumbRoots.length;i++){
            Integer at=slots.get(thumbRoots[i]);
            int mid=thumbMids[i],tip=thumbTips[i],side=thumbSides[i];
            if(at==null||side<0||side>1||mid<0||mid>=parents.length||tip<0||tip>=parents.length
                ||parents[mid]!=thumbRoots[i]||parents[tip]!=mid||!occupied.add(mid)||!occupied.add(tip))
                throw new IllegalArgumentException("Thumb description is not a three-joint chain");
            Vector3f baseAxis=null,flexAxis=null;
            if(palmInward[side]!=null&&palmAcross[side]!=null&&palmForward[side]!=null){
                Vector3f dir0=point(positions,mid).sub(point(positions,thumbRoots[i]));
                Vector3f dir1=point(positions,tip).sub(point(positions,mid));
                if(dir0.lengthSquared()>1e-12f&&dir1.lengthSquared()>1e-12f){
                    dir0.normalize();dir1.normalize();
                    Vector3f opposeTarget=new Vector3f(palmInward[side]).mul(.45f)
                        .fma(.70f,palmAcross[side]).fma(.55f,palmForward[side]);
                    Vector3f flexTarget=new Vector3f(palmInward[side]).mul(.25f)
                        .fma(.75f,palmAcross[side]).fma(.65f,palmForward[side]);
                    Vector3f a0=new Vector3f(dir0).cross(opposeTarget);
                    Vector3f a1=new Vector3f(dir1).cross(flexTarget);
                    if(a0.lengthSquared()>1e-12f)baseAxis=a0.normalize();
                    if(a1.lengthSquared()>1e-12f)flexAxis=a1.normalize();
                }
            }
            int dst=thumbDst+i*2;
            destination[dst]=mid;destination[dst+1]=tip;
            thumbs[i]=new Thumb(at,dst*10,(dst+1)*10,side,baseAxis,flexAxis);
        }
    }
    private static Vector3f point(float[] positions,int bone) {
        return new Vector3f(positions[bone*3],positions[bone*3+1],positions[bone*3+2]);
    }
    private static void rotation(float[] pose,int at,Quaternionf q){q.set(pose[at+3],pose[at+4],-pose[at+5],-pose[at+6]).normalize();}
    private void store(int at,Quaternionf q){frame[at+3]=q.x;frame[at+4]=q.y;frame[at+5]=-q.z;frame[at+6]=-q.w;}
    void publish(NativeFunc api,long model,float[] requested,float[] reference,float strength,float leftCurl,float rightCurl) {
        if(requested.length!=inputSize||reference.length!=inputSize||!Float.isFinite(strength)||strength<0||strength>1)throw new IllegalArgumentException("Finger pose contract");
        System.arraycopy(requested,0,frame,0,inputSize);
        boolean held=leftCurl!=0||rightCurl!=0;
        for(Bend bend:bends) {
            rotation(requested,bend.rootOffset,total);rotation(reference,bend.rootOffset,rest);
            tipRot.identity();
            if(held){
                float curl=bend.curlAxis==null?0:bend.side==0?leftCurl:rightCurl;
                assigned.set(total);
                remainder.identity();
                if(curl<0)assigned.slerp(rest,-curl).normalize();
                else if(curl>0){
                    var axis=bend.curlAxis;
                    change.rotationAxis(.28f*curl,axis.x,axis.y,axis.z);
                    assigned.mul(change).normalize();
                    remainder.rotationAxis(1.05f*curl,axis.x,axis.y,axis.z);
                }
                storeBend(bend);
                continue;
            }
            change.set(rest).conjugate();total.mul(change,change).normalize();
            if(change.w<0)change.set(-change.x,-change.y,-change.z,-change.w);
            double sine=Math.sqrt((double)change.x*change.x+(double)change.y*change.y+(double)change.z*change.z);
            if(sine<1e-8)assigned.set(rest);
            else assigned.rotationAxis((float)(2*Math.atan2(sine,change.w)*bend.fraction),(float)(change.x/sine),(float)(change.y/sine),(float)(change.z/sine)).mul(rest);
            total.slerp(assigned,strength,assigned).normalize();assigned.difference(total,remainder).normalize();
            if(strength>0&&bend.fingerIndex>=0&&bend.curlAxis!=null){
                int k=bend.fingerIndex;
                var axis=bend.curlAxis;
                float mcp=IDLE_MCP_CURL[k]*strength;
                if(mcp!=0){
                    change.rotationAxis(mcp,axis.x,axis.y,axis.z);
                    assigned.mul(change).normalize();
                }
                if(bend.splayAxis!=null){
                    float sp=IDLE_SPLAY[k]*strength;
                    if(sp!=0){
                        change.rotationAxis(sp,bend.splayAxis.x,bend.splayAxis.y,bend.splayAxis.z);
                        assigned.mul(change).normalize();
                    }
                }
                float pip=IDLE_PIP_CURL[k]*strength;
                if(pip!=0){
                    change.rotationAxis(pip,axis.x,axis.y,axis.z);
                    remainder.mul(change).normalize();
                }
                tipRot.rotationAxis(IDLE_DIP_CURL[k]*strength,axis.x,axis.y,axis.z);
            }
            storeBend(bend);
        }
        for(Thumb thumb:thumbs) {
            rotation(requested,thumb.rootOffset,total);rotation(reference,thumb.rootOffset,rest);
            assigned.set(total);
            remainder.identity();
            change.identity();
            if(held){
                float curl=thumb.side==0?leftCurl:rightCurl;
                if(curl<0){
                    assigned.slerp(rest,-curl).normalize();
                    if(thumb.baseAxis!=null){
                        change.rotationAxis(.22f*curl,thumb.baseAxis.x,thumb.baseAxis.y,thumb.baseAxis.z);
                        assigned.mul(change).normalize();
                    }
                    change.identity();
                }else if(curl>0){
                    if(thumb.baseAxis!=null){
                        change.rotationAxis(.58f*curl,thumb.baseAxis.x,thumb.baseAxis.y,thumb.baseAxis.z);
                        assigned.mul(change).normalize();
                    }
                    if(thumb.flexAxis!=null){
                        remainder.rotationAxis(.52f*curl,thumb.flexAxis.x,thumb.flexAxis.y,thumb.flexAxis.z);
                        change.rotationAxis(.68f*curl,thumb.flexAxis.x,thumb.flexAxis.y,thumb.flexAxis.z);
                    }else{
                        change.identity();
                    }
                }
            } else if(strength>0){
                if(thumb.baseAxis!=null){
                    change.rotationAxis(.62f*strength,thumb.baseAxis.x,thumb.baseAxis.y,thumb.baseAxis.z);
                    assigned.mul(change).normalize();
                }
                if(thumb.flexAxis!=null){
                    remainder.rotationAxis(.32f*strength,thumb.flexAxis.x,thumb.flexAxis.y,thumb.flexAxis.z);
                    change.rotationAxis(.38f*strength,thumb.flexAxis.x,thumb.flexAxis.y,thumb.flexAxis.z);
                }else{
                    change.identity();
                }
            }
            storeThumb(thumb,assigned,remainder,change);
        }
        if(api.SetBoneAdditiveTransformBatch(model,destination,frame)!=destination.length)throw new IllegalStateException("External finger pose was not accepted atomically");
    }
    private void storeBend(Bend bend) {
        store(bend.rootOffset,assigned);
        int at=bend.childOffset;
        store(at,remainder);
        frame[at+7]=frame[at+8]=frame[at+9]=1;
        if(bend.tipOffset>=0){
            int tip=bend.tipOffset;
            store(tip,tipRot);
            frame[tip+7]=frame[tip+8]=frame[tip+9]=1;
        }
    }
    private void storeThumb(Thumb thumb,Quaternionf baseQ,Quaternionf midQ,Quaternionf tipQ) {
        store(thumb.rootOffset,baseQ);
        int m=thumb.midOffset,t=thumb.tipOffset;
        store(m,midQ);
        frame[m+7]=frame[m+8]=frame[m+9]=1;
        store(t,tipQ);
        frame[t+7]=frame[t+8]=frame[t+9]=1;
    }
}
