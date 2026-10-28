package com.bags156.embermug;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.Locale;
final class TemperatureDial extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring=new RectF();
    private double current=Double.NaN;
    private boolean fahrenheit;
    private String label="Waiting for mug";
    TemperatureDial(Context c) { super(c); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES); }
    void update(double value,boolean f,String state) {
        current=value; fahrenheit=f; label=state;
        setContentDescription(state+", "+(Double.isNaN(value)?"temperature unavailable":String.format(Locale.getDefault(),"%.1f degrees %s",f?EmberProtocol.toF(value):value,f?"Fahrenheit":"Celsius"))); invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.41f,s=r/110;
        ring.set(cx-r,cy-r,cx+r,cy+r);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(7*s); p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xff343b33); canvas.drawArc(ring,135,270,false,p);
        p.setColor(0xffeaa174); float fraction=Double.isNaN(current)?.08f:(float)Math.max(0,Math.min(1,(current-20)/43));
        canvas.drawArc(ring,135,270*fraction,false,p);
        p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER); p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        p.setColor(0xffa2ad9b); p.setTextSize(10*s); canvas.drawText("CURRENT TEMPERATURE",cx,cy-40*s,p);
        p.setColor(0xfff8f2e6); p.setTextSize(53*s); canvas.drawText(Double.isNaN(current)?"—":String.format(Locale.getDefault(),"%.1f°",fahrenheit?EmberProtocol.toF(current):current),cx,cy+15*s,p);
        p.setColor(0xffeaa174); p.setTextSize(12*s); canvas.drawText(label,cx,cy+43*s,p);
        p.setColor(0xffa2ad9b); p.setTextSize(10*s); canvas.drawText(fahrenheit?"FAHRENHEIT":"CELSIUS",cx,cy+77*s,p);
    }
}
