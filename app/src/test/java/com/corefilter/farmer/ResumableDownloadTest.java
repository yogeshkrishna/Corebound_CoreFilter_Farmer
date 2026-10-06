package com.corefilter.farmer;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ResumableDownloadTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private final byte[] apk=new byte[1048576];
    private TestServer server;
    private final List<Long> offsets=Collections.synchronizedList(new ArrayList<>());
    @Before public void start()throws Exception{
        new Random(716).nextBytes(apk);
        server=new TestServer();
    }
    @After public void stop(){server.stop(0);}
    private ResumableDownload.Source source(){return offset->{
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+server.getAddress().getPort()+"/apk").openConnection();
        c.setConnectTimeout(1000);c.setReadTimeout(150);
        if(offset>0)c.setRequestProperty("Range","bytes="+offset+"-");return c;
    };}
    private ResumableDownload.Monitor monitor(){return new ResumableDownload.Monitor(){
        public void check(){} public void progress(String message){}
    };}
    private long offset(Exchange ex){String range=ex.getRequestHeaders().getFirst("Range");long n=range==null?0:Long.parseLong(range.substring(6,range.length()-1));offsets.add(n);return n;}
    private void respond(Exchange ex,long start,boolean partial,int count)throws IOException{
        if(partial)ex.getResponseHeaders().set("Content-Range","bytes "+start+"-"+(apk.length-1)+"/"+apk.length);
        ex.sendResponseHeaders(partial?206:200,apk.length-start);
        try(OutputStream out=ex.getResponseBody()){out.write(apk,(int)start,count);}
        catch(IOException ignored){} // Deliberately close incomplete bodies in fault fixtures.
    }
    @Test public void resumesAfterConnectionDiesAt75Percent()throws Exception{
        server.createContext("/apk",ex->{long start=offset(ex);respond(ex,start,start>0,start==0?apk.length*3/4:apk.length-(int)start);});server.start();
        File part=folder.newFile();ResumableDownload.fetch(part,apk.length,source(),monitor());
        assertArrayEquals(apk,Files.readAllBytes(part.toPath()));assertEquals(Arrays.asList(0L,786432L),offsets);
    }
    @Test public void resumesSavedFileAfterNewDownloadInvocation()throws Exception{
        server.createContext("/apk",ex->{long n=offset(ex);respond(ex,n,n>0,apk.length-(int)n);});server.start();
        File part=folder.newFile();Files.write(part.toPath(),Arrays.copyOf(apk,345678));
        ResumableDownload.fetch(part,apk.length,source(),monitor());
        assertArrayEquals(apk,Files.readAllBytes(part.toPath()));assertEquals(Arrays.asList(345678L),offsets);
    }
    @Test public void serverIgnoringRangeRestartsWithoutAppendingDuplicateBytes()throws Exception{
        server.createContext("/apk",ex->{offset(ex);respond(ex,0,false,apk.length);});server.start();
        File part=folder.newFile();Files.write(part.toPath(),Arrays.copyOf(apk,250000));
        ResumableDownload.fetch(part,apk.length,source(),monitor());assertArrayEquals(apk,Files.readAllBytes(part.toPath()));
    }
    @Test public void silentConnectionTimesOutAndResumes()throws Exception{
        server.createContext("/apk",ex->{long start=offset(ex);
            if(start>0){respond(ex,start,true,apk.length-(int)start);return;}
            ex.sendResponseHeaders(200,apk.length);try(OutputStream out=ex.getResponseBody()){
                out.write(apk,0,apk.length/2);out.flush();try{Thread.sleep(400);}catch(InterruptedException ignored){}
            }catch(IOException ignored){}
        });server.start();File part=folder.newFile();
        ResumableDownload.fetch(part,apk.length,source(),monitor());assertArrayEquals(apk,Files.readAllBytes(part.toPath()));assertTrue(offsets.stream().anyMatch(n->n>0));
    }
    @Test public void persistentDisconnectIsBoundedAndKeepsDownload()throws Exception{
        server.createContext("/apk",ex->{long n=offset(ex);respond(ex,n,n>0,10000);});server.start();File part=folder.newFile();
        try{ResumableDownload.fetch(part,apk.length,source(),monitor());fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("progress is saved"));}
        assertEquals(4,offsets.size());assertEquals(40000,part.length());assertArrayEquals(Arrays.copyOf(apk,40000),Files.readAllBytes(part.toPath()));
    }
    @Test public void wrongRangeNeverWritesIntoPartialFile()throws Exception{
        server.createContext("/apk",ex->{offset(ex);ex.getResponseHeaders().set("Content-Range","bytes 0-1048575/1048576");ex.sendResponseHeaders(206,0);ex.close();});server.start();
        File part=folder.newFile();Files.write(part.toPath(),Arrays.copyOf(apk,1000));
        try{ResumableDownload.fetch(part,apk.length,source(),monitor());fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("invalid resume offset"));}
        assertArrayEquals(Arrays.copyOf(apk,1000),Files.readAllBytes(part.toPath()));assertEquals(1,offsets.size());
    }
    @Test public void cancelKeepsBytesAndNextAttemptResumes()throws Exception{
        server.createContext("/apk",ex->{long n=offset(ex);respond(ex,n,n>0,apk.length-(int)n);});server.start();File part=folder.newFile();AtomicInteger checks=new AtomicInteger();
        ResumableDownload.Monitor canceled=new ResumableDownload.Monitor(){
            public void check()throws InterruptedIOException{if(checks.incrementAndGet()>=10)throw new InterruptedIOException("Canceled");}
            public void progress(String text){}
        };
        try{ResumableDownload.fetch(part,apk.length,source(),canceled);fail();}catch(InterruptedIOException expected){}
        long kept=part.length();assertTrue(kept>0&&kept<apk.length);
        ResumableDownload.fetch(part,apk.length,source(),monitor());assertArrayEquals(apk,Files.readAllBytes(part.toPath()));assertEquals(Long.valueOf(kept),offsets.get(1));
    }
    // A real HTTP socket fixture using only Android-compatible java.net APIs.
    private interface Handler {void handle(Exchange exchange)throws IOException;}
    private static final class Headers extends TreeMap<String,String>{
        Headers(){super(String.CASE_INSENSITIVE_ORDER);}String getFirst(String key){return get(key);}void set(String key,String value){put(key,value);}
    }
    private static final class Exchange implements Closeable{
        final Socket socket;final Headers request=new Headers(),response=new Headers();
        Exchange(Socket socket)throws IOException{this.socket=socket;BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));reader.readLine();
            String line;while((line=reader.readLine())!=null&&!line.isEmpty()){int colon=line.indexOf(':');if(colon>0)request.put(line.substring(0,colon),line.substring(colon+1).trim());}}
        Headers getRequestHeaders(){return request;}Headers getResponseHeaders(){return response;}
        void sendResponseHeaders(int code,long length)throws IOException{
            StringBuilder text=new StringBuilder("HTTP/1.1 "+code+" Response\r\nConnection: close\r\nContent-Length: "+length+"\r\n");
            for(Map.Entry<String,String> header:response.entrySet())text.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            socket.getOutputStream().write(text.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
        }
        OutputStream getResponseBody()throws IOException{return socket.getOutputStream();}
        @Override public void close()throws IOException{socket.close();}
    }
    private static final class TestServer{
        final ServerSocket socket;final ExecutorService handlers=Executors.newCachedThreadPool();Handler handler;
        TestServer()throws IOException{socket=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));}
        InetSocketAddress getAddress(){return (InetSocketAddress)socket.getLocalSocketAddress();}
        void createContext(String path,Handler next){handler=next;}
        void start(){Thread accept=new Thread(()->{while(!socket.isClosed())try{Socket client=socket.accept();client.setSoTimeout(2000);handlers.execute(()->{try(Exchange ex=new Exchange(client)){handler.handle(ex);}catch(IOException ignored){}});}catch(IOException ignored){}});accept.setDaemon(true);accept.start();}
        void stop(int delay){try{socket.close();}catch(IOException ignored){}handlers.shutdownNow();}
    }
}
