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
        try {
            try {
                if(scenario!=null && Boolean.parseBoolean(evalString("String(!!window.__guestPickerTrace)")))captureGuestTrace("scenario-end");
            } catch(Exception diagnostic) {android.util.Log.w(TAG,"guest end trace unavailable");}
            super.closeApp();
        } finally {GoogleSyncEnvironment.resetForTesting();}
    }

    @Test public void guestSshConnectsWithoutGoogleOrVaultAndTypesIntoRealHost() throws Exception {
        awaitJsTrue("!!document.querySelector('[data-testid=server-use-local]')");
        assertFalse("guest entry never starts with a vault prompt",exists("[data-testid=vault-unlock-gate]"));
        click("[data-testid=server-use-local]");
        awaitJsTrue("document.querySelector('.trusted-servers')?.textContent.includes('SSH without an account')");
        screenshot("j21-guest-local-entry");
        installGuestPickerTrace();
        try {
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
        } catch(Throwable failure) {
            // Capture this scenario while still alive, not the suite's later screen.
            screenshot("j21-guest-failure-current");
            try {captureGuestTrace("failure");} catch(Exception diagnostic) {android.util.Log.w(TAG,"guest failure trace unavailable");}
            throw failure;
        }
    }

    @Override protected void tapHost(String name) throws Exception {
        if(!name.endsWith("-guest")){super.tapHost(name);return;}
        // Exercise the supported Reload control while the real native SSH
        // promise is pending; never replace a store snapshot through JS.
        evalString("(()=>{const row=[...document.querySelectorAll('.host-row')].find(n=>n.textContent.includes("+org.json.JSONObject.quote(name)+"));"
            +"if(!row)throw new Error('guest fixture row missing');row.click();const reload=document.querySelector('button[aria-label=\"Reload hosts\"]');"
            +"if(!reload||reload.disabled)throw new Error('Reload hosts must be reachable while dialing');reload.click();return 'dial-and-reload';})()");
    }

    private void captureGuestTrace(String stage) throws Exception {
        org.json.JSONObject trace=new org.json.JSONObject(evalString("JSON.stringify(window.__guestPickerTrace.finish())"));
        org.json.JSONArray events=trace.getJSONArray("events");trace.remove("events");trace.put("stage",stage)
            .put("providerCalls",googleCalls.get()).put("accountHttp",accountHttp.get());
        for(int i=0;i<events.length();i++)android.util.Log.i(TAG,"GUEST_PICKER_EVENT "+events.getJSONObject(i));
        android.util.Log.i(TAG,"GUEST_PICKER_STATE_TRACE "+trace);
    }

    /** Observe public view-store identity/timing and bridge outcomes, without changing routing or auth. */
    private void installGuestPickerTrace() throws Exception {
        evalString("(()=>{const events=[],ids=new WeakMap();let next=0,clicked=null;const id=o=>{if(!o||typeof o!=='object')return null;if(!ids.has(o))ids.set(o,++next);return ids.get(o);};"
            +"const provides=document.querySelector('#app')?.__vue_app__?._context?.provides;const pinia=provides&&Reflect.ownKeys(provides).map(k=>provides[k]).find(v=>v?._s instanceof Map);const connection=pinia?._s.get('connection');"
            +"const row=h=>h?{ref:id(h),stableId:h.id??null,name:h.name,hostname:h.hostname,port:h.port,user:h.user,markers:['gateway','gatewayAuth','link','trustedVault'].filter(k=>Object.prototype.hasOwnProperty.call(h,k))}:null;"
            +"const snap=(event,detail={})=>{if(events.length>=160)return;events.push({event,detail,at:performance.now(),path:location.pathname,hash:location.hash,clicked:row(clicked),clickedInCurrent:!!clicked&&!!connection?.hosts.some(h=>h===clicked),hosts:connection?.hosts.map(row),active:row(connection?.activeHost),state:connection?.state,connectionId:connection?.connectionId,routeWorkspace:!!document.querySelector('button[title=\"Back to hosts\"]'),vaultGate:!!document.querySelector('[data-testid=vault-unlock-gate]'),terminalPresent:!!document.querySelector('.xterm-screen'),journal:(window.__pocketshellConnectionJournal??[]).slice(-4).map(e=>({phase:e.phase,selectedId:e.selectedId,hasError:!!e.error}))});};"
            +"document.addEventListener('click',e=>{const element=e.target.closest?.('.host-row');if(!element)return;const name=element.querySelector('.host-name')?.textContent;clicked=connection?.hosts.find(h=>h.name===name)??null;snap('host-click');},true);"
            +"connection?.$subscribe(()=>snap('connection-store'),{flush:'sync',detached:true});"
            +"for(const method of ['pushState','replaceState']){const call=history[method].bind(history);history[method]=(...args)=>{const result=call(...args);snap('history-'+method);return result;};}"
            +"const native=window.Capacitor.nativePromise.bind(window.Capacitor);window.Capacitor.nativePromise=(plugin,method,args)=>{const observe=(plugin==='SshCapability'&&method==='connect')||(plugin==='GoogleSync'&&['accountId','status'].includes(method));if(!observe)return native(plugin,method,args);snap(plugin+'.'+method+':invoke');return native(plugin,method,args).then(reply=>{const detail=plugin==='GoogleSync'?{accountPresent:!!reply?.accountId,signedIn:reply?.signedIn??null}:{};snap(plugin+'.'+method+':fulfilled',detail);queueMicrotask(()=>snap(plugin+'.'+method+':after'));return reply;},error=>{const code=/^[A-Z][A-Z0-9_]{0,63}$/.test(error?.code??'')?error.code:'OTHER';snap(plugin+'.'+method+':rejected:'+code);throw error;});};"
            +"window.__guestPickerTrace={finish:()=>{snap('failure-capture');return {piniaFound:!!pinia,storeIds:pinia?[...pinia._s.keys()]:[],events,dom:{heading:document.querySelector('h1')?.textContent,localChoice:document.querySelector('.trusted-servers')?.textContent.includes('SSH without an account'),rows:[...document.querySelectorAll('.host-row')].map(e=>({name:e.querySelector('.host-name')?.textContent,disabled:e.disabled})),disconnect:!!document.querySelector('.disconnect'),errorPresent:!!document.querySelector('.picker p.error'),innerWidth,innerHeight}};}};snap('installed');return 'observing';})()");
    }
    @Test
    @Override
    public void hostAddedThroughFormConnectsToFixture() throws Exception {
        super.hostAddedThroughFormConnectsToFixture();
    }

}
