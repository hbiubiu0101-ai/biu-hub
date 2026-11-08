package com.biu.next;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.math.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.json.*;

public final class MainActivity extends Activity {
  static final int INK = 0xff20282d,
      MUTED = 0xff626a70,
      BLUE = 0xff2c6d96,
      GREEN = 0xff38a378,
      RED = 0xffbe617f,
      BG = 0xfffaf7f2;
  Store db;
  LinearLayout page;
  FrameLayout root;
  ScrollView scroll;
  String screen = "home";
  private String payReturnScreen = "home";
  Handler handler = new Handler();
  TextView earnView, rateView, focusView;
  private TextView completedIncomeView;
  private LocalDateTime completedIncomeRefresh;
  private TextView wakeCardStatus, usageState, usageBadge;
  private ProgressBar usageProgress;
  private EditText homeTextEditor;
  private Runnable finishHomeTextEdit, cancelHomeTextEdit;
  private AppUpdates updates;
  private long lockEditCard = -1, lockEditItem = -1;
  private Runnable openLockEditor;
  private int reviewPeriod;
  private LocalDate reviewStart, reviewEnd;
  private int moneyPeriod, moneyOffset, moneyDirection;
  private LocalDate moneyStart, moneyEnd;
  LocalDate renderedDay;
  boolean visible = false;
  long focusOid = -1, focusStart = 0, focusBase = 0;
  String taskDay = Store.today();
  final Runnable tick =
      new Runnable() {
        public void run() {
          if (!visible) return;
          try {
            if (!LocalDate.now().equals(renderedDay) && !"payEdit".equals(screen)) {
              render();
              LockSync.refresh(MainActivity.this);
            }
            updateClock();
            updateWakeCardStatus();
            updateUsageState();
            if(!ActivationActivity.canUse(MainActivity.this)) {
              if(!"tools".equals(screen)){screen="tools";render();LockSync.refresh(MainActivity.this);}
              ActivationActivity.remindIfExpired(MainActivity.this);
            }
          } catch (Exception e) {
            toast("刷新失败：" + e.getMessage());
          }
          handler.postDelayed(this, 1000);
        }
      };

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    ActivationActivity.startTrial(this);
    db = new Store(this);
    updates = new AppUpdates(this);
    applyScreenIntent(getIntent());
    if (b != null) {
      screen = b.getString("youp_pay_screen",screen);
      payReturnScreen = b.getString("youp_pay_parent","home");
    }
    render();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    if (db == null) return;
    setIntent(intent);
    applyScreenIntent(intent);
    if (lockEditCard < 0) render();
  }

  private void applyScreenIntent(Intent intent) {
    long card = intent.getLongExtra(LockEditActivity.CARD, -1);
    if (card >= 0) {
      lockEditCard = card;
      lockEditItem = intent.getLongExtra(LockEditActivity.ITEM, -1);
      intent.removeExtra(LockEditActivity.CARD);
      intent.removeExtra(LockEditActivity.ITEM);
      screen = "home";
      return;
    }
    String destination = intent.getStringExtra(LockSync.SCREEN);
    if (destination == null) {
      if (Intent.ACTION_MAIN.equals(intent.getAction())) screen = "home";
      return;
    }
    if (Arrays.asList("tasks", "pay", "ledger", "focus", "study", "money", "review", "tools").contains(destination)) {
      screen = destination;
    } else {
      screen = "home";
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (db == null) return;
    visible = true;
    LockPanelTrace.record(this, "app-open enabled=" + LockPanelService.enabledInSettings(this)
        + " connected=" + LockPanelService.connected());
    LockPanelKeepAlive.resumeSession(this);
    if(!ActivationActivity.canUse(this))screen="tools";
    render();
    if (lockEditCard >= 0) {
      lockEditCard = lockEditItem = -1;
      if (openLockEditor != null) openLockEditor.run();
      else toast("卡片或条目已不存在");
      openLockEditor = null;
    }
    PrivacyCenter.requestWindowStructureUpdate(this);
    updates.resume();
    handler.removeCallbacks(tick);
    handler.post(tick);
    LockSync.refresh(this);
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (db == null) return;
    if (finishHomeTextEdit != null) finishHomeTextEdit.run();
    visible = false;
    updates.pause();
    handler.removeCallbacks(tick);
    saveFocus();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacks(tick);
    if (updates != null) updates.close();
    if (db != null) db.close();
    super.onDestroy();
  }

  @Override protected void onSaveInstanceState(Bundle state) {
    if ("pay".equals(screen) || "payEdit".equals(screen)) state.putString("youp_pay_screen",screen);
    state.putString("youp_pay_parent",payReturnScreen);
    super.onSaveInstanceState(state);
  }

  @Override
  public void onBackPressed() {
    if (cancelHomeTextEdit != null) { cancelHomeTextEdit.run(); return; }
    if("tools".equals(screen)&&!ActivationActivity.canUse(this)){super.onBackPressed();return;}
    if (!screen.equals("home")) {
      go(parentScreen());
    } else super.onBackPressed();
  }

  private String parentScreen() {
    if ("payEdit".equals(screen)) return "pay";
    if ("pay".equals(screen)) return payReturnScreen;
    if ("ledger".equals(screen)) return "money";
    return "home";
  }

  int dp(float n) {
    return (int) (getResources().getDisplayMetrics().density * n + .5f);
  }

  TextView text(String s, int sp, int color, boolean bold) {
    TextView v = new TextView(this);
    v.setText(s);
    v.setTextSize(sp);
    v.setTextColor(color);
    v.setFontFeatureSettings("tnum");
    if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    v.setGravity(Gravity.CENTER_VERTICAL);
    v.setLineSpacing(dp(2), 1);
    return v;
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  GradientDrawable bg(int color, int radius) {
    GradientDrawable b = new GradientDrawable();
    b.setColor(color);
    b.setCornerRadius(dp(radius));
    return b;
  }

  void gap(LinearLayout l, int h) {
    View v = new View(this);
    l.addView(v, new LinearLayout.LayoutParams(1, dp(h)));
  }

  void label(LinearLayout l, String s) {
    TextView t = text(s, 12, MUTED, false);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(14);
    p.bottomMargin = dp(5);
    l.addView(t, p);
  }

  void note(LinearLayout l, String s) {
    TextView t = text(s, 12, MUTED, false);
    l.addView(t);
    gap(l, 8);
  }

  TextView button(String s, Runnable cb, boolean primary) {
    TextView v = text(s, 14, primary ? Color.WHITE : BLUE, true);
    v.setGravity(Gravity.CENTER);
    v.setPadding(dp(14), dp(8), dp(14), dp(8));
    v.setMinHeight(dp(46));
    v.setBackground(bg(primary ? BLUE : 0xffedf3fc, 12));
    v.setOnClickListener(w -> cb.run());
    return v;
  }

  void actions(LinearLayout l, String[] names, Runnable[] callbacks) {
    LinearLayout r = row();
    for (int i = 0; i < names.length; i++) {
      Runnable callback = callbacks[i];
      boolean writes = !names[i].equals("取消") && (names[i].contains("保存")
          || names[i].contains("添加") || names[i].contains("删除") || names[i].contains("完成")
          || names[i].equals("暂停") || names[i].equals("继续"));
      TextView v = button(names[i], () -> {
        if (!writes || ActivationActivity.requireWrite(this)) callback.run();
      }, i == 0);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
      if (i > 0) lp.leftMargin = dp(8);
      r.addView(v, lp);
    }
    gap(l, 10);
    l.addView(r);
  }

  void line(LinearLayout l) {
    View v = new View(this);
    v.setBackgroundColor(0xffe6e0d8);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
    p.topMargin = dp(10);
    p.bottomMargin = dp(10);
    l.addView(v, p);
  }

  LinearLayout card(String title, String right, Runnable open) {
    LinearLayout l = column();
    l.setPadding(dp(17), dp(14), dp(17), dp(15));
    GradientDrawable b = bg(Color.WHITE, 18);
    b.setStroke(dp(1), 0xffe7edf6);
    l.setBackground(b);
    l.setElevation(dp(1));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(12);
    page.addView(l, p);
    if (title != null) {
      LinearLayout h = row();
      TextView a = text(title, 15, INK, true), z = text(right, 12, MUTED, false);
      h.addView(a, new LinearLayout.LayoutParams(0, -2, 1));
      h.addView(z);
      if (open != null) {
        h.setMinimumHeight(dp(40));
        h.setOnClickListener(v -> open.run());
      }
      l.addView(h);
    }
    return l;
  }

  void go(String s) {
    if(!"tools".equals(s)&&!ActivationActivity.canUse(this)){ActivationActivity.openRedemption(this);return;}
    if ("pay".equals(s) && !"payEdit".equals(screen) && !"pay".equals(screen))
      payReturnScreen = "money".equals(screen) ? "money" : "home";
    screen = s;
    render();
  }

  void changed() {
    int offset = "home".equals(screen) && scroll != null ? scroll.getScrollY() : 0;
    LockSync.refresh(this);
    render();
    if (offset > 0) {
      ScrollView current = scroll;
      current.post(() -> current.scrollTo(0, offset));
    }
  }

  void render() {
    if (finishHomeTextEdit != null) finishHomeTextEdit.run();
    if (homeTextEditor != null) return; // Keep a failed save available for retry.
    renderedDay = LocalDate.now();
    earnView = null;
    completedIncomeView = null;
    completedIncomeRefresh = null;
    rateView = null;
    focusView = null;
    wakeCardStatus = null;
    usageState=usageBadge=null;usageProgress=null;
    root = new FrameLayout(this) {
      @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && homeTextEditor != null) {
          android.graphics.Rect bounds = new android.graphics.Rect();
          homeTextEditor.getGlobalVisibleRect(bounds);
          if (!bounds.contains((int)event.getRawX(),(int)event.getRawY()) && finishHomeTextEdit != null)
            finishHomeTextEdit.run();
        }
        return super.dispatchTouchEvent(event);
      }
    };
    root.setBackgroundColor(BG);
    setContentView(root);
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setClipToPadding(false);
    boolean primary = "home".equals(screen) || "review".equals(screen) || "money".equals(screen);
    LinearLayout shell = primary ? column() : null;
    if (shell != null) {
      root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
      shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    } else {
      root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    }
    page = column();
    page.setPadding(dp(20), dp(12), dp(20), dp(primary ? 20 : 100));
    scroll.addView(page);
    if (Build.VERSION.SDK_INT >= 35)
      root.setOnApplyWindowInsetsListener(
          (v, in) -> {
            root.setPadding(
                in.getSystemWindowInsetLeft(),
                in.getSystemWindowInsetTop(),
                in.getSystemWindowInsetRight(),
                in.getSystemWindowInsetBottom());
            return in;
          });
    switch (screen) {
      case "home":
        home();
        bottomNavigation(shell);
        return;
      case "review":
        reviewPage();
        bottomNavigation(shell);
        return;
      case "money":
        moneyPage();
        bottomNavigation(shell);
        return;
      default:
        header(titleFor(screen));
    }
    switch (screen) {
      case "tasks":
        tasks();
        break;
      case "plans":
        plans(null);
        break;
      case "study":
        study();
        break;
      case "pay":
        pay();
        break;
      case "ledger":
        ledger();
        break;
      case "cards":
        cards();
        break;
      case "reading":
        reading();
        break;
      case "tools":
        toolsPage();
        break;
      case "payEdit":
        payEdit();
        break;
      case "focus":
        focus();
        break;
      default:
        home();
    }
  }

  String titleFor(String s) {
    switch (s) {
      case "tools":
        return "工具";
      case "payEdit":
        return "薪资与排班";
      case "tasks":
        return "今日任务";
      case "plans":
        return "长期计划";
      case "study":
        return "学习空间";
      case "pay":
        return "收入时钟";
      case "ledger":
        return "生活账本";
      case "cards":
        return "卡片自定义";
      case "lock":
        return "锁屏工作台";
      case "settings":
        return "设置与数据";
      case "reading":
        return "阅读与知识";
      case "focus":
        return "专注执行";
      default:
        return "有谱";
    }
  }

  void header(String title) {
    LinearLayout h = row();
    boolean payHeader="pay".equals(screen)||"payEdit".equals(screen);
    View back;
    if(payHeader) back=payToolbarButton(true,"返回",()->go(parentScreen()));
    else if("tools".equals(screen)) back=payToolbarButton(true,"返回",()->onBackPressed());
    else back=plainAction("‹",()->go(parentScreen()));
    back.setContentDescription("返回");
    h.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
    TextView t=text(title,20,INK,true);
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);
    p.leftMargin=payHeader?0:dp(12);if(payHeader)t.setGravity(Gravity.CENTER);
    h.addView(t,p);
    if ("pay".equals(screen)) {
      ImageButton more=payToolbarButton(false,"收入时钟更多操作",()->{});
      more.setOnClickListener(v->{
        PopupMenu menu=new PopupMenu(this,more);
        menu.getMenu().add("清除计薪设置");
        menu.setOnMenuItemClickListener(item->{confirmClearPaySettings();return true;});
        menu.show();
      });
      h.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));
    } else if(payHeader) h.addView(new View(this),new LinearLayout.LayoutParams(dp(48),dp(48)));
    page.addView(h);
    gap(page, 18);
  }

  private ImageButton payToolbarButton(boolean back,String description,Runnable action) {
    ImageButton button=new ImageButton(this);button.setContentDescription(description);
    button.setPadding(dp(12),dp(12),dp(12),dp(12));button.setScaleType(ImageView.ScaleType.CENTER);
    button.setBackgroundColor(Color.TRANSPARENT);
    button.setImageDrawable(new Drawable(){
      private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
      @Override public int getIntrinsicWidth(){return dp(24);}
      @Override public int getIntrinsicHeight(){return dp(24);}
      @Override public void draw(android.graphics.Canvas canvas){
        float x=getBounds().exactCenterX(),y=getBounds().exactCenterY();paint.setColor(BLUE);
        if(back){paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(dp(2));
          paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
          android.graphics.Path path=new android.graphics.Path();path.moveTo(x+dp(3),y-dp(7));
          path.lineTo(x-dp(4),y);path.lineTo(x+dp(3),y+dp(7));canvas.drawPath(path,paint);
        }else{paint.setStyle(android.graphics.Paint.Style.FILL);
          for(int i=-1;i<=1;i++)canvas.drawCircle(x+i*dp(6),y,dp(2),paint);}
      }
      @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
      @Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}
      @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    });
    button.setOnClickListener(v->action.run());return button;
  }

  String[] cardKeys = {
    "next", "pay", "spend", "tasks", "study", "plans", "stats", "reading", "focus"
  };
  String[] cardTitles = {"下一件事", "收入时钟", "今日消费", "今日任务", "学习进度", "长期计划", "收支概览", "阅读与知识", "专注计时"};

  List<String> order() {
    return new ArrayList<>(Arrays.asList(db.pref("order", String.join(",", cardKeys)).split(",")));
  }

  private TextView plainAction(String title, Runnable action) {
    TextView view = text(title, 14, BLUE, true);
    view.setGravity(Gravity.CENTER_VERTICAL);
    view.setMinHeight(dp(44));
    view.setOnClickListener(v -> action.run());
    return view;
  }

  private TextView sectionTitle(String title) {
    TextView view = text(title, 22, INK, true);
    view.setTypeface(Typeface.create("serif", Typeface.BOLD));
    return view;
  }

  private void toolsPage() {
    usageStatusCard();
    page.addView(plainAction("添加下拉记账按钮  ›", () -> ReceiptTile.addToPanel(this)));
    line(page);
    page.addView(plainAction("锁屏日志  ›", this::showLockLog));
    line(page);
    page.addView(plainAction("本地数据导出与导入  ›", this::backupTools));
    line(page);
    page.addView(plainAction("软件说明  ›",
        () -> startActivity(new Intent(this, PrivacyCenter.class))));
    note(page, "有谱 5.0.59 · 离线版");
  }

  private void usageStatusCard() {
    LinearLayout card=column();card.setPadding(dp(16),dp(16),dp(16),dp(12));
    GradientDrawable shape=bg(Color.WHITE,16);shape.setStroke(dp(1),0xffd3e1e6);card.setBackground(shape);
    LinearLayout title=row();title.addView(text("使用状态",17,INK,true),new LinearLayout.LayoutParams(0,-2,1));
    usageBadge=text("",12,BLUE,true);usageBadge.setPadding(dp(9),dp(4),dp(9),dp(4));usageBadge.setBackground(bg(0xffdef0f7,8));title.addView(usageBadge);card.addView(title);gap(card,10);
    usageState=text("",14,MUTED,false);card.addView(usageState);gap(card,12);
    usageProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);usageProgress.setMax(1000);
    usageProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));usageProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffe1e7ea));
    card.addView(usageProgress,new LinearLayout.LayoutParams(-1,dp(5)));gap(card,12);line(card);
    LinearLayout entry=row();TextView action=text(ActivationActivity.isRedeemed(this)?"查看激活状态":"验证码激活",14,BLUE,false);
    entry.addView(action,new LinearLayout.LayoutParams(0,dp(44),1));entry.addView(text("›",22,BLUE,false));
    entry.setContentDescription("验证码激活");entry.setOnClickListener(v->ActivationActivity.openRedemption(this));card.addView(entry);
    page.addView(card,new LinearLayout.LayoutParams(-1,-2));gap(page,18);updateUsageState();
  }

  private void updateUsageState() {
    if(usageState==null)return;
    boolean activated=ActivationActivity.isRedeemed(this);long left=activated?0:ActivationActivity.remaining(this);
    usageBadge.setText(activated?"永久使用":left>0?"试用中":"已到期");
    usageState.setText(activated?"已永久激活 · 无需联网":ActivationActivity.statusText(this));
    usageProgress.setVisibility(activated?View.GONE:View.VISIBLE);
    usageProgress.setProgress((int)Math.min(1000,Math.max(0,left*1000/OfflineLicensePolicy.TRIAL_MS)));
  }

  private void backupTools() {
    choose("本地数据导出与导入", new String[] {"导出本机备份", "从备份恢复"}, choice -> {
      if (choice == 0) {
        new AlertDialog.Builder(this).setTitle("导出未加密备份")
            .setMessage("备份包含计划、账本和设置，属于未加密 JSON 文件。请保存到自己信任的位置，不要公开转发；选择云盘时由对应文件服务处理。")
            .setNegativeButton("取消", null).setPositiveButton("继续导出", (d, w) -> {
              Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                  .addCategory(Intent.CATEGORY_OPENABLE)
                  .putExtra(Intent.EXTRA_TITLE, "有谱-备份-" + Store.today() + ".json");
              startActivityForResult(intent, 71);
            }).show();
      } else {
        confirm("恢复备份", "将覆盖本机数据，不会合并两份账本。建议先导出备份，仅导入自己信任的文件。", () -> openDocument(72));
      }
    });
  }

  private void showLockLog() {
    Form log = new Form("锁屏日志");
    log.compact = true;
    boolean authorized = LockPanelService.enabledInSettings(this);
    log.note((authorized ? "已授权" : "未授权") + "  ·  "
        + (LockPanelService.connected() ? "已连接" : "未连接") + "  ·  "
        + (LockPanelKeepAlive.enabled(this) ? "后台开启" : "后台关闭"));
    log.body.addView(text(LockPanelTrace.recentEvents(this), 13, INK, false));
    log.show("复制日志", () -> {
      ClipboardManager clipboard = getSystemService(ClipboardManager.class);
      clipboard.setPrimaryClip(ClipData.newPlainText("有谱锁屏日志", LockPanelTrace.report(this)));
      toast("已复制锁屏日志");
      return true;
    });
  }

  void home() {
    openLockEditor = null;
    LinearLayout masthead = row();
    LinearLayout identity = column();
    TextView brand = sectionTitle("有谱");
    brand.setTextSize(36);
    LinearLayout name = row();
    ImageView mark = new ImageView(this);
    mark.setImageResource(R.drawable.ic_next);
    mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    name.addView(mark, new LinearLayout.LayoutParams(dp(32), dp(32)));
    name.addView(brand);
    identity.addView(name);
    identity.addView(text(LocalDate.now().format(
        DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.CHINA)), 13, MUTED, false));
    masthead.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
    TextView tools = plainAction("⋯  工具", () -> go("tools"));
    tools.setGravity(Gravity.CENTER);
    tools.setPadding(dp(12), 0, dp(12), 0);
    GradientDrawable toolsFrame = bg(0xfffaf7f2, 22);
    toolsFrame.setStroke(dp(1), 0xffdedad4);
    tools.setBackground(toolsFrame);
    masthead.addView(tools);
    page.addView(masthead);
    gap(page, 16);

    LinearLayout planHeading = row();
    planHeading.setPadding(0, 0, 0, 0);
    TextView planTitle = text("我的计划", 18, INK, true);
    planTitle.setSingleLine(true);
    planTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
    planTitle.setAutoSizeTextTypeUniformWithConfiguration(12, 18, 1,
        android.util.TypedValue.COMPLEX_UNIT_SP);
    planHeading.addView(planTitle, new LinearLayout.LayoutParams(0, dp(48), 1));
    planTitle.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout newCard = column();
    TextView addCard = plainAction("＋ 新建卡片", () -> {
      if (newCard.getChildCount() == 0) openInlineNewCard(newCard);
      newCard.post(() -> newCard.requestRectangleOnScreen(
          new android.graphics.Rect(0, 0, newCard.getWidth(), Math.min(newCard.getHeight(), dp(160))), true));
    });
    addCard.setGravity(Gravity.CENTER);
    addCard.setSingleLine(true);
    addCard.setIncludeFontPadding(false);
    addCard.setMinWidth(dp(104));
    addCard.setEllipsize(android.text.TextUtils.TruncateAt.END);
    addCard.setAutoSizeTextTypeUniformWithConfiguration(12, 14, 1,
        android.util.TypedValue.COMPLEX_UNIT_SP);
    addCard.setPadding(dp(12), 0, dp(12), 0);
    addCard.setBackground(new InsetDrawable(bg(0xffe1f1f8, 20), 0, dp(5), 0, dp(5)));
    // Width follows the actual glyph measurement, including the user's system font scale.
    int measured = (int) Math.ceil(addCard.getPaint().measureText("＋ 新建卡片")) + dp(28);
    int available = getResources().getDisplayMetrics().widthPixels - dp(120);
    planHeading.addView(addCard, new LinearLayout.LayoutParams(
        Math.min(Math.max(dp(104), measured), Math.max(dp(104), available)), dp(48)));
    planHeading.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
      int width = right - left;
      if (width <= 0) return;
      int desired = Math.min(Math.max(dp(104), measured), Math.max(dp(80), width - dp(80)));
      if (addCard.getLayoutParams().width != desired) {
        ViewGroup.LayoutParams params = addCard.getLayoutParams();
        params.width = desired;
        addCard.setLayoutParams(params);
      }
    });
    page.addView(planHeading);
    gap(page, 4);
    page.addView(newCard, new LinearLayout.LayoutParams(-1,-2));

    for (LockCards.Group group : LockCards.homeGroups(db)) {
      LinearLayout planCard = homeCard();
      ((LinearLayout.LayoutParams)planCard.getLayoutParams()).bottomMargin = dp(12);
      LinearLayout heading = row();
      TextView title = sectionTitle(group.title);
      title.setMinHeight(dp(44));
      title.setContentDescription(group.title + "，双击编辑标题，长按更多设置");
      bindHomeDoubleTap(title, () -> beginHomeTextEdit(title, value -> {
        if (group.id == 0) db.putPref("lockPlanTitle",value);
        else db.exec("UPDATE plan_card SET title=? WHERE id=?",value,group.id);
      }), () -> managePlanInline(planCard,group));
      heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
      TextView deleteCard = plainAction("×", () -> confirm("删除卡片",
          "删除“" + currentPlanTitle(group) + "”及其中全部条目？", () -> {
            db.deletePlanCard(group.id); finishInlinePlan();
          }));
      deleteCard.setTextColor(MUTED);deleteCard.setTextSize(22);
      deleteCard.setGravity(Gravity.CENTER);
      deleteCard.setContentDescription("删除卡片 " + group.title);
      heading.addView(deleteCard,new LinearLayout.LayoutParams(dp(44),dp(44)));
      planCard.addView(heading);
      if (db.reminderCard(group.id)) note(planCard, "定时提醒");
      int completed = (int) group.tasks.stream().filter(task -> "done".equals(task.optString("state"))).count();
      boolean shownCompleted = false;
      for (JSONObject task : group.tasks) {
        if (!shownCompleted && "done".equals(task.optString("state"))) {
          compactHomeDivider(planCard);
          TextView completedInfo = plainAction("已完成 · " + completed + "  ⓘ", () ->
              new AlertDialog.Builder(this)
                  .setTitle("已完成事项")
                  .setMessage("已完成事项在当天留在首页。每天 00:00 后，之前的完成记录可以在「回顾」里查看。")
                  .setPositiveButton("知道了", null)
                  .show());
          completedInfo.setTextColor(MUTED);
          completedInfo.setTextSize(12);
          completedInfo.setMinHeight(dp(28));
          completedInfo.setPadding(0,0,0,0);
          completedInfo.setContentDescription("已完成 " + completed + " 项，查看归档说明");
          planCard.addView(completedInfo, new LinearLayout.LayoutParams(-2, -2));
          shownCompleted = true;
        }
        LinearLayout taskLine = taskRow(planCard, task, true, () -> openInlinePlanItem(planCard, group, task),
            () -> confirmPlanItemRemoval(planCard, group, task));
        bindHomeItemDrag(taskLine,group,task);
      }
      compactHomeDivider(planCard);
      planCard.addView(plainAction("＋ 添加条目", () -> openInlinePlanItem(planCard, group, null)));
      LinearLayout editor = column();
      planCard.addView(editor, new LinearLayout.LayoutParams(-1, -2));
      editor.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
        @Override public void onChildViewAdded(View parent, View child) { styleInlineEditor(editor); }
        @Override public void onChildViewRemoved(View parent, View child) { styleInlineEditor(editor); }
      });
      if (group.id == lockEditCard) {
        if (lockEditItem < 0) openLockEditor = () -> {
          managePlanInline(planCard, group);
          editor.post(() -> editor.requestRectangleOnScreen(
              new android.graphics.Rect(0, 0, editor.getWidth(), editor.getHeight()), true));
        };
        else for (JSONObject task : group.tasks) {
          if (task.optLong("id", -1) == lockEditItem) {
            openLockEditor = () -> openInlinePlanItem(planCard, group, task);
            break;
          }
        }
      }
    }
    LinearLayout clock = homeCard();
    ((LinearLayout.LayoutParams)clock.getLayoutParams()).bottomMargin = dp(12);
    LinearLayout clockHeading = row();
    ImageView clockIcon = new ImageView(this);
    clockIcon.setImageResource(R.drawable.ic_tab_review);
    clockIcon.setImageTintList(ColorStateList.valueOf(INK));
    clockIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    clockHeading.addView(clockIcon, new LinearLayout.LayoutParams(dp(21), dp(21)));
    gapHorizontal(clockHeading, 7);
    clockHeading.addView(text("收入时钟", 17, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
    clockHeading.addView(plainAction("计薪进度  ›", () -> go("pay")));
    clock.addView(clockHeading);
    clock.addView(text("今日计薪预估", 13, MUTED, false));
    gap(clock, 2);
    earnView = text("", 28, 0xff15536e, true);
    clock.addView(earnView);
    updateClock();
    compactHomeDivider(clock);
    clock.addView(text("锁屏显示",13,MUTED,false));
    moneyLockRow(clock, "计薪收入", "lockPay");
    moneyLockRow(clock, "今日消费", "lockSpend");
    LinearLayout settings = homeCard();
    showSproutSetting(settings);
    line(settings);
    showWakeCardSetting(settings);
    line(settings);
    addSettingAction(settings, "预览与调整锁屏",
        () -> startActivity(new Intent(this, LockStyleActivity.class)));
    line(settings);
    Switch auto = new Switch(this);
    auto.setText("自运行功能");
    auto.setMinHeight(dp(44));
    auto.setChecked(LockPanelKeepAlive.autoRun(this));
    auto.setOnCheckedChangeListener((view, enabled) -> LockPanelKeepAlive.setAutoRun(this, enabled));
    settings.addView(auto);
    note(settings, "控制划掉最近任务后是否继续显示小芽和亮屏卡片。");
    updateClock();
  }

  private void bottomNavigation(LinearLayout shell) {
    LinearLayout dock = column();
    dock.setBackgroundColor(Color.WHITE);
    View divider = new View(this);
    divider.setBackgroundColor(0xffe6e0d8);
    dock.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    LinearLayout tabs = row();
    tabs.setPadding(dp(10), dp(5), dp(10), dp(5));
    String[] destinations = {"home", "review", "money"};
    String[] names = {"首页", "回顾", "收支"};
    int[] icons = {R.drawable.ic_tab_today, R.drawable.ic_tab_review, R.drawable.ic_tab_money};
    for (int i = 0; i < destinations.length; i++) {
      String destination = destinations[i];
      boolean selected = destination.equals(screen);
      LinearLayout tab = column();
      tab.setGravity(Gravity.CENTER);
      tab.setSelected(selected);
      tab.setContentDescription(names[i]);
      FrameLayout iconSlot = new FrameLayout(this);
      if (selected) iconSlot.setBackground(bg(0xffe3f1f8, 14));
      tab.addView(iconSlot, new LinearLayout.LayoutParams(dp(52), dp(28)));
      ImageView icon = new ImageView(this);
      icon.setImageResource(icons[i]);
      icon.setImageTintList(ColorStateList.valueOf(selected ? BLUE : MUTED));
      icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      iconSlot.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
      TextView label = text(names[i], 12, selected ? BLUE : MUTED, selected);
      label.setGravity(Gravity.CENTER);
      label.setIncludeFontPadding(false);
      label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      LinearLayout.LayoutParams caption = new LinearLayout.LayoutParams(-1, -2);
      caption.topMargin = dp(3);
      tab.addView(label, caption);
      tab.setOnClickListener(v -> { if (!destination.equals(screen)) go(destination); });
      tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(52), 1));
    }
    dock.addView(tabs);
    shell.addView(dock, new LinearLayout.LayoutParams(-1, -2));
  }

  private void pageTitle(String title, String subtitle) {
    TextView heading = sectionTitle(title);
    heading.setTextSize(30);
    page.addView(heading);
    if (!subtitle.isEmpty()) note(page, subtitle);
    gap(page, 14);
  }

  private void reviewPage() {
    pageTitle("事项回顾", "");
    LocalDate today = LocalDate.now();
    LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1);
    LocalDate from = reviewPeriod == 1 ? today.withDayOfMonth(1)
        : reviewPeriod == 2 && reviewStart != null ? reviewStart : monday;
    LocalDate to = reviewPeriod == 1 ? today.withDayOfMonth(today.lengthOfMonth())
        : reviewPeriod == 2 && reviewEnd != null ? reviewEnd : monday.plusDays(6);
    LinearLayout periods = row();
    periods.setPadding(dp(3), dp(3), dp(3), dp(3));
    periods.setBackground(bg(0xffeef1f2, 24));
    String[] labels = {"本周", "本月", "自定义"};
    for (int i = 0; i < labels.length; i++) {
      int period = i;
      TextView option = plainAction(labels[i], () -> {
        if (period == 2) chooseReviewDates(from, to);
        else { reviewPeriod = period; render(); }
      });
      option.setGravity(Gravity.CENTER);
      option.setSelected(reviewPeriod == i);
      if (reviewPeriod == i) option.setBackground(bg(0xffd7edf6, 21));
      periods.addView(option, new LinearLayout.LayoutParams(0, dp(40), 1));
    }
    page.addView(periods);
    page.addView(plainAction(reviewDate(from) + " – " + reviewDate(to) + "  ›",
        () -> chooseReviewDates(from, to)));
    db.materialize();
    List<JSONObject> done = db.reviewTasks(from, to, true);
    List<JSONObject> pending = db.reviewTasks(from, to, false);
    summaryPair("已完成", String.valueOf(done.size()), "未完成", String.valueOf(pending.size()));
    reviewList("已完成", done, true);
    reviewList("未完成", pending, false);
  }

  private String reviewDate(LocalDate date) {
    return date.format(DateTimeFormatter.ofPattern(
        date.getYear() == LocalDate.now().getYear() ? "M月d日" : "yyyy年M月d日"));
  }

  private void chooseReviewDates(LocalDate from, LocalDate to) {
    Form dialog = new Form("自定义日期");
    TextView start = dialog.date("开始日期", from.toString());
    TextView end = dialog.date("结束日期", to.toString());
    dialog.show("查看回顾", () -> {
      LocalDate first = LocalDate.parse(val(start)), last = LocalDate.parse(val(end));
      if (last.isBefore(first)) throw new IllegalArgumentException("结束日期不能早于开始日期");
      reviewStart = first;
      reviewEnd = last;
      reviewPeriod = 2;
      render();
      return true;
    });
  }

  private void summaryPair(String firstLabel, String firstValue, String secondLabel, String secondValue) {
    LinearLayout summary = row();
    summary.setPadding(dp(14), dp(14), dp(14), dp(14));
    summary.setBackground(bg(0xffe7f3f7, 16));
    String[] labels = {firstLabel, secondLabel}, values = {firstValue, secondValue};
    for (int i = 0; i < 2; i++) {
      if (i == 1) {
        View divider = new View(this);
        divider.setBackgroundColor(0xffc4dbe3);
        summary.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(43)));
      }
      LinearLayout cell = column();
      cell.setGravity(Gravity.CENTER_HORIZONTAL);
      cell.addView(text(labels[i], 13, MUTED, false));
      TextView value = text(values[i], 23, 0xff15536e, true);
      value.setMaxLines(1);
      value.setAutoSizeTextTypeUniformWithConfiguration(11, 23, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
      value.setGravity(Gravity.CENTER);
      cell.addView(value, new LinearLayout.LayoutParams(-1, dp(34)));
      summary.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
    }
    page.addView(summary);
    gap(page, 16);
  }

  private void reviewList(String title, List<JSONObject> tasks, boolean completed) {
    page.addView(sectionTitle(title));
    if (tasks.isEmpty()) note(page, completed ? "这个时段还没有完成事项" : "这个时段没有未完成事项");
    for (JSONObject task : tasks) {
      LinearLayout item = row();
      item.setMinimumHeight(dp(49));
      ImageView marker = new ImageView(this);
      marker.setImageDrawable(new CompletionMark(dp(19), completed ? BLUE : MUTED, completed));
      marker.setScaleType(ImageView.ScaleType.CENTER);
      item.addView(marker, new LinearLayout.LayoutParams(dp(35), dp(40)));
      item.addView(text(task.optString("title"), 15, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
      String date = completed ? task.optString("completed") : task.optString("occurday");
      if (date.length() < 10) date = task.optString("occurday");
      if (date.length() >= 10) item.addView(text(reviewDate(LocalDate.parse(date.substring(0, 10))), 12, MUTED, false));
      page.addView(item);
      line(page);
    }
    gap(page, 14);
  }

  private void moneyPage() {
    LinearLayout header = row();
    TextView title = sectionTitle(db.pref("moneyCardTitle", "收支"));
    title.setTextSize(30);
    title.setContentDescription("收支标题，双击原位改名");
    bindHomeDoubleTap(title, () -> beginHomeTextEdit(title,
        value -> db.putPref("moneyCardTitle", value)));
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(plainAction("识别账单  ›", () -> startActivity(new Intent(this, ReceiptActivity.class))));
    page.addView(header);
    gap(page, 12);
    LocalDate today = LocalDate.now();
    LocalDate from = today, to = today;
    if (moneyPeriod == 1) {
      from = today.minusDays(today.getDayOfWeek().getValue() - 1).plusWeeks(moneyOffset);
      to = from.plusDays(6);
    } else if (moneyPeriod == 2) {
      from = today.withDayOfMonth(1).plusMonths(moneyOffset);
      to = from.withDayOfMonth(from.lengthOfMonth());
    } else if (moneyPeriod == 3 && moneyStart != null) {
      from = moneyStart;
      to = moneyEnd;
    }
    LocalDate first = from, last = to;
    String[] periods = {"今天", "本周", "本月", "自定义"};
    page.addView(moneyOptions(periods, moneyPeriod, selected -> {
      if (selected == 3) chooseMoneyDates(moneyStart == null ? first : moneyStart, moneyEnd == null ? last : moneyEnd);
      else {
        moneyPeriod = selected;
        moneyOffset = 0;
        render();
      }
    }));
    LinearLayout dates = row();
    String range = moneyPeriod == 2 ? from.format(DateTimeFormatter.ofPattern("yyyy年M月"))
        : from.equals(to) ? reviewDate(from) + " · " + from.format(DateTimeFormatter.ofPattern("EEEE", Locale.CHINA))
        : reviewDate(from) + " – " + reviewDate(to);
    TextView dateRange = plainAction(range + (moneyPeriod == 2 ? "" : "  ›"), () -> {
      if (moneyPeriod == 2) chooseMoneyMonth(first);
      else chooseMoneyDates(first, last);
    });
    if (moneyPeriod == 1 || moneyPeriod == 2) {
      TextView back = plainAction("‹", () -> { moneyOffset--; render(); });
      back.setContentDescription(moneyPeriod == 1 ? "上一周" : "上个月");
      back.setGravity(Gravity.CENTER);
      dates.addView(back, new LinearLayout.LayoutParams(dp(44), -2));
      dateRange.setGravity(Gravity.CENTER);
      dates.addView(dateRange, new LinearLayout.LayoutParams(0, -2, 1));
      TextView next = plainAction("›", () -> { moneyOffset++; render(); });
      next.setContentDescription(moneyPeriod == 1 ? "下一周" : "下个月");
      next.setGravity(Gravity.CENTER);
      next.setEnabled(moneyOffset < 0);
      next.setAlpha(moneyOffset < 0 ? 1f : .3f);
      dates.addView(next, new LinearLayout.LayoutParams(dp(44), -2));
    } else {
      dates.addView(dateRange, new LinearLayout.LayoutParams(0, -2, 1));
    }
    page.addView(dates);
    LocalDate end = to.plusDays(1);
    long received = db.received(from, end), spent = db.spending(from, end);
    LinearLayout summary = column();
    summary.setPadding(dp(15), dp(11), dp(15), dp(12));
    summary.setBackground(bg(0xffe7f3f7, 16));
    summary.addView(text((moneyPeriod == 0 ? "今日" : moneyPeriod == 2 && moneyOffset == 0 ? "本月" : "期间") + "净额", 13, MUTED, true));
    summary.addView(text(yuan(received - spent), 30, 0xff15536e, true));
    gap(summary, 8);
    LinearLayout totals = row();
    totals.addView(text("到账  " + yuan(received), 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
    View divider = new View(this);
    divider.setBackgroundColor(0xffc4dbe3);
    LinearLayout.LayoutParams dividerSize = new LinearLayout.LayoutParams(dp(1), dp(18));
    dividerSize.rightMargin = dp(12);
    totals.addView(divider, dividerSize);
    totals.addView(text("支出  " + yuan(spent), 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
    summary.addView(totals);
    page.addView(summary);
    gap(page, 12);
    if (moneyPeriod == 2) {
      LinearLayout budget = homeCard();
      long limit = Long.parseLong(db.pref("budget", "0"));
      LinearLayout budgetTitle = row();
      budgetTitle.addView(financeSectionTitle(moneyOffset == 0 ? "本月预算" : "所选月份预算"),
          new LinearLayout.LayoutParams(0, -2, 1));
      String budgetStatus = limit <= 0 ? "设置预算" : spent > limit
          ? "超出 " + yuan(spent - limit) : "剩余 " + yuan(limit - spent);
      budgetTitle.addView(plainAction(budgetStatus + "  ›", this::budgetEdit));
      budget.addView(budgetTitle);
      if (limit > 0) {
        int used = (int) Math.max(0, Math.min(100, spent * 100.0 / limit));
        note(budget, "已用 " + yuan(spent) + " / " + yuan(limit));
        bar(budget, used, spent > limit ? RED : BLUE);
      }
    }
    moneyRecords(from, end);
    moneyCategories(from, end);
  }

  private LinearLayout moneyOptions(String[] labels, int current, java.util.function.IntConsumer change) {
    LinearLayout options = row();
    options.setPadding(dp(3), dp(3), dp(3), dp(3));
    options.setBackground(bg(0xffeef1f2, 24));
    for (int i = 0; i < labels.length; i++) {
      int selected = i;
      TextView option = plainAction(labels[i], () -> change.accept(selected));
      option.setPadding(0, 0, 0, 0);
      option.setGravity(Gravity.CENTER);
      option.setSelected(i == current);
      if (i == current) option.setBackground(bg(0xffd7edf6, 21));
      options.addView(option, new LinearLayout.LayoutParams(0, dp(40), 1));
    }
    return options;
  }

  private void chooseMoneyDates(LocalDate from, LocalDate to) {
    Form form = new Form("选择日期范围");
    TextView start = form.date("开始日期", from.toString());
    TextView end = form.date("结束日期", to.toString());
    form.note("可以选同一天，也可以跨月。");
    form.show("查看账单", () -> {
      LocalDate first = LocalDate.parse(val(start)), last = LocalDate.parse(val(end));
      if (last.isBefore(first)) throw new IllegalArgumentException("结束日期不能早于开始日期");
      moneyStart = first;
      moneyEnd = last;
      moneyPeriod = 3;
      render();
      return true;
    });
  }

  private void chooseMoneyMonth(LocalDate selected) {
    Form form = new Form("选择月份");
    TextView day = form.date("选择该月内任意一天", selected.toString());
    form.show("查看月份", () -> {
      long offset = java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.now(), YearMonth.from(LocalDate.parse(val(day))));
      if (offset > 0) throw new IllegalArgumentException("请选择当前或过去的月份");
      moneyOffset = (int) offset;
      render();
      return true;
    });
  }

  private void moneyCategories(LocalDate start, LocalDate end) {
    LinearLayout categories = homeCard();
    categories.addView(financeSectionTitle("支出分类"));
    List<JSONObject> items = db.rows("SELECT category,SUM(cents) AS amount FROM ledger"
        + " WHERE day>=? AND day<? AND direction='支出' GROUP BY category ORDER BY amount DESC,category",
        start.toString(), end.toString());
    long total = 0;
    for (JSONObject item : items) total += item.optLong("amount");
    if (total <= 0) {
      note(categories, "这个时段还没有支出");
      return;
    }
    LinearLayout chart = row();
    chart.setBackground(bg(0xffeef1f2, 5));
    chart.setClipToOutline(true);
    int[] colors = {0xff77bedf, 0xffa8a3da, 0xffebba8e, 0xff86bdaf, 0xffaeb9c0};
    int index = 0;
    for (JSONObject item : items) {
      long amount = item.optLong("amount");
      if (amount <= 0) continue;
      View segment = new View(this);
      segment.setBackgroundColor(colors[index++ % colors.length]);
      chart.addView(segment, new LinearLayout.LayoutParams(0, dp(9), amount));
    }
    categories.addView(chart);
    index = 0;
    for (JSONObject item : items) {
      long amount = item.optLong("amount");
      if (amount <= 0) continue;
      LinearLayout label = row();
      View dot = new View(this);
      dot.setBackground(bg(colors[index++ % colors.length], 4));
      label.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
      gapHorizontal(label, 6);
      label.addView(text(item.optString("category", "其他"), 13, INK, false), new LinearLayout.LayoutParams(0, dp(29), 1));
      label.addView(text(yuan(amount) + "  ·  " + Math.round(100.0 * amount / total) + "%", 13, MUTED, false));
      categories.addView(label);
    }
    note(categories, "分类按支出金额统计，退款计入净支出。");
  }

  private TextView financeSectionTitle(String label) {
    return text(label, 18, INK, true);
  }

  private void moneyRecords(LocalDate from, LocalDate end) {
    LinearLayout records = homeCard();
    LinearLayout heading = row();
    heading.addView(financeSectionTitle("账单明细"), new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout filters = moneyOptions(new String[]{"全部", "收入", "支出"}, moneyDirection, selected -> {
      if (moneyDirection == selected) return;
      moneyDirection = selected;
      render();
    });
    heading.addView(filters, new LinearLayout.LayoutParams(dp(150), -2));
    records.addView(heading);
    gap(records, 4);

    String filter = moneyDirection == 1 ? " AND direction='收入'"
        : moneyDirection == 2 ? " AND direction IN ('支出','退款')" : "";
    List<JSONObject> entries = db.rows("SELECT * FROM ledger WHERE day>=? AND day<?" + filter
        + " ORDER BY day DESC,time DESC,id DESC", from.toString(), end.toString());
    if (entries.isEmpty()) {
      TextView empty = text("这个时段还没有对应账单", 13, MUTED, false);
      empty.setGravity(Gravity.CENTER);
      records.addView(empty, new LinearLayout.LayoutParams(-1, dp(64)));
      return;
    }

    // Give the list a bounded viewport while letting Android recycle rows and pass
    // scrolling on to the parent page after the list reaches an edge.
    int rowHeight = dp(54) + Math.round(dp(28) * Math.max(0, getResources().getConfiguration().fontScale - 1));
    ListView list = new ListView(this);
    list.setContentDescription("账单明细，可上下滑动");
    list.setNestedScrollingEnabled(true);
    list.setVerticalScrollBarEnabled(true);
    list.setScrollbarFadingEnabled(false);
    list.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
    list.setDivider(new ColorDrawable(0xffe9edef));
    list.setDividerHeight(dp(1));
    list.setPadding(0, 0, dp(5), 0);
    if (Build.VERSION.SDK_INT >= 29) {
      list.setVerticalScrollbarThumbDrawable(moneyScrollbar(0xff839ca9));
      list.setVerticalScrollbarTrackDrawable(moneyScrollbar(0xffedf1f3));
    }
    list.setAdapter(new BaseAdapter() {
      @Override public int getCount() { return entries.size(); }
      @Override public JSONObject getItem(int position) { return entries.get(position); }
      @Override public long getItemId(int position) { return getItem(position).optLong("id"); }
      @Override public boolean hasStableIds() { return true; }

      @Override public View getView(int position, View recycled, ViewGroup parent) {
        MoneyEntryRow entry = recycled instanceof MoneyEntryRow existing ? existing : new MoneyEntryRow(rowHeight);
        entry.bind(getItem(position));
        return entry;
      }
    });
    list.setOnItemClickListener((parent, row, position, id) -> ledgerEdit(entries.get(position)));
    int visibleRows = Math.min(6, entries.size());
    records.addView(list, new LinearLayout.LayoutParams(-1, rowHeight * visibleRows + dp(1) * (visibleRows - 1)));
  }

  private GradientDrawable moneyScrollbar(int color) {
    GradientDrawable bar = bg(color, 3);
    bar.setSize(dp(4), dp(4));
    return bar;
  }

  private final class MoneyEntryRow extends LinearLayout {
    final TextView icon, name, subtitle, amount;

    MoneyEntryRow(int rowHeight) {
      super(MainActivity.this);
      setGravity(Gravity.CENTER_VERTICAL);
      setLayoutParams(new AbsListView.LayoutParams(-1, rowHeight));
      setPadding(0, 0, dp(5), 0);
      icon = text("", 16, BLUE, true);
      icon.setGravity(Gravity.CENTER);
      icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
      gapHorizontal(this, 8);
      LinearLayout info = column();
      name = text("", 14, INK, true);
      subtitle = text("", 11, MUTED, false);
      for (TextView line : new TextView[]{name, subtitle}) {
        line.setSingleLine(true);
        line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(line);
      }
      addView(info, new LinearLayout.LayoutParams(0, -2, 1));
      gapHorizontal(this, 8);
      amount = text("", 14, INK, true);
      amount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
      addView(amount);
    }

    void bind(JSONObject entry) {
      String direction = entry.optString("direction");
      boolean incoming = "收入".equals(direction) || "退款".equals(direction);
      boolean outgoing = "支出".equals(direction);
      icon.setText(incoming ? "↓" : outgoing ? "↑" : "↔");
      icon.setTextColor(incoming ? 0xff21674e : BLUE);
      icon.setBackground(bg(incoming ? 0xffe7f4ed : 0xffeaf3f8, 16));
      String category = entry.optString("category", "其他");
      String title = entry.optString("note");
      name.setText(title.isEmpty() ? category : title);
      String day = entry.optString("day");
      String time = entry.optString("time");
      if (day.length() == 10 && day.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) day = day.substring(5).replace('-', '/');
      if (time.length() > 5) time = time.substring(0, 5);
      String when = (moneyPeriod == 0 ? "" : day + " ") + time;
      subtitle.setText(category + (when.trim().isEmpty() ? " · " + direction : " · " + when.trim()));
      amount.setText((outgoing ? "−" : incoming ? "+" : "") + yuan(entry.optLong("cents")));
      amount.setTextColor(incoming ? 0xff21674e : INK);
    }
  }

  private void gapHorizontal(LinearLayout row, int width) {
    row.addView(new View(this), new LinearLayout.LayoutParams(dp(width), 1));
  }

  private String yuan(long cents) {
    return (cents < 0 ? "−" : "") + "¥" + Engine.money(Math.abs(cents));
  }

  private void moneyLockRow(LinearLayout parent, String label, String key) {
    LinearLayout entry = row();
    boolean isIncome="lockPay".equals(key);
    TextView name=text(LockCards.moneyLabel(db,isIncome),14,INK,false);
    bindHomeDoubleTap(name,()->beginHomeTextEdit(name,value->{
      if(value.codePointCount(0,value.length())>12)throw new IllegalArgumentException("名称最多12个字");
      db.putPref(isIncome?"lockIncomeTitle":"lockSpendTitle",value);
    }));
    name.setContentDescription("双击修改 "+name.getText());
    entry.addView(name,new LinearLayout.LayoutParams(0,-2,1));
    entry.addView(lockCheckbox(LockCards.moneyLabel(db,isIncome), "true".equals(db.pref(key, "false")), enabled -> {
      db.putPref(key, String.valueOf(enabled));
      onLockSelectionChanged(enabled);
    }));
    parent.addView(entry);
  }

  private void addSettingAction(LinearLayout parent, String label, Runnable click) {
    LinearLayout setting = row();
    setting.setMinimumHeight(dp(48));
    setting.addView(text(label, 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
    TextView chevron = text("›", 22, MUTED, false);
    chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    setting.addView(chevron);
    setting.setContentDescription(label);
    setting.setOnClickListener(v -> click.run());
    parent.addView(setting);
  }

  private CheckBox lockCheckbox(String name, boolean checked, java.util.function.Consumer<Boolean> listener) {
    CheckBox check = new CheckBox(this);
    check.setButtonDrawable((Drawable) null);
    check.setTextSize(12);
    check.setGravity(Gravity.CENTER);
    check.setPadding(dp(9), 0, dp(9), 0);
    check.setMinimumWidth(dp(68));
    check.setMinimumHeight(dp(44));
    check.setContentDescription("在锁屏显示 " + name);
    check.setChecked(checked);
    styleLockCheckbox(check, checked);
    check.setOnCheckedChangeListener((view, enabled) -> {
      if (!ActivationActivity.requireWrite(this)) {
        check.setOnCheckedChangeListener(null);
        check.setChecked(!enabled);
        render();
        return;
      }
      if (enabled && !PrivacyCenter.lockNoticeAccepted(this)) {
        check.setOnCheckedChangeListener(null);
        check.setChecked(false);
        new AlertDialog.Builder(this).setTitle("锁屏内容可能被他人看到")
            .setMessage("开启后，所选事项会显示真实内容；金额由各自的眼睛控制。锁屏内容可能被他人看到，请确认需要公开显示。")
            .setNegativeButton("取消", (d, w) -> render())
            .setPositiveButton("了解并开启", (d, w) -> {
              PrivacyCenter.preferences(this).edit().putBoolean("lock_notice", true).apply();
              listener.accept(true); render();
            }).setOnCancelListener(d -> render()).show();
        return;
      }
      styleLockCheckbox(check, enabled);
      listener.accept(enabled);
    });
    return check;
  }

  private void styleLockCheckbox(CheckBox check, boolean checked) {
    int ink = checked ? 0xff1c6080 : MUTED;
    Drawable lock = getDrawable(R.drawable.ic_lock_small).mutate();
    lock.setTint(ink);
    lock.setBounds(0, 0, dp(13), dp(13));
    SpannableString label = new SpannableString("\ufffc\u2009锁屏");
    int alignment = Build.VERSION.SDK_INT >= 29 ? DynamicDrawableSpan.ALIGN_CENTER
        : DynamicDrawableSpan.ALIGN_BASELINE;
    label.setSpan(new ImageSpan(lock, alignment), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    check.setText(label);
    check.setTextColor(ink);
    GradientDrawable capsule = bg(checked ? 0xffe0eff6 : 0xfff1f2f2, 22);
    check.setBackground(new InsetDrawable(capsule, 0, dp(7), 0, dp(7)));
  }

  private void compactHomeDivider(LinearLayout card) {
    View divider=new View(this);divider.setBackgroundColor(0xffe6e0d8);
    LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(-1,dp(1));
    space.topMargin=dp(6);space.bottomMargin=dp(6);card.addView(divider,space);
  }

  private static final class HomeItemDrag {
    final long card;final JSONObject item;final boolean completed;
    HomeItemDrag(long card,JSONObject item){this.card=card;this.item=item;completed="done".equals(item.optString("state"));}
  }

  private void bindHomeItemDrag(LinearLayout entry,LockCards.Group group,JSONObject item) {
    TextView handle=text("⠿",19,MUTED,false);handle.setGravity(Gravity.CENTER);
    handle.setContentDescription("长按排序 " + item.optString("title"));
    entry.addView(handle,new LinearLayout.LayoutParams(dp(32),dp(44)));
    handle.setOnLongClickListener(view->{
      if (!ActivationActivity.requireWrite(this)) return true;
      boolean started=view.startDragAndDrop(ClipData.newPlainText("条目排序",""),
          new View.DragShadowBuilder(entry),new HomeItemDrag(group.id,item),0);
      if(started){entry.setAlpha(.65f);view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);}
      return true;
    });
    entry.setOnDragListener((view,event)->{
      if(!(event.getLocalState() instanceof HomeItemDrag))return false;
      HomeItemDrag source=(HomeItemDrag)event.getLocalState();
      boolean completed="done".equals(item.optString("state"));
      // Completed tasks remain at the end; drag only inside the same section/card.
      if(source.card!=group.id || source.completed!=completed)return false;
      switch(event.getAction()){
        case DragEvent.ACTION_DRAG_ENTERED:
          if(source.item!=item)entry.setBackground(bg(0xffe5f2f8,8));break;
        case DragEvent.ACTION_DRAG_LOCATION:
          int[] bounds=new int[2],position=new int[2];scroll.getLocationOnScreen(bounds);entry.getLocationOnScreen(position);
          float y=position[1]+event.getY()-bounds[1];
          if(y<dp(32))scroll.scrollBy(0,-dp(12));
          else if(y>scroll.getHeight()-dp(32))scroll.scrollBy(0,dp(12));break;
        case DragEvent.ACTION_DRAG_EXITED:entry.setBackground(null);break;
        case DragEvent.ACTION_DRAG_ENDED:entry.setAlpha(1);entry.setBackground(null);break;
        case DragEvent.ACTION_DROP:
          entry.setBackground(null);
          if(source.item==item)return false;
          List<JSONObject> order=new ArrayList<>(group.tasks);
          order.removeIf(task->task.optLong("id")==source.item.optLong("id"));
          int target=order.indexOf(item);
          if(target<0)return false;
          if(event.getY()>=entry.getHeight()/2f)target++;
          order.add(target,source.item);db.savePlanItemOrder(group.id,order);
          entry.post(this::changed);return true;
        default:break;
      }
      return true;
    });
  }

  private LinearLayout homeCard() {
    LinearLayout card = column();
    GradientDrawable frame = bg(Color.WHITE, 14);
    frame.setStroke(dp(1), 0xffdce5e3);
    card.setBackground(frame);
    card.setPadding(dp(12), dp(8), dp(12), dp(8));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(10);
    page.addView(card, params);
    return card;
  }

  private void showSproutSetting(LinearLayout settings) {
    Switch floating = new Switch(this);
    floating.setText("小芽悬浮助手");
    floating.setMinHeight(dp(44));
    floating.setChecked(LockPanelKeepAlive.floatingEnabled(this));
    floating.setOnCheckedChangeListener((view, enabled) -> {
      LockPanelKeepAlive.setFloatingEnabled(this, enabled);
      if (enabled && (!LockPanelService.enabledInSettings(this) || !PrivacyCenter.accessibilityAccepted(this))) openAccessibilitySettings();
    });
    settings.addView(floating);
    settings.addView(text("精灵造型", 13, MUTED, false));
    LinearLayout shapes = row();
    settings.addView(shapes);
    SproutView[] previews = new SproutView[SproutView.SHAPES.length];

    settings.addView(text("精灵颜色", 13, MUTED, false));
    RadioGroup colors = new RadioGroup(this);
    colors.setOrientation(LinearLayout.HORIZONTAL);
    settings.addView(colors);
    for (int index = 0; index <= SproutView.ORIGINAL; index++) {
      RadioButton option = new RadioButton(this);
      option.setId(View.generateViewId());
      boolean original = index == SproutView.ORIGINAL;
      option.setContentDescription(original ? "原色" : SproutView.COLOR_NAMES[index]);
      option.setButtonTintList(ColorStateList.valueOf(original ? BLUE : SproutView.COLORS[index]));
      if (original) {
        option.setText("原色");
        option.setTextSize(12);
      }
      colors.addView(option, new RadioGroup.LayoutParams(dp(original ? 61 : 36), dp(44)));
    }

    int current = SproutView.shapeIndex(this);
    for (int index = 0; index < previews.length; index++) {
      int shape = index;
      LinearLayout tile = column();
      tile.setGravity(Gravity.CENTER);
      StateListDrawable states = new StateListDrawable();
      GradientDrawable active = bg(0xffeaf4f9, 10);
      active.setStroke(dp(1), 0xffa2c5d8);
      states.addState(new int[]{android.R.attr.state_selected}, active);
      states.addState(new int[]{}, bg(0xfff6f7f7, 10));
      tile.setBackground(states);
      tile.setContentDescription(SproutView.SHAPES[shape]);
      tile.setPadding(dp(2), 0, dp(2), dp(6));
      SproutView preview = new SproutView(this, shape);
      preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      previews[shape] = preview;
      tile.addView(preview, new LinearLayout.LayoutParams(-1, dp(47)));
      TextView name = text(SproutView.SHAPES[shape], 11, INK, false);
      name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      tile.addView(name);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
      params.setMarginEnd(index < previews.length - 1 ? dp(5) : 0);
      shapes.addView(tile, params);
      tile.setSelected(shape == current);
      tile.setOnClickListener(v -> {
        if (shape == SproutView.shapeIndex(this)) return;
        LockPanelKeepAlive.preferences(this).edit().putInt(SproutView.SHAPE_KEY, shape).apply();
        for (int item = 0; item < shapes.getChildCount(); item++) {
          shapes.getChildAt(item).setSelected(item == shape);
        }
        colors.check(colors.getChildAt(SproutView.colorIndex(this, shape)).getId());
      });
    }
    colors.check(colors.getChildAt(SproutView.colorIndex(this, current)).getId());
    colors.setOnCheckedChangeListener((group, checkedId) -> {
      int shape = SproutView.shapeIndex(this);
      for (int index = 0; index < group.getChildCount(); index++) {
        if (group.getChildAt(index).getId() != checkedId) continue;
        if (index != SproutView.colorIndex(this, shape)) {
          LockPanelKeepAlive.preferences(this).edit().putInt(SproutView.colorKey(shape), index).apply();
          previews[shape].updateAppearance();
        }
        break;
      }
    });
  }

  private void showWakeCardSetting(LinearLayout settings) {
    Switch background = new Switch(this);
    background.setText("后台亮屏卡片");
    background.setTextColor(INK);
    background.setTextSize(16);
    background.setMinHeight(dp(48));
    background.setChecked(LockPanelKeepAlive.enabled(this));
    background.setOnCheckedChangeListener((view, enabled) -> {
      LockPanelKeepAlive.setEnabled(this, enabled);
      if (enabled && !PrivacyCenter.accessibilityAccepted(this)) openAccessibilitySettings();
      updateWakeCardStatus();
    });
    settings.addView(background);
    wakeCardStatus = text("", 12, MUTED, false);
    wakeCardStatus.setMinHeight(dp(32));
    wakeCardStatus.setOnClickListener(v -> openAccessibilitySettings());
    settings.addView(wakeCardStatus);
    updateWakeCardStatus();
  }

  private void openAccessibilitySettings() {
    PrivacyCenter.requestAccessibility(this, () -> {
      try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
      catch (ActivityNotFoundException e) { toast("请在手机设置中搜索「无障碍」，找到有谱服务。"); }
    });
  }

  private void updateWakeCardStatus() {
    if (wakeCardStatus == null) return;
    boolean backgroundEnabled = LockPanelKeepAlive.enabled(this);
    boolean connected = LockPanelService.connected();
    boolean enabled = connected || LockPanelService.enabledInSettings(this);
    boolean needsPermission = backgroundEnabled && !enabled;
    String status = !backgroundEnabled ? "后台亮屏卡片已关闭"
        : connected ? "已连接 · 按电源键可预览"
        : enabled ? "已授权 · 等待系统连接"
        : "点击开启系统无障碍授权 ›";
    wakeCardStatus.setClickable(needsPermission);
    wakeCardStatus.setFocusable(needsPermission);
    wakeCardStatus.setTextColor(needsPermission ? BLUE : MUTED);
    if (!android.text.TextUtils.equals(wakeCardStatus.getText(), status)) wakeCardStatus.setText(status);
  }

  private static final String[] INLINE_REPEATS = {"不重复", "每天", "工作日", "每周", "每月"};

  private LinearLayout inlineEditor(LinearLayout planCard) {
    LinearLayout editor = (LinearLayout) planCard.getChildAt(planCard.getChildCount() - 1);
    editor.removeAllViews();
    return editor;
  }

  private void styleInlineEditor(LinearLayout editor) {
    boolean shown = editor.getChildCount() > 0;
    GradientDrawable frame = shown ? bg(0xfff7fafb, 10) : null;
    if (frame != null) frame.setStroke(dp(1), 0xffa9c3d1);
    editor.setBackground(frame);
    int inset = shown ? dp(10) : 0;
    editor.setPadding(inset, inset, inset, inset);
  }

  private EditText inlineInput(LinearLayout editor, String hint, String initial) {
    EditText input = new EditText(this);
    input.setTextSize(15);
    input.setSingleLine(true);
    input.setHint(hint);
    input.setText(initial);
    input.setTextColor(INK);
    input.setHintTextColor(MUTED);
    input.setMinHeight(dp(44));
    input.setPadding(dp(10), dp(8), dp(10), dp(8));
    GradientDrawable outline = bg(Color.WHITE, 8);
    outline.setStroke(dp(1), 0xff90aab8);
    input.setBackground(outline);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = dp(4);
    params.bottomMargin = dp(6);
    editor.addView(input, params);
    return input;
  }

  private Spinner inlineChoice(String[] items, int selected) {
    Spinner choice = new Spinner(this, Spinner.MODE_DROPDOWN);
    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    choice.setAdapter(adapter);
    choice.setSelection(Math.max(0, selected));
    choice.setMinimumHeight(dp(44));
    return choice;
  }

  private void openInlineNewCard(LinearLayout host) {
    if (!ActivationActivity.requireWrite(this)) return;
    GradientDrawable frame = bg(Color.WHITE, 14);
    frame.setStroke(dp(1), 0xffdce5e3);
    host.setBackground(frame);
    host.setPadding(dp(12), dp(8), dp(12), dp(8));
    LinearLayout.LayoutParams hostPosition = (LinearLayout.LayoutParams)host.getLayoutParams();
    hostPosition.bottomMargin = dp(12);host.setLayoutParams(hostPosition);
    LinearLayout heading = row();
    heading.addView(text("新建计划卡片",17,INK,true),new LinearLayout.LayoutParams(0,-2,1));
    TextView close = plainAction("×", () -> closeNewCard(host));
    close.setTextSize(22);close.setGravity(Gravity.CENTER);close.setContentDescription("收起新建卡片");
    heading.addView(close,new LinearLayout.LayoutParams(dp(40),dp(40)));host.addView(heading);
    EditText name = inlineInput(host, "卡片标题", "");
    Spinner type = inlineChoice(new String[]{"普通计划", "定时提醒"}, 0);
    host.addView(type);
    EditText item = inlineInput(host, "首条内容（可留空）", "");
    InlineReminder reminder = new InlineReminder(host, "", "不重复");
    reminder.fields.setVisibility(View.GONE);
    type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        reminder.fields.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
      }
      @Override public void onNothingSelected(AdapterView<?> parent) {}
    });
    actions(host, new String[]{"保存卡片", "取消"}, new Runnable[]{() -> {
      String title = val(name);
      if (title.isEmpty()) {
        name.setError("请输入卡片标题");
        return;
      }
      boolean timed = type.getSelectedItemPosition() == 1;
      String first = val(item);
      db.createInlinePlanCard(title, timed, first, timed ? reminder.time() : "",
          timed ? reminder.repeat() : "不重复");
      if (timed && !first.isEmpty()) requestInlineReminderPermission();
      finishInlinePlan();
    }, () -> {
      closeNewCard(host);
    }});
    for (EditText field : new EditText[]{name,item}) {
      field.setMinimumHeight(dp(40));field.setMinHeight(dp(40));
      field.setPadding(dp(10),dp(6),dp(10),dp(6));
    }
    type.setMinimumHeight(dp(40));
    LinearLayout buttons = (LinearLayout)host.getChildAt(host.getChildCount()-1);
    for (int i=0;i<buttons.getChildCount();i++) {
      TextView button = (TextView)buttons.getChildAt(i);
      button.setMinHeight(dp(40));button.setMinimumHeight(dp(40));
      button.setPadding(dp(8),0,dp(8),0);
    }
  }

  private void closeNewCard(LinearLayout host) {
    host.removeAllViews();host.setBackground(null);host.setPadding(0,0,0,0);
    LinearLayout.LayoutParams position=(LinearLayout.LayoutParams)host.getLayoutParams();
    position.bottomMargin=0;host.setLayoutParams(position);
  }

  private void bindHomeDoubleTap(View target, Runnable edit) { bindHomeDoubleTap(target,edit,null); }

  private void bindHomeDoubleTap(View target, Runnable edit, Runnable more) {
    if (more != null) target.setOnLongClickListener(v -> { more.run(); return true; });
    GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
      @Override public boolean onDown(MotionEvent event) { return true; }
      @Override public boolean onDoubleTap(MotionEvent event) { return target.performClick(); }
      @Override public void onLongPress(MotionEvent event) { if (more != null) target.performLongClick(); }
    });
    detector.setIsLongpressEnabled(more != null);
    // ACTION_CLICK remains available for accessibility services. Normal single taps do not edit.
    target.setOnClickListener(v -> edit.run());
    target.setOnTouchListener((v,event) -> { detector.onTouchEvent(event); return true; });
  }

  private void beginHomeTextEdit(TextView label, java.util.function.Consumer<String> save) {
    if (finishHomeTextEdit != null) finishHomeTextEdit.run();
    if (homeTextEditor != null || !(label.getParent() instanceof ViewGroup)) return;
    ViewGroup parent = (ViewGroup) label.getParent();
    int index = parent.indexOfChild(label);
    ViewGroup.LayoutParams position = label.getLayoutParams();
    String original = label.getText().toString();
    boolean[] closing = {false};
    EditText field = new EditText(this) {
      @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
          if (cancelHomeTextEdit != null) cancelHomeTextEdit.run();
          return true;
        }
        return super.onKeyPreIme(keyCode,event);
      }
    };
    field.setSingleLine(true);
    field.setText(original);
    field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,label.getTextSize());
    field.setTypeface(label.getTypeface());
    field.setTextColor(INK);
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(300)});
    field.setTypeface(label.getTypeface());
    field.setGravity(label.getGravity());
    field.setPadding(label.getPaddingLeft(),label.getPaddingTop(),label.getPaddingRight(),label.getPaddingBottom());
    int originalHeight = label.getHeight() > 0 ? label.getHeight() : dp(24);
    field.setMinHeight(originalHeight);
    field.setMinimumHeight(originalHeight);
    field.setBackgroundTintList(ColorStateList.valueOf(BLUE));
    field.setContentDescription("原位编辑文字，完成保存，返回取消");
    java.util.function.Consumer<Boolean> close = commit -> {
      if (closing[0] || homeTextEditor != field) return;
      String value = commit ? field.getText().toString().trim() : original;
      if (value.isEmpty()) value = original; // Never replace a saved title with an empty label.
      if (commit && !value.equals(original)) {
        try { save.accept(value); }
        catch (RuntimeException error) {
          field.setError(error.getMessage() == null ? "保存失败，请重试" : error.getMessage());
          return;
        }
      }
      closing[0] = true;
      homeTextEditor = null; finishHomeTextEdit = null; cancelHomeTextEdit = null;
      getSystemService(android.view.inputmethod.InputMethodManager.class)
          .hideSoftInputFromWindow(field.getWindowToken(),0);
      int at = parent.indexOfChild(field);
      if (at >= 0) {
        parent.removeViewAt(at);
        label.setText(value);
        parent.addView(label,at,position);
      }
      if (commit && !value.equals(original)) LockSync.refresh(this);
    };
    homeTextEditor = field;
    finishHomeTextEdit = () -> close.accept(true);
    cancelHomeTextEdit = () -> close.accept(false);
    field.setOnEditorActionListener((view, action, event) -> {
      boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
      if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE && !enter) return false;
      if (enter && event.getAction() != KeyEvent.ACTION_DOWN) return true;
      if (field.getText().toString().trim().isEmpty()) {
        field.setError("请输入内容"); return true;
      }
      close.accept(true); return true;
    });
    field.setOnFocusChangeListener((view,focused) -> {
      if (!focused && !closing[0] && homeTextEditor == field) close.accept(true);
    });
    parent.removeViewAt(index); parent.addView(field,index,position);
    field.requestFocus(); field.selectAll();
    field.post(() -> {
      if (homeTextEditor == field) getSystemService(android.view.inputmethod.InputMethodManager.class)
          .showSoftInput(field,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    });
  }

  private String currentPlanTitle(LockCards.Group group) {
    if (group.id == 0) return db.pref("lockPlanTitle",group.title);
    List<JSONObject> rows = db.rows("SELECT title FROM plan_card WHERE id=?",String.valueOf(group.id));
    return rows.isEmpty() ? group.title : rows.get(0).optString("title");
  }

  private void savePlanTitle(LockCards.Group group, EditText title) {
    if (!ActivationActivity.requireWrite(this)) return;
    String name = val(title);
    if (name.isEmpty()) {
      title.setError("请输入卡片标题");
      return;
    }
    if (group.id == 0) db.putPref("lockPlanTitle", name);
    else db.exec("UPDATE plan_card SET title=? WHERE id=?", name, group.id);
    finishInlinePlan();
  }

  private void managePlanInline(LinearLayout card, LockCards.Group group) {
    if (!ActivationActivity.requireWrite(this)) return;
    LinearLayout editor = inlineEditor(card);
    EditText title = inlineInput(editor, "卡片标题", currentPlanTitle(group));
    actions(editor, new String[]{"保存标题", "删除卡片", "取消"}, new Runnable[]{
        () -> savePlanTitle(group, title), () -> {
      editor.removeAllViews();
      note(editor, "确认删除这张卡片及其中的所有条目？");
      actions(editor, new String[]{"确认删除", "取消"}, new Runnable[]{() -> {
        db.deletePlanCard(group.id);
        finishInlinePlan();
      }, editor::removeAllViews});
    }, editor::removeAllViews});
    if (group.tasks.size() > 1) {
      editor.addView(plainAction("条目排序  ›", () -> openPlanItemOrder(card, group)));
    }
    for (JSONObject item : group.tasks)
      editor.addView(plainAction("设置：" + item.optString("title") + "  ›",
          () -> openInlinePlanItem(card,group,item)));
  }

  private void openPlanItemOrder(LinearLayout card, LockCards.Group group) {
    if (!ActivationActivity.requireWrite(this)) return;
    LinearLayout editor = inlineEditor(card);
    note(editor, "长按右侧拖柄，上下拖动。");
    List<JSONObject> draft = new ArrayList<>(group.tasks);
    LinearLayout list = column();
    editor.addView(list);
    renderPlanItemOrder(list, draft);
    actions(editor, new String[]{"保存顺序", "取消"}, new Runnable[]{() -> {
      db.savePlanItemOrder(group.id, draft);
      finishInlinePlan();
    }, editor::removeAllViews});
    editor.post(() -> editor.requestRectangleOnScreen(
        new android.graphics.Rect(0, 0, editor.getWidth(), Math.min(editor.getHeight(), dp(200))), true));
  }

  private void renderPlanItemOrder(LinearLayout list, List<JSONObject> draft) {
    list.removeAllViews();
    for (JSONObject item : draft) {
      LinearLayout entry = row();
      TextView title = text(item.optString("title"), 14, INK, false);
      entry.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
      TextView handle = text("☰", 17, BLUE, false);
      handle.setGravity(Gravity.CENTER);
      handle.setContentDescription("长按拖动 " + item.optString("title"));
      entry.addView(handle, new LinearLayout.LayoutParams(dp(48), dp(44)));
      handle.setOnLongClickListener(view -> view.startDragAndDrop(
          ClipData.newPlainText("计划条目", ""), new View.DragShadowBuilder(entry), item, 0));
      entry.setOnDragListener((view, event) -> {
        Object source = event.getLocalState();
        if (!(source instanceof JSONObject) || source == item || !draft.contains(source)) return false;
        switch (event.getAction()) {
          case DragEvent.ACTION_DRAG_LOCATION:
            int[] bounds = new int[2], rowPosition = new int[2];
            scroll.getLocationOnScreen(bounds);
            entry.getLocationOnScreen(rowPosition);
            float y = rowPosition[1] + event.getY() - bounds[1];
            if (y < dp(32)) scroll.scrollBy(0, -dp(12));
            else if (y > scroll.getHeight() - dp(32)) scroll.scrollBy(0, dp(12));
            break;
          case DragEvent.ACTION_DRAG_ENTERED:
            entry.setBackgroundColor(0xffedf5fa);
            break;
          case DragEvent.ACTION_DRAG_EXITED:
          case DragEvent.ACTION_DRAG_ENDED:
            entry.setBackgroundColor(Color.TRANSPARENT);
            break;
          case DragEvent.ACTION_DROP:
            draft.remove(source);
            int target = draft.indexOf(item);
            if (event.getY() >= entry.getHeight() / 2f) target++;
            draft.add(target, (JSONObject) source);
            list.post(() -> renderPlanItemOrder(list, draft));
            break;
          default:
            break;
        }
        return true;
      });
      list.addView(entry);
    }
  }

  private void openInlinePlanItem(LinearLayout card, LockCards.Group group, JSONObject task) {
    if (!ActivationActivity.requireWrite(this)) return;
    LinearLayout editor = inlineEditor(card);
    EditText title = inlineInput(editor, "条目内容", task == null ? "" : task.optString("title"));
    CheckBox timed = new CheckBox(this);
    timed.setText("定时提醒");
    String originalTime = task == null ? "" : task.optString("time");
    timed.setChecked(db.reminderCard(group.id) || !originalTime.isEmpty());
    editor.addView(timed);
    String oldRepeat = task == null ? "不重复" : task.optString("repeat");
    InlineReminder reminder = new InlineReminder(editor, originalTime, oldRepeat);
    reminder.fields.setVisibility(timed.isChecked() ? View.VISIBLE : View.GONE);
    timed.setOnCheckedChangeListener((view, enabled) ->
        reminder.fields.setVisibility(enabled ? View.VISIBLE : View.GONE));
    CheckBox show = new CheckBox(this);
    show.setText("显示在锁屏");
    boolean privateTask = task != null && task.optInt("private") == 1;
    show.setEnabled(!privateTask);
    show.setChecked(!privateTask && task != null && db.scalar(
        "SELECT on_lock FROM occurrence WHERE id=?", String.valueOf(task.optLong("oid"))) == 1);
    editor.addView(show);
    actions(editor, new String[]{task == null ? "添加条目" : "保存条目", "取消"},
        new Runnable[]{() -> {
          String name = val(title);
          if (name.isEmpty()) {
            title.setError("请输入条目内容");
            return;
          }
          String time = timed.isChecked() ? reminder.time() : "";
          String repeat = timed.isChecked() ? reminder.repeat() : oldRepeat;
          if (task == null) {
            db.addPlanTask(group.id == 0 ? null : group.id, name, "", time, repeat, false, show.isChecked());
          } else if (!db.editFloatingTask(task, name, task.optString("note"), time, repeat, show.isChecked())) {
            title.setError("这条事项已不存在");
            return;
          }
          finishInlinePlan();
          requestLockNotificationPermission(show.isChecked());
          if (timed.isChecked() && !show.isChecked()) requestInlineReminderPermission();
        }, editor::removeAllViews});
    editor.post(() -> editor.requestRectangleOnScreen(
        new android.graphics.Rect(0, 0, editor.getWidth(), Math.min(editor.getHeight(), dp(200))), true));
  }

  private void confirmPlanItemRemoval(LinearLayout card, LockCards.Group group, JSONObject task) {
    if (!ActivationActivity.requireWrite(this)) return;
    hideInlineKeyboard();
    LinearLayout editor = inlineEditor(card);
    note(editor, "删除「" + task.optString("title") + "」？");
    actions(editor, new String[]{"确认删除", "取消"}, new Runnable[]{() -> {
      if (group.id == 0) db.exec("UPDATE occurrence SET state='cancelled' WHERE id=?", task.optLong("oid"));
      else db.exec("DELETE FROM task WHERE id=? AND card=?", task.optLong("id"), group.id);
      finishInlinePlan();
    }, editor::removeAllViews});
    editor.post(() -> editor.requestRectangleOnScreen(
        new android.graphics.Rect(0, 0, editor.getWidth(), editor.getHeight()), true));
  }

  private void requestInlineReminderPermission() {
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
        != android.content.pm.PackageManager.PERMISSION_GRANTED
        && !"true".equals(db.pref("inlineReminderNoticeAsked", "false"))) {
      db.putPref("inlineReminderNoticeAsked", "true");
      notificationPermission();
    }
  }

  private void hideInlineKeyboard() {
    View focused = getCurrentFocus();
    if (focused != null) getSystemService(android.view.inputmethod.InputMethodManager.class)
        .hideSoftInputFromWindow(focused.getWindowToken(), 0);
  }

  private void finishInlinePlan() {
    hideInlineKeyboard();
    changed();
  }

  private final class InlineReminder {
    final LinearLayout fields = column();
    final Spinner recurrence;
    LocalTime selectedTime;

    InlineReminder(LinearLayout parent, String time, String repeat) {
      selectedTime = time.isEmpty() ? LocalTime.now().plusHours(1) : LocalTime.parse(time);
      recurrence = inlineChoice(INLINE_REPEATS, index(INLINE_REPEATS, repeat));
      recurrence.setContentDescription("提醒重复频率");
      TextView timeButton = button(time() + "  ›", () -> {}, false);
      timeButton.setContentDescription("提醒时间 " + time());
      timeButton.setOnClickListener(v -> {
        hideInlineKeyboard();
        showClockPicker("提醒时间", selectedTime, true, chosen -> {
          selectedTime = chosen;
          timeButton.setText(time() + "  ›");
          timeButton.setContentDescription("提醒时间 " + time());
        });
      });
      LinearLayout controls = row();
      LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(0, dp(44), 1);
      timeParams.rightMargin = dp(8);
      controls.addView(timeButton, timeParams);
      controls.addView(recurrence, new LinearLayout.LayoutParams(0, dp(44), 1));
      fields.addView(controls);
      parent.addView(fields);
    }

    String time() {
      return formatClock(selectedTime);
    }

    String repeat() {
      return recurrence.getSelectedItem().toString();
    }
  }

  private String formatClock(LocalTime time) {
    return String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute());
  }

  private void showClockPicker(String title, LocalTime current, boolean compact,
      java.util.function.Consumer<LocalTime> onChosen) {
    LinearLayout wheels = row();
    NumberPicker hours = clockWheel(23, current.getHour());
    NumberPicker minutes = clockWheel(59, current.getMinute());
    hours.setContentDescription("小时");
    minutes.setContentDescription("分钟");
    int height = compact ? dp(112) : -2;
    wheels.addView(hours, new LinearLayout.LayoutParams(0, height, 1));
    wheels.addView(minutes, new LinearLayout.LayoutParams(0, height, 1));
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    if (compact) {
      LinearLayout body = column();
      body.setPadding(dp(14), dp(12), dp(14), 0);
      body.addView(text("选择" + title, 15, INK, true),
          new LinearLayout.LayoutParams(-1, dp(30)));
      body.addView(wheels);
      builder.setView(body);
    } else {
      builder.setTitle("选择" + title).setView(wheels);
    }
    AlertDialog picker = builder.setNegativeButton("取消", null)
        .setPositiveButton("确定", (dialog, which) ->
            onChosen.accept(LocalTime.of(hours.getValue(), minutes.getValue())))
        .show();
    Window window = picker.getWindow();
    if (window != null) {
      window.setGravity(compact ? Gravity.CENTER : Gravity.BOTTOM);
      window.setBackgroundDrawable(bg(BG, compact ? 14 : 18));
      int width = Math.min(dp(264), Math.round(getResources().getDisplayMetrics().widthPixels * .70f));
      window.setLayout(compact ? width : -1, -2);
    }
  }

  private NumberPicker clockWheel(int max, int value) {
    NumberPicker picker = new NumberPicker(this);
    picker.setMinValue(0);
    picker.setMaxValue(max);
    picker.setValue(value);
    picker.setFormatter(n -> String.format(Locale.ROOT, "%02d", n));
    picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    return picker;
  }

  private void cardItemActions(JSONObject task) {
    if (!ActivationActivity.requireWrite(this)) return;
    choose(task.optString("title"), new String[] {"编辑条目", "删除条目"}, choice -> {
      long id = task.optLong("id");
      if (choice == 0) {
        Form form = new Form("编辑条目");
        EditText title = form.input("条目内容", task.optString("title"), false);
        form.show("保存", () -> {
          db.exec("UPDATE task SET title=? WHERE id=? AND card IS NOT NULL", required(title), id);
          changed();
          return true;
        });
      } else confirm("删除条目", "将从卡片和锁屏移除此条目。", () ->
          db.exec("DELETE FROM task WHERE id=? AND card IS NOT NULL", id));
    });
  }

  private void onLockSelectionChanged(boolean enabled) {
    // The checkbox already updated itself. Refresh the lock-screen data without rebuilding Home.
    LockSync.refresh(this);
    requestLockNotificationPermission(enabled);
  }

  private void requestLockNotificationPermission(boolean enabled) {
    if (enabled && Build.VERSION.SDK_INT >= 33 && !NextWidget.lockHostBound(this)
        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        && !"true".equals(db.pref("lockNoticeAsked", "false"))) {
      db.putPref("lockNoticeAsked", "true");
      notificationPermission();
    }
  }

  void nextCard() {
    JSONObject t = db.next();
    LinearLayout c =
        card(
            "◷  下一件事",
            t == null ? "轻一点，也可以" : "预计 " + t.optInt("minutes") + " 分钟",
            () -> go("tasks"));
    gap(c, 4);
    c.addView(text(t == null ? "从一件小事开始" : t.optString("title"), 20, INK, true));
    note(
        c,
        t == null
            ? "把想做的事放进来，让今天有个方向。"
            : t.optString("state").equals("doing")
                ? "正在执行 · 专注当下"
                : t.optString("time").isEmpty() ? "今天，留一点时间给它。" : "计划时间 " + t.optString("time"));
    if (t == null) c.addView(button("＋ 添加第一件事", () -> taskEdit(null, null), true));
    else
      actions(
          c,
          new String[] {"▶ 开始执行", "稍后"},
          new Runnable[] {() -> startFocus(t), () -> postpone(t)});
  }

  void payCard() {
    LinearLayout c = card("◷  收入时钟", "查看排班 ›", () -> go("pay"));
    LinearLayout r = row();
    earnView = text("¥0.00", 25, GREEN, true);
    r.addView(earnView, new LinearLayout.LayoutParams(0, -2, 1));
    rateView = text("", 11, GREEN, false);
    r.addView(rateView);
    c.addView(r);
    updateClock();
  }

  void spendCard() {
    LinearLayout c = card("▱  今日消费", "快捷记账 ＋", () -> ledgerEdit(null));
    TextView amount =
        text(
            "¥" + Engine.money(db.spending(LocalDate.now(), LocalDate.now().plusDays(1))),
            24,
            RED,
            true);
    c.addView(amount);
    amount.setOnClickListener(v -> go("ledger"));
  }

  void taskCard() {
    List<JSONObject> ts = db.tasks(Store.today());
    long n = ts.stream().filter(x -> x.optString("state").equals("done")).count();
    LinearLayout c = card("☑  今日任务", n + " / " + ts.size() + "  ›", () -> go("tasks"));
    int limit = db.pref("size_tasks", "标准").equals("紧凑") ? 3 : 5;
    for (int i = 0; i < Math.min(ts.size(), limit); i++) taskRow(c, ts.get(i));
    if (ts.isEmpty()) note(c, "今天还没有安排，点击右下角 ＋ 添加。");
    int learned = db.learned();
    note(c, "学习目标  " + learned + " / " + db.studyGoal() + " · 与学习空间同步");
  }

  void taskRow(LinearLayout c, JSONObject t) {
    taskRow(c, t, false);
  }

  void taskRow(LinearLayout c, JSONObject t, boolean lockControl) {
    taskRow(c, t, lockControl, null, null);
  }

  private LinearLayout taskRow(LinearLayout c, JSONObject t, boolean lockControl, Runnable editInline,
      Runnable remove) {
    LinearLayout r = row();
    CheckBox check = new CheckBox(this);
    boolean completed = "done".equals(t.optString("state"));
    check.setButtonDrawable(new CompletionMark(dp(23), INK, completed));
    check.setButtonTintList(null);
    check.setPadding(dp(7), 0, 0, 0);
    check.setContentDescription(completed ? "撤销完成" : "标为完成");
    check.setChecked(completed);
    r.addView(check, new LinearLayout.LayoutParams(dp(editInline != null ? 36 : 44), dp(44)));
    LinearLayout txt = column();
    TextView title = text(t.optString("title"), 14, INK, false);
    if (check.isChecked()) {
      title.setTextColor(MUTED);
      title.setPaintFlags(title.getPaintFlags() | 16);
    }
    txt.addView(title);
    String sub = t.optString("time");
    if (t.optInt("priority") > 0) sub += "  ·  " + (t.optInt("priority") == 2 ? "置顶" : "重要");
    if (t.optString("state").equals("doing")) sub += "  ·  执行中";
    if (!sub.isEmpty()) txt.addView(text(sub, 11, MUTED, false));
    r.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
    if (editInline != null) {
      txt.setContentDescription(t.optString("title") + "，双击原位编辑，长按更多设置");
      bindHomeDoubleTap(txt, () -> beginHomeTextEdit(title, value -> {
        if (!db.renameHomeTaskTitle(t.optLong("oid"),t.optLong("id"),value))
          throw new IllegalStateException("这条事项已不存在，请刷新后重试");
        try { t.put("title",value); } catch (JSONException error) { throw new IllegalStateException(error); }
      }), editInline);
    } else txt.setOnClickListener(v -> {
      if (t.isNull("card")) taskActions(t); else cardItemActions(t);
    });
    check.setOnCheckedChangeListener(
        (b, yes) -> {
          if (!ActivationActivity.requireWrite(this)) {
            b.setOnCheckedChangeListener(null);
            b.setChecked(completed);
            render();
            return;
          }
          if (yes) db.done(t.optLong("oid"));
          else
            db.exec(
                "UPDATE occurrence SET state='planned',completed=NULL WHERE id=?",
                t.optLong("oid"));
          changed();
        });
    if (lockControl) {
      boolean isPrivate = t.optInt("private") == 1;
      CheckBox show = lockCheckbox(t.optString("title"), !isPrivate && t.optInt("on_lock") == 1,
          enabled -> {
            db.showPlanOnLock(t.optLong("oid"), enabled);
            onLockSelectionChanged(enabled);
          });
      show.setEnabled(!isPrivate);
      if (isPrivate) show.setContentDescription("私密计划不公开显示");
      r.addView(show);
    }
    if (remove != null) {
      ImageView delete = new ImageView(this);
      delete.setImageResource(R.drawable.ic_trash_small);
      delete.setImageTintList(ColorStateList.valueOf(MUTED));
      delete.setScaleType(ImageView.ScaleType.CENTER);
      delete.setContentDescription("删除 " + t.optString("title"));
      delete.setOnClickListener(v -> remove.run());
      r.addView(delete, new LinearLayout.LayoutParams(dp(editInline != null ? 32 : 44), dp(44)));
    }
    c.addView(r);
    return r;
  }

  void bar(LinearLayout c, int value, int color) {
    ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    p.setMax(100);
    p.setProgress(value);
    p.setProgressTintList(ColorStateList.valueOf(color));
    p.setProgressBackgroundTintList(ColorStateList.valueOf(0xffedf1f8));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(6));
    lp.topMargin = dp(10);
    lp.bottomMargin = dp(4);
    c.addView(p, lp);
  }

  void studyCard() {
    LinearLayout c =
        card("▣  学习进度", db.learned() + " / " + db.studyGoal() + "  ›", () -> go("study"));
    note(c, "每日新词 · 已复习 " + db.reviewed() + " 项");
    bar(c, Math.min(100, db.learned() * 100 / db.studyGoal()), BLUE);
  }

  void planCard() {
    LinearLayout c = card("⚑  长期计划", "全部 ›", () -> go("plans"));
    List<JSONObject> ps = db.rows("SELECT * FROM plan WHERE parent IS NULL LIMIT 2");
    for (JSONObject p : ps) {
      int pr = db.planProgress(p.optLong("id"));
      c.addView(text(p.optString("title") + "   " + pr + "%", 14, INK, false));
      bar(c, pr, BLUE);
    }
    if (ps.isEmpty()) note(c, "留一个位置，给正在靠近的目标。");
  }

  void statsCard() {
    LinearLayout c = card("▤  收支概览", "本月 ›", () -> go("ledger"));
    LocalDate d = LocalDate.now();
    note(
        c,
        "本周消费 ¥"
            + Engine.money(
                db.spending(
                    d.with(java.time.DayOfWeek.MONDAY),
                    d.with(java.time.DayOfWeek.MONDAY).plusDays(7))));
    note(
        c,
        "本月消费 ¥"
            + Engine.money(db.spending(d.withDayOfMonth(1), d.withDayOfMonth(1).plusMonths(1))));
  }

  void readingCard() {
    LinearLayout c = card("▣  阅读与知识", "继续 ›", () -> go("reading"));
    List<JSONObject> rs = db.rows("SELECT * FROM reading LIMIT 1");
    note(
        c,
        rs.isEmpty()
            ? "读几页，记住一个新想法。"
            : rs.get(0).optString("title")
                + "  "
                + rs.get(0).optInt("current")
                + " / "
                + rs.get(0).optInt("total")
                + " 页");
  }

  void focusCard() {
    LinearLayout c = card("◴  专注计时", "开始 ›", () -> go("focus"));
    note(c, "跟随真实时间计时，暂停后保留进度。");
  }

  void updateClock() {
    if (earnView != null) {
      LocalDateTime now = LocalDateTime.now();
      LocalDate d = now.toLocalDate(), employmentStart=db.payStartDate();
      Engine.Rule r = Engine.ruleFor(db.rules(), d);
      earnView.setText("¥" + Engine.money(db.earnedOnDate(d, now)));
      if (rateView != null) {
        boolean configured = db.scalar("SELECT COUNT(*) FROM payrule") > 0;
        boolean started = !d.isBefore(employmentStart);
        boolean active = configured && started && Engine.active(r, d, now);
        BigDecimal currentRate = started ? Engine.currentRate(r, d, now) : BigDecimal.ZERO;
        String status = !configured ? "待设置" : !started ? "尚未开始计薪"
            : active ? "工作中" : !Engine.ruleApplies(r,d) ? "规则待生效" : "休息中";
        if (!active && configured && started && !d.minusDays(1).isBefore(employmentStart)) {
          Engine.Rule previous = Engine.ruleFor(db.rules(), d.minusDays(1));
          if (Engine.active(previous, d.minusDays(1), now)) {
            status = "昨日夜班中";
            currentRate = Engine.currentRate(previous, d.minusDays(1), now);
          }
        }
        if (configured && started && "休息中".equals(status)
            && Engine.ruleApplies(r,d) && Engine.working(r,d)
            && now.isBefore(Engine.time(d,r.start))) status="未到上班时间";
        rateView.setText(
            status
                + "\n+¥"
                + currentRate.setScale(4, RoundingMode.HALF_UP).toPlainString()
                + " / 秒");
      }
    }
    if (completedIncomeView != null) {
      LocalDateTime now=LocalDateTime.now();
      // Recalculate at minute boundaries (also catches shift-end minute), not on every second tick.
      LocalDateTime minute=now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
      if (!minute.equals(completedIncomeRefresh)) {
        completedIncomeView.setText("¥"+Engine.money(db.completedIncome(now)));
        completedIncomeRefresh=minute;
      }
    }
    if (focusView != null) {
      long elapsed =
          focusBase
              + (focusStart == 0
                  ? 0
                  : Math.max(0, (System.currentTimeMillis() - focusStart) / 1000));
      focusView.setText(String.format(Locale.CHINA, "%02d:%02d", elapsed / 60, elapsed % 60));
    }
  }

  void quickAdd() {
    choose(
        "添加到工作台",
        new String[] {"今日任务", "长期计划", "记一笔", "单词 / 知识卡", "阅读目标"},
        i -> {
          switch (i) {
            case 0:
              taskEdit(null, null);
              break;
            case 1:
              planEdit(null, null);
              break;
            case 2:
              ledgerEdit(null);
              break;
            case 3:
              studyEdit();
              break;
            case 4:
              readingEdit();
              break;
          }
        });
  }

  interface Pick {
    void at(int i);
  }

  void choose(String title, String[] opts, Pick p) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(opts, (d, i) -> p.at(i))
        .setNegativeButton("取消", null)
        .show();
  }

  void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  void confirm(String title, String body, Runnable action) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(body)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "确定",
            (d, w) -> {
              if (!ActivationActivity.requireWrite(this)) return;
              action.run();
              changed();
            })
        .show();
  }

  void search() {
    Form f = new Form("搜索任务");
    EditText q = f.input("关键词", "", false);
    f.show(
        "搜索",
        () -> {
          taskDay = Store.today();
          String key = q.getText().toString().trim();
          List<JSONObject> all =
              db.rows(
                  "SELECT DISTINCT o.day FROM occurrence o JOIN task t ON t.id=o.task WHERE t.title"
                      + " LIKE ? ORDER BY o.day DESC LIMIT 30",
                  "%" + key + "%");
          go("tasks");
          for (JSONObject a : all) {
            if (a.optString("day").equals(taskDay)) continue;
            LinearLayout c = card(a.optString("day"), "", null);
            for (JSONObject t : db.tasks(a.optString("day")))
              if (t.optString("title").contains(key)) taskRow(c, t);
          }
          return true;
        });
  }

  void tasks() {
    LinearLayout tools = row();
    tools.addView(
        button(
            "‹",
            () -> {
              taskDay = LocalDate.parse(taskDay).minusDays(1).toString();
              render();
            },
            false));
    TextView date =
        button(
            taskDay,
            () ->
                pickDay(
                    taskDay,
                    d -> {
                      taskDay = d;
                      render();
                    }),
            false);
    tools.addView(date, new LinearLayout.LayoutParams(0, -2, 1));
    tools.addView(
        button(
            "›",
            () -> {
              taskDay = LocalDate.parse(taskDay).plusDays(1).toString();
              render();
            },
            false));
    page.addView(tools);
    gap(page, 12);
    List<JSONObject> ts = taskDay.equals(Store.today()) ? db.activePlanTasks() : db.tasks(taskDay);
    if (taskDay.equals(Store.today())) ts.removeIf(task -> !task.isNull("card"));
    LinearLayout c = card("任务清单", ts.size() + " 项", null);
    for (JSONObject t : ts) taskRow(c, t);
    if (ts.isEmpty()) note(c, "这一天还没有安排。");
    page.addView(button("＋ 新增任务", () -> taskEdit(null, null), true));
    if (taskDay.equals(Store.today())) {
      List<JSONObject> overdue =
          db.rows(
              "SELECT DISTINCT o.day FROM occurrence o JOIN task t ON t.id=o.task WHERE o.day<? AND"
                  + " o.state NOT IN('done','cancelled') AND t.cancelled=0 ORDER BY o.day DESC"
                  + " LIMIT 7",
              Store.today());
      for (JSONObject d : overdue) {
        LinearLayout oc = card("待处理 · " + d.optString("day"), "逾期未自动完成", null);
        for (JSONObject t : db.tasks(d.optString("day")))
          if (!t.optString("state").equals("done")) taskRow(oc, t);
      }
    }
  }

  void pickDay(String current, java.util.function.Consumer<String> use) {
    LocalDate d = LocalDate.parse(current);
    new DatePickerDialog(
            this,
            (v, y, m, day) -> use.accept(LocalDate.of(y, m + 1, day).toString()),
            d.getYear(),
            d.getMonthValue() - 1,
            d.getDayOfMonth())
        .show();
  }

  void taskActions(JSONObject t) {
    if (!ActivationActivity.requireWrite(this)) return;
    choose(
        t.optString("title"),
        new String[] {"开始执行", "编辑这一次", "改期 / 稍后", "置为下一件", "删除这一次", "停止以后重复"},
        i -> {
          switch (i) {
            case 0:
              startFocus(t);
              break;
            case 1:
              taskEdit(t, null);
              break;
            case 2:
              postpone(t);
              break;
            case 3:
              db.putPref("pin", t.optString("oid"));
              go("home");
              changed();
              break;
            case 4:
              confirm(
                  "删除任务",
                  "仅取消这一次，重复任务的其他日期不受影响。",
                  () ->
                      db.exec(
                          "UPDATE occurrence SET state='cancelled' WHERE id=?", t.optLong("oid")));
              break;
            case 5:
              confirm(
                  "停止重复",
                  "保留今天及历史记录，删除未来未完成实例。",
                  () -> {
                    db.exec(
                        "UPDATE task SET untilDay=? WHERE id=?", Store.today(), t.optLong("id"));
                    db.exec(
                        "DELETE FROM occurrence WHERE task=? AND day>? AND state!='done'",
                        t.optLong("id"),
                        Store.today());
                  });
              break;
          }
        });
  }

  void taskEdit(JSONObject t, Long planId) {
    if (!ActivationActivity.requireWrite(this)) return;
    Form f = new Form(t == null ? "安排一件事" : "编辑本次任务");
    EditText title = f.input("任务标题", t == null ? "" : t.optString("title"), false),
        note = f.input("说明", t == null ? "" : t.optString("note"), false),
        day = f.input("计划日期 YYYY-MM-DD", t == null ? taskDay : t.optString("occurday"), false),
        time = f.input("提醒时间 HH:mm（留空不提醒）", t == null ? "" : t.optString("time"), false),
        minutes = f.input("预计分钟", t == null ? "25" : t.optString("minutes"), true);
    Spinner priority =
        f.select("优先级", new String[] {"普通", "重要", "置顶"}, t == null ? 0 : t.optInt("priority"));
    String[] repeats = {"不重复", "每天", "工作日", "每周", "每月"};
    Spinner repeat =
        f.select("重复规则", repeats, index(repeats, t == null ? "不重复" : t.optString("repeat")));
    List<JSONObject> ps = db.rows("SELECT * FROM plan ORDER BY id");
    String[] names = new String[ps.size() + 1];
    names[0] = "不关联计划";
    int selected = 0;
    for (int i = 0; i < ps.size(); i++) {
      names[i + 1] = ps.get(i).optString("title");
      long pid = t != null ? t.optLong("plan") : (planId == null ? -1 : planId);
      if (ps.get(i).optLong("id") == pid) selected = i + 1;
    }
    Spinner plan = f.select("关联长期计划", names, selected);
    CheckBox priv = f.check("锁屏隐藏任务标题", t != null && t.optInt("private") == 1);
    if (t != null) f.note("保存仅影响本次。要建立新的重复规则，请新增任务；停止旧规则可在任务菜单操作。");
    f.note("提醒采用系统闹钟；省电、通知权限可能影响送达时间。");
    f.show(
        "保存任务",
        () -> {
          String name = required(title);
          LocalDate d = LocalDate.parse(val(day));
          String tm = val(time);
          if (!tm.isEmpty()) LocalTime.parse(tm);
          int mins = integer(minutes, 1, 1440);
          Long pid =
              plan.getSelectedItemPosition() == 0
                  ? null
                  : ps.get(plan.getSelectedItemPosition() - 1).optLong("id");
          String rep = repeats[repeat.getSelectedItemPosition()];
          if (t != null && !rep.equals(t.optString("repeat")))
            throw new IllegalArgumentException("编辑只影响本次；新的重复规则请用新增任务");
          if (t == null) {
            db.exec(
                "INSERT INTO task(title,note,day,time,priority,minutes,repeat,private,plan)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                name,
                val(note),
                d.toString(),
                tm,
                priority.getSelectedItemPosition(),
                mins,
                rep,
                priv.isChecked() ? 1 : 0,
                pid);
          } else if (d.toString().equals(t.optString("occurday"))) {
            JSONObject ov = new JSONObject();
            ov.put("title", name);
            ov.put("note", val(note));
            ov.put("time", tm);
            ov.put("priority", priority.getSelectedItemPosition());
            ov.put("minutes", mins);
            ov.put("private", priv.isChecked() ? 1 : 0);
            ov.put("plan", pid == null ? JSONObject.NULL : pid);
            db.exec(
                "UPDATE occurrence SET overrides=?,reminded=0 WHERE id=?",
                ov.toString(),
                t.optLong("oid"));
          } else {
            db.getWritableDatabase().beginTransaction();
            try {
              db.exec("UPDATE occurrence SET state='cancelled' WHERE id=?", t.optLong("oid"));
              db.exec(
                  "INSERT INTO task(title,note,day,time,priority,minutes,repeat,private,plan)"
                      + " VALUES(?,?,?,?,?,?,?,?,?)",
                  name,
                  val(note),
                  d.toString(),
                  tm,
                  priority.getSelectedItemPosition(),
                  mins,
                  "不重复",
                  priv.isChecked() ? 1 : 0,
                  pid);
              db.getWritableDatabase().setTransactionSuccessful();
            } finally {
              db.getWritableDatabase().endTransaction();
            }
          }
          changed();
          return true;
        });
  }

  void postpone(JSONObject t) {
    if (!ActivationActivity.requireWrite(this)) return;
    Form f = new Form("给它换个时间");
    EditText d = f.input("日期 YYYY-MM-DD", t.optString("occurday"), false),
        tm = f.input("时间 HH:mm（可留空）", t.optString("time"), false);
    f.show(
        "确认改期",
        () -> {
          LocalDate day = LocalDate.parse(val(d));
          if (!val(tm).isEmpty()) LocalTime.parse(val(tm));
          JSONObject ov = new JSONObject(t.optString("overrides", "{}"));
          ov.put("time", val(tm));
          if (day.toString().equals(t.optString("occurday"))) {
            db.exec(
                "UPDATE occurrence SET overrides=?,state='paused',reminded=0 WHERE id=?",
                ov.toString(),
                t.optLong("oid"));
          } else {
            db.getWritableDatabase().beginTransaction();
            try {
              db.exec("UPDATE occurrence SET state='cancelled' WHERE id=?", t.optLong("oid"));
              db.exec(
                  "INSERT INTO task(title,note,day,time,priority,minutes,private,plan)"
                      + " VALUES(?,?,?,?,?,?,?,?)",
                  t.optString("title"),
                  t.optString("note"),
                  day.toString(),
                  val(tm),
                  t.optInt("priority"),
                  t.optInt("minutes"),
                  t.optInt("private"),
                  t.isNull("plan") ? null : t.optLong("plan"));
              db.getWritableDatabase().setTransactionSuccessful();
            } finally {
              db.getWritableDatabase().endTransaction();
            }
          }
          db.putPref("pin", "-1");
          changed();
          return true;
        });
  }

  void plans(Long parent) {
    List<JSONObject> ps =
        parent == null
            ? db.rows("SELECT * FROM plan WHERE parent IS NULL")
            : db.rows("SELECT * FROM plan WHERE parent=?", parent.toString());
    for (JSONObject p : ps) {
      int n = db.planProgress(p.optLong("id"));
      LinearLayout c = card(p.optString("title"), p.optString("kind") + " ›", () -> planDetail(p));
      note(
          c,
          p.optLong("target") > 0
              ? p.optLong("amount") + " / " + p.optLong("target") + " " + p.optString("unit")
              : "按任务与下级计划的完成比例计算");
      bar(c, n, BLUE);
      note(c, "已完成 " + n + "%");
    }
    if (ps.isEmpty()) note(page, "把大目标拆成能在今天执行的小事。");
    page.addView(button("＋ 新建目标 / 项目", () -> planEdit(null, parent), true));
  }

  void planDetail(JSONObject p) {
    Form f = new Form(p.optString("title"));
    long id = p.optLong("id");
    f.note("进度 " + db.planProgress(id) + "% · " + p.optString("kind"));
    if (p.optLong("target") > 0) {
      f.note("累计 " + p.optLong("amount") + " / " + p.optLong("target") + " " + p.optString("unit"));
      f.body.addView(
          button(
              "更新数量进度",
              () -> {
                f.dialog.dismiss();
                planAmount(p);
              },
              true));
    }
    f.body.addView(
        button(
            "＋ 关联执行任务",
            () -> {
              f.dialog.dismiss();
              taskEdit(null, id);
            },
            false));
    gap(f.body, 8);
    f.body.addView(
        button(
            "＋ 添加子目标 / 里程碑",
            () -> {
              f.dialog.dismiss();
              planEdit(null, id);
            },
            false));
    for (JSONObject child : db.rows("SELECT * FROM plan WHERE parent=?", String.valueOf(id))) {
      gap(f.body, 8);
      f.body.addView(
          button(
              child.optString("title") + "  " + db.planProgress(child.optLong("id")) + "%",
              () -> {
                f.dialog.dismiss();
                planDetail(child);
              },
              false));
    }
    for (JSONObject t : db.tasks(Store.today())) if (t.optLong("plan") == id) taskRow(f.body, t);
    gap(f.body, 12);
    f.body.addView(
        button(
            "编辑计划",
            () -> {
              f.dialog.dismiss();
              planEdit(p, null);
            },
            false));
    gap(f.body, 8);
    f.body.addView(
        button(
            "删除计划",
            () -> {
              f.dialog.dismiss();
              confirm(
                  "删除计划",
                  "同时删除下级计划；关联任务将保留并解除关联。",
                  () -> db.exec("DELETE FROM plan WHERE id=?", id));
            },
            false));
    f.show("完成", () -> true);
  }

  void planEdit(JSONObject p, Long parent) {
    if (!ActivationActivity.requireWrite(this)) return;
    Form f = new Form(p == null ? "新建长期计划" : "编辑长期计划");
    EditText title = f.input("计划名称", p == null ? "" : p.optString("title"), false);
    String[] types = {"目标", "项目", "里程碑"};
    Spinner kind = f.select("类型", types, index(types, p == null ? "目标" : p.optString("kind")));
    EditText target = f.input("数量目标（0 = 按子任务计算）", p == null ? "0" : p.optString("target"), true),
        unit = f.input("单位，如 元 / 页 / 次", p == null ? "" : p.optString("unit"), false);
    f.show(
        "保存计划",
        () -> {
          String s = required(title);
          int n = integer(target, 0, 100000000);
          if (p == null)
            db.exec(
                "INSERT INTO plan(title,parent,kind,target,unit) VALUES(?,?,?,?,?)",
                s,
                parent,
                types[kind.getSelectedItemPosition()],
                n,
                val(unit));
          else
            db.exec(
                "UPDATE plan SET title=?,kind=?,target=?,unit=? WHERE id=?",
                s,
                types[kind.getSelectedItemPosition()],
                n,
                val(unit),
                p.optLong("id"));
          changed();
          return true;
        });
  }

  void planAmount(JSONObject p) {
    if (!ActivationActivity.requireWrite(this)) return;
    Form f = new Form("更新累计数量");
    EditText a = f.input("累计完成数量", p.optString("amount"), true);
    f.show(
        "保存进度",
        () -> {
          int n = integer(a, 0, 100000000);
          db.exec("UPDATE plan SET amount=? WHERE id=?", n, p.optLong("id"));
          changed();
          return true;
        });
  }

  void study() {
    LinearLayout c = card("每天一点，记得更久", "", null);
    c.addView(text(db.learned() + " / " + db.studyGoal(), 32, BLUE, true));
    note(c, "今日新学 · 今日复习 " + db.reviewed() + " 项");
    bar(c, Math.min(100, db.learned() * 100 / db.studyGoal()), BLUE);
    long total = db.scalar("SELECT COUNT(*) FROM study"),
        mastered = db.scalar("SELECT COUNT(*) FROM study WHERE stage>=5"),
        due = db.scalar("SELECT COUNT(*) FROM study WHERE due<=?", Store.today());
    note(c, "词库与知识卡 " + total + " 项 · 待复习 " + due + " · 已掌握 " + mastered);
    c.addView(button("开始学习 / 复习", this::studySession, true));
    note(c, "优先复习，再学习新内容；答错次日再复习。连续多轮记住后才计为掌握。");
    LinearLayout tools = card("学习设置", "", null);
    actions(
        tools,
        new String[] {"每日目标", "新增内容"},
        new Runnable[] {this::studyGoalEdit, this::studyEdit});
    gap(tools, 10);
    tools.addView(button("导入 CSV / TSV 词库", () -> openDocument(73), false));
    note(tools, "每行：单词,释义；也支持制表符分隔。UTF-8，最多 5000 行。内置 40 个基础词，可自行扩充。");
    gap(tools, 8);
    tools.addView(button("阅读与知识卡片 ›", () -> go("reading"), false));
  }

  void studyGoalEdit() {
    if (!ActivationActivity.requireWrite(this)) return;
    Form f = new Form("每日新学目标");
    EditText n = f.input("每日条目数", String.valueOf(db.studyGoal()), true);
    f.show(
        "保存目标",
        () -> {
          db.putPref("studyGoal", String.valueOf(integer(n, 1, 500)));
          changed();
          return true;
        });
  }

  void studyEdit() {
    if (!ActivationActivity.requireWrite(this)) return;
    Form f = new Form("新增学习内容");
    Spinner kind = f.select("内容类型", new String[] {"单词", "知识卡"}, 0);
    EditText front = f.input("单词 / 问题", "", false), back = f.input("释义 / 答案", "", false);
    f.show(
        "添加",
        () -> {
          db.exec(
              "INSERT INTO study(front,back,kind) VALUES(?,?,?)",
              required(front),
              required(back),
              kind.getSelectedItem().toString());
          changed();
          return true;
        });
  }

  void studySession() {
    if (!ActivationActivity.requireWrite(this)) return;
    JSONObject item = db.studyNext();
    if (item == null) {
      toast("今天的学习已完成，或没有新内容。可以导入词库、调整目标。");
      changed();
      return;
    }
    Form f = new Form(item.optInt("stage") < 0 ? "学习新内容" : "到期复习");
    TextView word = text(item.optString("front"), 32, INK, true);
    word.setGravity(Gravity.CENTER);
    word.setPadding(0, dp(25), 0, dp(25));
    f.body.addView(word);
    TextView answer = text(item.optString("back"), 20, BLUE, false);
    answer.setGravity(Gravity.CENTER);
    answer.setVisibility(View.GONE);
    f.body.addView(answer);
    TextView reveal = button("查看释义 / 答案", () -> answer.setVisibility(View.VISIBLE), false);
    f.body.addView(reveal);
    f.note("看完答案后，诚实判断自己是否记住。");
    f.show("稍后继续", () -> true);
    reveal.setOnClickListener(
        v -> {
          answer.setVisibility(View.VISIBLE);
          reveal.setVisibility(View.GONE);
          actions(
              f.body,
              new String[] {"记住了", "还不熟"},
              new Runnable[] {() -> answerStudy(f, item, true), () -> answerStudy(f, item, false)});
        });
  }

  void answerStudy(Form f, JSONObject item, boolean yes) {
    if (!ActivationActivity.requireWrite(this)) return;
    db.answer(item, yes);
    f.dialog.dismiss();
    LockSync.refresh(this);
    render();
    studySession();
  }

  void reading() {
    LinearLayout c = card("阅读书架", "", null);
    for (JSONObject r : db.rows("SELECT * FROM reading")) {
      c.addView(text(r.optString("title"), 16, INK, true));
      note(c, r.optInt("current") + " / " + r.optInt("total") + " 页");
      bar(c, Math.min(100, r.optInt("current") * 100 / r.optInt("total")), BLUE);
      actions(
          c,
          new String[] {"更新页码", "删除"},
          new Runnable[] {
            () -> readingProgress(r),
            () ->
                confirm(
                    "删除阅读记录",
                    "仅删除此书的阅读进度。",
                    () -> db.exec("DELETE FROM reading WHERE id=?", r.optLong("id")))
          });
      line(c);
    }
    c.addView(button("＋ 添加一本书", this::readingEdit, true));
    LinearLayout k = card("知识卡片", "", null);
    for (JSONObject x : db.rows("SELECT * FROM study WHERE kind='知识卡' ORDER BY id DESC LIMIT 50")) {
      TextView v =
          button(
              x.optString("front"),
              () ->
                  new AlertDialog.Builder(this)
                      .setTitle(x.optString("front"))
                      .setMessage(x.optString("back"))
                      .setPositiveButton("知道了", null)
                      .setNeutralButton(
                          "删除",
                          (d, w) -> {
                            db.exec("DELETE FROM study WHERE id=?", x.optLong("id"));
                            changed();
                          })
                      .show(),
              false);
      k.addView(v);
      gap(k, 8);
    }
    k.addView(button("＋ 新建知识卡", this::studyEdit, true));
  }

  void readingEdit() {
    if (!ActivationActivity.requireWrite(this)) return;
    Form f = new Form("添加阅读目标");
    EditText title = f.input("书名", "", false), total = f.input("总页数", "200", true);
    f.show(
        "添加",
        () -> {
          db.exec(
              "INSERT INTO reading(title,total) VALUES(?,?)",
              required(title),
              integer(total, 1, 100000));
          changed();
          return true;
        });
  }

  void readingProgress(JSONObject r) {
    if (!ActivationActivity.requireWrite(this)) return;
    Form f = new Form("今天读到哪一页？");
    EditText current = f.input("当前页码", r.optString("current"), true);
    f.show(
        "更新进度",
        () -> {
          db.exec(
              "UPDATE reading SET current=? WHERE id=?",
              integer(current, 0, r.optInt("total")),
              r.optLong("id"));
          changed();
          return true;
        });
  }

  void pay() {
    note(page, "计薪金额根据你填写的规则估算，实际工资以劳动合同及单位结算为准。");
    LocalDate today = LocalDate.now();
    Engine.Rule rule = Engine.ruleFor(db.rules(), today);
    if (db.scalar("SELECT COUNT(*) FROM payrule")>0) {
      TextView startLabel=text(db.payStartDate()+" 起计薪",12,BLUE,false);
      startLabel.setPadding(dp(9),dp(4),dp(9),dp(4));startLabel.setBackground(bg(0xffe5eff6,12));
      page.addView(startLabel,new LinearLayout.LayoutParams(-2,-2));gap(page,10);
    }
    page.addView(text("今日估算", 14, MUTED, false));
    earnView = text("¥0.00", 42, BLUE, true);
    page.addView(earnView);
    rateView = text("", 13, MUTED, false);
    page.addView(rateView);
    gap(page,12);
    page.addView(text("累计收入（已结束班次）",13,MUTED,false));
    completedIncomeView=text("¥0.00",24,INK,true);page.addView(completedIncomeView);
    note(page,"不含正在进行的班次 · 估算收入不自动入账");
    updateClock();
    line(page);
    page.addView(text(rule.start + "–" + rule.end, 17, INK, true));
    if (!rule.breakStart.equals(rule.breakEnd))
      note(page, "午休 " + rule.breakStart + "–" + rule.breakEnd + (rule.paidBreak ? " · 计薪" : ""));
    page.addView(plainAction("薪资与排班  ›", () -> go("payEdit")));
    page.addView(plainAction("调整一天  ›", () -> payException(today)));
    line(page);

    page.addView(sectionTitle("本月排班"));
    LocalDate first = today.withDayOfMonth(1);
    LocalDateTime now = LocalDateTime.now();
    long sum = 0;
    for (int i = 0; i < today.getDayOfMonth(); i++) sum += db.earned(first.plusDays(i), now);
    note(page, "本月累计估算 ¥" + Engine.money(sum));
    LinearLayout days = row();
    for (String name : new String[] {"一", "二", "三", "四", "五", "六", "日"}) {
      TextView cell = text(name, 12, MUTED, true);
      cell.setGravity(Gravity.CENTER);
      days.addView(cell, new LinearLayout.LayoutParams(0, dp(36), 1));
    }
    page.addView(days);
    List<Engine.Rule> rules = db.rules();
    int leading = first.getDayOfWeek().getValue() - 1;
    for (int start = 0; start < leading + today.lengthOfMonth(); start += 7) {
      LinearLayout week = row();
      for (int column = 0; column < 7; column++) {
        int number = start + column - leading + 1;
        TextView cell = text("", 14, INK, false);
        cell.setGravity(Gravity.CENTER);
        if (number >= 1 && number <= today.lengthOfMonth()) {
          LocalDate day = first.withDayOfMonth(number);
          Engine.Rule dayRule = Engine.ruleFor(rules, day);
          String exception = dayRule.exceptions.get(day.toString());
          boolean working = !day.isBefore(db.payStartDate())
              && Engine.ruleApplies(dayRule,day) && Engine.working(dayRule, day);
          cell.setText(number + (exception == null ? "" : "·"));
          cell.setTextColor(day.equals(today) ? Color.WHITE : working ? INK : MUTED);
          if (day.equals(today)) cell.setBackground(bg(BLUE, 8));
          cell.setContentDescription(day.toString() + " " +
              (exception == null ? working ? "工作" : "休息" : exception));
          cell.setOnClickListener(v -> payException(day));
        }
        week.addView(cell, new LinearLayout.LayoutParams(0, dp(44), 1));
      }
      page.addView(week);
    }
    long yesterday = db.earned(today.minusDays(1), now);
    if (yesterday > 0) note(page, "昨日班次估算 ¥" + Engine.money(yesterday) + "（含跨夜）");
    note(page, "估算收入不会自动记入账本。");
  }

  private void confirmClearPaySettings() {
    if (!ActivationActivity.requireWrite(this)) return;
    Dialog dialog=new Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout body=column();body.setPadding(dp(22),dp(24),dp(22),dp(24));
    body.setBackground(bg(BG,20));
    TextView title=text("清除计薪设置？",21,INK,true);title.setGravity(Gravity.CENTER);
    body.addView(title);gap(body,18);
    body.addView(text("将清除薪资、排班和调休设置，收入估算归零。",14,INK,false));gap(body,14);
    TextView hint=text("待办事项和账本记录不受影响",13,INK,false);
    hint.setPadding(dp(12),dp(12),dp(12),dp(12));hint.setBackground(bg(0xffe8f0f5,10));body.addView(hint);
    gap(body,12);note(body,"清除后可重新设置开始计薪日期");gap(body,14);
    TextView confirm=button("确认清除",()->{
      db.clearPaySettings();dialog.dismiss();LockPanelService.refreshReminderCards();changed();
    },true);confirm.setBackground(bg(0xffbd4d4d,12));
    body.addView(confirm,new LinearLayout.LayoutParams(-1,-2));gap(body,10);
    body.addView(button("取消",dialog::dismiss,false),new LinearLayout.LayoutParams(-1,-2));
    dialog.setContentView(body);dialog.setCanceledOnTouchOutside(true);
    Window window=dialog.getWindow();
    if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);}
    dialog.show();
    if(window!=null){window.setGravity(Gravity.BOTTOM);window.setLayout(-1,-2);window.setDimAmount(0.35f);}
  }

  private void setAlternateWeekdays(TextView field,String csv) {
    String[] names={"周一","周二","周三","周四","周五","周六","周日"};
    List<String> chosen=Arrays.asList(csv.split(","));List<String> label=new ArrayList<>();
    for(int i=0;i<7;i++)if(chosen.contains(String.valueOf(i+1)))label.add(names[i]);
    field.setTag(csv);field.setText(String.join("、",label)+"  ›");
    field.setContentDescription(String.join("、",label));
  }

  private TextView alternateWeekdays(Form form,String label,String initial,Runnable edited) {
    TextView field=form.field(label,initial);field.setMaxWidth(dp(180));field.setTextSize(14);setAlternateWeekdays(field,initial);
    field.setOnClickListener(v->{
      String[] names={"周一","周二","周三","周四","周五","周六","周日"};
      List<String> selected=Arrays.asList(val(field).split(","));boolean[] checked=new boolean[7];
      for(int i=0;i<7;i++)checked[i]=selected.contains(String.valueOf(i+1));
      AlertDialog dialog=new AlertDialog.Builder(this).setTitle(label)
          .setMultiChoiceItems(names,checked,(d,i,yes)->checked[i]=yes)
          .setNegativeButton("取消",null).setPositiveButton("确定",null).create();
      dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
        List<String> days=new ArrayList<>();for(int i=0;i<7;i++)if(checked[i])days.add(String.valueOf(i+1));
        if(days.isEmpty()){toast("请至少选择一个工作日");return;}
        setAlternateWeekdays(field,String.join(",",days));edited.run();dialog.dismiss();
      }));dialog.show();
    });return field;
  }

  void payEdit() {
    if (!ActivationActivity.requireWrite(this)) return;
    Engine.Rule r = Engine.ruleFor(db.rules(), LocalDate.now());
    Form f = new Form("薪资与排班");
    String[] types = {"月薪", "日薪", "时薪"};
    String[] schedules = {"双休", "单休", "大小周", "做N休M", "自定义"};
    RadioGroup type = new RadioGroup(this);
    type.setOrientation(LinearLayout.HORIZONTAL);
    for (int i = 0; i < types.length; i++) {
      RadioButton choice = new RadioButton(this);
      choice.setId(View.generateViewId());
      choice.setText(types[i]);
      type.addView(choice, new RadioGroup.LayoutParams(0, dp(48), 1));
      if (i == index(types, r.type)) type.check(choice.getId());
    }
    f.body.addView(type);
    EditText amount = f.input("工资金额", r.amount.signum() == 0 ? "10000" : r.amount.toPlainString(), true);
    Spinner schedule = f.select("排班方式", schedules, index(schedules, r.schedule));
    Form alternating = new Form("大小周规则");
    alternating.body.setPadding(0,0,0,0);
    TextView weekAnchor = alternating.date("基准周日期",r.anchor);
    String alternatingA = r.alternateWeekA, alternatingB = r.alternateWeekB;
    int initial = alternatingA.equals("1,2,3,4,5,6") && alternatingB.equals("1,2,3,4,5") ? 0
        : alternatingA.equals("1,2,3,4,5") && alternatingB.equals("1,2,3,4,5,6") ? 1 : 2;
    Spinner reference = alternating.select("基准日期所在周",new String[]{"单休周", "双休周", "自定义两周"},initial);
    TextView weekA = alternateWeekdays(alternating,"基准周工作日",alternatingA,()->reference.setSelection(2));
    TextView weekB = alternateWeekdays(alternating,"下一周工作日",alternatingB,()->reference.setSelection(2));
    alternating.note("按周一至周日整周轮换。基准日期可选该周任一天；两周工作日均可自行勾选，调休仍可用“调整一天”。");
    whenSelected(reference,()->{
      int preset=reference.getSelectedItemPosition();
      if(preset==0){ setAlternateWeekdays(weekA,"1,2,3,4,5,6");setAlternateWeekdays(weekB,"1,2,3,4,5"); }
      else if(preset==1){ setAlternateWeekdays(weekA,"1,2,3,4,5");setAlternateWeekdays(weekB,"1,2,3,4,5,6"); }
    });
    f.body.addView(alternating.body);
    TextView start = f.time("上班时间", r.start), end = f.time("下班时间", r.end);
    TextView bs = f.time("午休开始", r.breakStart), be = f.time("午休结束", r.breakEnd);

    Form advanced = new Form("更多规则");
    advanced.body.setPadding(0, 0, 0, 0);
    CheckBox paid = advanced.check("午休计薪", r.paidBreak);
    Form scheduling=new Form("排班规则");scheduling.body.setPadding(0,0,0,0);
    TextView anchor = scheduling.date("轮班基准日期", r.anchor);
    EditText on = scheduling.input("上班天数", String.valueOf(r.onDays), true);
    EditText off = scheduling.input("休息天数", String.valueOf(r.offDays), true);
    TextView week = scheduling.field("工作日", r.weekdays);
    week.setMaxWidth(dp(190));week.setTextSize(14);
    String[] weekdays = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    Runnable renderWeek = () -> {
      List<String> selected = Arrays.asList(val(week).split(","));
      ArrayList<String> names = new ArrayList<>();
      for (int i = 0; i < weekdays.length; i++) if (selected.contains("" + (i + 1))) names.add(weekdays[i]);
      String selection = names.isEmpty() ? "请选择" : String.join("、", names);
      week.setText(selection + "  ›");
      week.setContentDescription("每周工作日 " + selection);
    };
    week.setTag(r.weekdays);
    renderWeek.run();
    week.setOnClickListener(v -> {
      List<String> selected = Arrays.asList(val(week).split(","));
      boolean[] checked = new boolean[7];
      for (int i = 0; i < checked.length; i++) checked[i] = selected.contains("" + (i + 1));
      new AlertDialog.Builder(this).setTitle("每周工作日")
          .setMultiChoiceItems(weekdays, checked, (d, i, yes) -> checked[i] = yes)
          .setNegativeButton("取消", null)
          .setPositiveButton("确定", (d, w) -> {
            ArrayList<String> values = new ArrayList<>();
            for (int i = 0; i < checked.length; i++) if (checked[i]) values.add("" + (i + 1));
            week.setTag(String.join(",", values));
            renderWeek.run();
          }).show();
    });
    f.body.addView(scheduling.body);
    Runnable scheduleFields = () -> {
      String selected = schedule.getSelectedItem().toString();
      alternating.body.setVisibility(selected.equals("大小周") ? View.VISIBLE : View.GONE);
      scheduling.showField(anchor, selected.equals("做N休M"));
      scheduling.showField(on, selected.equals("做N休M"));
      scheduling.showField(off, selected.equals("做N休M"));
      scheduling.showField(week, selected.equals("自定义"));
    };
    whenSelected(schedule, scheduleFields);

    CheckBox ot = advanced.check("计薪加班", r.ot);
    TextView os = advanced.time("加班开始", r.otStart), oe = advanced.time("加班结束", r.otEnd);
    EditText om = advanced.input("加班倍率", r.otMultiplier.toPlainString(), true);
    Runnable overtimeFields = () -> {
      advanced.showField(os, ot.isChecked());
      advanced.showField(oe, ot.isChecked());
      advanced.showField(om, ot.isChecked());
    };
    ot.setOnCheckedChangeListener((b, enabled) -> overtimeFields.run());
    overtimeFields.run();
    Form timing = new Form("计薪起点");timing.body.setPadding(0,0,0,0);
    timing.body.addView(text("计薪起点",17,INK,true));
    TextView employmentStart = timing.date("开始计薪日期",db.payStartDate().toString());
    timing.note("入职当天开始计算，之前不计薪。保存后按当前规则从所选日期重新估算，不会修改真实账本。");
    advanced.note("下班早于上班表示跨夜。午休开始与结束相同表示无午休。");
    advanced.body.setVisibility(View.GONE);
    TextView more = plainAction("更多规则  ›", () -> {});
    more.setOnClickListener(v -> {
      boolean expanded = advanced.body.getVisibility() != View.VISIBLE;
      advanced.body.setVisibility(expanded ? View.VISIBLE : View.GONE);
      more.setText(expanded ? "收起更多规则  ⌃" : "更多规则  ›");
    });
    f.body.addView(more);
    f.body.addView(advanced.body);
    gap(f.body,12);f.body.addView(timing.body);
    f.body.setPadding(0, 0, 0, 0);
    page.addView(f.body);
    gap(page, 16);
    page.addView(button("保存计薪规则", () -> f.runSave(() -> {
          long cents = Engine.cents(required(amount));
          if (cents < 0) throw new IllegalArgumentException("工资不能为负数");
          for (TextView v : new TextView[] {start, end, bs, be, os, oe})
            LocalTime.parse(val(v));
          LocalDate.parse(val(anchor));
          LocalDate.parse(val(weekAnchor));
          LocalDate firstDay = LocalDate.parse(val(employmentStart));
          BigDecimal multiplier = new BigDecimal(val(om));
          if (multiplier.signum() < 0 || multiplier.compareTo(new BigDecimal("10")) > 0)
            throw new IllegalArgumentException("加班倍率应为 0–10");
          boolean cycle = "做N休M".equals(schedule.getSelectedItem());
          int n = cycle ? integer(on, 1, 365) : r.onDays;
          int m = cycle ? integer(off, 1, 365) : r.offDays;
          boolean custom = "自定义".equals(schedule.getSelectedItem());
          String wd = custom ? val(week) : r.weekdays;
          if (custom && !wd.matches("[1-7](,[1-7]){0,6}"))
            throw new IllegalArgumentException("请至少选择一个工作日");
          JSONObject j = new JSONObject();
          j.put("type", ((RadioButton) type.findViewById(type.getCheckedRadioButtonId())).getText());
          j.put("amount", BigDecimal.valueOf(cents, 2).toPlainString());
          j.put("schedule", schedule.getSelectedItem());
          j.put("start", val(start));
          j.put("end", val(end));
          j.put("breakStart", val(bs));
          j.put("breakEnd", val(be));
          j.put("paidBreak", paid.isChecked());
          boolean alternate = "大小周".equals(schedule.getSelectedItem());
          j.put("anchor", alternate ? val(weekAnchor) : val(anchor));
          j.put("alternateConfigured", alternate || r.alternateConfigured);
          j.put("alternateWeekA",val(weekA));
          j.put("alternateWeekB",val(weekB));
          j.put("onDays", n);
          j.put("offDays", m);
          j.put("weekdays", wd);
          j.put("ot", ot.isChecked());
          j.put("otStart", val(os));
          j.put("otEnd", val(oe));
          j.put("otMultiplier", multiplier.toPlainString());
          JSONObject ex = new JSONObject();
          for (Map.Entry<String, String> e : r.exceptions.entrySet())
            ex.put(e.getKey(), e.getValue());
          j.put("exceptions", ex);
          db.savePayConfiguration(firstDay,j.toString());
          LockPanelService.refreshReminderCards();LockSync.refresh(this);
          go("pay");
          return true;
        }), true));
  }

  private void whenSelected(Spinner spinner, Runnable update) {
    spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        update.run();
      }
      @Override public void onNothingSelected(AdapterView<?> parent) {}
    });
    update.run();
  }

  void payException(LocalDate day) {
    if (!ActivationActivity.requireWrite(this)) return;
    Form f = new Form("调整一天");
    TextView date = f.date("日期", day.toString());
    Spinner kind = f.select("处理方式", new String[] {"工作", "休息", "请假", "部分请假", "恢复规则"}, 0);
    TextView leaveStart = f.time("请假开始", "09:00");
    TextView leaveEnd = f.time("请假结束", "12:00");
    whenSelected(kind, () -> {
      boolean partial = "部分请假".equals(kind.getSelectedItem());
      f.showField(leaveStart, partial);
      f.showField(leaveEnd, partial);
    });
    f.show(
        "保存调整",
        () -> {
          LocalDate d = LocalDate.parse(val(date));
          String mode = kind.getSelectedItem().toString();
          if (mode.equals("部分请假")) {
            if (val(leaveStart).equals(val(leaveEnd)))
              throw new IllegalArgumentException("请假开始和结束时间不能相同");
            mode = val(leaveStart) + "-" + val(leaveEnd);
          }
          Engine.Rule selected = Engine.ruleFor(db.rules(), d);
          List<JSONObject> all =
              db.rows("SELECT * FROM payrule WHERE effective=?", selected.effective);
          if (all.isEmpty()) throw new IllegalArgumentException("请先设置薪资与排班");
          JSONObject j = new JSONObject(all.get(0).optString("json")),
              ex = j.optJSONObject("exceptions");
          if (ex == null) ex = new JSONObject();
          if (mode.equals("恢复规则")) ex.remove(d.toString());
          else ex.put(d.toString(), mode);
          j.put("exceptions", ex);
          db.exec("UPDATE payrule SET json=? WHERE effective=?", j.toString(), selected.effective);
          changed();
          return true;
        });
  }

  String ledgerPeriod = "今日";

  void ledger() {
    LinearLayout periods = row();
    for (String p : new String[] {"今日", "本周", "本月"}) {
      TextView v =
          button(
              p,
              () -> {
                ledgerPeriod = p;
                render();
              },
              p.equals(ledgerPeriod));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
      lp.setMargins(dp(2), 0, dp(2), dp(12));
      periods.addView(v, lp);
    }
    page.addView(periods);
    LocalDate now = LocalDate.now(), from = now, to = now.plusDays(1);
    if (ledgerPeriod.equals("本周")) {
      from = now.with(DayOfWeek.MONDAY);
      to = from.plusDays(7);
    }
    if (ledgerPeriod.equals("本月")) {
      from = now.withDayOfMonth(1);
      to = from.plusMonths(1);
    }
    long spend = db.spending(from, to), income = db.received(from, to);
    LinearLayout totals = row();
    for (int i = 0; i < 2; i++) {
      LinearLayout metric = column();
      metric.addView(text(i == 0 ? "净支出" : "到账收入", 13, MUTED, false));
      metric.addView(text("¥" + Engine.money(i == 0 ? spend : income), 26, BLUE, true));
      totals.addView(metric, new LinearLayout.LayoutParams(0, -2, 1));
    }
    page.addView(totals);
    note(page, "真实结余 ¥" + Engine.money(income - spend));
    line(page);
    long budget = Long.parseLong(db.pref("budget", "0"));
    LinearLayout budgetRow = row();
    budgetRow.addView(text("月预算", 16, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
    budgetRow.addView(plainAction(budget > 0 ? "¥" + Engine.money(budget) + "  ›" : "设置  ›",
        this::budgetEdit));
    page.addView(budgetRow);
    if (budget > 0) {
      long month = db.spending(now.withDayOfMonth(1), now.withDayOfMonth(1).plusMonths(1));
      bar(page, (int) Math.min(100, Math.max(0, month * 100 / budget)), month > budget ? RED : BLUE);
      note(page, "已用 ¥" + Engine.money(month) + " · 剩余 ¥" + Engine.money(budget - month));
    }
    line(page);

    LinearLayout heading = row();
    heading.addView(sectionTitle("账本明细"), new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout categories = column();
    heading.addView(plainAction("分类支出  ›", () -> categories.setVisibility(
        categories.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)));
    page.addView(heading);
    categories.setVisibility(View.GONE);
    page.addView(categories);
    for (JSONObject cat :
        db.rows(
            "SELECT category,SUM(CASE direction WHEN '支出' THEN cents WHEN '退款' THEN -cents ELSE 0"
                + " END) AS total FROM ledger WHERE day>=? AND day<? AND direction IN('支出','退款')"
                + " GROUP BY category ORDER BY total DESC",
            from.toString(),
            to.toString())) {
      categories.addView(
          text(
              cat.optString("category") + "    ¥" + Engine.money(cat.optLong("total")),
              14,
              INK,
              false));
      bar(
          categories,
          spend <= 0 ? 0 : (int) Math.max(0, Math.min(100, cat.optLong("total") * 100 / spend)),
          BLUE);
    }
    LinearLayout list = column();
    page.addView(list);
    List<JSONObject> tx =
        db.rows(
            "SELECT * FROM ledger WHERE day>=? AND day<? ORDER BY day DESC,id DESC",
            from.toString(),
            to.toString());
    if (tx.isEmpty()) note(list, "还没有流水，记下第一笔吧。");
    for (JSONObject t : tx) {
      LinearLayout entry = row();
      LinearLayout details = column();
      String direction = t.optString("direction");
      String memo = t.optString("note");
      details.addView(text(memo.isEmpty() ? t.optString("category") : memo, 16, INK, true));
      String time = t.optString("time");
      details.addView(text(t.optString("day") + (time.isEmpty() ? "" : " " + time) + " · " + direction + " · "
          + t.optString("category"), 12, MUTED, false));
      entry.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
      entry.addView(text(("支出".equals(direction) ? "−" : "+")
          + "¥" + Engine.money(t.optLong("cents")), 16, INK, true));
      entry.setMinimumHeight(dp(52));
      entry.setOnClickListener(v -> ledgerEdit(t));
      list.addView(entry);
      line(list);
    }
    TextView add = button("＋ 记一笔", () -> ledgerEdit(null), true);
    FrameLayout.LayoutParams footer = new FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM);
    footer.setMargins(dp(20), 0, dp(20), dp(16));
    root.addView(add, footer);
    note(page, "估算工资不会被当成到账收入。退款在记录当天冲减同类消费，可出现负净支出。");
  }

  void ledgerEdit(JSONObject t) {
    if (!ActivationActivity.requireWrite(this)) return;
    Form f = new Form(t == null ? "快捷记一笔" : "编辑流水");
    String[] dirs = {"支出", "收入", "退款", "转账"},
        cats = Store.LEDGER_CATEGORIES;
    Spinner dir = f.select("方向", dirs, index(dirs, t == null ? "支出" : t.optString("direction")));
    EditText amount =
        f.input(
            "金额 ¥",
            t == null ? "" : BigDecimal.valueOf(t.optLong("cents"), 2).toPlainString(),
            true);
    Spinner cat = f.select("分类", cats, index(cats, t == null ? "餐饮" : t.optString("category")));
    TextView day = f.date("日期", t == null ? Store.today() : t.optString("day"));
    EditText note = f.input("备注", t == null ? "" : t.optString("note"), false);
    if (t != null) {
      f.body.addView(
          button(
              "删除这笔流水",
              () -> {
                f.dialog.dismiss();
                confirm(
                    "删除流水",
                    "删除后今日、周、月统计同时回滚。",
                    () -> db.exec("DELETE FROM ledger WHERE id=?", t.optLong("id")));
              },
              false));
    }
    f.show(
        "保存流水",
        () -> {
          long n = Engine.cents(required(amount));
          if (n <= 0) throw new IllegalArgumentException("金额必须大于 0");
          LocalDate.parse(val(day));
          if (t == null)
            db.exec(
                "INSERT INTO ledger(day,direction,cents,category,note) VALUES(?,?,?,?,?)",
                val(day),
                dir.getSelectedItem(),
                n,
                cat.getSelectedItem(),
                val(note));
          else
            db.exec(
                "UPDATE ledger SET day=?,direction=?,cents=?,category=?,note=? WHERE id=?",
                val(day),
                dir.getSelectedItem(),
                n,
                cat.getSelectedItem(),
                val(note),
                t.optLong("id"));
          changed();
          return true;
        });
  }

  void budgetEdit() {
    if (!ActivationActivity.requireWrite(this)) return;
    Form f = new Form("月消费预算");
    EditText amount =
        f.input(
            "预算 ¥（0 = 不启用）",
            BigDecimal.valueOf(Long.parseLong(db.pref("budget", "0")), 2).toPlainString(),
            true);
    f.show(
        "保存预算",
        () -> {
          long n = Engine.cents(required(amount));
          if (n < 0) throw new IllegalArgumentException("预算不能为负数");
          db.putPref("budget", String.valueOf(n));
          changed();
          return true;
        });
  }

  void cards() {
    note(page, "长按拖动卡片可排序；开关决定首页是否显示。轻触右侧尺寸可以调整信息密度。");
    for (String k : order()) {
      int index = Arrays.asList(cardKeys).indexOf(k);
      if (index < 0) continue;
      LinearLayout c = card(null, "", null);
      LinearLayout r = row();
      TextView drag = text("☰", 20, MUTED, false);
      drag.setContentDescription("拖动排序 " + cardTitles[index]);
      r.addView(drag, new LinearLayout.LayoutParams(dp(42), dp(48)));
      TextView title = text(cardTitles[index], 15, INK, true);
      r.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
      Switch sw = new Switch(this);
      sw.setChecked(db.pref("card_" + k, String.valueOf(index < 6)).equals("true"));
      sw.setContentDescription("显示" + cardTitles[index]);
      sw.setThumbTintList(ColorStateList.valueOf(BLUE));
      sw.setOnCheckedChangeListener((b, on) -> db.putPref("card_" + k, String.valueOf(on)));
      r.addView(sw);
      c.addView(r);
      String current = db.pref("size_" + k, "标准");
      actions(
          c,
          new String[] {"↑ 上移", "↓ 下移", current},
          new Runnable[] {
            () -> moveCard(k, -1),
            () -> moveCard(k, 1),
            () ->
                choose(
                    "卡片尺寸 / 密度",
                    new String[] {"紧凑", "标准", "宽松"},
                    i -> {
                      db.putPref("size_" + k, new String[] {"紧凑", "标准", "宽松"}[i]);
                      render();
                    })
          });
      View.OnLongClickListener start =
          v -> {
            v.startDragAndDrop(
                ClipData.newPlainText("card", k), new View.DragShadowBuilder(c), k, 0);
            return true;
          };
      drag.setOnLongClickListener(start);
      c.setOnLongClickListener(start);
      c.setOnDragListener(
          (v, event) -> {
            switch (event.getAction()) {
              case DragEvent.ACTION_DRAG_STARTED:
                return event.getLocalState() instanceof String;
              case DragEvent.ACTION_DRAG_ENTERED:
                c.setAlpha(.6f);
                return true;
              case DragEvent.ACTION_DRAG_EXITED:
                c.setAlpha(1);
                return true;
              case DragEvent.ACTION_DROP:
                String src = (String) event.getLocalState();
                List<String> list = order();
                int at = list.indexOf(k);
                list.remove(src);
                list.add(Math.min(at, list.size()), src);
                db.putPref("order", String.join(",", list));
                render();
                return true;
              case DragEvent.ACTION_DRAG_ENDED:
                c.setAlpha(1);
                return true;
              default:
                return true;
            }
          });
    }
    page.addView(
        button(
            "恢复默认布局",
            () ->
                confirm(
                    "恢复默认布局",
                    "恢复卡片默认顺序、显示状态和尺寸。",
                    () -> {
                      db.putPref("order", String.join(",", cardKeys));
                      for (int i = 0; i < cardKeys.length; i++) {
                        db.putPref("card_" + cardKeys[i], String.valueOf(i < 6));
                        db.putPref("size_" + cardKeys[i], "标准");
                      }
                    }),
            false));
  }

  void moveCard(String k, int offset) {
    if (!ActivationActivity.requireWrite(this)) return;
    List<String> a = order();
    int pos = a.indexOf(k), dest = pos + offset;
    if (dest < 0 || dest >= a.size()) return;
    Collections.swap(a, pos, dest);
    db.putPref("order", String.join(",", a));
    render();
  }

  void toggle(LinearLayout parent, String label, String key, boolean def, boolean sync) {
    LinearLayout r = row();
    TextView t = text(label, 14, INK, false);
    r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    Switch sw = new Switch(this);
    sw.setContentDescription(label);
    sw.setChecked(db.pref(key, String.valueOf(def)).equals("true"));
    r.setMinimumHeight(dp(52));
    sw.setOnCheckedChangeListener(
        (b, on) -> {
          db.putPref(key, String.valueOf(on));
          if (sync) LockSync.refresh(this);
          if (on && key.equals("lockEnabled") && Build.VERSION.SDK_INT >= 33
              && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                  != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission();
          }
        });
    r.addView(sw);
    parent.addView(r);
  }

  void notificationPermission() {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 9);
    } else
      startActivity(
          new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
              .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
  }

  @Override
  public void onRequestPermissionsResult(int r, String[] p, int[] grants) {
    super.onRequestPermissionsResult(r, p, grants);
    LockSync.refresh(this);
    render();
  }

  void startFocus(JSONObject t) {
    if (!ActivationActivity.requireWrite(this)) return;
    saveFocus();
    focusOid = t.optLong("oid");
    focusBase = db.scalar("SELECT focus FROM occurrence WHERE id=?", String.valueOf(focusOid));
    focusStart = System.currentTimeMillis();
    db.putPref("focusOid", String.valueOf(focusOid));
    db.putPref("focusStart", String.valueOf(focusStart));
    db.putPref("focusBase", String.valueOf(focusBase));
    db.exec("UPDATE occurrence SET state='paused' WHERE state='doing'");
    db.exec("UPDATE occurrence SET state='doing' WHERE id=?", focusOid);
    LockSync.refresh(this);
    go("focus");
  }

  void loadFocus() {
    if (focusOid < 0) {
      focusOid = Long.parseLong(db.pref("focusOid", "-1"));
      focusStart = Long.parseLong(db.pref("focusStart", "0"));
      focusBase = Long.parseLong(db.pref("focusBase", "0"));
    }
  }

  void saveFocus() {
    if (!ActivationActivity.canUse(this)) { focusStart = 0; return; }
    loadFocus();
    if (focusOid > 0) {
      long seconds =
          focusBase
              + (focusStart == 0
                  ? 0
                  : Math.max(0, (System.currentTimeMillis() - focusStart) / 1000));
      db.exec("UPDATE occurrence SET focus=? WHERE id=?", seconds, focusOid);
    }
  }

  void focus() {
    loadFocus();
    List<JSONObject> a =
        db.rows(
            "SELECT t.title,o.state FROM occurrence o JOIN task t ON t.id=o.task WHERE o.id=?",
            String.valueOf(focusOid));
    LinearLayout c = card("把注意力留给现在", "", null);
    c.addView(text(a.isEmpty() ? "选择一件事，开始专注" : a.get(0).optString("title"), 19, INK, true));
    focusView = text("00:00", 56, BLUE, true);
    focusView.setGravity(Gravity.CENTER);
    focusView.setPadding(0, dp(30), 0, dp(30));
    c.addView(focusView);
    updateClock();
    if (!a.isEmpty()) {
      actions(
          c,
          new String[] {focusStart > 0 ? "暂停" : "继续", "完成任务"},
          new Runnable[] {
            () -> {
              if (focusStart > 0) {
                saveFocus();
                focusBase =
                    db.scalar("SELECT focus FROM occurrence WHERE id=?", String.valueOf(focusOid));
                focusStart = 0;
                db.exec("UPDATE occurrence SET state='paused' WHERE id=?", focusOid);
              } else {
                focusStart = System.currentTimeMillis();
                db.exec("UPDATE occurrence SET state='doing' WHERE id=?", focusOid);
              }
              db.putPref("focusStart", String.valueOf(focusStart));
              db.putPref("focusBase", String.valueOf(focusBase));
              changed();
            },
            () -> {
              saveFocus();
              db.done(focusOid);
              focusOid = -1;
              focusStart = 0;
              focusBase = 0;
              db.putPref("focusOid", "-1");
              db.putPref("focusStart", "0");
              db.putPref("focusBase", "0");
              go("home");
              changed();
            }
          });
    } else c.addView(button("挑选今日任务", () -> go("tasks"), true));
    note(c, "按真实起止时间计算经过时长。切到后台会继续计时，暂停时保留记录。");
  }

  void openDocument(int request) {
    startActivityForResult(
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE),
        request);
  }

  @Override
  protected void onActivityResult(int req, int result, Intent data) {
    super.onActivityResult(req, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    if (req != 71 && !ActivationActivity.requireWrite(this)) return;
    Uri uri = data.getData();
    try {
      if (req == 71) {
        String json = db.backup().toString(2);
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
          if (out == null) throw new IOException("无法写入文件");
          out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        toast("备份已导出");
      } else {
        String raw;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
          if (input == null) throw new IOException("无法读取文件");
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          byte[] chunk = new byte[8192];
          int n;
          while ((n = input.read(chunk)) != -1) {
            if (bytes.size() + n > 10 * 1024 * 1024) throw new IOException("文件超过 10MB");
            bytes.write(chunk, 0, n);
          }
          raw = bytes.toString("UTF-8");
        }
        if (req == 72) {
          db.restore(new JSONObject(raw));
          focusOid = -1;
          focusStart = 0;
          focusBase = 0;
          toast("数据已恢复");
        } else if (req == 73) {
          int count = 0;
          db.getWritableDatabase().beginTransaction();
          try {
            String[] lines = raw.replace("\uFEFF", "").split("\\R");
            if (lines.length > 5000) throw new IOException("最多导入 5000 行");
            for (String line : lines) {
              if (line.trim().isEmpty()) continue;
              String[] vals = parseCsv(line);
              if (vals.length < 2) throw new IOException("每行需要单词与释义：" + line);
              String a = vals[0].trim(), b = vals[1].trim();
              if (a.equalsIgnoreCase("word") || a.equals("单词")) continue;
              if (a.isEmpty() || b.isEmpty()) throw new IOException("单词或释义为空");
              long before = db.scalar("SELECT COUNT(*) FROM study");
              db.exec("INSERT OR IGNORE INTO study(front,back) VALUES(?,?)", a, b);
              if (db.scalar("SELECT COUNT(*) FROM study") > before) count++;
            }
            db.getWritableDatabase().setTransactionSuccessful();
          } finally {
            db.getWritableDatabase().endTransaction();
          }
          toast("已导入 " + count + " 项（重复内容自动跳过）");
        }
        changed();
      }
    } catch (Exception e) {
      toast("操作未完成：" + e.getMessage());
    }
  }

  static String[] parseCsv(String s) {
    if (s.contains("\t")) return s.split("\t", 2);
    List<String> out = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quote = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"') {
        if (quote && i + 1 < s.length() && s.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else quote = !quote;
      } else if (c == ',' && !quote) {
        out.add(cell.toString());
        cell.setLength(0);
      } else cell.append(c);
    }
    out.add(cell.toString());
    return out.toArray(new String[0]);
  }

  String val(TextView e) {
    Object value = e.getTag();
    return value instanceof String ? (String) value : e.getText().toString().trim();
  }

  String required(EditText e) {
    String s = val(e);
    if (s.isEmpty()) {
      e.setError("请填写此项");
      throw new IllegalArgumentException("请填写必填项");
    }
    return s;
  }

  int integer(EditText e, int min, int max) {
    int n = Integer.parseInt(required(e));
    if (n < min || n > max) throw new IllegalArgumentException("请输入 " + min + "–" + max + " 之间的整数");
    return n;
  }

  int index(String[] a, String s) {
    int i = Arrays.asList(a).indexOf(s);
    return Math.max(0, i);
  }

  interface Save {
    boolean run() throws Exception;
  }

  final class Form {
    LinearLayout body = column();
    AlertDialog dialog;
    String title;
    boolean compact, readOnly;

    Form(String s) {
      title = s;
      body.setPadding(dp(22), dp(4), dp(22), dp(20));
    }

    void note(String s) {
      MainActivity.this.note(body, s);
    }

    EditText input(String label, String value, boolean number) {
      label(body, label);
      EditText e = new EditText(MainActivity.this);
      e.setText(value);
      e.setTextColor(INK);
      e.setTextSize(15);
      e.setSingleLine(false);
      e.setMaxLines(4);
      e.setPadding(dp(12), dp(8), dp(12), dp(8));
      e.setMinimumHeight(dp(48));
      GradientDrawable shape = bg(0xfff2f5fa, 10);
      shape.setStroke(dp(1), 0xffe0e8f3);
      e.setBackground(shape);
      if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
      body.addView(e, new LinearLayout.LayoutParams(-1, -2));
      return e;
    }

    TextView field(String title, String value) {
      LinearLayout line = row();
      line.addView(text(title, 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
      TextView entry = text(value + "  ›", 15, BLUE, false);
      entry.setTag(value);
      entry.setMinHeight(dp(44));
      entry.setContentDescription(title + " " + value);
      line.addView(entry);
      line.setOnClickListener(v -> entry.performClick());
      body.addView(line);
      return entry;
    }

    void showField(View field, boolean visible) {
      int visibility = visible ? View.VISIBLE : View.GONE;
      if (field.getParent() != body) {
        ((View) field.getParent()).setVisibility(visibility);
        return;
      }
      int at = body.indexOfChild(field);
      body.getChildAt(at - 1).setVisibility(visibility);
      field.setVisibility(visibility);
    }

    void selectedValue(TextView entry, String title, String value) {
      entry.setTag(value);
      entry.setText(value + "  ›");
      entry.setContentDescription(title + " " + value);
    }

    TextView date(String title, String value) {
      TextView entry = field(title, value);
      entry.setOnClickListener(v -> {
        LocalDate current;
        try { current = LocalDate.parse(val(entry)); }
        catch (DateTimeException invalid) { current = LocalDate.now(); }
        new DatePickerDialog(MainActivity.this, (picker, year, month, day) ->
            selectedValue(entry, title, LocalDate.of(year, month + 1, day).toString()),
            current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth()).show();
      });
      return entry;
    }

    TextView time(String title, String value) {
      TextView entry = field(title, value);
      entry.setOnClickListener(v -> {
        LocalTime current;
        try { current = LocalTime.parse(val(entry)); }
        catch (DateTimeException invalid) { current = LocalTime.MIDNIGHT; }
        showClockPicker(title, current, false,
            chosen -> selectedValue(entry, title, formatClock(chosen)));
      });
      return entry;
    }

    void runSave(Save action) {
      // Search is read-only; all other forms write records or preferences.
      if (!readOnly && !ActivationActivity.requireWrite(MainActivity.this)) return;
      try {
        if (action.run() && dialog != null) dialog.dismiss();
      } catch (Exception e) {
        String message = e instanceof DateTimeException ? "请检查日期或时间" : e.getMessage();
        toast(message == null ? "请检查输入" : message);
      }
    }

    Spinner select(String label, String[] options, int chosen) {
      label(body, label);
      Spinner s = new Spinner(MainActivity.this);
      ArrayAdapter<String> adapter =
          new ArrayAdapter<>(MainActivity.this, android.R.layout.simple_spinner_item, options);
      adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
      s.setAdapter(adapter);
      s.setSelection(chosen);
      s.setBackground(bg(0xffedf3fc, 10));
      s.setPadding(dp(12), 0, dp(10), 0);
      body.addView(s, new LinearLayout.LayoutParams(-1, dp(48)));
      return s;
    }

    CheckBox check(String text, boolean checked) {
      CheckBox b = new CheckBox(MainActivity.this);
      b.setText(text);
      b.setTextColor(INK);
      b.setTextSize(14);
      b.setChecked(checked);
      b.setButtonTintList(ColorStateList.valueOf(BLUE));
      b.setMinHeight(dp(48));
      body.addView(b);
      return b;
    }

    void show(String save, Save action) {
      readOnly = save.startsWith("查看") || save.startsWith("搜索") || save.equals("复制日志")
          || save.equals("稍后继续") || save.equals("完成");
      ScrollView sc = new ScrollView(MainActivity.this);
      sc.addView(body);
      dialog =
          new AlertDialog.Builder(MainActivity.this)
              .setTitle(title)
              .setView(sc)
              .setPositiveButton(save, null)
              .setNegativeButton("取消", null)
              .create();
      dialog.setOnShowListener(
          d -> {
            dialog.getButton(-1).setTextColor(BLUE);
            dialog.getButton(-2).setTextColor(MUTED);
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v -> runSave(action));
          });
      dialog.show();
      Window w = dialog.getWindow();
      if (w != null) {
        w.setBackgroundDrawable(bg(Color.WHITE, 22));
        w.setGravity(Gravity.BOTTOM);
        w.setLayout(-1, compact ? -2 : (int) (getResources().getDisplayMetrics().heightPixels * .86));
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      }
    }
  }
}
