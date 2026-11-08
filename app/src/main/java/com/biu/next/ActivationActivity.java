package com.biu.next;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Three-day local trial, followed by a permanent offline activation. */
public final class ActivationActivity extends Activity {
  private static final String PREFS="youp_offline_license_v1";
  private static final int BLUE=0xff2c6d96, INK=0xff20282d, MUTED=0xff626a70;
  private static final java.util.WeakHashMap<Activity,Boolean> reminded=new java.util.WeakHashMap<>();
  private final Handler handler=new Handler();
  private LinearLayout page;
  private TextView currentState;
  private boolean renderedExpired,renderedActivated;
  private final Runnable tick=new Runnable(){@Override public void run(){
    if(isFinishing())return;
    boolean activated=isRedeemed(ActivationActivity.this),expired=!canUse(ActivationActivity.this);
    if(activated!=renderedActivated || expired!=renderedExpired)render();
    else if(currentState!=null)currentState.setText(statusText(ActivationActivity.this));
    handler.postDelayed(this,1000);
  }};

  static SharedPreferences preferences(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
  static synchronized void startTrial(Context context) {
    SharedPreferences p=preferences(context);
    if(p.contains("started"))return;
    long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();
    int boot=Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT,-1);
    p.edit().putLong("started",wall).putLong("logical",wall).putLong("elapsed",elapsed).putInt("boot",boot).commit();
  }
  static boolean isRedeemed(Context c){return preferences(c).getBoolean("activated",false);}
  static synchronized long remaining(Context c) {
    if(isRedeemed(c))return Long.MAX_VALUE;
    startTrial(c);SharedPreferences p=preferences(c);
    if(p.getBoolean("expired",false))return 0;
    long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();
    int boot=Settings.Global.getInt(c.getContentResolver(),Settings.Global.BOOT_COUNT,-1);
    long saved=p.getLong("logical",wall),savedElapsed=p.getLong("elapsed",elapsed);
    long logical=OfflineLicensePolicy.logicalTime(wall,elapsed,boot,saved,savedElapsed,p.getInt("boot",-1));
    long left=OfflineLicensePolicy.remaining(p.getLong("started",logical),logical);
    if(left==0 || logical-saved>=60000 || boot!=p.getInt("boot",-1))
      p.edit().putLong("logical",logical).putLong("elapsed",elapsed).putInt("boot",boot).putBoolean("expired",left==0).apply();
    return left;
  }
  static boolean canUse(Context c){return isRedeemed(c)||remaining(c)>0;}
  static boolean activate(Context c,String value) {
    if(!OfflineLicensePolicy.matches(value))return false;
    return preferences(c).edit().putBoolean("activated",true).commit();
  }
  static boolean requireWrite(Activity a) {
    if(canUse(a))return true;
    openRedemption(a);return false;
  }
  static boolean openIfNeeded(Activity a) {
    if(canUse(a))return false;
    openRedemption(a);a.finish();return true;
  }
  static void remindIfExpired(Activity a) {
    if(canUse(a)||Boolean.TRUE.equals(reminded.get(a)))return;
    reminded.put(a,true);openRedemption(a);
  }
  static String remainingText(long millis) {
    long minutes=Math.max(1,(millis+59999)/60000);
    long days=minutes/(24*60),hours=(minutes/60)%24,mins=minutes%60;
    if(days>0)return days+"天"+hours+"小时";
    if(hours>0)return hours+"小时"+mins+"分钟";
    return minutes+"分钟";
  }
  static String statusText(Context c) {
    if(isRedeemed(c))return "已永久激活";
    long left=remaining(c);
    return left>0?"免费试用剩余 "+remainingText(left):"3天免费试用已结束";
  }
  static void openRedemption(Activity a) {
    if(!(a instanceof ActivationActivity))a.startActivity(new Intent(a,ActivationActivity.class));
  }
  @Override protected void onCreate(Bundle state){super.onCreate(state);render();}
  @Override protected void onResume(){super.onResume();handler.removeCallbacks(tick);handler.post(tick);}
  @Override protected void onPause(){handler.removeCallbacks(tick);super.onPause();}
  @Override protected void onDestroy(){handler.removeCallbacks(tick);super.onDestroy();}

  private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
  private TextView text(String s,int size,int color,boolean bold){
    TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
    t.setLineSpacing(dp(3),1);if(bold)t.setTypeface(null,Typeface.BOLD);return t;
  }
  private GradientDrawable background(int fill,int radius,int stroke){
    GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));
    if(stroke!=0)d.setStroke(dp(1),stroke);return d;
  }
  private void space(int height){page.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
  private void action(String name,boolean primary,Runnable run){
    TextView button=text(name,16,primary?Color.WHITE:BLUE,true);button.setGravity(Gravity.CENTER);
    button.setMinHeight(dp(48));button.setBackground(background(primary?BLUE:0xffedf3fc,12,0));
    button.setOnClickListener(v->run.run());page.addView(button,new LinearLayout.LayoutParams(-1,-2));
  }
  private void returnToTools(){
    startActivity(LockSync.screenIntent(this,"tools"));finish();
  }
  private void render(){
    renderedActivated=isRedeemed(this);renderedExpired=!canUse(this);currentState=null;
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xfffaf7f2);
    page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(20),dp(12),dp(20),dp(28));
    scroll.addView(page);setContentView(scroll);
    LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);
    ImageButton back=new ImageButton(this);back.setContentDescription("返回工具");back.setBackgroundColor(Color.TRANSPARENT);
    back.setImageDrawable(new android.graphics.drawable.Drawable(){
      final android.graphics.Paint paint=new android.graphics.Paint(3);
      @Override public void draw(android.graphics.Canvas canvas){float x=getBounds().exactCenterX(),y=getBounds().exactCenterY();paint.setColor(BLUE);paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        android.graphics.Path p=new android.graphics.Path();p.moveTo(x+dp(3),y-dp(7));p.lineTo(x-dp(4),y);p.lineTo(x+dp(3),y+dp(7));canvas.drawPath(p,paint);}
      @Override public void setAlpha(int a){paint.setAlpha(a);}@Override public void setColorFilter(android.graphics.ColorFilter f){paint.setColorFilter(f);}@Override public int getOpacity(){return -3;}
    });back.setOnClickListener(v->returnToTools());toolbar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
    toolbar.addView(text("验证码激活",20,INK,true));page.addView(toolbar);space(36);
    View icon=new View(this){final android.graphics.Paint paint=new android.graphics.Paint(3);
      @Override protected void onDraw(android.graphics.Canvas c){
        super.onDraw(c);paint.setColor(BLUE);paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        float x=getWidth()/2f,y=getHeight()/2f;
        if(renderedActivated){c.drawCircle(x,y,dp(21),paint);android.graphics.Path p=new android.graphics.Path();p.moveTo(x-dp(10),y);p.lineTo(x-dp(3),y+dp(7));p.lineTo(x+dp(11),y-dp(8));c.drawPath(p,paint);}
        else if(renderedExpired){c.drawCircle(x,y,dp(21),paint);c.drawLine(x,y-dp(12),x,y,paint);c.drawLine(x,y,x+dp(9),y+dp(5),paint);}
        else {c.drawCircle(x+dp(8),y-dp(8),dp(12),paint);c.drawCircle(x+dp(11),y-dp(11),dp(2),paint);c.drawLine(x,y+dp(1),x-dp(18),y+dp(20),paint);c.drawLine(x-dp(18),y+dp(20),x-dp(24),y+dp(14),paint);c.drawLine(x-dp(14),y+dp(15),x-dp(20),y+dp(9),paint);}
      }
    };
    LinearLayout.LayoutParams iconSize=new LinearLayout.LayoutParams(dp(64),dp(64));iconSize.gravity=Gravity.CENTER_HORIZONTAL;page.addView(icon,iconSize);space(20);
    page.addView(text(renderedActivated?"已永久激活":renderedExpired?"3天免费试用已结束":"激活后，永久使用",24,INK,true));space(10);
    page.addView(text(renderedActivated?"此设备可永久离线使用":renderedExpired?"输入有效验证码，即可永久使用":"离线验证，激活后无需联网",14,MUTED,false));space(26);
    if(renderedActivated){
      TextView success=text("激活成功\n已有待办、账本和计薪数据均保留。",16,BLUE,false);success.setPadding(dp(16),dp(18),dp(16),dp(18));success.setBackground(background(0xffdef0f7,12,0));page.addView(success);space(28);action("返回工具",true,this::returnToTools);return;
    }
    if(!renderedExpired){currentState=text(statusText(this),14,BLUE,false);currentState.setPadding(dp(14),dp(14),dp(14),dp(14));currentState.setBackground(background(0xffedf7fa,12,0xffd6e2e6));page.addView(currentState);space(22);}
    page.addView(text("验证码",15,INK,true));space(8);
    LinearLayout fieldRow=new LinearLayout(this);fieldRow.setGravity(Gravity.CENTER_VERTICAL);fieldRow.setPadding(dp(10),0,dp(8),0);fieldRow.setBackground(background(Color.WHITE,12,0xffa9c3d1));
    EditText code=new EditText(this);code.setTextSize(16);code.setSingleLine(true);code.setHint("请输入验证码");code.setTextColor(INK);code.setBackgroundColor(Color.TRANSPARENT);code.setMinHeight(dp(50));code.setSelectAllOnFocus(true);
    code.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(64)});
    fieldRow.addView(code,new LinearLayout.LayoutParams(0,-2,1));
    TextView paste=text("粘贴",14,BLUE,false);paste.setGravity(Gravity.CENTER);paste.setMinHeight(dp(48));paste.setMinWidth(dp(54));paste.setOnClickListener(v->{ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData clip=clipboard.getPrimaryClip();if(clip!=null&&clip.getItemCount()>0){code.setText(clip.getItemAt(0).coerceToText(this));code.setSelection(code.length());}});
    fieldRow.addView(paste);page.addView(fieldRow);space(22);
    action(renderedExpired?"激活并继续使用":"立即激活",true,()->{
      if(!OfflineLicensePolicy.matches(code.getText().toString())){code.setError("验证码不正确，请检查后重试");return;}
      if(!activate(this,code.getText().toString())){code.setError("激活未保存，请重试");return;}
      android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);keyboard.hideSoftInputFromWindow(code.getWindowToken(),0);
      LockPanelKeepAlive.ensureRunning(this);LockSync.refresh(this);render();
    });space(14);page.addView(text(renderedExpired?"已有待办、账本和计薪数据会保留":"请使用获得的有效验证码",13,MUTED,false));space(28);
    action("返回工具",false,this::returnToTools);
  }
}
