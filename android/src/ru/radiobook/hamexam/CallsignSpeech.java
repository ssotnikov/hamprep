package ru.radiobook.hamexam;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.Comparator;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plays the vetted phonetic words of one practice callsign using a single installed voice. */
final class CallsignSpeech {
    static final String COMMAND_PATH = "/__callsign-speech/";
    private static final Pattern PLAY = Pattern.compile(
            "play/([0-9]{1,9})/(ru|en)/((?=[A-Z0-9]{4,6}$)[A-Z]{1,2}[0-9][A-Z]{1,4})");
    private static final Pattern STOP = Pattern.compile("stop/([0-9]{1,9})");
    private static final String[] RUSSIAN = ("Анна|Борис|Центр|Дмитрий|Елена|Фёдор|Галина|Харитон|"
            + "Иван|Иван краткий|Константин|Леонид|Михаил|Николай|Ольга|Павел|Щука|Роман|"
            + "Сергей|Татьяна|Ульяна|Женя|Василий|Знак|Игрек|Зинаида|"
            + "Ноль|Один|Два|Три|Четыре|Пять|Шесть|Семь|Восемь|Девять").split("\\|");
    private static final String[] ENGLISH = ("Alfa|Bravo|Charlie|Delta|Echo|Foxtrot|Golf|Hotel|India|"
            + "Juliett|Kilo|Lima|Mike|November|Oscar|Papa|Quebec|Romeo|Sierra|Tango|Uniform|"
            + "Victor|Whiskey|X-ray|Yankee|Zulu|Zero|One|Two|Three|Four|Five|Six|Seven|Eight|Nine").split("\\|");

    private final Context context;
    private final BiConsumer<String, String> report;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> finish("unavailable", true);
    private TextToSpeech engine;
    private boolean ready;
    private String requestId;
    private String language;
    private String callsign;
    private String utterancePrefix;
    private int sequence;

    CallsignSpeech(Context context, BiConsumer<String, String> report) {
        this.context = context.getApplicationContext();
        this.report = report;
    }

    void handleCommand(String command, boolean gesture) {
        Matcher play = PLAY.matcher(command);
        if (gesture && play.matches()) {
            stop();
            requestId = play.group(1);
            language = play.group(2);
            callsign = play.group(3);
            utterancePrefix = "callsign-" + (++sequence) + "-";
            main.postDelayed(timeout, 15_000);
            if (ready) speak();
            else if (engine == null) {
                engine = new TextToSpeech(context, status -> main.post(() -> initialized(status)));
            }
            return;
        }
        Matcher stop = STOP.matcher(command);
        if (stop.matches() && stop.group(1).equals(requestId)) stop();
    }

    void stop() {
        finish("stopped", true);
    }

    void close() {
        stop();
        if (engine != null) engine.shutdown();
        engine = null;
        ready = false;
    }

    private void initialized(int status) {
        if (engine == null) return;
        if (status != TextToSpeech.SUCCESS) {
            finish("unavailable", false);
            engine.shutdown();
            engine = null;
            return;
        }
        engine.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        int listenerResult = engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {
                main.post(() -> {
                    if (isCurrent(id) && id.equals(utterancePrefix + "word-0")) report.accept(requestId, "started");
                });
            }
            @Override public void onDone(String id) {
                main.post(() -> {
                    if (isCurrent(id) && id.equals(utterancePrefix + "last")) finish("finished", false);
                });
            }
            @Override public void onError(String id) {
                main.post(() -> {
                    if (isCurrent(id)) finish("unavailable", true);
                });
            }
            @Override public void onStop(String id, boolean interrupted) {
                main.post(() -> {
                    if (isCurrent(id)) finish("stopped", true);
                });
            }
        });
        if (listenerResult != TextToSpeech.SUCCESS) {
            finish("unavailable", false);
            engine.shutdown();
            engine = null;
            return;
        }
        ready = true;
        if (requestId != null) speak();
    }

    private boolean isCurrent(String utterance) {
        return requestId != null && utterance != null && utterance.startsWith(utterancePrefix);
    }

    private static boolean installedOffline(Voice voice, String language) {
        return voice != null && voice.getLocale().getLanguage().equals(language)
                && !voice.isNetworkConnectionRequired()
                && !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
    }

    private Voice chooseVoice() {
        Voice preferred = engine.getDefaultVoice();
        if (installedOffline(preferred, language)) return preferred;
        Set<Voice> voices = engine.getVoices();
        if (voices == null) return null;
        return voices.stream().filter(voice -> installedOffline(voice, language))
                .sorted(Comparator.comparingInt(Voice::getQuality).reversed().thenComparing(Voice::getName))
                .findFirst().orElse(null);
    }

    private void speak() {
        Voice voice = chooseVoice();
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS
                || engine.setSpeechRate(0.85f) != TextToSpeech.SUCCESS) {
            finish("unavailable", true);
            return;
        }
        String[] words = "ru".equals(language) ? RUSSIAN : ENGLISH;
        for (int index = 0; index < callsign.length(); index++) {
            char letter = callsign.charAt(index);
            int word = letter >= 'A' ? letter - 'A' : 26 + letter - '0';
            int mode = index == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            boolean last = index == callsign.length() - 1;
            String utterance = utterancePrefix + (last ? "last" : "word-" + index);
            if (engine.speak(words[word], mode, null, utterance) != TextToSpeech.SUCCESS
                    || (!last && engine.playSilentUtterance(
                            200, TextToSpeech.QUEUE_ADD, utterancePrefix + "pause-" + index)
                            != TextToSpeech.SUCCESS)) {
                finish("unavailable", true);
                return;
            }
        }
    }

    private void finish(String status, boolean stopEngine) {
        if (requestId == null) return;
        String finished = requestId;
        requestId = null;
        main.removeCallbacks(timeout);
        if (stopEngine && ready) engine.stop();
        report.accept(finished, status);
    }
}
