package com.biu.next;
import org.junit.Test;
import static org.junit.Assert.*;
public class OfflineLicensePolicyTest {
  @Test public void trialIsExactly72ElapsedHours(){
    long start=1794100000000L;
    assertEquals(259200000L,OfflineLicensePolicy.TRIAL_MS);
    assertEquals(259200000L,OfflineLicensePolicy.remaining(start,start));
    assertEquals(1,OfflineLicensePolicy.remaining(start,start+259199999L));
    assertEquals(0,OfflineLicensePolicy.remaining(start,start+259200000L));
    assertEquals(0,OfflineLicensePolicy.remaining(start,start+30L*86400000L));
  }
  @Test public void onlyChosenCodeActivates(){
    assertTrue(OfflineLicensePolicy.matches("YOUP999"));
    assertTrue(OfflineLicensePolicy.matches("  youp999\n"));
    for(String bad:new String[]{"","YOUP99","YOUP9999","YOUP998","YOUP 999","999"})assertFalse(bad,OfflineLicensePolicy.matches(bad));
    assertFalse(OfflineLicensePolicy.matches(null));
  }
  @Test public void clockRollbackCannotExtendTrialWithinSameBoot(){
    assertEquals(160000,OfflineLicensePolicy.logicalTime(90000,65000,7,100000,5000,7));
    assertEquals(200000,OfflineLicensePolicy.logicalTime(200000,65000,7,100000,5000,7));
  }
  @Test public void rebootKeepsPreviouslyObservedTime(){
    assertEquals(100000,OfflineLicensePolicy.logicalTime(90000,300,8,100000,5000,7));
    assertEquals(210000,OfflineLicensePolicy.logicalTime(210000,300,8,100000,5000,7));
  }
  @Test public void saturatedClockDoesNotWrapIntoNewTrial(){
    assertEquals(Long.MAX_VALUE,OfflineLicensePolicy.logicalTime(0,1000,7,Long.MAX_VALUE-10,0,7));
    assertEquals(0,OfflineLicensePolicy.remaining(1000,Long.MAX_VALUE));
  }
}
