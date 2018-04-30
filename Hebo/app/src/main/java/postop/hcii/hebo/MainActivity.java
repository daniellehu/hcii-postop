package postop.hcii.hebo;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import ai.api.android.AIConfiguration;
import ai.api.android.AIService;
import ai.api.model.AIContext;
import ai.api.model.AIEvent;
import ai.api.model.AIError;
import ai.api.model.AIOutputContext;
import ai.api.model.AIResponse;
import ai.api.model.AIRequest;
import ai.api.model.Result;
import ai.api.AIDataService;
import ai.api.RequestExtras;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.ImageView;

public class MainActivity extends AppCompatActivity implements RecognitionListener {

    public static final int MY_PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    public static final int VISUAL_ANSWER_OFFSET = 160;
    private SharedPreferences sharedPref;

    private SpeechRecognizer speech = null;
    private Intent recognizerIntent;
    private boolean isFollowUp = false;
    private boolean isBleedingInitial, isBleedingFinal = false;
    private String BODY_PART, DATE_TIME;
    private boolean gaveConsent;

    private ImageView listenButton;
    private android.os.Handler mHandler = new android.os.Handler();
    private RecyclerView mMessageRecycler;
    private MessageListAdapter mMessageAdapter;
    private java.util.List<Message> messageList;
    private AIService aiService;
    private AIDataService aiDataService;
    private TextToSpeech textToSpeech;
    private Config CONFIG;
    private Button consentCancel, consentAgree;
    private Timer currentTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Calendar c = Calendar.getInstance();
        String currentDate = getDate(c);
        String currentTime = getTime(c);

        Bundle b = getIntent().getExtras();
        if (b != null) {
            isBleedingInitial = b.getBoolean("bleeding_initial", false);
            isBleedingFinal = b.getBoolean("bleeding_final", false);
        }

        sharedPref = this.getSharedPreferences("profile", Context.MODE_PRIVATE);
        DATE_TIME = sharedPref.getString("date", currentDate) + " at " + sharedPref.getString("time", currentTime);
        BODY_PART = sharedPref.getString("bodyPart", Config.DEFAULT_SITE);
        gaveConsent = sharedPref.getBoolean("consent", false);

        messageList = new LinkedList<>();
        listenButton = (ImageView) findViewById(R.id.listenButton);
        mMessageRecycler = (RecyclerView) findViewById(R.id.recyclerview_message_list);
        mMessageAdapter = new MessageListAdapter(this, messageList);
        mMessageRecycler.setLayoutManager(new LinearLayoutManager(this));
        mMessageRecycler.setAdapter(mMessageAdapter);

        // Configure speech to text
        speech = SpeechRecognizer.createSpeechRecognizer(this);
        speech.setRecognitionListener(this);
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, this.getPackageName());
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000); // min 10 sec of silence
        recognizerIntent.putExtra("android.speech.extra.DICTATION_MODE", true);


        // Configure Dialogflow
        final AIConfiguration config = new AIConfiguration(CONFIG.DIALOGFLOW_API_KEY,
                AIConfiguration.SupportedLanguages.English,
                AIConfiguration.RecognitionEngine.System);
        aiDataService = new AIDataService(config);

        // Configure text to speech
        textToSpeech = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int i) {
                if (i != TextToSpeech.ERROR) {
                    textToSpeech.setLanguage(Locale.ENGLISH);
                }
            }
        });

        // Configure notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int importance = NotificationManagerCompat.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(Config.NOTIFICATION_CHANNEL, "Notification", importance);
            channel.setDescription("Hebo notifications");
            channel.enableLights(true);
            channel.setLightColor(Color.GREEN);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 100, 1000});

            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
        }


        // if first time user, bring up onboarding activity
        boolean isUserFirstTime = Boolean.valueOf(sharedPref.getString("isFirstTimeUser", "true"));
        Intent introIntent = new Intent(MainActivity.this, Onboarding.class);
        if (isUserFirstTime) { //TODO: REMOVE WHEN NOT TESTING
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.clear().commit();
            startActivity(introIntent);
        }

        // bring up consent & permissions
        if (!gaveConsent) createConsentDialog();
        if (doesNotHavePermission()) getPermissions();

        bleedingTimerCheck();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String permissions[], int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_RECORD_AUDIO: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    addTextMessage("Hello there! How are you doing, and how can I help?", true);
                } else {
                    addTextMessage("Sorry, I can't help you unless you accept microphone permissions!", true);
                }
                return;
            }
        }
    }

    private void bleedingTimerCheck() {
        if (isBleedingInitial && isBleedingFinal) {
            String speech = "Checking in again on the bleeding. Is your " + BODY_PART + " still bleeding?";
            addTextMessage(speech, true);
            textToSpeech.speak(speech, TextToSpeech.QUEUE_FLUSH, null);
            return;
        } else if (isBleedingInitial) {
            String speech = "Hey! How is it going, are you still bleeding?";
            addTextMessage(speech, true);
            textToSpeech.speak(speech, TextToSpeech.QUEUE_FLUSH, null);
            return;
        }
    }

    private void getPermissions() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{android.Manifest.permission.RECORD_AUDIO},
                    MY_PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{android.Manifest.permission.RECORD_AUDIO},
                    MY_PERMISSIONS_REQUEST_RECORD_AUDIO);
        };
    }

    private boolean doesNotHavePermission() {
        return (ContextCompat.checkSelfPermission(MainActivity.this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED);
    }

    public void addTextMessage(String msg, boolean isHebo) {
        int messageType = (isHebo) ? Config.MESSAGE_HEBO_TEXT : Config.MESSAGE_SENT;
        Response response = new Response(msg, false);
        List<Response> responseList = new ArrayList<>();
        responseList.add(response);
        Message myMessage = new Message(responseList, messageType);
        messageList.add(myMessage);
        int position = mMessageAdapter.getItemCount() - 1;
        mMessageAdapter.notifyItemInserted(position);
        mMessageRecycler.scrollToPosition(position);
    }

    public void addVisualMessage(List<String> stringResponses) {
        List<Response> responseList = new ArrayList<>();
        String title = stringResponses.get(0); // first response is always the title
        populateResponseList(responseList, stringResponses);
        Message myMessage = new Message(responseList, Config.MESSAGE_HEBO_VISUAL, title);
        messageList.add(myMessage);
        int position = mMessageAdapter.getItemCount() - 1;
        mMessageAdapter.notifyItemInserted(position);
        mMessageRecycler.scrollBy(0, VISUAL_ANSWER_OFFSET);
    }

    public void addTimerMessage() {
        List<Response> responseList = new ArrayList<>();
        String isSecond = (isBleedingInitial) ? "true" : "false";
        Response response = new Response(isSecond, false);
        responseList.add(response);
        Message myMessage = new Message(responseList, Config.MESSAGE_HEBO_TIMER);
        messageList.add(myMessage);
        int position = mMessageAdapter.getItemCount() - 1;
        mMessageAdapter.notifyItemInserted(position);
        mMessageRecycler.scrollToPosition(position);
        currentTimer = mMessageAdapter.getTimer();
    }

    public void populateResponseList(List<Response> responseList, List<String> stringList) {
        for (int i = 1; i < stringList.size(); i++) {
            String text = stringList.get(i);
            boolean isImage = (text.charAt(0) == '_');
            Response r = new Response(text, isImage);
            responseList.add(r);
        }
    }

    public void listenButtonOnClick(final View view) {
        gaveConsent = sharedPref.getBoolean("consent", false);
        if (gaveConsent) {
            if (doesNotHavePermission()) {
                getPermissions();
            } else {
                speech.startListening(recognizerIntent);
            }
        }
        else createConsentDialog();
    }

    public void listenProfileOnClick(final View view) {
        // go to profile page
        Intent profileIntent = new Intent(this, ProfileActivity.class);
        startActivity(profileIntent);
    }

    @Override
    public void onBeginningOfSpeech() {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
    }

    @Override
    public void onError(int errorCode) {
        if (errorCode != SpeechRecognizer.ERROR_CLIENT) {
            String errorMessage = getErrorText(errorCode);
            addTextMessage(errorMessage, true);
        }
    }

    @Override
    public void onEvent(int arg0, Bundle b) {
    }

    @Override
    public void onPartialResults(Bundle b) {
    }

    @Override
    public void onReadyForSpeech(Bundle b) {
    }

    @Override
    public void onResults(Bundle results) {
        java.util.ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String speech_text = matches.get(0);
        String param_text = "";

        Calendar c = Calendar.getInstance();
        String currentDate = getDate(c);
        String currentTime = getTime(c);

        DATE_TIME = sharedPref.getString("date", currentDate) + " at " + sharedPref.getString("time", currentTime);
        BODY_PART = sharedPref.getString("bodyPart", "head");

        if (isFollowUp) {
            param_text = speech_text;
        } else {
            param_text = "(" + BODY_PART + ")" + " (" + DATE_TIME + ") " + speech_text;
        }
        sendRequest(param_text);
        isFollowUp = false; // reset follow up
    }

    private void sendRequest(final String queryString) {
        Log.d("sent request", queryString);
        final String eventString = null;
        final String contextString = null;
        final android.os.AsyncTask<String, Void, AIResponse> task = new android.os.AsyncTask<String, Void, AIResponse>() {
            private AIError aiError;

            @Override
            protected AIResponse doInBackground(final String... params) {
                final AIRequest request = new AIRequest();
                String query = params[0];
                String event = params[1];

                if (!android.text.TextUtils.isEmpty(query)) request.setQuery(query);
                if (!android.text.TextUtils.isEmpty(event)) request.setEvent(new AIEvent(event));
                final String contextString = (isBleedingInitial || isBleedingFinal) ? Config.STILL_BLEEDING : params[2];
                ai.api.RequestExtras requestExtras = null;
                if(!android.text.TextUtils.isEmpty(contextString)) {
                    final java.util.List<AIContext> contexts = java.util.Collections.singletonList(new AIContext(contextString));
                    requestExtras = new RequestExtras(contexts, null);
                }

                try {
                    return aiDataService.request(request, requestExtras);
                } catch (final ai.api.AIServiceException e) {
                    aiError = new AIError(e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(final AIResponse response) {
                if (response != null) {
                    onResult(response);
                } else {
                    onError(aiError);
                }
            }
        };
        task.execute(queryString, eventString, contextString);
    }

    public void onResult(final AIResponse response) {
        Result result = response.getResult();

        String resolvedQuery = result.getResolvedQuery();
        Log.d("Me", resolvedQuery);
        Log.d("Hebo", result.getFulfillment().getSpeech().toString());
        if (resolvedQuery.charAt(0) == '(') {
            int firstInd = resolvedQuery.indexOf(")");
            int secondInd = resolvedQuery.indexOf(")", firstInd+1);
            resolvedQuery = resolvedQuery.substring(secondInd+2);
        }

        String speech = result.getFulfillment().getSpeech().toString();
        String displayText = result.getFulfillment().getDisplayText();
        displayText = (displayText == null) ? speech : displayText;

        // follow up question, so do not send parameters next time
        isFollowUp = (displayText.charAt(displayText.length()-1) == '?');

        // display my text input
        addTextMessage(resolvedQuery, false);

        // is a visual answer, display visual response from Hebo

        if (displayText.charAt(0) == '(' && displayText.charAt(displayText.length()-1) == ')') {
            String visual_key = displayText.substring(1, displayText.length()-1);
            Resources res = this.getResources();
            int responsesId = res.getIdentifier(visual_key, "array", getPackageName());
            final List<String> responses = Arrays.asList(res.getStringArray(responsesId));
            addVisualMessage(responses);
        } else {
            if (isBleedingFinal && isBleedingInitial) {
                AIOutputContext isStillBleeding = result.getContext("bleeding-yes");
                AIOutputContext isNotBleeding = result.getContext("bleeding-no");

                if (isStillBleeding != null) {
                    speech = "I'm sorry to see that you've been bleeding for the last hour. Let's call doctor Carroll now for further instructions.";
                    addTextMessage("I'm sorry to see that you've been bleeding for the last hour. Let's call Dr. Carroll now for futher instructions.", true);
                } else if (isNotBleeding != null) {
                    speech = "That's good to hear! Let me know if you have any more questions.";
                    addTextMessage(speech, true);
                }
                isBleedingFinal = isBleedingInitial = false;
            }

            else if (isBleedingInitial) {
                    AIOutputContext isStillBleeding = result.getContext("bleeding-yes");
                    AIOutputContext isNotBleeding = result.getContext("bleeding-no");

                    if (isStillBleeding != null) {
                        speech = "Sorry to hear that you're still bleeding! Let's try to apply pressure for 30 more minutes on your " + BODY_PART + ". If you need help, just ask me 'how to apply pressure' for more detailed instructions.";
                        addTextMessage(speech, true);
                        addTimerMessage();
                    } else if (isNotBleeding != null) {
                        speech = "Great job stopping the bleeding! If you have any more questions, just let me know.";
                        addTextMessage(speech, true);
                        isBleedingFinal = isBleedingInitial = false;
                    }
            }

            else {
                // display text response from Hebo
                addTextMessage(displayText, true);

                AIOutputContext startTimer = result.getContext("start-timer");
                if (startTimer!= null) {
                    addTimerMessage();
                }
            }
        }

        // Read out the response
        String toSpeak = speech;
        textToSpeech.speak(speech, TextToSpeech.QUEUE_FLUSH, null);
    }

    private void onError(final AIError error) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                addTextMessage(error.toString(), false);
            }
        });
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    public static String getErrorText(int errorCode) {
        String message;
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                message = "Audio recording error";
                break;
            case SpeechRecognizer.ERROR_CLIENT:
                message = "Client side error";
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                message = "Permission error";
                break;
            case SpeechRecognizer.ERROR_NETWORK:
                message = "Network error";
                break;
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                message = "Network timeout";
                break;
            case SpeechRecognizer.ERROR_NO_MATCH:
                message = "No match";
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                message = "RecognitionService busy";
                break;
            case SpeechRecognizer.ERROR_SERVER:
                message = "error from server";
                break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                message = "Sorry! I didn't catch that. Please try again.";
                break;
            default:
                message = "Didn't understand, please try again.";
                break;
        }
        return message;
    }


    private String getDate(Calendar calendar) {
        int month = calendar.get(Calendar.MONTH);
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        int year = calendar.get(Calendar.YEAR);
        return Integer.toString(month+1) + "/" + Integer.toString(day) + "/" + Integer.toString(year);
    }

    private String getTime(Calendar calendar) {
        int hour = calendar.get(Calendar.HOUR) == 0 ? 12 : calendar.get(Calendar.HOUR);
        int minute = calendar.get(Calendar.MINUTE);
        String am_pm = calendar.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";

        return Integer.toString(hour) + ":" + Integer.toString(minute) + am_pm;
    }

    private void createConsentDialog() {
        AlertDialog.Builder mBuilder = new AlertDialog.Builder(MainActivity.this);
        View mView = getLayoutInflater().inflate(R.layout.activity_consent, null);
        mBuilder.setView(mView);
        final AlertDialog dialog = mBuilder.create();
        dialog.show();

        consentAgree = (Button) mView.findViewById(R.id.agreeButton);
        consentCancel = (Button) mView.findViewById(R.id.cancelButton);

        consentAgree.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
                SharedPreferences.Editor editor = sharedPref.edit();
                editor.putBoolean("consent", true);
                editor.commit();
            }
        });
        consentCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
    }


    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentTimer == null) return;
        outState.putLong("millisLeft", currentTimer.getTimeLeftInMillis());
        outState.putBoolean("timerRunning", currentTimer.getTimerRunning());
        outState.putLong("endTime", currentTimer.getEndTime());
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (currentTimer == null) return;
        currentTimer.setTimeLeftInMillis(savedInstanceState.getLong("millisLeft"));
        currentTimer.setTimerRunning(savedInstanceState.getBoolean("timerRunning"));
        currentTimer.updateCountDownText();

        if (currentTimer.getTimerRunning()) {
            long endTime = savedInstanceState.getLong("endTime");
            currentTimer.setEndTime(endTime);
            currentTimer.setTimeLeftInMillis(endTime - System.currentTimeMillis());
            currentTimer.startTimer();
        }
    }
}

