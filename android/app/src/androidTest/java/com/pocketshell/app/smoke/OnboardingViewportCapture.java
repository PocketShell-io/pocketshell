package com.pocketshell.app.smoke;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pocketshell.app.MainActivity;
import org.json.JSONObject;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Crops the real full-device screenshot, never a separately rendered terminal stand-in. */
final class OnboardingViewportCapture {
    private OnboardingViewportCapture() {}
    static void capture(ActivityScenario<MainActivity> scenario,String geometry,String run,String name) throws Exception {
        assertTrue(run.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,48}"));
        assertTrue(name.matches("[a-z0-9-]+"));
        JSONObject css=new JSONObject(geometry);
        CountDownLatch painted=new CountDownLatch(1);
        scenario.onActivity(activity -> activity.getBridge().getWebView().postVisualStateCallback(SystemClock.uptimeMillis(),new WebView.VisualStateCallback() {
            @Override public void onComplete(long id) {painted.countDown();}
        }));
        assertTrue("terminal frame must paint",painted.await(15,TimeUnit.SECONDS));
        AtomicReference<Rect> viewport=new AtomicReference<>();
        scenario.onActivity(activity -> {
            try {
                WebView view=activity.getBridge().getWebView();
                assertTrue("the real window owns focus",activity.hasWindowFocus() && view.hasWindowFocus());
                int[] origin=new int[2];view.getLocationOnScreen(origin);
                double scale=view.getWidth()/css.getDouble("innerWidth");
                Rect crop=new Rect(origin[0]+(int)Math.floor(css.getDouble("left")*scale),origin[1]+(int)Math.floor(css.getDouble("top")*scale),
                        origin[0]+(int)Math.ceil(css.getDouble("right")*scale),origin[1]+(int)Math.ceil(css.getDouble("bottom")*scale));
                Rect visible=new Rect();assertTrue(view.getGlobalVisibleRect(visible));
                Rect frame=new Rect();view.getWindowVisibleDisplayFrame(frame);
                assertTrue("terminal must be visible above the actual keyboard/system bars",visible.contains(crop) && frame.contains(crop));
                viewport.set(crop);
            } catch(org.json.JSONException e) {throw new IllegalStateException(e);}
        });
        Bitmap screen=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screen);Rect rect=viewport.get();assertNotNull(rect);
        assertTrue(new Rect(0,0,screen.getWidth(),screen.getHeight()).contains(rect));
        Bitmap terminal=Bitmap.createBitmap(screen,rect.left,rect.top,rect.width(),rect.height());
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ContentValues value=new ContentValues();
        value.put(MediaStore.Images.Media.DISPLAY_NAME,name+".png");value.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
        value.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/PocketShell/Onboarding/"+run);
        value.put(MediaStore.Images.Media.IS_PENDING,1);
        Uri uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,value);assertNotNull(uri);
        try(OutputStream output=context.getContentResolver().openOutputStream(uri)) {assertNotNull(output);assertTrue(terminal.compress(Bitmap.CompressFormat.PNG,100,output));}
        finally {terminal.recycle();screen.recycle();}
        value.clear();value.put(MediaStore.Images.Media.IS_PENDING,0);
        assertEquals(1,context.getContentResolver().update(uri,value,null,null));
    }
}
