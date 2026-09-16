/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river;

import net.minecraft.util.Mth;

public final class RiverHelpers
{
    /**
     * @return The shortest square euclidean distance between the point (px, py), and the line segment described by (vx, vy) - (wx, wy).
     */
    public static double distancePointToLineSq(double vx, double vy, double wx, double wy, double px, double py)
    {
        double t = projectAlongLine(vx, vy, wx, wy, px, py);
        double x0 = vx + t * (wx - vx);
        double y0 = vy + t * (wy - vy);
        return norm2(x0 - px, y0 - py);
    }

    public static double projectAlongLine(double vx, double vy, double wx, double wy, double px, double py)
    {
        // Return minimum distance between line segment vw and point p, i.e. |w - v|^2
        double l2 = norm2(vx - wx, vy - wy);
        if (l2 == 0)
        {
            return l2;
        }
        // Consider the line extending the segment, parameterized as v + t (w - v).
        // We find projection of point p onto the line.
        // It falls where t = [(p-v) . (w-v)] / |w-v|^2
        // We clamp t from [0,1] to handle points outside the segment vw.
        return Mth.clamp((((px - vx) * (wx - vx)) + ((py - vy) * (wy - vy))) / l2, 0, 1);
    }

    /**
     * @return The euclidean norm of the vector (x, y).
     */
    public static double norm2(double x, double y)
    {
        return x * x + y * y;
    }

    /**
     * @return The infinity norm of the vector (x, y).
     */
    public static double normInf(double x, double y)
    {
        return Math.max(Math.abs(x), Math.abs(y));
    }

    /**
     * Packs a coordinate pair into a long based on their respective grid positions.
     */
    public static long pack(double x, double y)
    {
        return pack(floor(x), floor(y));
    }

    /**
     * Packs a coordinate pair into a long.
     */
    public static long pack(int x, int y)
    {
        return (x & 0xffffffffL) | (((long) y) << 32);
    }

    /**
     * Unpacks the x coordinate from a packed long.
     */
    public static int unpackX(long key)
    {
        return (int) key;
    }

    /**
     * Unpacks the y coordinate from a packed long.
     */
    public static int unpackZ(long key)
    {
        return (int) (key >> 32);
    }

    /**
     * @return The greatest integer x s.t. x < f.
     */
    public static int floor(double f)
    {
        return f >= 0 ? (int) f : (int) f - 1;
    }

    /**
     * @return The shortest square distance between a point {@code vertex} and the line segment {@code edge}
     */
    public static double distanceSq(River.Edge edge, River.Vertex vertex) {
        return distancePointToLineSq(edge.drain.x(), edge.drain.y(), edge.source.x(), edge.source.y(), vertex.x(), vertex.y());
    }

    public static double distanceVertex(River.Vertex vertex, River.Vertex otherVertex) {
        return Math.sqrt(Math.pow(vertex.x() - otherVertex.x(), 2) + Math.pow(vertex.y() - otherVertex.y(), 2));
    }

    public static double distanceVertexFastSquared(River.Vertex vertex, River.Vertex otherVertex) {
        double x = vertex.x() - otherVertex.x();
        double y = vertex.y() - otherVertex.y();
        return x * x + y * y;
    }

    /**
     * @return {@code true} if the lines described by (p1, q1), and (p2, q2) intersect.
     */
    public static boolean intersect(River.Vertex p1, River.Vertex q1, River.Vertex p2, River.Vertex q2) {
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
    private static boolean intersectCollinear(River.Vertex p, River.Vertex q, River.Vertex r) {
        return q.x() <= Math.max(p.x(), r.x()) && q.x() >= Math.min(p.x(), r.x()) && q.y() <= Math.max(p.y(), r.y()) && q.y() >= Math.min(p.y(), r.y());
    }

    /**
     * @return The orientation of three points (p, q, r) on a plane. 0 = collinear, 1 = clockwise, 2 = anticlockwise.
     */
    private static int orientation(River.Vertex p, River.Vertex q, River.Vertex r) {
        final double value = (q.y() - p.y()) * (r.x() - q.x()) - (q.x() - p.x()) * (r.y() - q.y());
        if (value == 0) {
            return 0;
        }
        return value > 0 ? 1 : 2;
    }
}
