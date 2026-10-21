package com.example.xraylocal;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

/**
 * Foreground service managing playback notifications and system media session controls.
 */
public final class PlaybackService extends Service implements PlayerController.Listener {
    public static final String CHANNEL_ID = "asmr_playback_channel";
    public static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START = "com.example.xraylocal.ACTION_START";
    public static final String ACTION_STOP = "com.example.xraylocal.ACTION_STOP";
    public static final String ACTION_PLAY_PAUSE = "com.example.xraylocal.ACTION_PLAY_PAUSE";
    public static final String ACTION_PREV = "com.example.xraylocal.ACTION_PREV";
    public static final String ACTION_NEXT = "com.example.xraylocal.ACTION_NEXT";

    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean isNoisyRegistered = false;

    private String lastCoverUrl = "";
    private Bitmap lastCoverBitmap = null;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                PlayerController pc = PlayerController.get();
                if (pc.isPlaying()) {
                    pc.toggle();
                }
            }
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                PlayerController pc = PlayerController.get();
                if (pc.isPlaying()) {
                    pc.toggle();
                }
            }
        }
    };

    public static void requestNotificationPermission(Activity activity) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }
    }

    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, PlaybackService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, PlaybackService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PlayerController.get().addListener(this);
        createNotificationChannel();
        initMediaSession();

        if (!isNoisyRegistered) {
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(noisyReceiver, filter);
            }
            isNoisyRegistered = true;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        initMediaSession();

        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                abandonAudioFocus();
                PlayerController.get().stop();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_PLAY_PAUSE.equals(action)) {
                PlayerController.get().toggle();
            } else if (ACTION_PREV.equals(action)) {
                PlayerController.get().prev();
            } else if (ACTION_NEXT.equals(action)) {
                PlayerController.get().next();
            }
        }

        PlayerController pc = PlayerController.get();
        if (!pc.hasTrack()) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        updateCoverIfNeeded(pc.workCoverUrl());
        updateMediaSession();
        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (pc.isPlaying()) {
            requestAudioFocus();
        }

        return START_STICKY;
    }

    @Override
    public void onPlayerChanged() {
        PlayerController pc = PlayerController.get();
        if (!pc.hasTrack()) {
            abandonAudioFocus();
            stopForeground(true);
            stopSelf();
            return;
        }
        if (pc.isPlaying()) {
            requestAudioFocus();
        }
        updateNotification();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "音频播放控制",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("后台音频播放及通知栏控制");
                channel.setShowBadge(false);
                channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void initMediaSession() {
        if (mediaSession != null) return;
        mediaSession = new MediaSession(this, "PlaybackService");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                PlayerController pc = PlayerController.get();
                if (!pc.isPlaying()) pc.toggle();
            }

            @Override
            public void onPause() {
                PlayerController pc = PlayerController.get();
                if (pc.isPlaying()) pc.toggle();
            }

            @Override
            public void onSkipToNext() {
                PlayerController.get().next();
            }

            @Override
            public void onSkipToPrevious() {
                PlayerController.get().prev();
            }

            @Override
            public void onSeekTo(long pos) {
                PlayerController.get().seekTo((int) pos);
            }

            @Override
            public void onStop() {
                PlayerController.get().stop();
            }
        });
        mediaSession.setActive(true);
    }

    private void updateCoverIfNeeded(String coverUrl) {
        if (coverUrl == null || coverUrl.isEmpty()) {
            lastCoverUrl = "";
            lastCoverBitmap = null;
            return;
        }
        if (coverUrl.equals(lastCoverUrl)) return;
        lastCoverUrl = coverUrl;
        ImageLoader.fetch(coverUrl, b -> {
            if (coverUrl.equals(lastCoverUrl)) {
                lastCoverBitmap = b;
                updateNotification();
            }
        });
    }

    private void updateMediaSession() {
        if (mediaSession == null) return;
        PlayerController pc = PlayerController.get();
        boolean isPlaying = pc.isPlaying();
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SEEK_TO;
        if (pc.hasPrev() || pc.position() > 3000) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (pc.hasNext()) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;

        int state = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        if (!pc.hasTrack()) state = PlaybackState.STATE_STOPPED;

        PlaybackState.Builder stateBuilder = new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, pc.position(), isPlaying ? 1.0f : 0f, SystemClock.elapsedRealtime());
        mediaSession.setPlaybackState(stateBuilder.build());

        AsmrApi.Node node = pc.current();
        if (node != null) {
            MediaMetadata.Builder meta = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, node.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, pc.workCircle())
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, pc.workTitle())
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, pc.duration());
            if (lastCoverBitmap != null && !lastCoverBitmap.isRecycled()) {
                meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, lastCoverBitmap);
                meta.putBitmap(MediaMetadata.METADATA_KEY_ART, lastCoverBitmap);
            }
            mediaSession.setMetadata(meta.build());
        }
    }

    private void updateNotification() {
        PlayerController pc = PlayerController.get();
        if (!pc.hasTrack()) {
            abandonAudioFocus();
            stopForeground(true);
            stopSelf();
            return;
        }
        updateCoverIfNeeded(pc.workCoverUrl());
        updateMediaSession();

        Notification notification = buildNotification();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        PlayerController pc = PlayerController.get();
        AsmrApi.Node node = pc.current();
        String title = node != null ? node.title : "ASMR 播放器";
        String workTitle = pc.workTitle();
        String workCircle = pc.workCircle();

        String subText;
        if (!workTitle.isEmpty() && !workCircle.isEmpty()) {
            subText = workTitle + " · " + workCircle;
        } else if (!workTitle.isEmpty()) {
            subText = workTitle;
        } else {
            subText = workCircle;
        }

        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPending = PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        PendingIntent prevPending = createActionPendingIntent(ACTION_PREV, 10);
        PendingIntent playPausePending = createActionPendingIntent(ACTION_PLAY_PAUSE, 11);
        PendingIntent nextPending = createActionPendingIntent(ACTION_NEXT, 12);
        PendingIntent stopPending = createActionPendingIntent(ACTION_STOP, 13);

        Notification.MediaStyle mediaStyle = new Notification.MediaStyle();
        if (mediaSession != null) {
            mediaStyle.setMediaSession(mediaSession.getSessionToken());
        }
        mediaStyle.setShowActionsInCompactView(0, 1, 2);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        boolean isPlaying = pc.isPlaying();

        builder.setStyle(mediaStyle)
                .setSmallIcon(isPlaying ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
                .setContentTitle(title)
                .setContentText(subText.isEmpty() ? "正在播放" : subText)
                .setContentIntent(contentPending)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_previous, "上一首", prevPending
                ).build())
                .addAction(new Notification.Action.Builder(
                        isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        isPlaying ? "暂停" : "播放", playPausePending
                ).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_next, "下一首", nextPending
                ).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "关闭", stopPending
                ).build());

        if (!workTitle.isEmpty()) {
            builder.setSubText(workTitle);
        }

        if (lastCoverBitmap != null && !lastCoverBitmap.isRecycled()) {
            builder.setLargeIcon(lastCoverBitmap);
        }

        return builder.build();
    }

    private PendingIntent createActionPendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, PlaybackService.class);
        intent.setAction(action);
        return PendingIntent.getService(
                this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
    }

    private void requestAudioFocus() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
            }
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
        } else {
            audioManager.abandonAudioFocus(focusListener);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        PlayerController pc = PlayerController.get();
        if (!pc.isPlaying()) {
            abandonAudioFocus();
            pc.stop();
            stopForeground(true);
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        PlayerController.get().removeListener(this);
        if (isNoisyRegistered) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (Throwable ignored) {}
            isNoisyRegistered = false;
        }
        abandonAudioFocus();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
