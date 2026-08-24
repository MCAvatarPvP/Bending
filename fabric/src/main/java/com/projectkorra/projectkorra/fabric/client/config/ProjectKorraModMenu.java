package com.projectkorra.projectkorra.fabric.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Makes the ProjectKorra client settings available through Mod Menu. */
public final class ProjectKorraModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ProjectKorraConfigScreen::new;
    }
}
