package dev.corefilter.pilot.core;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ScreenInterpreterTest {
  private final ScreenInterpreter interpreter = new ScreenInterpreter();

  @Test
  public void expectedVideoCompletionIsNotARunResult() {
    ScreenInterpreter.Reading reading =
        interpreter.read(
            Fixtures.menu(1000, Fixtures.token("Video completed"), Fixtures.token("Close")),
            true,
            true);
    assertEquals(ScreenInterpreter.Screen.AD, reading.screen());
    assertNotNull(reading.action());
    assertEquals("close", reading.action().normalized());
  }

  @Test
  public void closeButtonAloneCannotAuthorizeAdDismissal() {
    ScreenInterpreter.Reading reading =
        interpreter.read(Fixtures.menu(1000, Fixtures.token("Close")), true, true);
    assertEquals(ScreenInterpreter.Screen.AD, reading.screen());
    assertNull(reading.action());
  }

  @Test
  public void unrelatedCloseIsNotAnAdWithoutExpectedAdContext() {
    ScreenInterpreter.Reading reading =
        interpreter.read(
            Fixtures.menu(1000, Fixtures.token("Reward earned"), Fixtures.token("Close")),
            false,
            true);
    assertEquals(ScreenInterpreter.Screen.UNKNOWN, reading.screen());
    assertNull(reading.action());
  }

  @Test
  public void purchaseAndDestructiveWordsAlwaysStopAutomation() {
    for (String unsafe :
        new String[] {
          "Purchase",
          "Buy now",
          "Discard",
          "Delete",
          "Overwrite",
          "Privacy",
          "Consent",
          "Terms of service",
          "Not enough shards"
        }) {
      ScreenInterpreter.Reading reading =
          interpreter.read(
              Fixtures.observation(1000, 1000, Fixtures.game(), Fixtures.token(unsafe)),
              true,
              true);
      assertEquals(unsafe, ScreenInterpreter.Screen.UNSAFE, reading.screen());
      assertNull(reading.action());
    }
  }

  @Test
  public void watchAdTitleNearTopIsNotAConfirmationTarget() {
    ScreenInterpreter.Reading reading =
        interpreter.read(
            Fixtures.menu(1000, Fixtures.token("Watch ad", .2, .1, .8, .2)), false, true);
    assertEquals(ScreenInterpreter.Screen.AD_CONFIRM, reading.screen());
    assertNull(reading.action());
  }

  @Test
  public void worldUnlockIsNeverTreatedAsPlay() {
    ScreenInterpreter.Reading reading =
        interpreter.read(
            Fixtures.menu(1000, Fixtures.token("Lost Scrapyard"), Fixtures.token("Unlock")),
            false,
            false);
    assertEquals(ScreenInterpreter.Screen.UNSAFE, reading.screen());
    assertNull(reading.action());
  }

  @Test
  public void worldNameAloneCannotTapUnrelatedPlayButton() {
    ScreenInterpreter.Reading reading =
        interpreter.read(
            Fixtures.menu(1000, Fixtures.token("Lost Scrapyard"), Fixtures.token("Play")),
            false,
            false);
    assertEquals(ScreenInterpreter.Screen.WORLD, reading.screen());
    assertEquals("lost scrapyard", reading.action().normalized());
  }

  @Test
  public void staleResultTextDoesNotOverrideFreshVisualGameObservation() {
    ScreenInterpreter.Reading reading =
        interpreter.read(
            Fixtures.observation(
                5000,
                1000,
                Fixtures.game(),
                Fixtures.token("Destroyed"),
                Fixtures.token("Continue")),
            false,
            false);
    assertEquals(ScreenInterpreter.Screen.GAME, reading.screen());
    assertNull(reading.action());
  }

  @Test
  public void lowConfidenceVisualCannotAuthorizeMovement() {
    Vision.Result uncertain =
        new Vision.Result(true, new Point(.5, .5), .76, true, false, false, false, .9, 0, 0);
    assertEquals(
        ScreenInterpreter.Screen.UNKNOWN,
        interpreter.read(Fixtures.observation(1000, -1, uncertain), false, false).screen());
  }

  @Test
  public void exactButtonsDoNotMatchPromotionalPhrases() {
    ScreenInterpreter.Reading reading =
        interpreter.read(
            Fixtures.menu(1000, Fixtures.token("Run ended"), Fixtures.token("Continue shopping")),
            false,
            false);
    assertEquals(ScreenInterpreter.Screen.RESULTS, reading.screen());
    assertNull(reading.action());
  }
}
