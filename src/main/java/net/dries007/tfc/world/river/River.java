/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river;

import java.util.*;
import java.util.function.Function;

import net.dries007.tfc.world.region.Region;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class River {
    public static final float INITIAL_RIVER_EDGE_LENGTH = 0.8f;
    private static final int MIN_BRANCH_EDGE_COUNT = 3;
    private static final int MIN_RIVER_EDGE_COUNT = 4;
    private static final double SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER = 3;
    private static final double SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER_RAINFALL_INFLUENCE = 0.5;

    private static final Logger log = LoggerFactory.getLogger(River.class);

    /**
     * @return The shortest square distance between a point {@code vertex} and the line segment {@code edge}
     */
    private static double distance(Edge edge, Vertex vertex) {
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
        private final Queue<Edge> branchQueue;
        private final RandomSource random;

        private final List<Edge> edges;
        private final List<Edge> startEdges;
        private Edge endEdge;
        private final Vertex root;
        private Vertex prev;
        private double nextAngle;
        private final float waterVolumeCubicMeters;
        private final Function<Vertex, Region.Point> vertexToPoint;
        private int prevBiomeAltitude;
        private int prevDistanceToOcean;

        private final Region.Point initialPoint;

        private int stuckFor = 0;
        private int stuckForTotal = 0;

        public Builder(RandomSource random, double sourceX, double sourceY, double angle, float rainfall, Function<Vertex, Region.Point> vertexToPoint) {
            this.branchQueue = new LinkedList<>();
            this.random = random;

            this.edges = new ArrayList<>();
            this.startEdges = new ArrayList<>();
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
            boolean angleTowardsRiverSet = false;

            int length = 3000;
            for (int i = 0; i < length && stuckFor <= 30; i++) {
                double nextLength = vertexToPoint.apply(prev).shore() ? 1 : prev.length;

                Vertex next = computeNext(prev, nextLength, prev.distance, nextAngle);
                Region.Point nextPoint = vertexToPoint.apply(next);

                if (nextPoint == null
                    || nextPoint.mountain()
                    || nextPoint.hotSpot()
                    || nextPoint.volcanic()
                    || nextPoint.biomeAltitude > prevBiomeAltitude
                    || (nextPoint.distanceToOcean <= 3 && nextPoint.distanceToOcean > prevDistanceToOcean
                        && prevDistanceToOcean != -2 && nextPoint.distanceToOcean != -1)) {
                    stuckFor++;
                    stuckForTotal++;
                    nextAngle = computeNextAngle(prev);
                    continue;
                }

                Edge nextEdge = new Edge(prev, next, this);

                if ((!nextPoint.land() && !nextPoint.shore())) { // if shore, find closest ocean with atleast x connecting ocean points, at least one ocean point away from shore
                    edges.add(nextEdge);

                    if (isRiverToShort()) {
                        edges.clear();
                        return false;
                    }

                    commitRiver();

                    if (stuckForTotal > 0) {
                        //log.info("stuck for {} (total: {})of {}", stuckFor, stuckForTotal, length);
                    }
                    return true;
                }

                Edge intersectedSelf = intersectSelf(nextEdge);
                if (intersectedSelf != null) {
                    stuckFor++;
                    stuckForTotal++;
                    nextAngle = computeNextAngle(prev);
                    continue;
                }

                Edge intersected = context.intersectClosestOther(nextEdge);
                if (intersected != null) {
                    Vertex aimForVertex = distanceVertex(prev, intersected.source) < distanceVertex(prev, intersected.drain) - 0.15 ? intersected.source : intersected.drain;
                    double distance = distanceVertex(prev, aimForVertex);

                    if (edges.isEmpty() && distance < getMinDistanceToNearestRiver()) {
                        pruneBranchAndAddWaterVolumeTo(intersected);
                        return true;
                    }

                    if ((distance > 2.5 && nextPoint.distanceToOcean <= 2) || (distance > 1.2 && nextPoint.distanceToOcean <= 1)) {
                        confirmEdge(nextPoint, nextEdge, next);
                        nextAngle = findBestAngleToSea(nextPoint);
                        continue;
                    }

                    if (distance > prev.length * 1.3) {
                        if (angleTowardsRiverSet) {
                            confirmEdge(nextPoint, nextEdge, next);
                            nextAngle = computeNextAngle(prev);
                            angleTowardsRiverSet = false;
                        } else {
                            angleTowardsRiverSet = true;
                            nextAngle = getAngleToVertex(prev, aimForVertex) + (random.nextDouble() * 0.5f + 0.2f) * (random.nextBoolean() ? 1 : -1);
                        }

                        continue;
                    }

                    nextEdge = new Edge(prev, aimForVertex, this, intersected);
                    edges.add(nextEdge);

                    if (isBranchToShort()) {
                        pruneBranchAndAddWaterVolumeTo(intersected);
                    } else {
                        connectBranchTo(intersected);
                    }

                    if (stuckForTotal > 0) {
                        //log.info("branch stuck for {} (total: {})of {}", stuckFor, stuckForTotal, length);
                    }
                    return true;
                }

                if (nextPoint.distanceToOcean <= 2) {
                    confirmEdge(nextPoint, nextEdge, next);
                    nextAngle = findBestAngleToSea(nextPoint);
                    continue;
                }

                confirmEdge(nextPoint, nextEdge, next);
                nextAngle = computeNextAngle(prev);
            }

            edges.clear();
            return false;
        }

        private double getMinDistanceToNearestRiver() {
            return SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER - (initialPoint.rainfall / 500) * SOURCE_MIN_DISTANCE_TO_NEAREST_RIVER_RAINFALL_INFLUENCE;
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

        private void confirmEdge(Region.Point nextPoint, Edge nextEdge, Vertex next) {
            setBiomeAltitudeAndDistanceToOcean(nextPoint);
            edges.add(nextEdge);
            prev = next;
            stuckFor = 0;
        }

        private void setBiomeAltitudeAndDistanceToOcean(Region.Point initialPoint) {
            prevBiomeAltitude = initialPoint.biomeAltitude;
            prevDistanceToOcean = initialPoint.distanceToOcean;
        }

        private double getAngleToVertex(Vertex prev, Vertex aimForVertex) {
            return Math.atan2(aimForVertex.y - prev.y, aimForVertex.x - prev.x);
        }

        // Do a check to ensure that the river is of sufficient size and length, and if not, discard it
        private boolean isRiverToShort() {
            return edges.size() < MIN_RIVER_EDGE_COUNT;
        }

        private boolean isBranchToShort() {
            return edges.size() < MIN_BRANCH_EDGE_COUNT;
        }

        private void annotateDownstream() {
            for (int j = 0; j < edges.size() - 1; j++) {
                edges.get(j).setDownstreamEdge(edges.get(j + 1));
            }
        }

        @Nullable
        public Edge intersectSelf(Edge edge) {
            for (Edge e : edges) {
                if (e.drain != edge.source && (distance(e, edge.drain) < 0.8f || intersect(e.source, e.drain, edge.source, edge.drain))) {
                    return e;
                }
            }
            return null;
        }

        private double computeNextAngle(Vertex prev) {
            return prev.angle() + (random.nextDouble() * 0.5f + 0.2f) * (random.nextBoolean() ? 1 : -1);
        }

        private Vertex computeNext(Vertex prev, double length, int distance, double nextAngle) {
            double nextLength = Math.min(Math.max(length * (random.nextDouble() * 0.18f + 0.92f), 0.8f), 1.6f);

            // Extend in the direction of the next angle
            double dx = Mth.cos((float) nextAngle) * nextLength, dy = Mth.sin((float) nextAngle) * nextLength;
            double x = prev.x() + dx, y = prev.y() + dy;

            return new Vertex(x, y, nextAngle, nextLength, distance + 1);
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

        private static double normalizeAngle(double angle) {
            return (angle % Math.PI + Math.PI) % Math.PI;
        }

        private static double absoluteDifferenceAngle(double angle1, double angle2) {
            return Math.abs(angle1 - angle2);
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
            final PriorityQueue<Builder> working = new PriorityQueue<>(Comparator.comparing(b -> -b.edges.size() - 10 * b.branchQueue.size()));
            int i = 0;
            for (Builder builder : builders) {
                if (builder.buildInitialBranch(this)) {
                    working.offer(builder);
                }
            }

            log.info("points processed {} of {}", working.size(), builders.size());

            return builders.stream().flatMap(e -> e.edges.stream().map(map)).toList();
        }

        @Override
        @Nullable
        public Edge intersectClosestOther(Edge edge) {
            double minDistance = 14f;
            double minDistanceSquared = (minDistance + 1) * (minDistance + 1);

            double closestDistance = Double.MAX_VALUE;
            Edge closestEdge = null;
            for (Builder river : builders) {
                if (river == edge.river) {
                    continue;
                }

                for (Edge e : river.edges) {
//                    if (River.distanceVertexFastSquared(e.drain, edge.drain) > minDistanceSquared && River.distanceVertexFastSquared(e.source, edge.drain) > minDistanceSquared) {
//                        continue;
//                    }

                    double distance = distance(e, edge.drain);
                    if (e.drain != edge.source && ((distance < closestDistance && distance < minDistance) || intersect(e.source, e.drain, edge.source, edge.drain))) {
                        closestDistance = distance;
                        closestEdge = e;
                    }
                }
            }
            return closestEdge;
        }
    }
}
