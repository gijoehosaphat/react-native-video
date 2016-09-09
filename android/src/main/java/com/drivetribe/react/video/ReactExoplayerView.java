package com.drivetribe.react.video;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.accessibility.CaptioningManager;
import android.widget.Toast;

import com.drivetribe.exoplayer.AspectRatioFrameLayout;
import com.drivetribe.exoplayer.EventLogger;
import com.drivetribe.exoplayer.SmoothStreamingTestMediaDrmCallback;
import com.drivetribe.exoplayer.WidevineTestMediaDrmCallback;
import com.drivetribe.exoplayer.player.DashRendererBuilder;
import com.drivetribe.exoplayer.player.ExtractorRendererBuilder;
import com.drivetribe.exoplayer.player.HlsRendererBuilder;
import com.drivetribe.exoplayer.player.Player;
import com.drivetribe.exoplayer.player.SmoothStreamingRendererBuilder;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.MediaCodecTrackRenderer;
import com.google.android.exoplayer.MediaCodecUtil;
import com.google.android.exoplayer.audio.AudioCapabilities;
import com.google.android.exoplayer.audio.AudioCapabilitiesReceiver;
import com.google.android.exoplayer.drm.UnsupportedDrmException;
import com.google.android.exoplayer.metadata.id3.ApicFrame;
import com.google.android.exoplayer.metadata.id3.GeobFrame;
import com.google.android.exoplayer.metadata.id3.Id3Frame;
import com.google.android.exoplayer.metadata.id3.PrivFrame;
import com.google.android.exoplayer.metadata.id3.TextInformationFrame;
import com.google.android.exoplayer.metadata.id3.TxxxFrame;
import com.google.android.exoplayer.text.CaptionStyleCompat;
import com.google.android.exoplayer.text.Cue;
import com.google.android.exoplayer.text.SubtitleLayout;
import com.google.android.exoplayer.util.PlayerControl;
import com.google.android.exoplayer.util.Util;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.List;


public class ReactExoplayerView extends AspectRatioFrameLayout implements
        LifecycleEventListener,
        SurfaceHolder.Callback,
        AudioCapabilitiesReceiver.Listener,
        Player.Listener,
        Player.CaptionListener,
        Player.Id3MetadataListener {

    private static final int SHOW_PROGRESS = 1;
    private static final String TAG = "REPVM";

    private static final CookieManager defaultCookieManager;

    static {
        defaultCookieManager = new CookieManager();
        defaultCookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    }

    private final ThemedReactContext themedReactContext;

    private EventLogger eventLogger;

    private SurfaceView surfaceView;
    private View shutterView;
    private SubtitleLayout subtitleLayout;

    private boolean playerNeedsPrepare;
    private Player player;

    private Uri contentUri;
    private int contentType;
    private String contentId;
    private String provider;

    private long playerPosition;
    private boolean enableBackgroundAudio;
    private boolean repeat;

    private AudioCapabilitiesReceiver audioCapabilitiesReceiver;
    private VideoEventEmitter eventEmmiter;

    public ReactExoplayerView(ThemedReactContext themedReactContext) {
        super(themedReactContext);
        this.themedReactContext = themedReactContext;
        createViews();
        attachListeners();
    }

    private void createViews() {
        LayoutParams layoutParams = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);

        surfaceView = new SurfaceView(getContext());
        surfaceView.setLayoutParams(layoutParams);
        addView(surfaceView);

        shutterView = new View(getContext());
        shutterView.setLayoutParams(layoutParams);
        shutterView.setBackgroundColor(Color.BLACK);
        addView(shutterView);

        subtitleLayout = new SubtitleLayout(getContext());
        subtitleLayout.setLayoutParams(layoutParams);
        addView(subtitleLayout);
    }

    private void attachListeners() {
        themedReactContext.addLifecycleEventListener(this);
        surfaceView.getHolder().addCallback(this);

        audioCapabilitiesReceiver = new AudioCapabilitiesReceiver(getContext(), this);
        audioCapabilitiesReceiver.register();

        eventEmmiter = new VideoEventEmitter(getId(), themedReactContext);
    }

    private final Handler progressHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SHOW_PROGRESS:
                    if (player != null
                            && player.getPlaybackState() == Player.STATE_READY
                            && player.getPlayerControl().isPlaying()) {
                        long pos = player.getCurrentPosition();
                        eventEmmiter.onProgressChanged(pos, player.getBufferedPercentage());
                        msg = obtainMessage(SHOW_PROGRESS);
                        sendMessageDelayed(msg, 1000 - (pos % 1000));
                    }
                    break;
            }
        }
    };

    // Public methods

    public void setSrc(@NonNull Uri contentUri) {
        this.contentUri = contentUri;
        this.contentType = inferContentType(contentUri, "");
        Log.d(TAG, "set src " + contentUri.toString() + " type: " + contentType);

        eventEmmiter.loadStart();

        preparePlayer(true);
    }

    public void setResizeModeModifier(boolean repeat) {
        // TODO
    }

    public void setRepeatModifier(boolean repeat) {
        this.repeat = repeat;
    }

    public void setPausedModifier(boolean paused) {
        Log.d(TAG, "setPausedModifier");

        if (player != null) {
            PlayerControl playerControl = player.getPlayerControl();
            if (paused) {
                playerControl.start();
            } else {
                playerControl.pause();
            }
        }
    }

    public void setMutedModifier(boolean muted) {
        // Not available - use system UI
    }

    public void setVolumeModifier(int volume) {
        // Not available - use system UI
    }

    public void seekTo(long positionMs) {
        if (player != null) {
            eventEmmiter.seek(player.getCurrentPosition(), positionMs);
            player.seekTo(positionMs);
        }
    }

    public void setRateModifier(float rate) {
        // TODO: What is this for?
    }

    public void setPlayInBackground(boolean playInBackground) {
        Log.d(TAG, "setPlayInBackground: " + playInBackground);
        this.enableBackgroundAudio = playInBackground;
    }

    // SurfaceHolder.Callback implementation

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        Log.d(TAG, "surfaceCreated");
        if (player != null) {
            Log.d(TAG, "player set to surface w: " + surfaceView.getWidth() + " h: " + surfaceView.getHeight());
            player.setSurface(surfaceView.getHolder().getSurface());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {
        // Do nothing.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        if (player != null) {
            player.blockingClearSurface();
        }
    }

    // Lifecycle methods

    @Override
    public void onHostResume() {
        Log.d(TAG, "onHostResume");
        if (player == null) {
            onShown();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        Log.d(TAG, "onAttachedToWindow");
        super.onAttachedToWindow();
        onShown();
    }

    private void onShown() {
        configureSubtitleView();
        if (player == null) {
            preparePlayer(true);
        } else {
            player.setBackgrounded(false);
        }
    }

    @Override
    public void onHostPause() {
        Log.d(TAG, "onHostPause");
        onHidden();
    }

    @Override
    protected void onDetachedFromWindow() {
        Log.d(TAG, "onDetachedFromWindow");
        super.onDetachedFromWindow();
        onHidden();
    }

    private void onHidden() {
        Log.d(TAG, "onHidden");
        if (!enableBackgroundAudio) {
            releasePlayer();
            destroyAndClearListeners();
        } else {
            player.setBackgrounded(true);
        }
        shutterView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onHostDestroy() {
        // handle in detach
    }

    public void destroyAndClearListeners() {
        Log.d(TAG, "destroyAndClearListeners");
        themedReactContext.removeLifecycleEventListener(this);
        if (audioCapabilitiesReceiver != null) {
            audioCapabilitiesReceiver.unregister();
            audioCapabilitiesReceiver = null;
        }
        releasePlayer();

        if (surfaceView != null) {
            surfaceView.getHolder().removeCallback(this);
        }
    }

    // AudioCapabilitiesReceiver.Listener methods

    @Override
    public void onAudioCapabilitiesChanged(AudioCapabilities audioCapabilities) {
        if (player == null) {
            return;
        }
        boolean backgrounded = player.getBackgrounded();
        boolean playWhenReady = player.getPlayWhenReady();
        releasePlayer();
        preparePlayer(playWhenReady);
        player.setBackgrounded(backgrounded);
    }


    // Player internal methods

    private Player.RendererBuilder getRendererBuilder() {
        String userAgent = Util.getUserAgent(getContext(), "ExoPlayerDemo");
        Log.d(TAG, "getRendererBuilder");

        switch (contentType) {
            case Util.TYPE_SS:
                return new SmoothStreamingRendererBuilder(getContext(), userAgent, contentUri.toString(),
                        new SmoothStreamingTestMediaDrmCallback());
            case Util.TYPE_DASH:
                return new DashRendererBuilder(getContext(), userAgent, contentUri.toString(),
                        new WidevineTestMediaDrmCallback(contentId, provider));
            case Util.TYPE_HLS:
                return new HlsRendererBuilder(getContext(), userAgent, contentUri.toString());
            case Util.TYPE_OTHER:
                return new ExtractorRendererBuilder(getContext(), userAgent, contentUri);
            default:
                throw new IllegalStateException("Unsupported type: " + contentType);
        }
    }

    private void preparePlayer(boolean playWhenReady) {
        if (player == null) {
            player = new Player(getRendererBuilder());
            player.addListener(this);
            player.setCaptionListener(this);
            player.setMetadataListener(this);
            player.seekTo(playerPosition);
            playerNeedsPrepare = true;
            eventLogger = new EventLogger();
            eventLogger.startSession();
            player.addListener(eventLogger);
            player.setInfoListener(eventLogger);
            player.setInternalErrorListener(eventLogger);

        }
        if (playerNeedsPrepare) {
            player.prepare();
            playerNeedsPrepare = false;
        }
        player.setSurface(surfaceView.getHolder().getSurface());
        player.setPlayWhenReady(playWhenReady);
    }

    private void playerReady() {
        // TODO: is this the height and width?
        eventEmmiter.ready(player.getDuration(), player.getCurrentPosition());
        progressHandler.sendEmptyMessage(SHOW_PROGRESS);

        requestLayout();
        setAspectRatio(surfaceView.getHeight() == 0 ? 1 : (surfaceView.getWidth() * 1.0f) / surfaceView.getHeight());
    }

    private void releasePlayer() {
        if (player != null) {
            playerPosition = player.getCurrentPosition();
            player.release();
            player = null;
            eventLogger.endSession();
            eventLogger = null;
        }
        progressHandler.removeMessages(SHOW_PROGRESS);
    }

    @Override
    public void onStateChanged(boolean playWhenReady, int playbackState) {
        String text = "onStateChanged: playWhenReady=" + playWhenReady + ", playbackState=";
        switch (playbackState) {
            case ExoPlayer.STATE_BUFFERING:
                text += "buffering";
                eventEmmiter.buffering();
                break;
            case ExoPlayer.STATE_ENDED:
                text += "ended";
                eventEmmiter.end();
                break;
            case ExoPlayer.STATE_IDLE:
                text += "idle";
                eventEmmiter.idle();
                break;
            case ExoPlayer.STATE_PREPARING:
                text += "preparing";
                eventEmmiter.preparing();
                break;
            case ExoPlayer.STATE_READY:
                text += "ready";
                playerReady();
                break;
            default:
                text += "unknown";
                break;
        }
        Log.d(TAG, text);
    }

    @Override
    public void onError(Exception e) {
        String errorString = null;
        if (e instanceof UnsupportedDrmException) {
            // Special case DRM failures.
            UnsupportedDrmException unsupportedDrmException = (UnsupportedDrmException) e;
            errorString = getContext().getString(Util.SDK_INT < 18 ? R.string.error_drm_not_supported
                    : unsupportedDrmException.reason == UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME
                    ? R.string.error_drm_unsupported_scheme : R.string.error_drm_unknown);
        } else if (e instanceof ExoPlaybackException
                && e.getCause() instanceof MediaCodecTrackRenderer.DecoderInitializationException) {
            // Special case for decoder initialization failures.
            MediaCodecTrackRenderer.DecoderInitializationException decoderInitializationException =
                    (MediaCodecTrackRenderer.DecoderInitializationException) e.getCause();
            if (decoderInitializationException.decoderName == null) {
                if (decoderInitializationException.getCause() instanceof MediaCodecUtil.DecoderQueryException) {
                    errorString = getContext().getString(R.string.error_querying_decoders);
                } else if (decoderInitializationException.secureDecoderRequired) {
                    errorString = getContext().getString(R.string.error_no_secure_decoder,
                            decoderInitializationException.mimeType);
                } else {
                    errorString = getContext().getString(R.string.error_no_decoder,
                            decoderInitializationException.mimeType);
                }
            } else {
                errorString = getContext().getString(R.string.error_instantiating_decoder,
                        decoderInitializationException.decoderName);
            }
        }
        eventEmmiter.error(errorString, e);

        playerNeedsPrepare = true;
    }

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthAspectRatio) {
        shutterView.setVisibility(View.GONE);
        this.setAspectRatio(height == 0 ? 1 : (width * pixelWidthAspectRatio) / height);

        eventEmmiter.onVideoSizeChanged(width, height);
    }

    // Player.CaptionListener implementation

    @Override
    public void onCues(List<Cue> cues) {
        subtitleLayout.setCues(cues);
    }

    // Player.MetadataListener implementation

    @Override
    public void onId3Metadata(List<Id3Frame> id3Frames) {
        for (Id3Frame id3Frame : id3Frames) {
            if (id3Frame instanceof TxxxFrame) {
                TxxxFrame txxxFrame = (TxxxFrame) id3Frame;
                Log.i(TAG, String.format("ID3 TimedMetadata %s: description=%s, value=%s", txxxFrame.id,
                        txxxFrame.description, txxxFrame.value));
            } else if (id3Frame instanceof PrivFrame) {
                PrivFrame privFrame = (PrivFrame) id3Frame;
                Log.i(TAG, String.format("ID3 TimedMetadata %s: owner=%s", privFrame.id, privFrame.owner));
            } else if (id3Frame instanceof GeobFrame) {
                GeobFrame geobFrame = (GeobFrame) id3Frame;
                Log.i(TAG, String.format("ID3 TimedMetadata %s: mimeType=%s, filename=%s, description=%s",
                        geobFrame.id, geobFrame.mimeType, geobFrame.filename, geobFrame.description));
            } else if (id3Frame instanceof ApicFrame) {
                ApicFrame apicFrame = (ApicFrame) id3Frame;
                Log.i(TAG, String.format("ID3 TimedMetadata %s: mimeType=%s, description=%s",
                        apicFrame.id, apicFrame.mimeType, apicFrame.description));
            } else if (id3Frame instanceof TextInformationFrame) {
                TextInformationFrame textInformationFrame = (TextInformationFrame) id3Frame;
                Log.i(TAG, String.format("ID3 TimedMetadata %s: description=%s", textInformationFrame.id,
                        textInformationFrame.description));
            } else {
                Log.i(TAG, String.format("ID3 TimedMetadata %s", id3Frame.id));
            }
        }
    }

    private void configureSubtitleView() {
        CaptionStyleCompat style;
        float fontScale;
        if (Util.SDK_INT >= 19) {
            style = getUserCaptionStyleV19();
            fontScale = getUserCaptionFontScaleV19();
        } else {
            style = CaptionStyleCompat.DEFAULT;
            fontScale = 1.0f;
        }
        subtitleLayout.setStyle(style);
        subtitleLayout.setFractionalTextSize(SubtitleLayout.DEFAULT_TEXT_SIZE_FRACTION * fontScale);
    }

    @TargetApi(19)
    private float getUserCaptionFontScaleV19() {
        CaptioningManager captioningManager =
                (CaptioningManager) getContext().getSystemService(Context.CAPTIONING_SERVICE);
        return captioningManager.getFontScale();
    }

    @TargetApi(19)
    private CaptionStyleCompat getUserCaptionStyleV19() {
        CaptioningManager captioningManager =
                (CaptioningManager) getContext().getSystemService(Context.CAPTIONING_SERVICE);
        return CaptionStyleCompat.createFromCaptionStyle(captioningManager.getUserStyle());
    }

    /**
     * Makes a best guess to infer the type from a media {@link Uri} and an optional overriding file
     * extension.
     *
     * @param uri           The {@link Uri} of the media.
     * @param fileExtension An overriding file extension.
     * @return The inferred type.
     */
    private static int inferContentType(Uri uri, String fileExtension) {
        String lastPathSegment = !TextUtils.isEmpty(fileExtension) ? "." + fileExtension
                : uri.getLastPathSegment();
        return Util.inferContentType(lastPathSegment);
    }

}
