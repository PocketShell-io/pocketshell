import { createSSRApp } from 'vue';
import { renderToString } from 'vue/server-renderer';
import { beforeEach, describe, expect, it } from 'vitest';
import AndroidUploadChips from '../../src/sharedApp/AndroidUploadChips.vue';
import {
  androidUploads,
  queueUploads,
  rejectFiles,
  reportUploadProgress,
  resetAndroidUploads,
} from '../../src/platform/android/attachmentUploads';
import { attachmentTooLargeMessage } from '../../src/session/attachmentLimits';

async function render(): Promise<string> {
  return renderToString(createSSRApp(AndroidUploadChips));
}

beforeEach(() => resetAndroidUploads());

describe('Android composer upload chips', () => {
  it('shows an over-limit file on its own chip in plain words with a human size, never a byte count (#3191)', async () => {
    const size = 120 * 1024 * 1024;
    rejectFiles([{ name: 'IMG_0001.jpg', mimeType: 'image/jpeg', sizeBytes: size, message: attachmentTooLargeMessage('IMG_0001.jpg', 'image/jpeg', size) }]);

    const html = await render();

    expect(html).toContain('data-state="rejected"');
    expect(html).toContain('role="alert"');
    expect(html).toContain('This photo is 120.0 MB; the limit is 100.0 MB.');
    expect(html).toContain('aria-label="Dismiss IMG_0001.jpg"');
    expect(html).not.toContain(String(size));
    expect(html).not.toMatch(/\bbytes\b/);
    expect(html).not.toContain('data-testid="android-upload-progress"');
  });

  it('shows each uploading file with acknowledged progress and its own Cancel', async () => {
    const [first] = queueUploads([
      { name: 'PXL_1.jpg', mimeType: 'image/jpeg', sizeBytes: 1_700_000 },
      { name: 'PXL_2.jpg', mimeType: 'image/jpeg', sizeBytes: 700_000 },
    ]);
    reportUploadProgress(first!, 524_288);

    const html = await render();

    expect(html).toContain('data-testid="android-upload-progress"');
    expect(html).toContain('data-written="524288" data-total="2400000" data-file-count="2"');
    expect(html).toContain('512.0 KB of 1.6 MB');
    expect(html).toContain('Waiting…');
    expect(html).toContain('aria-label="Cancel upload of PXL_1.jpg"');
    expect(html).toContain('aria-label="Cancel upload of PXL_2.jpg"');
    expect(androidUploads.value).toHaveLength(2);
  });

  it('renders nothing when no upload is in flight or refused', async () => {
    expect(await render()).not.toContain('android-upload');
  });
});
