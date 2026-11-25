package gfl2.runtime;

import com.shiroha.mmdskin.NativeFunc;
import gfl2.motion.ModelAppearance;
import gfl2.motion.PmxModel;
import java.util.HashMap;
import java.util.Random;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;

/**
 * Lightweight additive live facial expressions:
 * 1) Natural auto-blink (+ closed eyes while asleep)
 * 2) Lip-sync during GFL2 voice playback or PZ speech bubbles
 * 3) Aiming single-eye wink + focused brow, and melee attack exertion
 */
final class LiveFacialMotion {
    private final int mBlink, mWinkLeft, mBrowFocus, mBrowAngry;
    private final int mMouthA, mMouthI, mMouthU, mMouthO, mMouthE;
    private final int[] ids = new int[9];
    private final float[] deltas = new float[9];
    private final Random rng;

    // Blink state
    private float nextBlinkIn, blinkElapsed;
    private boolean blinking, doubleBlinkPending;
    private float sleepWeight;

    // Lip-sync state
    private volatile float speakingRemaining;
    private float speakWeight, speakPhase, pauseTimer, pauseDur;
    private int currentVowel, nextVowel;
    private float vowelBlend;

    // Aim / Attack state
    private float aimRangedWeight, aimMeleeWeight, attackWeight;

    LiveFacialMotion(NativeFunc api, PmxModel model) {
        int count = Math.toIntExact(api.GetMorphCount(model.handle()));
        var map = new HashMap<String, Integer>(count * 2);
        for (int i = 0; i < count; i++) map.putIfAbsent(api.GetMorphName(model.handle(), i), i);

        mBlink = first(map, "まばたき", "まばたき２");
        mWinkLeft = first(map, "ウィンク", "ウィンク２", "ウィンク３");
        mBrowFocus = first(map, "真面目", "ｷﾘｯ", "下");
        mBrowAngry = first(map, "怒り", "下", "真面目");
        mMouthA = first(map, "あ", "あ２", "あ３");
        mMouthI = first(map, "い", "い２", "い３");
        mMouthU = first(map, "う", "う２");
        mMouthO = first(map, "お", "お２");
        mMouthE = first(map, "え", "え２", "あ");

        rng = new Random(System.nanoTime() ^ ((long) System.identityHashCode(this) * 0x9E3779B97F4A7C15L));
        nextBlinkIn = 1.5f + rng.nextFloat() * 3.0f;
        pauseTimer = 1.4f + rng.nextFloat() * 1.2f;
    }

    void notifySpeaking(float durationSeconds) {
        if (Float.isFinite(durationSeconds) && durationSeconds > 0f) {
            speakingRemaining = Math.max(speakingRemaining, Math.min(durationSeconds, 20f));
        }
    }

    void update(ModelAppearance appearance, IsoGameCharacter actor, float seconds, boolean motionActive, boolean firstPerson) {
        if (motionActive) {
            appearance.clearDynamicMorphs();
            return;
        }
        if (firstPerson || actor == null) return;
        float dt = Math.clamp(seconds, 0f, 0.08f);

        boolean dead = actor.isDead();
        boolean asleep = !dead && actor.isAsleep();
        boolean aiming = !dead && !asleep && actor.isAiming();
        boolean rangedAim = false;
        if (aiming) {
            InventoryItem primary = actor.getPrimaryHandItem();
            rangedAim = primary instanceof HandWeapon w && w.isRanged();
        }
        boolean meleeAim = aiming && !rangedAim;
        boolean attacking = !dead && !asleep && actor.isPerformingAttackAnimation();

        // 1) Sleep & Auto-blink
        sleepWeight = approach(sleepWeight, asleep ? 1f : 0f, dt, 0.25f);
        float blink = dead ? 0f : stepBlink(dt, asleep || rangedAim);
        float eyesClosed = Math.max(sleepWeight, blink);

        // 2) Lip-sync (voice notification or vanilla speech bubble)
        boolean speakingNow = false;
        if (!dead && !asleep) {
            if (speakingRemaining > 0f) {
                speakingRemaining = Math.max(0f, speakingRemaining - dt);
                speakingNow = true;
            }
            if (!speakingNow) {
                try { speakingNow = actor.isSpeaking(); } catch (RuntimeException ignored) {}
            }
        } else {
            speakingRemaining = 0f;
        }
        speakWeight = approach(speakWeight, speakingNow ? 1f : 0f, dt, speakingNow ? 0.07f : 0.10f);
        float aW = 0f, iW = 0f, uW = 0f, oW = 0f;
        if (speakWeight > 1e-3f) {
            float open = stepLipSync(dt) * speakWeight;
            float wCur = open * (1f - vowelBlend);
            float wNxt = open * vowelBlend;
            aW += (currentVowel == 0 ? wCur : 0f) + (nextVowel == 0 ? wNxt : 0f);
            iW += (currentVowel == 1 ? wCur : 0f) + (nextVowel == 1 ? wNxt : 0f);
            uW += (currentVowel == 2 ? wCur : 0f) + (nextVowel == 2 ? wNxt : 0f);
            oW += (currentVowel == 3 ? wCur : 0f) + (nextVowel == 3 ? wNxt : 0f);
        }

        // 3) Aiming & Attacking
        aimRangedWeight = approach(aimRangedWeight, rangedAim ? 1f : 0f, dt, rangedAim ? 0.10f : 0.14f);
        aimMeleeWeight = approach(aimMeleeWeight, meleeAim ? 1f : 0f, dt, meleeAim ? 0.10f : 0.14f);
        attackWeight = approach(attackWeight, attacking ? 1f : 0f, dt, attacking ? 0.05f : 0.14f);

        float winkW = aimRangedWeight * 0.78f * (1f - eyesClosed);
        float focusBrowW = aimRangedWeight * 0.62f;
        float angryBrowW = Math.max(aimMeleeWeight * 0.45f, attackWeight * 0.65f);
        float attackMouthW = attackWeight * 0.30f * (1f - speakWeight);

        int n = 0;
        n = put(n, mBlink, eyesClosed);
        n = put(n, mWinkLeft, winkW);
        n = put(n, mBrowFocus, focusBrowW);
        n = put(n, mBrowAngry, angryBrowW);
        n = put(n, mMouthA, aW);
        n = put(n, mMouthI, iW);
        n = put(n, mMouthU, uW);
        n = put(n, mMouthO, oW);
        n = put(n, mMouthE, attackMouthW);
        appearance.applyDynamicMorphs(ids, deltas, n);
    }

    private float stepBlink(float dt, boolean suppressed) {
        if (suppressed) {
            blinking = false;
            doubleBlinkPending = false;
            return 0f;
        }
        if (!blinking) {
            nextBlinkIn -= dt;
            if (nextBlinkIn <= 0f) {
                blinking = true;
                blinkElapsed = 0f;
                doubleBlinkPending = rng.nextFloat() < 0.18f;
            }
            return 0f;
        }
        blinkElapsed += dt;
        float closeDur = 0.065f, holdDur = 0.030f, openDur = 0.095f;
        float total = closeDur + holdDur + openDur;
        if (blinkElapsed < closeDur) return smooth(blinkElapsed / closeDur);
        if (blinkElapsed < closeDur + holdDur) return 1f;
        if (blinkElapsed < total) return 1f - smooth((blinkElapsed - closeDur - holdDur) / openDur);
        blinking = false;
        if (doubleBlinkPending) {
            doubleBlinkPending = false;
            nextBlinkIn = 0.14f;
        } else {
            nextBlinkIn = 2.6f + rng.nextFloat() * 3.4f;
        }
        return 0f;
    }

    private float stepLipSync(float dt) {
        if (pauseDur > 0f) {
            pauseDur -= dt;
            return 0.06f;
        }
        pauseTimer -= dt;
        if (pauseTimer <= 0f) {
            pauseDur = 0.14f + rng.nextFloat() * 0.16f;
            pauseTimer = 1.2f + rng.nextFloat() * 1.4f;
            return 0.06f;
        }
        float syllableRate = 6.2f;
        speakPhase += dt * syllableRate;
        vowelBlend += dt * 7.5f;
        if (vowelBlend >= 1f) {
            vowelBlend = 0f;
            currentVowel = nextVowel;
            int pick = rng.nextInt(4);
            if (pick == currentVowel) pick = (pick + 1) & 3;
            nextVowel = pick;
        }
        float wave = 0.5f - 0.5f * (float) Math.cos(speakPhase * Math.PI * 2.0);
        return 0.14f + wave * 0.46f;
    }

    private int put(int n, int morphId, float value) {
        if (morphId < 0 || value <= 1e-4f) return n;
        // Merge if two roles resolved to the same fallback morph index
        for (int i = 0; i < n; i++) {
            if (ids[i] == morphId) {
                deltas[i] = Math.max(deltas[i], value);
                return n;
            }
        }
        ids[n] = morphId;
        deltas[n] = value;
        return n + 1;
    }

    private static int first(HashMap<String, Integer> map, String... names) {
        for (String name : names) {
            Integer idx = map.get(name);
            if (idx != null) return idx;
        }
        return -1;
    }

    private static float smooth(float t) {
        float c = Math.clamp(t, 0f, 1f);
        return c * c * (3f - 2f * c);
    }

    private static float approach(float current, float target, float dt, float tau) {
        if (current == target || dt <= 0f) return target;
        float next = current + (target - current) * (1f - (float) Math.exp(-dt / tau));
        return Math.abs(next - target) < 1e-4f ? target : next;
    }
}
