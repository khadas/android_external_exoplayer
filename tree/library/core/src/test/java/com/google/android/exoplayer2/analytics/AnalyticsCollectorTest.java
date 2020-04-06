/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.analytics;

import static com.google.common.truth.Truth.assertThat;

import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.Timeline.Window;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.source.ConcatenatingMediaSource;
import com.google.android.exoplayer2.source.LoadEventInfo;
import com.google.android.exoplayer2.source.MediaLoadData;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MediaSource.MediaPeriodId;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.ads.AdPlaybackState;
import com.google.android.exoplayer2.testutil.ActionSchedule;
import com.google.android.exoplayer2.testutil.ActionSchedule.PlayerRunnable;
import com.google.android.exoplayer2.testutil.ExoPlayerTestRunner;
import com.google.android.exoplayer2.testutil.FakeMediaSource;
import com.google.android.exoplayer2.testutil.FakeRenderer;
import com.google.android.exoplayer2.testutil.FakeTimeline;
import com.google.android.exoplayer2.testutil.FakeTimeline.TimelineWindowDefinition;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.LooperMode.Mode;

/** Integration test for {@link AnalyticsCollector}. */
@RunWith(AndroidJUnit4.class)
@LooperMode(Mode.PAUSED)
public final class AnalyticsCollectorTest {

  private static final int EVENT_PLAYER_STATE_CHANGED = 0;
  private static final int EVENT_TIMELINE_CHANGED = 1;
  private static final int EVENT_POSITION_DISCONTINUITY = 2;
  private static final int EVENT_SEEK_STARTED = 3;
  private static final int EVENT_SEEK_PROCESSED = 4;
  private static final int EVENT_PLAYBACK_PARAMETERS_CHANGED = 5;
  private static final int EVENT_REPEAT_MODE_CHANGED = 6;
  private static final int EVENT_SHUFFLE_MODE_CHANGED = 7;
  private static final int EVENT_LOADING_CHANGED = 8;
  private static final int EVENT_PLAYER_ERROR = 9;
  private static final int EVENT_TRACKS_CHANGED = 10;
  private static final int EVENT_LOAD_STARTED = 11;
  private static final int EVENT_LOAD_COMPLETED = 12;
  private static final int EVENT_LOAD_CANCELED = 13;
  private static final int EVENT_LOAD_ERROR = 14;
  private static final int EVENT_DOWNSTREAM_FORMAT_CHANGED = 15;
  private static final int EVENT_UPSTREAM_DISCARDED = 16;
  private static final int EVENT_MEDIA_PERIOD_CREATED = 17;
  private static final int EVENT_MEDIA_PERIOD_RELEASED = 18;
  private static final int EVENT_READING_STARTED = 19;
  private static final int EVENT_BANDWIDTH_ESTIMATE = 20;
  private static final int EVENT_SURFACE_SIZE_CHANGED = 21;
  private static final int EVENT_METADATA = 23;
  private static final int EVENT_DECODER_ENABLED = 24;
  private static final int EVENT_DECODER_INIT = 25;
  private static final int EVENT_DECODER_FORMAT_CHANGED = 26;
  private static final int EVENT_DECODER_DISABLED = 27;
  private static final int EVENT_AUDIO_SESSION_ID = 28;
  private static final int EVENT_AUDIO_UNDERRUN = 29;
  private static final int EVENT_DROPPED_VIDEO_FRAMES = 30;
  private static final int EVENT_VIDEO_SIZE_CHANGED = 31;
  private static final int EVENT_RENDERED_FIRST_FRAME = 32;
  private static final int EVENT_DRM_KEYS_LOADED = 33;
  private static final int EVENT_DRM_ERROR = 34;
  private static final int EVENT_DRM_KEYS_RESTORED = 35;
  private static final int EVENT_DRM_KEYS_REMOVED = 36;
  private static final int EVENT_DRM_SESSION_ACQUIRED = 37;
  private static final int EVENT_DRM_SESSION_RELEASED = 38;

  private static final int TIMEOUT_MS = 10000;
  private static final Timeline SINGLE_PERIOD_TIMELINE = new FakeTimeline(/* windowCount= */ 1);
  private static final EventWindowAndPeriodId WINDOW_0 =
      new EventWindowAndPeriodId(/* windowIndex= */ 0, /* mediaPeriodId= */ null);
  private static final EventWindowAndPeriodId WINDOW_1 =
      new EventWindowAndPeriodId(/* windowIndex= */ 1, /* mediaPeriodId= */ null);

  private EventWindowAndPeriodId period0;
  private EventWindowAndPeriodId period1;
  private EventWindowAndPeriodId period0Seq0;
  private EventWindowAndPeriodId period1Seq1;
  private EventWindowAndPeriodId period0Seq1;
  private EventWindowAndPeriodId period1Seq0;
  private EventWindowAndPeriodId period1Seq2;
  private EventWindowAndPeriodId window0Period1Seq0;
  private EventWindowAndPeriodId window1Period0Seq1;

  @Test
  public void testEmptyTimeline() throws Exception {
    FakeMediaSource mediaSource =
        new FakeMediaSource(
            Timeline.EMPTY,
            ExoPlayerTestRunner.Builder.VIDEO_FORMAT,
            ExoPlayerTestRunner.Builder.AUDIO_FORMAT);
    TestAnalyticsListener listener = runAnalyticsTest(mediaSource);

    assertThat(listener.getEvents(EVENT_PLAYER_STATE_CHANGED))
        .containsExactly(
            WINDOW_0 /* setPlayWhenReady */, WINDOW_0 /* BUFFERING */, WINDOW_0 /* ENDED */);
    assertThat(listener.getEvents(EVENT_TIMELINE_CHANGED))
        .containsExactly(WINDOW_0 /* PLAYLIST_CHANGED */, WINDOW_0 /* SOURCE_UPDATE */);
    listener.assertNoMoreEvents();
  }

  @Test
  public void testSinglePeriod() throws Exception {
    FakeMediaSource mediaSource =
        new FakeMediaSource(
            SINGLE_PERIOD_TIMELINE,
            ExoPlayerTestRunner.Builder.VIDEO_FORMAT,
            ExoPlayerTestRunner.Builder.AUDIO_FORMAT);
    TestAnalyticsListener listener = runAnalyticsTest(mediaSource);

    populateEventIds(listener.lastReportedTimeline);
    assertThat(listener.getEvents(EVENT_PLAYER_STATE_CHANGED))
        .containsExactly(
            WINDOW_0 /* setPlayWhenReady */,
            WINDOW_0 /* BUFFERING */,
            period0 /* READY */,
            period0 /* ENDED */);
    assertThat(listener.getEvents(EVENT_TIMELINE_CHANGED))
        .containsExactly(WINDOW_0 /* PLAYLIST_CHANGED */, WINDOW_0 /* SOURCE_UPDATE */);
    assertThat(listener.getEvents(EVENT_LOADING_CHANGED))
        .containsExactly(period0 /* started */, period0 /* stopped */);
    assertThat(listener.getEvents(EVENT_TRACKS_CHANGED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_LOAD_STARTED))
        .containsExactly(WINDOW_0 /* manifest */, period0 /* media */);
    assertThat(listener.getEvents(EVENT_LOAD_COMPLETED))
        .containsExactly(WINDOW_0 /* manifest */, period0 /* media */);
    assertThat(listener.getEvents(EVENT_DOWNSTREAM_FORMAT_CHANGED))
        .containsExactly(period0 /* audio */, period0 /* video */);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_CREATED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_READING_STARTED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_DECODER_ENABLED))
        .containsExactly(period0 /* audio */, period0 /* video */);
    assertThat(listener.getEvents(EVENT_DECODER_INIT))
        .containsExactly(period0 /* audio */, period0 /* video */);
    assertThat(listener.getEvents(EVENT_DECODER_FORMAT_CHANGED))
        .containsExactly(period0 /* audio */, period0 /* video */);
    assertThat(listener.getEvents(EVENT_AUDIO_SESSION_ID)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_DROPPED_VIDEO_FRAMES)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_VIDEO_SIZE_CHANGED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_RENDERED_FIRST_FRAME)).containsExactly(period0);
    listener.assertNoMoreEvents();
  }

  @Test
  public void testAutomaticPeriodTransition() throws Exception {
    MediaSource mediaSource =
        new ConcatenatingMediaSource(
            new FakeMediaSource(
                SINGLE_PERIOD_TIMELINE,
                ExoPlayerTestRunner.Builder.VIDEO_FORMAT,
                ExoPlayerTestRunner.Builder.AUDIO_FORMAT),
            new FakeMediaSource(
                SINGLE_PERIOD_TIMELINE,
                ExoPlayerTestRunner.Builder.VIDEO_FORMAT,
                ExoPlayerTestRunner.Builder.AUDIO_FORMAT));
    TestAnalyticsListener listener = runAnalyticsTest(mediaSource);

    populateEventIds(listener.lastReportedTimeline);
    assertThat(listener.getEvents(EVENT_PLAYER_STATE_CHANGED))
        .containsExactly(
            WINDOW_0 /* setPlayWhenReady */,
            WINDOW_0 /* BUFFERING */,
            period0 /* READY */,
            period1 /* ENDED */);
    assertThat(listener.getEvents(EVENT_TIMELINE_CHANGED))
        .containsExactly(WINDOW_0 /* PLAYLIST_CHANGED */, period0 /* SOURCE_UPDATE */);
    assertThat(listener.getEvents(EVENT_POSITION_DISCONTINUITY)).containsExactly(period1);
    assertThat(listener.getEvents(EVENT_LOADING_CHANGED))
        .containsExactly(period0, period0, period0, period0);
    assertThat(listener.getEvents(EVENT_TRACKS_CHANGED)).containsExactly(period0, period1);
    assertThat(listener.getEvents(EVENT_LOAD_STARTED))
        .containsExactly(
            WINDOW_0 /* manifest */,
            period0 /* media */,
            WINDOW_1 /* manifest */,
            period1 /* media */);
    assertThat(listener.getEvents(EVENT_LOAD_COMPLETED))
        .containsExactly(
            WINDOW_0 /* manifest */,
            period0 /* media */,
            WINDOW_1 /* manifest */,
            period1 /* media */);
    assertThat(listener.getEvents(EVENT_DOWNSTREAM_FORMAT_CHANGED))
        .containsExactly(
            period0 /* audio */, period0 /* video */, period1 /* audio */, period1 /* video */);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_CREATED)).containsExactly(period0, period1);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_RELEASED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_READING_STARTED)).containsExactly(period0, period1);
    assertThat(listener.getEvents(EVENT_DECODER_ENABLED))
        .containsExactly(period0 /* audio */, period0 /* video */);
    assertThat(listener.getEvents(EVENT_DECODER_INIT))
        .containsExactly(
            period0 /* audio */, period0 /* video */, period1 /* audio */, period1 /* video */);
    assertThat(listener.getEvents(EVENT_DECODER_FORMAT_CHANGED))
        .containsExactly(
            period0 /* audio */, period0 /* video */, period1 /* audio */, period1 /* video */);
    assertThat(listener.getEvents(EVENT_AUDIO_SESSION_ID)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_DROPPED_VIDEO_FRAMES)).containsExactly(period1);
    assertThat(listener.getEvents(EVENT_VIDEO_SIZE_CHANGED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_RENDERED_FIRST_FRAME)).containsExactly(period0);
    listener.assertNoMoreEvents();
  }

  @Test
  public void testPeriodTransitionWithRendererChange() throws Exception {
    MediaSource mediaSource =
        new ConcatenatingMediaSource(
            new FakeMediaSource(SINGLE_PERIOD_TIMELINE, ExoPlayerTestRunner.Builder.VIDEO_FORMAT),
            new FakeMediaSource(SINGLE_PERIOD_TIMELINE, ExoPlayerTestRunner.Builder.AUDIO_FORMAT));
    TestAnalyticsListener listener = runAnalyticsTest(mediaSource);

    populateEventIds(listener.lastReportedTimeline);
    assertThat(listener.getEvents(EVENT_PLAYER_STATE_CHANGED))
        .containsExactly(
            WINDOW_0 /* setPlayWhenReady */,
            WINDOW_0 /* BUFFERING */,
            period0 /* READY */,
            period1 /* BUFFERING */,
            period1 /* READY */,
            period1 /* ENDED */);
    assertThat(listener.getEvents(EVENT_TIMELINE_CHANGED))
        .containsExactly(WINDOW_0 /* PLAYLIST_CHANGED */, period0 /* SOURCE_UPDATE */);
    assertThat(listener.getEvents(EVENT_POSITION_DISCONTINUITY)).containsExactly(period1);
    assertThat(listener.getEvents(EVENT_LOADING_CHANGED))
        .containsExactly(period0, period0, period0, period0);
    assertThat(listener.getEvents(EVENT_TRACKS_CHANGED)).containsExactly(period0, period1);
    assertThat(listener.getEvents(EVENT_LOAD_STARTED))
        .containsExactly(
            WINDOW_0 /* manifest */,
            period0 /* media */,
            WINDOW_1 /* manifest */,
            period1 /* media */);
    assertThat(listener.getEvents(EVENT_LOAD_COMPLETED))
        .containsExactly(
            WINDOW_0 /* manifest */,
            period0 /* media */,
            WINDOW_1 /* manifest */,
            period1 /* media */);
    assertThat(listener.getEvents(EVENT_DOWNSTREAM_FORMAT_CHANGED))
        .containsExactly(period0 /* video */, period1 /* audio */);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_CREATED)).containsExactly(period0, period1);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_RELEASED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_READING_STARTED)).containsExactly(period0, period1);
    assertThat(listener.getEvents(EVENT_DECODER_ENABLED))
        .containsExactly(period0 /* video */, period1 /* audio */);
    assertThat(listener.getEvents(EVENT_DECODER_INIT))
        .containsExactly(period0 /* video */, period1 /* audio */);
    assertThat(listener.getEvents(EVENT_DECODER_FORMAT_CHANGED))
        .containsExactly(period0 /* video */, period1 /* audio */);
    assertThat(listener.getEvents(EVENT_DECODER_DISABLED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_AUDIO_SESSION_ID)).containsExactly(period1);
    assertThat(listener.getEvents(EVENT_DROPPED_VIDEO_FRAMES)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_VIDEO_SIZE_CHANGED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_RENDERED_FIRST_FRAME)).containsExactly(period0);
    listener.assertNoMoreEvents();
  }

  @Test
  public void testSeekToOtherPeriod() throws Exception {
    MediaSource mediaSource =
        new ConcatenatingMediaSource(
            new FakeMediaSource(SINGLE_PERIOD_TIMELINE, ExoPlayerTestRunner.Builder.VIDEO_FORMAT),
            new FakeMediaSource(SINGLE_PERIOD_TIMELINE, ExoPlayerTestRunner.Builder.AUDIO_FORMAT));
    ActionSchedule actionSchedule =
        new ActionSchedule.Builder("AnalyticsCollectorTest")
            .pause()
            .waitForPlaybackState(Player.STATE_READY)
            .seek(/* windowIndex= */ 1, /* positionMs= */ 0)
            .play()
            .build();
    TestAnalyticsListener listener = runAnalyticsTest(mediaSource, actionSchedule);

    populateEventIds(listener.lastReportedTimeline);
    assertThat(listener.getEvents(EVENT_PLAYER_STATE_CHANGED))
        .containsExactly(
            WINDOW_0 /* setPlayWhenReady=true */,
            WINDOW_0 /* BUFFERING */,
            WINDOW_0 /* setPlayWhenReady=false */,
            period0 /* READY */,
            period1 /* BUFFERING */,
            period1 /* READY */,
            period1 /* setPlayWhenReady=true */,
            period1 /* ENDED */);
    assertThat(listener.getEvents(EVENT_TIMELINE_CHANGED))
        .containsExactly(WINDOW_0 /* PLAYLIST_CHANGED */, period0 /* SOURCE_UPDATE */);
    assertThat(listener.getEvents(EVENT_POSITION_DISCONTINUITY)).containsExactly(period1);
    assertThat(listener.getEvents(EVENT_SEEK_STARTED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_SEEK_PROCESSED)).containsExactly(period1);
    List<EventWindowAndPeriodId> loadingEvents = listener.getEvents(EVENT_LOADING_CHANGED);
    assertThat(loadingEvents).hasSize(4);
    assertThat(loadingEvents).containsAtLeast(period0, period0);
    assertThat(listener.getEvents(EVENT_TRACKS_CHANGED)).containsExactly(period0, period1);
    assertThat(listener.getEvents(EVENT_LOAD_STARTED))
        .containsExactly(
            WINDOW_0 /* manifest */,
            period0 /* media */,
            WINDOW_1 /* manifest */,
            period1 /* media */);
    assertThat(listener.getEvents(EVENT_LOAD_COMPLETED))
        .containsExactly(
            WINDOW_0 /* manifest */,
            period0 /* media */,
            WINDOW_1 /* manifest */,
            period1 /* media */);
    assertThat(listener.getEvents(EVENT_DOWNSTREAM_FORMAT_CHANGED))
        .containsExactly(period0 /* video */, period1 /* audio */);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_CREATED)).containsExactly(period0, period1);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_RELEASED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_READING_STARTED)).containsExactly(period0, period1);
    assertThat(listener.getEvents(EVENT_DECODER_ENABLED))
        .containsExactly(period0 /* video */, period1 /* audio */);
    assertThat(listener.getEvents(EVENT_DECODER_INIT))
        .containsExactly(period0 /* video */, period1 /* audio */);
    assertThat(listener.getEvents(EVENT_DECODER_FORMAT_CHANGED))
        .containsExactly(period0 /* video */, period1 /* audio */);
    assertThat(listener.getEvents(EVENT_DECODER_DISABLED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_AUDIO_SESSION_ID)).containsExactly(period1);
    assertThat(listener.getEvents(EVENT_VIDEO_SIZE_CHANGED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_RENDERED_FIRST_FRAME)).containsExactly(period0);
    listener.assertNoMoreEvents();
  }

  @Test
  public void testSeekBackAfterReadingAhead() throws Exception {
    MediaSource mediaSource =
        new ConcatenatingMediaSource(
            new FakeMediaSource(SINGLE_PERIOD_TIMELINE, ExoPlayerTestRunner.Builder.VIDEO_FORMAT),
            new FakeMediaSource(
                SINGLE_PERIOD_TIMELINE,
                ExoPlayerTestRunner.Builder.VIDEO_FORMAT,
                ExoPlayerTestRunner.Builder.AUDIO_FORMAT));
    long periodDurationMs =
        SINGLE_PERIOD_TIMELINE.getWindow(/* windowIndex= */ 0, new Window()).getDurationMs();
    ActionSchedule actionSchedule =
        new ActionSchedule.Builder("AnalyticsCollectorTest")
            .pause()
            .waitForPlaybackState(Player.STATE_READY)
            .playUntilPosition(/* windowIndex= */ 0, periodDurationMs)
            .seekAndWait(/* positionMs= */ 0)
            .play()
            .build();
    TestAnalyticsListener listener = runAnalyticsTest(mediaSource, actionSchedule);

    populateEventIds(listener.lastReportedTimeline);
    assertThat(listener.getEvents(EVENT_PLAYER_STATE_CHANGED))
        .containsExactly(
            WINDOW_0 /* setPlayWhenReady=true */,
            WINDOW_0 /* setPlayWhenReady=false */,
            WINDOW_0 /* BUFFERING */,
            period0 /* READY */,
            period0 /* setPlayWhenReady=true */,
            period0 /* setPlayWhenReady=false */,
            period0 /* BUFFERING */,
            period0 /* READY */,
            period0 /* setPlayWhenReady=true */,
            period1Seq2 /* BUFFERING */,
            period1Seq2 /* READY */,
            period1Seq2 /* ENDED */);
    assertThat(listener.getEvents(EVENT_TIMELINE_CHANGED))
        .containsExactly(WINDOW_0 /* PLAYLIST_CHANGED */, period0 /* SOURCE_UPDATE */);
    assertThat(listener.getEvents(EVENT_POSITION_DISCONTINUITY))
        .containsExactly(period0, period1Seq2);
    assertThat(listener.getEvents(EVENT_SEEK_STARTED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_SEEK_PROCESSED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_LOADING_CHANGED))
        .containsExactly(period0, period0, period0, period0, period0, period0);
    assertThat(listener.getEvents(EVENT_TRACKS_CHANGED)).containsExactly(period0, period1Seq2);
    assertThat(listener.getEvents(EVENT_LOAD_STARTED))
        .containsExactly(
            WINDOW_0 /* manifest */,
            period0 /* media */,
            WINDOW_1 /* manifest */,
            period1Seq1 /* media */,
            period1Seq2 /* media */);
    assertThat(listener.getEvents(EVENT_LOAD_COMPLETED))
        .containsExactly(
            WINDOW_0 /* manifest */,
            period0 /* media */,
            WINDOW_1 /* manifest */,
            period1Seq1 /* media */,
            period1Seq2 /* media */);
    assertThat(listener.getEvents(EVENT_DOWNSTREAM_FORMAT_CHANGED))
        .containsExactly(period0, period1Seq1, period1Seq2, period1Seq2);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_CREATED))
        .containsExactly(period0, period1Seq1, period1Seq2);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_RELEASED))
        .containsExactly(period0, period1Seq1);
    assertThat(listener.getEvents(EVENT_READING_STARTED))
        .containsExactly(period0, period1Seq1, period1Seq2);
    assertThat(listener.getEvents(EVENT_DECODER_ENABLED))
        .containsExactly(period0, period0, period1Seq2);
    assertThat(listener.getEvents(EVENT_DECODER_INIT))
        .containsExactly(period0, period1Seq1, period1Seq2, period1Seq2);
    assertThat(listener.getEvents(EVENT_DECODER_FORMAT_CHANGED))
        .containsExactly(period0, period1Seq1, period1Seq2, period1Seq2);
    assertThat(listener.getEvents(EVENT_DECODER_DISABLED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_AUDIO_SESSION_ID)).containsExactly(period1Seq2);
    assertThat(listener.getEvents(EVENT_DROPPED_VIDEO_FRAMES))
        .containsExactly(period0, period1Seq2, period1Seq2);
    assertThat(listener.getEvents(EVENT_VIDEO_SIZE_CHANGED)).containsExactly(period0, period0);
    assertThat(listener.getEvents(EVENT_RENDERED_FIRST_FRAME)).containsExactly(period0, period0);
    listener.assertNoMoreEvents();
  }

  @Test
  public void testPrepareNewSource() throws Exception {
    MediaSource mediaSource1 =
        new FakeMediaSource(SINGLE_PERIOD_TIMELINE, ExoPlayerTestRunner.Builder.VIDEO_FORMAT);
    MediaSource mediaSource2 =
        new FakeMediaSource(SINGLE_PERIOD_TIMELINE, ExoPlayerTestRunner.Builder.VIDEO_FORMAT);
    ActionSchedule actionSchedule =
        new ActionSchedule.Builder("AnalyticsCollectorTest")
            .pause()
            .waitForPlaybackState(Player.STATE_READY)
            .setMediaSources(/* resetPosition= */ false, mediaSource2)
            .play()
            .build();
    TestAnalyticsListener listener = runAnalyticsTest(mediaSource1, actionSchedule);

    // Populate all event ids with last timeline (after second prepare).
    populateEventIds(listener.lastReportedTimeline);
    // Populate event id of period 0, sequence 0 with timeline of initial preparation.
    period0Seq0 =
        new EventWindowAndPeriodId(
            /* windowIndex= */ 0,
            new MediaPeriodId(
                listener.reportedTimelines.get(1).getUidOfPeriod(/* periodIndex= */ 0),
                /* windowSequenceNumber= */ 0));
    assertThat(listener.getEvents(EVENT_PLAYER_STATE_CHANGED))
        .containsExactly(
            WINDOW_0 /* setPlayWhenReady=true */,
            WINDOW_0 /* BUFFERING */,
            WINDOW_0 /* setPlayWhenReady=false */,
            period0Seq0 /* READY */,
            WINDOW_0 /* BUFFERING */,
            WINDOW_0 /* setPlayWhenReady=true */,
            period0Seq1 /* READY */,
            period0Seq1 /* ENDED */);
    assertThat(listener.getEvents(EVENT_TIMELINE_CHANGED))
        .containsExactly(
            WINDOW_0 /* PLAYLIST_CHANGE */,
            WINDOW_0 /* SOURCE_UPDATE */,
            WINDOW_0 /* PLAYLIST_CHANGE */,
            WINDOW_0 /* SOURCE_UPDATE */);
    assertThat(listener.getEvents(EVENT_LOADING_CHANGED))
        .containsExactly(period0Seq0, period0Seq0, period0Seq1, period0Seq1);
    assertThat(listener.getEvents(EVENT_TRACKS_CHANGED))
        .containsExactly(
            period0Seq0 /* prepared */, WINDOW_0 /* setMediaSources */, period0Seq1 /* prepared */);
    assertThat(listener.getEvents(EVENT_LOAD_STARTED))
        .containsExactly(
            WINDOW_0 /* manifest */,
            period0Seq0 /* media */,
            WINDOW_0 /* manifest */,
            period0Seq1 /* media */);
    assertThat(listener.getEvents(EVENT_LOAD_COMPLETED))
        .containsExactly(
            WINDOW_0 /* manifest */,
            period0Seq0 /* media */,
            WINDOW_0 /* manifest */,
            period0Seq1 /* media */);
    assertThat(listener.getEvents(EVENT_DOWNSTREAM_FORMAT_CHANGED))
        .containsExactly(period0Seq0, period0Seq1);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_CREATED))
        .containsExactly(period0Seq0, period0Seq1);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_RELEASED)).containsExactly(period0Seq0);
    assertThat(listener.getEvents(EVENT_READING_STARTED)).containsExactly(period0Seq0, period0Seq1);
    assertThat(listener.getEvents(EVENT_DECODER_ENABLED)).containsExactly(period0Seq0, period0Seq1);
    assertThat(listener.getEvents(EVENT_DECODER_INIT)).containsExactly(period0Seq0, period0Seq1);
    assertThat(listener.getEvents(EVENT_DECODER_FORMAT_CHANGED))
        .containsExactly(period0Seq0, period0Seq1);
    assertThat(listener.getEvents(EVENT_DECODER_DISABLED)).containsExactly(period0Seq0);
    assertThat(listener.getEvents(EVENT_DROPPED_VIDEO_FRAMES)).containsExactly(period0Seq1);
    assertThat(listener.getEvents(EVENT_VIDEO_SIZE_CHANGED))
        .containsExactly(period0Seq0, period0Seq1);
    assertThat(listener.getEvents(EVENT_RENDERED_FIRST_FRAME))
        .containsExactly(period0Seq0, period0Seq1);
    listener.assertNoMoreEvents();
  }

  @Test
  public void testReprepareAfterError() throws Exception {
    MediaSource mediaSource =
        new FakeMediaSource(SINGLE_PERIOD_TIMELINE, ExoPlayerTestRunner.Builder.VIDEO_FORMAT);
    ActionSchedule actionSchedule =
        new ActionSchedule.Builder("AnalyticsCollectorTest")
            .pause()
            .waitForPlaybackState(Player.STATE_READY)
            .throwPlaybackException(ExoPlaybackException.createForSource(new IOException()))
            .waitForPlaybackState(Player.STATE_IDLE)
            .seek(/* positionMs= */ 0)
            .prepare()
            .play()
            .waitForPlaybackState(Player.STATE_ENDED)
            .build();
    TestAnalyticsListener listener = runAnalyticsTest(mediaSource, actionSchedule);

    populateEventIds(listener.lastReportedTimeline);
    assertThat(listener.getEvents(EVENT_PLAYER_STATE_CHANGED))
        .containsExactly(
            WINDOW_0 /* setPlayWhenReady=true */,
            WINDOW_0 /* setPlayWhenReady=false */,
            WINDOW_0 /* BUFFERING */,
            period0Seq0 /* READY */,
            period0Seq0 /* IDLE */,
            period0Seq0 /* BUFFERING */,
            period0Seq0 /* setPlayWhenReady=true */,
            period0Seq0 /* READY */,
            period0Seq0 /* ENDED */);
    assertThat(listener.getEvents(EVENT_TIMELINE_CHANGED))
        .containsExactly(WINDOW_0 /* prepared */, WINDOW_0 /* prepared */);
    assertThat(listener.getEvents(EVENT_POSITION_DISCONTINUITY)).containsExactly(period0Seq0);
    assertThat(listener.getEvents(EVENT_SEEK_STARTED)).containsExactly(period0Seq0);
    assertThat(listener.getEvents(EVENT_SEEK_PROCESSED)).containsExactly(period0Seq0);
    assertThat(listener.getEvents(EVENT_LOADING_CHANGED))
        .containsExactly(period0Seq0, period0Seq0, period0Seq0, period0Seq0);
    assertThat(listener.getEvents(EVENT_PLAYER_ERROR)).containsExactly(period0Seq0);
    assertThat(listener.getEvents(EVENT_TRACKS_CHANGED)).containsExactly(period0Seq0, period0Seq0);
    assertThat(listener.getEvents(EVENT_LOAD_STARTED))
        .containsExactly(
            WINDOW_0 /* manifest */,
            period0Seq0 /* media */,
            WINDOW_0 /* manifest */,
            period0Seq0 /* media */);
    assertThat(listener.getEvents(EVENT_LOAD_COMPLETED))
        .containsExactly(
            WINDOW_0 /* manifest */,
            period0Seq0 /* media */,
            WINDOW_0 /* manifest */,
            period0Seq0 /* media */);
    assertThat(listener.getEvents(EVENT_DOWNSTREAM_FORMAT_CHANGED))
        .containsExactly(period0Seq0, period0Seq0);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_CREATED))
        .containsExactly(period0Seq0, period0Seq0);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_RELEASED)).containsExactly(period0Seq0);
    assertThat(listener.getEvents(EVENT_READING_STARTED)).containsExactly(period0Seq0, period0Seq0);
    assertThat(listener.getEvents(EVENT_DECODER_ENABLED)).containsExactly(period0Seq0, period0Seq0);
    assertThat(listener.getEvents(EVENT_DECODER_INIT)).containsExactly(period0Seq0, period0Seq0);
    assertThat(listener.getEvents(EVENT_DECODER_FORMAT_CHANGED))
        .containsExactly(period0Seq0, period0Seq0);
    assertThat(listener.getEvents(EVENT_DECODER_DISABLED)).containsExactly(period0Seq0);
    assertThat(listener.getEvents(EVENT_DROPPED_VIDEO_FRAMES)).containsExactly(period0Seq0);
    assertThat(listener.getEvents(EVENT_VIDEO_SIZE_CHANGED))
        .containsExactly(period0Seq0, period0Seq0);
    assertThat(listener.getEvents(EVENT_RENDERED_FIRST_FRAME))
        .containsExactly(period0Seq0, period0Seq0);
    listener.assertNoMoreEvents();
  }

  @Test
  public void testDynamicTimelineChange() throws Exception {
    MediaSource childMediaSource =
        new FakeMediaSource(SINGLE_PERIOD_TIMELINE, ExoPlayerTestRunner.Builder.VIDEO_FORMAT);
    final ConcatenatingMediaSource concatenatedMediaSource =
        new ConcatenatingMediaSource(childMediaSource, childMediaSource);
    long periodDurationMs =
        SINGLE_PERIOD_TIMELINE.getWindow(/* windowIndex= */ 0, new Window()).getDurationMs();
    ActionSchedule actionSchedule =
        new ActionSchedule.Builder("AnalyticsCollectorTest")
            .pause()
            .waitForPlaybackState(Player.STATE_READY)
            // Ensure second period is already being read from.
            .playUntilPosition(/* windowIndex= */ 0, /* positionMs= */ periodDurationMs)
            .executeRunnable(
                () ->
                    concatenatedMediaSource.moveMediaSource(
                        /* currentIndex= */ 0, /* newIndex= */ 1))
            .waitForTimelineChanged()
            .play()
            .build();
    TestAnalyticsListener listener = runAnalyticsTest(concatenatedMediaSource, actionSchedule);

    populateEventIds(listener.lastReportedTimeline);
    assertThat(listener.getEvents(EVENT_PLAYER_STATE_CHANGED))
        .containsExactly(
            WINDOW_0 /* setPlayWhenReady=true */,
            WINDOW_0 /* setPlayWhenReady=false */,
            WINDOW_0 /* BUFFERING */,
            window0Period1Seq0 /* READY */,
            window0Period1Seq0 /* setPlayWhenReady=true */,
            window0Period1Seq0 /* setPlayWhenReady=false */,
            period1Seq0 /* setPlayWhenReady=true */,
            period1Seq0 /* BUFFERING */,
            period1Seq0 /* ENDED */);
    assertThat(listener.getEvents(EVENT_TIMELINE_CHANGED))
        .containsExactly(
            WINDOW_0 /* PLAYLIST_CHANGED */,
            window0Period1Seq0 /* SOURCE_UPDATE (concatenated timeline replaces dummy) */,
            period1Seq0 /* SOURCE_UPDATE (child sources in concatenating source moved) */);
    assertThat(listener.getEvents(EVENT_LOADING_CHANGED))
        .containsExactly(
            window0Period1Seq0, window0Period1Seq0, window0Period1Seq0, window0Period1Seq0);
    assertThat(listener.getEvents(EVENT_TRACKS_CHANGED)).containsExactly(window0Period1Seq0);
    assertThat(listener.getEvents(EVENT_LOAD_STARTED))
        .containsExactly(
            WINDOW_0 /* manifest */,
            window0Period1Seq0 /* media */,
            window1Period0Seq1 /* media */);
    assertThat(listener.getEvents(EVENT_LOAD_COMPLETED))
        .containsExactly(
            WINDOW_0 /* manifest */,
            window0Period1Seq0 /* media */,
            window1Period0Seq1 /* media */);
    assertThat(listener.getEvents(EVENT_DOWNSTREAM_FORMAT_CHANGED))
        .containsExactly(window0Period1Seq0, window1Period0Seq1);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_CREATED))
        .containsExactly(window0Period1Seq0, window1Period0Seq1);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_RELEASED)).containsExactly(window1Period0Seq1);
    assertThat(listener.getEvents(EVENT_READING_STARTED))
        .containsExactly(window0Period1Seq0, window1Period0Seq1);
    assertThat(listener.getEvents(EVENT_DECODER_ENABLED))
        .containsExactly(window0Period1Seq0, window0Period1Seq0);
    assertThat(listener.getEvents(EVENT_DECODER_INIT))
        .containsExactly(window0Period1Seq0, window1Period0Seq1);
    assertThat(listener.getEvents(EVENT_DECODER_FORMAT_CHANGED))
        .containsExactly(window0Period1Seq0, window1Period0Seq1);
    assertThat(listener.getEvents(EVENT_DECODER_DISABLED)).containsExactly(window0Period1Seq0);
    assertThat(listener.getEvents(EVENT_DROPPED_VIDEO_FRAMES)).containsExactly(window0Period1Seq0);
    assertThat(listener.getEvents(EVENT_VIDEO_SIZE_CHANGED)).containsExactly(window0Period1Seq0);
    assertThat(listener.getEvents(EVENT_RENDERED_FIRST_FRAME)).containsExactly(window0Period1Seq0);
    listener.assertNoMoreEvents();
  }

  @Test
  public void testPlaylistOperations() throws Exception {
    MediaSource fakeMediaSource =
        new FakeMediaSource(SINGLE_PERIOD_TIMELINE, ExoPlayerTestRunner.Builder.VIDEO_FORMAT);
    ActionSchedule actionSchedule =
        new ActionSchedule.Builder("AnalyticsCollectorTest")
            .pause()
            .waitForPlaybackState(Player.STATE_READY)
            .addMediaSources(fakeMediaSource)
            // Wait until second period has fully loaded to assert loading events without flakiness.
            .waitForIsLoading(true)
            .waitForIsLoading(false)
            .removeMediaItem(/* index= */ 0)
            .play()
            .build();
    TestAnalyticsListener listener = runAnalyticsTest(fakeMediaSource, actionSchedule);

    // Populate event ids with second to last timeline that still contained both periods.
    populateEventIds(listener.reportedTimelines.get(listener.reportedTimelines.size() - 2));
    // Expect the second period with window index 0 and increased window sequence after the removal
    // moved the period to another window index.
    period0Seq1 =
        new EventWindowAndPeriodId(
            /* windowIndex= */ 0,
            new MediaPeriodId(
                listener.lastReportedTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* windowSequenceNumber= */ 1));
    assertThat(listener.getEvents(EVENT_PLAYER_STATE_CHANGED))
        .containsExactly(
            WINDOW_0 /* setPlayWhenReady=true */,
            WINDOW_0 /* setPlayWhenReady=false */,
            WINDOW_0 /* BUFFERING */,
            period0Seq0 /* READY */,
            period0Seq1 /* BUFFERING */,
            period0Seq1 /* setPlayWhenReady=true */,
            period0Seq1 /* READY */,
            period0Seq1 /* ENDED */);
    assertThat(listener.getEvents(EVENT_TIMELINE_CHANGED))
        .containsExactly(
            WINDOW_0 /* PLAYLIST_CHANGED */,
            WINDOW_0 /* SOURCE_UPDATE (first item) */,
            period0Seq0 /* PLAYLIST_CHANGED (add) */,
            period0Seq0 /* SOURCE_UPDATE (second item) */,
            period0Seq1 /* PLAYLIST_CHANGED (remove) */);
    assertThat(listener.getEvents(EVENT_LOADING_CHANGED))
        .containsExactly(period0Seq0, period0Seq0, period0Seq0, period0Seq0);
    assertThat(listener.getEvents(EVENT_TRACKS_CHANGED)).containsExactly(period0Seq0, period0Seq1);
    assertThat(listener.getEvents(EVENT_LOAD_STARTED))
        .containsExactly(WINDOW_0 /* manifest */, period0Seq0 /* media */, period1Seq1 /* media */);
    assertThat(listener.getEvents(EVENT_LOAD_COMPLETED))
        .containsExactly(WINDOW_0 /* manifest */, period0Seq0 /* media */, period1Seq1 /* media */);
    assertThat(listener.getEvents(EVENT_DOWNSTREAM_FORMAT_CHANGED))
        .containsExactly(period0Seq0, period0Seq1);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_CREATED))
        .containsExactly(period0Seq0, period1Seq1);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_RELEASED)).containsExactly(period0Seq0);
    assertThat(listener.getEvents(EVENT_READING_STARTED)).containsExactly(period0Seq0, period0Seq1);
    assertThat(listener.getEvents(EVENT_DECODER_ENABLED)).containsExactly(period0Seq0, period0Seq1);
    assertThat(listener.getEvents(EVENT_DECODER_INIT)).containsExactly(period0Seq0, period0Seq1);
    assertThat(listener.getEvents(EVENT_DECODER_FORMAT_CHANGED))
        .containsExactly(period0Seq0, period0Seq1);
    assertThat(listener.getEvents(EVENT_DECODER_DISABLED)).containsExactly(period0Seq0);
    assertThat(listener.getEvents(EVENT_DROPPED_VIDEO_FRAMES)).containsExactly(period0Seq1);
    assertThat(listener.getEvents(EVENT_VIDEO_SIZE_CHANGED))
        .containsExactly(period0Seq0, period0Seq1);
    assertThat(listener.getEvents(EVENT_RENDERED_FIRST_FRAME))
        .containsExactly(period0Seq0, period0Seq1);
    listener.assertNoMoreEvents();
  }

  @Test
  public void testAdPlayback() throws Exception {
    long contentDurationsUs = 10 * C.MICROS_PER_SECOND;
    AtomicReference<AdPlaybackState> adPlaybackState =
        new AtomicReference<>(
            FakeTimeline.createAdPlaybackState(
                    /* adsPerAdGroup= */ 1, /* adGroupTimesUs...= */
                    0,
                    5 * C.MICROS_PER_SECOND,
                    C.TIME_END_OF_SOURCE)
                .withContentDurationUs(contentDurationsUs));
    AtomicInteger playedAdCount = new AtomicInteger(0);
    Timeline adTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ false,
                contentDurationsUs,
                adPlaybackState.get()));
    FakeMediaSource fakeMediaSource =
        new FakeMediaSource(adTimeline, ExoPlayerTestRunner.Builder.VIDEO_FORMAT);
    ActionSchedule actionSchedule =
        new ActionSchedule.Builder("AnalyticsCollectorTest")
            .executeRunnable(
                new PlayerRunnable() {
                  @Override
                  public void run(SimpleExoPlayer player) {
                    player.addListener(
                        new Player.EventListener() {
                          @Override
                          public void onPositionDiscontinuity(
                              @Player.DiscontinuityReason int reason) {
                            if (!player.isPlayingAd()
                                && reason == Player.DISCONTINUITY_REASON_AD_INSERTION) {
                              // Finished playing ad. Marked as played.
                              adPlaybackState.set(
                                  adPlaybackState
                                      .get()
                                      .withPlayedAd(
                                          playedAdCount.getAndIncrement(),
                                          /* adIndexInAdGroup= */ 0));
                              fakeMediaSource.setNewSourceInfo(
                                  new FakeTimeline(
                                      new TimelineWindowDefinition(
                                          /* periodCount= */ 1,
                                          /* id= */ 0,
                                          /* isSeekable= */ true,
                                          /* isDynamic= */ false,
                                          /* durationUs =*/ 10 * C.MICROS_PER_SECOND,
                                          adPlaybackState.get())),
                                  /* newManifest= */ null);
                            }
                          }
                        });
                  }
                })
            .pause()
            .waitForPlaybackState(Player.STATE_READY)
            // Wait in each content part to ensure previously triggered events get a chance to be
            // delivered. This prevents flakiness caused by playback progressing too fast.
            .playUntilPosition(/* windowIndex= */ 0, /* positionMs= */ 3_000)
            .playUntilPosition(/* windowIndex= */ 0, /* positionMs= */ 8_000)
            .play()
            .waitForPlaybackState(Player.STATE_ENDED)
            // Wait for final timeline change that marks post-roll played.
            .waitForTimelineChanged()
            .build();
    TestAnalyticsListener listener = runAnalyticsTest(fakeMediaSource, actionSchedule);

    Object periodUid = listener.lastReportedTimeline.getUidOfPeriod(/* periodIndex= */ 0);
    EventWindowAndPeriodId prerollAd =
        new EventWindowAndPeriodId(
            /* windowIndex= */ 0,
            new MediaPeriodId(
                periodUid,
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                /* windowSequenceNumber= */ 0));
    EventWindowAndPeriodId midrollAd =
        new EventWindowAndPeriodId(
            /* windowIndex= */ 0,
            new MediaPeriodId(
                periodUid,
                /* adGroupIndex= */ 1,
                /* adIndexInAdGroup= */ 0,
                /* windowSequenceNumber= */ 0));
    EventWindowAndPeriodId postrollAd =
        new EventWindowAndPeriodId(
            /* windowIndex= */ 0,
            new MediaPeriodId(
                periodUid,
                /* adGroupIndex= */ 2,
                /* adIndexInAdGroup= */ 0,
                /* windowSequenceNumber= */ 0));
    EventWindowAndPeriodId contentAfterPreroll =
        new EventWindowAndPeriodId(
            /* windowIndex= */ 0,
            new MediaPeriodId(periodUid, /* windowSequenceNumber= */ 0, /* nextAdGroupIndex= */ 1));
    EventWindowAndPeriodId contentAfterMidroll =
        new EventWindowAndPeriodId(
            /* windowIndex= */ 0,
            new MediaPeriodId(periodUid, /* windowSequenceNumber= */ 0, /* nextAdGroupIndex= */ 2));
    EventWindowAndPeriodId contentAfterPostroll =
        new EventWindowAndPeriodId(
            /* windowIndex= */ 0,
            new MediaPeriodId(
                periodUid, /* windowSequenceNumber= */ 0, /* nextAdGroupIndex= */ C.INDEX_UNSET));
    assertThat(listener.getEvents(EVENT_PLAYER_STATE_CHANGED))
        .containsExactly(
            WINDOW_0 /* setPlayWhenReady=true */,
            WINDOW_0 /* setPlayWhenReady=false */,
            WINDOW_0 /* BUFFERING */,
            prerollAd /* READY */,
            prerollAd /* setPlayWhenReady=true */,
            contentAfterPreroll /* setPlayWhenReady=false */,
            contentAfterPreroll /* setPlayWhenReady=true */,
            contentAfterMidroll /* setPlayWhenReady=false */,
            contentAfterMidroll /* setPlayWhenReady=true */,
            contentAfterPostroll /* ENDED */);
    assertThat(listener.getEvents(EVENT_TIMELINE_CHANGED))
        .containsExactly(
            WINDOW_0 /* PLAYLIST_CHANGED */,
            WINDOW_0 /* SOURCE_UPDATE (initial) */,
            contentAfterPreroll /* SOURCE_UPDATE (played preroll) */,
            contentAfterMidroll /* SOURCE_UPDATE (played midroll) */,
            contentAfterPostroll /* SOURCE_UPDATE (played postroll) */);
    assertThat(listener.getEvents(EVENT_POSITION_DISCONTINUITY))
        .containsExactly(
            contentAfterPreroll, midrollAd, contentAfterMidroll, postrollAd, contentAfterPostroll);
    assertThat(listener.getEvents(EVENT_LOADING_CHANGED))
        .containsExactly(
            prerollAd, prerollAd, prerollAd, prerollAd, prerollAd, prerollAd, prerollAd, prerollAd,
            prerollAd, prerollAd, prerollAd, prerollAd);
    assertThat(listener.getEvents(EVENT_TRACKS_CHANGED))
        .containsExactly(
            prerollAd,
            contentAfterPreroll,
            midrollAd,
            contentAfterMidroll,
            postrollAd,
            contentAfterPostroll);
    assertThat(listener.getEvents(EVENT_LOAD_STARTED))
        .containsExactly(
            WINDOW_0 /* content manifest */,
            WINDOW_0 /* preroll manifest */,
            prerollAd,
            contentAfterPreroll,
            WINDOW_0 /* midroll manifest */,
            midrollAd,
            contentAfterMidroll,
            WINDOW_0 /* postroll manifest */,
            postrollAd,
            contentAfterPostroll);
    assertThat(listener.getEvents(EVENT_LOAD_COMPLETED))
        .containsExactly(
            WINDOW_0 /* content manifest */,
            WINDOW_0 /* preroll manifest */,
            prerollAd,
            contentAfterPreroll,
            WINDOW_0 /* midroll manifest */,
            midrollAd,
            contentAfterMidroll,
            WINDOW_0 /* postroll manifest */,
            postrollAd,
            contentAfterPostroll);
    assertThat(listener.getEvents(EVENT_DOWNSTREAM_FORMAT_CHANGED))
        .containsExactly(
            prerollAd,
            contentAfterPreroll,
            midrollAd,
            contentAfterMidroll,
            postrollAd,
            contentAfterPostroll);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_CREATED))
        .containsExactly(
            prerollAd,
            contentAfterPreroll,
            midrollAd,
            contentAfterMidroll,
            postrollAd,
            contentAfterPostroll);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_RELEASED))
        .containsExactly(
            prerollAd, contentAfterPreroll, midrollAd, contentAfterMidroll, postrollAd);
    assertThat(listener.getEvents(EVENT_READING_STARTED))
        .containsExactly(
            prerollAd,
            contentAfterPreroll,
            midrollAd,
            contentAfterMidroll,
            postrollAd,
            contentAfterPostroll);
    assertThat(listener.getEvents(EVENT_DECODER_ENABLED)).containsExactly(prerollAd);
    assertThat(listener.getEvents(EVENT_DECODER_INIT))
        .containsExactly(
            prerollAd,
            contentAfterPreroll,
            midrollAd,
            contentAfterMidroll,
            postrollAd,
            contentAfterPostroll);
    assertThat(listener.getEvents(EVENT_DECODER_FORMAT_CHANGED))
        .containsExactly(
            prerollAd,
            contentAfterPreroll,
            midrollAd,
            contentAfterMidroll,
            postrollAd,
            contentAfterPostroll);
    assertThat(listener.getEvents(EVENT_DROPPED_VIDEO_FRAMES))
        .containsExactly(contentAfterPreroll, contentAfterMidroll, contentAfterPostroll);
    assertThat(listener.getEvents(EVENT_VIDEO_SIZE_CHANGED)).containsExactly(prerollAd);
    assertThat(listener.getEvents(EVENT_RENDERED_FIRST_FRAME)).containsExactly(prerollAd);
    listener.assertNoMoreEvents();
  }

  @Test
  public void testSeekAfterMidroll() throws Exception {
    Timeline adTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ false,
                10 * C.MICROS_PER_SECOND,
                FakeTimeline.createAdPlaybackState(
                    /* adsPerAdGroup= */ 1, /* adGroupTimesUs...= */ 5 * C.MICROS_PER_SECOND)));
    FakeMediaSource fakeMediaSource =
        new FakeMediaSource(adTimeline, ExoPlayerTestRunner.Builder.VIDEO_FORMAT);
    ActionSchedule actionSchedule =
        new ActionSchedule.Builder("AnalyticsCollectorTest")
            .pause()
            // Ensure everything is preloaded.
            .waitForIsLoading(true)
            .waitForIsLoading(false)
            .waitForIsLoading(true)
            .waitForIsLoading(false)
            .waitForIsLoading(true)
            .waitForIsLoading(false)
            // Seek behind the midroll.
            .seek(6 * C.MICROS_PER_SECOND)
            .play()
            .waitForPlaybackState(Player.STATE_ENDED)
            .build();
    TestAnalyticsListener listener = runAnalyticsTest(fakeMediaSource, actionSchedule);

    Object periodUid = listener.lastReportedTimeline.getUidOfPeriod(/* periodIndex= */ 0);
    EventWindowAndPeriodId midrollAd =
        new EventWindowAndPeriodId(
            /* windowIndex= */ 0,
            new MediaPeriodId(
                periodUid,
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                /* windowSequenceNumber= */ 0));
    EventWindowAndPeriodId contentBeforeMidroll =
        new EventWindowAndPeriodId(
            /* windowIndex= */ 0,
            new MediaPeriodId(periodUid, /* windowSequenceNumber= */ 0, /* nextAdGroupIndex= */ 0));
    EventWindowAndPeriodId contentAfterMidroll =
        new EventWindowAndPeriodId(
            /* windowIndex= */ 0,
            new MediaPeriodId(
                periodUid, /* windowSequenceNumber= */ 0, /* nextAdGroupIndex= */ C.INDEX_UNSET));
    assertThat(listener.getEvents(EVENT_PLAYER_STATE_CHANGED))
        .containsExactly(
            WINDOW_0 /* setPlayWhenReady=true */,
            WINDOW_0 /* setPlayWhenReady=false */,
            WINDOW_0 /* BUFFERING */,
            contentBeforeMidroll /* READY */,
            contentAfterMidroll /* setPlayWhenReady=true */,
            midrollAd /* BUFFERING */,
            midrollAd /* READY */,
            contentAfterMidroll /* ENDED */);
    assertThat(listener.getEvents(EVENT_TIMELINE_CHANGED))
        .containsExactly(WINDOW_0 /* PLAYLIST_CHANGED */, WINDOW_0 /* SOURCE_UPDATE */);
    assertThat(listener.getEvents(EVENT_POSITION_DISCONTINUITY))
        .containsExactly(
            contentAfterMidroll /* seek */,
            midrollAd /* seek adjustment */,
            contentAfterMidroll /* ad transition */);
    assertThat(listener.getEvents(EVENT_SEEK_STARTED)).containsExactly(contentBeforeMidroll);
    assertThat(listener.getEvents(EVENT_SEEK_PROCESSED)).containsExactly(midrollAd);
    assertThat(listener.getEvents(EVENT_LOADING_CHANGED))
        .containsExactly(
            contentBeforeMidroll,
            contentBeforeMidroll,
            contentBeforeMidroll,
            contentBeforeMidroll,
            contentBeforeMidroll,
            contentBeforeMidroll,
            midrollAd,
            midrollAd);
    assertThat(listener.getEvents(EVENT_TRACKS_CHANGED))
        .containsExactly(contentBeforeMidroll, midrollAd, contentAfterMidroll);
    assertThat(listener.getEvents(EVENT_LOAD_STARTED))
        .containsExactly(
            WINDOW_0 /* content manifest */,
            contentBeforeMidroll,
            midrollAd,
            contentAfterMidroll,
            contentAfterMidroll);
    assertThat(listener.getEvents(EVENT_LOAD_COMPLETED))
        .containsExactly(
            WINDOW_0 /* content manifest */,
            contentBeforeMidroll,
            midrollAd,
            contentAfterMidroll,
            contentAfterMidroll);
    assertThat(listener.getEvents(EVENT_DOWNSTREAM_FORMAT_CHANGED))
        .containsExactly(contentBeforeMidroll, midrollAd, contentAfterMidroll);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_CREATED))
        .containsExactly(contentBeforeMidroll, midrollAd, contentAfterMidroll, contentAfterMidroll);
    assertThat(listener.getEvents(EVENT_MEDIA_PERIOD_RELEASED))
        .containsExactly(contentBeforeMidroll, midrollAd, contentAfterMidroll);
    assertThat(listener.getEvents(EVENT_READING_STARTED))
        .containsExactly(contentBeforeMidroll, midrollAd, contentAfterMidroll);
    assertThat(listener.getEvents(EVENT_DECODER_ENABLED))
        .containsExactly(contentBeforeMidroll, midrollAd);
    assertThat(listener.getEvents(EVENT_DECODER_INIT))
        .containsExactly(contentBeforeMidroll, midrollAd, contentAfterMidroll);
    assertThat(listener.getEvents(EVENT_DECODER_FORMAT_CHANGED))
        .containsExactly(contentBeforeMidroll, midrollAd, contentAfterMidroll);
    assertThat(listener.getEvents(EVENT_DECODER_DISABLED)).containsExactly(contentBeforeMidroll);
    assertThat(listener.getEvents(EVENT_DROPPED_VIDEO_FRAMES)).containsExactly(contentAfterMidroll);
    assertThat(listener.getEvents(EVENT_VIDEO_SIZE_CHANGED))
        .containsExactly(contentBeforeMidroll, midrollAd);
    assertThat(listener.getEvents(EVENT_RENDERED_FIRST_FRAME))
        .containsExactly(contentBeforeMidroll, midrollAd);
    listener.assertNoMoreEvents();
  }

  @Test
  public void testNotifyExternalEvents() throws Exception {
    MediaSource mediaSource = new FakeMediaSource(SINGLE_PERIOD_TIMELINE);
    ActionSchedule actionSchedule =
        new ActionSchedule.Builder("AnalyticsCollectorTest")
            .pause()
            .waitForPlaybackState(Player.STATE_READY)
            .executeRunnable(
                new PlayerRunnable() {
                  @Override
                  public void run(SimpleExoPlayer player) {
                    player.getAnalyticsCollector().notifySeekStarted();
                  }
                })
            .seek(/* positionMs= */ 0)
            .play()
            .build();
    TestAnalyticsListener listener = runAnalyticsTest(mediaSource, actionSchedule);

    populateEventIds(listener.lastReportedTimeline);
    assertThat(listener.getEvents(EVENT_SEEK_STARTED)).containsExactly(period0);
    assertThat(listener.getEvents(EVENT_SEEK_PROCESSED)).containsExactly(period0);
  }

  private void populateEventIds(Timeline timeline) {
    period0 =
        new EventWindowAndPeriodId(
            /* windowIndex= */ 0,
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0));
    period0Seq0 = period0;
    period0Seq1 =
        new EventWindowAndPeriodId(
            /* windowIndex= */ 0,
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 1));
    window1Period0Seq1 =
        new EventWindowAndPeriodId(
            /* windowIndex= */ 1,
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 1));
    if (timeline.getPeriodCount() > 1) {
      period1 =
          new EventWindowAndPeriodId(
              /* windowIndex= */ 1,
              new MediaPeriodId(
                  timeline.getUidOfPeriod(/* periodIndex= */ 1), /* windowSequenceNumber= */ 1));
      period1Seq1 = period1;
      period1Seq0 =
          new EventWindowAndPeriodId(
              /* windowIndex= */ 1,
              new MediaPeriodId(
                  timeline.getUidOfPeriod(/* periodIndex= */ 1), /* windowSequenceNumber= */ 0));
      period1Seq2 =
          new EventWindowAndPeriodId(
              /* windowIndex= */ 1,
              new MediaPeriodId(
                  timeline.getUidOfPeriod(/* periodIndex= */ 1), /* windowSequenceNumber= */ 2));
      window0Period1Seq0 =
          new EventWindowAndPeriodId(
              /* windowIndex= */ 0,
              new MediaPeriodId(
                  timeline.getUidOfPeriod(/* periodIndex= */ 1), /* windowSequenceNumber= */ 0));
    }
  }

  private static TestAnalyticsListener runAnalyticsTest(MediaSource mediaSource) throws Exception {
    return runAnalyticsTest(mediaSource, /* actionSchedule= */ null);
  }

  private static TestAnalyticsListener runAnalyticsTest(
      MediaSource mediaSource, @Nullable ActionSchedule actionSchedule) throws Exception {
    RenderersFactory renderersFactory =
        (eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput,
            drmSessionManager) ->
            new Renderer[] {
              new FakeVideoRenderer(eventHandler, videoRendererEventListener),
              new FakeAudioRenderer(eventHandler, audioRendererEventListener)
            };
    TestAnalyticsListener listener = new TestAnalyticsListener();
    try {
      new ExoPlayerTestRunner.Builder()
          .setMediaSources(mediaSource)
          .setRenderersFactory(renderersFactory)
          .setAnalyticsListener(listener)
          .setActionSchedule(actionSchedule)
          .build(ApplicationProvider.getApplicationContext())
          .start()
          .blockUntilActionScheduleFinished(TIMEOUT_MS)
          .blockUntilEnded(TIMEOUT_MS);
    } catch (ExoPlaybackException e) {
      // Ignore ExoPlaybackException as these may be expected.
    }
    return listener;
  }

  private static final class FakeVideoRenderer extends FakeRenderer {

    private final VideoRendererEventListener.EventDispatcher eventDispatcher;
    private final DecoderCounters decoderCounters;
    private Format format;
    private boolean renderedFirstFrame;

    public FakeVideoRenderer(Handler handler, VideoRendererEventListener eventListener) {
      super(ExoPlayerTestRunner.Builder.VIDEO_FORMAT);
      eventDispatcher = new VideoRendererEventListener.EventDispatcher(handler, eventListener);
      decoderCounters = new DecoderCounters();
    }

    @Override
    protected void onEnabled(boolean joining) throws ExoPlaybackException {
      super.onEnabled(joining);
      eventDispatcher.enabled(decoderCounters);
      renderedFirstFrame = false;
    }

    @Override
    protected void onStopped() throws ExoPlaybackException {
      super.onStopped();
      eventDispatcher.droppedFrames(/* droppedFrameCount= */ 0, /* elapsedMs= */ 0);
    }

    @Override
    protected void onDisabled() {
      super.onDisabled();
      eventDispatcher.disabled(decoderCounters);
    }

    @Override
    protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
      super.onPositionReset(positionUs, joining);
      renderedFirstFrame = false;
    }

    @Override
    protected void onFormatChanged(Format format) {
      eventDispatcher.inputFormatChanged(format);
      eventDispatcher.decoderInitialized(
          /* decoderName= */ "fake.video.decoder",
          /* initializedTimestampMs= */ SystemClock.elapsedRealtime(),
          /* initializationDurationMs= */ 0);
      this.format = format;
    }

    @Override
    protected void onBufferRead() {
      if (!renderedFirstFrame) {
        eventDispatcher.videoSizeChanged(
            format.width, format.height, format.rotationDegrees, format.pixelWidthHeightRatio);
        eventDispatcher.renderedFirstFrame(/* surface= */ null);
        renderedFirstFrame = true;
      }
    }
  }

  private static final class FakeAudioRenderer extends FakeRenderer {

    private final AudioRendererEventListener.EventDispatcher eventDispatcher;
    private final DecoderCounters decoderCounters;
    private boolean notifiedAudioSessionId;

    public FakeAudioRenderer(Handler handler, AudioRendererEventListener eventListener) {
      super(ExoPlayerTestRunner.Builder.AUDIO_FORMAT);
      eventDispatcher = new AudioRendererEventListener.EventDispatcher(handler, eventListener);
      decoderCounters = new DecoderCounters();
    }

    @Override
    protected void onEnabled(boolean joining) throws ExoPlaybackException {
      super.onEnabled(joining);
      eventDispatcher.enabled(decoderCounters);
      notifiedAudioSessionId = false;
    }

    @Override
    protected void onDisabled() {
      super.onDisabled();
      eventDispatcher.disabled(decoderCounters);
    }

    @Override
    protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
      super.onPositionReset(positionUs, joining);
    }

    @Override
    protected void onFormatChanged(Format format) {
      eventDispatcher.inputFormatChanged(format);
      eventDispatcher.decoderInitialized(
          /* decoderName= */ "fake.audio.decoder",
          /* initializedTimestampMs= */ SystemClock.elapsedRealtime(),
          /* initializationDurationMs= */ 0);
    }

    @Override
    protected void onBufferRead() {
      if (!notifiedAudioSessionId) {
        eventDispatcher.audioSessionId(/* audioSessionId= */ 1);
        notifiedAudioSessionId = true;
      }
    }
  }

  private static final class EventWindowAndPeriodId {

    private final int windowIndex;
    @Nullable private final MediaPeriodId mediaPeriodId;

    public EventWindowAndPeriodId(int windowIndex, @Nullable MediaPeriodId mediaPeriodId) {
      this.windowIndex = windowIndex;
      this.mediaPeriodId = mediaPeriodId;
    }

    @Override
    public boolean equals(@Nullable Object other) {
      if (!(other instanceof EventWindowAndPeriodId)) {
        return false;
      }
      EventWindowAndPeriodId event = (EventWindowAndPeriodId) other;
      return windowIndex == event.windowIndex && Util.areEqual(mediaPeriodId, event.mediaPeriodId);
    }

    @Override
    public String toString() {
      return mediaPeriodId != null
          ? "Event{"
              + "window="
              + windowIndex
              + ", period="
              + mediaPeriodId.periodUid
              + ", sequence="
              + mediaPeriodId.windowSequenceNumber
              + '}'
          : "Event{" + "window=" + windowIndex + ", period = null}";
    }

    @Override
    public int hashCode() {
      return 31 * windowIndex + (mediaPeriodId == null ? 0 : mediaPeriodId.hashCode());
    }
  }

  private static final class TestAnalyticsListener implements AnalyticsListener {

    public Timeline lastReportedTimeline;

    private final List<Timeline> reportedTimelines;
    private final ArrayList<ReportedEvent> reportedEvents;

    public TestAnalyticsListener() {
      reportedEvents = new ArrayList<>();
      reportedTimelines = new ArrayList<>();
      lastReportedTimeline = Timeline.EMPTY;
    }

    public List<EventWindowAndPeriodId> getEvents(int eventType) {
      ArrayList<EventWindowAndPeriodId> eventTimes = new ArrayList<>();
      Iterator<ReportedEvent> eventIterator = reportedEvents.iterator();
      while (eventIterator.hasNext()) {
        ReportedEvent event = eventIterator.next();
        if (event.eventType == eventType) {
          eventTimes.add(event.eventWindowAndPeriodId);
          eventIterator.remove();
        }
      }
      return eventTimes;
    }

    public void assertNoMoreEvents() {
      assertThat(reportedEvents).isEmpty();
    }

    @Override
    public void onPlayerStateChanged(
        EventTime eventTime, boolean playWhenReady, @Player.State int playbackState) {
      reportedEvents.add(new ReportedEvent(EVENT_PLAYER_STATE_CHANGED, eventTime));
    }

    @Override
    public void onTimelineChanged(EventTime eventTime, int reason) {
      lastReportedTimeline = eventTime.timeline;
      reportedTimelines.add(eventTime.timeline);
      reportedEvents.add(new ReportedEvent(EVENT_TIMELINE_CHANGED, eventTime));
    }

    @Override
    public void onPositionDiscontinuity(EventTime eventTime, int reason) {
      reportedEvents.add(new ReportedEvent(EVENT_POSITION_DISCONTINUITY, eventTime));
    }

    @Override
    public void onSeekStarted(EventTime eventTime) {
      reportedEvents.add(new ReportedEvent(EVENT_SEEK_STARTED, eventTime));
    }

    @Override
    public void onSeekProcessed(EventTime eventTime) {
      reportedEvents.add(new ReportedEvent(EVENT_SEEK_PROCESSED, eventTime));
    }

    @Override
    public void onPlaybackParametersChanged(
        EventTime eventTime, PlaybackParameters playbackParameters) {
      reportedEvents.add(new ReportedEvent(EVENT_PLAYBACK_PARAMETERS_CHANGED, eventTime));
    }

    @Override
    public void onRepeatModeChanged(EventTime eventTime, int repeatMode) {
      reportedEvents.add(new ReportedEvent(EVENT_REPEAT_MODE_CHANGED, eventTime));
    }

    @Override
    public void onShuffleModeChanged(EventTime eventTime, boolean shuffleModeEnabled) {
      reportedEvents.add(new ReportedEvent(EVENT_SHUFFLE_MODE_CHANGED, eventTime));
    }

    @Override
    public void onLoadingChanged(EventTime eventTime, boolean isLoading) {
      reportedEvents.add(new ReportedEvent(EVENT_LOADING_CHANGED, eventTime));
    }

    @Override
    public void onPlayerError(EventTime eventTime, ExoPlaybackException error) {
      reportedEvents.add(new ReportedEvent(EVENT_PLAYER_ERROR, eventTime));
    }

    @Override
    public void onTracksChanged(
        EventTime eventTime, TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
      reportedEvents.add(new ReportedEvent(EVENT_TRACKS_CHANGED, eventTime));
    }

    @Override
    public void onLoadStarted(
        EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
      reportedEvents.add(new ReportedEvent(EVENT_LOAD_STARTED, eventTime));
    }

    @Override
    public void onLoadCompleted(
        EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
      reportedEvents.add(new ReportedEvent(EVENT_LOAD_COMPLETED, eventTime));
    }

    @Override
    public void onLoadCanceled(
        EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
      reportedEvents.add(new ReportedEvent(EVENT_LOAD_CANCELED, eventTime));
    }

    @Override
    public void onLoadError(
        EventTime eventTime,
        LoadEventInfo loadEventInfo,
        MediaLoadData mediaLoadData,
        IOException error,
        boolean wasCanceled) {
      reportedEvents.add(new ReportedEvent(EVENT_LOAD_ERROR, eventTime));
    }

    @Override
    public void onDownstreamFormatChanged(EventTime eventTime, MediaLoadData mediaLoadData) {
      reportedEvents.add(new ReportedEvent(EVENT_DOWNSTREAM_FORMAT_CHANGED, eventTime));
    }

    @Override
    public void onUpstreamDiscarded(EventTime eventTime, MediaLoadData mediaLoadData) {
      reportedEvents.add(new ReportedEvent(EVENT_UPSTREAM_DISCARDED, eventTime));
    }

    @Override
    public void onMediaPeriodCreated(EventTime eventTime) {
      reportedEvents.add(new ReportedEvent(EVENT_MEDIA_PERIOD_CREATED, eventTime));
    }

    @Override
    public void onMediaPeriodReleased(EventTime eventTime) {
      reportedEvents.add(new ReportedEvent(EVENT_MEDIA_PERIOD_RELEASED, eventTime));
    }

    @Override
    public void onReadingStarted(EventTime eventTime) {
      reportedEvents.add(new ReportedEvent(EVENT_READING_STARTED, eventTime));
    }

    @Override
    public void onBandwidthEstimate(
        EventTime eventTime, int totalLoadTimeMs, long totalBytesLoaded, long bitrateEstimate) {
      reportedEvents.add(new ReportedEvent(EVENT_BANDWIDTH_ESTIMATE, eventTime));
    }

    @Override
    public void onSurfaceSizeChanged(EventTime eventTime, int width, int height) {
      reportedEvents.add(new ReportedEvent(EVENT_SURFACE_SIZE_CHANGED, eventTime));
    }

    @Override
    public void onMetadata(EventTime eventTime, Metadata metadata) {
      reportedEvents.add(new ReportedEvent(EVENT_METADATA, eventTime));
    }

    @Override
    public void onDecoderEnabled(
        EventTime eventTime, int trackType, DecoderCounters decoderCounters) {
      reportedEvents.add(new ReportedEvent(EVENT_DECODER_ENABLED, eventTime));
    }

    @Override
    public void onDecoderInitialized(
        EventTime eventTime, int trackType, String decoderName, long initializationDurationMs) {
      reportedEvents.add(new ReportedEvent(EVENT_DECODER_INIT, eventTime));
    }

    @Override
    public void onDecoderInputFormatChanged(EventTime eventTime, int trackType, Format format) {
      reportedEvents.add(new ReportedEvent(EVENT_DECODER_FORMAT_CHANGED, eventTime));
    }

    @Override
    public void onDecoderDisabled(
        EventTime eventTime, int trackType, DecoderCounters decoderCounters) {
      reportedEvents.add(new ReportedEvent(EVENT_DECODER_DISABLED, eventTime));
    }

    @Override
    public void onAudioSessionId(EventTime eventTime, int audioSessionId) {
      reportedEvents.add(new ReportedEvent(EVENT_AUDIO_SESSION_ID, eventTime));
    }

    @Override
    public void onAudioUnderrun(
        EventTime eventTime, int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
      reportedEvents.add(new ReportedEvent(EVENT_AUDIO_UNDERRUN, eventTime));
    }

    @Override
    public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
      reportedEvents.add(new ReportedEvent(EVENT_DROPPED_VIDEO_FRAMES, eventTime));
    }

    @Override
    public void onVideoSizeChanged(
        EventTime eventTime,
        int width,
        int height,
        int unappliedRotationDegrees,
        float pixelWidthHeightRatio) {
      reportedEvents.add(new ReportedEvent(EVENT_VIDEO_SIZE_CHANGED, eventTime));
    }

    @Override
    public void onRenderedFirstFrame(EventTime eventTime, Surface surface) {
      reportedEvents.add(new ReportedEvent(EVENT_RENDERED_FIRST_FRAME, eventTime));
    }

    @Override
    public void onDrmSessionAcquired(EventTime eventTime) {
      reportedEvents.add(new ReportedEvent(EVENT_DRM_SESSION_ACQUIRED, eventTime));
    }

    @Override
    public void onDrmKeysLoaded(EventTime eventTime) {
      reportedEvents.add(new ReportedEvent(EVENT_DRM_KEYS_LOADED, eventTime));
    }

    @Override
    public void onDrmSessionManagerError(EventTime eventTime, Exception error) {
      reportedEvents.add(new ReportedEvent(EVENT_DRM_ERROR, eventTime));
    }

    @Override
    public void onDrmKeysRestored(EventTime eventTime) {
      reportedEvents.add(new ReportedEvent(EVENT_DRM_KEYS_RESTORED, eventTime));
    }

    @Override
    public void onDrmKeysRemoved(EventTime eventTime) {
      reportedEvents.add(new ReportedEvent(EVENT_DRM_KEYS_REMOVED, eventTime));
    }

    @Override
    public void onDrmSessionReleased(EventTime eventTime) {
      reportedEvents.add(new ReportedEvent(EVENT_DRM_SESSION_RELEASED, eventTime));
    }

    private static final class ReportedEvent {

      public final int eventType;
      public final EventWindowAndPeriodId eventWindowAndPeriodId;

      public ReportedEvent(int eventType, EventTime eventTime) {
        this.eventType = eventType;
        this.eventWindowAndPeriodId =
            new EventWindowAndPeriodId(eventTime.windowIndex, eventTime.mediaPeriodId);
      }
    }
  }
}