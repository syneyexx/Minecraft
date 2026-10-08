package com.livingmods.neoforge.client;

import com.livingmods.neoforge.entity.ProjectedHumanoidEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public final class ProjectedHumanoidRenderer
        extends HumanoidMobRenderer<ProjectedHumanoidEntity, HumanoidModel<ProjectedHumanoidEntity>> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");

    public ProjectedHumanoidRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(ProjectedHumanoidEntity entity) {
        return TEXTURE;
    }
}
