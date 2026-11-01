package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * The spoken greeting that comes with the arc reactor intro: the theme's greeting.mp3 if it has one, otherwise
 * greeting_speech read by the TV's own text-to-speech in greeting_speech_lang. It plays at the TV's media volume,
 * once per intro. The theme folder is on shared storage, which can still be unmounted when Home first starts after
 * a boot, so {@link #sync} keeps a copy of the file in the app's own storage, and that copy is what plays.
 */
final class BootGreeting {
    static final String FILE = "greeting.mp3";
    private static final String TAG = "TvHome.Boot";
    /** How far into the intro the voice starts: as the reactor's segments light up. */
    private static final long DELAY_MS = 450;

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private TextToSpeech tts;

    BootGreeting(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Brings the app's copy of the theme's greeting.mp3 up to date, or removes it once the theme has none. */
    static void sync(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir == null) return;
        File theme = new File(dir, FILE), local = new File(ctx.getFilesDir(), FILE);
        if (!theme.isFile()) {
            if (local.exists() && !local.delete()) Log.w(TAG, "could not remove the old greeting");
            return;
        }
        if (local.isFile() && local.length() == theme.length() && local.lastModified() >= theme.lastModified()) return;
        try {
            Files.copy(theme.toPath(), local.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.w(TAG, "could not copy the greeting", e);
        }
    }

    /** Plays the greeting file, or speaks {@code speech} when there is none; nothing when both are missing. */
    void play(String speech, String lang) {
        release();
        sync(ctx);
        File local = new File(ctx.getFilesDir(), FILE);
        if (local.isFile()) {
            playFile(local);
        } else if (speech != null && !speech.trim().isEmpty()) {
            speak(speech.trim(), lang == null ? "" : lang.trim());
        }
    }

    private void playFile(File file) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(file.getPath());
            mp.setOnPreparedListener(p -> handler.postDelayed(p::start, DELAY_MS));
            mp.setOnCompletionListener(p -> {
                Log.i(TAG, "greeting played");
                release();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "greeting failed: " + what + "/" + extra);
                release();
                return true;
            });
            player = mp;
            mp.prepareAsync();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "greeting not playable", e);
            mp.release();
            player = null;
        }
    }

    private void speak(String text, String lang) {
        long asked = SystemClock.uptimeMillis();
        tts = new TextToSpeech(ctx, status -> {
            TextToSpeech engine = tts;
            if (engine == null) return;
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "text-to-speech unavailable: " + status);
                release();
                return;
            }
            if (!lang.isEmpty()) {
                int ok = engine.setLanguage(Locale.forLanguageTag(lang));
                if (ok < TextToSpeech.LANG_AVAILABLE) Log.w(TAG, "no " + lang + " voice on this TV: " + ok);
            }
            engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    Log.i(TAG, "greeting spoken");
                    handler.post(BootGreeting.this::release);
                }

                @Override
                public void onError(String id) {
                    Log.w(TAG, "greeting could not be spoken");
                    handler.post(BootGreeting.this::release);
                }
            });
            long wait = Math.max(0, DELAY_MS - (SystemClock.uptimeMillis() - asked));
            handler.postDelayed(() -> {
                if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "greeting");
            }, wait);
        });
    }

    void release() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }
}
