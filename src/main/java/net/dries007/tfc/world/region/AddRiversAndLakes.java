/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.region;

import java.util.List;
import java.util.function.Function;

import net.dries007.tfc.world.river.RegionRiverContext;
import net.minecraft.util.RandomSource;

import net.dries007.tfc.world.river.River;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public enum AddRiversAndLakes implements RegionTask
{
    INSTANCE;

    @Override
    public void apply(RegionGenerator.Context context)
    {
        final Region region = context.region;
        final RandomSource random = context.random;

        final RegionRiverContext riverGenerator = new RegionRiverContext(region, random);

        final List<RiverEdge> rivers = riverGenerator.getRiverEdges();

        context.region.setRivers(rivers);
        if (!rivers.isEmpty())
        {
            annotateRiverGridScale(region, rivers);
            annotateLakesGridScale(region, riverGenerator.getLakes());
        }
    }

    private void annotateRiverGridScale(Region region, List<RiverEdge> rivers)
    {
        for (RiverEdge edge : rivers) {
            if (edge.width > RiverEdge.MIN_VALLEY_WIDTH)
            {
                annotateRiverGridScale(region, edge);
            }
        }
    }

    private void annotateLakesGridScale(Region region, List<River.Lake> lakes)
    {
        if (!River.Constants.LAKE_GENERATION_ENABLED) {
            return;
        }

        for (River.Lake lake : lakes) {
            int gridX = (int) lake.center().toGridAligned().x();
            int gridZ = (int) lake.center().toGridAligned().y();

            int minOffset = (int) Math.ceil((lake.lakeSize() - 1) / 2.);
            int maxOffset = (int) Math.floor((lake.lakeSize() - 1) / 2.);
            for (int offsetX = -minOffset; offsetX <= maxOffset; offsetX++) {
                for (int offsetZ = -minOffset; offsetZ <= maxOffset; offsetZ++) {
                    if (lake.endorheic()) {
                        placeEndorheicLakeAt(region, gridX + offsetX, gridZ + offsetZ);
                    } else {
                        placeLakeAt(region, gridX + offsetX, gridZ + offsetZ);
                    }
                }
            }
        }
    }

    private void placeEndorheicLakeAt(Region region, int gridX, int gridZ) {
        final Region.Point point = region.at(gridX, gridZ);

        if (point != null) {
            point.setLake();
            point.setEndorheicLake();
        }
    }

    private void placeLakeAt(Region region, int gridX, int gridZ) {
        final Region.Point point = region.at(gridX, gridZ);

        if (point != null) {
            point.setLake();
            point.rainfall += 0.09f * (500f - point.rainfall); // Small, localized rainfall increase around lakes of ~45mm max
        }
    }

    private void annotateRiverGridScale(Region region, RiverEdge edge)
    {
        // todo make size dependent on river width
        final double ux = edge.source().x();
        final double uy = edge.source().y();
        final double vx = edge.drain().x();
        final double vy = edge.drain().y();
        double dx = vx - ux;
        double dy = vy - uy;
        final double mag = Math.sqrt(dx * dx + dy * dy) + 1;
        final double unitX = dx / mag;
        final double unitY = dy / mag;

        double i = 0;
        while (i <= mag)
        {
            int x = (int) (ux + unitX * i);
            int y = (int) (uy + unitY * i);
            setRiver(region.at(x, y));
            setRiver(region.at(x + 1, y));
            setRiver(region.at(x - 1, y));
            setRiver(region.at(x, y + 1));
            setRiver(region.at(x, y - 1));
            i = i + 1;
        }
    }

    private void setRiver(@Nullable Region.Point point)
    {
        if (point != null && (point.land() || point.shore()))
        {
            point.setRiver();
            point.rainfall += 0.09f * (500f - point.rainfall); // Small, localized rainfall increase around river valleys of ~45mm max
        }
    }
}
