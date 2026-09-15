/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.data.providers;

import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.network.chat.ComponentSerialization;

import net.dries007.tfc.compat.patchouli.ConfigTextFunction;
import net.dries007.tfc.util.Helpers;

public class BuiltinFieldGuideConfig implements DataProvider
{
    private final PackOutput.PathProvider path;

    public BuiltinFieldGuideConfig(PackOutput output)
    {
        this.path = output.createPathProvider(PackOutput.Target.RESOURCE_PACK, "field_guide");
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache)
    {
        final JsonObject json = new JsonObject();
        ConfigTextFunction.values().forEach((name, value) -> json.add(name, ComponentSerialization.CODEC
            .encodeStart(JsonOps.INSTANCE, value.get())
            .getOrThrow()));
        return DataProvider.saveStable(cache, json, path.json(Helpers.identifier("config_defaults")));
    }

    @Override
    public String getName()
    {
        return "Field Guide Config Defaults";
    }
}
