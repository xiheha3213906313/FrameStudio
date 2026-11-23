package studio.yingxu.frame;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Parcelable;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.widget.GridView;
import java.util.function.IntConsumer;

/** Scale rows in place, then fade into the next column layout around the touched photo. */
final class PinchGrid extends GridView {
    private static final float COLUMN_THRESHOLD=.60f;
    private static final int FADE_DURATION=90;
    private boolean enabled, pinchStream, morphing, committing;
    private float columns,startColumns,startSpan,focusY,anchorFraction;
    private float incomingOpacity=1;
    private int anchor,previewColumns,outgoingColumns;
    private final SparseArray<View> tiles=new SparseArray<>();
    private ValueAnimator snap,fade;
    IntConsumer onColumnsChanged;

    PinchGrid(Context context){super(context);}
    void setZoomEnabled(boolean value){
        if(!value && morphing)resetMorph();
        enabled=value;
    }
    private void resetMorph(){
        if(snap!=null){snap.removeAllListeners();snap.cancel();snap=null;}
        if(fade!=null){fade.cancel();fade=null;}
        incomingOpacity=1;
        tiles.clear();
        morphing=false;committing=false;
        invalidate();
    }
    private float span(MotionEvent e){
        return (float)Math.hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));
    }
    @Override public boolean dispatchTouchEvent(MotionEvent e){
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            pinchStream=false;
            if(morphing)resetMorph();
        }
        if(enabled && !pinchStream && action==MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount()==2 && getChildCount()>0){
            float focusX=(e.getX(0)+e.getX(1))*.5f;
            focusY=(e.getY(0)+e.getY(1))*.5f;
            anchor=pointToPosition((int)focusX,(int)focusY);
            if(anchor==INVALID_POSITION){
                int pitch=Math.max(1,getColumnWidth()+getHorizontalSpacing());
                int row=Math.max(0,(int)((focusY-getChildAt(0).getTop())/pitch));
                int offset=row*getNumColumns()+Math.max(0,(int)(focusX/pitch));
                anchor=getFirstVisiblePosition()+Math.min(getChildCount()-1,offset);
            }
            View cell=getChildAt(anchor-getFirstVisiblePosition());
            if(cell==null)return super.dispatchTouchEvent(e);
            anchorFraction=Math.max(0,Math.min(1,(focusY-cell.getTop())/Math.max(1,cell.getHeight())));
            startSpan=Math.max(1f,span(e));
            columns=startColumns=getNumColumns();
            previewColumns=getNumColumns();
            MotionEvent cancel=MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            pinchStream=morphing=true;
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if(!pinchStream)return super.dispatchTouchEvent(e);
        if(action==MotionEvent.ACTION_MOVE && e.getPointerCount()>=2 && snap==null && morphing){
            columns=Math.max(3f,Math.min(6f,startColumns*startSpan/Math.max(1f,span(e))));
            focusY=(e.getY(0)+e.getY(1))*.5f;
            if(Math.abs(columns-previewColumns)>=COLUMN_THRESHOLD)fadeTo(Math.round(columns));
            invalidate();
        }else if((action==MotionEvent.ACTION_POINTER_UP && e.getPointerCount()==2 || action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_UP) && morphing && snap==null){
            settle();
        }
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL)pinchStream=false;
        return true;
    }
    private float cellSize(float count){return (getWidth()+getHorizontalSpacing())/count-getHorizontalSpacing();}
    private float firstTop(int n,float cell){
        float pitch=cell+getVerticalSpacing();
        int rows=(getCount()+n-1)/n;
        float content=rows*pitch-getVerticalSpacing();
        float proposed=focusY-anchorFraction*cell-(anchor/n)*pitch;
        return Math.max(Math.min(0,getHeight()-content),Math.min(0,proposed));
    }
    private void fadeTo(int count){
        if(count==previewColumns)return;
        if(fade!=null)fade.cancel();
        outgoingColumns=previewColumns;
        previewColumns=count;
        incomingOpacity=0;
        fade=ValueAnimator.ofFloat(0,1);
        fade.setDuration(FADE_DURATION);
        fade.addUpdateListener(a->{incomingOpacity=(float)a.getAnimatedValue();invalidate();});
        fade.start();
    }
    private void settle(){
        final int target=Math.round(columns);
        fadeTo(target);
        snap=ValueAnimator.ofFloat(columns,target);
        snap.setDuration(140);
        snap.addUpdateListener(a->{columns=(float)a.getAnimatedValue();invalidate();});
        snap.addListener(new AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(Animator a){
                commitColumns(target);
            }
        });
        snap.start();
    }
    private void commitColumns(int target){
        committing=true;
        if(onColumnsChanged!=null)onColumnsChanged.accept(target);
        if(target!=getNumColumns()){
            // GridView clears its children when the column count changes. Its saved scroll
            // position works in touch mode, where a programmatic selection can go to the top.
            Parcelable viewport=super.onSaveInstanceState();
            setNumColumns(target);
            super.onRestoreInstanceState(viewport);
        }
        requestLayout();
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        super.onLayout(changed,l,t,r,b);
        if(committing){
            alignPinchAnchor();
            resetMorph();
        }
    }
    private void alignPinchAnchor(){
        View first=getChildAt(0);
        if(first==null)return;
        int count=getNumColumns(),cell=first.getHeight(),pitch=cell+getVerticalSpacing();
        int desired=Math.round(-firstTop(count,cell));
        int previous=Integer.MIN_VALUE;
        // Each scrollListBy call is capped at one viewport. A 6 to 3 column change
        // can move the anchor by up to three viewports in the new layout.
        for(int attempt=0;attempt<5;attempt++){
            first=getChildAt(0);
            if(first==null)return;
            int current=(getFirstVisiblePosition()/count)*pitch-first.getTop();
            if(current==desired||current==previous)return;
            scrollListBy(desired-current);
            previous=current;
        }
    }
    @Override protected void onDetachedFromWindow(){
        resetMorph();
        super.onDetachedFromWindow();
    }
    @Override protected void dispatchDraw(Canvas canvas){
        if(!morphing){super.dispatchDraw(canvas);return;}
        float cell=cellSize(columns);
        if(incomingOpacity<1)drawRows(canvas,outgoingColumns,cell,columns>outgoingColumns,1-incomingOpacity);
        if(incomingOpacity>0)drawRows(canvas,previewColumns,cell,columns>previewColumns,incomingOpacity);
    }
    private void drawRows(Canvas canvas,int count,float side,boolean nextAtRight,float opacity){
        float top=firstTop(count,side),pitch=side+getVerticalSpacing();
        int firstRow=Math.max(0,(int)Math.floor(-top/pitch));
        int lastRow=Math.min((getCount()+count-1)/count,(int)Math.ceil((getHeight()-top)/pitch));
        int saved=opacity<1?canvas.saveLayerAlpha(0,0,getWidth(),getHeight(),Math.round(255*opacity)):canvas.save();
        for(int row=firstRow;row<lastRow;row++){
            float y=top+row*pitch;
            int start=row*count,end=Math.min(getCount(),start+count+(nextAtRight?1:0));
            for(int index=start;index<end;index++){
                float x=(index-start)*(side+getHorizontalSpacing());
                if(x>=getWidth())break;
                drawTile(canvas,index,x,y,side);
            }
        }
        canvas.restoreToCount(saved);
    }
    private void drawTile(Canvas canvas,int position,float x,float y,float side){
        View tile=getChildAt(position-getFirstVisiblePosition());
        if(tile==null)tile=tiles.get(position);
        if(tile==null){
            tile=getAdapter().getView(position,null,this);
            int base=Math.max(1,Math.round(cellSize(startColumns)));
            int spec=MeasureSpec.makeMeasureSpec(base,MeasureSpec.EXACTLY);
            tile.measure(spec,spec);tile.layout(0,0,base,base);tiles.put(position,tile);
        }
        int saved=canvas.save();
        canvas.translate(x,y);
        canvas.scale(side/tile.getWidth(),side/tile.getWidth());
        tile.draw(canvas);
        canvas.restoreToCount(saved);
    }
}
