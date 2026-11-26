package viewpoint.render;

import java.lang.reflect.Field;

/**
 * Third-party compatibility guard for:
 *   Mod: Shoot to Thrill (3D Aiming & Combat Expansion, formerly I SEE THEM)
 *   Workshop ID: 3813032735 (package: iseethem)
 *   Conflict & Root Cause:
 *     1. OpticPip renders a second WorldRenderer pass for Picture-in-Picture (PIP) scopes while
 *        iseethem.OpticPip.drawing == true at scope FBO resolution. Without isolation, GFL2's
 *        Native source renderer destroys/recreates main-screen FBOs every frame and overwrites
 *        temporal histories/previousValues with the scope camera.
 *     2. WorldDepthPass splits ModelPass.gbuffer into two same-frame calls: first masking out
 *        local player draws (pendingPass != null, replay == false), then re-invoking gbuffer for
 *        local draws (replay == true). Without awareness, Gfl2NativeSourceRenderer cached the
 *        masked actor list for the whole frame and threw "Duplicate source GBuffer callback" on
 *        the replay pass, causing the local character to vanish/flicker.
 *   Hook Points:
 *     - Gfl2NativeRegistry.owns(ModelDraws, int)
 *     - Gfl2NativeRuntime.{beforeLighting, shadows, draw}
 *     - Gfl2NativeSourceRenderer.{actors, prepass}
 *   Performance Cost:
 *     - Zero when Shoot to Thrill is absent (static final null checks constant-folded by JIT).
 *     - Negligible (~nanoseconds/frame) static field reads when installed; avoids per-frame FBO
 *       reallocation during PIP scope use.
 *   Removal:
 *     - Delete this class and remove its calls in Gfl2NativeRegistry, Gfl2NativeRuntime, and
 *       Gfl2NativeSourceRenderer; update docs/MOD_COMPATIBILITY.md.
 */
final class Gfl2ShootToThrillCompatibility {
    private static final Field OPTIC_DRAWING = findStaticField("iseethem.OpticPip", "drawing");
    private static final Field DEPTH_PENDING = findStaticField("iseethem.WorldDepthPass", "pendingPass");
    private static final Field DEPTH_REPLAY = findStaticField("iseethem.WorldDepthPass", "replay");

    private static Field findStaticField(String className, String fieldName) {
        try {
            Class<?> type = Class.forName(className, false, Gfl2ShootToThrillCompatibility.class.getClassLoader());
            Field field = type.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    static boolean isScopePipPass() {
        if (OPTIC_DRAWING == null) return false;
        try {
            return OPTIC_DRAWING.getBoolean(null);
        } catch (IllegalAccessException | LinkageError unavailable) {
            return false;
        }
    }

    static boolean isWorldDepthSplitPass() {
        if (DEPTH_PENDING == null) return false;
        try {
            return DEPTH_PENDING.get(null) != null;
        } catch (IllegalAccessException | LinkageError unavailable) {
            return false;
        }
    }

    static boolean isWorldDepthReplay() {
        if (DEPTH_REPLAY == null) return false;
        try {
            return DEPTH_REPLAY.getBoolean(null);
        } catch (IllegalAccessException | LinkageError unavailable) {
            return false;
        }
    }

    private Gfl2ShootToThrillCompatibility() {}
}
