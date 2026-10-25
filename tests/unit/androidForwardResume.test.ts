import { afterEach, describe, expect, it, vi } from 'vitest';
import { ConnectionController, type HostKeyTrustPin } from '@pocketshell/core';
import { createAndroidPlatform, FORWARD_RESUME_STORAGE_KEY, type AndroidPlatform } from '@/platform/android/androidApi';
import { AndroidHostStore } from '@/platform/android/hostStore';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { FakeNative, HOST_KEY, MemoryStorage, settle, target } from './support/androidFakeNative';

const instances: AndroidPlatform[]=[];
afterEach(() => { for (const instance of instances.splice(0)) instance.dispose(); vi.unstubAllGlobals(); });
const intent={name:'fixture',hostname:'fixture',port:2222,user:'u',hostId:target.hostId};
function fixture(enabled=true, pin: HostKeyTrustPin | null={kind:'wire-key',...HOST_KEY}) {
  const storage=new MemoryStorage(); vi.stubGlobal('localStorage',storage);
  const hosts=new AndroidHostStore({storage,readLegacyHosts:async()=>[]});
  hosts.save({name:'fixture',hostname:'fixture',port:2222,user:'u',keyHandleId:'fixture-key'});
  if (enabled) {
    storage.setItem(FORWARD_RESUME_STORAGE_KEY,JSON.stringify([intent]));
    storage.setItem(`pocketshell.android.forwards.${target.hostId}`,JSON.stringify({auto:true}));
  }
  const trust=createLocalTrustStore(storage);
  if (pin) void trust.record(target.hostId,pin);
  return {storage,hosts,trust};
}
function mount(f: ReturnType<typeof fixture>, native: FakeNative, gate: Promise<void>=Promise.resolve()) {
  let active: ((value:boolean)=>void) | undefined;
  const logs: string[]=[];
  const platform=createAndroidPlatform({
    hosts:f.hosts,
    createController:()=>{
      const capability=native.capability(); const connect=capability.connect;
      capability.connect=async(options)=>{ await gate; return connect(options); };
      return new ConnectionController({capability,trustStore:f.trust,retryDelaysMs:[0]});
    },
    backgroundGraceMs:()=>60000,addHostRoute:'/android/hosts',
    lifecycle:{onActiveChange:(handler)=>{active=handler;return()=>{active=undefined;};}},
    log:(entry)=>logs.push(entry.message),
  });
  instances.push(platform);
  return {platform,logs,resume:()=>active?.(true)};
}

describe('Android enabled forwarding host cold start',()=>{
  it('cold starts an explicitly enabled host and deduplicates a concurrent picker dial through one core owner',async()=>{
    const f=fixture(); const native=new FakeNative(); let release!:()=>void;
    const gate=new Promise<void>((resolve)=>{release=resolve;});
    const {platform,resume}=mount(f,native,gate);
    const manual=platform.api.ssh.connect({host:'fixture',port:2222,user:'u',hostAlias:'fixture'});
    await new Promise((resolve)=>setTimeout(resolve,10));
    release();
    const result=await manual;
    await settle(()=>native.connects.length===1);
    expect(result).toMatchObject({ok:true,connectionId:'android-1'});
    resume(); await new Promise((resolve)=>setTimeout(resolve,10));
    expect(native.connects).toHaveLength(1);
    expect(platform.hub.controllerOf(result.connectionId!)).not.toBeNull();
  });
  it('does not dial hosts without explicit enablement or after the user turns automatic forwarding off',async()=>{
    const f=fixture(false); const native=new FakeNative(); const first=mount(f,native);
    await new Promise((resolve)=>setTimeout(resolve,10)); expect(native.connects).toHaveLength(0);
    const result=await first.platform.api.ssh.connect({host:'fixture',port:2222,user:'u',hostAlias:'fixture'});
    await first.platform.api.forwards.startAuto(result.connectionId!);
    expect(JSON.parse(f.storage.getItem(FORWARD_RESUME_STORAGE_KEY)!)).toHaveLength(1);
    await first.platform.api.forwards.stopAuto(result.connectionId!);
    first.platform.dispose();
    const coldNative=new FakeNative(); mount(f,coldNative);
    await new Promise((resolve)=>setTimeout(resolve,10)); expect(coldNative.connects).toHaveLength(0);
  });
  it('uses normal core host-key trust on automatic startup and never silently accepts a first-contact key',async()=>{
    const f=fixture(true,null); const native=new FakeNative(); const {logs,platform}=mount(f,native);
    await settle(()=>logs.some((message)=>message.includes('not trusted')));
    expect(native.connects).toHaveLength(1);
    expect(platform.hub.controllerOf('android-1')).toBeNull();
    expect(await f.trust.get(target.hostId)).toBeNull();
  });
});
