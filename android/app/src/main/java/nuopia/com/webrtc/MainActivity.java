package nuopia.com.webrtc;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, SignallingClient.SignalingInterface {
    PeerConnectionFactory peerConnectionFactory;
    MediaConstraints audioConstraints;
    MediaConstraints videoConstraints;
    MediaConstraints sdpConstraints;
    VideoSource videoSource;
    VideoTrack localVideoTrack;
    AudioSource audioSource;
    AudioTrack localAudioTrack;

    SurfaceViewRenderer localVideoView;
    SurfaceViewRenderer remoteVideoView;
    ProxyVideoSink localProxyVideoSink = new ProxyVideoSink();
    ProxyVideoSink remoteProxyVideoSink = new ProxyVideoSink();

    Button hangup;
    PeerConnection localPeer;
    EglBase rootEglBase;

    boolean gotUserMedia;
    List<PeerConnection.IceServer> peerIceServers = new ArrayList<>();

    private static class ProxyVideoSink implements VideoSink {
        private VideoSink target;

        @Override
        synchronized public void onFrame(VideoFrame frame) {
            if (target == null) {
                Logging.d(TAG, "Dropping frame in proxy because target is null.");
                return;
            }
            target.onFrame(frame);
        }

        synchronized void setTarget(VideoSink target) {
            this.target = target;
        }
    }

    private static final String TAG = "noupia.MainActivity";

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        AudioManager audioManager = (AudioManager)this.getSystemService(Context.AUDIO_SERVICE);
        String sampleRate = audioManager.getProperty("android.media.property.OUTPUT_SAMPLE_RATE");
        Log.d(TAG, "device sample rate: " + sampleRate);

        initViews();
        initVideos();
        getIceServers();
        SignallingClient.getInstance().init(this);
        start();
    }


    private void initViews() {
        hangup = findViewById(R.id.end_call);
        localVideoView = findViewById(R.id.local_gl_surface_view);
        remoteVideoView = findViewById(R.id.remote_gl_surface_view);
        hangup.setOnClickListener(this);
    }

    private void initVideos() {
        rootEglBase = EglBase.create();
        localVideoView.init(rootEglBase.getEglBaseContext(), null);
        remoteVideoView.init(rootEglBase.getEglBaseContext(), null);
        localVideoView.setZOrderMediaOverlay(true);
        remoteVideoView.setZOrderMediaOverlay(true);
    }

    private void getIceServers() {
        PeerConnection.IceServer peerIceServer = PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer();
        peerIceServers.add(peerIceServer);
    }


    public void start() {
        sdpConstraints = new MediaConstraints();
        sdpConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                "OfferToReceiveVideo", "true"));

        //Initialize PeerConnectionFactory globals.
        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .setEnableVideoHwAcceleration(true)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);

        //Create a new PeerConnectionFactory instance - using Hardware encoder and decoder.
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory defaultVideoEncoderFactory = new DefaultVideoEncoderFactory(
                rootEglBase.getEglBaseContext(),  /* enableIntelVp8Encoder */true,  /* enableH264HighProfile */true);
        DefaultVideoDecoderFactory defaultVideoDecoderFactory = new DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext());

        AudioDeviceModule adm = JavaAudioDeviceModule.builder(this)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule();

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(defaultVideoEncoderFactory)
                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory();


        //Now create a VideoCapturer instance.
        VideoCapturer videoCapturerAndroid;
        videoCapturerAndroid = createCameraCapturer(new Camera1Enumerator(false));


        //Create MediaConstraints - Will be useful for specifying video and audio constraints.
        audioConstraints = new MediaConstraints();
        videoConstraints = new MediaConstraints();

        //Create a VideoSource instance
        if (videoCapturerAndroid != null) {
            videoSource = peerConnectionFactory.createVideoSource(videoCapturerAndroid);
        }
        localVideoTrack = peerConnectionFactory.createVideoTrack("100", videoSource);

        //create an AudioSource instance
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
        localAudioTrack = peerConnectionFactory.createAudioTrack("101", audioSource);


        if (videoCapturerAndroid != null) {
            videoCapturerAndroid.startCapture(1024, 720, 30);
        }
        localVideoView.setVisibility(View.VISIBLE);
        //create a videoRenderer based on SurfaceViewRenderer instance
//        localRenderer = new VideoRenderer(localVideoView);
        localProxyVideoSink.setTarget(localVideoView);
        // And finally, with our VideoRenderer ready, we
        // can add our renderer to the VideoTrack.
//        localVideoTrack.addRenderer(localRenderer);
        localVideoTrack.addSink(localProxyVideoSink);

        localVideoView.setMirror(true);
        remoteVideoView.setMirror(true);

        gotUserMedia = true;
        if (SignallingClient.getInstance().isInitiator) {
            onTryToStart();
        }
    }


    /**
     * This method will be called directly by the app when it is the initiator and has got the local media
     * or when the remote peer sends a message through socket that it is ready to transmit AV data
     */
    @Override
    public void onTryToStart() {
        runOnUiThread(() -> {
            Log.d(TAG, "onTryToStart: ");
            if (!SignallingClient.getInstance().isStarted && localVideoTrack != null && SignallingClient.getInstance().isChannelReady) {
                createPeerConnection();
                SignallingClient.getInstance().isStarted = true;
                if (SignallingClient.getInstance().isInitiator) {
                    doCall();
                }
            }
        });
    }


    /**
     * Creating the local peerconnection instance
     */
    private void createPeerConnection() {
        PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(peerIceServers);
        // TCP candidates are only useful when connecting to a server that supports
        // ICE-TCP.
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        // Use ECDSA encryption.
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
        localPeer = peerConnectionFactory.createPeerConnection(rtcConfig, new CustomPeerConnectionObserver("localPeerCreation") {
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                super.onIceCandidate(iceCandidate);
                Log.d(TAG, "onIceCandidate: " + iceCandidate);
                onIceCandidateReceived(iceCandidate);
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                showToast("Received Remote stream");
                super.onAddStream(mediaStream);
                gotRemoteStream(mediaStream);
            }
        });

        addStreamToLocalPeer();
    }

    /**
     * Adding the stream to the localpeer
     */
    private void addStreamToLocalPeer() {
        //creating local mediastream
        MediaStream stream = peerConnectionFactory.createLocalMediaStream("102");
        stream.addTrack(localAudioTrack);
        stream.addTrack(localVideoTrack);
        localPeer.addStream(stream);
    }

    /**
     * This method is called when the app is initiator - We generate the offer and send it over through socket
     * to remote peer
     */
    private void doCall() {
        localPeer.createOffer(new CustomSdpObserver("localCreateOffer") {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                localPeer.setLocalDescription(new CustomSdpObserver("localSetLocalDesc"), sessionDescription);
                Log.d("onCreateSuccess", "SignallingClient emit ");
                SignallingClient.getInstance().emitMessage(sessionDescription);
            }
        }, sdpConstraints);
    }

    /**
     * Received remote peer's media stream. we will get the first video track and render it
     */
    private void gotRemoteStream(MediaStream stream) {
        //we have remote video stream. add to the renderer.
        final VideoTrack videoTrack = stream.videoTracks.get(0);
        runOnUiThread(() -> {
            try {
//                remoteRenderer = new VideoRenderer(remoteVideoView);
                remoteProxyVideoSink.setTarget(remoteVideoView);
                remoteVideoView.setVisibility(View.VISIBLE);
//                videoTrack.addRenderer(remoteRenderer);
                videoTrack.addSink(remoteProxyVideoSink);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

    }


    /**
     * Received local ice candidate. Send it to remote peer through signalling for negotiation
     */
    public void onIceCandidateReceived(IceCandidate iceCandidate) {
        //we have received ice candidate. We can set it to the other peer.
        SignallingClient.getInstance().emitIceCandidate(iceCandidate);
    }

    /**
     * SignallingCallback - called when the room is created - i.e. you are the initiator
     */
    @Override
    public void onCreatedRoom() {
        showToast("You created the room " + gotUserMedia);
        if (gotUserMedia) {
            SignallingClient.getInstance().emitMessage("got user media");
        }
    }

    /**
     * SignallingCallback - called when you join the room - you are a participant
     */
    @Override
    public void onJoinedRoom() {
        showToast("You joined the room " + gotUserMedia);
        if (gotUserMedia) {
            SignallingClient.getInstance().emitMessage("got user media");
        }
    }

    @Override
    public void onNewPeerJoined() {
        showToast("Remote Peer Joined");
    }

    @Override
    public void onRemoteHangUp(String msg) {
        showToast("Remote Peer hungup");
        runOnUiThread(this::hangup);
    }

    /**
     * SignallingCallback - Called when remote peer sends offer
     */
    @Override
    public void onOfferReceived(final JSONObject data) {
        showToast("Received Offer");
        runOnUiThread(() -> {
            if (!SignallingClient.getInstance().isInitiator && !SignallingClient.getInstance().isStarted) {
                onTryToStart();
            }

            try {
                localPeer.setRemoteDescription(new CustomSdpObserver("localSetRemote"), new SessionDescription(SessionDescription.Type.OFFER, data.getString("sdp")));
                doAnswer();
                updateVideoViews(true);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });
    }

    private void doAnswer() {
        localPeer.createAnswer(new CustomSdpObserver("localCreateAns") {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                localPeer.setLocalDescription(new CustomSdpObserver("localSetLocal"), sessionDescription);
                SignallingClient.getInstance().emitMessage(sessionDescription);
            }
        }, sdpConstraints);
    }

    /**
     * SignallingCallback - Called when remote peer sends answer to your offer
     */

    @Override
    public void onAnswerReceived(JSONObject data) {
        showToast("Received Answer");
        try {
            localPeer.setRemoteDescription(new CustomSdpObserver("localSetRemote"), new SessionDescription(SessionDescription.Type.fromCanonicalForm(data.getString("type").toLowerCase()), data.getString("sdp")));
            updateVideoViews(true);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * Remote IceCandidate received
     */
    @Override
    public void onIceCandidateReceived(JSONObject data) {
        try {
            localPeer.addIceCandidate(new IceCandidate(data.getString("id"), data.getInt("label"), data.getString("candidate")));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }


    private void updateVideoViews(final boolean remoteVisible) {
        runOnUiThread(() -> {
            ViewGroup.LayoutParams params = localVideoView.getLayoutParams();
            if (remoteVisible) {
                params.height = dpToPx(100);
                params.width = dpToPx(100);
            } else {
                params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            }
            localVideoView.setLayoutParams(params);
        });

    }


    /**
     * Closing up - normal hangup and app destroye
     */

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.end_call: {
                hangup();
                break;
            }
        }
    }

    private void hangup() {
        try {
            localPeer.close();
            localPeer = null;
            SignallingClient.getInstance().close();
            updateVideoViews(false);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @Override
    protected void onDestroy() {
        SignallingClient.getInstance().close();
        super.onDestroy();
    }

    /**
     * Util Methods
     */
    public int dpToPx(int dp) {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        return Math.round(dp * (displayMetrics.xdpi / DisplayMetrics.DENSITY_DEFAULT));
    }

    public void showToast(final String msg) {
        Log.d(TAG, "showToast: " + msg);
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        Logging.d(TAG, "Looking for front facing cameras.");
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating front facing camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Front facing camera not found, try something else
        Logging.d(TAG, "Looking for other cameras.");
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating other camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }
}