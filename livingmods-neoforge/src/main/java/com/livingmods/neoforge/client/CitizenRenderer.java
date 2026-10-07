package com.livingmods.neoforge.client;

import com.livingmods.neoforge.client.skin.CitizenSkinLibrary;
import com.livingmods.neoforge.entity.CitizenEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class CitizenRenderer extends HumanoidMobRenderer<CitizenEntity, HumanoidModel<CitizenEntity>> {
    public CitizenRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(CitizenEntity entity) {
        return CitizenSkinLibrary.texture(entity.skinKey());
    }

    @Override
    protected void scale(CitizenEntity entity, com.mojang.blaze3d.vertex.PoseStack poseStack, float partialTick) {
        float scale = entity.ageYears() < 16 ? 0.7f : entity.ageYears() >= 60 ? 0.95f : 0.9375f;
        poseStack.scale(scale, scale, scale);
    }
}
