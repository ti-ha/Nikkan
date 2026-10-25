package com.nhkdaily;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads Japanese aloud. Tries Microsoft Edge's neural voice, then Google
 * Translate's, then the phone's own text-to-speech forced to Japanese (so it
 * never guesses Mandarin). Audio is cached for the day.
 */
final class Speaker {

    interface Listener {
        /** "loading", "playing" or "idle". */
        void onState(String state);
    }

    private static final ExecutorService NET = Executors.newSingleThreadExecutor();

    private final Activity activity;
    private final Listener listener;
    private final File dir;
    private MediaPlayer player;
    private TextToSpeech device;
    private boolean deviceReady;
    private int generation = 0;

    Speaker(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.dir = new File(activity.getCacheDir(), "tts");
    }

    /** Old audio isn't needed once the day's article changes. */
    void clearCache() {
        NhkClient.deleteRecursive(dir);
    }

    void speak(String text, String voice) {
        stop();
        final int my = ++generation;
        listener.onState("loading");
        final File file = new File(dir, hash(voice + "|" + text) + ".mp3");
        NET.submit(() -> {
            if (!file.isFile()) {
                byte[] mp3 = null;
                try {
                    mp3 = Tts.edge(text, voice);
                } catch (Exception edgeFailed) {
                    try {
                        mp3 = Tts.google(text);
                    } catch (Exception googleFailed) {
                        // fall through to the phone's voice
                    }
                }
                if (mp3 != null) {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File tmp = new File(dir, file.getName() + ".part");
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        out.write(mp3);
                    } catch (Exception ignored) {
                    }
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(file);
                }
            }
            activity.runOnUiThread(() -> {
                if (my != generation) return;
                if (file.isFile()) play(file, text, my);
                else speakOnDevice(text, my);
            });
        });
    }

    void stop() {
        generation++;
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
        if (device != null) device.stop();
        listener.onState("idle");
    }

    void shutdown() {
        stop();
        if (device != null) device.shutdown();
        device = null;
    }

    private void play(File file, String text, int my) {
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(file.getPath());
            mp.setOnPreparedListener(p -> {
                if (my != generation) return;
                p.start();
                listener.onState("playing");
            });
            mp.setOnCompletionListener(p -> {
                if (my == generation) {
                    listener.onState("idle");
                    p.release();
                    if (player == p) player = null;
                }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                //noinspection ResultOfMethodCallIgnored
                file.delete(); // bad file: don't reuse it
                if (my == generation) speakOnDevice(text, my);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            speakOnDevice(text, my);
        }
    }

    private void speakOnDevice(String text, int my) {
        if (device == null) {
            device = new TextToSpeech(activity, status -> {
                deviceReady = status == TextToSpeech.SUCCESS;
                if (deviceReady) {
                    device.setLanguage(Locale.JAPAN);
                    device.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String id) {
                            activity.runOnUiThread(() -> {
                                if (id.equals("u" + generation)) listener.onState("playing");
                            });
                        }

                        @Override
                        public void onDone(String id) {
                            activity.runOnUiThread(() -> {
                                if (id.equals("u" + generation)) listener.onState("idle");
                            });
                        }

                        @Override
                        public void onError(String id) {
                            onDone(id);
                        }
                    });
                }
                activity.runOnUiThread(() -> {
                    if (my == generation) speakOnDevice(text, my);
                });
            });
            return;
        }
        if (!deviceReady || device.isLanguageAvailable(Locale.JAPAN) < TextToSpeech.LANG_AVAILABLE) {
            listener.onState("idle"); // no voice available at all
            return;
        }
        device.setLanguage(Locale.JAPAN);
        device.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + my);
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", d[i] & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
