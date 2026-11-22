package org.lambdaproject.lambda;

import android.graphics.*;
import android.view.View;

/** Observed M1-M8 stages, deliberately not a fake success percentage. */
final class WpsSteps extends View {
    final MainActivity a;final Paint paint=new Paint(3);int mask,current;
    WpsSteps(MainActivity a){super(a);this.a=a;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
    void set(int mask,int current){if(this.mask==mask&&this.current==current)return;this.mask=mask;this.current=current;setContentDescription(a.tr("Mensajes WPS observados","Observed WPS messages")+": "+Integer.bitCount(mask)+" / 8");invalidate();}
    @Override protected void onDraw(Canvas c){
        float step=getWidth()/8f,w=step-a.dp(4),y=a.dp(5),h=a.dp(5);
        paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));paint.setTextSize(a.dp(11));paint.setTextAlign(Paint.Align.CENTER);
        for(int i=0;i<8;i++){boolean seen=(mask&(1<<i))!=0;float x=i*step+a.dp(2);
            paint.setColor(seen?a.foreground:a.divider);c.drawRoundRect(x,y,x+w,y+h,h/2,h/2,paint);
            paint.setColor(seen?a.foreground:a.secondary);c.drawText("M"+(i+1),x+w/2,a.dp(29),paint);
            if(current==i+1){paint.setColor(a.foreground);c.drawCircle(x+w/2,a.dp(39),a.dp(2),paint);}
        }
    }
}
