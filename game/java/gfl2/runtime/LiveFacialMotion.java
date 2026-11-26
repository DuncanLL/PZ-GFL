package gfl2.runtime;

import com.shiroha.mmdskin.NativeFunc;
import gfl2.motion.ModelAppearance;
import gfl2.motion.PmxModel;
import java.util.HashMap;
import java.util.Random;
import zombie.characters.IsoGameCharacter;
import zombie.characters.Moodles.Moodles;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.scripting.objects.MoodleType;

/**
 * Lightweight additive live facial expressions (100% geometry morphs across all 89 GFL2 models):
 * 1) Natural auto-blink (+ closed eyes while asleep, slower when tired, quicker when panicked)
 * 2) Lip-sync during GFL2 voice playback or PZ speech bubbles, coupled with voice emotion hints
 * 3) Aiming single-eye wink + focused brow, and melee attack exertion
 * 4) Smooth, restrained moodle-driven emotional expressions (calm, well-fed, tired, discomfort, pain + hit wince, panic)
 */
final class LiveFacialMotion {
    static final int MODE_OFF = 0;
    static final int MODE_LIGHT = 1;
    static final int MODE_FULL = 2;

    static final int EMOTION_NONE = 0;
    static final int EMOTION_CALM = 1;
    static final int EMOTION_TIRED = 2;
    static final int EMOTION_DISCOMFORT = 3;
    static final int EMOTION_PAIN = 4;
    static final int EMOTION_PANIC = 5;
    static final int EMOTION_SHOUT = 6;
    static final int EMOTION_KILL = 7;

    private final int mBlink, mWinkLeft, mEyeWide, mPupilSmall;
    private final int mBrowFocus, mBrowAngry, mBrowTroubled, mBrowUp;
    private final int mMouthSmile, mMouthFrown;
    private final int mMouthA, mMouthI, mMouthU, mMouthO, mMouthE;
    private final int[] ids = new int[16];
    private final float[] deltas = new float[16];
    private int lastCount;
    private final Random rng;

    // Blink & subtle living idle phase
    private float nextBlinkIn, blinkElapsed, idleClock;
    private boolean blinking, doubleBlinkPending;
    private float sleepWeight;

    // Lip-sync & voice emotion state
    private volatile float speakingRemaining;
    private volatile int speakingEmotion;
    private float speakWeight, speakPhase, pauseTimer, pauseDur;
    private int currentVowel, nextVowel;
    private float vowelBlend;

    // Aim / Attack state
    private float aimRangedWeight, aimMeleeWeight, attackWeight;

    // Moodle sampling (4 Hz) + smoothed emotional weights
    private float moodSampleIn;
    private float lastHealth = -1f;
    private float targetPain, targetDiscomfort, targetTired, targetPanic, targetWellFed;
    private float painW, discomfortW, tiredW, panicW, wellFedW, calmW;
    private float hitWinceW;

    LiveFacialMotion(NativeFunc api, PmxModel model) {
        int count = Math.toIntExact(api.GetMorphCount(model.handle()));
        var map = new HashMap<String, Integer>(count * 2);
        for (int i = 0; i < count; i++) map.putIfAbsent(api.GetMorphName(model.handle(), i), i);

        // Strictly panel-pure geometry morphs across all 90 GFL2 models (zero panel-4 stickers, zero cross-channel leaks)
        mBlink = first(map, "まばたき", "まばたき２");
        mWinkLeft = first(map, "ウィンク", "ウィンク２", "ウィンク３");
        mEyeWide = first(map, "びっくり");
        mPupilSmall = first(map, "瞳小");
        mBrowFocus = first(map, "下");
        mBrowAngry = first(map, "怒り", "下");
        mBrowTroubled = first(map, "困る", "困り", "悲しい");
        mBrowUp = first(map, "上");
        mMouthSmile = first(map, "口角上げ", "にやり", "微笑み");
        mMouthFrown = first(map, "口角下げ", "∧", "へ");
        mMouthA = first(map, "あ", "あ２", "あ３");
        mMouthI = first(map, "い", "い２", "い３");
        mMouthU = first(map, "う", "う２");
        mMouthO = first(map, "お", "お２");
        mMouthE = first(map, "え", "え２", "あ");

        rng = new Random(System.nanoTime() ^ ((long) System.identityHashCode(this) * 0x9E3779B97F4A7C15L));
        nextBlinkIn = 1.5f + rng.nextFloat() * 3.0f;
        pauseTimer = 1.4f + rng.nextFloat() * 1.2f;
        idleClock = rng.nextFloat() * 6.283f;
    }

    void notifySpeaking(float durationSeconds) {
        notifySpeaking(durationSeconds, null);
    }

    void notifySpeaking(float durationSeconds, String emotionTag) {
        if (Float.isFinite(durationSeconds) && durationSeconds > 0f) {
            speakingRemaining = Math.max(speakingRemaining, Math.min(durationSeconds, 20f));
            speakingEmotion = parseEmotion(emotionTag);
        }
    }

    static int parseEmotion(String tag) {
        if (tag == null || tag.isEmpty()) return EMOTION_NONE;
        return switch (tag) {
            case "idle", "FoodEaten" -> EMOTION_CALM;
            case "Tired", "Endurance" -> EMOTION_TIRED;
            case "Pain", "Injured", "Bleeding" -> EMOTION_PAIN;
            case "Panic", "Stress" -> EMOTION_PANIC;
            case "shout", "sneakShout", "manual" -> EMOTION_SHOUT;
            case "kill" -> EMOTION_KILL;
            case "Hungry", "Thirst", "Sick", "Bored", "Unhappy", "Wet", "HasACold",
                 "HeavyLoad", "Drunk", "Hyperthermia", "Hypothermia", "Windchill",
                 "Uncomfortable", "NoxiousSmell", "CantSprint" -> EMOTION_DISCOMFORT;
            default -> EMOTION_NONE;
        };
    }

    void update(ModelAppearance appearance, IsoGameCharacter actor, float seconds, boolean motionActive, boolean firstPerson) {
        update(appearance, actor, seconds, motionActive, firstPerson, MODE_FULL);
    }

    void update(ModelAppearance appearance, IsoGameCharacter actor, float seconds, boolean motionActive, boolean firstPerson, int mode) {
        if (motionActive || mode <= MODE_OFF) {
            appearance.clearDynamicMorphs();
            return;
        }
        if (firstPerson || actor == null) return;
        float dt = Math.clamp(seconds, 0f, 0.08f);
        boolean lightOnly = (mode == MODE_LIGHT);

        boolean dead = actor.isDead();
        boolean asleep = !dead && actor.isAsleep();
        boolean aiming = !lightOnly && !dead && !asleep && actor.isAiming();
        boolean rangedAim = false;
        if (aiming) {
            InventoryItem primary = actor.getPrimaryHandItem();
            rangedAim = primary instanceof HandWeapon w && w.isRanged();
        }
        boolean meleeAim = aiming && !rangedAim;
        boolean attacking = !lightOnly && !dead && !asleep && actor.isPerformingAttackAnimation();

        // Sample PZ moodles & health at 4 Hz in full mode (near-zero overhead)
        boolean hitNow = false;
        if (dead || lightOnly) {
            targetPain = targetDiscomfort = targetTired = targetPanic = targetWellFed = 0f;
            lastHealth = -1f;
        } else {
            moodSampleIn -= dt;
            if (moodSampleIn <= 0f) {
                moodSampleIn = 0.25f;
                hitNow = sampleActorMood(actor);
            }
        }

        boolean speakingExternal = false;
        if (!dead && !asleep && speakingRemaining <= 0f) {
            try { speakingExternal = actor.isSpeaking(); } catch (RuntimeException ignored) {}
        }
        applyStep(appearance, dt, dead, asleep, rangedAim, meleeAim, attacking, speakingExternal, hitNow, lightOnly);
    }

    /** Offline/test entry point driving the exact same production blending & smoothing pipeline. */
    void updateSimulated(ModelAppearance appearance, float dt, boolean dead, boolean asleep,
                         boolean rangedAim, boolean meleeAim, boolean attacking,
                         float pain, float discomfort, float tired, float panic, float wellFed,
                         boolean hitImpulse) {
        updateSimulatedMode(appearance, dt, dead, asleep, rangedAim, meleeAim, attacking,
                pain, discomfort, tired, panic, wellFed, hitImpulse, MODE_FULL);
    }

    void updateSimulatedMode(ModelAppearance appearance, float dt, boolean dead, boolean asleep,
                             boolean rangedAim, boolean meleeAim, boolean attacking,
                             float pain, float discomfort, float tired, float panic, float wellFed,
                             boolean hitImpulse, int mode) {
        if (mode <= MODE_OFF) {
            appearance.clearDynamicMorphs();
            return;
        }
        boolean lightOnly = (mode == MODE_LIGHT);
        if (lightOnly) {
            targetPain = targetDiscomfort = targetTired = targetPanic = targetWellFed = 0f;
            applyStep(appearance, Math.clamp(dt, 0f, 0.08f), dead, asleep, false, false, false, false, false, true);
            return;
        }
        targetPain = Math.clamp(pain, 0f, 1f);
        targetDiscomfort = Math.clamp(discomfort, 0f, 1f);
        targetTired = Math.clamp(tired, 0f, 1f);
        targetPanic = Math.clamp(panic, 0f, 1f);
        targetWellFed = Math.clamp(wellFed, 0f, 1f);
        applyStep(appearance, Math.clamp(dt, 0f, 0.08f), dead, asleep, rangedAim, meleeAim, attacking, false, hitImpulse, false);
    }

    private boolean sampleActorMood(IsoGameCharacter actor) {
        boolean hit = false;
        try {
            float hp = actor.getHealth();
            if (lastHealth >= 0f && hp < lastHealth - 0.005f) hit = true;
            lastHealth = hp;
        } catch (RuntimeException ignored) {}

        Moodles m;
        try { m = actor.getMoodles(); } catch (RuntimeException e) { return hit; }
        if (m == null) return hit;

        int painL = max3(lvl(m, MoodleType.PAIN), lvl(m, MoodleType.INJURED), lvl(m, MoodleType.BLEEDING));
        int tiredL = Math.max(lvl(m, MoodleType.TIRED), lvl(m, MoodleType.ENDURANCE));
        int panicL = lvl(m, MoodleType.PANIC);
        int stressL = lvl(m, MoodleType.STRESS);
        int discL = max3(
            max3(lvl(m, MoodleType.SICK), lvl(m, MoodleType.HUNGRY), lvl(m, MoodleType.THIRST)),
            max3(lvl(m, MoodleType.UNCOMFORTABLE), lvl(m, MoodleType.UNHAPPY), lvl(m, MoodleType.NOXIOUS_SMELL)),
            max3(
                Math.max(lvl(m, MoodleType.HAS_A_COLD), lvl(m, MoodleType.HEAVY_LOAD)),
                Math.max(lvl(m, MoodleType.HYPERTHERMIA), lvl(m, MoodleType.HYPOTHERMIA)),
                max3(lvl(m, MoodleType.WET), lvl(m, MoodleType.DRUNK), lvl(m, MoodleType.BORED))
            )
        );
        int foodL = lvl(m, MoodleType.FOOD_EATEN);

        targetPain = painL * 0.25f;
        targetTired = tiredL * 0.25f;
        targetPanic = Math.max(panicL * 0.25f, stressL * 0.18f);
        targetDiscomfort = discL * 0.25f;
        boolean anyNeg = painL > 0 || tiredL > 0 || panicL > 0 || stressL > 0 || discL > 0;
        targetWellFed = (!anyNeg && foodL > 0) ? Math.min(1f, 0.55f + foodL * 0.15f) : 0f;
        return hit;
    }

    private void applyStep(ModelAppearance appearance, float dt, boolean dead, boolean asleep,
                           boolean rangedAim, boolean meleeAim, boolean attacking,
                           boolean speakingExternal, boolean hitImpulse, boolean lightOnly) {
        idleClock += dt;

        // 1) Smooth mood state (slow organic transitions: 0.70s rise, 1.05s relax)
        float sleepSuppress = (dead || asleep) ? 0f : 1f;
        if (lightOnly) {
            painW = discomfortW = tiredW = panicW = wellFedW = calmW = 0f;
            hitWinceW = 0f;
        } else {
            painW = approach(painW, targetPain * sleepSuppress, dt, targetPain > painW ? 0.55f : 0.95f);
            discomfortW = approach(discomfortW, targetDiscomfort * sleepSuppress, dt, targetDiscomfort > discomfortW ? 0.80f : 1.15f);
            tiredW = approach(tiredW, targetTired * sleepSuppress, dt, targetTired > tiredW ? 0.95f : 1.25f);
            panicW = approach(panicW, targetPanic * sleepSuppress, dt, targetPanic > panicW ? 0.40f : 0.95f);
            wellFedW = approach(wellFedW, targetWellFed * sleepSuppress, dt, targetWellFed > wellFedW ? 0.90f : 1.20f);
            float maxNeg = Math.max(Math.max(painW, discomfortW), Math.max(tiredW, panicW));
            float calmTarget = sleepSuppress * Math.clamp(1f - maxNeg * 2.2f, 0f, 1f);
            calmW = approach(calmW, calmTarget, dt, 1.0f);

            // Hit-wince impulse: fast flinch (0.04s), natural smooth recovery (0.38s)
            if (hitImpulse && !dead && !asleep) {
                hitWinceW = 1f;
            } else {
                hitWinceW = approach(hitWinceW, 0f, dt, 0.36f);
            }
        }

        // 2) Aim / Attack & Sleep
        sleepWeight = approach(sleepWeight, asleep ? 1f : 0f, dt, 0.25f);
        aimRangedWeight = lightOnly ? 0f : approach(aimRangedWeight, rangedAim ? 1f : 0f, dt, rangedAim ? 0.10f : 0.14f);
        aimMeleeWeight = lightOnly ? 0f : approach(aimMeleeWeight, meleeAim ? 1f : 0f, dt, meleeAim ? 0.10f : 0.14f);
        attackWeight = lightOnly ? 0f : approach(attackWeight, attacking ? 1f : 0f, dt, attacking ? 0.05f : 0.14f);

        // 3) Lip-sync + Voice emotion boost (Plan B)
        boolean speakingNow = false;
        if (!dead && !asleep) {
            if (speakingRemaining > 0f) {
                speakingRemaining = Math.max(0f, speakingRemaining - dt);
                speakingNow = true;
            } else if (speakingExternal) {
                speakingNow = true;
                speakingEmotion = EMOTION_NONE;
            }
        } else {
            speakingRemaining = 0f;
            speakingEmotion = EMOTION_NONE;
        }
        speakWeight = approach(speakWeight, speakingNow ? 1f : 0f, dt, speakingNow ? 0.07f : 0.12f);
        if (speakWeight <= 1e-3f && !speakingNow) speakingEmotion = EMOTION_NONE;

        int emo = lightOnly ? EMOTION_NONE : speakingEmotion;
        float spCalm = (emo == EMOTION_CALM) ? speakWeight : 0f;
        float spTired = (emo == EMOTION_TIRED) ? speakWeight : 0f;
        float spDisc = (emo == EMOTION_DISCOMFORT) ? speakWeight : 0f;
        float spPain = (emo == EMOTION_PAIN) ? speakWeight : 0f;
        float spPanic = (emo == EMOTION_PANIC) ? speakWeight : 0f;
        float spShout = (emo == EMOTION_SHOUT) ? speakWeight : 0f;
        float spKill = (emo == EMOTION_KILL) ? speakWeight : 0f;

        // 4) Blink modulated by tired / panic cadence
        float blinkCadence = lightOnly ? 1f : Math.clamp(1f + tiredW * 0.65f - panicW * 0.25f, 0.72f, 1.75f);
        float blink = dead ? 0f : stepBlink(dt, asleep || rangedAim, blinkCadence);

        // 5) Compose the 4 mutually-exclusive facial channels (anatomically clean at every 0..1 transition frame!)
        float notAim = 1f - aimRangedWeight;
        float notSleep = 1f - sleepWeight;
        float active = notSleep * notAim;

        // Eye channel (panel-2 only): まばたき / ウィンク / びっくり are mutually exclusive so eyelids never fight
        float hitClose = hitWinceW * 0.90f * active;
        float painNarrow = (painW > 0.45f ? (painW - 0.45f) * 0.34f : 0f) * active * (1f - panicW * 0.65f);
        float tiredLid = Math.clamp((tiredW * 0.36f + spTired * 0.06f) * active * (1f - panicW * 0.85f), 0f, 0.38f);
        float lidBase = lightOnly ? 0f : Math.max(hitClose, Math.max(tiredLid, painNarrow));
        float eyesClosed = Math.clamp(Math.max(sleepWeight, Math.max(lidBase, blink)), 0f, 1f);
        float winkW = lightOnly ? 0f : aimRangedWeight * 0.78f * (1f - eyesClosed);
        float eyeWideW = lightOnly ? 0f : Math.clamp(
            (panicW * 0.52f + spPanic * 0.14f + spShout * 0.20f) * active * (1f - eyesClosed),
            0f, 0.55f
        );

        // Pupil channel: restrained pupil shrink at moderate-high panic (L2..L4)
        float pupilSmall = lightOnly ? 0f : Math.clamp((panicW > 0.40f ? (panicW - 0.40f) * 0.72f : 0f) * notSleep, 0f, 0.42f);

        // Brow channel (panel-1 only): normalized total additive budget <= 0.88 so brows never kink or clip
        float focusBrowW = lightOnly ? 0f : aimRangedWeight * 0.55f * notSleep;
        float angryBrowW = 0f, troubledBrowW = 0f, browUpW = 0f;
        if (!lightOnly) {
            angryBrowW = Math.clamp(
                Math.max(
                    Math.max(aimMeleeWeight * 0.42f, attackWeight * 0.60f),
                    Math.max(painW * 0.18f * (1f - panicW * 0.75f), Math.max(spShout * 0.26f, spKill * 0.15f))
                ) * notSleep,
                0f, 0.65f
            );
            troubledBrowW = Math.max(
                Math.max(painW * 0.78f, hitWinceW * 0.84f),
                Math.max(discomfortW * 0.58f, Math.max(tiredW * 0.36f, panicW * 0.48f))
            ) + spPain * 0.12f + spDisc * 0.10f;
            troubledBrowW = Math.clamp(troubledBrowW * active * (1f - attackWeight * 0.60f), 0f, 0.84f);

            float surpriseLift = (panicW * 0.26f + spPanic * 0.10f + spShout * 0.16f) * active
                * (1f - hitWinceW * 0.90f) * (1f - painW * 0.70f);
            float warmLift = (wellFedW * 0.18f + spCalm * 0.10f) * active * (1f - Math.max(troubledBrowW, angryBrowW));
            browUpW = Math.clamp(Math.max(surpriseLift, warmLift), 0f, 0.32f);

            float browSum = focusBrowW + angryBrowW + troubledBrowW + browUpW;
            if (browSum > 0.88f) {
                float scale = 0.88f / browSum;
                focusBrowW *= scale;
                angryBrowW *= scale;
                troubledBrowW *= scale;
                browUpW *= scale;
            }
        }

        // Mouth channel (panel-3 only): vowel lip-sync has priority; static smile/frown soften cleanly as mouth opens
        float aW = 0f, iW = 0f, uW = 0f, oW = 0f;
        if (speakWeight > 1e-3f) {
            float openScale = (emo == EMOTION_SHOUT) ? 1.18f : (emo == EMOTION_TIRED ? 0.82f : 1.00f);
            float open = Math.min(0.68f, stepLipSync(dt) * openScale) * speakWeight;
            float wCur = open * (1f - vowelBlend);
            float wNxt = open * vowelBlend;
            aW += (currentVowel == 0 ? wCur : 0f) + (nextVowel == 0 ? wNxt : 0f);
            iW += (currentVowel == 1 ? wCur : 0f) + (nextVowel == 1 ? wNxt : 0f);
            uW += (currentVowel == 2 ? wCur : 0f) + (nextVowel == 2 ? wNxt : 0f);
            oW += (currentVowel == 3 ? wCur : 0f) + (nextVowel == 3 ? wNxt : 0f);
        }
        float attackMouthW = lightOnly ? 0f : attackWeight * 0.26f * (1f - speakWeight);

        float mouthFrownW = 0f, mouthSmileW = 0f;
        if (!lightOnly) {
            float mouthMask = notSleep * (1f - Math.max(speakWeight * 0.82f, attackWeight * 0.80f));
            // Subtle shocked gasp on heavy panic & brief wince gasp on hit impulse (when not speaking)
            aW = Math.max(aW, Math.clamp((panicW > 0.55f ? (panicW - 0.55f) * 0.36f : 0f) * mouthMask, 0f, 0.16f));
            uW = Math.max(uW, Math.clamp(hitWinceW * 0.24f * mouthMask, 0f, 0.24f));

            float openMouth = Math.min(1f, aW + iW + uW + oW + attackMouthW);
            float staticMouthMask = mouthMask * (1f - Math.min(0.75f, openMouth * 1.4f));

            mouthFrownW = Math.max(
                Math.max(painW * 0.60f, hitWinceW * 0.62f),
                Math.max(discomfortW * 0.52f, Math.max(tiredW * 0.28f, panicW * 0.34f))
            ) + spPain * 0.10f + spDisc * 0.08f;
            mouthFrownW = Math.clamp(mouthFrownW * staticMouthMask, 0f, 0.62f);

            float livingBreath = 0.025f * (0.5f + 0.5f * (float) Math.sin(idleClock * 0.7f));
            float rawSmile = calmW * (0.12f + livingBreath) + wellFedW * 0.48f + spCalm * 0.14f + spKill * 0.12f;
            float frownSuppress = Math.clamp(1f - mouthFrownW * 2.5f, 0f, 1f);
            mouthSmileW = Math.clamp(rawSmile * staticMouthMask * frownSuppress, 0f, 0.55f);
        }

        int n = 0;
        n = put(n, mBlink, eyesClosed);
        n = put(n, mWinkLeft, winkW);
        n = put(n, mEyeWide, eyeWideW);
        n = put(n, mPupilSmall, pupilSmall);
        n = put(n, mBrowFocus, focusBrowW);
        n = put(n, mBrowAngry, angryBrowW);
        n = put(n, mBrowTroubled, troubledBrowW);
        n = put(n, mBrowUp, browUpW);
        n = put(n, mMouthSmile, mouthSmileW);
        n = put(n, mMouthFrown, mouthFrownW);
        n = put(n, mMouthA, aW);
        n = put(n, mMouthI, iW);
        n = put(n, mMouthU, uW);
        n = put(n, mMouthO, oW);
        n = put(n, mMouthE, attackMouthW);
        lastCount = n;
        appearance.applyDynamicMorphs(ids, deltas, n);
    }

    private float stepBlink(float dt, boolean suppressed, float cadenceScale) {
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
        float speed = Math.clamp(cadenceScale, 0.85f, 1.35f);
        float closeDur = 0.065f * speed, holdDur = 0.030f * speed, openDur = 0.095f * speed;
        float total = closeDur + holdDur + openDur;
        if (blinkElapsed < closeDur) return smooth(blinkElapsed / closeDur);
        if (blinkElapsed < closeDur + holdDur) return 1f;
        if (blinkElapsed < total) return 1f - smooth((blinkElapsed - closeDur - holdDur) / openDur);
        blinking = false;
        if (doubleBlinkPending) {
            doubleBlinkPending = false;
            nextBlinkIn = 0.14f;
        } else {
            nextBlinkIn = (2.6f + rng.nextFloat() * 3.4f) * cadenceScale;
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
        for (int i = 0; i < n; i++) {
            if (ids[i] == morphId) {
                deltas[i] = Math.max(deltas[i], value);
                return n;
            }
        }
        if (n >= ids.length) return n;
        ids[n] = morphId;
        deltas[n] = value;
        return n + 1;
    }

    private static int lvl(Moodles m, MoodleType type) {
        return type == null ? 0 : Math.max(0, m.getMoodleLevel(type));
    }

    private static int max3(int a, int b, int c) {
        return Math.max(a, Math.max(b, c));
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
