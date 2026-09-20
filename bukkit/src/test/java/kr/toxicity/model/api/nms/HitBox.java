package kr.toxicity.model.api.nms;

import java.util.UUID;

/** Test-only shape of the optional BetterModel 3.4 API; never packaged in the plugin. */
public interface HitBox {
    Source source();
    UUID uuid();
    Controller mountController();

    interface Source { UUID uuid(); }
    interface Controller { boolean canMount(); }
}
