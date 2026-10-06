package com.corefilter.farmer;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Read-only grants for the app's own generated recording ZIP, never arbitrary paths. */
public final class OfflineFileProvider extends ContentProvider {
    static Uri uri(Context c,File file){return new Uri.Builder().scheme("content").authority(c.getPackageName()+".offline").appendPath(file.getName()).build();}
    private File file(Uri uri)throws FileNotFoundException{String name=uri.getLastPathSegment();if(uri.getPathSegments().size()!=1||name==null||!name.matches("recording-[0-9]{13}-[a-f0-9]{8}\\.zip"))throw new FileNotFoundException();File f=new File(getContext().getCacheDir(),name);if(!f.isFile())throw new FileNotFoundException();return f;}
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return "application/zip";}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!mode.equals("r"))throw new FileNotFoundException("Read-only export");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){try{File f=file(uri);MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{f.getName(),f.length()});return c;}catch(FileNotFoundException e){return null;}}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
