package gfl2.runtime;

import java.util.ArrayList;
import java.util.Random;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Discrete standing idle micro-actions after standing still for a few seconds.
 * - Base standing pose stays 100% untouched vanilla Bob_Idle.
 * - Empty-handed: plays curated baked idle clips (1.0x speed) with smooth ease-in/out.
 * - Holding a weapon: gentle head/neck look-around only (spine/arms untouched for weapon grip).
 */
final class IdleAmbientMotion {
    private static final float DWELL_EMPTY = 4.5f;
    private static final float DWELL_HELD = 3.5f;
    private static final float CLIP_FADE = 0.45f;

    private final IdleClipBank bank;
    private final int[] clipBoneToSource;
    private final Quaternionf[] clipBoneRest;
    private final int rootBone, pelvisBone, neck, head;
    private final int[] neckSubtree, headSubtree;
    private final float[] output;
    private final Random rng;

    private final Matrix4f work = new Matrix4f(), rotMat = new Matrix4f();
    private final Quaternionf q = new Quaternionf(), qA = new Quaternionf(), qB = new Quaternionf(),
            liveRot = new Quaternionf(), targetRot = new Quaternionf();
    private final Vector3f pivot = new Vector3f(), offset = new Vector3f(), scale = new Vector3f();

    private float dwell;
    // Empty-hand baked clip state
    private int activeClip = -1, lastClip = -1;
    private float clipTime, clipCooldown, clipBlend;
    // Held-weapon head glance state
    private float glanceBlend, nextGlanceIn, glanceElapsed, glanceInDur, glanceHoldDur, glanceOutDur;
    private float targetYaw, targetPitch, targetRoll;
    private int lastSide = 1;
    private boolean glancing;

    IdleAmbientMotion(String[] sourceNames, float[] sourceReference) {
        bank = IdleClipBank.get();
        rootBone = find(sourceNames, "Bip01");
        pelvisBone = find(sourceNames, "Bip01_Pelvis");
        neck = find(sourceNames, "Bip01_Neck");
        head = find(sourceNames, "Bip01_Head");
        headSubtree = collect(sourceNames, "Bip01_Head");
        neckSubtree = collect(sourceNames, "Bip01_Neck", "Bip01_Head");

        if (bank != null && bank.boneNames.length > 0 && bank.clips.length > 0) {
            clipBoneToSource = new int[bank.boneNames.length];
            clipBoneRest = new Quaternionf[bank.boneNames.length];
            Matrix4f m = new Matrix4f();
            for (int i = 0; i < bank.boneNames.length; i++) {
                int idx = find(sourceNames, bank.boneNames[i]);
                clipBoneToSource[i] = idx;
                if (idx >= 0) {
                    clipBoneRest[i] = m.set(sourceReference, idx * 16).getUnnormalizedRotation(new Quaternionf()).normalize();
                }
            }
        } else {
            clipBoneToSource = new int[0];
            clipBoneRest = new Quaternionf[0];
        }

        output = new float[sourceNames.length * 16];
        rng = new Random(System.nanoTime() ^ ((long) System.identityHashCode(this) * 0x9E3779B97F4A7C15L));
        clipCooldown = 1.5f + rng.nextFloat() * 3.5f;
        nextGlanceIn = 1.5f + rng.nextFloat() * 2.5f;
    }

    float[] apply(float[] source, float seconds, boolean standingIdle, boolean holding) {
        if (seconds > 0f) {
            if (standingIdle) {
                dwell = Math.min(20f, dwell + seconds);
                if (!holding && bank != null && bank.clips.length > 0) {
                    glancing = false;
                    if (dwell >= DWELL_EMPTY) stepClip(seconds);
                } else if (holding) {
                    activeClip = -1;
                    if (dwell >= DWELL_HELD) stepGlance(seconds);
                }
            } else {
                dwell = 0f;
                activeClip = -1;
                glancing = false;
                clipCooldown = 1.5f + rng.nextFloat() * 3.5f;
                nextGlanceIn = 1.5f + rng.nextFloat() * 2.5f;
            }

            float wantClip = (!holding && standingIdle && activeClip >= 0) ? clipEnvelope(bank.clips[activeClip]) : 0f;
            clipBlend = approach(clipBlend, wantClip, seconds, wantClip > clipBlend ? .12f : .15f);
            if (clipBlend <= 1e-4f && (!standingIdle || holding)) activeClip = -1;

            float wantGlance = (holding && standingIdle && glancing) ? glanceEnvelope() : 0f;
            glanceBlend = approach(glanceBlend, wantGlance, seconds, .14f);
        }

        if (clipBlend > 1e-4f && activeClip >= 0 && bank != null) {
            return applyClip(source, bank.clips[activeClip], clipTime, clipBlend);
        }
        if (glanceBlend > 1e-4f && head >= 0 && neck >= 0) {
            return applyGlance(source, glanceBlend);
        }
        return source;
    }

    private float[] applyClip(float[] source, IdleClipBank.Clip clip, float time, float weight) {
        System.arraycopy(source, 0, output, 0, source.length);
        for (int i = 0; i < clipBoneToSource.length; i++) {
            int src = clipBoneToSource[i];
            if (src < 0 || clipBoneRest[i] == null) continue;
            clip.sample(i, time, qA, qB, q);
            targetRot.set(q).mul(clipBoneRest[i]).normalize();
            int at = src * 16;
            work.set(source, at);
            work.getScale(scale);
            work.getUnnormalizedRotation(liveRot).normalize();
            liveRot.slerp(targetRot, weight).normalize();
            rotMat.rotation(liveRot);
            output[at]      = rotMat.m00() * scale.x; output[at + 1]  = rotMat.m01() * scale.x; output[at + 2]  = rotMat.m02() * scale.x;
            output[at + 4]  = rotMat.m10() * scale.y; output[at + 5]  = rotMat.m11() * scale.y; output[at + 6]  = rotMat.m12() * scale.y;
            output[at + 8]  = rotMat.m20() * scale.z; output[at + 9]  = rotMat.m21() * scale.z; output[at + 10] = rotMat.m22() * scale.z;
        }
        // Keep Bip01 root rotation aligned with Bip01_Pelvis while preserving grounded translation.
        if (rootBone >= 0 && pelvisBone >= 0 && rootBone != pelvisBone) {
            int rAt = rootBone * 16, pAt = pelvisBone * 16;
            System.arraycopy(output, pAt, output, rAt, 12);
        }
        return output;
    }

    private float[] applyGlance(float[] source, float weight) {
        System.arraycopy(source, 0, output, 0, source.length);
        float totalYaw = targetYaw * weight;
        float totalPitch = targetPitch * weight;
        float totalRoll = targetRoll * weight;
        if (Math.abs(totalYaw) + Math.abs(totalPitch) + Math.abs(totalRoll) > 1e-5f) {
            rotateSubtree(neck, neckSubtree, totalPitch * .35f, totalYaw * .35f, totalRoll * .35f);
            rotateSubtree(head, headSubtree, totalPitch * .65f, totalYaw * .65f, totalRoll * .65f);
        }
        return output;
    }

    private void stepClip(float seconds) {
        if (activeClip < 0) {
            clipCooldown -= seconds;
            if (clipCooldown <= 0f) {
                int count = bank.clips.length;
                int pick = count == 1 ? 0 : rng.nextInt(count);
                if (count > 1 && pick == lastClip) pick = (pick + 1 + rng.nextInt(count - 1)) % count;
                activeClip = pick;
                lastClip = pick;
                clipTime = 0f;
            }
            return;
        }
        clipTime += seconds;
        float dur = bank.clips[activeClip].duration();
        if (clipTime >= dur) {
            activeClip = -1;
            clipCooldown = 6.5f + rng.nextFloat() * 6.5f;
        }
    }

    private float clipEnvelope(IdleClipBank.Clip clip) {
        float dur = clip.duration();
        if (dur <= 0f) return 0f;
        float fade = Math.min(CLIP_FADE, dur * .25f);
        if (clipTime < fade) return smooth(clipTime / fade);
        if (clipTime > dur - fade) return smooth((dur - clipTime) / fade);
        return 1f;
    }

    private void stepGlance(float seconds) {
        if (!glancing) {
            nextGlanceIn -= seconds;
            if (nextGlanceIn <= 0f) {
                glancing = true;
                glanceElapsed = 0f;
                int dir = rng.nextFloat() < .72f ? -lastSide : lastSide;
                lastSide = dir;
                targetYaw = dir * (0.22f + rng.nextFloat() * 0.18f);
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
            nextGlanceIn = 4.0f + rng.nextFloat() * 5.0f;
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
