import { describe, expect, it, vi } from 'vitest';
import { SHARED_ROUTE_NAMES } from '@ui/app/routes';
import { INSET_VARIABLES } from '@ui/app/insets';
import { ACCOUNT_ROUTE, ADD_HOST_ROUTE, createSharedAppRouter } from '@/sharedApp/router';
import { ANDROID_INSETS, ANDROID_MONO_FALLBACK, applyAndroidInsets, bindAndroidBottomInsets } from '@/sharedApp/androidShell';

/**
 * Android on the shared app shell (#2949): the router is core's
 * createAppRoutes() plus the one Android route, and the platform plugs its
 * insets and mono stack into the shared root rather than carrying its own
 * copy of the root's watchers.
 */
describe('Android shared app shell', () => {
  it('uses native navigation clearance without double-padding a resized IME viewport and disposes its listener',async()=>{
    vi.useFakeTimers();
    try {
      const written=new Map<string,string>();let event!:()=>void;
      let state={supported:true,imeVisible:false,safeBottomDp:24};
      const remove=vi.fn(async()=>{}),getState=vi.fn(async()=>state);
      const stop=bindAndroidBottomInsets({getState,addListener:async(_,handler)=>{event=handler;return {remove};}},
        {style:{setProperty:(name,value)=>{written.set(name,String(value));},removeProperty:()=>''}});
      await vi.advanceTimersByTimeAsync(121);expect(written.get('--ps-inset-bottom')).toBe('24px');
      state={supported:true,imeVisible:true,safeBottomDp:24};event();await vi.advanceTimersByTimeAsync(121);
      expect(written.get('--ps-inset-bottom')).toBe('0px');
      state={supported:true,imeVisible:false,safeBottomDp:36};event();await vi.advanceTimersByTimeAsync(121);
      expect(written.get('--ps-inset-bottom')).toBe('36px');
      stop();await Promise.resolve();event();await vi.advanceTimersByTimeAsync(121);
      expect(remove).toHaveBeenCalledTimes(1);expect(getState).toHaveBeenCalledTimes(3);
    } finally {vi.useRealTimers();}
  });

  it('late native inset reads after disposal cannot mutate the shared viewport',async()=>{
    vi.useFakeTimers();
    try {
      let finish!:(value:{supported:boolean;imeVisible:boolean;safeBottomDp:number})=>void;
      const setProperty=vi.fn();
      const stop=bindAndroidBottomInsets({getState:()=>new Promise(resolve=>{finish=resolve;}),addListener:async()=>({remove:async()=>{}})},
        {style:{setProperty,removeProperty:()=>''}});
      await vi.advanceTimersByTimeAsync(121);stop();finish({supported:true,imeVisible:false,safeBottomDp:24});
      await vi.advanceTimersByTimeAsync(121);expect(setProperty).not.toHaveBeenCalled();
    } finally {vi.useRealTimers();}
  });
  it('routes by the shared map plus exactly the Android host form and account screen', async () => {
    const router = createSharedAppRouter();
    const names = router.getRoutes().map((r) => r.name).filter(Boolean).map(String).sort();
    expect(names).toEqual([...SHARED_ROUTE_NAMES, 'android-hosts', 'android-account'].sort());
    expect(router.resolve(ADD_HOST_ROUTE).name).toBe('android-hosts');
    expect(router.resolve(ACCOUNT_ROUTE).name).toBe('android-account');
    expect(router.resolve('/host/dev/folder/~%2Fgit%2Fx').name).toBe('folder');
    await router.push('/nowhere');
    expect(router.currentRoute.value.name).toBe('hosts');
  });

  it('writes the system-bar and landscape side insets into the shared inset contract', () => {
    const written = new Map<string, string>();
    applyAndroidInsets({
      style: {
        setProperty: (name: string, value: string | null) => void written.set(name, String(value)),
        removeProperty: (name: string) => {
          written.delete(name);
          return '';
        },
      },
    });
    expect(written.get(INSET_VARIABLES.top)).toBe('env(safe-area-inset-top, 0px)');
    expect(written.get(INSET_VARIABLES.bottom)).toBe('env(safe-area-inset-bottom, 0px)');
    expect(written.get(INSET_VARIABLES.left)).toBe('env(safe-area-inset-left, 0px)');
    expect(written.get(INSET_VARIABLES.right)).toBe('env(safe-area-inset-right, 0px)');
    // The keyboard inset is left at the shared 0px default until A3 feeds it.
    expect(written.has(INSET_VARIABLES.keyboard)).toBe(false);
    expect(Object.keys(ANDROID_INSETS).sort()).toEqual(['bottom', 'left', 'right', 'top']);
  });

  it('hands the shared root a phone mono stack, not the desktop Consolas default', () => {
    expect(ANDROID_MONO_FALLBACK).toBe('ui-monospace, monospace');
  });
});
