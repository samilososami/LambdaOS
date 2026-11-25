package org.lambdaproject.lambda;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/** Native RFB framebuffer surface for LEX mode 1. Owns a Bitmap and an int[]
 *  mirror: the RFB thread copies damage into the mirror, the UI thread syncs
 *  only the dirty region into the Bitmap and draws it scaled to fit with the
 *  aspect ratio preserved. Touch becomes pointer events (tap/drag) or wheel
 *  steps (two-finger vertical drag); the soft keyboard commits X11 keysyms.
 *
 *  Callbacks from LexRfbClient arrive on the RFB thread; setFramebuffer and
 *  applyDamage are safe to call there. */
public final class LexDesktopView extends View {
    /** Input sink implemented by the activity and forwarded to LexRfbClient. */
    public interface Host {
        void onPointer(int x,int y,int mask);
        void onScroll(int steps);
        void onKey(int keysym,boolean down);
    }
    /** Lets the activity label its keyboard toggle when the IME opens/closes. */
    public interface KeyboardStateListener {
        void onKeyboardState(boolean visible);
    }

    private static final int BACKGROUND=0xff101112;

    private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect destination=new Rect();
    private final int scrollStep;
    private Bitmap bitmap;
    private int[] mirror;
    private int frameWidth,frameHeight;
    private Host host;
    private KeyboardStateListener keyboardListener;

    private boolean dirty;
    private int[] dirtyRects=new int[16*4];
    private int dirtyCount;
    private float scale=1f;
    private float offsetX,offsetY;

    private int activePointer=-1;
    private boolean pressed,scrolling;
    private float lastX,lastY,scrollAnchorY;

    public LexDesktopView(Context context){
        super(context);
        setBackgroundColor(BACKGROUND);
        setFocusable(true);
        setFocusableInTouchMode(true);
        scrollStep=Math.max(8,Math.round(18*getResources().getDisplayMetrics().density));
        paint.setFilterBitmap(true);
    }

    public void setHost(Host host){this.host=host;}
    public void setKeyboardStateListener(KeyboardStateListener listener){keyboardListener=listener;}

    /** Called once per connection on the RFB thread. */
    public synchronized void setFramebuffer(int[] pixels,int width,int height){
        if(pixels==null||width<=0||height<=0||pixels.length<width*height)return;
        frameWidth=width;
        frameHeight=height;
        mirror=new int[width*height];
        System.arraycopy(pixels,0,mirror,0,mirror.length);
        bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        dirtyCount=0;
        addDirty(0,0,width,height);
        dirty=true;
        updateScale();
        postInvalidateOnAnimation();
    }

    /** Called on the RFB thread after every decoded update. */
    public synchronized void applyDamage(int[] pixels,int[] damage,int count){
        if(mirror==null||pixels==null||damage==null)return;
        for(int i=0;i+3<count;i+=4){
            int x=damage[i],y=damage[i+1],w=damage[i+2],h=damage[i+3];
            if(w<=0||h<=0||x<0||y<0||x+w>frameWidth||y+h>frameHeight)continue;
            for(int r=0;r<h;r++){
                int base=(y+r)*frameWidth+x;
                System.arraycopy(pixels,base,mirror,base,w);
            }
            addDirty(x,y,w,h);
            dirty=true;
        }
        postInvalidateOnAnimation();
    }

    private void addDirty(int x,int y,int w,int h){
        if(dirtyCount+4>dirtyRects.length){
            int[] grown=new int[Math.max(dirtyRects.length*2,dirtyCount+4)];
            System.arraycopy(dirtyRects,0,grown,0,dirtyCount);
            dirtyRects=grown;
        }
        dirtyRects[dirtyCount++]=x;dirtyRects[dirtyCount++]=y;
        dirtyRects[dirtyCount++]=w;dirtyRects[dirtyCount++]=h;
    }

    public synchronized boolean hasFrame(){return bitmap!=null;}

    public void showKeyboard(){
        requestFocus();
        InputMethodManager imm=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if(imm!=null)imm.showSoftInput(this,0);
    }

    public void hideKeyboard(){
        InputMethodManager imm=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if(imm!=null)imm.hideSoftInputFromWindow(getWindowToken(),0);
    }

    private void updateScale(){
        int viewWidth=getWidth(),viewHeight=getHeight();
        if(viewWidth<=0||viewHeight<=0||frameWidth<=0||frameHeight<=0)return;
        scale=Math.min(viewWidth/(float)frameWidth,viewHeight/(float)frameHeight);
        offsetX=(viewWidth-frameWidth*scale)/2f;
        offsetY=(viewHeight-frameHeight*scale)/2f;
        destination.set(Math.round(offsetX),Math.round(offsetY),
            Math.round(offsetX+frameWidth*scale),Math.round(offsetY+frameHeight*scale));
    }

    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){
        super.onSizeChanged(w,h,oldW,oldH);
        updateScale();
    }

    @Override protected void onDraw(Canvas canvas){
        Bitmap frame;
        synchronized(this){
            if(bitmap==null||mirror==null)return;
            if(dirty){
                for(int i=0;i<dirtyCount;i+=4){
                    int x=dirtyRects[i],y=dirtyRects[i+1];
                    int w=dirtyRects[i+2],h=dirtyRects[i+3];
                    if(w>0&&h>0)bitmap.setPixels(mirror,y*frameWidth+x,frameWidth,x,y,w,h);
                }
                dirtyCount=0;
                dirty=false;
            }
            if(destination.isEmpty())updateScale();
            frame=bitmap;
            if(destination.isEmpty())return;
            canvas.drawBitmap(frame,null,destination,paint);
        }
    }

    private void sendPointer(float viewX,float viewY,int mask){
        Host target=host;
        if(target==null||frameWidth<=0||frameHeight<=0)return;
        if(scale<=0)updateScale();
        int x=Math.round((viewX-offsetX)/scale);
        int y=Math.round((viewY-offsetY)/scale);
        if(x<0)x=0;else if(x>=frameWidth)x=frameWidth-1;
        if(y<0)y=0;else if(y>=frameHeight)y=frameHeight-1;
        lastX=viewX;lastY=viewY;
        target.onPointer(x,y,mask);
    }

    private static float midY(MotionEvent event){
        float sum=0;
        int count=event.getPointerCount();
        for(int i=0;i<count;i++)sum+=event.getY(i);
        return count==0?0:sum/count;
    }

    @Override public boolean onTouchEvent(MotionEvent event){
        if(bitmap==null)return true;
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                requestFocus();
                activePointer=event.getPointerId(0);
                pressed=true;
                scrolling=false;
                sendPointer(event.getX(),event.getY(),1);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if(!scrolling&&event.getPointerCount()>=2){
                    if(pressed)sendPointer(lastX,lastY,0);
                    pressed=false;
                    scrolling=true;
                    scrollAnchorY=midY(event);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if(scrolling){
                    if(event.getPointerCount()<2)return true;
                    float middle=midY(event);
                    float delta=scrollAnchorY-middle;
                    if(Math.abs(delta)>=scrollStep){
                        int steps=(int)(delta/scrollStep);
                        if(steps>4)steps=4;else if(steps<-4)steps=-4;
                        Host target=host;
                        if(target!=null&&steps!=0)target.onScroll(steps);
                        scrollAnchorY-=steps*scrollStep;
                    }
                }else if(pressed){
                    int index=event.findPointerIndex(activePointer);
                    if(index<0)return true;
                    sendPointer(event.getX(index),event.getY(index),1);
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if(scrolling&&event.getPointerCount()<=2){
                    scrolling=false;
                    pressed=false;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if(pressed)sendPointer(event.getX(),event.getY(),0);
                pressed=false;
                scrolling=false;
                activePointer=-1;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if(pressed)sendPointer(lastX,lastY,0);
                pressed=false;
                scrolling=false;
                activePointer=-1;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        int keysym=keysymForAndroidKey(keyCode);
        if(keysym==0)return super.onKeyDown(keyCode,event);
        Host target=host;
        if(target!=null)target.onKey(keysym,true);
        return true;
    }

    @Override public boolean onKeyUp(int keyCode,KeyEvent event){
        int keysym=keysymForAndroidKey(keyCode);
        if(keysym==0)return super.onKeyUp(keyCode,event);
        Host target=host;
        if(target!=null)target.onKey(keysym,false);
        return true;
    }

    /** Android keycode -> X11 keysym. Character keys use their unshifted
     *  keysym; modifiers are sent as their own key events so X applies them. */
    static int keysymForAndroidKey(int keyCode){
        if(keyCode>=KeyEvent.KEYCODE_A&&keyCode<=KeyEvent.KEYCODE_Z)
            return 'a'+(keyCode-KeyEvent.KEYCODE_A);
        if(keyCode>=KeyEvent.KEYCODE_0&&keyCode<=KeyEvent.KEYCODE_9)
            return '0'+(keyCode-KeyEvent.KEYCODE_0);
        if(keyCode>=KeyEvent.KEYCODE_F1&&keyCode<=KeyEvent.KEYCODE_F12)
            return 0xffbe+(keyCode-KeyEvent.KEYCODE_F1);
        switch(keyCode){
            case KeyEvent.KEYCODE_ENTER:return LexRfbClient.KEY_RETURN;
            case KeyEvent.KEYCODE_DEL:return LexRfbClient.KEY_BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL:return 0xffff;
            case KeyEvent.KEYCODE_TAB:return LexRfbClient.KEY_TAB;
            case KeyEvent.KEYCODE_ESCAPE:return LexRfbClient.KEY_ESCAPE;
            case KeyEvent.KEYCODE_SPACE:return 0x20;
            case KeyEvent.KEYCODE_SHIFT_LEFT:case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return LexRfbClient.KEY_SHIFT;
            case KeyEvent.KEYCODE_CTRL_LEFT:case KeyEvent.KEYCODE_CTRL_RIGHT:
                return LexRfbClient.KEY_CONTROL;
            case KeyEvent.KEYCODE_ALT_LEFT:case KeyEvent.KEYCODE_ALT_RIGHT:
                return LexRfbClient.KEY_ALT;
            case KeyEvent.KEYCODE_META_LEFT:case KeyEvent.KEYCODE_META_RIGHT:
                return LexRfbClient.KEY_SUPER;
            case KeyEvent.KEYCODE_DPAD_LEFT:return 0xff51;
            case KeyEvent.KEYCODE_DPAD_UP:return 0xff52;
            case KeyEvent.KEYCODE_DPAD_RIGHT:return 0xff53;
            case KeyEvent.KEYCODE_DPAD_DOWN:return 0xff54;
            case KeyEvent.KEYCODE_MOVE_HOME:return 0xff50;
            case KeyEvent.KEYCODE_MOVE_END:return 0xff57;
            case KeyEvent.KEYCODE_PAGE_UP:return 0xff55;
            case KeyEvent.KEYCODE_PAGE_DOWN:return 0xff56;
            case KeyEvent.KEYCODE_INSERT:return 0xff63;
            case KeyEvent.KEYCODE_CAPS_LOCK:return 0xffe5;
            case KeyEvent.KEYCODE_COMMA:return 0x2c;
            case KeyEvent.KEYCODE_PERIOD:return 0x2e;
            case KeyEvent.KEYCODE_MINUS:return 0x2d;
            case KeyEvent.KEYCODE_EQUALS:return 0x3d;
            case KeyEvent.KEYCODE_SLASH:return 0x2f;
            case KeyEvent.KEYCODE_BACKSLASH:return 0x5c;
            case KeyEvent.KEYCODE_SEMICOLON:return 0x3b;
            case KeyEvent.KEYCODE_APOSTROPHE:return 0x27;
            case KeyEvent.KEYCODE_LEFT_BRACKET:return 0x5b;
            case KeyEvent.KEYCODE_RIGHT_BRACKET:return 0x5d;
            case KeyEvent.KEYCODE_GRAVE:return 0x60;
            case KeyEvent.KEYCODE_AT:return '@';
            case KeyEvent.KEYCODE_PLUS:return '+';
            case KeyEvent.KEYCODE_STAR:return '*';
            case KeyEvent.KEYCODE_POUND:return '#';
            default:return 0;
        }
    }

    private void notifyKeyboard(boolean visible){
        KeyboardStateListener listener=keyboardListener;
        if(listener!=null)listener.onKeyboardState(visible);
    }

    private void typeCodePoint(int codePoint){
        int keysym=LexRfbClient.keysym(codePoint);
        if(keysym==0)return;
        Host target=host;
        if(target==null)return;
        target.onKey(keysym,true);
        target.onKey(keysym,false);
    }

    @Override public boolean onCheckIsTextEditor(){return true;}

    @Override public InputConnection onCreateInputConnection(EditorInfo info){
        info.inputType=InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions=EditorInfo.IME_ACTION_NONE|EditorInfo.IME_FLAG_NO_FULLSCREEN
            |EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        notifyKeyboard(true);
        return new BaseInputConnection(this,false){
            @Override public boolean commitText(CharSequence text,int newCursorPosition){
                if(text==null)return true;
                for(int i=0;i<text.length();){
                    int codePoint=Character.codePointAt(text,i);
                    i+=Character.charCount(codePoint);
                    typeCodePoint(codePoint);
                }
                return true;
            }
            @Override public boolean deleteSurroundingText(int beforeLength,int afterLength){
                for(int i=0;i<beforeLength;i++)typeCodePoint('\b');
                return true;
            }
            @Override public boolean sendKeyEvent(KeyEvent event){
                int keysym=keysymForAndroidKey(event.getKeyCode());
                if(keysym==0)return false;
                Host target=host;
                if(target!=null)target.onKey(keysym,event.getAction()==KeyEvent.ACTION_DOWN);
                return true;
            }
            @Override public void closeConnection(){
                notifyKeyboard(false);
                super.closeConnection();
            }
        };
    }
}
