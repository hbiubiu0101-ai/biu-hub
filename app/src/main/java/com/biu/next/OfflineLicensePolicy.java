package com.biu.next;

/** Local trial arithmetic; no network or device identifier. */
final class OfflineLicensePolicy {
  static final long TRIAL_MS = 3L * 24 * 60 * 60 * 1000;
  private OfflineLicensePolicy() {}
  static boolean matches(String value) {
    return value != null && "YOUP999".equals(value.trim().toUpperCase(java.util.Locale.ROOT));
  }
  static long logicalTime(long wall,long elapsed,int boot,long savedTime,long savedElapsed,int savedBoot) {
    long logical=Math.max(wall,savedTime);
    if(boot>=0 && boot==savedBoot && elapsed>=savedElapsed) {
      long delta=elapsed-savedElapsed;
      long advanced=savedTime>Long.MAX_VALUE-delta?Long.MAX_VALUE:savedTime+delta;
      logical=Math.max(logical,advanced);
    }
    return logical;
  }
  static long remaining(long started,long now) {
    if(now<=started)return TRIAL_MS;
    long age=now-started;
    if(age<0 || age>=TRIAL_MS)return 0;
    return TRIAL_MS-age;
  }
}
