package gfl2.runtime;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL33;
import zombie.characters.IsoGameCharacter;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel.AnimatedModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;
import zombie.core.textures.Texture;
import zombie.iso.IsoCamera;

/** Adapts shared, CPU-skinned frames to the stock static character shader. */
public final class VanillaWorld {
    private static final ConcurrentHashMap<ModelSlotRenderData,Draw> QUEUED=new ConcurrentHashMap<>();
    private static final ThreadLocal<ModelSlotRenderData> WORLD=new ThreadLocal<>();
    private static final Field SLOT_READY=readyField();

    private static Field readyField() {
        try {
            Field f=ModelSlotRenderData.class.getDeclaredField("ready");
            f.setAccessible(true);
            return f;
        } catch(ReflectiveOperationException ignored) {
            return null;
        }
    }

    private record Hand(ModelInstance root,Matrix4f correction) {}

    private static final class Draw {
        final ModelSession session;
        final ModelSession.Snapshot snapshot;
        final ModelInstance body;
        final ModelInstanceRenderData bodyData;
        final Matrix4f bodyPlacement;
        final Hand primary,secondary;
        final IdentityHashMap<ModelInstanceRenderData,Matrix4f> heldTransforms=new IdentityHashMap<>();
        boolean painted;

        Draw(ModelSession session,ModelSession.Snapshot snapshot,ModelInstance body,ModelInstanceRenderData bodyData,
                Matrix4f bodyPlacement,Hand primary,Hand secondary) {
            this.session=session;this.snapshot=snapshot;this.body=body;this.bodyData=bodyData;
            this.bodyPlacement=bodyPlacement;this.primary=primary;this.secondary=secondary;
        }

        Hand hand(ModelInstanceRenderData part) {
            if(primary==null&&secondary==null)return null;
            for(AnimatedModelInstanceRenderData node=part;node!=null;node=node.parent) {
                if(primary!=null&&node.modelInstance==primary.root())return primary;
                if(secondary!=null&&node.modelInstance==secondary.root())return secondary;
            }
            // The ready-model fallback can lack a render-data parent for a held child.
            for(ModelInstance node=part.modelInstance;node!=null;node=node.parent) {
                if(primary!=null&&node==primary.root())return primary;
                if(secondary!=null&&node==secondary.root())return secondary;
            }
            return null;
        }
    }

    public static void capture(ModelSlotRenderData slot) {
        QUEUED.remove(slot);
        IsoGameCharacter actor=slot.character;
        if(actor==null||!Main.hasAppearance(actor)||ViewpointMode.active()
                ||slot.animPlayer==null||slot.animPlayer.getSkinningData()==null)return;
        ModelSession session=Main.sessionForCapture(actor);
        if(session==null)return;
        var mainItem=actor.getPrimaryHandItem();
        var offItem=actor.getSecondaryHandItem();
        boolean holding=mainItem!=null||offItem!=null;
        ModelInstance body=slot.modelSlot.model;
        ModelInstanceRenderData bodyData=null,primaryData=null,secondaryData=null;
        for(var data:slot.modelData) {
            if(data.modelInstance==body)bodyData=data;
            if(actor.primaryHandModel!=null&&data.modelInstance==actor.primaryHandModel)primaryData=data;
            if(actor.secondaryHandModel!=null&&data.modelInstance==actor.secondaryHandModel)secondaryData=data;
        }
        Matrix4f bodyXfrm=bodyData==null?new Matrix4f():new Matrix4f(bodyData.xfrm);
        Matrix4f inverseBody=bodyData==null||(primaryData==null&&secondaryData==null)?null:new Matrix4f(bodyData.xfrm).transpose().invert();
        int calibrationSide=GripCalibration.heldSide(actor);
        var heldData=calibrationSide==1?primaryData:calibrationSide==0?secondaryData:null;
        Matrix4f weapon=heldData==null||!heldData.model.isStatic||inverseBody==null?null:
            new Matrix4f(inverseBody).mul(new Matrix4f(heldData.xfrm).transpose());
        long frame=IsoCamera.frameState.frameCount,nanos=System.nanoTime();
        boolean idle=IdleHandPose.eligible(actor,holding);
        try {
            if(!session.failed)session.update(frame,nanos,slot.animPlayer,holding,actor.getX(),actor.getY(),actor.getZ(),false,idle,false,
                GripCalibration.request(actor,session.entry.id(),weapon,slot.animPlayer),false);
        } catch(Throwable failure) { session.fail(failure); }
        ModelSession selected=Main.completeCapture(actor,session);
        if(selected==null||selected.failed)return;
        try {
            if(selected!=session)selected.update(frame,nanos,slot.animPlayer,holding,actor.getX(),actor.getY(),actor.getZ(),false,idle,false,
                GripCalibration.request(actor,selected.entry.id(),weapon,slot.animPlayer),false);
            if(!selected.ready)return;
            ModelSession.Snapshot snapshot=selected.snapshot();
            if(snapshot==null)return;
            Matrix4f targetBody=placement(bodyXfrm,selected.scale,selected.furnitureDrop());
            Hand primary=hand(selected,slot,actor.primaryHandModel,primaryData,1,mainItem==null?"":mainItem.getType(),inverseBody,targetBody);
            Hand secondary=hand(selected,slot,actor.secondaryHandModel,secondaryData,0,offItem==null?"":offItem.getType(),inverseBody,targetBody);
            QUEUED.put(slot,new Draw(selected,snapshot,body,bodyData,targetBody,primary,secondary));
        } catch(Throwable failure) { selected.fail(failure); }
    }

    private static Hand hand(ModelSession session,ModelSlotRenderData slot,ModelInstance root,
            ModelInstanceRenderData data,int side,String key,Matrix4f inverseBody,Matrix4f targetBody) {
        if(root==null)return null;
        Matrix4f correction=null;
        if(data!=null&&data.model.isStatic&&inverseBody!=null&&inverseBody.isFinite()&&session.profile.optBoolean("palm_ready")) {
            Matrix4f original=new Matrix4f(data.xfrm).transpose();
            if(original.isFinite()&&Math.abs(original.determinant())>1e-8f) {
                Matrix4f local=new Matrix4f(inverseBody).mul(original);
                Matrix4f mapped=session.attachment(root,side,key,local,slot.animPlayer);
                Matrix4f candidate=new Matrix4f(targetBody).mul(mapped).mul(original.invert());
                if(candidate.isFinite())correction=candidate;
            }
        }
        return new Hand(root,correction);
    }

    public static void keepReady(ModelSlotRenderData slot) {
        if(SLOT_READY==null||slot==null)return;
        Draw draw=QUEUED.get(slot);
        if(draw==null||draw.session.retired||draw.session.failed)return;
        try { SLOT_READY.setBoolean(slot,true); } catch(IllegalAccessException ignored) {}
    }

    public static void begin(ModelSlotRenderData slot) {
        Draw draw=QUEUED.get(slot);
        if(draw==null)WORLD.remove();
        else {draw.painted=false;WORLD.set(slot);}
    }
    public static void end() { WORLD.remove(); }
    public static void release(ModelSlotRenderData slot) { QUEUED.remove(slot); }

    public static boolean replace(ModelSlotRenderData slot,ModelInstanceRenderData data) {
        Draw draw=QUEUED.get(slot);
        if(draw==null||draw.session.retired||draw.session.failed) {
            // Suppress late outline passes (e.g. ModelOutlines.endFrame after postRender) for active GFL2 characters.
            return WORLD.get()!=slot&&slot!=null&&slot.character!=null&&Main.hasAppearance(slot.character)&&!ViewpointMode.active();
        }
        if(WORLD.get()!=slot)return true;
        Hand hand=draw.hand(data);
        if(hand!=null) {
            if(!draw.painted)paint(slot,draw,draw.bodyData!=null?draw.bodyData:data);
            if(draw.painted&&hand.correction()!=null&&data.model.isStatic) {
                // RenderList keeps the per-slot properties for queued/instanced equipment draws.
                Matrix4f transform=draw.heldTransforms.computeIfAbsent(data,part->
                    new Matrix4f(hand.correction()).mul(new Matrix4f(part.xfrm).transpose()).transpose());
                data.xfrm.set(transform);
                data.properties.SetMatrix4("transform",transform);
            }
            return false;
        }
        if(!draw.painted)return paint(slot,draw,draw.bodyData!=null?draw.bodyData:data);
        return true;
    }

    private static boolean paint(ModelSlotRenderData slot,Draw draw,ModelInstanceRenderData source) {
        ReadyRoomPreview.State saved=new ReadyRoomPreview.State();
        Shader shader=null;
        boolean shaderStarted=false;
        try {
            var mesh=draw.session.plainMesh(draw.snapshot.frame());
            var textures=draw.session.preparePlainMaterials(draw.snapshot.materials());
            mesh.bindGeometry(draw.snapshot.frame());
            shader=ShaderManager.instance.getOrCreateShader("basicEffect",true,false);
            if(shader==null||shader.getID()==0)return false;
            shader.Start();
            shaderStarted=true;
            // Must go through VertexBufferObject so ShaderProgram's cached modelView/projection
            // stays in sync with the GPU uniform for subsequent static world items (e.g. radios at 25%/50% zoom).
            VertexBufferObject.setModelViewProjection(shader);
            shader.startCharacter(slot,source);
            shader.setTransformMatrix(draw.bodyPlacement,false);
            if(slot.modelSlot!=null&&slot.modelSlot.model!=null) {
                shader.setTargetDepth(PerformanceSettings.fboRenderChunk?slot.modelSlot.model.targetDepth:0.5f);
            }
            shader.setDepthBias(0f);
            shader.setScale(slot.finalScale);
            shader.setHueShift(0f);
            shader.setTint(1f,1f,1f);
            shader.setAlpha(slot.alpha);
            if(source.ignoreLighting&&!slot.debugChunkState) {
                shader.setAmbient(slot.ambientR*0.45f,slot.ambientG*0.45f,slot.ambientB*0.45f);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL33.glBindSampler(0,0);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LEQUAL);GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_BLEND);GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_ALPHA_TEST);GL11.glAlphaFunc(GL11.GL_GREATER,0.01f);
            Texture.lastTextureID=-1;
            for(int part=0;part<mesh.materials.length;part++) {
                int material=mesh.materials[part];
                if(draw.snapshot.frame().materials()[material*12+3]<=0)continue;
                shader.setTexture(textures[material],"Texture",0);
                GL11.glDrawElements(GL11.GL_TRIANGLES,mesh.counts[part],GL11.GL_UNSIGNED_INT,(long)mesh.firsts[part]*4);
            }
            draw.painted=true;
            return true;
        } catch(Exception | LinkageError failure) {
            if(draw.snapshot.frame().gpuPose()!=null)draw.session.disableGpu(failure);
            else draw.session.fail(failure);
            return false;
        } finally {
            if(shaderStarted) {
                shader.setScale(1f);
            }
            saved.restore();
            if(shaderStarted) {
                shader.End();
            }
            Texture.lastTextureID=-1;
            SpriteRenderer.ringBuffer.restoreVbos=true;SpriteRenderer.ringBuffer.restoreBoundTextures=true;
            GLStateRenderThread.restore();
        }
    }

    private static Matrix4f placement(Matrix4f xfrm,float scale,float furnitureDrop) {
        // Shader.startCharacter uploads xfrm with transpose=true.
        return new Matrix4f(xfrm).transpose().translate(0,-furnitureDrop,0).scale(1/scale,1/scale,-1/scale);
    }
}
