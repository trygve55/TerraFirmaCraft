/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river;

import java.util.*;
import java.util.function.Predicate;

import net.dries007.tfc.world.region.Region;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class River {
    public static final float INITIAL_RIVER_EDGE_LENGTH = 0.8f;
    public static final int MIN_BRANCH_EDGE_COUNT = 4;
    public static final int MIN_RIVER_EDGE_COUNT = 8;
    public static final int MIN_ENDORHEIC_RIVER_EDGE_COUNT = 8;

    public static final float MIN_RAINFALL_TO_CONTRIBUTE_TO_RIVERS_MM = 70;

    public static final double SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER = 4;
    public static final double SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER_RAINFALL_INFLUENCE = 0.4;

    public static final float LAKE_GENERATION_AT_SOURCE_CHANCE = 0.1f;
    public static final float LAKE_GENERATION_ALONG_RIVER_CHANCE = 0.02f;
    public static final int LAKE_GENERATION_ALONG_RIVER_MINIMUM_DISTANCE_FROM_SOURCE = 4;
    public static final int LAKE_GENERATION_AT_SOURCE_MINIMUM_WIDTH = 5;
    public static final boolean LAKE_GENERATION_ENABLED = true;

    public static final boolean DEBUG_DRAW_UNPLACED_STARTING_EDGES = false;

    public record Vertex(double x, double y, double angle, double length, int distance) {}

    public static class Edge {
        public Vertex source;
        public Vertex drain;
        public Builder river;
        @Nullable
        public Edge downstreamEdge;
        public float waterflowSource;
        public float waterflowTotal;

        public Edge(Vertex source, Vertex drain, Builder river) {
            this.source = source;
            this.drain = drain;
            this.river = river;
        }

        public Edge(Vertex source, Vertex drain, Builder river, @Nonnull Edge downstreamEdge) {
            this.source = source;
            this.drain = drain;
            this.river = river;
            this.downstreamEdge = Objects.requireNonNull(downstreamEdge);
        }

        public Vertex drain() {
            return drain;
        }

        public Vertex source() {
            return source;
        }

        public void setDownstreamEdge(Edge downstreamEdge) {
            this.downstreamEdge = downstreamEdge;
        }

        public void setRiver(Builder river) {
            this.river = river;
        }

        public void addWaterflowSource(float waterflow) {
            waterflowSource += waterflow;
            addWaterflowDownstream(waterflow);
        }

        private void addWaterflowDownstream(float waterflow) {
            waterflowTotal += waterflow;
            if (downstreamEdge != null) {
                downstreamEdge.addWaterflowDownstream(waterflow);
            }
        }

        public MidpointFractal fractal(RandomSource random, int bisections) {
            return new MidpointFractal(random, bisections, source.x, source.y, drain.x, drain.y);
        }
    }

    public static class Builder {
        private final RegionRiverContext context;
        private final RandomSource random;

        final List<Edge> edges;
        private final List<Edge> startEdges;
        private Edge endEdge;
        private final Vertex root;
        private Vertex prev;
        private Edge prevEdge;
        private double nextAngle;
        private double nextLength;
        private final float waterVolumeCubicMeters;
        private int prevBiomeAltitude;
        private int prevDistanceToOcean;
        private boolean requireReachingOcean = true;

        private final Region.Point initialPoint;

        private int stuckFor = 0;
        private int stuckForTotal = 0;

        private boolean angleTowardsRiverSet = false;
        private boolean angleTowardsLowerAltitudeSet = false;
        private boolean angleTowardsOceanSet = false;

        public Builder(RegionRiverContext context, RandomSource random, Region.Point initialPoint) {
            this.context = context;
            this.random = random;

            this.initialPoint = initialPoint;

            this.edges = new ArrayList<>();
            this.startEdges = new ArrayList<>();
            this.nextLength = INITIAL_RIVER_EDGE_LENGTH;
            this.root = new Vertex(initialPoint.x + 0.5f, initialPoint.z + 0.5f, getBestAngleToSeaOrRandom(initialPoint), INITIAL_RIVER_EDGE_LENGTH, 0);
            this.prev = root;
            this.nextAngle = root.angle;
            this.waterVolumeCubicMeters = initialPoint.rainfall * (128f * 128f / 1000f);

            setBiomeAltitudeAndDistanceToOcean(initialPoint);
        }

        /**
         * Builds the initial branch for a river
         *
         * @return {@code true} if the initial branch reached a sufficient length
         */
        boolean buildInitialBranch() {
            if (isValidRiverRootSource()) {
                return false;
            }

            while (stuckFor <= 15) {
                if (pathToNextIntercepts(
                    point -> point == null
                        || point.mountain()
                        || point.hotSpot()
                        || point.volcanic()
                        || point.island()
                        || point.coastalMountain()
                        || point.biomeAltitude > prevBiomeAltitude
                        || (point.distanceToOcean <= 3 && point.distanceToOcean > prevDistanceToOcean
                        && prevDistanceToOcean != -2 && point.distanceToOcean != -1))) {
                    stuckFor++;
                    stuckForTotal++;
                    nextAngle = computeNextAngle(prev);
                    continue;
                }

                Vertex next = computeNext(nextLength);
                Region.Point nextPoint = context.vertex2point(next);

                if (nextPoint == null) {
                    stuckFor++; // todo find out why needed
                    stuckForTotal++;
                    nextAngle = computeNextAngle(prev);
                    continue;
                }

                Edge nextEdge = new Edge(prev, next, this);

                Edge intersectedSelf = intersectSelf(nextEdge);
                if (intersectedSelf != null) {
                    stuckFor++;
                    stuckForTotal++;
                    nextAngle = computeNextAngle(prev);
                    continue;
                }

                Vertex lowerBiomeAltitudeVertex = getNeighboringLowerBiomeAltitude(nextPoint);
                if (lowerBiomeAltitudeVertex != null && !angleTowardsLowerAltitudeSet) {
                    angleTowardsLowerAltitudeSet = true;
                    nextAngle = getAngleToVertex(prev, lowerBiomeAltitudeVertex) + (random.nextDouble() * 0.3f) * (random.nextBoolean() ? 1 : -1);
                    continue;
                }

                Edge intersected = context.intersectClosestOther(nextEdge);
                if (intersected != null && !angleTowardsOceanSet) {

                    boolean aimForSourceVertex = RiverHelpers.distanceVertex(prev, intersected.source) < RiverHelpers.distanceVertex(prev, intersected.drain) - 0.15 - ((intersected.source.distance == 0) ? 0.1 : 0.);
                    Vertex aimForVertex = aimForSourceVertex ? intersected.source : intersected.drain;
                    double distance = RiverHelpers.distanceVertex(prev, aimForVertex);

                    if (edges.isEmpty() && distance < getMinDistanceToNearestRiver()) {
                        pruneBranchAndAddWaterVolumeTo(intersected);
                        return true;
                    }

                    if ((distance > 2.8 && nextPoint.distanceToOcean <= 2) || (distance > 0.8 && nextPoint.distanceToOcean <= 1)) {
                        Vertex oceanVertex = getPossibleOceanDrain(nextPoint);
                        if (oceanVertex != null) {
                            nextAngle = getAngleToVertex(prev, oceanVertex) + (random.nextDouble() * 0.4 - 0.2);
                        } else {
                            nextAngle = getBestAngleToSea(nextPoint); // todo make failsafe
                        }
                        angleTowardsOceanSet = true;
                        stuckFor++;
                        stuckForTotal++;
                        continue;
                    }

                    if (distance > prev.length * 1.8) {
                        if (angleTowardsRiverSet) {
                            commitEdge(nextEdge);
                            nextAngle = computeNextAngle(prev);
                            angleTowardsRiverSet = false;
                        } else {
                            angleTowardsRiverSet = true;
                            nextAngle = getAngleToVertex(prev, aimForVertex) + (random.nextDouble() * 0.5f + 0.2f) * (random.nextBoolean() ? 1 : -1);
                        }

                        continue;
                    }

                    if (prevBiomeAltitude < context.vertex2point(aimForVertex).biomeAltitude) {
                        stuckFor++;
                        stuckForTotal++;
                        nextAngle = computeNextAngle(prev);
                        continue;
                    }

                    if (!aimForSourceVertex && intersected.downstreamEdge != null) {
                        intersected = intersected.downstreamEdge;
                    }

                    nextEdge = new Edge(prev, aimForVertex, this, intersected);
                    edges.add(nextEdge);

                    if (isBranchToShort()) {
                        pruneBranchAndAddWaterVolumeTo(intersected);
                    } else {
                        connectBranchTo(intersected);
                    }
                    return true;
                }

                if (!nextPoint.land() && !nextPoint.shore()) { // || nextPoint.distanceToOcean <= 1) {
                    Vertex oceanVertex = getPossibleOceanDrain(nextPoint);
                    if (oceanVertex != null && RiverHelpers.distanceVertex(prev, oceanVertex) <= 1.3) {
                        nextEdge = new Edge(prev, oceanVertex, this);
                        commitEdge(nextEdge);

                        if (isRiverToShort()) {
                            resetRiver();
                            return false;
                        }
                        commitRiver();
                        return true;
                    }
                }

                if ((nextPoint.distanceToOcean <= 2) && !angleTowardsOceanSet) {
                    Vertex oceanVertex = getPossibleOceanDrain(nextPoint);
                    if (oceanVertex != null) {
                        stuckFor++;
                        stuckForTotal++;
                        angleTowardsOceanSet = true;
                        nextAngle = getAngleToVertex(prev, oceanVertex) + (random.nextDouble() * 0.4 - 0.2);
                        continue;
                    }
                }

                commitEdge(nextEdge);
                nextAngle = computeNextAngle(prev);
            }

            if (!requireReachingOcean && !isEndorheicRiverToShort()) {
                commitRiver();
                return true;
            }

            resetRiver();
            return false;
        }

        private void resetRiver() {
            edges.clear();
            prev = root;
            nextAngle = root.angle;

            stuckFor = 0;
            stuckForTotal = 0;

            angleTowardsRiverSet = false;
            angleTowardsLowerAltitudeSet = false;
            angleTowardsOceanSet = false;
        }

        private @Nullable Vertex getClosestAcceptableInRange(Region.Point nextPoint, int maxDistance, Predicate<Region.Point> pointPredicate, float offsetToResultX, float offsetToResultY) {
            List<Vertex> options = new ArrayList<>();

            for (int distance = 1; distance <= maxDistance; distance++) {
                for (int offsetX = -distance; offsetX <= distance; offsetX++) {
                    for (int offsetY = -distance; offsetY <= distance; offsetY++) {
                        Vertex testVertex = new Vertex(nextPoint.x + offsetX, nextPoint.z + offsetY, 0, 0, 0);
                        Region.Point point = context.vertex2point(testVertex);

                        if (point == null) {
                            continue;
                        }

                        if (pointPredicate.test(point)) {
                            options.add(new Vertex(nextPoint.x + offsetX + offsetToResultX, nextPoint.z + offsetY + offsetToResultY, 0, 0, 0));
                        }
                    }
                }

                if (!options.isEmpty()) {
                    double closestDistance = Double.MAX_VALUE;
                    Vertex closest = null;
                    for (Vertex vertex : options) {
                        double newDistance = RiverHelpers.distanceVertex(prev, vertex);
                        if (newDistance < closestDistance) {
                            closestDistance = newDistance;
                            closest = vertex;
                        }
                    }
                    return closest;
                }
            }
            return null;
        }

        private @Nullable Vertex getPossibleOceanDrain(Region.Point nextPoint) {
            Vertex oceanDrain;

            oceanDrain = getClosestAcceptableInRange(nextPoint, 2, this::is3x3ocean, 0.5f, 0.5f);

            if (oceanDrain != null) {
                return oceanDrain;
            }

            oceanDrain = getClosestAcceptableInRange(nextPoint, 3, this::is2x2ocean, 1.0f, 1.0f);

            if (oceanDrain != null) {
                return oceanDrain;
            }

            oceanDrain = getClosestAcceptableInRange(nextPoint, 3, this::isOceanWithShoreOrIslandAround3x3, 0.5f, 0.5f);

            if (oceanDrain != null) {
                return oceanDrain;
            }

            return null;
        }

        private boolean is2x2shore(Region.Point testPoint) {
            if (!isShorePoint(new Vertex(testPoint.x, testPoint.z, 0, 0, 0))) {
                return false;
            }

            int totalShoreTiles = 1;

            if (isShorePoint(new Vertex(testPoint.x + 1, testPoint.z, 0, 0, 0))) {
                totalShoreTiles++;
            }
            if (isShorePoint(new Vertex(testPoint.x, testPoint.z + 1, 0, 0, 0))) {
                totalShoreTiles++;
            }
            if (isShorePoint(new Vertex(testPoint.x + 1, testPoint.z + 1, 0, 0, 0))) {
                totalShoreTiles++;
            }

            return totalShoreTiles == 4;
        }

        private boolean is2x2ocean(Region.Point testPoint) {
            if (!isOceanPoint(new Vertex(testPoint.x, testPoint.z, 0, 0, 0))) {
                return false;
            }

            int totalShoreTiles = 1;

            if (isOceanPoint(new Vertex(testPoint.x + 1, testPoint.z, 0, 0, 0))) {
                totalShoreTiles++;
            }
            if (isOceanPoint(new Vertex(testPoint.x, testPoint.z + 1, 0, 0, 0))) {
                totalShoreTiles++;
            }
            if (isOceanPoint(new Vertex(testPoint.x + 1, testPoint.z + 1, 0, 0, 0))) {
                totalShoreTiles++;
            }

            return totalShoreTiles == 4;
        }

        private boolean is3x3ocean(Region.Point testPoint) {
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    if (!isOceanPoint(new Vertex(testPoint.x + i, testPoint.z + j, 0, 0, 0))) {
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean isOceanWithShoreOrIslandAround3x3(Region.Point testPoint) {
            if (!isOceanPoint(new Vertex(testPoint.x, testPoint.z, 0, 0, 0))) {
                return false;
            }

            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    Vertex testVertex = new Vertex(testPoint.x + i, testPoint.z + j, 0, 0, 0);
                    if (!isOceanPoint(testVertex) && !isShoreOrIslandPoint(testVertex)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean isShorePoint(Vertex testVertex) {
            Region.Point point = context.vertex2point(testVertex);

            return point != null
                && !point.land()
                && point.shore()
                && !point.island()
                && !point.mountain()
                && !point.hotSpot()
                && !point.volcanic()
                && !point.barrierIsland()
                && !point.coastalMountain();
        }

        private boolean isShoreOrIslandPoint(Vertex testVertex) {
            Region.Point point = context.vertex2point(testVertex);

            return point != null
                && !point.land()
                && !point.mountain()
                && !point.hotSpot()
                && !point.volcanic()
                && !point.barrierIsland()
                && !point.coastalMountain()
                && (point.shore() || point.island());
        }

        private boolean isOceanPoint(Vertex testVertex) {
            Region.Point point = context.vertex2point(testVertex);

            return point != null
                && !point.land()
                && !point.shore()
                && !point.island()
                && !point.mountain()
                && !point.hotSpot()
                && !point.volcanic()
                && !point.barrierIsland()
                && !point.coastalMountain();
        }

        private boolean pathToNextIntercepts(Predicate<Region.Point> pointPredicate) {
            int testPoints = 5;
            for (int i = testPoints; i >= 1; i--) {
                Vertex testVertex = computeNext((nextLength / testPoints) * i);
                Region.Point testPoint = context.vertex2point(testVertex);
                if (pointPredicate.test(testPoint)) {
                    return true;
                }
            }
            return false;
        }

        @Nullable
        private Vertex getNeighboringLowerBiomeAltitude(Region.Point nextPoint) {
            List<Vertex> options = new ArrayList<>(); // todo optimize

            for (int distance = 1; distance <= 1; distance++) {
                for (int offsetX = -distance; offsetX <= distance; offsetX++) {
                    for (int offsetY = -distance; offsetY <= distance; offsetY++) {
                        Vertex lowerAltiudeVertex = new Vertex(nextPoint.x + offsetX, nextPoint.z + offsetY, 0, 0, 0);
                        Region.Point point = context.vertex2point(lowerAltiudeVertex);

                        if (point != null
                            && point.land()
                            && !point.shore()
                            && !point.island()
                            && !point.mountain()
                            && !point.hotSpot()
                            && !point.volcanic()
                            && !point.barrierIsland()
                            && !point.coastalMountain()
                            && point.biomeAltitude < prevBiomeAltitude) {

                            options.add(new Vertex(nextPoint.x + offsetX + 0.5f, nextPoint.z + offsetY + 0.5f, 0, 0, prev.distance));
                        }
                    }
                }
            }

            if (options.isEmpty()) {
                return null;
            }
            return options.get(random.nextInt(options.size()));
        }

        private boolean isValidRiverRootSource() {
            return initialPoint.mountain()
                || initialPoint.hotSpot()
                || initialPoint.volcanic()
                || initialPoint.island()
                || initialPoint.shore()
                || initialPoint.coastalMountain();
        }

        boolean addRainfallToClosestRiverOrSea(RegionRiverContext context) {
            int maxDistance = 12;
            int maxDistanceToOcean = 12;

            Edge intersected = context.intersectClosestOther(new Edge(root, computeNext(0.01), this));
            if (intersected != null) {
                Vertex aimForVertex = RiverHelpers.distanceVertex(root, intersected.source) < RiverHelpers.distanceVertex(root, intersected.drain) ? intersected.source : intersected.drain;
                double distance = RiverHelpers.distanceVertex(root, aimForVertex);
                if (distance < initialPoint.distanceToOcean) {
                    if (distance <= maxDistance) {
                        pruneBranchAndAddWaterVolumeTo(intersected);
                        return true;
                    }
                    return false;
                }
            }

            if (initialPoint.distanceToOcean <= maxDistanceToOcean) {
                return true;
            }

            return false;
        }

        void drawDebugEdge() {
            if (DEBUG_DRAW_UNPLACED_STARTING_EDGES) {
                Vertex left = new Vertex(root.x - 0.1, root.y, 0, 0, 0);
                Vertex right = new Vertex(root.x + 0.1, root.y, 0, 0, 1);
                commitEdge(new Edge(left, right, this));
                commitRiver();
            }
        }

        public boolean buildAllowInlandDrain() {
            requireReachingOcean = false;

            this.stuckFor = 0;
            this.stuckForTotal = 0;

            setBiomeAltitudeAndDistanceToOcean(initialPoint);

            return buildInitialBranch();
        }

        private double getMinDistanceToNearestRiver() {
            return SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER - SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER * (initialPoint.rainfall / 500) * SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER_RAINFALL_INFLUENCE;
        }

        private void commitRiver() {
            annotateDownstream();
            edges.getFirst().addWaterflowSource(waterVolumeCubicMeters);
            startEdges.add(edges.getFirst());
            endEdge = edges.getLast();
        }

        private void connectBranchTo(Edge intersected) {
            annotateDownstream();
            edges.getFirst().addWaterflowSource(waterVolumeCubicMeters);

            // transfer to other river
            intersected.river.startEdges.add(edges.getFirst());
            edges.forEach(edge -> edge.setRiver(intersected.river));
            intersected.river.edges.addAll(edges);
            edges.clear();
            endEdge = intersected.river.endEdge;
        }

        private void pruneBranchAndAddWaterVolumeTo(Edge intersected) {
            intersected.addWaterflowSource(waterVolumeCubicMeters);
            edges.clear();
            endEdge = intersected.river.endEdge;
        }

        private void commitEdge(Edge edge) {
            Region.Point nextPoint = context.vertex2point(edge.drain);
            setBiomeAltitudeAndDistanceToOcean(nextPoint);
            edges.add(edge);
            prev = edge.drain;
            stuckFor = 0;
            angleTowardsRiverSet = false;
            angleTowardsLowerAltitudeSet = false;
            angleTowardsOceanSet = false;
        }

        // If the river have successfully been connected to a drainage basin.
        public boolean haveDrainageBasin() {
            return endEdge != null;
        }

        private void setBiomeAltitudeAndDistanceToOcean(Region.Point point) {
            prevBiomeAltitude = point.biomeAltitude;
            prevDistanceToOcean = point.distanceToOcean;
        }

        private double getAngleToVertex(Vertex prev, Vertex aimForVertex) {
            return Math.atan2(aimForVertex.y - prev.y, aimForVertex.x - prev.x);
        }

        // Do a check to ensure that the river is of sufficient length, and if not, discard it
        private boolean isRiverToShort() {
            return edges.size() < MIN_RIVER_EDGE_COUNT;
        }

        private boolean isBranchToShort() {
            return edges.size() < MIN_BRANCH_EDGE_COUNT;
        }

        private boolean isEndorheicRiverToShort() {
            return edges.size() < MIN_ENDORHEIC_RIVER_EDGE_COUNT;
        }

        private void annotateDownstream() {
            for (int j = 0; j < edges.size() - 1; j++) {
                edges.get(j).setDownstreamEdge(edges.get(j + 1));
            }
        }

        @Nullable
        public Edge intersectSelf(Edge edge) {
            for (Edge e : edges) {
                if (e.drain != edge.source && (RiverHelpers.distanceSq(e, edge.drain) < 0.8f || RiverHelpers.intersect(e.source, e.drain, edge.source, edge.drain))) {
                    return e;
                }
            }
            return null;
        }

        private double computeNextAngle(Vertex prev) {
            return prev.angle() + (random.nextDouble() * 0.5f + 0.2f) * (random.nextBoolean() ? 1 : -1);
        }

        private Vertex computeNext(double length) {
            //double nextLength = Math.min(Math.max(length * (random.nextDouble() * 0.18f + 0.92f), 0.8f), 1.6f); // todo increase length over time

            // Extend in the direction of the next angle
            double dx = Mth.cos((float) nextAngle) * nextLength, dy = Mth.sin((float) nextAngle) * nextLength;
            double x = prev.x() + dx, y = prev.y() + dy;

            return new Vertex(x, y, nextAngle, nextLength, prev.distance + 1);
        }

        private float getBestAngleToSeaOrRandom(Region.Point point) {
            float angle = getBestAngleToSea(point);
            if (!Float.isNaN(angle)) {
                return angle;
            }
            return getRandomAngle();
        }

        private float getBestAngleToSea(Region.Point point) {
            // Iterate to find the most likely direction towards the ocean
            // Selects the best angle, out of eight choices, and if there are multiple ideal choices, will select uniformly
            // Then, applies a slight variance on the chosen angle, so rivers don't start at exact pi/4 increments, as the river builder will respect the starting angle exactly.
            float bestDistanceMetric = Float.MAX_VALUE;
            int bestDistanceCount = 0;
            float bestAngle = Float.NaN;

            for (int dirX = -1; dirX <= 1; dirX++) {
                for (int dirZ = -1; dirZ <= 1; dirZ++) {
                    if (dirX == 0 && dirZ == 0) continue;

                    @Nullable
                    final Region.Point dirPoint = context.vertex2point(new Vertex(point.x + 4 * dirX, point.z + 4 * dirZ, 0, 0, 0));
                    if (dirPoint != null) {
                        final float dirDistanceMetric = dirPoint.distanceToLand - dirPoint.distanceToOcean - Math.abs(dirX) - Math.abs(dirZ);
                        if (dirDistanceMetric < bestDistanceMetric || (dirDistanceMetric == bestDistanceMetric && random.nextInt(1 + bestDistanceCount) == 0)) {
                            if (dirDistanceMetric < bestDistanceMetric) {
                                bestDistanceMetric = dirDistanceMetric;
                                bestDistanceCount = 0;
                            }
                            bestDistanceCount += 1;
                            bestAngle = (float) Math.atan2(-dirZ, -dirX);
                        }
                    }
                }
            }
            if (!Float.isNaN(bestAngle)) {
                return bestAngle + random.nextFloat() * 1.2f - 0.6f; // The rough area covered by each angle is pi/4 ~ 0.75, this gives each angle some wiggle room, but still directs it in the general vicinity of the target angle.
            }

            return Float.NaN;
        }

        private float getRandomAngle() {
            return (float) (Math.PI * random.nextFloat() * (random.nextBoolean() ? 1 : -1));
        }
    }
}
