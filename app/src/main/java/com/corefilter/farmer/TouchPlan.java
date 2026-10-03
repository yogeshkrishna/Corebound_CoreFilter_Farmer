package com.corefilter.farmer;

import android.accessibilityservice.GestureDescription;
import android.graphics.Path;

/** Separate DOWN/UP jump taps alongside one horizontal hold. No held-jump substitution. */
final class TouchPlan {
    static GestureDescription build(int w,int h,double x,double y,boolean move,double jumpX,double jumpY,int count,long spacing,long tapMs,long requestedDuration){
        if(w<=0||h<=0)throw new IllegalArgumentException("Invalid screen size");
        int jumps=Math.max(0,Math.min(4,count));
        long tap=Math.max(40,Math.min(140,tapMs));
        long gap=Math.max(tap+35,Math.min(450,spacing));
        long duration=Math.min(700,Math.max(80,requestedDuration));
        while(jumps>0&&(jumps-1)*gap+tap>700)jumps--;
        if(jumps>0)duration=Math.max(duration,(jumps-1)*gap+tap);
        GestureDescription.Builder b=new GestureDescription.Builder();
        if(move)b.addStroke(stroke(w,h,x,y,0,duration));
        for(int i=0;i<jumps;i++)b.addStroke(stroke(w,h,jumpX,jumpY,i*gap,tap));
        if(!move&&jumps==0)throw new IllegalArgumentException("Empty touch plan");
        return b.build();
    }
    static GestureDescription tap(int w,int h,double x,double y){return new GestureDescription.Builder().addStroke(stroke(w,h,x,y,0,65)).build();}
    private static GestureDescription.StrokeDescription stroke(int w,int h,double x,double y,long start,long duration){
        if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>=1||y<0||y>=1)throw new IllegalArgumentException("Touch outside screen");
        Path p=new Path();p.moveTo((float)(x*w),(float)(y*h));return new GestureDescription.StrokeDescription(p,start,duration);
    }
}
