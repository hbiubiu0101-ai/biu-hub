package com.biu.next;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import java.util.Locale;

public final class LockPanelService extends AccessibilityService {
  private static final long FAST_RETRY_MS = 80;
  private static final long VISIBILITY_CHECK_MS = 80;
  private static boolean connected;
  private static LockPanelService activeService;
  private static boolean overlayReady;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable show = this::showIfLocked;
  private Store store;
  private WindowManager windows;
  private LockPanelView panel;
  private FloatingTasks floating;
  private LedgerGesture ledgerGesture;
  private ScreenReceipts screenReceipts;
  private long captureGeneration;
  private long nextFloatingIncomeRefresh;
  private DisplayManager displays;
  private boolean displayOn, screenOff, leftLockScreen, observedInteractive;
  private long wakeAt, lockedSince = -1, nextIncomeRefresh;
  private java.time.LocalDate panelDay;
  private String foregroundPackage = "";
  private boolean capturingReceipt;
  private String lastShowResult;
  private boolean credentialVisible;
  private long plainLockSince = -1;
  private static final int WINDOW_UNKNOWN = 0, WINDOW_LOCK = 1, WINDOW_CREDENTIAL = 2;
  private final java.util.concurrent.ExecutorService windowScanner =
      java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "YoupLockWindow"); thread.setDaemon(true); return thread;
      });
  private long windowGeneration, lastWindowScanAt = -1000, showDeadline;
  private boolean scanPending;
  private int cachedWindowState = WINDOW_UNKNOWN;

  private void resetWindowSnapshot() {
    windowGeneration++;
    cachedWindowState = WINDOW_UNKNOWN;
    lastWindowScanAt = -1000;
  }

  private void requestWindowScan() {
    if (store == null || scanPending || windowScanner.isShutdown()
        || !PrivacyCenter.accessibilityAccepted(this) || !PrivacyCenter.windowStructureAccepted(this)) return;
    long now = SystemClock.uptimeMillis();
    if (now - lastWindowScanAt < 120) return;
    lastWindowScanAt = now;
    scanPending = true;
    long generation = windowGeneration;
    windowScanner.execute(() -> {
      int result;
      try { result = inspectLockWindows(); }
      catch (RuntimeException e) { result = WINDOW_UNKNOWN; }
      final int found = result;
      handler.post(() -> {
        scanPending = false;
        if (store == null || activeService != this) return;
        if (generation != windowGeneration) {
          if (!screenOff && !leftLockScreen) requestWindowScan();
          return;
        }
        if (!getSystemService(KeyguardManager.class).isKeyguardLocked()) {
          leaveLockScreen(); return;
        }
        cachedWindowState = found;
        if (screenOff || leftLockScreen) return;
        if (found == WINDOW_CREDENTIAL) pauseForCredential();
        else if (credentialVisible) {
          handler.removeCallbacks(credentialWatch); handler.post(credentialWatch);
        } else if (panel == null) scheduleShow();
      });
    });
  }

  private final Runnable credentialWatch = new Runnable() {
    @Override public void run() {
      if (store == null || !credentialVisible || !screenLocked()) return;
      int state = lockWindowState();
      if (state == WINDOW_LOCK) {
        long now = SystemClock.uptimeMillis();
        if (plainLockSince < 0) plainLockSince = now;
        if (now - plainLockSince >= 300) {
          credentialVisible = false; plainLockSince = -1;
          scheduleShow(); return;
        }
      } else plainLockSince = -1;
      handler.postDelayed(this, 200);
    }
  };

  private void pauseForCredential() {
    if (!credentialVisible) LockPanelTrace.record(this, "overlay-paused:credential");
    credentialVisible = true; plainLockSince = -1;
    hide();
    handler.removeCallbacks(credentialWatch);
    handler.postDelayed(credentialWatch, 200);
  }

  private long overlaySuppressedUntil, notLockedSince = -1;
  private final SharedPreferences.OnSharedPreferenceChangeListener backgroundSetting =
      (preferences, key) -> {
        if (SproutView.isAppearanceKey(key) && floating != null) {
          floating.updateAppearance();
          return;
        }
        if (LockPanelKeepAlive.FLOATING_ENABLED.equals(key)) {
          updateFloating();
          return;
        }
        if (!LockPanelKeepAlive.BACKGROUND_ENABLED.equals(key)
            && !LockPanelKeepAlive.SUSPENDED.equals(key)
            && !LockPanelKeepAlive.SESSION.equals(key)) return;
        if (lockCardsAllowed()) {
          readWakeState();
          scheduleShow();
        } else {
          hide();
          updateOverlayReady(false);
        }
        updateFloating();
      };

  private final Runnable tick = new Runnable() {
    @Override
    public void run() {
      if (panel == null) return;
      if (!screenLocked()) {
        // Once keyguard is gone, never leave lock-screen cards over the launcher.
        leaveLockScreen();
        return;
      }
      if (lockWindowState() == WINDOW_CREDENTIAL) { pauseForCredential(); return; }
      notLockedSince = -1;
      java.time.LocalDate today = java.time.LocalDate.now();
      if (!today.equals(panelDay)) {
        hide();
        scheduleShow();
        return;
      }
      long now = SystemClock.uptimeMillis();
      if (now >= nextIncomeRefresh) {
        panel.updateIncome();
        panel.updateReminders();
        nextIncomeRefresh = now + 1000;
      }
      handler.postDelayed(this, VISIBILITY_CHECK_MS);
    }
  };

  private final DisplayManager.DisplayListener display = new DisplayManager.DisplayListener() {
    @Override public void onDisplayAdded(int id) {}
    @Override public void onDisplayRemoved(int id) {}
    @Override public void onDisplayChanged(int id) {
      if (id != Display.DEFAULT_DISPLAY) return;
      boolean on = defaultDisplayOn();
      if (displayOn == on) return;
      displayOn = on;
      LockPanelTrace.record(LockPanelService.this, on ? "display-on" : "display-off");
      if (on) wake();
      else sleep();
    }
  };

  private final BroadcastReceiver screen = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      LockPanelTrace.record(context, action);
      if (Intent.ACTION_SCREEN_ON.equals(action)) wake();
      else if (Intent.ACTION_SCREEN_OFF.equals(action)) sleep();
      else if (Intent.ACTION_USER_PRESENT.equals(action)) {
        rearmWake();
        leaveLockScreen();
      }
    }
  };

  private void sleep() {
    screenOff = true;
    resetWindowSnapshot();
    handler.removeCallbacks(credentialWatch);
    lastShowResult = null;
    hide();
    handler.removeCallbacks(floatingWake);
    if (floating != null) floating.hide();
  }

  private void wake() {
    // Duplicate wake signals must not clear a live credential prompt.
    if (defaultDisplayOn() && getSystemService(PowerManager.class).isInteractive()) rearmWake();
    if (!leftLockScreen && panel == null) scheduleShow();
    updateFloating();
    handler.removeCallbacks(floatingWake);
    if (LockPanelKeepAlive.floatingEnabled(this)) handler.postDelayed(floatingWake, 450);
  }

  private void rearmWake() {
    if (!screenOff && !leftLockScreen) return;
    screenOff = false;
    leftLockScreen = false;
    credentialVisible = false;
    overlaySuppressedUntil = 0;
    notLockedSince = -1;
    lockedSince = -1;
    wakeAt = SystemClock.uptimeMillis();
    showDeadline = wakeAt + 2500;
    resetWindowSnapshot();
  }

  void leaveLockScreen() {
    if (!leftLockScreen) LockPanelTrace.record(this, "overlay-hidden:unlocked");
    leftLockScreen = true;
    showDeadline = 0;
    resetWindowSnapshot();
    handler.removeCallbacks(credentialWatch);
    hide();
    updateFloating();
  }

  void pauseForEditor() {
    credentialVisible = true;
    hide();
  }

  static void restoreLockedCards() {
    LockPanelService service = activeService;
    if (service == null || !service.getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
    service.credentialVisible = false;
    service.leftLockScreen = false;
    service.overlaySuppressedUntil = SystemClock.uptimeMillis() + 200;
    service.scheduleShow();
  }

  static void refreshReminderCards() {
    LockPanelService service = activeService;
    if (service == null) return;
    service.handler.post(() -> {
      if (service.panel != null && !service.panel.editing()) service.panel.updateReminders();
    });
  }

  static boolean connected() {
    return connected;
  }

  static boolean enabledInSettings(Context context) {
    ComponentName expected = new ComponentName(context, LockPanelService.class);
    String enabled = Settings.Secure.getString(
        context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    TextUtils.SimpleStringSplitter services = new TextUtils.SimpleStringSplitter(':');
    services.setString(enabled == null ? "" : enabled);
    for (String name : services) {
      if (expected.equals(ComponentName.unflattenFromString(name))) return true;
    }
    AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
    if (manager == null) return false;
    for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
        AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
      if (expected.equals(ComponentName.unflattenFromString(info.getId()))) return true;
    }
    return false;
  }

  static boolean overlayReady() {
    return overlayReady;
  }

  private void updateOverlayReady(boolean ready) {
    if (overlayReady == ready) return;
    overlayReady = ready;
    LockSync.refresh(this);
  }

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    if (store != null) return;
    store = new Store(this);
    windows = getSystemService(WindowManager.class);
    displays = getSystemService(DisplayManager.class);
    floating = new FloatingTasks(this, windows, store, this::canShowFloating, this::canReviewReceipt, this::watchFloatingPanel);
    // Screen-swipe capture was retired; quick-settings tile remains available.
    ledgerGesture = null;
    readWakeState();
    displays.registerDisplayListener(display, handler);
    IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
    filter.addAction(Intent.ACTION_SCREEN_OFF);
    filter.addAction(Intent.ACTION_USER_PRESENT);
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(screen, filter);
    connected = true;
    activeService = this;
    LockPanelKeepAlive.preferences(this).registerOnSharedPreferenceChangeListener(backgroundSetting);
    LockPanelTrace.record(this, "accessibility-connected");
    LockPanelKeepAlive.ensureRunning(this);
    scheduleShow();
    handler.post(visibilityWatch);
    updateFloating();
  }

  // Recovery is independent of activity lifecycle and does not expire after wake.
  // Read only power/keyguard here; window content remains consent-gated.
  private long nextReminderCheck;
  private boolean lastLicenseAllowed=true;
  private final Runnable visibilityWatch = new Runnable() {
    @Override public void run() {
      if (store == null) return;
      boolean licenseAllowed=ActivationActivity.canUse(LockPanelService.this);
      if(lastLicenseAllowed!=licenseAllowed){
        lastLicenseAllowed=licenseAllowed;updateFloating();LockSync.refresh(LockPanelService.this);
      }
      boolean awake = defaultDisplayOn() && getSystemService(PowerManager.class).isInteractive();
      if (awake && SystemClock.uptimeMillis() >= nextReminderCheck) {
        nextReminderCheck = SystemClock.uptimeMillis() + 1000;
        LockSync.reminders(LockPanelService.this);
      }
      if (lockCardsAllowed()) {
        if (!awake) {
          if (!screenOff || panel != null) sleep();
        } else if (!getSystemService(KeyguardManager.class).isKeyguardLocked()) {
          if (!leftLockScreen || panel != null) leaveLockScreen();
        } else {
          if (screenOff || leftLockScreen) {
            LockPanelTrace.record(LockPanelService.this, "lock-state-recovered");
            rearmWake();
          }
          if (!credentialVisible && panel == null) scheduleShow();
        }
      } else if (panel != null) hide();
      handler.postDelayed(this, awake ? 500 : 2000);
    }
  };

  private final Runnable floatingWake = this::updateFloating;
  private final Runnable floatingTick = new Runnable() {
    @Override public void run() {
      updateFloating();
      if (floating == null || !floating.expanded()) return;
      long now = SystemClock.uptimeMillis();
      if (now >= nextFloatingIncomeRefresh) {
        floating.updateIncome();
        nextFloatingIncomeRefresh = now + 1000;
      }
      handler.postDelayed(this, 250);
    }
  };

  private boolean lockCardsAllowed() {
    return PrivacyCenter.accessibilityAccepted(this) && ActivationActivity.canUse(this) && LockPanelKeepAlive.enabled(this);
  }

  boolean canShowFloating() {
    return canReviewReceipt() && LockPanelKeepAlive.floatingEnabled(this);
  }

  private boolean canReviewReceipt() {
    if (!PrivacyCenter.accessibilityAccepted(this) || !ActivationActivity.canUse(this) || floating == null || panel != null || screenOff || LockPanelKeepAlive.suspended(this)
        || !defaultDisplayOn() || !getSystemService(PowerManager.class).isInteractive()) return false;
    KeyguardManager keyguard = getSystemService(KeyguardManager.class);
    return !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
  }

  private void updateFloating() {
    if (floating == null) return;
    boolean canReview = canReviewReceipt();
    boolean allowed = canShowFloating();
    if (!canReview && capturingReceipt) {
      capturingReceipt = false;
      captureGeneration++;
    }
    if (!canReview || capturingReceipt) floating.hide();
    else if (allowed) floating.show();
    else floating.hideMascot();
    if (ledgerGesture != null) ledgerGesture.show(allowed && !capturingReceipt && !floating.expanded());
  }

  void manualReceipt() {
    if (!capturingReceipt && canShowFloating())
      floating.reviewReceipts(java.util.Collections.emptyList(), "");
  }

  static String receiptTarget() {
    return activeService == null ? null : activeService.foregroundPackage;
  }

  private String tileSource;

  static boolean captureFromTile(String source) {
    LockPanelService service = activeService;
    if (service == null) return false;
    service.tileSource = source;
    service.handler.removeCallbacks(service.tileCapture);
    service.handler.postDelayed(service.tileCapture, 350);
    return true;
  }

  private final Runnable tileCapture = () -> {
    if (canReviewReceipt() && (tileSource == null || tileSource.equals(foregroundPackage))) captureReceipt();
    else android.widget.Toast.makeText(this, "请解锁并打开账单页面后再识别", android.widget.Toast.LENGTH_SHORT).show();
  };

  void captureReceipt() {
    if (!PrivacyCenter.receiptAccepted(this)) {
      startActivity(new Intent(this, ReceiptConsentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      return;
    }
    if (capturingReceipt || !canReviewReceipt()) return;
    if (Build.VERSION.SDK_INT < 30) {
      finishReceiptCapture(java.util.Collections.emptyList(), "当前系统无法截屏，可手动记一笔");
      return;
    }
    String source = foregroundPackage;
    long generation = ++captureGeneration;
    capturingReceipt = true;
    updateFloating();
    handler.postDelayed(() -> {
      if (!captureActive(generation, source)) return;
      try {
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
          @Override public void onSuccess(ScreenshotResult result) {
            try (android.hardware.HardwareBuffer buffer = result.getHardwareBuffer()) {
              if (!captureActive(generation, source)) return;
              android.graphics.Bitmap hardware = android.graphics.Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
              if (hardware == null) { onFailure(0); return; }
              android.graphics.Bitmap bitmap;
              try {
                bitmap = hardware.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
              } finally {
                hardware.recycle();
              }
              if (bitmap == null) { onFailure(0); return; }
              try {
                if (screenReceipts == null) screenReceipts = new ScreenReceipts();
              } catch (RuntimeException error) {
                bitmap.recycle();
                onFailure(0);
                return;
              }
              screenReceipts.recognize(bitmap, java.time.LocalDate.now(), "com.tencent.mm".equals(source), entries -> {
                if (captureActive(generation, source)) finishReceiptCapture(entries,
                    entries.isEmpty() ? "未找到可确认的支出，可手动记一笔" : "");
              }, null, () -> onFailure(0));
            } catch (RuntimeException error) {
              onFailure(0);
            }
          }

          @Override public void onFailure(int errorCode) {
            if (captureActive(generation, source))
              finishReceiptCapture(java.util.Collections.emptyList(), "无法识别当前画面，可手动记一笔");
          }
        });
      } catch (RuntimeException error) {
        if (captureActive(generation, source))
          finishReceiptCapture(java.util.Collections.emptyList(), "无法截取当前画面，可手动记一笔");
      }
    }, 180);
    handler.postDelayed(() -> {
      if (captureActive(generation, source))
        finishReceiptCapture(java.util.Collections.emptyList(), "识别超时，可手动记一笔");
    }, 10_000);
  }

  private boolean captureActive(long generation, String source) {
    if (!capturingReceipt || captureGeneration != generation) return false;
    if (canReviewReceipt() && source.equals(foregroundPackage)) return true;
    capturingReceipt = false;
    updateFloating();
    return false;
  }

  private void finishReceiptCapture(java.util.List<ScreenReceipts.Entry> entries, String message) {
    capturingReceipt = false;
    updateFloating();
    if (canReviewReceipt()) floating.reviewReceipts(entries, message);
  }

  private void watchFloatingPanel() {
    if (ledgerGesture != null) ledgerGesture.show(canShowFloating() && !capturingReceipt && !floating.expanded());
    handler.removeCallbacks(floatingTick);
    if (floating != null && floating.expanded()) {
      nextFloatingIncomeRefresh = SystemClock.uptimeMillis() + 1000;
      handler.postDelayed(floatingTick, 250);
    }
  }

  private void readWakeState() {
    displayOn = defaultDisplayOn();
    observedInteractive = getSystemService(PowerManager.class).isInteractive();
    screenOff = !displayOn || !observedInteractive;
    leftLockScreen = false;
    credentialVisible = false;
    overlaySuppressedUntil = 0;
    notLockedSince = -1;
    wakeAt = SystemClock.uptimeMillis();
    lockedSince = -1;
    showDeadline = wakeAt + 2500;
    resetWindowSnapshot();
  }

  private void scheduleShow() {
    if (!lockCardsAllowed()) return;
    handler.removeCallbacks(show);
    if (!screenLocked()) return;
    if (showDeadline < SystemClock.uptimeMillis()) showDeadline = SystemClock.uptimeMillis() + 1000;
    handler.post(show);
  }

  private boolean defaultDisplayOn() {
    Display current = displays.getDisplay(Display.DEFAULT_DISPLAY);
    return current != null && current.getState() == Display.STATE_ON;
  }

  boolean screenLocked() {
    boolean interactive = getSystemService(PowerManager.class).isInteractive();
    // A SystemUI event can reveal a wake even when display and power broadcasts were missed.
    if (interactive != observedInteractive) {
      observedInteractive = interactive;
      if (interactive) {
        rearmWake();
      } else {
        screenOff = true;
        lockedSince = -1;
      }
      LockPanelTrace.record(this, interactive ? "interactive-on" : "interactive-off");
    }
    boolean locked = interactive && defaultDisplayOn()
        && getSystemService(KeyguardManager.class).isKeyguardLocked();
    if (locked && (screenOff || leftLockScreen)) rearmWake();
    return locked;
  }

  private void recordShowResult(String result) {
    if (result.equals(lastShowResult)) return;
    lastShowResult = result;
    LockPanelTrace.record(this, result);
  }

  private void showIfLocked() {
    if (store == null || panel != null || !lockCardsAllowed()) return;
    // Check power/keyguard before doing any data or window work.
    if (!screenLocked()) {
      if (SystemClock.uptimeMillis() < showDeadline && !leftLockScreen && !screenOff)
        handler.postDelayed(show, FAST_RETRY_MS);
      return;
    }
    if (credentialVisible) return;
    // First paint never waits for a window scan. Detection runs separately and
    // removes the overlay when a credential screen is identified.
    if (lockWindowState() == WINDOW_CREDENTIAL) { pauseForCredential(); return; }
    long suppressed = overlaySuppressedUntil - SystemClock.uptimeMillis();
    if (suppressed > 0) { handler.postDelayed(show, suppressed); return; }
    LockCards data = LockCards.read(store);
    if (data.empty()) {
      recordShowResult("no-selected-cards");
      return;
    }

    if (floating != null) floating.hide();
    DisplayMetrics metrics = new DisplayMetrics();
    windows.getDefaultDisplay().getMetrics(metrics);
    LockPanelStyle style = LockPanelStyle.read(this);
    int width = Math.max(1, Math.round(metrics.widthPixels * style.width / 100f));
    LockPanelView candidate = new LockPanelView(this, store);
    candidate.style(style, true);
    candidate.render(data);
    int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
    if (getSystemService(KeyguardManager.class).isKeyguardSecure()) {
      flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
      candidate.dismissWhenUnlocking(() -> handler.post(() -> {
        if (panel == candidate) {
          LockPanelTrace.record(this, "overlay-paused:unlock-gesture");
          overlaySuppressedUntil = SystemClock.uptimeMillis() + 1200;
          hide();
          // If no unlock followed, recover on the same lock screen without a screen-off cycle.
          scheduleShow();
        }
      }));
    }
    WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags, PixelFormat.TRANSLUCENT);
    layout.gravity = Gravity.TOP | Gravity.LEFT;
    layout.x = style.left(metrics.widthPixels, width);
    candidate.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST));
    layout.y = style.top(metrics.heightPixels, candidate.getMeasuredHeight());
    configurePanelEditing(candidate, layout, metrics.heightPixels, style);
    configureGroupDrag(candidate, layout, metrics, style);
    layout.setTitle("有谱亮屏计划卡片");
    if (!screenLocked() || credentialVisible || cachedWindowState == WINDOW_CREDENTIAL) return;
    try {
      windows.addView(candidate, layout);
    } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException | SecurityException e) {
      Log.w("NextLockPanel", "System rejected the lock screen overlay", e);
      recordShowResult("overlay-rejected:" + e.getClass().getSimpleName());
      updateOverlayReady(false);
      return;
    }
    panel = candidate;
    panelDay = java.time.LocalDate.now();
    recordShowResult("overlay-shown");
    updateOverlayReady(true);
    handler.removeCallbacks(show);
    nextIncomeRefresh = SystemClock.uptimeMillis() + 1000;
    handler.postDelayed(tick, VISIBILITY_CHECK_MS);
  }

  private void configurePanelEditing(LockPanelView candidate, WindowManager.LayoutParams layout,
      int screenHeight, LockPanelStyle style) {
    int idleFlags = layout.flags;
    int naturalHeight = candidate.getMeasuredHeight();
    Runnable fitToKeyboard = () -> {
      if (panel != candidate || !candidate.editing()) return;
      Rect visible = new Rect();
      candidate.getWindowVisibleDisplayFrame(visible);
      int bottom = visible.bottom > 0 ? Math.min(screenHeight, visible.bottom) : screenHeight;
      if (Build.VERSION.SDK_INT >= 30) {
        WindowInsets insets = candidate.getRootWindowInsets();
        if (insets != null && insets.isVisible(WindowInsets.Type.ime())) {
          bottom = Math.min(bottom, screenHeight - insets.getInsets(WindowInsets.Type.ime()).bottom);
        }
      }
      int top = Math.max(0, visible.top);
      int height = Math.max(1, bottom - top);
      int targetY = Math.min(style.top(screenHeight, naturalHeight), Math.max(0, height - naturalHeight));
      int targetHeight = naturalHeight > height ? height : WindowManager.LayoutParams.WRAP_CONTENT;
      if (targetY == layout.y && targetHeight == layout.height) return;
      layout.y = targetY;
      layout.height = targetHeight;
      windows.updateViewLayout(candidate, layout);
      candidate.revealEditor();
    };
    candidate.onEditWindowChanged(editing -> {
      if (panel != candidate) return;
      layout.flags = editing
          ? (idleFlags & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
              | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH : idleFlags;
      layout.softInputMode = editing ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
          : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED;
      layout.y = style.top(screenHeight, naturalHeight);
      layout.height = WindowManager.LayoutParams.WRAP_CONTENT;
      windows.updateViewLayout(candidate, layout);
    });
    candidate.getViewTreeObserver().addOnGlobalLayoutListener(fitToKeyboard::run);
    candidate.setOnApplyWindowInsetsListener((view, insets) -> {
      candidate.post(fitToKeyboard);
      return insets;
    });
  }

  private void configureGroupDrag(LockPanelView candidate, WindowManager.LayoutParams layout,
      DisplayMetrics metrics, LockPanelStyle style) {
    int idleHeight = candidate.getMeasuredHeight();
    candidate.onMoveGroup(new java.util.function.Consumer<MotionEvent>() {
      private float startX, startY;
      private int oldX, oldY;

      @Override public void accept(MotionEvent event) {
        if (panel != candidate) return;
        int maxX = Math.max(0, metrics.widthPixels - layout.width);
        int maxY = Math.max(0, metrics.heightPixels - idleHeight);
        switch (event.getActionMasked()) {
          case MotionEvent.ACTION_DOWN:
            startX = event.getRawX();
            startY = event.getRawY();
            oldX = layout.x;
            oldY = layout.y;
            return;
          case MotionEvent.ACTION_MOVE:
          case MotionEvent.ACTION_UP:
            layout.x = Math.max(0, Math.min(maxX, oldX + Math.round(event.getRawX() - startX)));
            layout.y = Math.max(0, Math.min(maxY, oldY + Math.round(event.getRawY() - startY)));
            break;
          case MotionEvent.ACTION_CANCEL:
            layout.x = oldX;
            layout.y = oldY;
            break;
          default:
            return;
        }
        windows.updateViewLayout(candidate, layout);
      }
    }, () -> {
      if (panel == candidate) style.setPosition(this, layout.x, Math.max(0, metrics.widthPixels - layout.width),
          layout.y, Math.max(0, metrics.heightPixels - idleHeight));
    });
  }

  private boolean isCurrentKeyboard(CharSequence source) {
    String inputMethod = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
    ComponentName keyboard = inputMethod == null ? null : ComponentName.unflattenFromString(inputMethod);
    return keyboard != null && TextUtils.equals(keyboard.getPackageName(), source);
  }

  private void hide() {
    lockedSince = -1;
    handler.removeCallbacks(show);
    handler.removeCallbacks(tick);
    if (panel == null) return;
    LockPanelView previous = panel;
    panel = null;
    try {
      windows.removeViewImmediate(previous);
    } catch (IllegalArgumentException e) {
      Log.d("NextLockPanel", "Overlay was already detached", e);
    }
  }

  @Override
  public void onConfigurationChanged(Configuration configuration) {
    super.onConfigurationChanged(configuration);
    if (store == null) return;
    hide();
    if (floating != null) floating.hide();
    scheduleShow();
    updateFloating();
  }

  static boolean isCredentialPrompt(AccessibilityEvent event) {
    CharSequence source = event.getPackageName();
    if (source == null) return false;
    String pkg = source.toString();
    if (!pkg.endsWith(".systemui") && !pkg.endsWith(".keyguard")) return false;
    CharSequence eventClass = event.getClassName();
    if (eventClass != null) {
      String name = eventClass.toString().toLowerCase(Locale.ROOT);
      if (name.contains("bouncer") || name.contains("lockpattern") || name.contains("patternview")
          || name.contains("keyguardpin") || name.contains("keyguardpassword")
          || name.contains("confirmcredential") || name.contains("passwordview")
          || name.contains("pinview") || name.contains("securitycontainer")
          || name.contains("keyguardpattern") || name.contains("patternunlock")
          || name.contains("passwordentry") || name.contains("pukview")) return true;
    }
    if (credentialText(event.getContentDescription())) return true;
    for (CharSequence text : event.getText()) if (credentialText(text)) return true;
    return false;
  }

  private int lockWindowState() {
    requestWindowScan();
    return cachedWindowState;
  }

  private int inspectLockWindows() {
    if (!PrivacyCenter.accessibilityAccepted(this) || !PrivacyCenter.windowStructureAccepted(this)) return WINDOW_UNKNOWN;
    int state = WINDOW_UNKNOWN;
    java.util.List<android.view.accessibility.AccessibilityWindowInfo> observed;
    try { observed = getWindows(); } catch (SecurityException e) { return WINDOW_UNKNOWN; }
    try {
    for (android.view.accessibility.AccessibilityWindowInfo window : observed) {
      if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
          || window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
      android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
      if (root == null) continue;
      try {
        String pkg = String.valueOf(root.getPackageName());
        if (!pkg.endsWith(".systemui") && !pkg.endsWith(".keyguard")) {
          // An application behind keyguard is not evidence of an unlocked device.
          continue;
        }
        int[] budget = {512};
        int found = scanLockNode(root, budget, 0, SystemClock.uptimeMillis() + 60);
        if (budget[0] < 0 && found != WINDOW_CREDENTIAL) found = WINDOW_UNKNOWN;
        if (found == WINDOW_CREDENTIAL) return found;
        if (found == WINDOW_LOCK) state = found;
      } finally { root.recycle(); }
    }
    return state;
    } finally {
      for (android.view.accessibility.AccessibilityWindowInfo window : observed) window.recycle();
    }
  }

  static int classifyLockView(String type) {
    String value = type == null ? "" : type.toLowerCase(Locale.ROOT);
    if (value.contains("lockpattern") || value.contains("patternview") || value.contains("keyguardpattern")
        || value.contains("pinentry") || value.contains("passwordentry") || value.contains("keyguardpin")
        || value.contains("keyguardpassword") || value.contains("securitycontainer")
        || value.contains("bouncer") || value.contains("pin_view") || value.contains("password_view")
        || value.contains("pattern_view") || value.contains("password_entry") || value.contains("pin_entry"))
      return WINDOW_CREDENTIAL;
    if (value.contains("keyguard_clock") || value.contains("keyguard_status_view")
        || value.contains("keyguardstatusview") || value.contains("lockscreen_clock")
        || value.contains("lock_screen_clock")) return WINDOW_LOCK;
    return WINDOW_UNKNOWN;
  }

  private static int scanLockNode(android.view.accessibility.AccessibilityNodeInfo node, int[] remaining, int depth, long deadline) {
    if (SystemClock.uptimeMillis() > deadline || --remaining[0] < 0) { remaining[0] = -1; return WINDOW_UNKNOWN; }
    if (depth > 24 || !node.isVisibleToUser()) return WINDOW_UNKNOWN;
    // No getText(), password values, content descriptions or user data are read here.
    int result = classifyLockView(String.valueOf(node.getClassName()) + " " + node.getViewIdResourceName());
    if (result == WINDOW_CREDENTIAL) return result;
    for (int i=0; i<node.getChildCount() && remaining[0]>0; i++) {
      android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
      if (child == null) continue;
      try {
        int found = scanLockNode(child, remaining, depth+1, deadline);
        if (found == WINDOW_CREDENTIAL) return found;
        if (found == WINDOW_LOCK) result = found;
      } finally { child.recycle(); }
    }
    return result;
  }

  private static boolean credentialText(CharSequence value) {
    if (value == null) return false;
    String text = value.toString().toLowerCase(Locale.ROOT);
    return text.contains("绘制图案") || text.contains("绘制图形") || text.contains("图案解锁")
        || text.contains("输入图案") || text.contains("输入手势") || text.contains("绘制解锁图案") || text.contains("输入解锁图案")
        || text.contains("绘制解锁手势") || text.contains("锁屏密码") || text.equals("输入密码")
        || text.contains("输入密码解锁") || text.contains("请输入密码")
        || text.contains("输入 pin") || text.contains("输入pin")
        || text.contains("draw pattern") || text.contains("draw your pattern")
        || text.contains("enter pin") || text.contains("enter your pin")
        || text.contains("enter password") || text.contains("enter your password");
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (store == null || !PrivacyCenter.accessibilityAccepted(this)) return;
    int type = event.getEventType();
    if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
    // Unlock must take precedence over scan requests and event timestamp filtering.
    boolean locked = getSystemService(KeyguardManager.class).isKeyguardLocked();
    if (!locked) {
      if (panel != null || !leftLockScreen) leaveLockScreen();
    } else {
      long eventTime = event.getEventTime();
      if (eventTime > 0 && eventTime < wakeAt) return;
      if (lockCardsAllowed() && isCredentialPrompt(event)) {
        pauseForCredential(); return;
      }
      requestWindowScan();
      if (lockCardsAllowed() && cachedWindowState == WINDOW_CREDENTIAL) {
        pauseForCredential(); return;
      }
    }
    if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
    updateFloating();
    CharSequence source = event.getPackageName();
    if (TextUtils.isEmpty(source)) return;
    if (!TextUtils.equals(getPackageName(), source) && !TextUtils.equals("com.android.systemui", source)
        && !isCurrentKeyboard(source)) {
      if (!TextUtils.equals(foregroundPackage, source) && floating != null) floating.closeReceiptReview();
      foregroundPackage = source.toString();
    } else if (TextUtils.equals(MainActivity.class.getName(), event.getClassName())) {
      foregroundPackage = "";
    }
    if (!lockCardsAllowed()) return;
    if (panel != null && panel.editing() && isCurrentKeyboard(source)) return;
    if (TextUtils.equals("com.android.systemui", source)) {
      // Generic SystemUI/status-bar events also occur above the password screen.
      // Only a positively identified plain lock screen can clear the credential latch.
      if (credentialVisible) {
        handler.removeCallbacks(credentialWatch);
        handler.post(credentialWatch);
        return;
      }
      if (panel == null && !credentialVisible) scheduleShow();
    } else if (panel == null && !credentialVisible && !leftLockScreen
        && getSystemService(KeyguardManager.class).isKeyguardLocked()) {
      // Events from a launcher/app behind keyguard do not mean the user unlocked.
      scheduleShow();
    }

  }

  @Override
  public void onInterrupt() {
    // NEXT does not produce accessibility feedback. Keep screen-transition work queued.
    LockPanelTrace.record(this, "accessibility-feedback-interrupted");
  }

  private void disconnect() {
    if (store == null) return;
    connected = false;
    if (activeService == this) activeService = null;
    LockPanelKeepAlive.preferences(this).unregisterOnSharedPreferenceChangeListener(backgroundSetting);
    LockPanelTrace.record(this, "accessibility-disconnected");
    unregisterReceiver(screen);
    displays.unregisterDisplayListener(display);
    windowGeneration++;
    windowScanner.shutdownNow();
    handler.removeCallbacks(visibilityWatch);
    handler.removeCallbacks(credentialWatch);
    handler.removeCallbacks(floatingWake);
    handler.removeCallbacks(floatingTick);
    handler.removeCallbacks(tileCapture);
    capturingReceipt = false;
    captureGeneration++;
    if (ledgerGesture != null) { ledgerGesture.close(); ledgerGesture = null; }
    if (screenReceipts != null) { screenReceipts.close(); screenReceipts = null; }
    if (floating != null) {
      floating.hide();
      floating = null;
    }
    hide();
    updateOverlayReady(false);
    store.close();
    store = null;
  }

  @Override
  public boolean onUnbind(Intent intent) {
    disconnect();
    return super.onUnbind(intent);
  }

  @Override
  public void onDestroy() {
    disconnect();
    super.onDestroy();
  }
}
