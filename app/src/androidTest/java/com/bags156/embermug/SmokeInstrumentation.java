package com.bags156.embermug;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.util.List;

/** Emulator-only UI review. It never connects to hardware or sends a heater/LED command. */
public final class SmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle b) { super.onCreate(b); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            getTargetContext().getSharedPreferences(MugService.PREFS,Context.MODE_PRIVATE).edit().clear().commit();
            MainActivity activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync(); SystemClock.sleep(700);
            runOnMainSync(() -> {
                require(!((Button)activity.getWindow().getDecorView().findViewWithTag("heat")).isEnabled(),"Heat must be disabled without a mug");
            });
            captureContent(activity,"01-home.png");
            runOnMainSync(() -> activity.getWindow().getDecorView().findViewWithTag("editFavorites").performClick());
            settle(); captureScreen("02-favorites.png");
            List<AccessibilityNodeInfo> inputs=getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("125.0");
            require(!inputs.isEmpty(),"First favorite input missing");
            Bundle text=new Bundle(); text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"130.0");
            require(inputs.get(0).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text),"Could not edit favorite");
            click("Save"); settle();
            double stored=getTargetContext().getSharedPreferences(MugService.PREFS,Context.MODE_PRIVATE).getFloat("favorite_0",0);
            require(Math.abs(EmberProtocol.toF(stored)-130)<.02,"Favorite was not saved in Celsius");
            runOnMainSync(() -> activity.getWindow().getDecorView().findViewWithTag("units").performClick()); settle(); captureContent(activity,"03-celsius.png");
            require(Math.abs(getTargetContext().getSharedPreferences(MugService.PREFS,Context.MODE_PRIVATE).getFloat("favorite_0",0)-stored)<.001,"Unit switch changed favorite");
            // Enable the picker only for a visual preview; no connected-mug telemetry is fabricated.
            runOnMainSync(() -> { View picker=activity.getWindow().getDecorView().findViewWithTag("chooseLed"); picker.setEnabled(true); picker.performClick(); });
            settle(); captureScreen("04-led-picker.png"); click("Cancel");
            result.putString("stream","UI smoke checks passed\n"); finish(Activity.RESULT_OK,result);
        } catch(Throwable t) { result.putString("stream","UI smoke checks failed: "+t+"\n"); finish(Activity.RESULT_CANCELED,result); }
    }
    private void settle() { waitForIdleSync(); SystemClock.sleep(500); }
    private void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private void click(String text) {
        List<AccessibilityNodeInfo> nodes=getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText(text);
        for(AccessibilityNodeInfo node:nodes) if(node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        throw new AssertionError("Button missing: "+text);
    }
    private File output(String name) { File dir=new File(getTargetContext().getExternalFilesDir(null),"ui-review"); if(!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create screenshot directory"); return new File(dir,name); }
    private void save(Bitmap bitmap,String name) { require(bitmap!=null,"Screenshot unavailable"); try(FileOutputStream stream=new FileOutputStream(output(name))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,stream); } catch(IOException e) { throw new RuntimeException(e); } finally { if(bitmap!=null) bitmap.recycle(); } }
    private void captureScreen(String name) { save(getUiAutomation().takeScreenshot(),name); }
    private void captureContent(MainActivity activity,String name) {
        runOnMainSync(() -> { ScrollView scroll=activity.getWindow().getDecorView().findViewWithTag("mainScroll"); View content=scroll.getChildAt(0); Bitmap bitmap=Bitmap.createBitmap(content.getWidth(),content.getHeight(),Bitmap.Config.ARGB_8888); Canvas canvas=new Canvas(bitmap); canvas.drawColor(0xff151916); content.draw(canvas); save(bitmap,name); });
    }
}
