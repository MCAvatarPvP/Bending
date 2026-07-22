package com.projectkorra.projectkorra.fabric;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.fabric.FabricProjectKorraPlatform;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads;

/** Fabric entrypoint. The platform layer is installed here instead of JavaPlugin.onEnable(). */
public final class ProjectKorraFabricMod implements ModInitializer {
    private FabricProjectKorraPlatform platform;
    private final FabricPKListener gameplay = new FabricPKListener();

    @Override
    public void onInitialize() {
        PredictionPayloads.registerTypes();
        FabricCommands.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            this.platform = new FabricProjectKorraPlatform(server);
            Platform.install(this.platform);
            ProjectKorra.initCommon();
            this.platform.enable();
            this.gameplay.start(server);
        });

        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (this.platform != null) {
                this.platform.tick();
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (this.platform != null) {
                this.gameplay.tick(server);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (this.platform != null) {
                GeneralMethods.stopBending();
                this.gameplay.stop();
                this.platform.disable();
                this.platform = null;
            }
        });
    }
}
