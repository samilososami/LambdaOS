package org.lambdaproject.lambda;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Resolution-independent UI pictograms, with one purposeful USB insertion loop. */
final class RadioIllustration extends View {
    static final int HANDSHAKE=0,PHONE=1,ANTENNA=2,PLUG=3,SCAN=4,CAPTURE=5,CHECK=6;
    private final Paint p=new Paint(3);private final int kind,color;private final boolean moving;
    private ValueAnimator animator;private float phase;
    RadioIllustration(Context c,int kind,int color,boolean moving){super(c);this.kind=kind;this.color=color;this.moving=moving;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(moving&&ValueAnimator.areAnimatorsEnabled()){animator=ValueAnimator.ofFloat(0,1);animator.setDuration(2400);animator.setInterpolator(new android.view.animation.LinearInterpolator());animator.setRepeatCount(ValueAnimator.INFINITE);animator.addUpdateListener(v->{phase=(float)v.getAnimatedValue();invalidate();});animator.start();}}
    @Override protected void onDetachedFromWindow(){if(animator!=null){animator.cancel();animator=null;}super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(animator!=null){if(visibility==VISIBLE)animator.resume();else animator.pause();}}
    private void line(Canvas c,float... points){Path path=new Path();path.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);c.drawPath(path,p);}
    private void rect(Canvas c,float x,float y,float w,float h,float r){c.drawRoundRect(x,y,x+w,y+h,r,r,p);}
    private void phone(Canvas c,float x,float y){rect(c,x,y,64,124,9);rect(c,x+4,y+4,56,116,6);c.drawCircle(x+51,y+13,2.5f,p);line(c,x+25,y+124,x+39,y+124);}
    private void antenna(Canvas c,float x,float y){rect(c,x+13,y+52,24,48,5);rect(c,x+21,y,8,54,4);line(c,x+20,y+76,x+30,y+76);rect(c,x+20,y+100,10,10,1);}
    private void wifi(Canvas c,float x,float y,float scale){
        c.save();c.translate(x,y);c.scale(scale,scale);
        for(int i=0;i<3;i++){
            // Build the signal outwards from the dot; fade each arc in rather
            // than flipping the outermost arc on first. The short final fade
            // makes the next loop continuous without implying actual signal.
            float level=Math.max(0f,Math.min(1f,(phase*4-i)/.5f));
            if(phase>.86f)level*=Math.max(0f,(1f-phase)/.14f);
            p.setAlpha(moving&&ValueAnimator.areAnimatorsEnabled()?50+Math.round(205*level):255);
            float r=14+i*10;c.drawArc(50-r,70-r,50+r,70+r,220,100,false,p);
        }
        p.setAlpha(255);p.setStyle(Paint.Style.FILL);c.drawCircle(50,70,2.5f,p);p.setStyle(Paint.Style.STROKE);c.restore();
    }
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float scale=Math.min(getWidth(),getHeight())/240f;canvas.save();canvas.translate((getWidth()-240*scale)/2,(getHeight()-240*scale)/2);canvas.scale(scale,scale);
        p.setColor(color);p.setStrokeWidth(Math.max(kind==HANDSHAKE?5:2.2f,getResources().getDisplayMetrics().density*1.5f/scale));p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStyle(Paint.Style.STROKE);
        switch(kind){
            case PHONE:phone(canvas,88,57);break;
            case ANTENNA:antenna(canvas,95,60);break;
            case PLUG:{phone(canvas,35,25);antenna(canvas,160,60);float gap=moving?22*(float)Math.max(0,Math.cos(phase*Math.PI*2)):0;rect(canvas,59,149+gap,16,22,3);rect(canvas,62,144+gap,10,5,1);Path cable=new Path();cable.moveTo(67,171+gap);cable.cubicTo(67,232,185,226,185,170);canvas.drawPath(cable,p);break;}
            case SCAN:phone(canvas,88,62);wifi(canvas,45,-24,1.5f);break;
            case CAPTURE:{phone(canvas,166,85);rect(canvas,14,93,54,35,4);line(canvas,10,133,73,133);wifi(canvas,73,40,1);for(int i=0;i<4;i++){float angle=(float)(Math.PI*2*(phase+i/4f));p.setAlpha(60+i*60);canvas.drawCircle(120+80*(float)Math.cos(angle),120+70*(float)Math.sin(angle),3,p);}p.setAlpha(255);break;}
            case CHECK:canvas.drawCircle(120,120,65,p);p.setStrokeWidth(5);line(canvas,86,120,110,144,157,94);break;
            default:
                android.graphics.drawable.Drawable hand=getContext().getDrawable(R.drawable.ic_handshake).mutate();
                hand.setTint(color);hand.setBounds(24,24,216,216);hand.draw(canvas);break;
        }canvas.restore();
    }
}
