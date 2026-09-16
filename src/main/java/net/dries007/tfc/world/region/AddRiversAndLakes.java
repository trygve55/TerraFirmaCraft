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
            annotateRiverGridScale(region, random, rivers);
        }
    }

    private @Nonnull Function<River.Vertex, Region.Point> vertex2point(Region region) {
        return vertex -> {
            final int gridX = (int) Math.floor(vertex.x());
            final int gridZ = (int) Math.floor(vertex.y());
            return region.at(gridX, gridZ);
        };
    }

    private void annotateRiverGridScale(Region region, RandomSource random, List<RiverEdge> rivers)
    {
        // Place lakes around the source of rivers.
        for (RiverEdge edge : rivers) {
            if (River.LAKE_GENERATION_ENABLED &&
                (!edge.sourceEdge()
                    && random.nextFloat() <= River.LAKE_GENERATION_AT_SOURCE_CHANCE
                    && edge.width >= River.LAKE_GENERATION_AT_SOURCE_MINIMUM_WIDTH))
            {
                // todo make lakes connect to rivers always
                // Try and place a lake near this source
                placeLakeNear(region, edge, 1, 1);
                placeLakeNear(region, edge, -1, 1);
                placeLakeNear(region, edge, 1, -1);
                placeLakeNear(region, edge, -1, -1);
            }
            else if (edge.width > RiverEdge.MIN_VALLEY_WIDTH)
            {
                annotateRiverGridScale(region, edge);
            }
        }

        // Place lakes around the drain of rivers into endorheic basin.
        for (RiverEdge edge : rivers)
        {
            if (edge.drainEdge() == null)
            {
                Region.Point point = vertex2point(region).apply(edge.drain());
                if (point == null || point.distanceToOcean < 4) {
                    continue;
                }
                // todo make sure it ends in the middle of the lake

                // Try and place a lake near this endorheic basin
                placeLakeNear(region, edge, 1, 1);
                placeLakeNear(region, edge, -1, 1);
                placeLakeNear(region, edge, 1, -1);
                placeLakeNear(region, edge, -1, -1);
            }
        }
    }

    private void annotateRiverGridScale(Region region, RiverEdge edge)
    {
        // todo make size dependent on river width
        final int ux = (int) (edge.source().x());
        final int uy = (int) (edge.source().y());
        final int vx = (int) (edge.drain().x());
        final int vy = (int) (edge.drain().y());
        int dx = vx - ux;
        int dy = vy - uy;
        final double mag = Math.sqrt(dx * dx + dy * dy);
        final double unitX = (double) dx / mag;
        final double unitY = (double) dy / mag;

        double i = 0;
        while (i <= mag)
        {
            int x = (int) (ux + unitX * i);
            int y = (int) (uy + unitY * i);
            setRiver(region.at(x, y));
            setRiver(region.at(x + 1, y));
            setRiver(region.at(x, y + 1));
            setRiver(region.at(x + 1, y + 1));
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

    private void placeLakeNear(Region region, RiverEdge edge, int offsetX, int offsetZ)
    {
        final int gridX = (int) (edge.source().x() + 0.3f * offsetX);
        final int gridZ = (int) (edge.source().y() + 0.3f * offsetZ);

        final Region.Point point = region.at(gridX, gridZ);
        if (point != null && point.land() && point.distanceToOcean >= 2 && point.distanceToEdge >= 2)
        {
            point.setLake();
            point.rainfall += 0.09f * (500f - point.rainfall); // Small, localized rainfall increase around lakes of ~45mm max
        }
    }
}
