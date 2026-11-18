package studio.yingxu.frame;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.widget.GridView;
import java.util.function.IntConsumer;

/** Interpolate the visible cells between adjacent column layouts around the touched row. */
final class PinchGrid extends GridView {
    private boolean enabled, pinchStream, morphing, committing;
    private float columns,startColumns,startSpan,focusY,anchorFraction;
    private int anchor;
    private final SparseArray<View> tiles=new SparseArray<>();
    private ValueAnimator snap;
    IntConsumer onColumnsChanged;

    PinchGrid(Context context){super(context);}
    void setZoomEnabled(boolean value){
        if(!value && morphing)resetMorph();
        enabled=value;
    }
    private void resetMorph(){
        if(snap!=null){snap.removeAllListeners();snap.cancel();snap=null;}
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
            anchorFraction=Math.max(0,Math.min(1,(focusY-cell.getTop())/Math.max(1,cell.getHeight())));
            startSpan=Math.max(1f,span(e));
            columns=startColumns=getNumColumns();
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
            invalidate();
        }else if((action==MotionEvent.ACTION_POINTER_UP && e.getPointerCount()==2 || action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_UP) && morphing && snap==null){
            settle();
        }
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL)pinchStream=false;
        return true;
    }
    private float cellSize(int n){return (getWidth()-(n-1)*getHorizontalSpacing())/(float)n;}
    private float firstTop(int n){
        float cell=cellSize(n),pitch=cell+getVerticalSpacing();
        int rows=(getCount()+n-1)/n;
        float content=rows*pitch-getVerticalSpacing();
        float proposed=focusY-anchorFraction*cell-(anchor/n)*pitch;
        return Math.max(Math.min(0,getHeight()-content),Math.min(0,proposed));
    }
    private void settle(){
        final int target=Math.round(columns);
        snap=ValueAnimator.ofFloat(columns,target);
        snap.setDuration(140);
        snap.addUpdateListener(a->{columns=(float)a.getAnimatedValue();invalidate();});
        snap.addListener(new AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(Animator a){
                int row=anchor/target;
                int y=Math.round(firstTop(target)+row*(cellSize(target)+getVerticalSpacing()));
                committing=true;
                onColumnsChanged.accept(target);
                setNumColumns(target);
                setSelectionFromTop(row*target,y);
                requestLayout();
            }
        });
        snap.start();
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        super.onLayout(changed,l,t,r,b);
        if(committing)resetMorph();
    }
    @Override protected void onDetachedFromWindow(){
        resetMorph();
        super.onDetachedFromWindow();
    }
    @Override protected void dispatchDraw(Canvas canvas){
        if(!morphing){super.dispatchDraw(canvas);return;}
        int lower=(int)Math.floor(columns),upper=(int)Math.ceil(columns);
        float blend=columns-lower,topA=firstTop(lower),topB=firstTop(upper);
        float a=cellSize(lower),b=cellSize(upper);
        float rowA=a+getVerticalSpacing(),rowB=b+getVerticalSpacing();
        int first=Math.max(0,Math.min((int)Math.floor(-topA/rowA)*lower,(int)Math.floor(-topB/rowB)*upper));
        int last=Math.min(getCount(),Math.max((int)Math.ceil((getHeight()-topA)/rowA)*lower,(int)Math.ceil((getHeight()-topB)/rowB)*upper));
        for(int i=first;i<last;i++){
            float yA=topA+(i/lower)*rowA,yB=topB+(i/upper)*rowB;
            float y=yA+(yB-yA)*blend,side=a+(b-a)*blend;
            if(y+side<0 || y>getHeight())continue;
            float xA=(i%lower)*(a+getHorizontalSpacing()),xB=(i%upper)*(b+getHorizontalSpacing());
            View tile=tiles.get(i);
            if(tile==null){
                tile=getAdapter().getView(i,null,this);
                int base=Math.max(1,Math.round(cellSize((int)startColumns)));
                int spec=MeasureSpec.makeMeasureSpec(base,MeasureSpec.EXACTLY);
                tile.measure(spec,spec);tile.layout(0,0,base,base);tiles.put(i,tile);
            }
            int saved=canvas.save();
            canvas.translate(xA+(xB-xA)*blend,y);
            canvas.scale(side/tile.getWidth(),side/tile.getWidth());
            tile.draw(canvas);
            canvas.restoreToCount(saved);
        }
    }
}
