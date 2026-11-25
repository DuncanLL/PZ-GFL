package gfl2.runtime;

import java.util.ArrayList;
import java.util.Random;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Subtle procedural standing idle: gentle breathing sway and periodic look-around glances. */
final class IdleAmbientMotion {
    private static final float DWELL_EMPTY = 1.2f;
    private static final float DWELL_HELD = 1.6f;

    private final int spine, spine1, neck, head, lUpperArm, rUpperArm;
    private final int[] spineSubtree, spine1Subtree, neckSubtree, headSubtree, lArmSubtree, rArmSubtree;
    private final float[] output;
    private final Random rng;

    private final Matrix4f work = new Matrix4f(), rotMat = new Matrix4f();
    private final Quaternionf q = new Quaternionf();
    private final Vector3f pivot = new Vector3f(), offset = new Vector3f();

    private float time, dwell, headBlend, bodyBlend;
    private float nextGlanceIn, glanceElapsed, glanceInDur, glanceHoldDur, glanceOutDur;
    private float targetYaw, targetPitch, targetRoll;
    private int lastSide = 1;
    private boolean glancing;

    IdleAmbientMotion(String[] sourceNames) {
        spine = find(sourceNames, "Bip01_Spine");
        spine1 = find(sourceNames, "Bip01_Spine1");
        neck = find(sourceNames, "Bip01_Neck");
        head = find(sourceNames, "Bip01_Head");
        lUpperArm = find(sourceNames, "Bip01_L_UpperArm");
        rUpperArm = find(sourceNames, "Bip01_R_UpperArm");

        lArmSubtree = collect(sourceNames, "Bip01_L_UpperArm", "Bip01_L_Forearm", "Bip01_L_Hand", "Bip01_L_Finger0", "Bip01_L_Finger1");
        rArmSubtree = collect(sourceNames, "Bip01_R_UpperArm", "Bip01_R_Forearm", "Bip01_R_Hand", "Bip01_R_Finger0", "Bip01_R_Finger1");
        headSubtree = collect(sourceNames, "Bip01_Head");
        neckSubtree = collect(sourceNames, "Bip01_Neck", "Bip01_Head");
        spine1Subtree = collect(sourceNames,
            "Bip01_Spine1", "Bip01_Neck", "Bip01_Head", "Bip01_BackPack",
            "Bip01_L_Clavicle", "Bip01_L_UpperArm", "Bip01_L_Forearm", "Bip01_L_Hand", "Bip01_L_Finger0", "Bip01_L_Finger1",
            "Bip01_R_Clavicle", "Bip01_R_UpperArm", "Bip01_R_Forearm", "Bip01_R_Hand", "Bip01_R_Finger0", "Bip01_R_Finger1");
        spineSubtree = collect(sourceNames,
            "Bip01_Spine", "Bip01_Spine1", "Bip01_Neck", "Bip01_Head", "Bip01_BackPack",
            "Bip01_L_Clavicle", "Bip01_L_UpperArm", "Bip01_L_Forearm", "Bip01_L_Hand", "Bip01_L_Finger0", "Bip01_L_Finger1",
            "Bip01_R_Clavicle", "Bip01_R_UpperArm", "Bip01_R_Forearm", "Bip01_R_Hand", "Bip01_R_Finger0", "Bip01_R_Finger1");

        output = new float[sourceNames.length * 16];
        rng = new Random(System.nanoTime() ^ ((long) System.identityHashCode(this) * 0x9E3779B97F4A7C15L));
        time = rng.nextFloat() * 12f;
        nextGlanceIn = 2.0f + rng.nextFloat() * 3.0f;
    }

    float[] apply(float[] source, float seconds, boolean standingIdle, boolean holding) {
        if (head < 0 || neck < 0) return source;
        if (seconds > 0f) {
            time += seconds;
            if (standingIdle) dwell = Math.min(10f, dwell + seconds);
            else {
                dwell = 0f;
                glancing = false;
                nextGlanceIn = 2.0f + rng.nextFloat() * 2.5f;
            }
            float wantHead = standingIdle && dwell >= (holding ? DWELL_HELD : DWELL_EMPTY) ? 1f : 0f;
            float wantBody = !holding && standingIdle && dwell >= DWELL_EMPTY ? 1f : 0f;
            headBlend = approach(headBlend, wantHead, seconds, wantHead > headBlend ? .55f : .16f);
            bodyBlend = approach(bodyBlend, wantBody, seconds, wantBody > bodyBlend ? .70f : .16f);
            if (headBlend > 1e-4f) stepGlance(seconds);
        }
        if (headBlend <= 1e-4f && bodyBlend <= 1e-4f) return source;

        System.arraycopy(source, 0, output, 0, source.length);

        float swayRoll = 0f, swayYaw = 0f, breathPitch = 0f;
        if (bodyBlend > 1e-4f && spine >= 0 && spine1 >= 0) {
            breathPitch = (float) Math.sin(time * 1.65) * 0.018f * bodyBlend;
            swayRoll = (float) Math.sin(time * 0.88) * 0.024f * bodyBlend;
            swayYaw = (float) Math.sin(time * 0.59 + 1.1) * 0.028f * bodyBlend;

            rotateSubtree(spine, spineSubtree, breathPitch * .40f, swayYaw * .40f, swayRoll * .40f);
            rotateSubtree(spine1, spine1Subtree, breathPitch * .60f, swayYaw * .60f, swayRoll * .60f);

            float armSway = (float) Math.sin(time * 0.88 - 0.65) * 0.020f * bodyBlend;
            float armBreath = (float) Math.sin(time * 1.65 - 0.4) * 0.014f * bodyBlend;
            if (lUpperArm >= 0) rotateSubtree(lUpperArm, lArmSubtree, armBreath, 0f, armSway);
            if (rUpperArm >= 0) rotateSubtree(rUpperArm, rArmSubtree, armBreath, 0f, armSway);
        }

        float glanceWeight = glanceEnvelope() * headBlend;
        float microYaw = (float) Math.sin(time * 0.43 + 0.7) * 0.025f * headBlend;
        float microPitch = (float) Math.sin(time * 0.71 + 2.0) * 0.015f * headBlend;
        float totalYaw = targetYaw * glanceWeight + microYaw - swayYaw * .35f;
        float totalPitch = targetPitch * glanceWeight + microPitch - breathPitch * .35f;
        float totalRoll = targetRoll * glanceWeight - swayRoll * .55f;

        if (Math.abs(totalYaw) + Math.abs(totalPitch) + Math.abs(totalRoll) > 1e-5f) {
            rotateSubtree(neck, neckSubtree, totalPitch * .35f, totalYaw * .35f, totalRoll * .35f);
            rotateSubtree(head, headSubtree, totalPitch * .65f, totalYaw * .65f, totalRoll * .65f);
        }
        return output;
    }

    private void stepGlance(float seconds) {
        if (!glancing) {
            nextGlanceIn -= seconds;
            if (nextGlanceIn <= 0f) {
                glancing = true;
                glanceElapsed = 0f;
                int dir = rng.nextFloat() < .72f ? -lastSide : lastSide;
                lastSide = dir;
                targetYaw = dir * (0.20f + rng.nextFloat() * 0.18f);      // ~11..22 deg
                targetPitch = (rng.nextFloat() - 0.42f) * 0.13f;          // subtle up/down
                targetRoll = -dir * (0.02f + rng.nextFloat() * 0.035f);   // subtle natural tilt
                glanceInDur = 0.55f + rng.nextFloat() * 0.30f;
                glanceHoldDur = 0.90f + rng.nextFloat() * 1.00f;
                glanceOutDur = 0.65f + rng.nextFloat() * 0.35f;
            }
            return;
        }
        glanceElapsed += seconds;
        float total = glanceInDur + glanceHoldDur + glanceOutDur;
        if (glanceElapsed >= total) {
            glancing = false;
            nextGlanceIn = 3.2f + rng.nextFloat() * 4.5f;
        }
    }

    private float glanceEnvelope() {
        if (!glancing) return 0f;
        if (glanceElapsed < glanceInDur) return smooth(glanceElapsed / glanceInDur);
        float holdEnd = glanceInDur + glanceHoldDur;
        if (glanceElapsed < holdEnd) return 1f;
        return 1f - smooth((glanceElapsed - holdEnd) / glanceOutDur);
    }

    private static float smooth(float t) {
        float c = Math.clamp(t, 0f, 1f);
        return c * c * (3f - 2f * c);
    }

    private static float approach(float current, float target, float dt, float tau) {
        if (current == target) return target;
        float next = current + (target - current) * (1f - (float) Math.exp(-dt / tau));
        return Math.abs(next - target) < 1e-4f ? target : next;
    }

    private void rotateSubtree(int pivotBone, int[] subtree, float pitchX, float yawY, float rollZ) {
        if (pivotBone < 0 || subtree.length == 0) return;
        int pAt = pivotBone * 16;
        pivot.set(output[pAt + 12], output[pAt + 13], output[pAt + 14]);
        q.identity().rotateY(yawY).rotateX(pitchX).rotateZ(rollZ).normalize();
        rotMat.rotation(q);
        for (int bone : subtree) {
            int at = bone * 16;
            work.set(output, at);
            offset.set(work.m30() - pivot.x, work.m31() - pivot.y, work.m32() - pivot.z);
            q.transform(offset);
            work.setTranslation(0f, 0f, 0f);
            rotMat.mul(work, work);
            work.setTranslation(pivot.x + offset.x, pivot.y + offset.y, pivot.z + offset.z);
            work.get(output, at);
        }
    }

    private static int find(String[] names, String target) {
        for (int i = 0; i < names.length; i++) if (target.equals(names[i])) return i;
        return -1;
    }

    private static int[] collect(String[] names, String... targets) {
        var list = new ArrayList<Integer>(targets.length);
        for (String name : targets) {
            int idx = find(names, name);
            if (idx >= 0) list.add(idx);
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }
}
