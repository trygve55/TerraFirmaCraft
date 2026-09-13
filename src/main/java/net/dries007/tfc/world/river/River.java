/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

import com.google.common.collect.Queues;
import net.dries007.tfc.world.region.Region;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.apache.commons.compress.utils.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class River {
    public static final float INITIAL_RIVER_EDGE_LENGTH = 0.8f;
    private static final int MIN_BRANCH_EDGE_COUNT = 4;
    private static final int MIN_RIVER_EDGE_COUNT = 8;
    private static final int MIN_ENDORHEIC_RIVER_EDGE_COUNT = 8;
    private static final double SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER = 4;
    private static final double SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER_RAINFALL_INFLUENCE = 0.4;
    private static final boolean DEBUG_DRAW_UNPLACED_STARTING_EDGES = false;

    /**
     * @return The shortest square distance between a point {@code vertex} and the line segment {@code edge}
     */
    private static double distanceSq(Edge edge, Vertex vertex) {
        return RiverHelpers.distancePointToLineSq(edge.drain.x, edge.drain.y, edge.source.x, edge.source.y, vertex.x, vertex.y);
    }

    private static double distanceVertex(Vertex vertex, Vertex otherVertex) {
        return Math.sqrt(Math.pow(vertex.x - otherVertex.x, 2) + Math.pow(vertex.y - otherVertex.y, 2));
    }

    private static double distanceVertexFastSquared(Vertex vertex, Vertex otherVertex) {
        double x = vertex.x - otherVertex.x;
        double y = vertex.y - otherVertex.y;
        return x * x + y * y;
    }

    /**
     * @return {@code true} if the lines described by (p1, q1), and (p2, q2) intersect.
     */
    private static boolean intersect(Vertex p1, Vertex q1, Vertex p2, Vertex q2) {
        int o1 = orientation(p1, q1, p2);
        int o2 = orientation(p1, q1, q2);
        int o3 = orientation(p2, q2, p1);
        int o4 = orientation(p2, q2, q1);

        return (o1 != o2 && o3 != o4)
            || (o1 == 0 && intersectCollinear(p1, p2, q1))
            || (o2 == 0 && intersectCollinear(p1, q2, q1))
            || (o3 == 0 && intersectCollinear(p2, p1, q2))
            || (o4 == 0 && intersectCollinear(p2, q1, q2));
    }

    /**
     * @return {@code true} if, given three collinear points (p, q, r), that q intersects the line segment described by (p, r).
     */
    private static boolean intersectCollinear(Vertex p, Vertex q, Vertex r) {
        return q.x <= Math.max(p.x, r.x) && q.x >= Math.min(p.x, r.x) && q.y <= Math.max(p.y, r.y) && q.y >= Math.min(p.y, r.y);
    }

    /**
     * @return The orientation of three points (p, q, r) on a plane. 0 = collinear, 1 = clockwise, 2 = anticlockwise.
     */
    private static int orientation(Vertex p, Vertex q, Vertex r) {
        final double value = (q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y);
        if (value == 0) {
            return 0;
        }
        return value > 0 ? 1 : 2;
    }

    public interface Context {
        @Nullable
        Edge intersectClosestOther(Edge edge);
    }

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
        private final RandomSource random;

        private final List<Edge> edges;
        private final List<Edge> startEdges;
        private Edge endEdge;
        private final Vertex root;
        private Vertex prev;
        private double nextAngle;
        private double nextLength;
        private final float waterVolumeCubicMeters;
        private final Function<Vertex, Region.Point> vertexToPoint;
        private int prevBiomeAltitude;
        private int prevDistanceToOcean;
        private boolean requireReachingOcean = true;

        private final Region.Point initialPoint;

        private int stuckFor = 0;
        private int stuckForTotal = 0;

        private boolean angleTowardsRiverSet = false;
        private boolean angleTowardsLowerAltitudeSet = false;
        private boolean angleTowardsOceanSet = false;

        public Builder(RandomSource random, double sourceX, double sourceY, double angle, float rainfall, Function<Vertex, Region.Point> vertexToPoint) {
            this.random = random;

            this.edges = new ArrayList<>();
            this.startEdges = new ArrayList<>();
            this.nextLength = INITIAL_RIVER_EDGE_LENGTH;
            this.root = new Vertex(sourceX, sourceY, angle, INITIAL_RIVER_EDGE_LENGTH, 0);
            this.prev = root;
            this.nextAngle = root.angle;
            this.waterVolumeCubicMeters = rainfall * (128f * 128f / 1000f);
            this.vertexToPoint = vertexToPoint;

            initialPoint = vertexToPoint.apply(root);
            setBiomeAltitudeAndDistanceToOcean(initialPoint);
        }

        /**
         * Builds the initial branch for a river
         *
         * @return {@code true} if the initial branch reached a sufficient length
         */
        private boolean buildInitialBranch(Context context) {
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

                Vertex next = computeNext(prev, nextLength, nextAngle);
                Region.Point nextPoint = vertexToPoint.apply(next);

                if (nextPoint == null) {
                    stuckFor++;
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

                    Vertex aimForVertex = distanceVertex(prev, intersected.source) < distanceVertex(prev, intersected.drain) - 0.15 || intersected.source.distance == 0 ? intersected.source : intersected.drain;
                    double distance = distanceVertex(prev, aimForVertex);

                    if (edges.isEmpty() && distance < getMinDistanceToNearestRiver()) {
                        pruneBranchAndAddWaterVolumeTo(intersected);
                        return true;
                    }

                    if ((distance > 2.8 && nextPoint.distanceToOcean <= 2) || (distance > 0.8 && nextPoint.distanceToOcean <= 1)) {
                        Vertex oceanVertex = getPossibleOceanDrain(nextPoint);
                        if (oceanVertex != null) {
                            nextAngle = getAngleToVertex(prev, oceanVertex) + (random.nextDouble() * 0.4 - 0.2);
                        } else {
                            nextAngle = findBestAngleToSea(nextPoint) + (random.nextDouble() * 0.4 - 0.2);
                        }
                        angleTowardsOceanSet = true;
                        stuckFor++;
                        stuckForTotal++;
                        continue;
                    }

                    if (distance > prev.length * 1.3) {
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

                    if (prevBiomeAltitude < vertexToPoint.apply(aimForVertex).biomeAltitude) {
                        stuckFor++;
                        stuckForTotal++;
                        nextAngle = computeNextAngle(prev);
                        continue;
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
                    if (oceanVertex != null && distanceVertex(prev, oceanVertex) <= 1.3) {
                        nextEdge = new Edge(prev, oceanVertex, this);
                        commitEdge(nextEdge);

                        if (isRiverToShort()) {
                            edges.clear();
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

            edges.clear();
            return false;
        }

        private @Nullable Vertex getClosestAcceptableInRange(Region.Point nextPoint, int maxDistance, Predicate<Region.Point> pointPredicate, float offsetToResultX, float offsetToResultY) {
            List<Vertex> options = new ArrayList<>();

            for (int distance = 1; distance <= maxDistance; distance++) {
                for (int offsetX = -distance; offsetX <= distance; offsetX++) {
                    for (int offsetY = -distance; offsetY <= distance; offsetY++) {
                        Vertex testVertex = new Vertex(nextPoint.x + offsetX, nextPoint.z + offsetY, 0, 0, 0);
                        Region.Point point = vertexToPoint.apply(testVertex);

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
                        double newDistance = distanceVertex(prev, vertex);
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
            Region.Point point = vertexToPoint.apply(testVertex);

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
            Region.Point point = vertexToPoint.apply(testVertex);

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
            Region.Point point = vertexToPoint.apply(testVertex);

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

        private boolean isLandOrIslandNearby(Region.Point nextPoint) {
            for (int distance = 1; distance <= 1; distance++) {
                for (int offsetX = -distance; offsetX <= distance; offsetX++) {
                    for (int offsetY = -distance; offsetY <= distance; offsetY++) {
                        Vertex testVertex = new Vertex(nextPoint.x + offsetX, nextPoint.z + offsetY, 0, 0, 0);
                        Region.Point point = vertexToPoint.apply(testVertex);

                        if (point == null) {
                            continue;
                        }

                        if (point.shore()) {
                            continue;
                        }

                        if (point.land()
                            || point.island()
                            || point.mountain()
                            || point.hotSpot()
                            || point.volcanic()
                            || point.barrierIsland()
                            || point.coastalMountain()) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private boolean isOceanWithShoreAndTwoOceanAdjacent(Region.Point testPoint) {
            if (!isOceanPoint(new Vertex(testPoint.x, testPoint.z, 0, 0, 0))) {
                return false;
            }

            int totalOceanTiles = 0;

            if (isOceanPoint(new Vertex(testPoint.x + 1, testPoint.z, 0, 0, 0))) {
                totalOceanTiles++;
            }
            if (isOceanPoint(new Vertex(testPoint.x - 1, testPoint.z, 0, 0, 0))) {
                totalOceanTiles++;
            }
            if (isOceanPoint(new Vertex(testPoint.x, testPoint.z + 1, 0, 0, 0))) {
                totalOceanTiles++;
            }
            if (isOceanPoint(new Vertex(testPoint.x, testPoint.z - 1, 0, 0, 0))) {
                totalOceanTiles++;
            }
            if (totalOceanTiles < 2) {
                return false;
            }

            for (int distance = 1; distance <= 1; distance++) {
                for (int offsetX = -distance; offsetX <= distance; offsetX++) {
                    for (int offsetY = -distance; offsetY <= distance; offsetY++) {
                        Vertex testVertex = new Vertex(testPoint.x + offsetX, testPoint.z + offsetY, 0, 0, 0);
                        Region.Point point = vertexToPoint.apply(testVertex);

                        if (point == null) {
                            continue;
                        }

                        if (point.land()
                            || point.island()
                            || point.mountain()
                            || point.hotSpot()
                            || point.volcanic()
                            || point.barrierIsland()
                            || point.coastalMountain()) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        private boolean pathToNextIntercepts(Predicate<Region.Point> pointPredicate) {
            int testPoints = 5;
            for (int i = testPoints; i >= 1; i--) {
                Vertex testVertex = computeNext(prev, (nextLength / testPoints) * i, nextAngle);
                Region.Point testPoint = vertexToPoint.apply(testVertex);
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
                        Region.Point point = vertexToPoint.apply(lowerAltiudeVertex);

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

        private boolean addRainfallToClosestRiverOrSea(Context context) {
            int maxDistance = 12;
            int maxDistanceToOcean = 12;

            Edge intersected = context.intersectClosestOther(new Edge(root, computeNext(root, 0.01, 0), this));
            if (intersected != null) {
                Vertex aimForVertex = distanceVertex(root, intersected.source) < distanceVertex(root, intersected.drain) ? intersected.source : intersected.drain;
                double distance = distanceVertex(root, aimForVertex);
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

        private void drawDebugEdge() {
            if (DEBUG_DRAW_UNPLACED_STARTING_EDGES) {
                Vertex left = new Vertex(root.x - 0.1, root.y, 0, 0, 0);
                Vertex right = new Vertex(root.x + 0.1, root.y, 0, 0, 1);
                commitEdge(new Edge(left, right, this));
                commitRiver();
            }
        }

        public boolean buildAllowInlandDrain(Context context) {
            requireReachingOcean = false;

            this.prev = root;
            this.nextAngle = root.angle;

            this.stuckFor = 0;
            this.stuckForTotal = 0;

            setBiomeAltitudeAndDistanceToOcean(initialPoint);

            return buildInitialBranch(context);
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
        }

        private void pruneBranchAndAddWaterVolumeTo(Edge intersected) {
            intersected.addWaterflowSource(waterVolumeCubicMeters);
            edges.clear();
        }

        private void commitEdge(Edge edge) {
            Region.Point nextPoint = vertexToPoint.apply(edge.drain);
            setBiomeAltitudeAndDistanceToOcean(nextPoint);
            edges.add(edge);
            prev = edge.drain;
            stuckFor = 0;
            angleTowardsRiverSet = false;
            angleTowardsLowerAltitudeSet = false;
            angleTowardsOceanSet = false;
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
                if (e.drain != edge.source && (distanceSq(e, edge.drain) < 0.8f || intersect(e.source, e.drain, edge.source, edge.drain))) {
                    return e;
                }
            }
            return null;
        }

        private double computeNextAngle(Vertex prev) {
            return prev.angle() + (random.nextDouble() * 0.5f + 0.2f) * (random.nextBoolean() ? 1 : -1);
        }

        private Vertex computeNext(Vertex prev, double length, double nextAngle) {
            //double nextLength = Math.min(Math.max(length * (random.nextDouble() * 0.18f + 0.92f), 0.8f), 1.6f); // todo increase length over time

            // Extend in the direction of the next angle
            double dx = Mth.cos((float) nextAngle) * nextLength, dy = Mth.sin((float) nextAngle) * nextLength;
            double x = prev.x() + dx, y = prev.y() + dy;

            return new Vertex(x, y, nextAngle, nextLength, prev.distance + 1);
        }

        private float findBestAngleToSea(Region.Point point) {
            // Iterate to find the most likely direction towards the ocean
            // Selects the best angle, out of eight choices, and if there are multiple ideal choices, will select uniformly
            // Then, applies a slight variance on the chosen angle, so rivers don't start at exact pi/4 increments, as the river builder will respect the starting angle exactly.
            float bestDistanceMetric = Float.MAX_VALUE;
            int bestDistanceCount = 0;
            float bestAngle = Float.NaN;

            for (int dirX = -1; dirX <= 1; dirX++) {
                for (int dirZ = -1; dirZ <= 1; dirZ++) {
                    if (dirX == 0 && dirZ == 0) continue;

                    final @Nullable Region.Point dirPoint = vertexToPoint.apply(new Vertex(point.x + 4 * dirX, point.z + 4 * dirZ, 0, 0, 0));
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
                bestAngle += random.nextFloat() * 1.2f - 0.6f; // The rough area covered by each angle is pi/4 ~ 0.75, this gives each angle some wiggle room, but still directs it in the general vicinity of the target angle.
            }

            return bestAngle;
        }
    }

    public static class MultiParallelBuilder implements Context {
        private final List<Builder> builders;

        public MultiParallelBuilder() {
            this.builders = new ArrayList<>();
        }

        public Context add(Builder builder) {
            builders.add(builder);
            return this;
        }

        public <E> List<E> build(Function<Edge, E> map) {
            // Use a heap, sorted by total river length (edge count), so we prioritize building large rivers, and discarding short ones.
            //final PriorityQueue<Builder> remainingStartingBuilders = new PriorityQueue<>(Comparator.comparing(b -> -b.edges.size() - 10 * b.branchQueue.size()));
            final Queue<Builder> remainingStartingBuilders = Queues.newConcurrentLinkedQueue();

            for (Builder builder : builders) {
                if (!builder.buildInitialBranch(this)) {
                    remainingStartingBuilders.offer(builder);
                }
            }

            final Collection<Builder> remainingBuildersNotConnected = Lists.newArrayList();

            for (Builder builder : remainingStartingBuilders) {
                if (!builder.buildAllowInlandDrain(this)) {
                    remainingBuildersNotConnected.add(builder);
                }
            }

            for (Builder builder : remainingBuildersNotConnected) {
                if (!builder.addRainfallToClosestRiverOrSea(this)) {
                    builder.drawDebugEdge();
                }
            }

            for (Builder builder : remainingStartingBuilders) {
                builder.drawDebugEdge();
            }

            return builders.stream().flatMap(e -> e.edges.stream().map(map)).toList();
        }

        @Override
        @Nullable
        public Edge intersectClosestOther(Edge edge) {
            double maxDistance = 6f;
            double maxDistanceSquared = maxDistance * maxDistance;

            double closestDistance = Double.MAX_VALUE;
            Edge closestEdge = null;
            for (Builder river : builders) {
                if (river == edge.river) {
                    continue;
                }

                for (Edge e : river.edges) {
                    if (River.distanceVertexFastSquared(e.drain, edge.drain) > maxDistanceSquared + 4 && River.distanceVertexFastSquared(e.source, edge.drain) > maxDistanceSquared + 4) {
                        continue;
                    }

                    double distanceSquared = distanceSq(e, edge.drain);
                    if (e.drain != edge.source && ((distanceSquared < closestDistance && distanceSquared < maxDistanceSquared) || intersect(e.source, e.drain, edge.source, edge.drain))) {
                        closestDistance = distanceSquared;
                        closestEdge = e;
                    }
                }
            }
            return closestEdge;
        }
    }
}
