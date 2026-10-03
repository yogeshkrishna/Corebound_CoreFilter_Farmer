package com.corefilter.farmer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.*;

final class Ui {
    static final int BG=Color.rgb(11,23,26),PANEL=Color.rgb(22,39,43),INK=Color.rgb(235,245,240),MUTED=Color.rgb(158,184,181),MINT=Color.rgb(151,233,200),AMBER=Color.rgb(245,199,116);
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    static TextView text(Context c,String s,int size,int color){TextView t=new TextView(c);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(c,4),0,dp(c,4));t.setLineSpacing(dp(c,2),1);return t;}
    static Button button(Context c,String s,Runnable action){
        Button b=new Button(c);b.setText(s);b.setAllCaps(false);b.setTextColor(BG);b.setTextSize(14);b.setTypeface(null,Typeface.BOLD);
        b.setMinHeight(dp(c,48));b.setMinimumHeight(dp(c,48));b.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));
        b.setBackgroundTintList(ColorStateList.valueOf(MINT));b.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(c,4),0,0);b.setLayoutParams(lp);return b;
    }
    static Button secondaryButton(Context c,String s,Runnable action){Button b=button(c,s,action);b.setTextColor(INK);b.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(36,59,63)));return b;}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout card(Context c){LinearLayout l=column(c);int p=dp(c,16);l.setPadding(p,p,p,p);l.setBackground(bg(PANEL,dp(c,16)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(c,14),0,0);l.setLayoutParams(lp);return l;}
    static void title(LinearLayout l,String s){TextView t=text(l.getContext(),s,18,INK);t.setTypeface(null,Typeface.BOLD);l.addView(t);}
}
