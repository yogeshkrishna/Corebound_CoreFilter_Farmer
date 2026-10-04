package com.corefilter.farmer.maps;

import com.corefilter.farmer.engine.MapNavigator;
import java.io.*;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.json.*;

/** Deterministic raster renderer/PNG encoder: no phone screenshot or desktop graphics dependency. */
final class MapPngRenderer {
    static final int WIDTH=1536,MAX_PANELS=16;
    private static final int BG=0x101B20,FREE=0x1D323A,SOLID=0x41515B,FLOOR=0x79D4E1,CEILING=0xF2C56B,WALL=0xAE9BDC,PATH=0x91DDB3,ENEMY=0xF47782,ATTEMPT=0xEBAE77,INK=0xDDEAE9,MUTED=0x9DB1B5;
    private static final class Bounds {
        double l=Double.POSITIVE_INFINITY,t=l,r=Double.NEGATIVE_INFINITY,b=r;int points;
        void add(double x,double y){if(Double.isFinite(x)&&Double.isFinite(y)&&Math.abs(x)<10000&&Math.abs(y)<10000){l=Math.min(l,x);r=Math.max(r,x);t=Math.min(t,y);b=Math.max(b,y);points++;}}
        void pad(){if(points==0){l=t=0;r=b=1;}if(r-l<.12){double mid=(l+r)/2;l=mid-.06;r=mid+.06;}if(b-t<.12){double mid=(t+b)/2;t=mid-.06;b=mid+.06;}}
    }
    private static TreeMap<Integer,Bounds> bounds(MapNavigator.Snapshot s){
        TreeMap<Integer,Bounds> out=new TreeMap<>();
        points(out,s.cells,4,s.room,MapArchiveStore.MAX_CELLS);points(out,s.enemies,4,s.room,MapArchiveStore.MAX_ENEMIES);points(out,s.path,2,s.room,MapArchiveStore.MAX_PATH);
        double[][] history=MapArchiveStore.history(s);for(int i=0;i<Math.min(history.length,MapArchiveStore.MAX_HISTORY);i++){double[] r=history[i];if(r!=null&&r.length>=4)out.computeIfAbsent((int)r[3],k->new Bounds()).add(r[1],r[2]);}
        if(s.borders!=null)for(int i=0;i<Math.min(s.borders.length,MapArchiveStore.MAX_BORDERS);i++){double[] row=s.borders[i];if(row!=null&&row.length>=6){Bounds b=out.computeIfAbsent((int)row[5],k->new Bounds());b.add(row[0],row[1]);b.add(row[2],row[3]);}}
        if(s.coverage!=null)for(int i=0;i<Math.min(s.coverage.length,MapArchiveStore.MAX_COVERAGE);i++){double[] row=s.coverage[i];if(row!=null&&row.length>=4){Bounds b=out.computeIfAbsent((int)row[3],k->new Bounds());b.add(row[0],row[1]);b.add(row[2],row[1]);}}
        out.computeIfAbsent(s.room,k->new Bounds()).add(s.playerX,s.playerY);for(Bounds b:out.values())b.pad();return out;
    }
    private static void points(TreeMap<Integer,Bounds> out,double[][] rows,int sectionIndex,int fallback,int cap){if(rows!=null)for(int i=0;i<Math.min(rows.length,cap);i++){double[] r=rows[i];if(r!=null&&r.length>=2)out.computeIfAbsent(r.length>sectionIndex?(int)r[sectionIndex]:fallback,k->new Bounds()).add(r[0],r[1]);}}
    static JSONArray boundsJson(MapNavigator.Snapshot s)throws JSONException {
        JSONArray out=new JSONArray();for(Map.Entry<Integer,Bounds> entry:bounds(s).entrySet()){Bounds b=entry.getValue();out.put(new JSONObject().put("section",entry.getKey()).put("minX",b.l).put("minY",b.t).put("maxX",b.r).put("maxY",b.b).put("observedPoints",b.points));}return out;
    }
    static byte[] render(MapNavigator.Snapshot s,long run,String outcome)throws IOException {
        TreeMap<Integer,Bounds> sections=bounds(s);int n=Math.min(MAX_PANELS,sections.size()),panelHeight=Math.min(420,Math.max(230,(4096-176)/Math.max(1,n)));
        Raster image=new Raster(WIDTH,176+n*panelHeight);image.text(28,22,"CEILING SCOUT - OBSERVED MAP - RUN "+run,3,INK);
        image.text(28,61,"FLOORS",2,FLOOR);image.text(166,61,"CEILINGS",2,CEILING);image.text(340,61,"WALLS",2,WALL);image.text(479,61,"PATH",2,PATH);image.text(586,61,"ENEMY",2,ENEMY);image.text(730,61,"CONTACT ATTEMPT",2,ATTEMPT);
        image.text(28,88,"BLANK = UNKNOWN. BURN ATTEMPT DOES NOT PROVE A KILL.",2,MUTED);
        image.text(28,113,"RUN "+safe(outcome)+" / COVERAGE "+(s.complete?"CHECKED":"INCOMPLETE")+" / CEILING CHECKS "+s.inspectedCeilings+" OF "+s.ceilingSections,2,MUTED);
        image.text(28,138,"BLUE = SPAWN / SECTION ANCHOR. ORANGE DOTS = PATH REVERSALS. UNLINKED ORIGINS STAY SEPARATE.",2,MUTED);
        int index=0;for(Map.Entry<Integer,Bounds> entry:sections.entrySet()){if(index==n)break;int section=entry.getKey(),top=176+index*panelHeight;Bounds b=entry.getValue();
            image.line(20,top,WIDTH-20,top,0x31454D,1,false);image.text(28,top+14,"SECTION "+section+" - LOCAL COORDINATES",2,INK);
            Plot plot=new Plot(b,44,top+46,WIDTH-88,panelHeight-65,s.viewportAspectRatio);
            if(s.cells!=null)for(int i=0;i<Math.min(s.cells.length,MapArchiveStore.MAX_CELLS);i++){double[] r=s.cells[i];if(r==null||r.length<4||sectionOf(r,4,s.room)!=section||!finite(r[0],r[1]))continue;
                int color=r[2]==2?SOLID:r[3]>0?0x294C48:FREE;image.rect(plot.x(r[0]-.5/48),plot.y(r[1]-.5/24),plot.x(r[0]+.5/48),plot.y(r[1]+.5/24),color);
            }
            if(s.borders!=null)for(int i=0;i<Math.min(s.borders.length,MapArchiveStore.MAX_BORDERS);i++){double[] r=s.borders[i];if(r==null||r.length<6||(int)r[5]!=section||!finite(r[0],r[1],r[2],r[3]))continue;
                int color=r[4]==1?FLOOR:r[4]==2?CEILING:WALL;image.line(plot.x(r[0]),plot.y(r[1]),plot.x(r[2]),plot.y(r[3]),color,3,r.length>6&&r[6]<.55);
            }
            if(s.coverage!=null)for(int i=0;i<Math.min(s.coverage.length,MapArchiveStore.MAX_COVERAGE);i++){double[] r=s.coverage[i];if(r==null||r.length<5||(int)r[3]!=section||!finite(r[0],r[1],r[2]))continue;image.line(plot.x(r[0]),plot.y(r[1])+6,plot.x(r[2]),plot.y(r[1])+6,r[4]>0?PATH:CEILING,2,r[4]==0);}
            double[] previous=null;int direction=0;boolean spawn=true;
            if(s.path!=null)for(int i=0;i<Math.min(s.path.length,MapArchiveStore.MAX_PATH);i++){double[] r=s.path[i];if(r==null||r.length<3||(int)r[2]!=section||!finite(r[0],r[1])){previous=null;continue;}
                if(spawn){image.dot(plot.x(r[0]),plot.y(r[1]),5,0x6DADEF);spawn=false;}
                if(previous!=null){image.line(plot.x(previous[0]),plot.y(previous[1]),plot.x(r[0]),plot.y(r[1]),PATH,1,false);double dx=r[0]-previous[0];int next=Math.abs(dx)>.012?(dx>0?1:-1):0;if(next!=0){if(direction!=0&&next!=direction)image.dot(plot.x(previous[0]),plot.y(previous[1]),4,ATTEMPT);direction=next;}}
                previous=r;
            }
            if(s.enemies!=null)for(int i=0;i<Math.min(s.enemies.length,MapArchiveStore.MAX_ENEMIES);i++){double[] r=s.enemies[i];if(r!=null&&r.length>=3&&sectionOf(r,4,s.room)==section&&finite(r[0],r[1]))image.dot(plot.x(r[0]),plot.y(r[1]),5,r[2]>0?ATTEMPT:ENEMY);}
            double[][] history=MapArchiveStore.history(s);for(int i=0;i<Math.min(history.length,MapArchiveStore.MAX_HISTORY);i++){double[] r=history[i];if(r!=null&&r.length>=7&&(int)r[3]==section&&finite(r[1],r[2]))image.dot(plot.x(r[1]),plot.y(r[2]),4,Double.isFinite(r[6])&&r[6]>=0?ATTEMPT:ENEMY);}
            index++;
        }
        if(sections.size()>n)image.text(28,image.h-23,"IMAGE PANEL LIMIT REACHED - ALL RETAINED ARRAYS AND SECTION BOUNDS ARE IN MAP.JSON",2,CEILING);
        return image.png();
    }
    private static String safe(String value){return value==null?"UNKNOWN":value.toUpperCase(Locale.US).replaceAll("[^A-Z0-9 _-]","");}
    private static int sectionOf(double[] r,int index,int fallback){return r.length>index?(int)r[index]:fallback;}
    private static boolean finite(double... values){for(double v:values)if(!Double.isFinite(v)||Math.abs(v)>=10000)return false;return true;}
    private static final class Plot {
        final Bounds b;final double sx,sy;final int left,top;
        Plot(Bounds b,int l,int t,int width,int height,double aspect){this.b=b;double a=Double.isFinite(aspect)&&aspect>0&&aspect<10?aspect:1;double scale=Math.min(width/((b.r-b.l)*a),height/(b.b-b.t));sx=scale*a;sy=scale;left=l+(int)((width-(b.r-b.l)*sx)/2);top=t+(int)((height-(b.b-b.t)*sy)/2);}
        int x(double value){return left+(int)Math.round((value-b.l)*sx);}int y(double value){return top+(int)Math.round((value-b.t)*sy);}
    }
    private static final class Raster {
        final int w,h;final int[] pixels;Raster(int w,int h){this.w=w;this.h=h;pixels=new int[w*h];Arrays.fill(pixels,BG);}
        void pixel(int x,int y,int color){if(x>=0&&y>=0&&x<w&&y<h)pixels[y*w+x]=color;}
        void rect(int l,int t,int r,int b,int color){int x1=Math.min(w,Math.max(0,Math.min(l,r))),x2=Math.max(0,Math.min(w,Math.max(l,r)+1)),y1=Math.min(h,Math.max(0,Math.min(t,b))),y2=Math.max(0,Math.min(h,Math.max(t,b)+1));if(x2<x1)return;for(int y=y1;y<y2;y++)Arrays.fill(pixels,y*w+x1,y*w+x2,color);}
        void dot(int x,int y,int radius,int color){for(int dy=-radius;dy<=radius;dy++)for(int dx=-radius;dx<=radius;dx++)if(dx*dx+dy*dy<=radius*radius)pixel(x+dx,y+dy,color);}
        void line(int x1,int y1,int x2,int y2,int color,int thickness,boolean dashed){int dx=Math.abs(x2-x1),dy=-Math.abs(y2-y1),sx=x1<x2?1:-1,sy=y1<y2?1:-1,error=dx+dy,n=0;while(true){if(!dashed||(n/6)%2==0)rect(x1-thickness/2,y1-thickness/2,x1+thickness/2,y1+thickness/2,color);if(x1==x2&&y1==y2)break;int twice=2*error;if(twice>=dy){error+=dy;x1+=sx;}if(twice<=dx){error+=dx;y1+=sy;}if(++n>100000)break;}}
        void text(int x,int y,String value,int scale,int color){for(char c:value.toCharArray()){String glyph=FONT.get(c);if(glyph!=null)for(int row=0;row<7;row++)for(int col=0;col<5;col++)if(glyph.charAt(row*5+col)=='1')rect(x+col*scale,y+row*scale,x+(col+1)*scale-1,y+(row+1)*scale-1,color);x+=6*scale;if(x>w-20)break;}}
        byte[] png()throws IOException {
            ByteArrayOutputStream result=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(result);out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
            ByteArrayOutputStream header=new ByteArrayOutputStream();DataOutputStream fields=new DataOutputStream(header);fields.writeInt(w);fields.writeInt(h);fields.write(new byte[]{8,2,0,0,0});chunk(out,"IHDR",header.toByteArray());
            ByteArrayOutputStream compressed=new ByteArrayOutputStream();try(DeflaterOutputStream z=new DeflaterOutputStream(compressed)){byte[] row=new byte[1+w*3];for(int y=0;y<h;y++){for(int x=0;x<w;x++){int c=pixels[y*w+x],i=1+x*3;row[i]=(byte)(c>>16);row[i+1]=(byte)(c>>8);row[i+2]=(byte)c;}z.write(row);}}
            chunk(out,"IDAT",compressed.toByteArray());chunk(out,"IEND",new byte[0]);out.flush();return result.toByteArray();
        }
        private static void chunk(DataOutputStream out,String name,byte[] data)throws IOException {byte[] type=name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);out.writeInt(data.length);out.write(type);out.write(data);CRC32 crc=new CRC32();crc.update(type);crc.update(data);out.writeInt((int)crc.getValue());}
    }
    private static final Map<Character,String> FONT=new HashMap<>();
    static {
        String chars="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-./:=_";
        String[] shapes={"01110/10001/10001/11111/10001/10001/10001","11110/10001/10001/11110/10001/10001/11110","01111/10000/10000/10000/10000/10000/01111","11110/10001/10001/10001/10001/10001/11110","11111/10000/10000/11110/10000/10000/11111","11111/10000/10000/11110/10000/10000/10000","01111/10000/10000/10111/10001/10001/01111","10001/10001/10001/11111/10001/10001/10001","11111/00100/00100/00100/00100/00100/11111","00111/00010/00010/00010/00010/10010/01100","10001/10010/10100/11000/10100/10010/10001","10000/10000/10000/10000/10000/10000/11111","10001/11011/10101/10101/10001/10001/10001","10001/11001/10101/10011/10001/10001/10001","01110/10001/10001/10001/10001/10001/01110","11110/10001/10001/11110/10000/10000/10000","01110/10001/10001/10001/10101/10010/01101","11110/10001/10001/11110/10100/10010/10001","01111/10000/10000/01110/00001/00001/11110","11111/00100/00100/00100/00100/00100/00100","10001/10001/10001/10001/10001/10001/01110","10001/10001/10001/10001/10001/01010/00100","10001/10001/10001/10101/10101/11011/10001","10001/10001/01010/00100/01010/10001/10001","10001/10001/01010/00100/00100/00100/00100","11111/00001/00010/00100/01000/10000/11111","01110/10001/10011/10101/11001/10001/01110","00100/01100/00100/00100/00100/00100/01110","01110/10001/00001/00010/00100/01000/11111","11110/00001/00001/01110/00001/00001/11110","00010/00110/01010/10010/11111/00010/00010","11111/10000/10000/11110/00001/00001/11110","01110/10000/10000/11110/10001/10001/01110","11111/00001/00010/00100/01000/01000/01000","01110/10001/10001/01110/10001/10001/01110","01110/10001/10001/01111/00001/00001/01110","00000/00000/00000/11111/00000/00000/00000","00000/00000/00000/00000/00000/00110/00110","00001/00001/00010/00100/01000/10000/10000","00000/00110/00110/00000/00110/00110/00000","00000/00000/11111/00000/11111/00000/00000","00000/00000/00000/00000/00000/00000/11111"};
        for(int i=0;i<chars.length();i++)FONT.put(chars.charAt(i),shapes[i].replace("/",""));
    }
}
