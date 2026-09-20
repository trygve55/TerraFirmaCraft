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

import static net.dries007.tfc.world.river.River.Constants.*;

public class River {
    public static class Constants {
        public static final float INITIAL_RIVER_EDGE_LENGTH = 0.8f;

        public static final int MIN_BRANCH_EDGE_COUNT = 4;
        public static final int MIN_RIVER_EDGE_COUNT = 8;
        public static final int MIN_ENDORHEIC_RIVER_EDGE_COUNT = 8;

        public static final float MIN_RAINFALL_TO_CONTRIBUTE_TO_RIVERS_MM = 40;

        public static final double SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER = 3.5;
        public static final double SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER_RAINFALL_INFLUENCE = 0.4;

    public static final float LAKE_GENERATION_AT_SOURCE_CHANCE = 0.1f;
    public static final float LAKE_GENERATION_ALONG_RIVER_CHANCE = 0.02f;
    public static final int LAKE_GENERATION_ALONG_RIVER_MINIMUM_DISTANCE_FROM_SOURCE = 4;
    public static final int LAKE_GENERATION_AT_SOURCE_MINIMUM_WIDTH = 5;
    public static final boolean LAKE_GENERATION_ENABLED = true;
        public static final boolean LAKE_GENERATION_ENABLED = true;
        public static final float LAKE_GENERATION_AT_SOURCE_CHANCE = 0.7f;
        public static final float LAKE_GENERATION_AT_SOURCE_CHANCE_RAINFALL_INFLUENCE = 0.7f;
        public static final int LAKE_AT_SOURCE_MAX_SIZE = 6;
        public static final float LAKE_AT_SOURCE_MAX_SIZE_RAINFALL_INFLUENCE = 0.5f;

        public static final boolean LAKE_ENDORHEIC_GENERATION_ENABLED = true;

        public static final int MIN_GRID_DISTANCE_BETWEEN_LAKES = 1;
        public static final int MIN_GRID_DISTANCE_BETWEEN_LAKE_AND_OCEAN = 2;
        public static final int MIN_GRID_DISTANCE_BETWEEN_LAKE_AND_OTHER_RIVER = 1;

        public static final boolean DEBUG_DRAW_UNPLACED_STARTING_EDGES = false;
        public static final boolean DEBUG_STRAIGHT_RIVER_EDGES = false;

        private static final int MAX_RAINFALL = 500;

        public static double getSourceMinDistanceToNearestRiverWithRainfallInfluence(Region.Point point) {
            return SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER - SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER * (point.rainfall / MAX_RAINFALL) * SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER_RAINFALL_INFLUENCE;
        }

        public static double getLakeGenerationAtSourceChanceWithRainfallInfluence(Region.Point point) {
            return LAKE_GENERATION_AT_SOURCE_CHANCE - LAKE_GENERATION_AT_SOURCE_CHANCE * (1 - point.rainfall / MAX_RAINFALL) * LAKE_GENERATION_AT_SOURCE_CHANCE_RAINFALL_INFLUENCE;
        }

        public static int getLakeAtSourceMaxSizeWithRainfallInfluence(Region.Point point) {
            return Math.round(LAKE_AT_SOURCE_MAX_SIZE - LAKE_AT_SOURCE_MAX_SIZE * (1 - point.rainfall / MAX_RAINFALL) * LAKE_AT_SOURCE_MAX_SIZE_RAINFALL_INFLUENCE);
        }
    }

    public record Vertex(double x, double y, int distance) {
        public Vertex toGridAligned() {
            return new Vertex(
                Math.round(x - 0.5),
                Math.round(y - 0.5),
                distance);
        }

        public Vertex toGridTileCentered() {
            return new Vertex(
                Math.round(x - 0.5) + 0.5,
                Math.round(y - 0.5) + 0.5,
                distance);
        }
    }

    public record Lake(Vertex center, int lakeSize, boolean endorheic) {
        public Lake(Vertex center, int lakeSize, boolean endorheic) {
            assert lakeSize > 0;
            this.center = (lakeSize % 2 == 0)
                ? center.toGridAligned()
                : center.toGridTileCentered();
            this.lakeSize = lakeSize;
            this.endorheic = endorheic;
        }
    }

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

        private boolean isDownstreamOf(River.Edge edge) {
            if (edge.downstreamEdge == this) {
                return true;
            }

            if (edge.downstreamEdge == null) {
                return false;
            }
            return isDownstreamOf(edge.downstreamEdge);
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
        private double prevAngle;
        private double nextAngle;
        private double prevLength;
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
            this.root = new Vertex(initialPoint.x + 0.5f, initialPoint.z + 0.5f, 0);
            this.prev = root;
            this.prevLength = INITIAL_RIVER_EDGE_LENGTH;
            this.nextLength = prevLength;
            this.prevAngle = getBestAngleToSeaOrRandom(initialPoint);
            this.nextAngle = prevAngle;
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
                        || point.distanceToEdge < 3
                        || point.biomeAltitude > prevBiomeAltitude
                        || (point.distanceToOcean <= 3 && point.distanceToOcean > prevDistanceToOcean
                        && prevDistanceToOcean != -2 && point.distanceToOcean != -1))) {
                    stuckFor++;
                    stuckForTotal++;
                    nextAngle = computeNextAngle();
                    continue;
                }

                Vertex next = computeNext(nextLength);
                Region.Point nextPoint = context.vertex2point(next);

                if (nextPoint == null) {
                    stuckFor++; // todo find out why needed
                    stuckForTotal++;
                    nextAngle = computeNextAngle();
                    continue;
                }

                Edge nextEdge = new Edge(prev, next, this);

                Edge intersectedSelf = intersectSelf(nextEdge);
                if (intersectedSelf != null) {
                    stuckFor++;
                    stuckForTotal++;
                    nextAngle = computeNextAngle();
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

                    if (edges.isEmpty() && distance < Constants.getSourceMinDistanceToNearestRiverWithRainfallInfluence(nextPoint)) {
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

                    if (distance > prevLength * 1.8) {
                        if (angleTowardsRiverSet) {
                            commitEdge(nextEdge);
                            nextAngle = computeNextAngle();
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
                        nextAngle = computeNextAngle();
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
                nextAngle = computeNextAngle();
            }

            if (!requireReachingOcean && !isEndorheicRiverToShort()) {
                addEndorheicLake();
                commitRiver();
                return true;
            }

            resetRiver();
            return false;
        }

        private void addEndorheicLake() {
            if (!LAKE_ENDORHEIC_GENERATION_ENABLED) {
                return;
            }

            addLakeAndAlignRiver(edges.getLast().drain, random.nextIntBetweenInclusive(1, 3), true);

            annotateDownstream();
            pruneVertex(edges.getLast().source);
        }

        void attemptAddLakeAtSources() {
            for (Edge edge : startEdges) {
                attemptAddSourceLake(edge.source);
            }
        }

        private void attemptAddSourceLake(Vertex aroundVertex) {
            Region.Point point = context.vertex2point(aroundVertex);
            if (point == null) {
                return;
            }

            if (random.nextFloat() > Constants.getLakeGenerationAtSourceChanceWithRainfallInfluence(point)) {
                return;
            }

            int maxLakeSize = Math.min(
                Math.min(
                    random.nextIntBetweenInclusive(1, Constants.getLakeAtSourceMaxSizeWithRainfallInfluence(point)),
                    (int) Math.ceil(getDistanceToClosestLake(aroundVertex) - MIN_GRID_DISTANCE_BETWEEN_LAKES)),
                Math.min(
                    point.distanceToOcean - MIN_GRID_DISTANCE_BETWEEN_LAKE_AND_OCEAN,
                    (int) Math.floor(getClosestDifferentBranchDistance(aroundVertex, LAKE_AT_SOURCE_MAX_SIZE)) - MIN_GRID_DISTANCE_BETWEEN_LAKE_AND_OTHER_RIVER));

            if (maxLakeSize <= 0) {
                return;
            }

            Edge currentEdge = getEdgeOfSourceVertex(aroundVertex);

            //size based on rainfall and random
            addLakeAndAlignRiver(aroundVertex, maxLakeSize, false);

            for (int i = 0; i < (maxLakeSize + 2) / 2; i++) {
                pruneVertex(currentEdge.drain);
            }
        }

        private double getClosestDifferentBranchDistance(Vertex aroundVertex, int maxDistance) {
            Edge currentEdge = getEdgeOfSourceVertex(aroundVertex);
            List<Edge> closeEdges = context.allEdgesInRange(aroundVertex, maxDistance);

            double closestDifferentBranchDistance = Double.MAX_VALUE;
            for (Edge edge : closeEdges) {
                if (edge.river != this
                    || edge != currentEdge && !(edge.isDownstreamOf(currentEdge) || currentEdge.isDownstreamOf(edge))) {
                    double distance = Math.sqrt(RiverHelpers.distanceSq(edge, aroundVertex));
                    if (distance < closestDifferentBranchDistance) {
                        closestDifferentBranchDistance = distance;
                    }
                }
            }
            return closestDifferentBranchDistance;
        }

        @Nullable
        private Edge getEdgeOfSourceVertex(Vertex vertex) {
            for (Edge edge : edges) {
                if (edge.source == vertex) {
                    return edge;
                }
            }
            return null;
        }

        private double getDistanceToClosestLake(Vertex vertex) {
            double minDistanceToOtherLakes = Double.MAX_VALUE;

            Vertex gridTileCentered = vertex.toGridTileCentered();
            for (Lake lake : context.getLakes()) {
                double lakeRadius = lake.lakeSize / 2.0;
                double distanceX = Math.abs(gridTileCentered.x - lake.center.x) - lakeRadius;
                double distanceY = Math.abs(gridTileCentered.y - lake.center.y) - lakeRadius;
                if (distanceX < minDistanceToOtherLakes && distanceY < minDistanceToOtherLakes) {
                    minDistanceToOtherLakes = Math.max(distanceX, distanceY);
                }
            }
            return minDistanceToOtherLakes;
        }

        private void addLakeAndAlignRiver(Vertex aroundVertex, int lakeSize, boolean endorheic) {
            Vertex relocateRiverTo = aroundVertex.toGridAligned();
            if (lakeSize == 2) {
                relocateRiverTo = new Vertex(relocateRiverTo.x - 0.5, relocateRiverTo.y - 0.15, relocateRiverTo.distance);
            }
            moveVertexTo(aroundVertex, relocateRiverTo);
            context.addLake(new Lake(aroundVertex, lakeSize, endorheic));
        }

        private void pruneVertex(Vertex vertex) {
            // todo implement support to prune start and end edges

            Set<Edge> edgesToRemove = new HashSet<>();

            for (Edge edge : edges) {
                if (edge.drain == vertex) {
                    Edge edgeToBePruned = edge.downstreamEdge;
                    edge.drain = edgeToBePruned.drain;
                    edge.downstreamEdge = edgeToBePruned.downstreamEdge;
                    edge.waterflowSource += edgeToBePruned.waterflowSource;
                    edgesToRemove.add(edgeToBePruned);
                }
            }

            edges.removeAll(edgesToRemove);

            if (edgesToRemove.isEmpty()) {
                throw new NoSuchElementException();
            }
        }

        private void moveVertexTo(Vertex oldVertex, Vertex newVertex) {
            for (Edge edge : edges) {
                if (edge.source == oldVertex) {
                    edge.source = newVertex;
                }
                if (edge.drain == oldVertex) {
                    edge.drain = newVertex;
                }
            }
        }

        private void resetRiver() {
            edges.clear();
            prev = root;
            nextAngle = getBestAngleToSeaOrRandom(initialPoint);

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
                        Vertex testVertex = new Vertex(nextPoint.x + offsetX, nextPoint.z + offsetY, 0);
                        Region.Point point = context.vertex2point(testVertex);

                        if (point == null) {
                            continue;
                        }

                        if (pointPredicate.test(point)) {
                            options.add(new Vertex(nextPoint.x + offsetX + offsetToResultX, nextPoint.z + offsetY + offsetToResultY, 0));
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
            if (!isShorePoint(new Vertex(testPoint.x, testPoint.z, 0))) {
                return false;
            }

            int totalShoreTiles = 1;

            if (isShorePoint(new Vertex(testPoint.x + 1, testPoint.z, 0))) {
                totalShoreTiles++;
            }
            if (isShorePoint(new Vertex(testPoint.x, testPoint.z + 1, 0))) {
                totalShoreTiles++;
            }
            if (isShorePoint(new Vertex(testPoint.x + 1, testPoint.z + 1, 0))) {
                totalShoreTiles++;
            }

            return totalShoreTiles == 4;
        }

        private boolean is2x2ocean(Region.Point testPoint) {
            if (!isOceanPoint(new Vertex(testPoint.x, testPoint.z, 0))) {
                return false;
            }

            int totalShoreTiles = 1;

            if (isOceanPoint(new Vertex(testPoint.x + 1, testPoint.z, 0))) {
                totalShoreTiles++;
            }
            if (isOceanPoint(new Vertex(testPoint.x, testPoint.z + 1, 0))) {
                totalShoreTiles++;
            }
            if (isOceanPoint(new Vertex(testPoint.x + 1, testPoint.z + 1, 0))) {
                totalShoreTiles++;
            }

            return totalShoreTiles == 4;
        }

        private boolean is3x3ocean(Region.Point testPoint) {
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    if (!isOceanPoint(new Vertex(testPoint.x + i, testPoint.z + j, 0))) {
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean isOceanWithShoreOrIslandAround3x3(Region.Point testPoint) {
            if (!isOceanPoint(new Vertex(testPoint.x, testPoint.z, 0))) {
                return false;
            }

            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    Vertex testVertex = new Vertex(testPoint.x + i, testPoint.z + j, 0);
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
                        Vertex lowerAltiudeVertex = new Vertex(nextPoint.x + offsetX, nextPoint.z + offsetY, 0);
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

                            options.add(new Vertex(nextPoint.x + offsetX + 0.5f, nextPoint.z + offsetY + 0.5f, prev.distance));
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
                || initialPoint.coastalMountain()
                || initialPoint.distanceToEdge < 3; // is rift valley
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
                Vertex left = new Vertex(root.x - 0.1, root.y, 0);
                Vertex right = new Vertex(root.x + 0.1, root.y, 1);
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
            prevAngle = nextAngle;
            prevLength = nextLength;
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

        private double computeNextAngle() {
            return prevAngle + (random.nextDouble() * 0.5f + 0.2f) * (random.nextBoolean() ? 1 : -1);
        }

        private Vertex computeNext(double length) {
            //double nextLength = Math.min(Math.max(length * (random.nextDouble() * 0.18f + 0.92f), 0.8f), 1.6f); // todo increase length over time

            // Extend in the direction of the next angle
            double dx = Mth.cos((float) nextAngle) * nextLength, dy = Mth.sin((float) nextAngle) * nextLength;
            double x = prev.x() + dx, y = prev.y() + dy;

            return new Vertex(x, y, prev.distance + 1);
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
                    final Region.Point dirPoint = context.vertex2point(new Vertex(point.x + 4 * dirX, point.z + 4 * dirZ, 0));
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
