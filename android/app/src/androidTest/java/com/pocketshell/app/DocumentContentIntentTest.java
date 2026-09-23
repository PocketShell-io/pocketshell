package com.pocketshell.app;

import static org.junit.Assert.assertEquals;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;

/** Packaged checks for the intent shapes returned by Android document and share providers. */
@RunWith(AndroidJUnit4.class)
public final class DocumentContentIntentTest {
    @Test
    public void singleOpenDocumentDataUriIsIncludedAndDeduplicated() {
        assertSingleOpenDocumentDataUriIsIncludedAndDeduplicated();
    }

    /** Shared assertion also invoked by the registered packaged-shell smoke class. */
    public static void assertSingleOpenDocumentDataUriIsIncludedAndDeduplicated() {
        Uri selected = Uri.parse("content://documents/document/one");
        Intent dataOnlyResult = new Intent(Intent.ACTION_OPEN_DOCUMENT).setData(selected);
        assertEquals(Collections.singletonList(selected), DocumentContentPlugin.extractUris(dataOnlyResult));

        Intent duplicatedResult = new Intent(Intent.ACTION_OPEN_DOCUMENT).setData(selected);
        duplicatedResult.setClipData(ClipData.newRawUri("selected document", selected));

        ArrayList<Uri> extracted = DocumentContentPlugin.extractUris(duplicatedResult);
        assertEquals(Collections.singletonList(selected), extracted);
    }
}
