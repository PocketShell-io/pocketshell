package com.pocketshell.app.smoke;

import android.app.UiAutomation;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.platform.app.InstrumentationRegistry;

/**
 * The one physical-tap injector every packaged journey uses (#2884, #2946).
 *
 * <p>A tap is a touchscreen ACTION_DOWN and ACTION_UP whose timestamps are fixed
 * before either is injected: {@code eventTime(UP) = downTime + TAP_DURATION_MS}.
 * The down is queued without waiting for dispatch and the up follows at once,
 * synchronously, so the pair reaches the input dispatcher back to back.
 *
 * <p>The previous helpers stamped the up with {@code SystemClock.uptimeMillis()}
 * after a short sleep, or waited for a synchronous down. On a starved hosted
 * emulator either step can stall for most of a second. The tap then became a
 * long press: no click, and on the CI image a text selection that launched
 * Select-to-Speak (runs 36907806792, 36910611490, 36923516541).
 */
public final class PhysicalTap {
    /** Event-time gap between down and up; well under the long-press timeout. */
    public static final long TAP_DURATION_MS = 50;

    private PhysicalTap() {}

    /** Timing and dispatch evidence for one injected tap. */
    public static final class Result {
        public final long downTime;
        public final long upEventTime;
        public final long injectedAtUptime;
        public final long upReturnedAtUptime;
        public final boolean downInjected;
        public final boolean upInjected;

        Result(long downTime, long upEventTime, long injectedAtUptime, long upReturnedAtUptime,
               boolean downInjected, boolean upInjected) {
            this.downTime = downTime;
            this.upEventTime = upEventTime;
            this.injectedAtUptime = injectedAtUptime;
            this.upReturnedAtUptime = upReturnedAtUptime;
            this.downInjected = downInjected;
            this.upInjected = upInjected;
        }
    }

    /** Inject one tap at screen pixels {@code (x, y)}; throws if either event is refused. */
    public static Result tap(float x, float y) {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        long downTime = SystemClock.uptimeMillis();
        long upTime = downTime + TAP_DURATION_MS;
        MotionEvent down = touch(downTime, downTime, MotionEvent.ACTION_DOWN, x, y);
        MotionEvent up = touch(downTime, upTime, MotionEvent.ACTION_UP, x, y);
        boolean downInjected;
        boolean upInjected;
        long injectedAt = SystemClock.uptimeMillis();
        try {
            downInjected = automation.injectInputEvent(down, false);
            upInjected = automation.injectInputEvent(up, true);
        } finally {
            down.recycle();
            up.recycle();
        }
        long returnedAt = SystemClock.uptimeMillis();
        if (!downInjected || !upInjected) {
            throw new AssertionError("Android touchscreen tap must be injected: down=" + downInjected
                    + " up=" + upInjected + " at (" + x + ", " + y + ")");
        }
        return new Result(downTime, upTime, injectedAt, returnedAt, downInjected, upInjected);
    }

    /**
     * Regression probe only: the same stamped pair, but ACTION_UP is injected
     * {@code deliveryDelayMs} after ACTION_DOWN, as a starved emulator delivers
     * it. Journeys must use {@link #tap(float, float)}.
     */
    static Result tapWithDeliveryDelay(float x, float y, long deliveryDelayMs) {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        long downTime = SystemClock.uptimeMillis();
        long upTime = downTime + TAP_DURATION_MS;
        MotionEvent down = touch(downTime, downTime, MotionEvent.ACTION_DOWN, x, y);
        MotionEvent up = touch(downTime, upTime, MotionEvent.ACTION_UP, x, y);
        long injectedAt = SystemClock.uptimeMillis();
        boolean downInjected;
        boolean upInjected;
        try {
            downInjected = automation.injectInputEvent(down, false);
            SystemClock.sleep(deliveryDelayMs);
            upInjected = automation.injectInputEvent(up, true);
        } finally {
            down.recycle();
            up.recycle();
        }
        return new Result(downTime, upTime, injectedAt, SystemClock.uptimeMillis(), downInjected, upInjected);
    }

    /** A finger on the touchscreen, so WebView reports pointerType 'touch'. */
    static MotionEvent touch(long downTime, long eventTime, int action, float x, float y) {
        MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties()};
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coordinates = {new MotionEvent.PointerCoords()};
        coordinates[0].x = x;
        coordinates[0].y = y;
        coordinates[0].pressure = 1f;
        coordinates[0].size = 1f;
        return MotionEvent.obtain(downTime, eventTime, action, 1, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
    }
}
