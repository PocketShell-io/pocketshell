package com.pocketshell.app.smoke;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pocketshell.app.GoogleSyncEnvironment;
import com.pocketshell.app.SyncAuthException;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Packaged feature journey; the inherited oracle drives the real shared app and Docker host. */
public class J21HostAddConnectJourney extends SharedAppDockerJourneyTest {
    private final AtomicInteger googleCalls=new AtomicInteger();
    private final AtomicInteger accountHttp=new AtomicInteger();

    @Before @Override public void launchSharedApp() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteSharedPreferences("pocketshell-sync-auth");
        GoogleSyncEnvironment.installForTesting(activity -> new com.pocketshell.app.GoogleSyncSession.SignInProvider() {
            @Override public String obtainIdToken(boolean interactive) throws SyncAuthException {
                googleCalls.incrementAndGet();
                throw new SyncAuthException(SyncAuthException.UNAVAILABLE,"Guest journey must not sign in.");
            }
            @Override public void clearCredentialState() {}
        },(method,url,headers,body)->{
            accountHttp.incrementAndGet();
            throw new java.io.IOException("Guest journey must not contact the account service.");
        });
        super.launchSharedApp();
    }

    @After @Override public void closeApp() {
        try {super.closeApp();} finally {GoogleSyncEnvironment.resetForTesting();}
    }

    @Test public void guestSshConnectsWithoutGoogleOrVaultAndTypesIntoRealHost() throws Exception {
        awaitJsTrue("!!document.querySelector('[data-testid=server-use-local]')");
        assertFalse("guest entry never starts with a vault prompt",exists("[data-testid=vault-unlock-gate]"));
        click("[data-testid=server-use-local]");
        awaitJsTrue("document.querySelector('.trusted-servers')?.textContent.includes('SSH without an account')");
        screenshot("j21-guest-local-entry");
        fixtureHostAdded=false;
        openFixtureHost(sessionRun()+"-guest");
        awaitFolder(sessionRun()+"-a");
        openFolder(sessionRun()+"-a");
        awaitJsTrue(VISIBLE_TERMINAL+".includes('$')");
        typeLine("echo PSGUEST$((6*7))_"+sessionRun()+" | tee \"$HOME/ps-guest-"+sessionRun()+".txt\"");
        awaitTerminalLine("PSGUEST42_"+sessionRun());
        screenshot("j21-guest-real-terminal");
        OnboardingViewportCapture.capture(scenario,evalString("JSON.stringify((()=>{const r=[...document.querySelectorAll('.xterm-screen')].find(n=>n.offsetParent!==null).getBoundingClientRect();return {left:r.left,top:r.top,right:r.right,bottom:r.bottom,innerWidth:innerWidth};})())"),sessionRun(),"guest-terminal-viewport");
        android.util.Log.i(TAG,"GUEST_VISIBLE_TERMINAL "+evalString(VISIBLE_TERMINAL));
        assertEquals("no native Google account chooser or renewal",0,googleCalls.get());
        assertEquals("no account HTTP call",0,accountHttp.get());
        assertFalse("no vault prompt after real local SSH",exists("[data-testid=vault-unlock-gate]"));
    }
    @Test
    @Override
    public void hostAddedThroughFormConnectsToFixture() throws Exception {
        super.hostAddedThroughFormConnectsToFixture();
    }

}
