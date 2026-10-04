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
        int registeredViews=0,uncertainViews=0;double previousCameraY=0;
        com.corefilter.farmer.engine.MapNavigator.Snapshot previous=null;
        for(int time:new int[]{5500,5750,6000,6250,6500}){
            FarmEngine.Frame f=frame(root,String.format("manual_%.2f.png",time/1000.),time,camera);
            observer.observe(f);
            com.corefilter.farmer.engine.MapNavigator.Snapshot snapshot=observer.navigationSnapshot();
            if(time>5500&&f.cameraConfidence>.55){
                registeredViews++;
                check(Double.isFinite(f.cameraY),"Registered foreground supplies a finite camera displacement");
            }else if(time>5500){
                uncertainViews++;
                check(f.cameraY==previousCameraY,"Occluded foreground cannot invent camera displacement at "+time);
                check(java.util.Arrays.deepEquals(snapshot.cells,previous.cells),"Unregistered view cannot repaint the terrain atlas at "+time);
                check(snapshot.path.length==previous.path.length,"Unregistered view cannot append a fabricated world path at "+time);
            }
            previousCameraY=f.cameraY;previous=snapshot;
        }
        check(registeredViews>0&&uncertainViews>0,"Replay exercises both registered and effect-obscured terrain");
        check(observer.navigationSnapshot().remainingJumps==7,"Observing manual jumps must not invent injected pulses");
        System.out.println("NavigationReplay: "+checks+" checks passed (recorded walls and frozen mapping during effect-obscured camera views).");
    }
}
