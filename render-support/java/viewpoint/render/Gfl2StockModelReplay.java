package viewpoint.render;

import gfl2.runtime.EfzAimCompatibility;
import viewpoint.platform.IrisPacks;

/** EFZ's near-band replay bypasses the host's normal Native model filter. */
public final class Gfl2StockModelReplay {
    static ModelBatches.Filter filter(ModelDraws draws, ModelBatches.Filter original) {
        if (!EfzAimCompatibility.isReplay(original) || IrisPacks.active() != null) return original;
        return index -> original.take(index) && !Gfl2NativeRegistry.owns(draws, index);
    }

    private Gfl2StockModelReplay() {}
}
