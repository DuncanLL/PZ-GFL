package gfl2.runtime;

import me.zed_0xff.zombie_buddy.Patch;
import viewpoint.core.Frame;
import zombie.characters.IsoGameCharacter;

@Patch(className = "viewpoint.camera.ThirdPerson", methodName = "capture")
public final class VmdCameraPatch {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(0) Frame frame, @Patch.Argument(2) IsoGameCharacter character) {
        VmdCameraController.beforeCapture(frame, character);
    }

    @Patch.OnExit
    public static void exit(@Patch.Argument(0) Frame frame) {
        VmdCameraController.afterCapture(frame);
    }
}
