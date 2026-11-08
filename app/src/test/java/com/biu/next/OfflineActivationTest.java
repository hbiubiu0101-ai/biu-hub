package com.biu.next;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class OfflineActivationTest {
  private Context app(){return RuntimeEnvironment.getApplication();}
  private void clear(){ActivationActivity.preferences(app()).edit().clear().commit();}
  private void expire(){
    ActivationActivity.startTrial(app());
    ActivationActivity.preferences(app()).edit().putLong("started",System.currentTimeMillis()-OfflineLicensePolicy.TRIAL_MS-1000).commit();
  }
  @Test public void firstUseStartsOnceAndOldOnlineTrialIsIgnored(){
    clear();app().getSharedPreferences("youp_activation",0).edit().putBoolean("redeemed",true).putLong("trial_deadline",1).commit();
    ActivationActivity.startTrial(app());long started=ActivationActivity.preferences(app()).getLong("started",0);
    assertTrue(started>0);assertFalse(ActivationActivity.isRedeemed(app()));assertTrue(ActivationActivity.canUse(app()));
    ActivationActivity.startTrial(app());assertEquals(started,ActivationActivity.preferences(app()).getLong("started",0));
    assertTrue(ActivationActivity.remaining(app())<=OfflineLicensePolicy.TRIAL_MS);
  }
  @Test public void wrongCodeDoesNotUnlockAndChosenCodeSurvivesExpiry(){
    clear();expire();assertFalse(ActivationActivity.canUse(app()));assertFalse(ActivationActivity.activate(app(),"WRONG"));
    assertFalse(ActivationActivity.isRedeemed(app()));assertTrue(ActivationActivity.activate(app(),"YOUP999"));
    assertTrue(ActivationActivity.isRedeemed(app()));assertTrue(ActivationActivity.canUse(app()));
    assertEquals(Long.MAX_VALUE,ActivationActivity.remaining(app()));
    try(ActivityController<ActivationActivity> c=Robolectric.buildActivity(ActivationActivity.class).setup()){
      View content=c.get().findViewById(android.R.id.content);assertTrue(has(content,"已永久激活"));
      assertFalse(has(content,"立即激活"));assertFalse(hasEdit(content));
    }
  }
  @Test public void toolsAddsTopCardAndPreservesOriginalMenuOrder(){
    clear();app().deleteDatabase("next.db");
    try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()){
      MainActivity a=c.get();a.go("tools");java.util.List<String> all=new java.util.ArrayList<>();collect(a.page,all);
      String[] expected={"使用状态","添加下拉记账按钮  ›","锁屏日志  ›","本地数据导出与导入  ›","软件说明  ›"};
      int previous=-1;for(String name:expected){int index=all.indexOf(name);assertTrue(name,index>previous);previous=index;}
      assertTrue(has(a.page,"验证码激活"));
    }
  }
  @Test public void trialAndExpiredScreensNeverShowSuccessUntilActivated(){
    clear();
    try(ActivityController<ActivationActivity> c=Robolectric.buildActivity(ActivationActivity.class).setup()){
      View content=c.get().findViewById(android.R.id.content);assertTrue(has(content,"立即激活"));assertFalse(has(content,"已永久激活"));
    }
    expire();
    try(ActivityController<ActivationActivity> c=Robolectric.buildActivity(ActivationActivity.class).setup()){
      View content=c.get().findViewById(android.R.id.content);assertTrue(has(content,"3天免费试用已结束"));assertTrue(has(content,"激活并继续使用"));assertFalse(has(content,"已永久激活"));
    }
  }
  @Test public void expirationPreservesPlansLedgerAndPreferences()throws Exception{
    clear();app().deleteDatabase("next.db");
    try(Store store=new Store(app())){
      store.addTodayPlan("到期后保留事项",true);store.putPref("keep_this","unchanged");
      store.addLedgerEntry(java.time.LocalDate.now(),java.time.LocalTime.NOON,"支出",1234,"其他","保留账本");
      int tasks=store.activePlanTasks().size();int ledger=store.rows("SELECT * FROM ledger").size();expire();assertFalse(ActivationActivity.canUse(app()));
      assertEquals(tasks,store.activePlanTasks().size());assertEquals("unchanged",store.pref("keep_this",""));
      ActivationActivity.activate(app(),"YOUP999");assertEquals(tasks,store.activePlanTasks().size());assertEquals(ledger,store.rows("SELECT * FROM ledger").size());
    }
  }
  private boolean has(View v,String s){if(v instanceof TextView&&s.equals(((TextView)v).getText().toString()))return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(has(g.getChildAt(i),s))return true;}return false;}
  private boolean hasEdit(View v){if(v instanceof android.widget.EditText)return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(hasEdit(g.getChildAt(i)))return true;}return false;}
  private void collect(View v,java.util.List<String> values){if(v instanceof TextView)values.add(((TextView)v).getText().toString());if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)collect(g.getChildAt(i),values);}}
}
