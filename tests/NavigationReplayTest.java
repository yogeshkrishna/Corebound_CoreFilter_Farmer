import com.corefilter.farmer.engine.FarmEngine;
import com.corefilter.farmer.engine.ScreenInterpreter;
import com.corefilter.farmer.vision.PixelVision;
import com.corefilter.farmer.vision.TemporalVision;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Collections;
import javax.imageio.ImageIO;

/** Real pixels through camera tracking, interpretation and the movement controller.
 * This checks decisions on recorded observations; it does not simulate injected game input. */
public final class NavigationReplayTest {
    private static int checks;
    private static void check(boolean condition,String description){checks++;if(!condition)throw new AssertionError(description);}
    private static FarmEngine.Frame frame(File root,String name,long timestamp,TemporalVision camera)throws Exception{
        BufferedImage image=ImageIO.read(new File(root,"analysis/v3/"+name));
        int[] pixels=image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());
        PixelVision.Result v=PixelVision.analyse(pixels,image.getWidth(),image.getHeight());
        camera.update(pixels,image.getWidth(),image.getHeight(),v,timestamp);
        return ScreenInterpreter.interpret(timestamp+120,timestamp,"com.Overcurve.Corebound",Collections.emptyList(),v);
    }
    public static void main(String[] args)throws Exception{
        File root=new File(args.length>0?args[0]:".");
        FarmEngine wallEngine=new FarmEngine(new FarmEngine.Config());TemporalVision camera=new TemporalVision();
        for(int time:new int[]{56,58,64}){
            FarmEngine.Frame f=frame(root,String.format("farmer_%d.00.png",time),time*1000L,camera);
            check(f.gameplay&&f.wallRight,"Recorded wall contact must reach policy at "+time);
            FarmEngine.Action a=wallEngine.next(f);
            check(a.kind!=FarmEngine.Kind.PAUSE,"Fresh readable wall replay must remain recoverable at "+time+": "+a.reason);
            check(a.direction!=1,"The wall replay must not hold right into solid terrain at "+time+": "+a.reason);
            check(a.jumpCount<=1,"Wall recovery cannot emit a blind multi-jump batch");
        }
        FarmEngine observer=new FarmEngine(new FarmEngine.Config());camera=new TemporalVision();
        double previousScreenY=0,previousWorldY=0;
        for(int time:new int[]{5500,5750,6000,6250,6500}){
            FarmEngine.Frame f=frame(root,String.format("manual_%.2f.png",time/1000.),time,camera);
            if(time>5500)check(f.cameraConfidence>.55,"Recorded terrain must be registered before world motion is used at "+time);
            observer.observe(f);
            if(time==6500){
                check(f.playerY<previousScreenY,"The recorded screen position appears to rise");
                check(observer.navigationSnapshot().playerY>previousWorldY+.20,"Planner must place the crawler lower in world space during the recorded fall");
                check(observer.navigationSnapshot().verticalVelocity>0,"Planner must classify corrected world motion as descent");
            }
            previousScreenY=f.playerY;previousWorldY=observer.navigationSnapshot().playerY;
        }
        check(observer.navigationSnapshot().remainingJumps==7,"Observing manual jumps must not invent injected pulses");
        System.out.println("NavigationReplay: "+checks+" checks passed (recorded wall and camera-corrected Hookshot descent through the complete controller).");
    }
}
