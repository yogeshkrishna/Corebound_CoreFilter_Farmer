package com.corefilter.farmer;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Decisions used before a release can become an install candidate. */
final class ReleasePolicy {
    static final long MAX_APK_BYTES = 150L * 1024 * 1024;
    static final Pattern SHA256 = Pattern.compile("[a-fA-F0-9]{64}");
    private static final Pattern VERSION = Pattern.compile("^v?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})$");
    static final class Release {
        final String tag, notes, apkName, apkUrl, hash, checksumUrl;
        final long bytes;
        Release(String tag, String notes, String name, String url, String hash, String checksum, long bytes) {
            this.tag=tag;this.notes=notes;apkName=name;apkUrl=url;this.hash=hash;checksumUrl=checksum;this.bytes=bytes;
        }
    }
    static String repository(String value) {
        if(value==null)throw new IllegalArgumentException("Enter owner/repository.");
        String repo=value.trim();
        if(repo.startsWith("https://github.com/"))repo=repo.substring(19);
        while(repo.endsWith("/"))repo=repo.substring(0,repo.length()-1);
        if(repo.endsWith(".git"))repo=repo.substring(0,repo.length()-4);
        String[] parts=repo.split("/",-1);
        if(parts.length!=2||!parts[0].matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}")||!parts[1].matches("[A-Za-z0-9_.-]{1,100}")||parts[1].equals(".")||parts[1].equals(".."))
            throw new IllegalArgumentException("Use a public GitHub owner/repository, such as yogeshkrishna/ceiling-scout.");
        return repo;
    }
    static boolean newer(String candidate,String current) {
        Matcher a=VERSION.matcher(candidate),b=VERSION.matcher(current);
        if(!a.matches()||!b.matches())return false;
        for(int n=1;n<=3;n++){int cmp=Integer.compare(Integer.parseInt(a.group(n)),Integer.parseInt(b.group(n)));if(cmp!=0)return cmp>0;}
        return false;
    }
    static Release parse(String json,String repo,String current) throws Exception {
        repo=repository(repo);
        JSONObject release=new JSONObject(json);
        if(release.optBoolean("draft")||release.optBoolean("prerelease"))return null;
        String tag=release.optString("tag_name");
        if(!newer(tag,current))return null;
        // GitHub's API redirects a renamed repository. Its canonical release page
        // may then name the new repository while the saved slug still has the old one.
        // Keep owner/HTTPS validation and require every asset to use that same repo.
        repo=canonicalRepository(release.optString("html_url"),repo,tag);
        JSONArray assets=release.optJSONArray("assets");
        if(assets==null)throw new IllegalArgumentException("This release has no downloadable APK.");
        JSONObject apk=null;
        for(int i=0;i<assets.length();i++){
            JSONObject asset=assets.getJSONObject(i);String name=asset.optString("name");
            if(name.equals("Ceiling-Scout.apk")||name.equals("Ceiling-Scout-debug.apk")){
                if(apk!=null)throw new IllegalArgumentException("The release has multiple app APKs; publish one.");
                apk=asset;
            }
        }
        if(apk==null)throw new IllegalArgumentException("The release needs a Ceiling-Scout.apk attachment.");
        long size=apk.optLong("size",0);
        if(size<1||size>MAX_APK_BYTES)throw new IllegalArgumentException("The APK size is outside the supported range.");
        String url=apk.optString("browser_download_url");validateAssetUrl(url,repo);
        String digest=apk.optString("digest"), hash="";
        if(digest.startsWith("sha256:")&&SHA256.matcher(digest.substring(7)).matches())hash=digest.substring(7).toLowerCase(Locale.ROOT);
        String checksum="";
        for(int i=0;i<assets.length();i++){
            JSONObject asset=assets.getJSONObject(i);String name=asset.optString("name");
            if(name.equals(apk.optString("name")+".sha256")||name.equals("SHA256SUMS")){
                checksum=asset.optString("browser_download_url");validateAssetUrl(checksum,repo);
                if(name.endsWith(".sha256"))break;
            }
        }
        if(hash.isEmpty()&&checksum.isEmpty())throw new IllegalArgumentException("The APK needs a SHA-256 digest or checksum attachment.");
        String notes=release.optString("body","");if(notes.length()>2500)notes=notes.substring(0,2500)+"…";
        return new Release(tag,notes,apk.optString("name"),url,hash,checksum,size);
    }
    static void validateAssetUrl(String url,String repo) {
        try{
            URI uri=new URI(url);String expected="/"+repo+"/releases/download/";
            if(!"https".equals(uri.getScheme())||!"github.com".equalsIgnoreCase(uri.getHost())||uri.getUserInfo()!=null||uri.getPort()!=-1||uri.getFragment()!=null||!uri.getPath().startsWith(expected))throw new IllegalArgumentException();
        }catch(Exception ex){throw new IllegalArgumentException("The release attachment must come from this GitHub repository.");}
    }
    static String canonicalRepository(String page,String requested,String tag) {
        if(page==null||page.isEmpty())return requested;
        try{
            URI uri=new URI(page);String[] parts=uri.getRawPath().split("/",-1);
            if(!"https".equals(uri.getScheme())||!"github.com".equalsIgnoreCase(uri.getHost())||uri.getUserInfo()!=null||uri.getPort()!=-1||uri.getQuery()!=null||uri.getFragment()!=null||parts.length!=6||!parts[3].equals("releases")||!parts[4].equals("tag")||!parts[5].equals(tag)||!parts[1].equalsIgnoreCase(requested.split("/")[0]))throw new IllegalArgumentException();
            return repository(parts[1]+"/"+parts[2]);
        }catch(Exception ex){throw new IllegalArgumentException("The canonical release must belong to the configured GitHub owner.");}
    }
    static boolean allowedNetworkUrl(URI uri) {
        String host=uri.getHost();
        return "https".equals(uri.getScheme())&&uri.getUserInfo()==null&&uri.getPort()==-1&&host!=null&&
                (host.equals("github.com")||host.equals("api.github.com")||host.endsWith(".githubusercontent.com"));
    }
    static String checksum(String text,String name) {
        for(String line:text.split("\\r?\\n")){
            String trimmed=line.trim();String[] parts=trimmed.split("\\s+",2);
            if(parts.length>0&&SHA256.matcher(parts[0]).matches()){
                if(parts.length==1)return parts[0].toLowerCase(Locale.ROOT);
                String listed=parts[1];if(listed.startsWith("*"))listed=listed.substring(1);
                if(listed.equals(name))return parts[0].toLowerCase(Locale.ROOT);
            }
        }
        throw new IllegalArgumentException("The checksum attachment has no hash for this APK.");
    }
    static void validateIdentity(String expectedPackage,long installedCode,List<String> installedSigners,String candidatePackage,long candidateCode,List<String> candidateSigners) {
        if(!expectedPackage.equals(candidatePackage))throw new IllegalArgumentException("The download is for a different app.");
        if(candidateCode<=installedCode)throw new IllegalArgumentException("The downloaded APK is not newer than this installation.");
        if(installedSigners==null||installedSigners.isEmpty()||candidateSigners==null||candidateSigners.isEmpty()||!new HashSet<>(installedSigners).equals(new HashSet<>(candidateSigners)))
            throw new IllegalArgumentException("The APK was signed with a different key. The publisher must reuse this app’s original signing key.");
    }
}
