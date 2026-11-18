package studio.yingxu.frame;

import android.app.Activity;
import android.os.Build;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

final class DisplayPolicy {
    static void preferHighRefreshRate(Activity activity,View content) {
        Display display=activity.getWindowManager().getDefaultDisplay();
        Display.Mode current=display.getMode();
        Display.Mode fastest=current;
        for(Display.Mode mode:display.getSupportedModes()) {
            if(mode.getPhysicalWidth()==current.getPhysicalWidth()
                && mode.getPhysicalHeight()==current.getPhysicalHeight()
                && mode.getRefreshRate()>fastest.getRefreshRate())fastest=mode;
        }
        WindowManager.LayoutParams params=activity.getWindow().getAttributes();
        params.preferredDisplayModeId=fastest.getModeId();
        activity.getWindow().setAttributes(params);
        if(Build.VERSION.SDK_INT>=35)content.setRequestedFrameRate(fastest.getRefreshRate());
    }
}
