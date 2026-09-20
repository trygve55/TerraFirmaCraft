/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river;

import net.dries007.tfc.world.region.Region;
import net.dries007.tfc.world.region.RiverEdge;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class RegionRiverContext {
    private final List<River.Builder> builders = new ArrayList<>();
    private final List<River.Lake> lakes = new ArrayList<>();

    private final Region region;
    private final RandomSource random;

    public RegionRiverContext(Region region, RandomSource random) {
        this.region = region;
        this.random = random;

        createInitialBuilders();
        buildRivers();
    }

    private void createInitialBuilders() {
        for (final var point : randomOrderRegionPoints()) {
            if (point.land()
                && point.distanceToOcean > 1
                && point.rainfall > River.Constants.MIN_RAINFALL_TO_CONTRIBUTE_TO_RIVERS_MM
                && isSurroundedByLand(point)) {
                final XoroshiroRandomSource rng = new XoroshiroRandomSource(random.nextLong());
                builders.add(new River.Builder(this, rng, point));
            }
        }
    }

    private void buildRivers() {
        final List<River.Builder> remainingBuildersNotReachingTheSea = new ArrayList<>();
        final Collection<River.Builder> remainingBuildersNotConnectedToAnything = new ArrayList<>();

        for (River.Builder builder : builders) {
            if (!builder.buildInitialBranch()) {
                remainingBuildersNotReachingTheSea.add(builder);
            }
        }

        for (River.Builder builder : remainingBuildersNotReachingTheSea) {
            if (!builder.haveDrainageBasin() && !builder.buildAllowInlandDrain()) {
                remainingBuildersNotConnectedToAnything.add(builder);
            }
        }

        for (River.Builder builder : remainingBuildersNotConnectedToAnything) {
            if (!builder.haveDrainageBasin() && !builder.addRainfallToClosestRiverOrSea(this)) {
                builder.drawDebugEdge();
            }
        }

        for (River.Builder builder : builders) {
            builder.attemptAddLakeAtSources();
        }
    }

    private Iterable<Region.Point> randomOrderRegionPoints() {
        List<Region.Point> list = StreamSupport.stream(region.points().spliterator(), false)
            .collect(Collectors.toList());

        Collections.shuffle(list, random::nextLong);

        return list;
    }

    private boolean isSurroundedByLand(Region.Point point) {
        for (int dirX = -1; dirX <= 1; dirX++) {
            for (int dirZ = -1; dirZ <= 1; dirZ++) {
                if (dirX == 0 && dirZ == 0) continue;

                final @Nullable Region.Point dirPoint = region.atOffset(point.index, dirX, dirZ);
                if (dirPoint != null && !dirPoint.land()) {
                    return false;
                }
            }
        }

        return true;
    }

    @Nonnull
    public List<RiverEdge> getRiverEdges() {
        List<RiverEdge> riverEdges = builders.stream().flatMap(builder -> builder.edges.stream().map(e -> new RiverEdge(e, random))).toList();

        // todo make this use the internal structure instead
        // Build a map of each source vertex to the downstream edge.
        // Use this to populate the source -> drain linked list, so we can traverse down each branch
        final Map<River.Vertex, RiverEdge> sourceVertexToEdge = new HashMap<>();
        for (RiverEdge edge : riverEdges)
        {
            sourceVertexToEdge.put(edge.source(), edge);
        }

        for (RiverEdge edge : riverEdges)
        {
            edge.linkToDrain(sourceVertexToEdge.get(edge.drain()));
        }

        return riverEdges;
    }

    @Nonnull
    public List<River.Lake> getLakes() {
        return lakes;
    }

    void addLake(River.Lake lake) {
        lakes.add(lake);
    }

    @Nullable
    Region.Point vertex2point(River.Vertex vertex) {
        final int gridX = (int) Math.round(vertex.x() - 0.5);
        final int gridZ = (int) Math.round(vertex.y() - 0.5);
        return region.at(gridX, gridZ);
    }

    @Nullable
    River.Edge intersectClosestOther(River.Edge edge) {
        double maxDistance = 6f;
        double maxDistanceSquared = maxDistance * maxDistance;

        double closestDistance = Double.MAX_VALUE;
        River.Edge closestEdge = null;
        for (River.Builder river : builders) {
            if (river == edge.river) {
                continue;
            }

            for (River.Edge e : river.edges) {
                if (RiverHelpers.distanceVertexFastSquared(e.drain, edge.drain) > maxDistanceSquared + 4 && RiverHelpers.distanceVertexFastSquared(e.source, edge.drain) > maxDistanceSquared + 4) {
                    continue;
                }

                double distanceSquared = RiverHelpers.distanceSq(e, edge.drain);
                if (e.drain != edge.source && ((distanceSquared < closestDistance && distanceSquared < maxDistanceSquared) || RiverHelpers.intersect(e.source, e.drain, edge.source, edge.drain))) {
                    closestDistance = distanceSquared;
                    closestEdge = e;
                }
            }
        }
        return closestEdge;
    }

    List<River.Edge> allEdgesInRange(River.Vertex vertex, double maxDistance) {
        List<River.Edge> allEdgesInRange = new ArrayList<>();

        double maxDistanceSquared = maxDistance * maxDistance;

        for (River.Builder river : builders) {

            for (River.Edge e : river.edges) {
                if (RiverHelpers.distanceVertexFastSquared(e.drain, vertex) > maxDistanceSquared + 4
                    && RiverHelpers.distanceVertexFastSquared(e.source, vertex) > maxDistanceSquared + 4) {
                    continue;
                }

                double distanceSquared = RiverHelpers.distanceSq(e, vertex);
                if (distanceSquared < maxDistanceSquared) {
                    allEdgesInRange.add(e);
                }
            }
        }
        return allEdgesInRange;
    }
}
