package com.corefilter.farmer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import com.corefilter.farmer.mapping.*;
import org.json.*;
import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.calib3d.Calib3d;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Batch reconstruction from immutable native PNGs. Analysis is reduced; output never is. */
final class OfflineReconstructor implements AutoCloseable {
    interface Progress { void update(String stage,int done,int total) throws Exception; }
    private final File recording,work;private final List<File> inputs;
    private final BooleanSupplier cancelled;private final Progress progress;
    private final SIFT sift=SIFT.create(2200,3,.025,10,1.6);
    private final LinkedHashMap<Integer,Feature> cache=new LinkedHashMap<>(12,.75f,true);
    private final List<Node> nodes=new ArrayList<>();
    private static final class Node {int width,height,segment;boolean usable;float[] signature;}
    private static final class Feature implements AutoCloseable {
        int width,height;double scale;boolean gameplay;Point[] points;float[] signature;
        Mat descriptors=new Mat();DescriptorMatcher matcher;
        void prepare(){if(descriptors.empty())return;matcher=DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);matcher.add(Collections.singletonList(descriptors));matcher.train();}
        public void close(){if(matcher!=null)matcher.clear();descriptors.release();}
    }
    private static final class Match {int a,b,count;double dx,dy,ratio;TranslationGraph.Edge edge(){return new TranslationGraph.Edge(a,b,dx,dy,Math.min(200,count)*ratio);}}
    OfflineReconstructor(File recording,BooleanSupplier cancelled,Progress progress)throws Exception{
        this.recording=recording;this.cancelled=cancelled;this.progress=progress;inputs=RecordingStore.frames(recording);work=new File(recording,"analysis-v1");
        if(!work.isDirectory()&&!work.mkdirs())throw new IOException("Cannot create analysis cache");
    }
    private void check()throws InterruptedIOException{if(cancelled.getAsBoolean())throw new InterruptedIOException("Paused. Saved recording and analysis are retained.");}
    JSONObject build()throws Exception{
        if(inputs.isEmpty())throw new IOException("No original game images have been saved");
        File graphFile=new File(work,"alignment.json");JSONObject alignment;
        if(graphFile.isFile()){alignment=RecordingStore.read(graphFile);if(alignment.optInt("inputCount")!=inputs.size())alignment=align();}
        else alignment=align();
        JSONArray poses=alignment.getJSONArray("poses");int sections=alignment.getInt("sections");
        if(sections==0)throw new IOException("No gameplay views could be aligned. Original images are retained; record with both movement buttons visible.");
        File atlasRoot=new File(recording,"map-v1");if(!atlasRoot.isDirectory()&&!atlasRoot.mkdirs())throw new IOException("Cannot create map");
        List<NativeAtlas> atlases=new ArrayList<>();int[] counts=new int[sections];
        File checkpoint=new File(atlasRoot,"checkpoint.json");int start=0;
        if(checkpoint.isFile()){JSONObject saved=RecordingStore.read(checkpoint);start=saved.optInt("next");JSONArray groups=saved.optJSONArray("sections");
            if(groups!=null&&groups.length()==sections)for(int i=0;i<sections;i++)counts[i]=groups.getJSONObject(i).optInt("frames");else start=0;}
        try {
            for(int i=0;i<sections;i++){NativeAtlas atlas=new NativeAtlas(new File(atlasRoot,"section-"+i));atlases.add(atlas);if(start>0){JSONArray b=RecordingStore.read(checkpoint).getJSONArray("sections").getJSONObject(i).optJSONArray("bounds");if(b!=null)atlas.bounds=new int[]{b.getInt(0),b.getInt(1),b.getInt(2),b.getInt(3)};}}
            for(int i=start;i<inputs.size();i++){check();progress.update("Building native map",i,inputs.size());JSONObject pose=poses.getJSONObject(i);int section=pose.getInt("section");
                if(section>=0){JSONObject meta=RecordingStore.read(inputs.get(i));File png=new File(inputs.get(i).getParentFile(),meta.getString("file"));
                    Bitmap image=BitmapFactory.decodeFile(png.getPath());if(image==null)throw new IOException("Unreadable original image "+png.getName());
                    Mat bgr=readImage(png),mask=null,padded=new Mat(),distance=new Mat(),cropped=null;
                    try{mask=mask(bgr,meta);Core.copyMakeBorder(mask,padded,1,1,1,1,Core.BORDER_CONSTANT,new Scalar(0));Imgproc.distanceTransform(padded,distance,Imgproc.DIST_L2,3);cropped=distance.submat(1,1+bgr.rows(),1,1+bgr.cols());
                        byte[] valid=new byte[image.getWidth()*image.getHeight()];float[] quality=new float[valid.length];mask.get(0,0,valid);cropped.get(0,0,quality);
                        atlases.get(section).paint(image,valid,quality,(int)Math.round(pose.getDouble("x")),(int)Math.round(pose.getDouble("y")));counts[section]++;
                    }finally{image.recycle();bgr.release();if(mask!=null)mask.release();padded.release();distance.release();if(cropped!=null)cropped.release();}}
                // A checkpoint covers only fully flushed source frames. Replaying the current
                // frame after an interruption is safe and does not change its source pixels.
                for(NativeAtlas a:atlases)a.flush();RecordingStore.write(checkpoint,summary(atlases,counts).put("next",i+1));}
            progress.update("Preparing previews",0,sections);
            for(int i=0;i<sections;i++){check();atlases.get(i).preview(new File(atlasRoot,"preview-"+i+".png"));progress.update("Preparing previews",i+1,sections);}
            JSONObject result=summary(atlases,counts).put("schema",1).put("nativePixels",true).put("alignment",alignment).put("inputCount",inputs.size()).put("completed",System.currentTimeMillis());
            RecordingStore.write(new File(recording,"map.json"),result);return result;
        }finally{for(NativeAtlas a:atlases)a.close();}
    }
    private JSONObject summary(List<NativeAtlas> atlases,int[] counts)throws Exception{JSONArray sections=new JSONArray();for(int i=0;i<atlases.size();i++)sections.put(atlases.get(i).summary(counts[i]));return new JSONObject().put("sections",sections);}
    private JSONObject align()throws Exception{
        int segment=0;boolean previousGame=false;long previousTime=0;
        for(int i=0;i<inputs.size();i++){check();progress.update("Finding stable scenery",i,inputs.size());Feature f=feature(i);JSONObject meta=RecordingStore.read(inputs.get(i));long time=meta.optLong("captureElapsedMs");
            if(f.gameplay&&(!previousGame||time-previousTime>3000))segment++;
            Node n=new Node();n.width=f.width;n.height=f.height;n.segment=segment;n.usable=f.gameplay&&f.points.length>=20;n.signature=f.signature;nodes.add(n);previousGame=f.gameplay;previousTime=time;}
        boolean[] usable=new boolean[nodes.size()];for(int i=0;i<nodes.size();i++)usable[i]=nodes.get(i).usable;
        List<TranslationGraph.Edge> edges=new ArrayList<>();List<Integer> recent=new ArrayList<>();
        double[] localX=new double[nodes.size()],localY=new double[nodes.size()];int[] localSection=new int[nodes.size()];Arrays.fill(localSection,-1);
        File matchingFile=new File(work,"matching.json");int localStart=0,globalStart=0;
        if(matchingFile.isFile()){JSONObject saved=RecordingStore.read(matchingFile);if(saved.optInt("inputCount")==nodes.size()){
            JSONArray constraints=saved.getJSONArray("edges");for(int k=0;k<constraints.length();k++){JSONArray e=constraints.getJSONArray(k);edges.add(new TranslationGraph.Edge(e.getInt(0),e.getInt(1),e.getDouble(2),e.getDouble(3),e.getDouble(4)));}
            if(saved.getString("stage").equals("global")){localStart=nodes.size();globalStart=saved.getInt("next");}else localStart=saved.getInt("next");
            TranslationGraph.Pose[] p=TranslationGraph.solve(nodes.size(),usable,edges);for(int k=0;k<localStart;k++)if(usable[k]){localX[k]=p[k].x;localY[k]=p[k].y;localSection[k]=p[k].section;recent.add(k);}
        }}
        // First build continuity locally, independently of any distant look-alike scenery.
        for(int i=localStart;i<nodes.size();i++){check();progress.update("Tracking camera movement",i,nodes.size());Node n=nodes.get(i);if(!n.usable)continue;
            List<Match> matches=new ArrayList<>();for(int k=recent.size()-1;k>=Math.max(0,recent.size()-6);k--){int j=recent.get(k);if(nodes.get(j).segment!=n.segment)continue;Match m=match(j,i);if(m!=null)matches.add(m);}
            matches.sort((a,b)->Integer.compare(b.count,a.count));if(!matches.isEmpty()){Match m=matches.get(0);edges.add(m.edge());localX[i]=localX[m.a]+m.dx;localY[i]=localY[m.a]+m.dy;localSection[i]=localSection[m.a];}else localSection[i]=i;
            // Extra nearby constraints make subpixel camera error observable for later refinement.
            if(matches.size()>1)for(int k=1;k<matches.size();k++){Match m=matches.get(k);if(localSection[m.a]==localSection[i]&&Math.hypot(localX[m.a]+m.dx-localX[i],localY[m.a]+m.dy-localY[i])<8)edges.add(m.edge());}
            recent.add(i);if(i%32==0)saveMatching(matchingFile,"local",i+1,edges);}
        saveMatching(matchingFile,"global",globalStart,edges);
        TranslationGraph.Pose[] preliminary=TranslationGraph.solve(nodes.size(),usable,edges);
        // Look across the full recording, including future frames. Global joins require
        // agreement with continuity, or two independent observations for disconnected pieces.
        for(int i=globalStart;i<nodes.size();i++){check();progress.update("Checking revisits across the run",i,nodes.size());if(!usable[i]||i%4!=0)continue;
            final int query=i;List<Integer> candidates=new ArrayList<>();for(int j=0;j<nodes.size();j++)if(usable[j]&&Math.abs(i-j)>20&&nodes.get(j).segment==nodes.get(i).segment&&nodes.get(j).width==nodes.get(i).width&&nodes.get(j).height==nodes.get(i).height)candidates.add(j);
            candidates.sort((a,b)->Double.compare(similarity(nodes.get(query).signature,nodes.get(b).signature),similarity(nodes.get(query).signature,nodes.get(a).signature)));
            List<Match> matches=new ArrayList<>();for(int k=0;k<Math.min(10,candidates.size());k++){Match m=match(candidates.get(k),i);if(m!=null)matches.add(m);}matches.sort((a,b)->Integer.compare(b.count,a.count));
            for(Match m:matches){TranslationGraph.Pose a=preliminary[m.a],b=preliminary[i];double ox=a.x+m.dx-b.x,oy=a.y+m.dy-b.y;
                if(a.section==b.section){if(Math.hypot(ox,oy)<=24)edges.add(m.edge());continue;}
                if(m.count<30||m.ratio<.8)continue;boolean corroborated=false,ambiguous=false;
                for(Match other:matches){if(other==m||Math.abs(other.a-m.a)<4)continue;TranslationGraph.Pose c=preliminary[other.a];
                    double cx=c.x+other.dx-b.x,cy=c.y+other.dy-b.y;
                    if(c.section==a.section&&Math.hypot(cx-ox,cy-oy)<=8&&other.count>=25&&other.ratio>=.8)corroborated=true;
                    else if(other.count>m.count*.85&&(c.section!=a.section||Math.hypot(cx-ox,cy-oy)>24))ambiguous=true;}
                if(corroborated&&!ambiguous)edges.add(m.edge());}
            if(i%32==0)saveMatching(matchingFile,"global",i+1,edges);
        }
        progress.update("Refining camera positions",0,1);TranslationGraph.Pose[] solved=TranslationGraph.solve(nodes.size(),usable,edges);JSONArray poses=new JSONArray();int sections=0;
        for(int i=0;i<solved.length;i++){TranslationGraph.Pose p=solved[i];if(Math.abs(p.x)>1000000||Math.abs(p.y)>1000000)throw new IOException("Implausible camera movement; original recording retained");sections=Math.max(sections,p.section+1);
            poses.put(new JSONObject().put("frame",inputs.get(i).getName()).put("section",p.section).put("x",p.x).put("y",p.y));}
        JSONObject result=new JSONObject().put("schema",1).put("inputCount",inputs.size()).put("sections",sections).put("constraints",edges.size()).put("poses",poses);
        RecordingStore.write(new File(work,"alignment.json"),result);return result;
    }
    private void saveMatching(File file,String stage,int next,List<TranslationGraph.Edge> edges)throws Exception{
        JSONArray data=new JSONArray();for(TranslationGraph.Edge e:edges)data.put(new JSONArray().put(e.a).put(e.b).put(e.dx).put(e.dy).put(e.weight));
        RecordingStore.write(file,new JSONObject().put("inputCount",inputs.size()).put("stage",stage).put("next",next).put("edges",data));
    }
    private double similarity(float[] a,float[] b){double sum=0;for(int i=0;i<a.length;i++)sum+=a[i]*b[i];return sum;}
    private Match match(int anchor,int query)throws Exception{
        Feature a=feature(anchor),b=feature(query);if(!a.gameplay||!b.gameplay||a.width!=b.width||a.height!=b.height||a.descriptors.rows()<20||b.descriptors.rows()<20)return null;
        List<MatOfDMatch> pairs=new ArrayList<>();List<Point> from=new ArrayList<>(),to=new ArrayList<>();MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();Mat inliers=new Mat(),affine=null;
        try{a.matcher.knnMatch(b.descriptors,pairs,2);for(MatOfDMatch pair:pairs){DMatch[] p=pair.toArray();if(p.length==2&&p[0].distance<.68*p[1].distance){from.add(b.points[p[0].queryIdx]);to.add(a.points[p[0].trainIdx]);}}
            if(from.size()<18)return null;src.fromList(from);dst.fromList(to);affine=Calib3d.estimateAffinePartial2D(src,dst,inliers,Calib3d.RANSAC,2,1000,.99,10);
            if(affine.empty()||inliers.empty())return null;double xx=affine.get(0,0)[0],yx=affine.get(1,0)[0];if(Math.abs(Math.hypot(xx,yx)-1)>.012||Math.abs(yx)>.012)return null;
            byte[] accepted=new byte[from.size()];inliers.get(0,0,accepted);List<Double> dx=new ArrayList<>(),dy=new ArrayList<>();for(int i=0;i<accepted.length;i++)if(accepted[i]!=0){dx.add(to.get(i).x-from.get(i).x);dy.add(to.get(i).y-from.get(i).y);}if(dx.size()<18)return null;
            Collections.sort(dx);Collections.sort(dy);double x=dx.get(dx.size()/2),y=dy.get(dy.size()/2);int count=0;double left=1e9,top=1e9,right=-1e9,bottom=-1e9;
            for(int i=0;i<from.size();i++){Point p=from.get(i),q=to.get(i);if(Math.hypot(q.x-p.x-x,q.y-p.y-y)<2){count++;left=Math.min(left,p.x);top=Math.min(top,p.y);right=Math.max(right,p.x);bottom=Math.max(bottom,p.y);}}
            double ratio=(double)count/from.size();if(count<18||ratio<.7||right-left<b.width*b.scale*.13||bottom-top<b.height*b.scale*.08)return null;
            Match m=new Match();m.a=anchor;m.b=query;m.dx=x/b.scale;m.dy=y/b.scale;m.count=count;m.ratio=ratio;return m;
        }finally{for(MatOfDMatch pair:pairs)pair.release();src.release();dst.release();inliers.release();if(affine!=null)affine.release();}
    }
    private Feature feature(int i)throws Exception{
        Feature f=cache.get(i);if(f!=null)return f;check();File saved=new File(work,String.format(Locale.ROOT,"%08d.features",i));
        if(saved.isFile()){try{f=load(saved);}catch(IOException e){f=extract(i);save(saved,f);}}else{f=extract(i);save(saved,f);}
        f.prepare();cache.put(i,f);while(cache.size()>8){Map.Entry<Integer,Feature> oldest=cache.entrySet().iterator().next();oldest.getValue().close();cache.remove(oldest.getKey());}return f;
    }
    private Feature extract(int i)throws Exception{
        JSONObject meta=RecordingStore.read(inputs.get(i));File png=new File(inputs.get(i).getParentFile(),meta.getString("file"));Mat image=readImage(png),gray=new Mat(),valid=null,small=new Mat(),smallMask=new Mat(),signature=new Mat();MatOfKeyPoint points=new MatOfKeyPoint();Feature f=new Feature();
        try{f.width=image.cols();f.height=image.rows();f.scale=Math.min(1,960.0/f.width);Imgproc.cvtColor(image,gray,Imgproc.COLOR_BGR2GRAY);f.gameplay=gameplay(gray);f.signature=new float[32*18];f.points=new Point[0];
            Imgproc.resize(gray,signature,new Size(32,18));byte[] samples=new byte[32*18];signature.get(0,0,samples);double mean=0;for(byte s:samples)mean+=s&255;mean/=samples.length;double norm=0;
            for(int k=0;k<samples.length;k++){f.signature[k]=(float)((samples[k]&255)-mean);norm+=f.signature[k]*f.signature[k];}norm=Math.sqrt(norm);if(norm>0)for(int k=0;k<samples.length;k++)f.signature[k]/=norm;
            if(f.gameplay){valid=mask(image,meta);Size size=new Size(Math.round(f.width*f.scale),Math.round(f.height*f.scale));Imgproc.resize(gray,small,size,0,0,Imgproc.INTER_AREA);Imgproc.resize(valid,smallMask,size,0,0,Imgproc.INTER_NEAREST);
                sift.detectAndCompute(small,smallMask,points,f.descriptors);KeyPoint[] keys=points.toArray();f.points=new Point[keys.length];for(int k=0;k<keys.length;k++)f.points[k]=keys[k].pt;}
            return f;
        }finally{image.release();gray.release();if(valid!=null)valid.release();small.release();smallMask.release();signature.release();points.release();}
    }
    private Mat readImage(File png)throws IOException{Mat image=Imgcodecs.imread(png.getPath(),Imgcodecs.IMREAD_COLOR);if(image.empty())throw new IOException("Cannot read original PNG "+png.getName());return image;}
    private boolean gameplay(Mat gray){int h=gray.rows(),w=gray.cols();List<org.opencv.core.Rect> boxes=new ArrayList<>();
        for(int threshold:new int[]{70,110}){Mat binary=new Mat(),hierarchy=new Mat();List<MatOfPoint> contours=new ArrayList<>();
            try{Imgproc.threshold(gray,binary,threshold,255,Imgproc.THRESH_BINARY);Imgproc.findContours(binary,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE);
                for(MatOfPoint c:contours){org.opencv.core.Rect r=Imgproc.boundingRect(c);if(r.x<w*.4&&r.y>h*.55&&r.y+r.height<h*.97&&r.width>w*.075&&r.width<w*.2&&r.height>h*.1&&r.height<h*.33)boxes.add(r);}
            }finally{binary.release();hierarchy.release();for(MatOfPoint c:contours)c.release();}}
        for(org.opencv.core.Rect a:boxes)for(org.opencv.core.Rect b:boxes)if(b.x>a.x+a.width*.8&&b.x<a.x+a.width*1.3&&Math.abs(b.y-a.y)<h*.025&&Math.abs(b.height-a.height)<h*.03)return true;return false;
    }
    private Mat mask(Mat bgr,JSONObject meta)throws JSONException{
        int w=bgr.cols(),h=bgr.rows();Mat hsv=new Mat(),dynamic=new Mat(),kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(19,19));Mat mask=new Mat(h,w,CvType.CV_8UC1,new Scalar(255));
        try{Imgproc.cvtColor(bgr,hsv,Imgproc.COLOR_BGR2HSV);Core.inRange(hsv,new Scalar(0,66,56),new Scalar(180,255,255),dynamic);Imgproc.dilate(dynamic,dynamic,kernel);mask.setTo(new Scalar(0),dynamic);
            blank(mask,0,0,(int)(w*.37),(int)(h*.14));blank(mask,(int)(w*.91),0,w,(int)(h*.17));blank(mask,(int)(w*.08),(int)(h*.69),(int)(w*.38),(int)(h*.95));
            JSONArray occlusions=meta.optJSONArray("occlusions");if(occlusions!=null)for(int i=0;i<occlusions.length();i++){JSONArray r=occlusions.getJSONArray(i);blank(mask,r.getInt(0),r.getInt(1),r.getInt(2),r.getInt(3));}return mask;
        }finally{hsv.release();dynamic.release();kernel.release();}
    }
    private void blank(Mat image,int x,int y,int right,int bottom){x=Math.max(0,x);y=Math.max(0,y);right=Math.min(image.cols(),right);bottom=Math.min(image.rows(),bottom);if(right>x&&bottom>y){Mat r=image.submat(y,bottom,x,right);r.setTo(new Scalar(0));r.release();}}
    private void save(File file,Feature f)throws IOException{AtomicFile atom=new AtomicFile(file);FileOutputStream stream=atom.startWrite();
        try{DataOutputStream out=new DataOutputStream(new BufferedOutputStream(stream));out.writeInt(1);out.writeInt(f.width);out.writeInt(f.height);out.writeDouble(f.scale);out.writeBoolean(f.gameplay);for(float s:f.signature)out.writeFloat(s);out.writeInt(f.points.length);
            for(Point p:f.points){out.writeFloat((float)p.x);out.writeFloat((float)p.y);}int len=f.descriptors.rows()*f.descriptors.cols();out.writeInt(f.descriptors.cols());float[] desc=new float[len];if(len>0)f.descriptors.get(0,0,desc);for(float d:desc)out.writeFloat(d);out.flush();atom.finishWrite(stream);
        }catch(IOException e){atom.failWrite(stream);throw e;}}
    private Feature load(File file)throws IOException{Feature f=new Feature();try(DataInputStream in=new DataInputStream(new BufferedInputStream(new AtomicFile(file).openRead()))){if(in.readInt()!=1)throw new IOException("Old feature cache");f.width=in.readInt();f.height=in.readInt();f.scale=in.readDouble();f.gameplay=in.readBoolean();f.signature=new float[32*18];for(int i=0;i<f.signature.length;i++)f.signature[i]=in.readFloat();int n=in.readInt();if(n<0||n>10000)throw new IOException("Invalid feature cache");f.points=new Point[n];for(int i=0;i<n;i++)f.points[i]=new Point(in.readFloat(),in.readFloat());int columns=in.readInt();if(n>0&&columns!=128)throw new IOException("Invalid descriptor cache");if(n>0){float[] data=new float[n*columns];for(int i=0;i<data.length;i++)data[i]=in.readFloat();f.descriptors.create(n,columns,CvType.CV_32F);f.descriptors.put(0,0,data);}return f;}catch(IOException e){f.close();throw e;}}
    @Override public void close(){for(Feature f:cache.values())f.close();cache.clear();sift.clear();}
}
