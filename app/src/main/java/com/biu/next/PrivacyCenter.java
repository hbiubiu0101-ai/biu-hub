package com.biu.next;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Local notices and controls. Opening this screen never sends a network request. */
public final class PrivacyCenter extends Activity {
  private LinearLayout body;

  static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences("youp_privacy_v1", Context.MODE_PRIVATE);
  }
  static boolean receiptAccepted(Context c) { return preferences(c).getBoolean("receipt", false); }
  static boolean accessibilityAccepted(Context c) { return preferences(c).getBoolean("accessibility", false); }
  static boolean windowStructureAccepted(Context c) { return preferences(c).getBoolean("window_structure", false); }
  static boolean lockNoticeAccepted(Context c) { return preferences(c).getBoolean("lock_notice", false); }

  static void requestReceipt(Activity activity, Runnable accepted, Runnable declined) {
    if (receiptAccepted(activity)) { accepted.run(); return; }
    new AlertDialog.Builder(activity).setTitle("账单识别与敏感信息处理")
        .setMessage("仅在你主动点击识别、选择截图或分享账单时，处理本次画面/文字。画面可能包含商户、金额、交易时间、姓名或账户等信息，用于提取账单。\n\n本版使用手机内置 OCR，本 App 无联网权限；临时识别图片不由本 App 保存到相册。保存前请核对结果，不必保存账号、姓名等无关内容。\n\n不会持续录屏或代你付款。请勿识别密码、验证码、非本人账单及其他敏感页面。拒绝不影响手动记账、计划等功能；可在工具里撤回。")
        .setNegativeButton("暂不使用", (d,w)->declined.run())
        .setPositiveButton("同意本地识别", (d,w)->{
          preferences(activity).edit().putBoolean("receipt",true).apply();
          accepted.run();
        }).setOnCancelListener(d->declined.run()).show();
  }

  static void requestAccessibility(Activity activity, Runnable accepted) {
    Runnable open = () -> {
      preferences(activity).edit().putBoolean("accessibility",true).putBoolean("window_structure",true).apply();
      accepted.run();
    };
    if (accessibilityAccepted(activity) && windowStructureAccepted(activity)) { accepted.run(); return; }
    new AlertDialog.Builder(activity).setTitle("开启有谱无障碍服务")
        .setMessage("用于锁屏/悬浮卡片显示、判断前台应用和系统锁屏状态，以及在你主动点击识别时截取当前画面。服务会接收窗口变化事件和应用包名；为避免遮挡解锁，会检查系统锁屏窗口可见控件的类型与标识。不读取密码输入值，不遍历其他App正文。\n\n不持续截屏、不访问短信或通讯录数据库、不自动点击付款，不上传这些信息。截图识别另行征求同意。你可以拒绝，继续使用手动记账和计划；在系统设置中可随时停用服务。")
        .setNegativeButton("取消",null).setPositiveButton("了解并前往设置",(d,w)->open.run()).show();
  }

  static void requestWindowStructureUpdate(Activity activity) {
    if (!accessibilityAccepted(activity) || windowStructureAccepted(activity)
        || preferences(activity).getBoolean("window_structure_asked",false)) return;
    preferences(activity).edit().putBoolean("window_structure_asked",true).apply();
    new AlertDialog.Builder(activity).setTitle("锁屏解锁界面避让")
        .setMessage("本次修正需要检查系统锁屏窗口中可见控件的类型与标识，用于识别手势、PIN和密码界面并隐藏卡片。不读取密码输入值，不遍历其他App正文，不上传信息。\n\n同意后请在系统无障碍设置中将有谱关闭再开启，使更新的能力生效。拒绝后不会进行这项结构检查，仍可手动使用计划和记账；可从无障碍功能入口重新了解并开启。")
        .setNegativeButton("暂不开启",null)
        .setPositiveButton("同意并前往设置",(d,w)->{
          preferences(activity).edit().putBoolean("window_structure",true).apply();
          activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }).show();
  }

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(0xfffaf7f2);
    body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(20),dp(18),dp(20),dp(32));
    scroll.addView(body); setContentView(scroll);
    if (android.os.Build.VERSION.SDK_INT >= 35) scroll.setOnApplyWindowInsetsListener((v,insets)->{
      android.graphics.Insets b=insets.getInsets(android.view.WindowInsets.Type.systemBars());
      v.setPadding(b.left,b.top,b.right,b.bottom); return insets;
    });
    action("‹ 返回",this::finish);
    policy();
    usage();
  }

  private void policy() {
    title("软件说明");
    paragraph("有谱离线版 · 隐私说明 1.0\n更新日期：2026年11月7日");
    String owner=getString(R.string.youp_operator_name).trim();
    String contact=getString(R.string.youp_support_email).trim();
    if (!owner.isEmpty() || !contact.isEmpty()) {
      section("提供者与联系", (owner.isEmpty() ? "" : "提供者：" + owner)
          + (contact.isEmpty() ? "" : "\n联系方式：" + contact));
    }
    section("数据存在哪里",
        "本版不申请互联网访问权限，不提供账号、联网激活、云同步、广告或网络统计。计划、账本、计薪规则和设置保存在手机应用私有存储中，未额外加密，不向运营者上传。\n"
        + "你手动选择、分享或导出的文件由安卓文件服务及你选择的应用处理；如选择云盘或微信，相关服务可能自行联网，其行为受对应服务规则约束。");
    section("账单识别",
        "主动触发时临时处理当前画面、所选图片或分享文字，以提取金额、商户、日期、类别等。可能包含账户等敏感信息；首次识别前单独征求同意。取消识别不影响手动记账。\n"
        + "识别可能出错、漏笔或重复，请逐笔核对后保存。只记录你有权处理的账单，不识别他人的私人页面。临时图片处理完成后释放；本 App 不删除用户原图。");
    section("第三方组件",
        "Google ML Kit 中文文字识别（com.google.mlkit:text-recognition-chinese:16.0.1），模型随安装包提供，用于本地 OCR。\n"
        + "本版通过合并清单移除网络权限，组件不能通过本 App 的网络权限向外发送信息。功能和兼容性仍需在目标手机验证。\n"
        + "Android 系统组件负责文件选择、通知、锁屏、安全解锁等。");
    section("权限及拒绝后的影响",
        "无障碍：仅在你开启后，用于卡片、窗口状态和主动截图；经同意检查系统锁屏可见控件类型与标识以隐藏解锁界面上的卡片，不读取密码输入值；拒绝后悬浮/亮屏卡片和屏幕识别不可用，手动功能可用。\n"
        + "通知：用于你开启的计划提醒和服务状态；拒绝后相应通知不可见。\n"
        + "开机与前台服务：按你的自运行设置恢复已开启的功能，可能增加耗电。可在首页关闭。\n"
        + "未申请通讯录、短信、麦克风、定位、电话或安装其他应用的权限。图片通过系统选择器按需读取，不批量扫描相册。");
    section("保存与删除",
        "业务记录保留到你主动删除、清空数据或卸载。识别同意状态和显示设置在本机保存；本地诊断仅保留最近32条服务事件，滚动覆盖，不包含完整账单，不自动上传。\n"
        + "备份为未加密 JSON，保留到你自行删除。卸载/清空本 App 不会删除你导出的文件或相册原图。本版关闭安卓应用自动备份。");
    section("锁屏显示提醒", "你勾选显示到锁屏的事项会显示真实内容，私密事项不显示；收入和消费的眼睛各自控制金额，隐藏时仅显示 *****。锁屏内容可能被旁人看到，所选事项也可直接勾选完成，请按自己的需要选择。");
    action("撤回截图识别同意",()->{
      preferences(this).edit().putBoolean("receipt",false).apply();
      android.widget.Toast.makeText(this,"已撤回，下次识别会重新询问",android.widget.Toast.LENGTH_LONG).show();
    });
    action("停用无障碍功能",()->{
      preferences(this).edit().putBoolean("accessibility",false).apply();
      LockPanelKeepAlive.setFloatingEnabled(this,false);
      LockPanelKeepAlive.setEnabled(this,false);
      LockSync.refresh(this);
      new AlertDialog.Builder(this).setTitle("已停止有谱相关功能")
          .setMessage("还请在接下来的系统无障碍设置中关闭有谱服务。其他权限可在系统应用管理中撤回。")
          .setPositiveButton("前往设置",(d,w)->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
          .setNegativeButton("稍后",null).show();
    });
    action("清除本地诊断日志",()->{
      getSharedPreferences("lock_panel_diagnostics",MODE_PRIVATE).edit().clear().apply();
      android.widget.Toast.makeText(this,"本地日志已清除",android.widget.Toast.LENGTH_SHORT).show();
    });
    action("清空计划、账本和计薪数据",this::erase);
    paragraph("撤回权限同意不会自动删除已保存记录；清空业务数据不会删除系统相册、外部备份或关闭系统权限。");
  }

  private void usage() {
    title("使用提示");
    section("使用与激活", "首次使用本版起免费试用3天。输入有效验证码后，此设备可永久离线使用，无需登录或定期联网续验。卸载、清除应用数据或换机后，激活记录可能丢失，需要重新输入验证码。");
    section("记账和计薪的用途",
        "本软件用于个人记录与估算，不提供支付、转账、账户托管、投资建议或银行服务；不能代替真实账单、工资结算凭证。计薪预估以你输入的规则为基础，实际金额以合同及单位结算为准。");
    section("安全操作",
        "使用截图识别前退出密码、验证码和其他敏感页面；敏感支付操作时主动收起精灵。遇到受系统保护不能截图的画面，请使用手动记账，不绕过系统限制。\n"
        + "锁屏内容可能被旁人看到，事项按你的勾选显示，金额由各自行的眼睛控制。小精灵和卡片与微信、支付宝等第三方支付平台无隶属关系。");
    section("数据与更新",
        "无云同步，请定期手动导出备份并妥善保存；重装、清除数据或换机前先备份。新版需从原购买或分发渠道获取，由安卓系统确认覆盖安装，本 App 不联网更新。");
    section("获取与反馈",
        "请从原获取渠道取得与反馈本软件。如涉及购买，交付内容、兼容要求和售后条件应在购买前明确展示，不能以本说明排除依法享有的权利。\n"
        + "本版验证码仅用于本地激活，不需要提供付款密码、短信验证码或完整私人账单。");
  }

  private void erase() {
    EditText confirmation = new EditText(this); confirmation.setHint("输入“删除”确认");
    new AlertDialog.Builder(this).setTitle("此操作不可撤销").setView(confirmation)
        .setMessage("删除本机所有计划、学习/阅读记录、账本、计薪规则和业务设置。请先返回工具导出备份。")
        .setNegativeButton("取消",null).setPositiveButton("确认清空",(dialog,which)->{
          if (!"删除".equals(confirmation.getText().toString().trim())) {
            android.widget.Toast.makeText(this,"未清空：确认文字不匹配",android.widget.Toast.LENGTH_LONG).show(); return;
          }
          try (Store store=new Store(this)) {
            android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();
            db.beginTransaction();
            try {
              for(int i=Store.TABLES.length-1;i>=0;i--) db.delete(Store.TABLES[i],null,null);
              db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            LockPanelKeepAlive.setFloatingEnabled(this,false); LockPanelKeepAlive.setEnabled(this,false);
            LockSync.refresh(this);
            android.widget.Toast.makeText(this,"业务数据已清空",android.widget.Toast.LENGTH_LONG).show();
            startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
          } catch(Exception error) {
            android.widget.Toast.makeText(this,"清空未完成，请重试",android.widget.Toast.LENGTH_LONG).show();
          }
        }).show();
  }

  private void title(String value) {
    TextView text=label(value,22);text.setTypeface(null,Typeface.BOLD);body.addView(text);
  }
  private void section(String heading,String content) {
    TextView h=label(heading,17); h.setTypeface(null,Typeface.BOLD);
    h.setPadding(0,dp(20),0,dp(4));body.addView(h);paragraph(content);
  }
  private void paragraph(String value){TextView text=label(value,14);text.setLineSpacing(dp(4),1);text.setPadding(0,dp(6),0,dp(10));body.addView(text);}
  private TextView label(String value,int sp){TextView v=new TextView(this);v.setText(value);v.setTextSize(sp);v.setTextColor(0xff293137);return v;}
  private void action(String value,Runnable run){
    TextView button=label(value,15);button.setTextColor(0xff2c6d96);button.setMinHeight(dp(48));
    button.setGravity(Gravity.CENTER_VERTICAL);button.setOnClickListener(v->run.run());body.addView(button);
  }
  private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
