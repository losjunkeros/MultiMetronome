package com.example.silly_000.multimetronome;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.NumberPicker;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.Connections;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static GoogleApiClient mGoogleApiClientSlave;
    private static GoogleApiClient mGoogleApiClientMaster;
    private static String mRemoteHostEndpoint;
    private static boolean mIsConnected;
    private static final String NEARBY_SERVICE_ID = "@string/service_id";
    private static List<String> mRemotePeerEndpoints = new ArrayList<>();

    private static String mode = "SLAVE";
    private static int tempo = 120;
    private static String messageString = "";
    private static String accent = "4";
    private static long time = 0L, prev_time = 0L;
    private static float x = 0, prev_x_1 = 0, prev_x_2 = 0, prev_x_3 = 0;
    private static Thread metronomeThread = null;
    private static boolean isOn = false;
    private static boolean isSyncOn = false;

    /** Służy do wyświetlania komunikatów logcat */
    private static final String LOG_TAG = MainActivity.class.getSimpleName();
    /** Częstotliwość próbkowania danych z mikrofonu. */
    private static final int RECORDER_SAMPLE_RATE = 44100;
    /** Ilość nagrywanych kanałów (mono/stereo). */
    private static final int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    /** Format enkodowania danych z mikrofonu (16-bitowy PCM). */
    private static final int RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    /** Obiekt klasy AudioRecord. */
    private static AudioRecord recorder = null;
    /** Wątek do obsługi pobierania danych z mikrofonu i ich analizy. */
    private static Thread recordingThread = null;
    /** Czy trwa nagrywanie dźwięku z mikrofonu? */
    public static boolean isRecording = false;
    /** Rozmiar buffora, do którego trafiają dane. */
    private static int bufferSize = AudioRecord.getMinBufferSize(RECORDER_SAMPLE_RATE, RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING);


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final MediaPlayer mp1 = MediaPlayer.create(this, R.raw.metronome1);
        final MediaPlayer mp2 = MediaPlayer.create(this, R.raw.metronome2);

        final Spinner spinner = (Spinner) findViewById(R.id.spinner);
        final ArrayAdapter<CharSequence> adapter = ArrayAdapter
                .createFromResource(this, R.array.accent_array,
                        android.R.layout.simple_spinner_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(adapter.getPosition("4"));
        spinner.setOnItemSelectedListener(new OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                accent = (String) parent.getItemAtPosition(position);
                if(mGoogleApiClientMaster.isConnected() && !mRemotePeerEndpoints.isEmpty()) {
                    sendMessage(accent);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // TODO Auto-generated method stub
            }
        });

        final NumberPicker picker = (NumberPicker) findViewById(R.id.numberPicker);
        picker.setMinValue(20);
        picker.setMaxValue(260);
        picker.setValue(120);
        picker.setWrapSelectorWheel(false);
        picker.setOnValueChangedListener(new NumberPicker.OnValueChangeListener() {
            @Override
            public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
                tempo = newVal;
                if(mGoogleApiClientMaster.isConnected() && !mRemotePeerEndpoints.isEmpty()) {
                    sendMessage(Integer.toString(tempo));
                }
            }
        });

        final ToggleButton button1 = (ToggleButton) findViewById(R.id.button1);
        button1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(button1.isChecked()) {
                    isOn = true;
                    if(mGoogleApiClientMaster.isConnected() && !mRemotePeerEndpoints.isEmpty()) {
                        sendMessage("START");
                    }
                    metronome(mp1, mp2, button1);
                }
                else {
                    isOn = false;
                    button1.setChecked(false);
                    if(mGoogleApiClientMaster.isConnected() && !mRemotePeerEndpoints.isEmpty()) {
                        sendMessage("STOP");
                    }
                    try {
                        metronomeThread.join();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    metronomeThread = null;
                }
            }
        });

        final Button button2 = (Button) findViewById(R.id.button2);
        button2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prev_time = time;
                time = System.currentTimeMillis();
                prev_x_3 = prev_x_2;
                prev_x_2 = prev_x_1;
                prev_x_1 = x;
                x = (1000 / (float) (time - prev_time) * 60);
                float mean = (x + prev_x_1 + prev_x_2 + prev_x_3) / 4;
                if(mean >= 20 && mean <= 260 && x != 0 && prev_x_1 != 0 && prev_x_2 != 0 && prev_x_3 != 0) {
                    tempo = (int) mean;
                    picker.setValue(tempo);
                    if(mGoogleApiClientMaster.isConnected() && !mRemotePeerEndpoints.isEmpty()) {
                        sendMessage(Integer.toString(tempo));
                    }
                }
            }
        });

        final Switch mySwitch = (Switch) findViewById(R.id.switch1);
        mySwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(mySwitch.isChecked()) {
                    button1.setEnabled(false);
                    button2.setEnabled(false);
                    if(isOn) {
                        isOn = false;
                        try {
                            metronomeThread.join();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        metronomeThread = null;
                    }
                    audioAnalysis(picker);
                }
                else {
                    if(isRecording) {
                        stopAudioAnalysis();
                    }
                    if(!isOn && button1.isChecked()) {
                        isOn = true;
                        metronome(mp1, mp2, button1);
                    }
                    button1.setEnabled(true);
                    button2.setEnabled(true);
                }
            }
        });

        final ToggleButton toggleButton1 = (ToggleButton) findViewById(R.id.toggleButton);
        final ToggleButton toggleButton2 = (ToggleButton) findViewById(R.id.toggleButton2);
        final ToggleButton toggleButton3 = (ToggleButton) findViewById(R.id.toggleButton3);

        if(mode.equals("SLAVE")) {
            toggleButton1.setChecked(false);
            toggleButton2.setChecked(true);
        }
        if(mode.equals("MASTER")) {
            toggleButton1.setChecked(true);
            toggleButton2.setChecked(false);
        }
        if(isSyncOn) {
            toggleButton3.setChecked(true);
            toggleButton1.setEnabled(false);
            toggleButton2.setEnabled(false);;
        } else {
            toggleButton3.setChecked(false);
            toggleButton1.setEnabled(true);
            toggleButton2.setEnabled(true);
        }


        toggleButton1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(toggleButton1.isChecked()) {
                    mode = "MASTER";
                    toggleButton2.setChecked(false);
                }
                else {
                    toggleButton1.setChecked(true);
                }
            }
        });

        toggleButton2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(toggleButton2.isChecked()) {
                    mode = "SLAVE";
                    toggleButton1.setChecked(false);
                }
                else {
                    toggleButton2.setChecked(true);
                }
            }
        });

        toggleButton3.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(toggleButton3.isChecked()) {
                    toggleButton1.setEnabled(false);
                    toggleButton2.setEnabled(false);
                    if(!isSyncOn) {
                        startComm(spinner, picker, button1, button2, mySwitch);
                    }
                }
                else {
                    toggleButton1.setEnabled(true);
                    toggleButton2.setEnabled(true);
                    if(isSyncOn) {
                        stopComm(spinner, picker, button1, button2, mySwitch);
                    }
                }
            }
        });

        mGoogleApiClientSlave = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(@Nullable Bundle bundle) {
                        Log.v(LOG_TAG, "onConnected: start discovering hosts to send connection requests");
                        startDiscovery(spinner, picker, mp1, mp2, button1, adapter);
                    }

                    @Override
                    public void onConnectionSuspended(int i) {
                        Log.v(LOG_TAG, "onConnectionSuspended: " + i);
                        mGoogleApiClientSlave.reconnect();
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                        Log.v(LOG_TAG, "onConnectionFailed: " + connectionResult.getErrorCode());
                    }
                })
                .addApi(Nearby.CONNECTIONS_API)
                .build();

        mGoogleApiClientMaster = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(@Nullable Bundle bundle) {
                        Log.v(LOG_TAG, "onConnected: start advertising");
                        startAdvertising();
                    }

                    @Override
                    public void onConnectionSuspended(int i) {
                        Log.v(LOG_TAG, "onConnectionSuspended: " + i);
                        mGoogleApiClientMaster.reconnect();
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                        Log.v(LOG_TAG, "onConnectionFailed: " + connectionResult.getErrorCode());
                    }
                })
                .addApi(Nearby.CONNECTIONS_API)
                .build();
    }

    private void startAdvertising() {
        Log.v(LOG_TAG, "startAdvertising");

        Nearby.Connections
                .startAdvertising(mGoogleApiClientMaster, null, NEARBY_SERVICE_ID, new ConnectionLifecycleCallback() {
                            @Override
                            public void onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo) {
                                Log.v(LOG_TAG, "onConnectionInitiated. Token: " + connectionInfo.getAuthenticationToken());
                                // Automatically accept the connection on both sides"
                                Nearby.Connections.acceptConnection(mGoogleApiClientMaster, endpointId, new PayloadCallback() {
                                    @Override
                                    public void onPayloadReceived(String endpointId, Payload payload) {
                                        if (payload.getType() == Payload.Type.BYTES) {
                                            Log.v(LOG_TAG, "onPayloadReceived: " + new String(payload.asBytes()));
                                            //Nearby.Connections.sendPayload(mGoogleApiClientMaster, endpointId, Payload.fromBytes("ACK".getBytes(Charset.forName("UTF-8"))));
                                        }
                                    }

                                    @Override
                                    public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
                                        // Provides updates about the progress of both incoming and outgoing payloads
                                    }
                                });
                            }

                            @Override
                            public void onConnectionResult(String endpointId, ConnectionResolution resolution) {
                                Log.v(LOG_TAG, "onConnectionResult");
                                if (resolution.getStatus().isSuccess()) {
                                    if (!mRemotePeerEndpoints.contains(endpointId)) {
                                        mRemotePeerEndpoints.add(endpointId);
                                    }
                                    Toast.makeText(MainActivity.this, "Connected to " + endpointId + "!",
                                            Toast.LENGTH_LONG).show();
                                    if(mGoogleApiClientMaster.isConnected() && !mRemotePeerEndpoints.isEmpty()) {
                                        sendMessage(Integer.toString(tempo));
                                        sendMessage(accent);
                                        if(isOn) {
                                            sendMessage("START");
                                        }
                                    }
                                    Log.v(LOG_TAG, "Connected! (endpointId=" + endpointId + ")");
                                } else {
                                    Toast.makeText(MainActivity.this, "Connection to " + endpointId + " failed.",
                                            Toast.LENGTH_LONG).show();
                                    Log.v(LOG_TAG, "Connection to " + endpointId + " failed. Code: " + resolution.getStatus().getStatusCode());
                                }
                            }

                            @Override
                            public void onDisconnected(String endpointId) {
                                // We've been disconnected from this endpoint. No more data can be sent or received.
                                Toast.makeText(MainActivity.this, "Disconnected from " + endpointId + ".",
                                        Toast.LENGTH_LONG).show();
                                Log.v(LOG_TAG, "onDisconnected: " + endpointId);
                            }
                        },
                        new AdvertisingOptions(Strategy.P2P_STAR)
                )
                .setResultCallback(new ResultCallback<Connections.StartAdvertisingResult>() {
                    @Override
                    public void onResult(Connections.StartAdvertisingResult result) {
                        Log.v(LOG_TAG, "startAdvertising:onResult:" + result);
                        if (result.getStatus().isSuccess()) {
                            Log.v(LOG_TAG, "Advertising...");
                        }
                    }
                });
    }

    private void startDiscovery(final Spinner spinner, final NumberPicker picker, final MediaPlayer mp1, final MediaPlayer mp2, final ToggleButton button1, final ArrayAdapter adapter) {
        Log.v(LOG_TAG, "startDiscovery");

        Nearby.Connections.startDiscovery(mGoogleApiClientSlave, NEARBY_SERVICE_ID, new EndpointDiscoveryCallback() {
                    @Override
                    public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
                        Log.v(LOG_TAG, "onEndpointFound:" + endpointId + ":" + info.getEndpointName());

                        Nearby.Connections
                                .requestConnection(mGoogleApiClientSlave, null, endpointId, new ConnectionLifecycleCallback() {
                                    @Override
                                    public void onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo) {
                                        Log.v(LOG_TAG, "onConnectionInitiated. Token: " + connectionInfo.getAuthenticationToken());
                                        // Automatically accept the connection on both sides"
                                        Nearby.Connections.acceptConnection(mGoogleApiClientSlave, endpointId, new PayloadCallback() {
                                            @Override
                                            public void onPayloadReceived(String endpointId, Payload payload) {
                                                if (payload.getType() == Payload.Type.BYTES) {
                                                    messageString = new String(payload.asBytes());
                                                    if(messageString.equals("START")) {
                                                        button1.setChecked(true);
                                                        isOn = true;
                                                        metronome(mp1, mp2, button1);
                                                    } else {
                                                        if(messageString.equals("STOP")) {
                                                            button1.setChecked(false);
                                                            if(isOn) {
                                                                isOn = false;
                                                                try {
                                                                    metronomeThread.join();
                                                                } catch (InterruptedException e) {
                                                                    e.printStackTrace();
                                                                }
                                                                metronomeThread = null;
                                                            }
                                                        } else {
                                                            if(Integer.parseInt(messageString) >= 20 && Integer.parseInt(messageString) <= 260) {
                                                                tempo = Integer.parseInt(messageString);
                                                                picker.setValue(tempo);
                                                            } else {
                                                                if(Integer.parseInt(messageString) <= 12) {
                                                                    accent = messageString;
                                                                    spinner.setSelection(adapter.getPosition(accent));
                                                                }
                                                            }
                                                        }
                                                    }
                                                    Log.v(LOG_TAG, "onPayloadReceived: " + new String(payload.asBytes()));
                                                }
                                            }

                                            @Override
                                            public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
                                                // Provides updates about the progress of both incoming and outgoing payloads
                                            }
                                        });
                                    }

                                    @Override
                                    public void onConnectionResult(String endpointId, ConnectionResolution resolution) {
                                        Log.v(LOG_TAG, "onConnectionResult:" + endpointId + ":" + resolution.getStatus());
                                        if (resolution.getStatus().isSuccess()) {
                                            Toast.makeText(MainActivity.this, "Connected to " + endpointId + "!",
                                                    Toast.LENGTH_LONG).show();
                                            Log.v(LOG_TAG, "Connected successfully");
                                            Nearby.Connections.stopDiscovery(mGoogleApiClientSlave);
                                            mRemoteHostEndpoint = endpointId;
                                            mIsConnected = true;
                                        } else {
                                            if (resolution.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) {
                                                Toast.makeText(MainActivity.this, "The connection was rejected by one or both sides",
                                                        Toast.LENGTH_LONG).show();
                                                Log.v(LOG_TAG,"The connection was rejected by one or both sides");
                                            } else {
                                                Toast.makeText(MainActivity.this, "Connection to " + endpointId + " failed.",
                                                        Toast.LENGTH_LONG).show();
                                                Log.v(LOG_TAG, "Connection to " + endpointId + " failed. Code: " + resolution.getStatus().getStatusCode());
                                            }
                                            mIsConnected = false;
                                        }
                                    }

                                    @Override
                                    public void onDisconnected(String endpointId) {
                                        // We've been disconnected from this endpoint. No more data can be sent or received.
                                        Toast.makeText(MainActivity.this, "Disconnected from " + endpointId + ".",
                                                Toast.LENGTH_LONG).show();
                                        Log.v(LOG_TAG, "onDisconnected: " + endpointId);
                                    }
                                })
                                .setResultCallback(new ResultCallback<Status>() {
                                    @Override
                                    public void onResult(@NonNull Status status) {
                                        if (status.isSuccess()) {
                                            // We successfully requested a connection. Now both sides
                                            // must accept before the connection is established.
                                        } else {
                                            // Nearby Connections failed to request the connection.
                                        }
                                    }
                                });
                    }

                    @Override
                    public void onEndpointLost(String endpointId) {
                        // An endpoint that was previously available for connection is no longer.
                        // It may have stopped advertising, gone out of range, or lost connectivity.
                        Log.v(LOG_TAG, "onEndpointLost:" + endpointId);
                    }
                },
                new DiscoveryOptions(Strategy.P2P_STAR)
        )
                .setResultCallback(new ResultCallback<Status>() {
                    @Override
                    public void onResult(@NonNull Status status) {
                        if (status.isSuccess()) {
                            Log.v(LOG_TAG, "Discovering...");
                        } else {
                            Log.v(LOG_TAG, "Discovering failed: " + status.getStatusMessage() + "(" + status.getStatusCode() + ")");
                        }
                    }
                });
    }

    private static void sendMessage(String message) {
        Log.v(LOG_TAG, "About to send message: " + message);
        Nearby.Connections.sendPayload(mGoogleApiClientMaster, mRemotePeerEndpoints, Payload.fromBytes(message.getBytes(Charset.forName("UTF-8"))));
        Log.v(LOG_TAG, "Message sent");
    }

    private void audioAnalysis(final NumberPicker picker) {
        recordingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

                if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    bufferSize = RECORDER_SAMPLE_RATE * 2;
                }

                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        RECORDER_SAMPLE_RATE, RECORDER_CHANNELS,
                        RECORDER_AUDIO_ENCODING, bufferSize);

                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(LOG_TAG, "Audio Record can't initialize!");
                    return;
                }
                recorder.startRecording();
                Log.v(LOG_TAG, "Start recording");
                isRecording = true;

                short buffer[] = new short[bufferSize / 2];
                short max = 0;

                time = 0L;
                prev_time = 0L;
                x = 0;
                prev_x_1 = 0;
                prev_x_2 = 0;
                prev_x_3 = 0;

                while(isRecording) {
                    recorder.read(buffer, 0, buffer.length);
                    for(int i = 0; i < buffer.length; i++) {
                        if(buffer[i] > max) {
                            max = buffer[i];
                        }
                    }
                    if(max > 20000) {
                        prev_time = time;
                        time = System.currentTimeMillis();
                        x = (1000 / (float) (time - prev_time) * 60);
                        if(x < 260) {
                            prev_x_3 = prev_x_2;
                            prev_x_2 = prev_x_1;
                            prev_x_1 = x;
                            float mean = (x + prev_x_1 + prev_x_2 + prev_x_3) / 4;
                            if(mean >= 20 && mean <= 260 && x != 0 && prev_x_2 != 0 && prev_x_3 != 0) {
                                tempo = (int) mean;
                                picker.setValue(tempo);
                                if(mGoogleApiClientMaster.isConnected() && !mRemotePeerEndpoints.isEmpty()) {
                                    sendMessage(Integer.toString(tempo));
                                }
                            }
                        }
                    }
                    max = 0;
                }

                time = 0L;
                prev_time = 0L;
                x = 0;
                prev_x_1 = 0;
                prev_x_2 = 0;
                prev_x_3 = 0;

            }
        }, "AudioRecorder Thread");
        recordingThread.start();
    }

    private static void stopAudioAnalysis() {
        isRecording = false;
        recorder.stop();
        recorder.release();
        recorder = null;
        try {
            recordingThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        recordingThread = null;
        Log.v(LOG_TAG, "Recording stopped");
    }

    private void metronome(final MediaPlayer mp1, final MediaPlayer mp2, final ToggleButton button1) {
        metronomeThread = new Thread(new Runnable() {
            @Override
            public void run(){
                if(mGoogleApiClientMaster.isConnected()) {
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                while(isOn) {
                    try {
                        if((Integer.parseInt(accent) > 0)) {
                            if(isOn) {
                                mp2.start();
                            }
                            if(isOn) {
                                Thread.sleep(60000 / tempo);
                            }
                            for(int i = 0; i < (Integer.parseInt(accent) - 1); i++) {
                                if(isOn) {
                                    mp1.start();
                                }
                                if(isOn) {
                                    Thread.sleep(60000 / tempo);
                                }
                            }
                        }
                        else {
                            if(isOn) {
                                mp1.start();
                            }
                            if(isOn) {
                                Thread.sleep(60000 / tempo);
                            }
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        isOn = false;
                        button1.setChecked(false);
                    }
                }
            }
        }, "Metronome Thread");
        metronomeThread.start();
    }

    public static void startComm(final Spinner spinner, final NumberPicker picker, final ToggleButton button1, final Button button2, final Switch mySwitch) {
        isSyncOn = true;
        if(mode.equals("MASTER")) {
            mGoogleApiClientMaster.connect();
        }
        if(mode.equals("SLAVE")) {
            if(isOn) {
                isOn = false;
                try {
                    metronomeThread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                metronomeThread = null;
            }
            if(isRecording) {
                stopAudioAnalysis();
                mySwitch.setChecked(false);
            }
            button1.setChecked(false);
            spinner.setEnabled(false);
            picker.setEnabled(false);
            button1.setEnabled(false);
            button2.setEnabled(false);
            mySwitch.setEnabled(false);

            mGoogleApiClientSlave.connect();
        }
    }

    public static void stopComm(final Spinner spinner, final NumberPicker picker, final ToggleButton button1, final Button button2, final Switch mySwitch) {
        isSyncOn = false;
        if(mode.equals("MASTER")) {
            if (mGoogleApiClientMaster != null && mGoogleApiClientMaster.isConnected()) {
                Nearby.Connections.stopAdvertising(mGoogleApiClientMaster);
                Log.v(LOG_TAG, "Stop advertising");

                if (!mRemotePeerEndpoints.isEmpty()) {
                    Nearby.Connections.stopAllEndpoints(mGoogleApiClientMaster);
                    mRemotePeerEndpoints.clear();
                }

                mGoogleApiClientMaster.disconnect();
            }
        }
        if(mode.equals("SLAVE")) {
            if (mGoogleApiClientSlave.isConnected()) {
                if (!mIsConnected || TextUtils.isEmpty(mRemoteHostEndpoint)) {
                    Nearby.Connections.stopDiscovery(mGoogleApiClientSlave);
                    Log.v(LOG_TAG, "Stop discovery");
                } else {
                    Nearby.Connections.disconnectFromEndpoint(mGoogleApiClientSlave, mRemoteHostEndpoint);
                    mRemoteHostEndpoint = null;
                    mIsConnected = false;
                }
                mGoogleApiClientSlave.disconnect();

                spinner.setEnabled(true);
                picker.setEnabled(true);
                button1.setEnabled(true);
                button2.setEnabled(true);
                mySwitch.setEnabled(true);
            }
        }
    }

    @Override
    protected void onDestroy() {
        metronomeThread = null;
        recordingThread = null;
        recorder = null;
        if(mGoogleApiClientMaster.isConnected()) {
            mGoogleApiClientMaster.disconnect();
        }
        if(mGoogleApiClientSlave.isConnected()) {
            mGoogleApiClientSlave.disconnect();
        }
        mGoogleApiClientMaster = null;
        mGoogleApiClientSlave = null;
        super.onDestroy();
    }
}
