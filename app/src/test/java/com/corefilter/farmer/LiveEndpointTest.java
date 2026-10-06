package com.corefilter.farmer;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveEndpointTest {
    private final String key="A".repeat(43);
    @Test public void preservesStablePrivateLink(){LiveEndpoint e=LiveEndpoint.parse("http://192.168.68.63:8767/connect/"+key+"/");assertEquals("http://192.168.68.63:8767",e.base);assertEquals(key,e.token);}
    @Test public void refusesPublicDestinationsAndMalformedKeys(){for(String link:new String[]{"http://8.8.8.8:8767/connect/"+key,"http://localhost:8767/connect/"+key,"http://192.168.68.63:8767/connect/short","http://user@192.168.68.63:8767/connect/"+key,"http://192.168.68.63:8767/connect/"+key+"?a=1"}){try{LiveEndpoint.parse(link);fail(link);}catch(IllegalArgumentException expected){}}}
}
