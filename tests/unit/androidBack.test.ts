import { afterEach, describe, expect, it, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import { createAppRoutes } from '@ui/app/routes';
import { registerDismissLayer } from '@ui/app/extensions';
import { MAINTENANCE_ROOT } from '@ui/app/maintenance';
import { handleAndroidBack } from '../../src/sharedApp/androidBack';

const cleanup: Array<() => void> = [];
afterEach(() => { while (cleanup.length) cleanup.pop()!(); });

const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('Android Back', () => {
  it('walks Ports, the workspace and the host list, then minimises instead of exiting', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: createAppRoutes() });
    await router.push(`/host/box/folder/${encodeURIComponent(MAINTENANCE_ROOT)}`);
    const minimize = vi.fn();
    const layerClosed = vi.fn();
    cleanup.push(registerDismissLayer(layerClosed));

    handleAndroidBack(router, minimize);
    expect(layerClosed).toHaveBeenCalledTimes(1);
    cleanup.pop()!();

    handleAndroidBack(router, minimize);
    await settle();
    expect(router.currentRoute.value.name).toBe('host-sessions');
    handleAndroidBack(router, minimize);
    await settle();
    expect(router.currentRoute.value.name).toBe('hosts');
    expect(minimize).not.toHaveBeenCalled();

    handleAndroidBack(router, minimize);
    expect(minimize).toHaveBeenCalledTimes(1);
  });
});
