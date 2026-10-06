package com.corefilter.farmer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.util.Locale;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class RecordingStoreTest {
    @Test public void localRecordingCommitsNativeImagesBeforeMetadataAndRetainsThemAfterStop()throws Exception{
        Context c=RuntimeEnvironment.getApplication();File f=RecordingStore.create(c);Bitmap original=Bitmap.createBitmap(1600,737,Bitmap.Config.ARGB_8888);original.eraseColor(0xff283849);
        RecordingStore.saveFrame(f,1,original,new JSONObject().put("width",1600).put("height",737));original.recycle();RecordingStore.finish(f,"Ready");
        assertEquals(1,RecordingStore.frames(f).size());JSONObject metadata=RecordingStore.read(RecordingStore.frames(f).get(0));Bitmap saved=BitmapFactory.decodeFile(new File(f,"frames/"+metadata.getString("file")).getPath());
        assertEquals(1600,saved.getWidth());assertEquals(737,saved.getHeight());assertEquals(0xff283849,saved.getPixel(1000,500));saved.recycle();
        File png=new File(f,"frames/"+metadata.getString("file"));MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream bytes=new DigestInputStream(new FileInputStream(png),digest)){byte[] buffer=new byte[8192];while(bytes.read(buffer)!=-1){}}
        StringBuilder hash=new StringBuilder();for(byte value:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",value&255));
        assertEquals(png.length(),metadata.getLong("bytes"));assertEquals(hash.toString(),metadata.getString("sha256"));
        assertEquals("recorded",RecordingStore.read(new File(f,"recording.json")).getString("state"));
        new File(f,"frames/00000002.png").createNewFile();assertEquals("An incomplete frame must not enter the mapper",1,RecordingStore.frames(f).size());
    }
    @Test public void recordingLookupCannotEscapePrivateStorage()throws Exception{
        try{RecordingStore.directory(RuntimeEnvironment.getApplication(),"../../private");fail();}catch(IOException expected){assertEquals("Invalid recording",expected.getMessage());}
    }
}
