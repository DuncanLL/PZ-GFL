package gfl2.runtime;

import gfl2.motion.VmdCamera;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import viewpoint.core.Frame;
import viewpoint.input.ClaimedCamera;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;

/** Drives Viewpoint's official ClaimedCamera from an active local VMD camera track. */
public final class VmdCameraController {
    private static final double SQRT6 = 2.4494895935058594;

    private record PlacementSnapshot(
            IsoPlayer owner, Matrix4f source, float scale,
            float camX, float camY, float camZ) {}

    private static volatile PlacementSnapshot tracked;
    private static volatile boolean holding;
    private static volatile boolean cutNext;
    private static volatile float lastFrame = Float.NaN;

    private VmdCameraController() {}

    public static void recordPlacement(Frame frame, IsoPlayer player, Matrix4f sourcePlacement, float scale) {
        if (frame == null || player == null || sourcePlacement == null || !Float.isFinite(scale) || scale <= 1e-4f) {
            return;
        }
        tracked = new PlacementSnapshot(
                player, new Matrix4f(sourcePlacement), scale,
                frame.camX, frame.camY, frame.camZ);
    }

    public static void releaseIfHeld() {
        if (!holding) return;
        holding = false;
        cutNext = true;
        lastFrame = Float.NaN;
        try {
            ClaimedCamera.release();
        } catch (Throwable ignored) {}
    }

    public static void beforeCapture(Frame frame, IsoGameCharacter character) {
        if (frame == null || !(character instanceof IsoPlayer player) || !player.isLocalPlayer()) {
            releaseIfHeld();
            return;
        }
        ModelSession session = Main.session(player);
        if (session == null || session.failed || session.retired || !ReadyRoomPreview.motionAllowed(player)) {
            releaseIfHeld();
            return;
        }
        VmdCamera.Sample sample = session.model.sampleCamera();
        if (sample == null) {
            releaseIfHeld();
            return;
        }

        PlacementSnapshot snap = tracked;
        Matrix4f source;
        float scale;
        float originCamX, originCamY, originCamZ;
        if (snap != null && snap.owner == player && Float.isFinite(snap.scale) && snap.scale > 1e-4f) {
            source = snap.source;
            scale = snap.scale;
            originCamX = snap.camX;
            originCamY = snap.camY;
            originCamZ = snap.camZ;
        } else {
            scale = session.scale;
            if (!Float.isFinite(scale) || scale <= 1e-4f) {
                releaseIfHeld();
                return;
            }
            source = fallbackPlacement(frame, player);
            originCamX = frame.camX;
            originCamY = frame.camY;
            originCamZ = frame.camZ;
        }

        // Convert MMD left-handed camera frame into right-handed PMX space (+Z in front of character),
        // then apply the exact bodyPlacement transform (source * scale(1, 1, -1) * scale(1 / unit_scale))
        // into Viewpoint camera-relative scene space.
        Matrix3f rot = new Matrix3f()
                .rotateY(-sample.rotY())
                .rotateX(sample.rotX())
                .rotateZ(sample.rotZ());
        Vector3f targetRh = new Vector3f(sample.targetX(), sample.targetY(), -sample.targetZ());
        Vector3f eyeOffset = rot.transform(new Vector3f(0f, 0f, -sample.distance()));
        Vector3f eyeRh = new Vector3f(targetRh).add(eyeOffset);
        Vector3f fwdRh = rot.transform(new Vector3f(0f, 0f, -1f));
        Vector3f upRh = rot.transform(new Vector3f(0f, 1f, 0f));

        Matrix4f rhToVp = new Matrix4f(source).scale(1f, 1f, -1f).scale(1f / scale);
        Vector4f eyeVp = rhToVp.transform(new Vector4f(eyeRh.x, eyeRh.y, eyeRh.z, 1f));
        Matrix3f basis = new Matrix3f(rhToVp);
        Vector3f fwdVp = basis.transform(new Vector3f(fwdRh));
        Vector3f upVp = basis.transform(new Vector3f(upRh));
        if (fwdVp.lengthSquared() < 1e-8f) {
            releaseIfHeld();
            return;
        }
        fwdVp.normalize();
        float dotFu = fwdVp.dot(upVp);
        upVp.fma(-dotFu, fwdVp);
        if (upVp.lengthSquared() < 1e-8f) upVp.set(0f, 1f, 0f);
        else upVp.normalize();

        float pitch = (float) Math.asin(Math.clamp(fwdVp.y, -0.9995f, 0.9995f));
        float yaw = (float) Math.atan2(-fwdVp.z, -fwdVp.x);
        Vector3f right0 = new Vector3f(fwdVp).cross(0f, 1f, 0f);
        float roll = 0f;
        if (right0.lengthSquared() > 1e-8f) {
            right0.normalize();
            Vector3f trueUp0 = new Vector3f(right0).cross(fwdVp);
            float cosP = (float) Math.cos(pitch);
            roll = (float) Math.atan2(upVp.dot(right0) * cosP, upVp.dot(trueUp0));
        }

        // Convert camera-relative Viewpoint scene coordinates back to absolute PZ world coordinates
        // expected by Viewpoint's Camera.at(frame, place, eyeOut, dirOut).
        double claimX = (double) originCamX - eyeVp.x;
        double claimY = (double) originCamY - eyeVp.z;
        double claimZ = (double) originCamZ + eyeVp.y / SQRT6;
        float fovRad = (float) Math.toRadians(sample.fovDeg());
        if (!Double.isFinite(claimX) || !Double.isFinite(claimY) || !Double.isFinite(claimZ)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(roll)
                || !Float.isFinite(fovRad) || fovRad <= 0.05f) {
            releaseIfHeld();
            return;
        }

        float curFrame = session.model.cameraFrame();
        boolean jumped = !holding || sample.cut() || Float.isNaN(lastFrame) || Math.abs(curFrame - lastFrame) > 2.5f;
        lastFrame = curFrame;
        cutNext = jumped;
        holding = true;
        ClaimedCamera.hold(claimX, claimY, claimZ, yaw, pitch, roll, fovRad, false);
    }

    public static void afterCapture(Frame frame) {
        if (cutNext && frame != null && frame.cameraShot != null) {
            frame.cameraShot.cut = true;
            cutNext = false;
        }
    }

    private static Matrix4f fallbackPlacement(Frame frame, IsoPlayer player) {
        float angle = player.getAnimationPlayer() != null
                ? player.getAnimationPlayer().getRenderedAngle()
                : player.getDirectionAngleRadians();
        return new Matrix4f()
                .translation(
                        -(player.getX() - frame.camX),
                        (float) ((player.getZ() - frame.camZ) * SQRT6),
                        -(player.getY() - frame.camY))
                .scale(-1.5f, 1.5f, 1.5f)
                .rotateY(angle + (float) Math.PI);
    }
}
