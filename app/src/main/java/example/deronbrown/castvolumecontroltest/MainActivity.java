package example.deronbrown.castvolumecontroltest;

import android.media.AudioManager;
import android.net.Uri;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.ext.cast.CastPlayer;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;

public class MainActivity extends AppCompatActivity implements CastPlayer.SessionAvailabilityListener,
        AudioManager.OnAudioFocusChangeListener {

    private EventListener eventListener;
    private SimpleExoPlayer exoPlayer;
    private CastPlayer castPlayer;

    private Player currentPlayer;
    private boolean castMediaQueueCreationPending;
    private int currentItemIndex = C.INDEX_UNSET;
    private int currentWindowIndex;

    private MediaSource mainMediaSource;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        eventListener = new EventListener();

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
        DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(new AdaptiveTrackSelection.Factory(bandwidthMeter));
        exoPlayer = ExoPlayerFactory.newSimpleInstance(renderersFactory, trackSelector);

        // Initialize player
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.CONTENT_TYPE_MUSIC)
                .build();
        exoPlayer.setAudioAttributes(audioAttributes);
        exoPlayer.addListener(eventListener);

        // Initialize cast player
        CastContext castContext = CastContext.getSharedInstance(this);
        castPlayer = new CastPlayer(castContext);

        setCurrentPlayer(castPlayer.isCastSessionAvailable() ? castPlayer : exoPlayer);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main, menu);
        CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), menu, R.id.action_cast);
        return true;
    }

    public void playAudio(View view) {
        currentPlayer.setPlayWhenReady(true);
    }

    public void pauseAudio(View view) {
        currentPlayer.setPlayWhenReady(false);
    }

    public void startAudio(View view) {
        AudioManager audioManager = (AudioManager) getApplicationContext().getSystemService(AUDIO_SERVICE);
        int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            setCurrentPlayer(castPlayer.isCastSessionAvailable() ? castPlayer : exoPlayer);
            setupCastListeners();

            DefaultHttpDataSourceFactory dataSourceFactory = new DefaultHttpDataSourceFactory("test-agent");
            mainMediaSource = new ExtractorMediaSource.Factory(dataSourceFactory).createMediaSource(Uri.parse("https://html5demos.com/assets/dizzy.mp4"));
            if (currentPlayer == exoPlayer) {
                exoPlayer.setPlayWhenReady(true);
                exoPlayer.prepare(mainMediaSource);
            } else {
                castMediaQueueCreationPending = true;
                setCurrentItem(C.INDEX_UNSET, 0, exoPlayer.getPlayWhenReady());
            }
        }
    }

    private void setupCastListeners() {
        castPlayer.addListener(eventListener);
        castPlayer.setSessionAvailabilityListener(this);
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        // TODO
    }

    @Override
    public void onCastSessionAvailable() {
        setCurrentPlayer(castPlayer);
    }

    @Override
    public void onCastSessionUnavailable() {
        setCurrentPlayer(exoPlayer);
    }

    private void setCurrentPlayer(Player newPlayer) {
        if (currentPlayer == newPlayer) {
            return;
        }

        // Player state management.
        long playbackPositionMs = C.TIME_UNSET;
        int windowIndex = C.INDEX_UNSET;
        boolean playWhenReady = false;
        if (currentPlayer != null) {
            int playbackState = currentPlayer.getPlaybackState();
            if (playbackState != Player.STATE_ENDED) {
                playbackPositionMs = currentPlayer.getCurrentPosition();
                playWhenReady = currentPlayer.getPlayWhenReady();
                windowIndex = currentPlayer.getCurrentWindowIndex();
                if (windowIndex != currentItemIndex) {
                    playbackPositionMs = C.TIME_UNSET;
                    windowIndex = currentItemIndex;
                }
            }
            currentPlayer.stop(true);
        }

        currentPlayer = newPlayer;

        // Media queue management.
        castMediaQueueCreationPending = newPlayer == castPlayer;
        if (newPlayer == exoPlayer && mainMediaSource != null) {
            exoPlayer.prepare(mainMediaSource);
        }

        // Playback transition.
        if (windowIndex != C.INDEX_UNSET) {
            setCurrentItem(windowIndex, playbackPositionMs, playWhenReady);
        }
    }

    private void updateCurrentItemIndex() {
        int playbackState = currentPlayer.getPlaybackState();
        maybeSetCurrentItem(
                playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED
                        ? currentPlayer.getCurrentWindowIndex() : C.INDEX_UNSET);
    }

    private void setCurrentItem(int itemIndex, long positionMs, boolean playWhenReady) {
        maybeSetCurrentItem(itemIndex);
        if (castMediaQueueCreationPending) {
            castMediaQueueCreationPending = false;

            MediaInfo info = new MediaInfo.Builder("https://html5demos.com/assets/dizzy.mp4")
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(MimeTypes.VIDEO_MP4)
                    .setMetadata(getMetadata())
                    .build();
            castPlayer.loadItem(new MediaQueueItem.Builder(info).build(), positionMs);
        } else {
            currentPlayer.seekTo(itemIndex, positionMs);
            currentPlayer.setPlayWhenReady(playWhenReady);
        }
    }

    private MediaMetadata getMetadata() {
        MediaMetadata data = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        data.putString(MediaMetadata.KEY_TITLE, "test-title");
        data.putString(MediaMetadata.KEY_ARTIST, "test-artist");
        data.putString(MediaMetadata.KEY_SUBTITLE, "test-subtitle");
        return data;
    }

    private void maybeSetCurrentItem(int currentItemIndex) {
        if (this.currentItemIndex != currentItemIndex) {
            this.currentItemIndex = currentItemIndex;
        }
    }

    private class EventListener extends Player.DefaultEventListener {

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            updateCurrentItemIndex();
            switch (playbackState) {
                case Player.STATE_IDLE:
                    break;
                case Player.STATE_READY:
                    break;
                case Player.STATE_ENDED:
                    break;
            }
        }

        @Override
        public void onPositionDiscontinuity(@Player.DiscontinuityReason int reason) {
            updateCurrentItemIndex();
            if (currentWindowIndex != currentPlayer.getCurrentWindowIndex()) {
                currentWindowIndex = currentPlayer.getCurrentWindowIndex();
            }
        }

        @Override
        public void onTimelineChanged(Timeline timeline, Object manifest, @Player.TimelineChangeReason int reason) {
            currentWindowIndex = currentPlayer.getCurrentWindowIndex();
            updateCurrentItemIndex();
            if (timeline.isEmpty()) {
                castMediaQueueCreationPending = true;
            }
        }
    }
}
