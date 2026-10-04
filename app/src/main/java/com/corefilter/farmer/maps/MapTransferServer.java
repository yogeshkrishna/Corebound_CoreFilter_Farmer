package com.corefilter.farmer.maps;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.*;

/** Local-only capability server owned by the background sharing service. It has no cloud client and accepts no filesystem paths. */
public final class MapTransferServer implements AutoCloseable {
    private final Context context;private final ServerSocket server;private final String token,base;
    private final Set<Socket> clients=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"map-transfer-client");t.setDaemon(true);return t;});
    private volatile boolean stopped;private final Thread acceptor;
    public MapTransferServer(Context context,InetAddress wifiAddress)throws IOException {
        this(context,wifiAddress,0,null);
    }
    public MapTransferServer(Context context,InetAddress wifiAddress,int port,String capability)throws IOException {
        if(!(wifiAddress instanceof Inet4Address)||(!wifiAddress.isSiteLocalAddress()&&!wifiAddress.isLoopbackAddress()))throw new IOException("A local Wi-Fi IPv4 address is required");
        if(capability!=null&&!capability.matches("[A-Za-z0-9_-]{43}"))throw new IOException("Invalid sharing capability");
        this.context=context.getApplicationContext();byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);token=capability==null?Base64.getUrlEncoder().withoutPadding().encodeToString(secret):capability;
        server=new ServerSocket();try{server.setReuseAddress(true);server.bind(new InetSocketAddress(wifiAddress,port),4);}catch(IOException e){server.close();workers.shutdownNow();throw e;}
        base="http://"+wifiAddress.getHostAddress()+":"+server.getLocalPort()+"/"+token;
        acceptor=new Thread(this::accept,"map-transfer-listener");acceptor.setDaemon(true);acceptor.start();
    }
    public String url(){return base+"/";}
    private void accept(){while(!stopped)try{Socket socket=server.accept();socket.setSoTimeout(10000);clients.add(socket);try{workers.execute(()->handle(socket));}catch(RejectedExecutionException busy){clients.remove(socket);socket.close();}}catch(IOException e){if(!stopped)close();}}
    private void handle(Socket socket){Socket client=socket;try{
        InputStream raw=client.getInputStream();String first=line(raw,2048);if(first==null)return;String[] parts=first.split(" ");if(parts.length!=3||!parts[2].startsWith("HTTP/1.")){respond(client,400,"Bad request");return;}
        Map<String,String> headers=new HashMap<>();int consumed=first.length();String next;
        while((next=line(raw,4096))!=null&&!next.isEmpty()){consumed+=next.length();if(consumed>8192){respond(client,431,"Headers too large");return;}int colon=next.indexOf(':');if(colon<1){respond(client,400,"Bad header");return;}String key=next.substring(0,colon).trim().toLowerCase(Locale.US);if(headers.put(key,next.substring(colon+1).trim())!=null){respond(client,400,"Duplicate header");return;}}
        String method=parts[0],path=parts[1];if(path.contains("%")||path.contains("?")||path.contains("#")||(!path.equals("/"+token)&&!path.startsWith("/"+token+"/"))){respond(client,403,"Sharing link required");return;}
        String route=path.substring(token.length()+1);if(route.isEmpty())route="/";
        if(method.equals("GET")){
            if(route.equals("/")){bytes(client,200,"text/html; charset=utf-8",MapTransferPage.html(base).getBytes(StandardCharsets.UTF_8),null);return;}
            if(route.equals("/api/maps")){JSONArray list=new JSONArray();for(MapArchiveStore.Bundle b:MapArchiveStore.list(context))list.put(b.json());bytes(client,200,"application/json",new JSONObject().put("schemaVersion",1).put("maps",list).toString().getBytes(StandardCharsets.UTF_8),null);return;}
            if(route.startsWith("/download/")){String id=route.substring("/download/".length());File f=MapArchiveStore.find(context,id);file(client,f);return;}
            if(route.startsWith("/preview/")){File f=MapArchiveStore.find(context,route.substring("/preview/".length()));try(ZipInputStream zip=new ZipInputStream(new FileInputStream(f))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)if(entry.getName().equals("map.png")){bytes(client,200,"image/png",readLimited(zip,12000000),null);return;}}respond(client,404,"Map picture not found");return;}
            if(route.equals("/receive-maps.ps1")){try(InputStream in=context.getAssets().open("receive-maps.ps1")){String script=new String(readLimited(in,65536),StandardCharsets.UTF_8).replace("__CEILING_SCOUT_BASE_URL__",base+"/");bytes(client,200,"text/plain; charset=utf-8",script.getBytes(StandardCharsets.UTF_8),"receive-maps.ps1");}return;}
            respond(client,404,"Not found");return;
        }
        if(!method.equals("POST")||!route.equals("/api/ack")){respond(client,405,"Method not allowed");return;}
        if(!("Bearer "+token).equals(headers.get("authorization"))||!headers.getOrDefault("content-type","").toLowerCase(Locale.US).startsWith("application/json")){respond(client,403,"Authenticated receipt required");return;}
        String origin=headers.get("origin");String expectedOrigin=base.substring(0,base.indexOf('/',7));if(origin!=null&&!origin.equals(expectedOrigin)){respond(client,403,"Invalid origin");return;}
        int length;try{length=Integer.parseInt(headers.getOrDefault("content-length","-1"));}catch(NumberFormatException e){length=-1;}if(length<1||length>4096){respond(client,400,"Invalid receipt size");return;}
        byte[] body=new byte[length];int at=0,n;while(at<length&&(n=raw.read(body,at,length-at))!=-1)at+=n;if(at!=length){respond(client,400,"Incomplete receipt");return;}
        JSONObject receipt=new JSONObject(new String(body,StandardCharsets.UTF_8));String id=receipt.optString("id",""),hash=receipt.optString("sha256","");
        if(!MapArchiveStore.acknowledge(context,id,hash)){respond(client,409,"Checksum does not match; phone copy retained");return;}
        bytes(client,200,"application/json",new JSONObject().put("deleted",true).put("id",id).toString().getBytes(StandardCharsets.UTF_8),null);
    }catch(FileNotFoundException e){try{respond(socket,404,"Map bundle not found");}catch(IOException ignored){}}
     catch(IOException|JSONException|RuntimeException e){try{respond(socket,400,"Transfer incomplete; unacknowledged phone copies retained");}catch(IOException ignored){}}
     finally{clients.remove(socket);try{socket.close();}catch(IOException ignored){}}}
    private static String line(InputStream in,int max)throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();int c;while((c=in.read())!=-1){if(c=='\n')break;if(b.size()>=max)throw new IOException("Request line too long");if(c!='\r')b.write(c);}return c==-1&&b.size()==0?null:b.toString(StandardCharsets.US_ASCII.name());}
    private static byte[] readLimited(InputStream in,int max)throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(b.size()+n>max)throw new IOException("Response asset too large");b.write(buffer,0,n);}return b.toByteArray();}
    private static void respond(Socket socket,int code,String message)throws IOException {bytes(socket,code,"text/plain; charset=utf-8",message.getBytes(StandardCharsets.UTF_8),null);}
    private static void header(OutputStream out,int status,String type,long length,String filename)throws IOException {
        String text="HTTP/1.1 "+status+" "+(status==200?"OK":"Error")+"\r\nContent-Type: "+type+"\r\nContent-Length: "+length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n";
        if(filename!=null)text+="Content-Disposition: attachment; filename=\""+filename+"\"\r\n";
        if(type.startsWith("text/html"))text+="Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'\r\n";
        out.write((text+"\r\n").getBytes(StandardCharsets.US_ASCII));
    }
    private static void bytes(Socket socket,int status,String type,byte[] data,String filename)throws IOException {OutputStream out=socket.getOutputStream();header(out,status,type,data.length,filename);out.write(data);out.flush();}
    private static void file(Socket socket,File file)throws IOException {OutputStream out=socket.getOutputStream();header(out,200,"application/zip",file.length(),file.getName());try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}out.flush();}
    @Override public void close(){if(stopped)return;stopped=true;try{server.close();}catch(IOException ignored){}for(Socket s:clients)try{s.close();}catch(IOException ignored){}clients.clear();workers.shutdownNow();}
}
