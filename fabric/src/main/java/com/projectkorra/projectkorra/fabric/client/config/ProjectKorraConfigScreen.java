package com.projectkorra.projectkorra.fabric.client.config;

import com.projectkorra.projectkorra.fabric.client.PredictionClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Small native screen exposed by the optional Mod Menu API integration. */
public final class ProjectKorraConfigScreen extends Screen {
    private static final int TEXT_WHITE = 0xFFFFFF;
    private static final int TEXT_DIM = 0xA0A0A0;

    private final Screen parent;

    public ProjectKorraConfigScreen(final Screen parent) {
        super(Text.translatable("projectkorra.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        final int centerX = width / 2;
        final int toggleY = height / 2 - 10;
        addDrawableChild(ButtonWidget.builder(toggleText(), button -> {
            final boolean enabled = !ClientBendingConfig.isEnabled();
            ClientBendingConfig.setEnabled(enabled);
            PredictionClient.onClientSideBendingSettingChanged(enabled);
            button.setMessage(toggleText());
        }).dimensions(centerX - 100, toggleY, 200, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
                .dimensions(centerX - 100, toggleY + 48, 200, 20).build());
    }

    private static Text toggleText() {
        final String state = ClientBendingConfig.isEnabled() ? "options.on" : "options.off";
        return Text.translatable("projectkorra.config.client_side_bending", Text.translatable(state));
    }

    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 72, TEXT_WHITE);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("projectkorra.config.client_side_bending.description.1"),
                width / 2, height / 2 - 48, TEXT_DIM);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("projectkorra.config.client_side_bending.description.2"),
                width / 2, height / 2 - 36, TEXT_DIM);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
