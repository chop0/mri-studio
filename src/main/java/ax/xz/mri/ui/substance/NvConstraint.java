package ax.xz.mri.ui.substance;

import ax.xz.mri.model.simulation.Vec3;

/**
 * Geometric constraint that restricts NV-centre placement and dragging in the
 * {@link NvScatter3DCanvas}. The default is {@link None} — every NV moves
 * freely in 3-D. Selecting a plane or axis constraint snaps drags and Add
 * clicks to that surface.
 *
 * <p>Plane constraints are described by an axis (the plane's normal) and a
 * value on that axis. Axis constraints are described by a line through a
 * fixed (a, b) on the other two axes.
 *
 * <h3>Why a sealed type</h3>
 * The canvas's drag math switches on the constraint kind once per drag, so the
 * polymorphism stays compile-time. Pattern matching exhaustively over the
 * permits list lets the compiler enforce that every new constraint kind gets a
 * matching projection.
 */
public sealed interface NvConstraint
    permits NvConstraint.None,
            NvConstraint.PlaneX, NvConstraint.PlaneY, NvConstraint.PlaneZ,
            NvConstraint.LineX,  NvConstraint.LineY,  NvConstraint.LineZ {

    /** Project an arbitrary world position onto this constraint surface. */
    Vec3 project(Vec3 world);

    /** Display name shown in the dropdown. */
    String displayName();

    /** No constraint — every NV moves freely in 3-D. */
    record None() implements NvConstraint {
        @Override public Vec3 project(Vec3 world) { return world; }
        @Override public String displayName() { return "None"; }
    }

    /** Plane perpendicular to the X axis at {@code x = x0}. */
    record PlaneX(double x0) implements NvConstraint {
        @Override public Vec3 project(Vec3 w) { return new Vec3(x0, w.y(), w.z()); }
        @Override public String displayName() { return String.format("X = %.3g µm", x0 * 1e6); }
    }

    /** Plane perpendicular to the Y axis at {@code y = y0}. */
    record PlaneY(double y0) implements NvConstraint {
        @Override public Vec3 project(Vec3 w) { return new Vec3(w.x(), y0, w.z()); }
        @Override public String displayName() { return String.format("Y = %.3g µm", y0 * 1e6); }
    }

    /** Plane perpendicular to the Z axis at {@code z = z0}. */
    record PlaneZ(double z0) implements NvConstraint {
        @Override public Vec3 project(Vec3 w) { return new Vec3(w.x(), w.y(), z0); }
        @Override public String displayName() { return String.format("Z = %.3g nm", z0 * 1e9); }
    }

    /** Line along X, fixed {@code (y, z) = (y0, z0)}. */
    record LineX(double y0, double z0) implements NvConstraint {
        @Override public Vec3 project(Vec3 w) { return new Vec3(w.x(), y0, z0); }
        @Override public String displayName() { return String.format("Line X (y=%.3g µm, z=%.3g nm)", y0*1e6, z0*1e9); }
    }

    /** Line along Y, fixed {@code (x, z) = (x0, z0)}. */
    record LineY(double x0, double z0) implements NvConstraint {
        @Override public Vec3 project(Vec3 w) { return new Vec3(x0, w.y(), z0); }
        @Override public String displayName() { return String.format("Line Y (x=%.3g µm, z=%.3g nm)", x0*1e6, z0*1e9); }
    }

    /** Line along Z, fixed {@code (x, y) = (x0, y0)}. */
    record LineZ(double x0, double y0) implements NvConstraint {
        @Override public Vec3 project(Vec3 w) { return new Vec3(x0, y0, w.z()); }
        @Override public String displayName() { return String.format("Line Z (x=%.3g µm, y=%.3g µm)", x0*1e6, y0*1e6); }
    }
}
