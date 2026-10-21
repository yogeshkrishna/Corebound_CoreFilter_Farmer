package dev.corefilter.pilot;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Exposes only the explicit diagnostic export, read-only, under a temporary URI grant. */
public final class DiagnosticProvider extends ContentProvider {
  @Override
  public boolean onCreate() {
    return true;
  }

  private File file(Uri uri) throws FileNotFoundException {
    if (getContext() == null
        || !(getContext().getPackageName() + ".diagnostics").equals(uri.getAuthority())
        || !"/session.json".equals(uri.getPath()))
      throw new FileNotFoundException("Unknown diagnostic export");
    return new File(getContext().getCacheDir(), "session.json");
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("Read-only export");
    return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override
  public Cursor query(
      Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    try {
      File file = file(uri);
      String[] columns =
          projection == null
              ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
              : projection;
      MatrixCursor cursor = new MatrixCursor(columns);
      Object[] row = new Object[columns.length];
      for (int i = 0; i < columns.length; i++) {
        if (OpenableColumns.DISPLAY_NAME.equals(columns[i]))
          row[i] = "corefilter-pilot-session.json";
        else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
      }
      cursor.addRow(row);
      return cursor;
    } catch (FileNotFoundException error) {
      return null;
    }
  }

  @Override
  public String getType(Uri uri) {
    return "application/json";
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException("Read-only export");
  }

  @Override
  public int delete(Uri uri, String selection, String[] args) {
    throw new UnsupportedOperationException("Read-only export");
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] args) {
    throw new UnsupportedOperationException("Read-only export");
  }
}
