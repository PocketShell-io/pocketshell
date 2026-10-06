package com.pocketshell.app.smoke;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Sub-pixel tolerant rectangle containment for packaged fast-key geometry
 * oracles (#3055).
 *
 * <p>WebView {@code getBoundingClientRect()} values are fractional: a rail that
 * settles at a fractional {@code scrollLeft} (e.g. 167.619) can report a fully
 * visible 48dp key a few tenths of a pixel past the rail edge. An exact
 * comparison turns that rounding into an intermittent "key clipped" failure.
 * Every fast-key containment check uses this one helper so they share the same
 * {@link #SUBPIXEL_TOLERANCE_PX}, while a genuinely clipped key (more than the
 * tolerance outside, e.g. 2px) still fails.
 *
 * <p>Lives in {@code src/sharedTest} so the JVM unit gate
 * ({@code RectContainmentTest}) pins the tolerance and the clipped-key red case
 * without an emulator.
 */
public final class RectContainment {
    /** Same 0.5 CSS px allowance the sibling fast-key checks always used. */
    public static final double SUBPIXEL_TOLERANCE_PX = 0.5;

    private RectContainment() {}

    /** True when {@code inner} lies inside {@code outer}, allowing sub-pixel rounding on each edge. */
    public static boolean insideWithTolerance(
            double innerLeft, double innerTop, double innerRight, double innerBottom,
            double outerLeft, double outerTop, double outerRight, double outerBottom) {
        return innerLeft >= outerLeft - SUBPIXEL_TOLERANCE_PX
                && innerRight <= outerRight + SUBPIXEL_TOLERANCE_PX
                && innerTop >= outerTop - SUBPIXEL_TOLERANCE_PX
                && innerBottom <= outerBottom + SUBPIXEL_TOLERANCE_PX;
    }

    /** {@link #insideWithTolerance} over {@code {left,top,right,bottom}} JSON bounds. */
    public static boolean insideWithTolerance(JSONObject inner, JSONObject outer) throws JSONException {
        return insideWithTolerance(
                inner.getDouble("left"), inner.getDouble("top"), inner.getDouble("right"), inner.getDouble("bottom"),
                outer.getDouble("left"), outer.getDouble("top"), outer.getDouble("right"), outer.getDouble("bottom"));
    }
}
