package gfl2.runtime;

import me.zed_0xff.zombie_buddy.Patch;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.iso.IsoCamera;
import zombie.iso.Vector3;
import zombie.scripting.objects.ModelAttachment;

/** Aligns weapon attachment world queries (muzzle flash, ballistics, tracers) with retargeted held meshes. */
@Patch(className="zombie.core.skinnedmodel.model.ModelInstance",methodName="getAttachmentWorldPosition")
public final class HeldAttachmentPatch {
    @Patch.OnExit
    public static void exit(@Patch.This ModelInstance instance,@Patch.AllArguments Object[] args) {
        if(args==null||args.length!=4)return;
        apply(instance,(ModelAttachment)args[0],((Number)args[1]).floatValue(),(Vector3)args[2],(Vector3)args[3]);
    }

    public static boolean apply(ModelInstance instance,ModelAttachment attachment,float angle,Vector3 pos,Vector3 dir) {
        if(instance==null||attachment==null||pos==null)return false;
        IsoGameCharacter actor=instance.character;
        if(actor==null||!Main.hasAppearance(actor))return false;
        ModelSession session=Main.session(actor);
        if(session==null)return false;
        Matrix4f local=session.heldAttachmentLocal(instance,IsoCamera.frameState.frameCount);
        if(local==null)return false;
        return computeWorld(instance,local,attachment,actor.getX(),actor.getY(),actor.getZ(),angle,pos,dir);
    }

    public static boolean computeWorld(ModelInstance instance,Matrix4f heldLocal,ModelAttachment attachment,
            float charX,float charY,float charZ,float angle,Vector3 pos,Vector3 dir) {
        Matrix4f att=instance.getAttachmentMatrix(attachment,new Matrix4f());
        Matrix4f combined=new Matrix4f(heldLocal).mul(att);
        if(!combined.isFinite())return false;
        Vector3f origin=combined.getTranslation(new Vector3f());
        pos.set(origin.x,origin.y,origin.z);
        Model.vectorToWorldCoords(charX,charY,charZ,angle,pos);
        if(dir!=null) {
            Vector3f forward=combined.transformDirection(new Vector3f(0f,0f,1f));
            float lenSq=forward.lengthSquared();
            if(lenSq>1e-8f)forward.mul((float)(1.0/Math.sqrt(lenSq)));
            dir.set(forward.x,forward.y,forward.z);
            Model.vectorToWorldCoords(0f,0f,0f,angle,dir);
        }
        return true;
    }
}
