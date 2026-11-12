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
import android.util.Log;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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
        AtomicReference<JSONObject> measured=new AtomicReference<>();
        AtomicReference<Boolean> contained=new AtomicReference<>(false);
        AtomicReference<Boolean> focused=new AtomicReference<>(false);
        CountDownLatch layoutCaptured=new CountDownLatch(1);
        scenario.onActivity(activity -> {
            try {
                WebView view=activity.getBridge().getWebView();
                focused.set(activity.hasWindowFocus() && view.hasWindowFocus());
                int[] origin=new int[2];view.getLocationOnScreen(origin);
                double scale=view.getWidth()/css.getDouble("innerWidth");
                Rect crop=new Rect(origin[0]+(int)Math.floor(css.getDouble("left")*scale),origin[1]+(int)Math.floor(css.getDouble("top")*scale),
                        origin[0]+(int)Math.ceil(css.getDouble("right")*scale),origin[1]+(int)Math.ceil(css.getDouble("bottom")*scale));
                Rect visible=new Rect();assertTrue(view.getGlobalVisibleRect(visible));
                Rect frame=new Rect();view.getWindowVisibleDisplayFrame(frame);
                contained.set(visible.contains(crop) && frame.contains(crop));
                JSONObject state=new JSONObject().put("name",name).put("css",css).put("scale",scale)
                    .put("viewWidth",view.getWidth()).put("viewHeight",view.getHeight()).put("originX",origin[0]).put("originY",origin[1])
                    .put("crop",crop.toShortString()).put("visible",visible.toShortString()).put("frame",frame.toShortString())
                    .put("contains",contained.get()).put("focused",focused.get()).put("density",view.getResources().getDisplayMetrics().density);
                WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(view);
                if(insets!=null) state.put("systemBars",insets.getInsets(WindowInsetsCompat.Type.systemBars()).toString())
                    .put("cutout",insets.getInsets(WindowInsetsCompat.Type.displayCutout()).toString())
                    .put("imeVisible",insets.isVisible(WindowInsetsCompat.Type.ime())).put("ime",insets.getInsets(WindowInsetsCompat.Type.ime()).toString());
                measured.set(state);Log.i("PocketshellGatewayLane","ONBOARDING_NATIVE_GEOMETRY "+state);
                view.evaluateJavascript("JSON.stringify((()=>{const rect=e=>{if(!e)return null;const r=e.getBoundingClientRect();return {left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height};};const screen=[...document.querySelectorAll('.xterm-screen')].find(e=>e.offsetParent!==null);const style=getComputedStyle(document.documentElement);return {innerWidth,innerHeight,dpr:devicePixelRatio,scrollX,scrollY,visualViewport:visualViewport?{width:visualViewport.width,height:visualViewport.height,offsetTop:visualViewport.offsetTop,offsetLeft:visualViewport.offsetLeft,scale:visualViewport.scale}:null,screen:rect(screen),xterm:rect(screen?.closest('.xterm')),terminalViewport:rect(screen?.closest('.xterm')?.querySelector('.xterm-viewport')),insets:{top:style.getPropertyValue('--ps-inset-top'),bottom:style.getPropertyValue('--ps-inset-bottom')},vault:rect(document.querySelector('[data-testid=vault-setup]'))};})())",
                    value->Log.i("PocketshellGatewayLane","ONBOARDING_DOM_GEOMETRY "+value));
                view.evaluateJavascript("JSON.stringify((()=>{const props=['height','min-height','max-height','box-sizing','display','position','flex','overflow','padding-top','padding-bottom'];"
                    +"const rules=[];const walk=rs=>{for(const r of rs){if(r.media&&!matchMedia(r.conditionText).matches)continue;if(r.selectorText&&r.style)rules.push(r);else if(r.cssRules)walk(r.cssRules);}};"
                    +"for(const s of document.styleSheets){try{walk(s.cssRules);}catch{}}const result=[];let e=[...document.querySelectorAll('.xterm-screen')].find(e=>e.offsetParent!==null)||document.querySelector('.vault-setup-dialog');"
                    +"while(e){const c=getComputedStyle(e),b=e.getBoundingClientRect();result.push({tag:e.tagName,id:e.id,classes:e.className,top:b.top,bottom:b.bottom,height:b.height,clientHeight:e.clientHeight,scrollHeight:e.scrollHeight,"
                    +"computed:Object.fromEntries(props.map(p=>[p,c.getPropertyValue(p)])),matched:rules.filter(r=>{try{return e.matches(r.selectorText);}catch{return false;}}).map(r=>({selector:r.selectorText,style:Object.fromEntries(props.filter(p=>r.style.getPropertyValue(p)).map(p=>[p,r.style.getPropertyValue(p)]))})).filter(r=>Object.keys(r.style).length)});e=e.parentElement;}return result;})())",
                    value->{try{org.json.JSONArray rows=new org.json.JSONArray((String)new org.json.JSONTokener(value).nextValue());
                        for(int i=0;i<rows.length();i++)Log.i("PocketshellGatewayLane","ONBOARDING_LAYOUT_ANCESTOR "+rows.getJSONObject(i));
                    }catch(Exception error){Log.e("PocketshellGatewayLane","layout capture failed",error);}finally{layoutCaptured.countDown();}});
                viewport.set(crop);
            } catch(org.json.JSONException e) {throw new IllegalStateException(e);}
        });
        assertTrue("computed layout ancestry captured before the frame",layoutCaptured.await(15,TimeUnit.SECONDS));
        Bitmap screen=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screen);Rect rect=viewport.get();assertNotNull(rect);
        try {
            export(screen,run,name+"-native-full");
            assertTrue("the real window owns focus: "+measured.get(),focused.get());
            assertTrue("captured surface must be visible above the actual keyboard/system bars: "+measured.get(),contained.get());
            assertTrue(new Rect(0,0,screen.getWidth(),screen.getHeight()).contains(rect));
            Bitmap terminal=Bitmap.createBitmap(screen,rect.left,rect.top,rect.width(),rect.height());
            try {export(terminal,run,name);} finally {terminal.recycle();}
        } finally {screen.recycle();}
    }

    private static void export(Bitmap bitmap,String run,String name) throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ContentValues value=new ContentValues();
        value.put(MediaStore.Images.Media.DISPLAY_NAME,name+".png");value.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
        value.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/PocketShell/Onboarding/"+run);
        value.put(MediaStore.Images.Media.IS_PENDING,1);
        Uri uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,value);assertNotNull(uri);
        try(OutputStream output=context.getContentResolver().openOutputStream(uri)) {assertNotNull(output);assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}
        value.clear();value.put(MediaStore.Images.Media.IS_PENDING,0);
        assertEquals(1,context.getContentResolver().update(uri,value,null,null));
    }
}
