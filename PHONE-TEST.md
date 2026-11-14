# Phone test

Device this build is for: Samsung Galaxy Z Fold 8, One UI 9, Android 17. Run the whole sequence on the inner display, then again on the cover display. Fold and unfold once while a line is scrolling.

One UI pop-ups, floating notifications, and edge lighting are in scope. Write down any of them the quiet rule does not stop. Do not cancel or snooze the original notification to hide one. A pop-up that is not an AOSP heads-up can still get through on Samsung, and that result is a note, not a reason to change this version.

The strip should stay about 32dp, sit under the status bar and the camera cutout, and ignore taps on both displays. If it stays on the screen that just turned off, record that too.

This file is the checklist. It has not been run on the phone yet.

## Steps

1. Grant all four settings. Select YouTube. Turn Ticker on. Preview the strip over the setup screen. The preview does not turn the quiet rule on.
2. Play a YouTube video. The video’s sound continues. The strip is absent until some other app posts an alert.
3. Send a chat message from another phone. Record whether a heads-up appeared, whether the phone made a sound or vibrated, and whether the strip showed the message. Open the shade and confirm the original notification is still there, with its actions. Also record any One UI pop-up, floating window, or edge lighting.
4. Leave YouTube. The strip is gone, and Modes / Do Not Disturb no longer shows this rule as active. Send another message and confirm a normal banner and sound.
5. While a video is playing, trigger an alarm and place a phone call. Both should still interrupt.
6. Lock the phone while a message is on the strip. The strip must not remain on the lock screen. Check this on the display that is actually on, inner or cover.
7. Force-stop Ticker while a video is playing and the rule is active. Launch Ticker again. The rule must be inactive unless YouTube is still the foreground app.
8. Rotate the phone, and fold or unfold it, during a scroll. The strip stays thin, stays under the status bar, and still does not eat taps.
9. Repeat step 3 on the other display. Write down any alert type that still banners.

Done means this list passed on the Fold, with a written note of anything that still banners.
