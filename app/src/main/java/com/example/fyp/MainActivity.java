package com.example.fyp;
//porcupine ai acc:porcupine3053@gmail.com/ github: porcupine3053-jun/apikey:sRuYbmbPoyXLK10jDnAB+oAKDD8bQrn5nrKGbBAjRkBhJhYm5ZtmDQ==

// ===== ANDROID CORE =====
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.media.ToneGenerator;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

// ===== JAVA CORE =====
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;


// ===== JSON =====
import org.json.JSONObject;

// =====Picovoice=====
import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.porcupine.PorcupineException;
import ai.picovoice.porcupine.PorcupineManager;
import ai.picovoice.porcupine.PorcupineManagerCallback;

public class MainActivity extends AppCompatActivity {

    // --- UI ---
    private TextView tvResult;
    private Button btnSpeak;

    // --- Core ---
    private TextToSpeech tts;
    private Vibrator vibrator;
    private ActivityResultLauncher<Intent> speechLauncher;

    // --- State ---
    private String currentAction = null; // "call", "dial", "add_contact", "news"
    private int stepIndex = 0;
    private String tempName = null;
    private String tempNumber = null;

    // --- News ---
    private org.json.JSONArray newsArticles = null;
    private int newsIndex = 0;

    // --- Config ---
    private final float DEFAULT_SPEECH_RATE = 0.8f;

    // --- pico ---
    private PorcupineManager porcupineManager;
    // --- vibrate ---
    // 🔔 Unique vibration patterns (milliseconds on/off)
    private static final long[] VIBRATE_HOTWORD = {0, 100, 100};             // short double
    private static final long[] VIBRATE_CALL = {0, 300, 150, 300};           // two medium buzz
    private static final long[] VIBRATE_CONTACT = {0, 150, 80, 150, 80, 150}; // triple short
    private static final long[] VIBRATE_NEWS = {0, 500};                     // single long
    private static final long[] VIBRATE_BATTERY_WARNING = {0, 600, 200, 600}; // two long buzzes
    private static final long[] VIBRATE_WEATHER = {0, 100, 100, 100, 100, 100}; // dotted (rain-like)
    private static final long[] VIBRATE_MENU = {0, 100, 50, 200};            // short + medium
    private static final long[] VIBRATE_ERROR = {0, 800};                    // very long single
    private static final long[] VIBRATE_VOLUME = {0, 200, 50, 200};   // two medium buzzes
    private static final long[] VIBRATE_SPEECH = {0, 400};            // long buzz
    private static final long[] VIBRATE_NAME = {0, 100, 50, 300};     // short + long buzz
    private static final long[] VIBRATE_SUCCESS = {0, 50, 50, 50};    // quick confirmation
    //--- settings ---
    private String userName = "User"; //dafault name
    private float speechRate = 1.0f;
    private int volumeLevel = 7;


    // =========================================================
    // LIFECYCLE
    // =========================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerReceiver(getBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        setContentView(R.layout.activity_main);
        loadPreferences();
        //--- pico ---
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }else{
            initPorcupine();
        }
        try {
            String keywordPath = getAssetFilePath(this, "hey-bp_en_android_v3_0_0.ppn");
            porcupineManager = new PorcupineManager.Builder()
                    .setKeywordPath(keywordPath)
                    .setSensitivity(0.7f)
                    .build(getApplicationContext(), keywordIndex -> runOnUiThread(() -> {
                        speakText("Yes, I'm listening.", "hotword");
                        startVoiceInput();
                    }));
            porcupineManager.start();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error loading keyword", Toast.LENGTH_SHORT).show();
        } catch (PorcupineException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error starting Porcupine", Toast.LENGTH_SHORT).show();
        }


        tvResult = findViewById(R.id.tvResult);
        btnSpeak = findViewById(R.id.btnSpeak);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        // --- Speech recognizer ---
        speechLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> matches = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                        String userSpeech = matches.get(0).toLowerCase();
                        tvResult.setText(userSpeech);
                        processCommand(userSpeech);
                    }
                });

        // --- Initialize TTS ---
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                Locale localeMY = new Locale("en", "MY");
                int result = tts.setLanguage(localeMY);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(MainActivity.this, "TTS language en-MY not supported, using default.", Toast.LENGTH_SHORT).show();
                    tts.setLanguage(Locale.getDefault());
                }
                tts.setSpeechRate(DEFAULT_SPEECH_RATE);

                setSpeechRate(speechRate);
                setVolume(volumeLevel);

                tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        runOnUiThread(() -> {
                            if ("guide".equals(utteranceId) || "reset".equals(utteranceId)
                                    || "time_response".equals(utteranceId)
                                    || "date_response".equals(utteranceId)
                                    || "weather_response".equals(utteranceId)) {
                                startVoiceInput();
                            } else if (currentAction != null) {
                                startVoiceInput();
                            }
                        });
                    }

                    @Override
                    public void onError(String utteranceId) {
                        vibratePattern(VIBRATE_ERROR);
                        speakText("error", "reset");
                    }
                });
                guideUserMenu();
            }
        });

        // --- Permissions ---
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CALL_PHONE}, 2);
        }

        btnSpeak.setOnClickListener(v -> startVoiceInput());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        savePreferences();
        if (porcupineManager != null) {
            try {
                porcupineManager.stop();
            } catch (PorcupineException e) {
                throw new RuntimeException(e);
            }
            porcupineManager.delete();
        }
        try {
            unregisterReceiver(getBatteryReceiver);
        } catch (IllegalArgumentException e) {
            // ignore
        }
    }


    // =========================================================
    // UTILITIES
    // =========================================================

    //===== pico service =====
    private void initPorcupine() {
        try {
            String keywordPath = getAssetFilePath(this, "hey-bp_en_android_v3_0_0.ppn");
            porcupineManager = new PorcupineManager.Builder()
                    .setKeywordPath(keywordPath)
                    .setSensitivity(0.7f)
                    .build(getApplicationContext(), keywordIndex -> runOnUiThread(() -> {
                        vibratePattern(VIBRATE_HOTWORD);
                        speakText("Yes, I'm listening.", "hotword");
                        startVoiceInput();
                    }));
            porcupineManager.start();
            Toast.makeText(this, "Porcupine started", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Porcupine failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    public void onHotwordDetected() {
        vibratePattern(VIBRATE_HOTWORD);
        speakText("Yes, I'm listening.", "hotword");
        startVoiceInput();
    }

    private String getAssetFilePath(Context context, String assetName) throws IOException {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists()) {
            return file.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[4 * 1024];
            int length;
            while ((length = is.read(buffer)) != -1) {
                fos.write(buffer, 0, length);
            }
        }
        return file.getAbsolutePath();
    }

    private void readNextHeadline() {
        try {
            if (newsIndex >= 5) {
                speakText("I have told you the top 5 news headlines. Returning to menu.", "reset");
                resetCallFlow();
                return;
            }
            if (newsArticles != null && newsIndex < newsArticles.length()) {
                JSONObject article = newsArticles.getJSONObject(newsIndex);
                String headline = article.optString("title", "");
                String description = article.optString("description", "");

                String toSpeak = "Headline " + (newsIndex + 1) + ": " + headline;
                if (!description.isEmpty()) {
                    toSpeak += ". Summary: " + description;
                }
                toSpeak += ". Do you want to hear more?";

                speakText(toSpeak, "news_response");
                newsIndex++;
            } else {
                speakText("That's all the news for now. Returning to menu.", "reset");
                resetCallFlow();
            }
        } catch (Exception e) {
            speakText("Sorry, I had trouble reading the news. Returning to menu.", "reset");
            resetCallFlow();
        }
    }

    private String readStream(HttpURLConnection connection) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            result.append(line);
        }
        reader.close();
        return result.toString();
    }

    private void speakText(String text, String utteranceId) {
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
    }

    private void speakConfirm(String text) {
        tts.setSpeechRate(0.7f);
        speakText(text, "reset");
        tts.setSpeechRate(DEFAULT_SPEECH_RATE);
    }

    //===== VIBRATE FUNCTION =====
    private void vibratePattern(long[] pattern) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(pattern, -1);
            }
        }
    }


    private void repeatLastStep() {
        if ("call".equals(currentAction)) {
            if (stepIndex == 1) {
                speakText("Tell me the name.", "reset");
            } else if (stepIndex == 2) {
                speakText("Did you say " + tempName + "?", "reset");
            } else if (stepIndex == 3) {
                speakText("Call failed. Say again to retry, or say later to return to menu.", "reset");
            }
        } else if ("dial".equals(currentAction)) {
            if (stepIndex == 1) {
                speakText("Tell me the digits.", "reset");
            } else if (stepIndex == 2) {
                speakConfirm("Did you say " + tempNumber + "?");
            } else if (stepIndex == 3) {
                speakText("Call failed. Say again to retry, or say later to return to menu.", "reset");
            }
        } else if ("add_contact".equals(currentAction)) {
            if (stepIndex == 1) {
                speakText("Tell me the contact name.", "reset");
            } else if (stepIndex == 2) {
                speakText("Now tell me the number for " + tempName, "reset");
            } else if (stepIndex == 3) {
                speakText("Did you say " + tempName + " with number " + tempNumber + "?", "reset");
            }
        }
    }

    private String cleanNumber(String input) {
        return input.replaceAll("[^0-9]", "");
    }

    private boolean isValidMalaysiaNumber(String number) {
        return number.matches("^0\\d{9,10}$");
    }

    private void resetCallFlow() {
        vibratePattern(VIBRATE_MENU);
        currentAction = null;
        stepIndex = 0;
        tempName = null;
        tempNumber = null;
        guideUserMenu();
    }

    private void saveContact(String name, String number) {
        vibratePattern(VIBRATE_CONTACT);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_CONTACTS}, 4);
            speakText("Please grant contacts permission and try again.", "reset");
            return;
        }

        ArrayList<ContentProviderOperation> ops = new ArrayList<>();

        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build());

        // Name
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build());

        // Phone Number
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build());
        try {
            getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            speakText("Contact saved successfully. Returning to menu.", "reset");
            resetCallFlow();
        } catch (Exception e) {
            speakText("Failed to save contact. Returning to menu.", "reset");
        }
    }

    private String findMatchingContact(String spokenName) {
        SharedPreferences prefs = getSharedPreferences("contacts", MODE_PRIVATE);
        for (String key : prefs.getAll().keySet()) {
            if (spokenName.contains(key) || key.contains(spokenName)) {
                return prefs.getString(key, null);
            }
        }
        return null;
    }

    private boolean makePhoneCall(String phoneNumber) {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CALL_PHONE}, 2);
                speakText("Please grant call permission and try again", "reset");
                return false;
            }
            Intent intent = new Intent(Intent.ACTION_CALL);
            intent.setData(Uri.parse("tel:" + phoneNumber));
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String getContactNumber(String name) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_CONTACTS}, 3);
            speakText("Please grant contacts permission and try again.", "reset");
            return null;
        }

        String phoneNumber = null;
        Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        String[] projection = {ContactsContract.CommonDataKinds.Phone.NUMBER};
        String selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?";
        String[] selectionArgs = {"%" + name + "%"};

        try (Cursor cursor = getContentResolver().query(uri, projection, selection, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                phoneNumber = cursor.getString(0);
            }
        }
        return phoneNumber;
    }

    // =========================================================
    // MENU & INPUT
    // =========================================================
    private void guideUserMenu() {
        speakText("Hello " + userName + ". How can I help you? "
                + "You can say: tell me the time, tell me the date, check the weather in a city, "
                + "call, dial, add contact, news, battery status, or settings.", "guide");
    }


    private void startVoiceInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-MY");
        speechLauncher.launch(intent);
    }

    // =========================================================
    // WEATHER & NEWS
    // =========================================================
    private void fetchWeather(String city) {
        vibratePattern(VIBRATE_WEATHER);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            try {
                String apiKey = "49fdb54f6a3ef012fda900f7c217a9a3";
                String apiUrl = "https://api.openweathermap.org/data/2.5/weather?q=" + city + "&appid=" + apiKey + "&units=metric";
                HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
                connection.setRequestMethod("GET");

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) result.append(line);

                handler.post(() -> {
                    try {
                        JSONObject json = new JSONObject(result.toString());
                        JSONObject main = json.getJSONObject("main");
                        double temp = main.getDouble("temp");
                        int humidity = main.getInt("humidity");
                        String description = json.getJSONArray("weather").getJSONObject(0).getString("description");
                        speakText("In " + city + ", it is currently " + description + " with " + temp + " degrees Celsius and " + humidity + " percent humidity. Say start to return to menu.", "weather_response");
                    } catch (Exception e) {
                        speakText("Sorry, I couldn't parse the weather information.", "weather_response");
                    }
                });
            } catch (Exception e) {
                handler.post(() -> speakText("Sorry, I couldn't retrieve the weather.", "weather_response"));
            }
        });
    }

    //=== BATTERY STATUS ===//
    //Track if already warned
    private boolean lowBaterryWarned = false;
    private boolean criticalBatteryWarned = false;

    private BroadcastReceiver getBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

            float batteryPct = (level / (float) scale) * 100;

            // Reset warning flags if battery is charging or above 30%
            if (status != BatteryManager.BATTERY_STATUS_CHARGING && batteryPct >= 30) {
                lowBaterryWarned = false;
                criticalBatteryWarned = false;
                return;
            }
            //Warn at 20 %
            if (batteryPct <= 20 && !lowBaterryWarned) {
                speakText("Warning. Battery is low. Only " + (int) batteryPct + "percent remaining.", "battery_warning");
                lowBaterryWarned = true;
                vibratePattern(VIBRATE_BATTERY_WARNING);
            }
            //Extra warning at 10%
            if (batteryPct <= 10 && !criticalBatteryWarned) {
                speakText("Warning. Battery is critically low. Only " + (int) batteryPct + "percent remaining.", "battery_warning");
                criticalBatteryWarned = true;
                vibratePattern(VIBRATE_BATTERY_WARNING);
            }
        }
    };

    private void getBatteryStatus() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = this.registerReceiver(null, ifilter);

        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

            float batteryPct = (level / (float) scale) * 100;

            String statusText;
            switch (status) {
                case BatteryManager.BATTERY_STATUS_CHARGING:
                    statusText = "Charging";
                    break;
                case BatteryManager.BATTERY_STATUS_FULL:
                    statusText = "fully charged";
                    break;
                default:
                    statusText = "not charging";
                    break;
            }

            speakText("The battery is at " + (int) batteryPct + " percent and is currently " + statusText + ". ", "battery_response");
        } else {
            speakText("Sorry, I couldn't retrieve the battery status.", "battery_response");
        }
    }

    // === NEWS (using GNews API) ===
    private void fetchNews(String category) {
        vibratePattern(VIBRATE_NEWS);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            try {
                String apiKey = "dc7742d40f80892f57e74d946a08dfc1"; // replace with your key
                String apiUrl = "https://gnews.io/api/v4/top-headlines?token=" + apiKey + "&lang=en&country=my";

                if (category != null && !category.isEmpty()) {
                    apiUrl += "&topic=" + category; // GNews uses "topic" instead of "category"
                }

                HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
                connection.setRequestMethod("GET");

                String response = readStream(connection);

                handler.post(() -> {
                    try {
                        JSONObject json = new JSONObject(response);
                        if (json.has("articles")) {
                            newsArticles = json.getJSONArray("articles");
                            newsIndex = 0;
                            readNextHeadline();
                            currentAction = "news";
                        } else {
                            speakText("Sorry, I couldn't fetch the news right now.", "news_response");
                        }
                    } catch (Exception e) {
                        speakText("Sorry, I couldn't parse the news information.", "news_response");
                    }
                });
            } catch (Exception e) {
                handler.post(() -> speakText("Sorry, I couldn't retrieve the news.", "news_response"));
            }
        });
    }

    // =========================================================
    // SETTINGS
    // =========================================================
    private void setSpeechRate(float rate) {
        if (tts != null) {
            if (rate < 0.5f) rate = 0.5f;
            if (rate > 2.0f) rate = 2.0f;

            tts.setSpeechRate(rate);
            speechRate = rate;
            vibratePattern(VIBRATE_SUCCESS);
            speakText("Speech rate set to " + rate + ". Here is a preview. "
                    + "The quick brown fox jumps over the lazy dog.", "reset");
        }
        savePreferences();
    }


    private void setVolume(int volume) {
        if(volume < 0 ) volume = 0;
        if(volume > 15) volume = 15;

        AudioManager audioManager = (AudioManager) getSystemService((Context.AUDIO_SERVICE));
        if (audioManager != null) {
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

            //scale to device max
            int newVolume = (int)((volume/15.0f)* maxVolume);

            // --- detect direction ---
            int oldVolume = this.volumeLevel;

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume,0);

            this.volumeLevel = volume;

            //vibrate confirmation
            vibratePattern(VIBRATE_SUCCESS);

            //Play a short beep as confirmation
            ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC,100);
            if(volume > oldVolume){
                //higher pitch tone for volume increase
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 150); // 150ms beep)
            }else if (volume < oldVolume){
                //lower pitch tone for volume decrease
                toneGen.startTone(ToneGenerator.TONE_CDMA_LOW_PBX_L, 150); // 150ms beep
            }else {
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150); // 150ms beep
            }
            //TTS Confirmation
            speakText("Volume set to " + (int)(volume * 100) +"percent.","reset");
        }
        savePreferences();
    }

    private void setUserName(String name) {
        userName = name;
        vibratePattern(VIBRATE_SUCCESS);
        speakText("Your username is now set to " + name + ". Hello " + name + " ! ", "reset");
        savePreferences();
    }

    private void savePreferences() {
        SharedPreferences prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("username", userName);
        editor.putInt("volume_level", volumeLevel);
        editor.putFloat("speech_rate", speechRate);
        editor.apply();
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        userName = prefs.getString("username", "User");
        speechRate = prefs.getFloat("speech_rate", 1.0f);
        volumeLevel = prefs.getInt("volume_level", 7);
    }

    // =========================================================
    // CALL / DIAL / ADD CONTACT FLOW
    // =========================================================
    private void handleCallFlow(String userSpeech) {
        vibratePattern(VIBRATE_CALL);
        userSpeech = userSpeech.toLowerCase();
        if (userSpeech.contains("cancel") || userSpeech.contains("menu")) {
            speakText("Cancelled. Returning to main menu.", "reset");
            resetCallFlow();
            return;
        }

        if (currentAction == null) {
            if (userSpeech.contains("call")) {
                currentAction = "call";
                stepIndex = 1;
                speakText("Tell me the name.", "reset");
                return;
            } else if (userSpeech.contains("dial")) {
                currentAction = "dial";
                stepIndex = 1;
                speakText("Tell me the digits.", "reset");
                return;
            } else if (userSpeech.contains("add contact")) {
                currentAction = "add_contact";
                stepIndex = 1;
                speakText("Tell me the contact name.", "reset");
                return;
            }
        }

        if ("call".equals(currentAction)) {
            if (stepIndex == 1) {
                tempName = userSpeech.trim();
                speakConfirm("Did you say " + tempName + "?");
                stepIndex = 2;
            } else if (stepIndex == 2) {
                if (userSpeech.contains("yes")) {
                    String number = getContactNumber(tempName);
                    if (number != null) {
                        boolean success = makePhoneCall(number);
                        if (!success) {
                            speakText("Call failed. Say again to retry, or later to return to menu.", "reset");
                            stepIndex = 3;
                        } else resetCallFlow();
                    } else {
                        speakText("I don't have that contact saved. Returning to menu.", "reset");
                        resetCallFlow();
                    }
                } else {
                    speakText("Okay, returning to menu.", "reset");
                    resetCallFlow();
                }
            } else if (stepIndex == 3) {
                if (userSpeech.contains("again")) {
                    String number = findMatchingContact(tempName);
                    if (number != null) makePhoneCall(number);
                    resetCallFlow();
                } else {
                    speakText("Okay, returning to menu.", "reset");
                    resetCallFlow();
                }
            }
        } else if ("dial".equals(currentAction)) {
            if (stepIndex == 1) {
                tempNumber = cleanNumber(userSpeech);
                if (isValidMalaysiaNumber(tempNumber)) {
                    speakConfirm("Did you say " + tempNumber + "?");
                    stepIndex = 2;
                } else {
                    speakText("That doesn't seem like a valid Malaysian number. Returning to menu.", "reset");
                    resetCallFlow();
                }
            } else if (stepIndex == 2) {
                if (userSpeech.contains("yes")) {
                    boolean success = makePhoneCall(tempNumber);
                    if (!success) {
                        speakText("Call failed. Say again to retry, or later to return to menu.", "reset");
                        stepIndex = 3;
                    } else resetCallFlow();
                } else {
                    speakText("Okay, returning to menu.", "reset");
                    resetCallFlow();
                }
            } else if (stepIndex == 3) {
                if (userSpeech.contains("again")) {
                    makePhoneCall(tempNumber);
                    resetCallFlow();
                } else {
                    speakText("Okay, returning to menu.", "reset");
                    resetCallFlow();
                }
            }
        } else if ("add_contact".equals(currentAction)) {
            if (stepIndex == 1) {
                tempName = userSpeech.trim();
                speakText("Now tell me the number for " + tempName, "reset");
                stepIndex = 2;
            } else if (stepIndex == 2) {
                tempNumber = cleanNumber(userSpeech);
                if (isValidMalaysiaNumber(tempNumber)) {
                    speakConfirm("Did you say " + tempName + " with number " + tempNumber + "?");
                    stepIndex = 3;
                } else {
                    speakText("That doesn't seem like a valid Malaysian number. Returning to menu.", "reset");
                    resetCallFlow();
                }
            } else if (stepIndex == 3) {
                if (userSpeech.contains("yes")) {
                    saveContact(tempName, tempNumber);
                    speakText("Contact saved successfully. Returning to menu.", "reset");
                    resetCallFlow();
                } else if (userSpeech.contains("again")) {
                    speakText("Okay, tell me the name again.", "reset");
                    stepIndex = 1;
                } else {
                    speakText("Okay, returning to menu.", "reset");
                    resetCallFlow();
                }
            }
        }
    }


    // =========================================================
    // COMMAND HANDLER
    // =========================================================
    private void processCommand(String command) {
        command = command.toLowerCase().trim();

        // --- Global menu commands ---
        if (command.contains("menu") || command.contains("start")) {
            speakText("Returning to main menu.", "reset");
            resetCallFlow();
            return;
        }
        if (command.contains("later")) {
            speakText("Okay, returning to main menu.", "reset");
            resetCallFlow();
            return;
        }
        if (command.contains("again")) {
            if (currentAction != null && stepIndex > 0) {
                repeatLastStep();
            } else {
                speakText("There's nothing to repeat. Returning to main menu.", "reset");
                resetCallFlow();
            }
            return;
        }

        // --- SETTINGS MENU ---
        if (command.contains("settings") || command.contains("custom")) {
            vibratePattern(VIBRATE_MENU);
            speakText("Settings menu. You can say: change volume, change speech rate, or change name.", "settings_menu");
            currentAction = "settings";
            stepIndex = 0;
            return;
        }

        if ("settings".equals(currentAction)) {
            if (stepIndex == 0) {
                if (command.contains("volume")) {
                    vibratePattern(VIBRATE_VOLUME);
                    speakText("What volume level? Say a number between 0 and 15.", "settings_volume");
                    stepIndex = 1;
                    return;
                } else if (command.contains("speech rate") || command.contains("speed")) {
                    vibratePattern(VIBRATE_SPEECH);
                    speakText("What speech rate? Say 1 for normal, 0.5 for slow, 1.5 for faster.", "settings_speed");
                    stepIndex = 2;
                    return;
                } else if (command.contains("name") || command.contains("username")) {
                    vibratePattern(VIBRATE_NAME);
                    speakText("What is your name?", "settings_name");
                    stepIndex = 3;
                    return;
                }
            }

            if (stepIndex == 1) { // volume
                try {
                    int vol = Integer.parseInt(command.replaceAll("[^0-9]", ""));
                    setVolume(vol);
                } catch (Exception e) {
                    vibratePattern(VIBRATE_ERROR);
                    speakText("Sorry, I didn't get the number. Try again.", "settings_error");
                }
                resetCallFlow();
                return;
            }
            if (stepIndex == 2) { // speech rate
                try {
                    float rate = Float.parseFloat(command.replaceAll("[^0-9.]", ""));
                    setSpeechRate(rate);
                } catch (Exception e) {
                    speakText("Sorry, I didn't get the number. Try again.", "settings_error");
                    vibratePattern(VIBRATE_ERROR);
                }
                resetCallFlow();
                return;
            }
            if (stepIndex == 3) { // username
                String name = command.replace("my name is", "").trim();
                setUserName(name);
                resetCallFlow();
                return;
            }
        }

        // --- Battery ---
        if (command.contains("battery") || command.contains("power")) {
            getBatteryStatus();
            resetCallFlow();
            return;
        }

        // --- Calls / Dial / Add contact ---
        if (command.contains("call") || command.contains("dial") || command.contains("add contact")) {
            handleCallFlow(command);
            return;
        }

        // --- News ---
        if ("news".equals(currentAction)) {
            if (command.contains("yes") || command.contains("more")) {
                readNextHeadline();
                return;
            } else if (command.contains("no") || command.contains("stop")) {
                speakText("Okay, returning to main menu.", "reset");
                resetCallFlow();
                return;
            } else if (command.contains("full")) {
                try {
                    JSONObject article = newsArticles.getJSONObject(newsIndex - 1);
                    String url = article.optString("url", "");
                    if (!url.isEmpty()) {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                        speakText("Opening full article in browser.", "news_response");
                    }
                } catch (Exception e) {
                    speakText("Sorry, I couldn't get the article link.", "news_response");
                }
                return;
            }
        }
        if (command.contains("news") || command.contains("headline") || command.contains("breaking")) {
            String category = null;
            if (command.contains("sports")) category = "sports";
            else if (command.contains("business")) category = "business";
            else if (command.contains("technology")) category = "technology";
            fetchNews(category);
            return;
        }

        // --- Time & Date ---
        if (command.contains("time") && command.contains("date")) {
            SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a", Locale.getDefault());
            speakText("Current time is " + sdf.format(new Date()) +
                    " and today's date is " + java.text.DateFormat.getDateInstance().format(new Date()) +
                    ". Say start to return to menu.", "time_response");
            return;
        }
        if (command.contains("time")) {
            SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a", Locale.getDefault());
            speakText("Current time is " + sdf.format(new Date()) + ". Say start to return to menu.", "time_response");
            return;
        }
        if (command.contains("date")) {
            speakText("Today's date is " + java.text.DateFormat.getDateInstance().format(new Date()) +
                    ". Say start to return to menu.", "date_response");
            return;
        }

        // --- Weather ---
        if (command.contains("weather") || command.contains("temperature")) {
            String city = command.replaceAll(".*in ", "").trim();
            fetchWeather(city);
            return;
        }

        // --- Default fallback ---
        speakText("You said: " + command + ". I didn't understand. Say start to return to menu.", "reset");
    }
}
