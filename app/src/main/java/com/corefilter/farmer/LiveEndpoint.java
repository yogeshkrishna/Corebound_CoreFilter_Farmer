package com.corefilter.farmer;

import java.net.URI;

/** Pairing accepts literal private IPv4 addresses only; no DNS or public upload targets. */
public final class LiveEndpoint {
    public final String base, token;
    private LiveEndpoint(String base,String token){this.base=base;this.token=token;}
    public static LiveEndpoint parse(String value){
        URI u=URI.create(value.trim());String host=u.getHost();
        if(!"http".equals(u.getScheme())||host==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null)throw new IllegalArgumentException("Paste the laptop connection link");
        String[] octets=host.split("\\.");if(octets.length!=4)throw new IllegalArgumentException("Use the laptop's Wi-Fi IPv4 address");
        int[] ip=new int[4];for(int i=0;i<4;i++){if(!octets[i].matches("0|[1-9][0-9]{0,2}"))throw new IllegalArgumentException("Invalid laptop address");ip[i]=Integer.parseInt(octets[i]);if(ip[i]>255)throw new IllegalArgumentException("Invalid laptop address");}
        if(!(ip[0]==10||ip[0]==192&&ip[1]==168||ip[0]==172&&ip[1]>=16&&ip[1]<=31))throw new IllegalArgumentException("The laptop must be on your local Wi-Fi");
        int port=u.getPort();if(port<1024||port>65535)throw new IllegalArgumentException("Invalid laptop port");
        String path=u.getPath();if(path==null||!path.matches("/connect/[A-Za-z0-9_-]{43}/?"))throw new IllegalArgumentException("The connection key is missing");
        String token=path.split("/")[2];return new LiveEndpoint("http://"+host+":"+port,token);
    }
    public String link(){return base+"/connect/"+token;}
}
