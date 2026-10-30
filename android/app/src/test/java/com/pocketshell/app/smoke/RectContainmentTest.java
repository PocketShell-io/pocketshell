package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Pins the shared fast-key containment oracle used by
 * {@code JsFastKeysDockerJourneyTest} (#3055): sub-pixel layout rounding passes,
 * a genuinely clipped key fails.
 */
public final class RectContainmentTest {
    private static JSONObject rect(double left, double top, double right, double bottom) throws Exception {
        return new JSONObject().put("left", left).put("top", top).put("right", right).put("bottom", bottom)
                .put("width", right - left).put("height", bottom - top);
    }

    /** Release validation run 37319816493: ctrl-x right 398.381 vs rail right 398.190 at scrollLeft 167.619. */
    private static final double RAIL_LEFT = 0.0;
    private static final double RAIL_RIGHT = 398.190;
    private static final double RAIL_TOP = 700.0;
    private static final double RAIL_BOTTOM = 748.0;

    @Test
    public void sharesTheSiblingChecksHalfPixelTolerance() {
        assertEquals(0.5, RectContainment.SUBPIXEL_TOLERANCE_PX, 0.0);
    }

    @Test
    public void reportedCtrlXEndpointSubpixelOverhangIsInsideTheRail() throws Exception {
        JSONObject rail = rect(RAIL_LEFT, RAIL_TOP, RAIL_RIGHT, RAIL_BOTTOM);
        JSONObject ctrlX = rect(398.381 - 48.0, RAIL_TOP, 398.381, RAIL_BOTTOM);
        assertTrue("a full 48dp key 0.19px past the rail edge is layout rounding, not clipping",
                RectContainment.insideWithTolerance(ctrlX, rail));
    }

    @Test
    public void overhangAtTheToleranceBoundaryOnEveryEdgeIsInside() throws Exception {
        JSONObject rail = rect(10, 10, 110, 110);
        assertTrue(RectContainment.insideWithTolerance(rect(9.5, 9.5, 110.5, 110.5), rail));
        assertTrue(RectContainment.insideWithTolerance(rect(10, 10, 110, 110), rail));
    }

    @Test
    public void genuinelyClippedKeyTwoPixelsOutsideTheRailFailsOnEveryEdge() throws Exception {
        JSONObject rail = rect(RAIL_LEFT, RAIL_TOP, RAIL_RIGHT, RAIL_BOTTOM);
        assertFalse("right-clipped key",
                RectContainment.insideWithTolerance(rect(RAIL_RIGHT + 2 - 48, RAIL_TOP, RAIL_RIGHT + 2, RAIL_BOTTOM), rail));
        assertFalse("left-clipped key",
                RectContainment.insideWithTolerance(rect(RAIL_LEFT - 2, RAIL_TOP, RAIL_LEFT + 46, RAIL_BOTTOM), rail));
        assertFalse("top-clipped key",
                RectContainment.insideWithTolerance(rect(100, RAIL_TOP - 2, 148, RAIL_BOTTOM - 2), rail));
        assertFalse("bottom-clipped key",
                RectContainment.insideWithTolerance(rect(100, RAIL_TOP + 2, 148, RAIL_BOTTOM + 2), rail));
    }

    @Test
    public void justPastTheToleranceIsClipped() throws Exception {
        JSONObject rail = rect(10, 10, 110, 110);
        assertFalse(RectContainment.insideWithTolerance(rect(20, 20, 110.51, 100), rail));
        assertFalse(RectContainment.insideWithTolerance(rect(9.49, 20, 100, 100), rail));
    }
}
