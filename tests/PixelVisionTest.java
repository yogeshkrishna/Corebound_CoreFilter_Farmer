import com.corefilter.farmer.vision.PixelVision;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import com.corefilter.farmer.engine.FarmEngine;
import com.corefilter.farmer.engine.ScreenInterpreter;
import java.util.Arrays;

/** Evidence-based tests: call with repository root; requires extracted video frames. */
public class PixelVisionTest {
    private static int checks;
    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }
    private static PixelVision.Result read(File root, String name, double scale) throws Exception {
        BufferedImage source = ImageIO.read(new File(root,"analysis/"+name));
        int w=(int)(source.getWidth()*scale),h=(int)(source.getHeight()*scale);
        BufferedImage frame=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g=frame.createGraphics();
        g.drawImage(source,0,0,w,h,null);g.dispose();
        return PixelVision.analyse(frame.getRGB(0,0,w,h,null,0,w),w,h);
    }
    private static PixelVision.Result terrain(boolean floor, boolean ceiling) {
        BufferedImage frame=new BufferedImage(600,270,BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g=frame.createGraphics();
        g.setColor(new java.awt.Color(210,220,40));
        g.fillRect(25,8,150,18); // HUD context.
        g.fillRect(268,145,44,32); // Crawler-sized gold hull.
        g.setColor(new java.awt.Color(35,85,175));g.fillRect(283,153,8,8);
        g.setColor(new java.awt.Color(90,90,102));
        if(floor)g.fillRect(240,178,100,5);
        if(ceiling)g.fillRect(240,141,100,3);
        g.dispose();
        return PixelVision.analyse(frame.getRGB(0,0,600,270,null,0,600),600,270);
    }
    private static void fieldChecks(File root,double scale) throws Exception {
        PixelVision.Result menu=read(root,"v2/field_0.png",scale);
        check(!menu.gameplay&&menu.playButton&&menu.selectedPanel,"Selected level panel geometry at "+scale);
        check(Math.abs(menu.playX-.703)<.025&&Math.abs(menu.playY-.806)<.025,"Pixel Play centre at "+scale);
        FarmEngine.Frame selected=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(
                new FarmEngine.Token("Lost Scrapyard",.60,.585,.89,.635),
                new FarmEngine.Token("Frozen 5t",.60,.64,.80,.68)),menu);
        FarmEngine.Action start=new FarmEngine(new FarmEngine.Config()).next(selected);
        check(start.kind==FarmEngine.Kind.TAP&&Math.abs(start.x-menu.playX)<.001,
                "Verified target starts from the pixel Play position at "+scale);
        PixelVision.Result landing=read(root,"v2/field_10.png",scale);
        check(landing.gameplay&&landing.playerConfidence>.5&&landing.grounded&&!landing.ceilingReached,
                "Slotted platform supports both crawler feet at "+scale);
        PixelVision.Result air=read(root,"v2/field_25.png",scale);
        check(air.gameplay&&air.playerConfidence>.5&&!air.grounded&&!air.ceilingReached,
                "Airborne crawler does not recharge jumps at "+scale);
        for(String name:new String[]{"40","130"}) {
            PixelVision.Result enemy=read(root,"v2/field_"+name+".png",scale);
            check(enemy.gameplay&&enemy.enemyBoxes.length==1,"Visible hoverer candidate found at "+name+"s and "+scale);
            double[] box=enemy.enemyBoxes[0];
            check(box[0]>enemy.playerX&&box[1]<enemy.playerY,"Enemy above/ahead retained for pursuit at "+name+"s");
            check(box[4]==0&&box[5]==0,"Pixels do not assert burning or Dreadnought identity at "+name+"s");
        }
        PixelVision.Result end=read(root,"v2/field_170.png",scale);
        check(!end.gameplay&&!end.playButton&&!end.rewardButton&&!end.filterLoot,"No reward strip on field completion at "+scale);
        FarmEngine.Frame endFrame=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(
                new FarmEngine.Token("Complete!",.40,.04,.61,.13),
                new FarmEngine.Token("Continue",.41,.87,.60,.95)),end);
        FarmEngine.Action next=new FarmEngine(new FarmEngine.Config()).next(endFrame);
        check(next.kind==FarmEngine.Kind.TAP&&Math.abs(next.x-.505)<.005,
                "Completion without an ad uses the observed centre Continue at "+scale);
    }
    private static void v3Checks(File root,double scale) throws Exception {
        for(String name:new String[]{"56.00","58.00","64.00"}) {
            PixelVision.Result wall=read(root,"v3/farmer_"+name+".png",scale);
            check(wall.gameplay&&wall.controlsDetected&&wall.playerConfidence>.5,"Overlay-covered HUD uses movement control evidence: "+name+" / "+scale);
            check(wall.wallRight&&!wall.wallLeft,"Actual full-height wall contact detected: "+name+" / "+scale);
            check(wall.playerLeft<wall.playerX&&wall.playerRight>wall.playerX&&wall.playerTop<wall.playerY&&wall.playerBottom>wall.playerY,"Crawler bounds contain the observed body at "+scale);
            int walls=0;for(int y=4;y<20;y++)for(int x=24;x<28;x++)if(wall.terrainCells[y*48+x]==2)walls++;
            check(walls>=5,"Visible right barrier contributes solid terrain cells at "+scale);
            check(wall.terrainCells[0]==0&&wall.terrainCells[20*48+8]==0,"HUD and movement controls remain unknown terrain at "+scale);
        }
        PixelVision.Result red=read(root,"v3/farmer_15.00.png",scale);
        check(red.gameplay&&red.enemyBoxes.length>=1,"Non-pink compact red-core bot is observed at "+scale);
        boolean redAhead=false;for(double[] box:red.enemyBoxes)redAhead|=box[0]>.74&&box[2]<.86;
        check(redAhead,"Missed ground bot is retained to the right at "+scale);
        PixelVision.Result cyan=read(root,"v3/farmer_168.00.png",scale);
        boolean cyanAhead=false,orbMisread=false;
        for(double[] box:cyan.enemyBoxes){cyanAhead|=box[0]>.83;orbMisread|=box[0]<.75;}
        check(cyan.gameplay&&cyanAhead,"Floor-supported cyan armour candidate observed at "+scale);
        check(!orbMisread,"Separate allied orb / drop is not labelled an enemy at "+scale);
        PixelVision.Result before=read(root,"v3/manual_8.75.png",scale),landed=read(root,"v3/manual_9.00.png",scale),settled=read(root,"v3/manual_9.25.png",scale);
        check(before.gameplay&&!before.grounded,"Descending frame cannot replenish Hookshot jumps at "+scale);
        check(landed.gameplay&&landed.grounded&&settled.grounded,"Visible floor confirms landing in two independent recorded frames at "+scale);
    }
    public static void main(String[] args) throws Exception {
        File root=new File(args.length>0?args[0]:".");
        for(double scale:new double[]{.5,1.,2.}) {
            v3Checks(root,scale);
            fieldChecks(root,scale);
            PixelVision.Result positive=read(root,"clip2_56.5.png",scale);
            check(!positive.gameplay,"Result is not gameplay at "+scale);
            check(positive.rewardButton,"Reward frame found at "+scale);
            check(positive.filterLoot,"Purple reward filter found at "+scale);
            check(positive.lootFilterCount>=1,"Main green filter found at "+scale);
            FarmEngine.Frame rewardFrame=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(new FarmEngine.Token("Complete!",.4,.03,.6,.15),new FarmEngine.Token("Continue",.22,.81,.46,.95)),positive);
            FarmEngine.Action rewardAction=new FarmEngine(new FarmEngine.Config()).next(rewardFrame);
            check(rewardAction.kind==FarmEngine.Kind.TAP&&rewardAction.x>.52,"Actual video pixel evidence selects the FILTER AD at "+scale);
            PixelVision.Result negative=read(root,"clip1_81.5.png",scale);
            check(negative.rewardButton,"Non-filter reward frame found at "+scale);
            check(!negative.filterLoot,"No false filter from blue weapons at "+scale);
            FarmEngine.Frame ordinary=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(new FarmEngine.Token("Complete!",.4,.03,.6,.15),new FarmEngine.Token("Continue",.22,.81,.46,.95)),negative);
            FarmEngine.Action skipAction=new FarmEngine(new FarmEngine.Config()).next(ordinary);
            check(skipAction.kind==FarmEngine.Kind.TAP&&skipAction.x<.5,"Actual ordinary reward selects Continue at "+scale);
            for(String file:new String[]{"clip2_14.0.png","clip2_49.0.png","clip2_55.png"}) {
                PixelVision.Result game=read(root,file,scale);
                check(game.gameplay,"HUD found in "+file+" at "+scale);
                check(!game.filterLoot&&!game.rewardButton,"No gameplay reward false positive: "+file);
                check(game.controlsDetected&&Math.abs(game.leftX-.166)<.035&&Math.abs(game.rightX-.282)<.035,"Movement controls detected at "+scale+" in "+file);
                if(!file.contains("55")) {
                    check(game.playerX>=0&&game.playerY>=0,"Visible player found in "+file+" at "+scale);
                    double expectedX=file.contains("14.")?.363:.578;
                    double expectedY=file.contains("14.")?.550:.634;
                    check(Math.abs(game.playerX-expectedX)<.06&&Math.abs(game.playerY-expectedY)<.10,
                        "Player near observed body: "+file+" got "+game.playerX+","+game.playerY);
                }
            }
            for(String file:new String[]{"clip2_58.png","clip1_83.png","clip2_60.png","clip2_0.png"}) {
                PixelVision.Result r=read(root,file,scale);
                check(!r.gameplay&&!r.rewardButton&&!r.filterLoot,"Menu/modal correctly gated: "+file+" at "+scale);
            }
        }
        boolean rejected=false;
        try{PixelVision.analyse(new int[2],2,2);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Invalid dimensions rejected");
        check(!PixelVision.analyse(new int[100],10,10).gameplay,"Blank portrait/square frame safe");
        PixelVision.Result floor=terrain(true,false),ceiling=terrain(false,true),air=terrain(false,false);
        check(floor.gameplay&&floor.grounded&&!floor.ceilingReached,"Controlled geometry: floor support only");
        check(ceiling.gameplay&&ceiling.ceilingReached&&!ceiling.grounded,"Controlled geometry: ceiling contact only");
        check(air.gameplay&&!air.grounded&&!air.ceilingReached,"Controlled geometry: neither contact in the air");
        check(air.enemyBoxes.length==0,"Controlled geometry: player alone is not an enemy candidate");
        System.out.println("PixelVision: "+checks+" checks passed (video fixtures at 3 resolutions plus controlled terrain geometry).");
    }
}
