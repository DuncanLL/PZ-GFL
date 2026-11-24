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
    private static final double SQRT6 = 2.449489742783178;

    private static volatile IsoPlayer trackedOwner;
    private static volatile Matrix4f trackedPlacement;
    private static volatile float trackedScale = 12.5f;
    private static volatile boolean holding;
    private static volatile boolean cutNext;
    private static volatile float lastFrame = Float.NaN;

    private VmdCameraController() {}

    public static void recordPlacement(IsoPlayer player, Matrix4f sourcePlacement, float scale) {
        if (player == null || sourcePlacement == null || !Float.isFinite(scale) || scale <= 1e-4f) return;
        trackedOwner = player;
        trackedPlacement = new Matrix4f(sourcePlacement);
        trackedScale = scale;
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
        if (!(character instanceof IsoPlayer player) || !player.isLocalPlayer()) {
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
        Matrix4f source = trackedOwner == player ? trackedPlacement : null;
        float scale = trackedOwner == player ? trackedScale : session.scale;
        if (source == null || !Float.isFinite(scale) || scale <= 1e-4f) {
            source = fallbackPlacement(player);
            scale = session.scale;
        }

        // Convert MMD left-handed camera frame into right-handed PMX-flipped space (Z negated),
        // then transform by sourcePlacement * scale(1 / unit_scale) into Viewpoint world space.
        Matrix3f rot = new Matrix3f()
                .rotateY(-sample.rotY())
                .rotateX(sample.rotX())
                .rotateZ(sample.rotZ());
        Vector3f targetRh = new Vector3f(sample.targetX(), sample.targetY(), -sample.targetZ());
        Vector3f eyeOffset = rot.transform(new Vector3f(0f, 0f, -sample.distance()));
        Vector3f eyeRh = new Vector3f(targetRh).add(eyeOffset);
        Vector3f fwdRh = rot.transform(new Vector3f(0f, 0f, -1f));
        Vector3f upRh = rot.transform(new Vector3f(0f, 1f, 0f));

        Matrix4f rhToVp = new Matrix4f(source).scale(1f / scale);
        Vector4f eyeVp = rhToVp.transform(new Vector4f(eyeRh.x, eyeRh.y, eyeRh.z, 1f));
        Matrix3f basis = new Matrix3f(rhToVp);
        Vector3f fwdVp = basis.transform(new Vector3f(fwdRh));
        Vector3f upVp = basis.transform(new Vector3f(upRh));
        if (fwdVp.lengthSquared() < 1e-8f) {
            releaseIfHeld();
            return;
        }
        fwdVp.normalize();
        // Orthogonalize up against forward
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
        double claimX = -eyeVp.x;
        double claimY = -eyeVp.z;
        double claimZ = eyeVp.y / SQRT6;
        if (!Double.isFinite(claimX) || !Double.isFinite(claimY) || !Double.isFinite(claimZ)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(roll)) {
            releaseIfHeld();
            return;
        }

        float curFrame = session.model.motionFrame();
        boolean jumped = !holding || sample.cut() || Float.isNaN(lastFrame) || Math.abs(curFrame - lastFrame) > 2.5f;
        lastFrame = curFrame;
        cutNext = jumped;
        holding = true;
        ClaimedCamera.hold(claimX, claimY, claimZ, yaw, pitch, roll, sample.fovDeg(), false);
    }

    public static void afterCapture(Frame frame) {
        if (cutNext && frame != null && frame.cameraShot != null) {
            frame.cameraShot.cut = true;
            cutNext = false;
        }
    }

    private static Matrix4f fallbackPlacement(IsoPlayer player) {
        float angle = player.getAnimationPlayer() != null ? player.getAnimationPlayer().getRenderedAngle() : 0f;
        return new Matrix4f()
                .translate(-player.getX(), (float) (player.getZ() * SQRT6), -player.getY())
                .rotateY(-angle);
    }
}
