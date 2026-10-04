package com.corefilter.farmer.maps;

import android.content.Context;
import com.corefilter.farmer.Profile;
import com.corefilter.farmer.engine.MapNavigator;
import java.io.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.*;

/** Private, persistent queue. A download never removes a bundle; only a matching receipt does. */
public final class MapArchiveStore {
    static final int MAX_CELLS=20000,MAX_BORDERS=20000,MAX_PATH=6000,MAX_COVERAGE=20000,MAX_ENEMIES=1024,MAX_HISTORY=1024;
    private MapArchiveStore(){}
    public static final class Bundle {
        public final String id,sha256;public final File file;public final long bytes,createdAt;
        Bundle(File f)throws IOException{id=f.getName().substring(0,f.getName().length()-4);file=f;bytes=f.length();createdAt=f.lastModified();sha256=hash(f);}
        JSONObject json()throws JSONException{return new JSONObject().put("id",id).put("sha256",sha256).put("bytes",bytes).put("createdAt",createdAt).put("filename",file.getName());}
    }
    private static File directory(Context c)throws IOException {
        File dir=new File(c.getFilesDir(),"map-queue");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create private map storage");return dir;
    }
    public static synchronized File save(Context context,MapNavigator.Snapshot s,String outcome,long runNumber)throws IOException {
        if(s==null)throw new IOException("No map snapshot to save");
        String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date());
        String id="run-"+stamp+"-"+Math.max(0,runNumber)+"-"+UUID.randomUUID().toString().substring(0,8);
        File dir=directory(context),part=new File(dir,id+".partial"),finished=new File(dir,id+".zip");
        try {
            JSONObject data=metadata(context,s,outcome,runNumber);
            byte[] image=MapPngRenderer.render(s,runNumber,outcome);
            try(FileOutputStream raw=new FileOutputStream(part);ZipOutputStream zip=new ZipOutputStream(raw)) {
                entry(zip,"map.png",image);entry(zip,"map.json",data.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                entry(zip,"README.txt",("Ceiling Scout observed level map\r\n\r\nmap.png: observed rectangular boundaries, path, ceiling checks and enemy sightings.\r\nmap.json: coordinate units, all retained model arrays, build settings, confidence and incomplete coverage.\r\n\r\nBlank areas are unknown. Separate panels have unlinked origins; no connecting passage is inferred.\r\nA burn attempt is not a confirmed kill. A successful run does not prove full ceiling coverage.\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.finish();raw.getFD().sync();
            }
            if(!part.renameTo(finished))throw new IOException("Cannot finalize saved map bundle");return finished;
        }catch(JSONException e){throw new IOException("Cannot encode map metadata",e);}finally{if(part.exists()&&!part.delete())part.deleteOnExit();}
    }
    private static void entry(ZipOutputStream z,String name,byte[] value)throws IOException {z.putNextEntry(new ZipEntry(name));z.write(value);z.closeEntry();}
    public static synchronized int count(Context c){File d=new File(c.getFilesDir(),"map-queue");File[] files=d.listFiles((dir,name)->validId(name.endsWith(".zip")?name.substring(0,name.length()-4):""));return files==null?0:files.length;}
    public static synchronized List<Bundle> list(Context c)throws IOException {
        File[] files=directory(c).listFiles((dir,name)->name.endsWith(".zip")&&validId(name.substring(0,name.length()-4)));List<Bundle> out=new ArrayList<>();
        if(files!=null){Arrays.sort(files,Comparator.comparing(File::getName));for(File f:files)if(f.isFile())out.add(new Bundle(f));}return out;
    }
    static boolean validId(String id){return id!=null&&id.matches("run-[A-Za-z0-9-]{1,90}");}
    public static synchronized File find(Context c,String id)throws IOException {
        if(!validId(id))throw new IOException("Invalid map identifier");File dir=directory(c).getCanonicalFile(),file=new File(dir,id+".zip").getCanonicalFile();
        if(!dir.equals(file.getParentFile())||!file.isFile())throw new FileNotFoundException("Map bundle not found");return file;
    }
    public static synchronized boolean acknowledge(Context c,String id,String receiptHash)throws IOException {
        if(receiptHash==null||!receiptHash.matches("[a-fA-F0-9]{64}"))return false;
        File f=find(c,id);String actual=hash(f);
        if(!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),receiptHash.toLowerCase(Locale.US).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))return false;
        if(!f.delete())throw new IOException("Receipt verified, but the phone could not remove this bundle");return true;
    }
    public static String hash(File file)throws IOException {
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(Locale.US,"%02x",b&255));return result.toString();}
        catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
    }
    private static Object number(double n){return Double.isFinite(n)?n:JSONObject.NULL;}
    private static JSONArray rows(double[][] values,int limit)throws JSONException {
        JSONArray array=new JSONArray();if(values!=null)for(int i=0;i<Math.min(values.length,limit);i++){JSONArray row=new JSONArray();if(values[i]!=null)for(double v:values[i])row.put(number(v));array.put(row);}return array;
    }
    private static int length(double[][] values){return values==null?0:values.length;}
    private static JSONObject arrayInfo(double[][] values,int limit)throws JSONException {int n=length(values);return new JSONObject().put("available",n).put("retained",Math.min(n,limit)).put("truncated",n>limit);}
    private static Object optional(MapNavigator.Snapshot s,String name,Object fallback){try{return s.getClass().getField(name).get(s);}catch(ReflectiveOperationException e){return fallback;}}
    static double[][] history(MapNavigator.Snapshot s){Object value=optional(s,"enemyHistory",new double[0][]);return value instanceof double[][]?(double[][])value:new double[0][];}
    private static JSONObject metadata(Context context,MapNavigator.Snapshot s,String outcome,long run)throws JSONException {
        JSONObject root=new JSONObject().put("schemaVersion",1).put("createdAtUnixMs",System.currentTimeMillis()).put("runNumber",run).put("outcome",outcome==null?"unknown":outcome)
                .put("complete",s.complete).put("phase",s.phase).put("runEnded",optional(s,"runEnded",true)).put("runSucceeded",optional(s,"runSucceeded",false));
        root.put("units",new JSONObject().put("x","one captured viewport width").put("y","one captured viewport height; increases downward")
                .put("viewportAspectRatio",number(s.viewportAspectRatio)).put("terrainCols",s.terrainCols).put("terrainRows",s.terrainRows)
                .put("sectionOrigins","Separate local coordinate origins when camera registration cannot link sections. No inferred connecting offset."));
        root.put("model",new JSONObject().put("section",s.room).put("cameraX",number(s.cameraX)).put("cameraY",number(s.cameraY)).put("cameraConfidence",number(s.cameraConfidence))
                .put("playerX",number(s.playerX)).put("playerY",number(s.playerY)).put("goalX",number(s.goalX)).put("goalY",number(s.goalY)).put("goal",s.goal).put("reason",s.reason)
                .put("blockedForMs",s.blockedForMs).put("remainingJumps",s.remainingJumps).put("learnedJumpRise",number(s.learnedJumpRise)).put("verticalVelocity",number(s.verticalVelocity))
                .put("inspectedCeilings",s.inspectedCeilings).put("ceilingSections",s.ceilingSections).put("unresolvedEnemies",s.unresolvedEnemies));
        root.put("fields",new JSONObject().put("cells","[x,y,occupancy:1 free/2 solid,visited,optional section]")
                .put("borders","[x1,y1,x2,y2,type:1 floor/2 ceiling/3 wall,section,confidence]")
                .put("path","[x,y,section,observationTimeMs,optional confidence]").put("coverage","[x1,y,x2,section,inspected:0/1]")
                .put("enemies","[x,y,burnAttempt:0/1,lastSeenMs,optional section]; a contact is not a confirmed kill")
                .put("enemyHistory","[id,x,y,section,firstSeenMs,lastSeenMs,touchedAtMs,clearedAtMs,confidence]; null or negative timestamps mean unconfirmed"));
        root.put("cells",rows(s.cells,MAX_CELLS)).put("borders",rows(s.borders,MAX_BORDERS)).put("path",rows(s.path,MAX_PATH)).put("coverage",rows(s.coverage,MAX_COVERAGE)).put("enemies",rows(s.enemies,MAX_ENEMIES));
        root.put("controlTrace",rows(s.controlTrace,1200)).put("controlReasons",new JSONArray(Arrays.asList(s.controlReasons)));
        root.getJSONObject("fields").put("controlTrace","[capturedAtMs,processedAtMs,screenPlayerX,screenPlayerY,playerConfidence,strongGround,footCandidate,controlGround,cameraConfidence,direction,jumps,usedJumps,corridorDirection,wallLeft,wallRight,ceilingContact]; controlReasons has the corresponding decision text");
        double[][] history=history(s);root.put("enemyHistory",rows(history,MAX_HISTORY));
        root.put("retention",new JSONObject().put("cells",arrayInfo(s.cells,MAX_CELLS)).put("borders",arrayInfo(s.borders,MAX_BORDERS)).put("path",arrayInfo(s.path,MAX_PATH)).put("coverage",arrayInfo(s.coverage,MAX_COVERAGE)).put("enemies",arrayInfo(s.enemies,MAX_ENEMIES)).put("enemyHistory",arrayInfo(history,MAX_HISTORY)));
        JSONArray incomplete=new JSONArray();if(!s.complete)incomplete.put("Navigator did not verify complete observed coverage");if(s.inspectedCeilings<s.ceilingSections)incomplete.put("Some observed ceiling sections remain uninspected");
        if(s.cameraConfidence<.55)incomplete.put("Final camera registration was uncertain");if(!Double.isFinite(s.viewportAspectRatio)||s.viewportAspectRatio<=0)incomplete.put("Physical viewport aspect ratio was unavailable; plot uses independent coordinate units");
        if(length(s.cells)>MAX_CELLS||length(s.borders)>MAX_BORDERS||length(s.path)>MAX_PATH||length(s.coverage)>MAX_COVERAGE||length(s.enemies)>MAX_ENEMIES||length(history)>MAX_HISTORY)incomplete.put("Export limits reached; retention records identify truncated arrays");
        root.put("incompleteReasons",incomplete).put("boundsBySection",MapPngRenderer.boundsJson(s)).put("image",new JSONObject().put("maxPanels",MapPngRenderer.MAX_PANELS).put("maxWidth",MapPngRenderer.WIDTH).put("unknownAreas","blank; no unobserved room boundaries are invented"));
        Profile p=Profile.load(context);root.put("build",new JSONObject().put("name",p.name).put("hull",p.hull).put("weapons",p.weapons).put("notes",p.notes).put("hookshotCount",p.hookshotCount).put("extraJumps",p.extraJumps).put("totalJumpBudget",p.totalJumpBudget())
                .put("moveMs",p.moveMs).put("jumpSpacingMs",p.jumpSpacingMs).put("settleMs",p.settleMs).put("maxRunSeconds",p.maxRunSeconds).put("continuousFarm",p.continuousFarm).put("watchFilterAds",p.watchFilterAds));
        root.put("limitations",new JSONArray().put("Observed on-screen geometry only; no guarantee that the entire level was observed").put("Separate unlinked sections are drawn in distinct panels").put("Enemy sightings are heuristic; burnAttempt does not confirm a kill").put("A successful run alone does not prove complete ceiling coverage"));return root;
    }
}
