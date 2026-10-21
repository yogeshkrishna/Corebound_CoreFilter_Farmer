package dev.corefilter.pilot;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
public final class DiagnosticProviderTest {
  private DiagnosticProvider provider;
  private File export;
  private Uri uri;
  private static final byte[] PAYLOAD =
      "{\"status\":\"paused\",\"observations\":7}".getBytes(StandardCharsets.UTF_8);

  @Before
  public void setUp() throws IOException {
    Context context = RuntimeEnvironment.getApplication();
    String authority = context.getPackageName() + ".diagnostics";
    ProviderInfo info = new ProviderInfo();
    info.authority = authority;
    info.exported = false;
    info.grantUriPermissions = true;
    provider = new DiagnosticProvider();
    provider.attachInfo(context, info);
    uri = Uri.parse("content://" + authority + "/session.json");
    export = new File(context.getCacheDir(), "session.json");
    Files.write(export.toPath(), PAYLOAD);
  }

  @Test
  public void readsOnlyTheExplicitCacheExport() throws IOException {
    try (ParcelFileDescriptor descriptor = provider.openFile(uri, "r");
        ParcelFileDescriptor.AutoCloseInputStream input =
            new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
      assertArrayEquals(PAYLOAD, input.readAllBytes());
    }
  }

  @Test
  public void allWriteModesAreRejectedWithoutChangingTheExport() throws IOException {
    for (String mode : new String[] {"w", "rw", "rwt", "wt", "a", "wa", "", "rt"}) {
      assertThrows(FileNotFoundException.class, () -> provider.openFile(uri, mode));
    }
    assertArrayEquals(PAYLOAD, Files.readAllBytes(export.toPath()));
  }

  @Test
  public void unknownAuthorityAndTraversalCannotReadOtherFiles() {
    String authority = uri.getAuthority();
    for (String invalid :
        new String[] {
          "content://other.example/session.json",
          "content://" + authority + "/settings.json",
          "content://" + authority + "/../settings.json",
          "content://" + authority + "/session.json/../settings.json",
          "content://" + authority + "/%2e%2e%2fsettings.json",
          "file:///session.json",
          "/session.json"
        }) {
      assertThrows(
          invalid, FileNotFoundException.class, () -> provider.openFile(Uri.parse(invalid), "r"));
    }
  }

  @Test
  public void missingExportIsAnActionableFileError() throws IOException {
    Files.delete(export.toPath());
    assertThrows(FileNotFoundException.class, () -> provider.openFile(uri, "r"));
  }

  @Test
  public void defaultMetadataDescribesTheSharedFile() {
    try (Cursor cursor = provider.query(uri, null, null, null, null)) {
      assertTrue(cursor.moveToFirst());
      assertEquals(
          "corefilter-pilot-session.json",
          cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
      assertEquals(
          PAYLOAD.length, cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)));
      assertEquals(1, cursor.getCount());
    }
    assertEquals("application/json", provider.getType(uri));
  }

  @Test
  public void metadataRespectsProjectionWithoutExposingPaths() {
    try (Cursor cursor =
        provider.query(
            uri,
            new String[] {OpenableColumns.SIZE, "unknown", OpenableColumns.DISPLAY_NAME},
            null,
            null,
            null)) {
      assertTrue(cursor.moveToFirst());
      assertEquals(PAYLOAD.length, cursor.getLong(0));
      assertTrue(cursor.isNull(1));
      assertEquals("corefilter-pilot-session.json", cursor.getString(2));
    }
    assertNull(
        provider.query(Uri.parse("content://bad.example/session.json"), null, null, null, null));
  }

  @Test
  public void mutationEntryPointsAreDisabled() {
    ContentValues values = new ContentValues();
    values.put("content", "replacement");
    assertThrows(UnsupportedOperationException.class, () -> provider.insert(uri, values));
    assertThrows(
        UnsupportedOperationException.class, () -> provider.update(uri, values, null, null));
    assertThrows(UnsupportedOperationException.class, () -> provider.delete(uri, null, null));
  }
}
