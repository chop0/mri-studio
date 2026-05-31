package ax.xz.mri.ui.canvas;

import ax.xz.mri.model.simulation.Vec3;

/**
 * Immutable orbit-camera snapshot shared by every {@link OrbitView3D}.
 *
 * <p>Bundles the orthographic projection (via {@link Projection}) and its
 * inverse so the studio's 3-D canvases compute screen ↔ world mappings one
 * way. World coordinates are lab-frame metres; the camera normalises them by
 * {@link #halfExtentM} so the unit cube {@code [-1,1]³} frames the region of
 * interest. {@code scale} is pixels per normalised unit and {@code (cx, cy)}
 * the screen centre.
 */
public record Camera3D(double theta, double phi, double zoom,
                       double scale, double cx, double cy, double halfExtentM) {

    /** Metres → normalised-cube units. */
    public double worldScale() { return 1.0 / Math.max(1e-30, halfExtentM); }

    /** Project normalised cube coordinates ({@code [-1,1]³}) → {@code [screenX, screenY, depth]}. */
    public double[] projectNorm(double nx, double ny, double nz) {
        return Projection.project(nx, ny, nz, theta, phi, scale, cx, cy);
    }

    /** Project a lab-frame point in metres → {@code [screenX, screenY, depth]}. */
    public double[] projectMetres(double xMetres, double yMetres, double zMetres) {
        double ws = worldScale();
        return Projection.project(xMetres * ws, yMetres * ws, zMetres * ws, theta, phi, scale, cx, cy);
    }

    public double[] projectMetres(Vec3 p) { return projectMetres(p.x(), p.y(), p.z()); }

    /**
     * Inverse projection: the lab-frame point (metres) at normalised depth
     * {@code depthNorm} (0 = through the origin) whose projection lands at
     * screen {@code (screenX, screenY)}.
     */
    public Vec3 worldAtScreen(double screenX, double screenY, double depthNorm) {
        double ct = Math.cos(theta), st = Math.sin(theta);
        double cp = Math.cos(phi),   sp = Math.sin(phi);
        double dx = (screenX - cx) / scale;
        double dy = (screenY - cy) / scale;
        // dy + depthNorm·cp = sp·(mx·st + my·ct); solve the 2×2 (det = 1).
        double dySp = dy + depthNorm * cp;
        double mxStPlusMyCt = sp == 0 ? 0 : dySp / sp;
        double mx = ct * dx + st * mxStPlusMyCt;
        double my = -st * dx + ct * mxStPlusMyCt;
        return new Vec3(mx * halfExtentM, my * halfExtentM, depthNorm * halfExtentM);
    }

    /**
     * The lab-frame delta (metres) corresponding to a screen drag
     * {@code (dxScreen, dyScreen)} in the camera-facing plane — the inverse of
     * the forward projection's screen-basis vectors, scaled back to metres.
     */
    public Vec3 screenDeltaToWorld(double dxScreen, double dyScreen) {
        double ct = Math.cos(theta), st = Math.sin(theta);
        double cp = Math.cos(phi),   sp = Math.sin(phi);
        double ndx = dxScreen / scale;
        double ndy = dyScreen / scale;
        return new Vec3(
            (ct * ndx + st * sp * ndy) * halfExtentM,
            (-st * ndx + ct * sp * ndy) * halfExtentM,
            (-cp * ndy) * halfExtentM);
    }
}
