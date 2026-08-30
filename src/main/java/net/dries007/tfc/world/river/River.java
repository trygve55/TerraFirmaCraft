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

public class River
{
    private static final double MIN_BRANCH_ANGLE = 0.4f;
    private static final int MIN_BRANCH_DISTANCE = 2;
    private static final int MIN_RIVER_EDGE_COUNT = 2;
    private static final Logger log = LoggerFactory.getLogger(River.class);

    /**
     * @return The shortest square distance between a point {@code vertex} and the line segment {@code edge}
     */
    private static double distance(Edge edge, Vertex vertex)
    {
        return RiverHelpers.distancePointToLineSq(edge.drain.x, edge.drain.y, edge.source.x, edge.source.y, vertex.x, vertex.y);
    }

    /**
     * @return {@code true} if the lines described by (p1, q1), and (p2, q2) intersect.
     */
    private static boolean intersect(Vertex p1, Vertex q1, Vertex p2, Vertex q2)
    {
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
    private static boolean intersectCollinear(Vertex p, Vertex q, Vertex r)
    {
        return q.x <= Math.max(p.x, r.x) && q.x >= Math.min(p.x, r.x) && q.y <= Math.max(p.y, r.y) && q.y >= Math.min(p.y, r.y);
    }

    /**
     * @return The orientation of three points (p, q, r) on a plane. 0 = collinear, 1 = clockwise, 2 = anticlockwise.
     */
    private static int orientation(Vertex p, Vertex q, Vertex r)
    {
        final double value = (q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y);
        if (value == 0)
        {
            return 0;
        }
        return value > 0 ? 1 : 2;
    }

    public interface Context
    {
        Edge intersectAny(Edge edge);
    }

    public record Vertex(double x, double y, double angle, double length, int distance) {}

    public static class Edge
    {
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

        public MidpointFractal fractal(RandomSource random, int bisections)
        {
            return new MidpointFractal(random, bisections, source.x, source.y, drain.x, drain.y);
        }
    }

    /**
     * A step based builder for rivers
     */
    public static class Builder implements Context
    {
        private final Queue<Edge> branchQueue;
        private final RandomSource random;

        private final List<Edge> edges;
        private final List<Edge> startEdges;
        private Edge endEdge;
        private final Vertex root;
        private final int depth;
        private final float waterVolumeCubicMeters;
        private final Function<Vertex, Region.Point> vertexToPoint;

        public Builder(RandomSource random, double drainX, double drainY, double angle, double length, int depth, double feather, float rainfall, Function<Vertex, Region.Point> vertexToPoint)
        {
            this.branchQueue = new LinkedList<>();
            this.random = random;

            this.edges = new ArrayList<>();
            this.startEdges = new ArrayList<>();
            this.root = new Vertex(drainX, drainY, angle, length, 0);
            this.depth = depth;
            this.waterVolumeCubicMeters = rainfall * (128f * 128f / 1000f);
            this.vertexToPoint = vertexToPoint;
        }

        /**
         * Builds the initial branch for a river
         *
         * @return {@code true} if the initial branch reached a sufficient length
         */
        private boolean buildInitialBranch(Context context)
        {

            int prevBiomeAltitude = Integer.MAX_VALUE;
            int prevDistanceToOcean = Integer.MAX_VALUE;

            Vertex prev = root;
            int length  = 3000;
            for (int i = 0; i < length; i++)
            {
                Vertex next = computeNext(prev, prev.length, prev.distance);

                Region.Point nextPoint = vertexToPoint.apply(next);
                if (nextPoint == null) {
                    continue;
                }

                if (nextPoint.mountain()) {
                    continue;
                }

                if (nextPoint.volcanic()) {
                    continue;
                }

                if (nextPoint.biomeAltitude > prevBiomeAltitude) {
                    continue;
                }

                if (nextPoint.distanceToOcean <= 3 && nextPoint.distanceToOcean > prevDistanceToOcean && prevDistanceToOcean != -2 && nextPoint.distanceToOcean != -1) { //
                    continue;
                }

                prevBiomeAltitude = nextPoint.biomeAltitude;
                prevDistanceToOcean = nextPoint.distanceToOcean;

                Edge nextEdge = new Edge(prev, next, this);
                Edge intersected = context.intersectAny(nextEdge);
                if (intersected != null) {
                    if (intersected.river != this) {
                        Edge maybeDownstream = intersected.downstreamEdge != null ? intersected.downstreamEdge : intersected;

                        nextEdge = new Edge(prev, intersected.drain, this, maybeDownstream);
                        edges.add(nextEdge);

                        if (isRiverToShort())
                        {
                            intersected.addWaterflowSource(waterVolumeCubicMeters);
                            edges.clear();
                        } else {
                            annotateDownstream();
                            edges.getFirst().addWaterflowSource(waterVolumeCubicMeters);

                            // transfer to other river
                            intersected.river.startEdges.add(edges.getFirst());
                            edges.forEach(edge -> edge.setRiver(intersected.river));
                            intersected.river.edges.addAll(edges);
                            edges.clear();
                        }

                        return true;
                    } else {
                        continue;
                    }
                }

                edges.add(nextEdge);
                prev = next;

                if (!nextPoint.land() && !nextPoint.shore()) {
                    annotateDownstream();
                    edges.getFirst().addWaterflowSource(waterVolumeCubicMeters);
                    startEdges.add(edges.getFirst());
                    endEdge = edges.getLast();

                    return true;
                }
            }
            edges.clear();
            return false;
        }

        // Do a check to ensure that the river is of sufficient size and length, and if not, discard it
        private boolean isRiverToShort() {
            return edges.size() < MIN_RIVER_EDGE_COUNT;
        }

        private void annotateDownstream() {
            for (int j = 0; j < edges.size() - 1; j++) {
                edges.get(j).setDownstreamEdge(edges.get(j + 1));
            }
        }

        @Override
        @Nullable
        public Edge intersectAny(Edge edge)
        {
            for (Edge e : edges)
            {
                if (e.drain != edge.source && ((distance(e, edge.drain) < 2.8f && edge.river == e.river) || (distance(e, edge.drain) < 16f && edge.river != e.river) || intersect(e.source, e.drain, edge.source, edge.drain)))
                {
                    return e;
                }
            }
            return null;
        }

        private Vertex computeNext(Vertex prev, double length, int distance)
        {
            double nextAngle = distance == 0 ?
                prev.angle() : // For distance = 0, this is the mouth of a river, and we want to use the computed 'best' start angle directly
                prev.angle() + (random.nextDouble() * 0.5f + 0.2f) * (random.nextBoolean() ? 1 : -1);
            double nextLength = length * (random.nextDouble() * 0.08f + 0.92f);

            // Extend in the direction of the next angle
            double dx = Mth.cos((float) nextAngle) * nextLength, dy = Mth.sin((float) nextAngle) * nextLength;
            double x = prev.x() + dx, y = prev.y() + dy;

            return new Vertex(x, y, nextAngle, nextLength, distance + 1);
        }
    }

    public static class MultiParallelBuilder implements Context
    {
        private final List<Builder> builders;

        public MultiParallelBuilder()
        {
            this.builders = new ArrayList<>();
        }

        public Context add(Builder builder)
        {
            builders.add(builder);
            return this;
        }

        public <E> List<E> build(Function<Edge, E> map)
        {
            // Use a heap, sorted by total river length (edge count), so we prioritize building large rivers, and discarding short ones.
            final PriorityQueue<Builder> working = new PriorityQueue<>(Comparator.comparing(b -> -b.edges.size() - 10 * b.branchQueue.size()));
            int i = 0;
            for (Builder builder : builders)
            {
                if (builder.buildInitialBranch(this))
                {
                    working.offer(builder);
                }
            }

            log.info("points processed {} of {}", working.size(), builders.size());

            return builders.stream()
                .flatMap(e -> e.edges.stream().map(map))
                .toList();
        }

        @Override
        @Nullable
        public Edge intersectAny(Edge edge)
        {
            for (Builder builder : builders)
            {
                Edge intersected = builder.intersectAny(edge);
                if (intersected != null)
                {
                    return intersected;
                }
            }
            return null;
        }

        protected boolean isLegal(Vertex prev, Vertex vertex)
        {
            return true;
        }
    }
}
