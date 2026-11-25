package gfl2.runtime;

import java.util.ArrayList;
import java.util.Random;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Discrete look-around idle glances after standing still; base standing pose stays untouched. */
final class IdleAmbientMotion {
    private static final float DWELL_SECONDS = 3.0f;

    private final int neck, head;
    private final int[] neckSubtree, headSubtree;
    private final float[] output;
    private final Random rng;

    private final Matrix4f work = new Matrix4f(), rotMat = new Matrix4f();
    private final Quaternionf q = new Quaternionf();
    private final Vector3f pivot = new Vector3f(), offset = new Vector3f();

    private float dwell, blend;
    private float nextGlanceIn, glanceElapsed, glanceInDur, glanceHoldDur, glanceOutDur;
    private float targetYaw, targetPitch, targetRoll;
    private int lastSide = 1;
    private boolean glancing;

    IdleAmbientMotion(String[] sourceNames) {
        neck = find(sourceNames, "Bip01_Neck");
        head = find(sourceNames, "Bip01_Head");
        headSubtree = collect(sourceNames, "Bip01_Head");
        neckSubtree = collect(sourceNames, "Bip01_Neck", "Bip01_Head");
        output = new float[sourceNames.length * 16];
        rng = new Random(System.nanoTime() ^ ((long) System.identityHashCode(this) * 0x9E3779B97F4A7C15L));
        nextGlanceIn = 1.5f + rng.nextFloat() * 2.5f;
    }

    float[] apply(float[] source, float seconds, boolean standingIdle, boolean holding) {
        if (head < 0 || neck < 0) return source;
        if (seconds > 0f) {
            if (standingIdle) {
                dwell = Math.min(10f, dwell + seconds);
                if (dwell >= DWELL_SECONDS) stepGlance(seconds);
            } else {
                dwell = 0f;
                glancing = false;
                nextGlanceIn = 1.5f + rng.nextFloat() * 2.5f;
            }
            float want = standingIdle && glancing ? glanceEnvelope() : 0f;
            blend = approach(blend, want, seconds, want > blend ? .14f : .14f);
        }
        if (blend <= 1e-4f) return source;

        System.arraycopy(source, 0, output, 0, source.length);
        float totalYaw = targetYaw * blend;
        float totalPitch = targetPitch * blend;
        float totalRoll = targetRoll * blend;
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
                targetYaw = dir * (0.22f + rng.nextFloat() * 0.18f);      // ~13..23 deg
                targetPitch = (rng.nextFloat() - 0.42f) * 0.12f;
                targetRoll = -dir * (0.02f + rng.nextFloat() * 0.035f);
                glanceInDur = 0.55f + rng.nextFloat() * 0.30f;
                glanceHoldDur = 0.90f + rng.nextFloat() * 1.10f;
                glanceOutDur = 0.65f + rng.nextFloat() * 0.35f;
            }
            return;
        }
        glanceElapsed += seconds;
        float total = glanceInDur + glanceHoldDur + glanceOutDur;
        if (glanceElapsed >= total) {
            glancing = false;
            nextGlanceIn = 3.5f + rng.nextFloat() * 4.5f;
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
