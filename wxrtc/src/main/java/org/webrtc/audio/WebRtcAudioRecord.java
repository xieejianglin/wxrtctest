package org.webrtc.audio;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTimestamp;
import android.os.Build;
import android.os.Process;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.webrtc.CalledByNative;
import org.webrtc.Logging;
import org.webrtc.ThreadUtils;

class WebRtcAudioRecord {
  private static final String TAG = "WebRtcAudioRecordExternal";
  
  private static final int CALLBACK_BUFFER_SIZE_MS = 10;
  
  private static final int BUFFERS_PER_SECOND = 100;
  
  private static final int BUFFER_SIZE_FACTOR = 2;
  
  private static final long AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS = 2000L;
  
  public static final int DEFAULT_AUDIO_SOURCE = 7;
  
  public static final int DEFAULT_AUDIO_FORMAT = 2;
  
  private static final int AUDIO_RECORD_START = 0;
  
  private static final int AUDIO_RECORD_STOP = 1;
  
  private static final int CHECK_REC_STATUS_DELAY_MS = 100;
  
  private final Context context;
  
  private final AudioManager audioManager;
  
  private final int audioSource;
  
  private final int audioFormat;
  
  private long nativeAudioRecord;
  
  private final WebRtcAudioEffects effects = new WebRtcAudioEffects();
  
  @Nullable
  private ByteBuffer byteBuffer;
  
  @Nullable
  private AudioRecord audioRecord;
  
  @Nullable
  private AudioRecordThread audioThread;
  
  @Nullable
  private AudioDeviceInfo preferredDevice;
  
  private final ScheduledExecutorService executor;
  
  @Nullable
  private ScheduledFuture<String> future;
  
  private volatile boolean microphoneMute;
  
  private final AtomicReference<Boolean> audioSourceMatchesRecordingSessionRef = new AtomicReference<>();
  
  private byte[] emptyBytes;
  
  @Nullable
  private final JavaAudioDeviceModule.AudioRecordErrorCallback errorCallback;
  
  @Nullable
  private final JavaAudioDeviceModule.AudioRecordStateCallback stateCallback;
  
  @Nullable
  private final JavaAudioDeviceModule.SamplesReadyCallback audioSamplesReadyCallback;
  
  private final boolean isAcousticEchoCancelerSupported;
  
  private final boolean isNoiseSuppressorSupported;

  public boolean paused = false;
  
  private class AudioRecordThread extends Thread {
    private volatile boolean keepAlive = true;
    
    public AudioRecordThread(String name) {
      super(name);
    }
    
    public void run() {
      Process.setThreadPriority(-19);
      Logging.d("WebRtcAudioRecordExternal", "AudioRecordThread" + WebRtcAudioUtils.getThreadInfo());
      WebRtcAudioRecord.assertTrue((WebRtcAudioRecord.this.audioRecord.getRecordingState() == 3));
      WebRtcAudioRecord.this.doAudioRecordStateCallback(0);
      long lastTime = System.nanoTime();
      AudioTimestamp audioTimestamp = null;
      if (Build.VERSION.SDK_INT >= 24)
        audioTimestamp = new AudioTimestamp(); 
      while (this.keepAlive) {
        if (paused) {
          if (WebRtcAudioRecord.this.audioRecord != null &&
                  WebRtcAudioRecord.this.audioRecord.getState() == AudioRecord.STATE_INITIALIZED &&
                  WebRtcAudioRecord.this.audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            WebRtcAudioRecord.this.audioRecord.stop();
          }
          continue;
        }else{
          if (WebRtcAudioRecord.this.audioRecord != null &&
                  WebRtcAudioRecord.this.audioRecord.getState() == AudioRecord.STATE_INITIALIZED &&
                  WebRtcAudioRecord.this.audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_STOPPED) {
            WebRtcAudioRecord.this.audioRecord.startRecording();
          }
        }
        int bytesRead = WebRtcAudioRecord.this.audioRecord.read(WebRtcAudioRecord.this.byteBuffer, WebRtcAudioRecord.this.byteBuffer.capacity());
        if (bytesRead == WebRtcAudioRecord.this.byteBuffer.capacity()) {
          if (WebRtcAudioRecord.this.microphoneMute) {
            WebRtcAudioRecord.this.byteBuffer.clear();
            WebRtcAudioRecord.this.byteBuffer.put(WebRtcAudioRecord.this.emptyBytes);
          } 
          if (this.keepAlive) {
            long captureTimeNs = 0L;
            if (Build.VERSION.SDK_INT >= 24 && 
              WebRtcAudioRecord.this.audioRecord.getTimestamp(audioTimestamp, 0) == 0)
              captureTimeNs = audioTimestamp.nanoTime; 
            WebRtcAudioRecord.this.nativeDataIsRecorded(WebRtcAudioRecord.this.nativeAudioRecord, bytesRead, captureTimeNs);
          } 
          if (WebRtcAudioRecord.this.audioSamplesReadyCallback != null) {
            byte[] data = Arrays.copyOfRange(WebRtcAudioRecord.this.byteBuffer.array(), WebRtcAudioRecord.this.byteBuffer.arrayOffset(), WebRtcAudioRecord.this.byteBuffer
                .capacity() + WebRtcAudioRecord.this.byteBuffer.arrayOffset());
            WebRtcAudioRecord.this.audioSamplesReadyCallback.onWebRtcAudioRecordSamplesReady(new JavaAudioDeviceModule.AudioSamples(WebRtcAudioRecord.this.audioRecord
                  .getAudioFormat(), WebRtcAudioRecord.this.audioRecord
                  .getChannelCount(), WebRtcAudioRecord.this.audioRecord.getSampleRate(), data));
          } 
          continue;
        } 
        String errorMessage = "AudioRecord.read failed: " + bytesRead;
        Logging.e("WebRtcAudioRecordExternal", errorMessage);
        if (bytesRead == -3) {
          this.keepAlive = false;
          WebRtcAudioRecord.this.reportWebRtcAudioRecordError(errorMessage);
        } 
      } 
      try {
        if (WebRtcAudioRecord.this.audioRecord != null) {
          WebRtcAudioRecord.this.audioRecord.stop();
          WebRtcAudioRecord.this.doAudioRecordStateCallback(1);
        } 
      } catch (IllegalStateException e) {
        Logging.e("WebRtcAudioRecordExternal", "AudioRecord.stop failed: " + e.getMessage());
      } 
    }
    
    public void stopThread() {
      Logging.d("WebRtcAudioRecordExternal", "stopThread");
      this.keepAlive = false;
    }
  }
  
  @CalledByNative
  WebRtcAudioRecord(Context context, AudioManager audioManager) {
    this(context, newDefaultScheduler(), audioManager, 7, 2, null, null, null, 
        
        WebRtcAudioEffects.isAcousticEchoCancelerSupported(), 
        WebRtcAudioEffects.isNoiseSuppressorSupported());
  }
  
  public WebRtcAudioRecord(Context context, ScheduledExecutorService scheduler, AudioManager audioManager, int audioSource, int audioFormat, @Nullable JavaAudioDeviceModule.AudioRecordErrorCallback errorCallback, @Nullable JavaAudioDeviceModule.AudioRecordStateCallback stateCallback, @Nullable JavaAudioDeviceModule.SamplesReadyCallback audioSamplesReadyCallback, boolean isAcousticEchoCancelerSupported, boolean isNoiseSuppressorSupported) {
    if (isAcousticEchoCancelerSupported && !WebRtcAudioEffects.isAcousticEchoCancelerSupported())
      throw new IllegalArgumentException("HW AEC not supported"); 
    if (isNoiseSuppressorSupported && !WebRtcAudioEffects.isNoiseSuppressorSupported())
      throw new IllegalArgumentException("HW NS not supported"); 
    this.context = context;
    this.executor = scheduler;
    this.audioManager = audioManager;
    this.audioSource = audioSource;
    this.audioFormat = audioFormat;
    this.errorCallback = errorCallback;
    this.stateCallback = stateCallback;
    this.audioSamplesReadyCallback = audioSamplesReadyCallback;
    this.isAcousticEchoCancelerSupported = isAcousticEchoCancelerSupported;
    this.isNoiseSuppressorSupported = isNoiseSuppressorSupported;
    Logging.d("WebRtcAudioRecordExternal", "ctor" + WebRtcAudioUtils.getThreadInfo());
  }
  
  @CalledByNative
  public void setNativeAudioRecord(long nativeAudioRecord) {
    this.nativeAudioRecord = nativeAudioRecord;
  }
  
  @CalledByNative
  boolean isAcousticEchoCancelerSupported() {
    return this.isAcousticEchoCancelerSupported;
  }
  
  @CalledByNative
  boolean isNoiseSuppressorSupported() {
    return this.isNoiseSuppressorSupported;
  }
  
  @CalledByNative
  boolean isAudioConfigVerified() {
    return (this.audioSourceMatchesRecordingSessionRef.get() != null);
  }
  
  @CalledByNative
  boolean isAudioSourceMatchingRecordingSession() {
    Boolean audioSourceMatchesRecordingSession = this.audioSourceMatchesRecordingSessionRef.get();
    if (audioSourceMatchesRecordingSession == null) {
      Logging.w("WebRtcAudioRecordExternal", "Audio configuration has not yet been verified");
      return false;
    } 
    return audioSourceMatchesRecordingSession.booleanValue();
  }
  
  @CalledByNative
  private boolean enableBuiltInAEC(boolean enable) {
    Logging.d("WebRtcAudioRecordExternal", "enableBuiltInAEC(" + enable + ")");
    return this.effects.setAEC(enable);
  }
  
  @CalledByNative
  private boolean enableBuiltInNS(boolean enable) {
    Logging.d("WebRtcAudioRecordExternal", "enableBuiltInNS(" + enable + ")");
    return this.effects.setNS(enable);
  }
  
  @CalledByNative
  private int initRecording(int sampleRate, int channels) {
    Logging.d("WebRtcAudioRecordExternal", "initRecording(sampleRate=" + sampleRate + ", channels=" + channels + ")");
    if (this.audioRecord != null) {
      reportWebRtcAudioRecordInitError("InitRecording called twice without StopRecording.");
      return -1;
    } 
    int bytesPerFrame = channels * getBytesPerSample(this.audioFormat);
    int framesPerBuffer = sampleRate / 100;
    this.byteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer);
    if (!this.byteBuffer.hasArray()) {
      reportWebRtcAudioRecordInitError("ByteBuffer does not have backing array.");
      return -1;
    } 
    Logging.d("WebRtcAudioRecordExternal", "byteBuffer.capacity: " + this.byteBuffer.capacity());
    this.emptyBytes = new byte[this.byteBuffer.capacity()];
    nativeCacheDirectBufferAddress(this.nativeAudioRecord, this.byteBuffer);
    int channelConfig = channelCountToConfiguration(channels);
    int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, this.audioFormat);
    if (minBufferSize == -1 || minBufferSize == -2) {
      reportWebRtcAudioRecordInitError("AudioRecord.getMinBufferSize failed: " + minBufferSize);
      return -1;
    } 
    Logging.d("WebRtcAudioRecordExternal", "AudioRecord.getMinBufferSize: " + minBufferSize);
    int bufferSizeInBytes = Math.max(2 * minBufferSize, this.byteBuffer.capacity());
    Logging.d("WebRtcAudioRecordExternal", "bufferSizeInBytes: " + bufferSizeInBytes);
    try {
      if (Build.VERSION.SDK_INT >= 23) {
        this.audioRecord = createAudioRecordOnMOrHigher(this.audioSource, sampleRate, channelConfig, this.audioFormat, bufferSizeInBytes);
        this.audioSourceMatchesRecordingSessionRef.set(null);
        if (this.preferredDevice != null)
          setPreferredDevice(this.preferredDevice); 
      } else {
        this.audioRecord = createAudioRecordOnLowerThanM(this.audioSource, sampleRate, channelConfig, this.audioFormat, bufferSizeInBytes);
        this.audioSourceMatchesRecordingSessionRef.set(null);
      } 
    } catch (IllegalArgumentException|UnsupportedOperationException e) {
      reportWebRtcAudioRecordInitError(e.getMessage());
      releaseAudioResources();
      return -1;
    } 
    if (this.audioRecord == null || this.audioRecord.getState() != 1) {
      reportWebRtcAudioRecordInitError("Creation or initialization of audio recorder failed.");
      releaseAudioResources();
      return -1;
    } 
    this.effects.enable(this.audioRecord.getAudioSessionId());
    logMainParameters();
    logMainParametersExtended();
    int numActiveRecordingSessions = logRecordingConfigurations(this.audioRecord, false);
    if (numActiveRecordingSessions != 0)
      Logging.w("WebRtcAudioRecordExternal", "Potential microphone conflict. Active sessions: " + numActiveRecordingSessions); 
    return framesPerBuffer;
  }
  
  @RequiresApi(23)
  @TargetApi(23)
  void setPreferredDevice(@Nullable AudioDeviceInfo preferredDevice) {
    Integer var10001 = preferredDevice != null ? preferredDevice.getId() : null;
    Logging.d("WebRtcAudioRecordExternal", "setPreferredDevice " + var10001);
    this.preferredDevice = preferredDevice;
    if (this.audioRecord != null && 
      !this.audioRecord.setPreferredDevice(preferredDevice))
      Logging.e("WebRtcAudioRecordExternal", "setPreferredDevice failed"); 
  }
  
  @CalledByNative
  private boolean startRecording() {
    Logging.d("WebRtcAudioRecordExternal", "startRecording");
    assertTrue((this.audioRecord != null));
    assertTrue((this.audioThread == null));
    try {
      this.audioRecord.startRecording();
    } catch (IllegalStateException e) {
      reportWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode.AUDIO_RECORD_START_EXCEPTION, "AudioRecord.startRecording failed: " + e
          .getMessage());
      return false;
    } 
    if (this.audioRecord.getRecordingState() != 3) {
      reportWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode.AUDIO_RECORD_START_STATE_MISMATCH, "AudioRecord.startRecording failed - incorrect state: " + this.audioRecord
          
          .getRecordingState());
      return false;
    } 
    this.audioThread = new AudioRecordThread("AudioRecordJavaThread");
    this.audioThread.start();
    scheduleLogRecordingConfigurationsTask(this.audioRecord);
    return true;
  }
  
  @CalledByNative
  private boolean stopRecording() {
    Logging.d("WebRtcAudioRecordExternal", "stopRecording");
    assertTrue((this.audioThread != null));
    if (this.future != null) {
      if (!this.future.isDone())
        this.future.cancel(true); 
      this.future = null;
    } 
    this.audioThread.stopThread();
    if (!ThreadUtils.joinUninterruptibly(this.audioThread, 2000L)) {
      Logging.e("WebRtcAudioRecordExternal", "Join of AudioRecordJavaThread timed out");
      WebRtcAudioUtils.logAudioState("WebRtcAudioRecordExternal", this.context, this.audioManager);
    } 
    this.audioThread = null;
    this.effects.release();
    releaseAudioResources();
    return true;
  }

  public void pause() {
    this.paused = true;
    if (this.audioRecord != null && this.audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
      this.audioRecord.stop();
    }

  }

  public void resume() {
    if (this.audioRecord != null && this.audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
      this.audioRecord.startRecording();
    }
    this.paused = false;
  }
  
  @TargetApi(23)
  private static AudioRecord createAudioRecordOnMOrHigher(int audioSource, int sampleRate, int channelConfig, int audioFormat, int bufferSizeInBytes) {
    Logging.d("WebRtcAudioRecordExternal", "createAudioRecordOnMOrHigher");
    return (new AudioRecord.Builder())
      .setAudioSource(audioSource)
      .setAudioFormat((new AudioFormat.Builder())
        .setEncoding(audioFormat)
        .setSampleRate(sampleRate)
        .setChannelMask(channelConfig)
        .build())
      .setBufferSizeInBytes(bufferSizeInBytes)
      .build();
  }
  
  private static AudioRecord createAudioRecordOnLowerThanM(int audioSource, int sampleRate, int channelConfig, int audioFormat, int bufferSizeInBytes) {
    Logging.d("WebRtcAudioRecordExternal", "createAudioRecordOnLowerThanM");
    return new AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSizeInBytes);
  }
  
  private void logMainParameters() {
    Logging.d("WebRtcAudioRecordExternal", "AudioRecord: session ID: " + this.audioRecord
        
        .getAudioSessionId() + ", channels: " + this.audioRecord
        .getChannelCount() + ", sample rate: " + this.audioRecord
        .getSampleRate());
  }
  
  @TargetApi(23)
  private void logMainParametersExtended() {
    if (Build.VERSION.SDK_INT >= 23)
      Logging.d("WebRtcAudioRecordExternal", "AudioRecord: buffer size in frames: " + this.audioRecord
          
          .getBufferSizeInFrames()); 
  }
  
  @TargetApi(24)
  private int logRecordingConfigurations(AudioRecord audioRecord, boolean verifyAudioConfig) {
    if (Build.VERSION.SDK_INT < 24) {
      Logging.w("WebRtcAudioRecordExternal", "AudioManager#getActiveRecordingConfigurations() requires N or higher");
      return 0;
    } 
    if (audioRecord == null)
      return 0; 
    List<AudioRecordingConfiguration> configs = this.audioManager.getActiveRecordingConfigurations();
    int numActiveRecordingSessions = configs.size();
    Logging.d("WebRtcAudioRecordExternal", "Number of active recording sessions: " + numActiveRecordingSessions);
    if (numActiveRecordingSessions > 0) {
      logActiveRecordingConfigs(audioRecord.getAudioSessionId(), configs);
      if (verifyAudioConfig)
        this.audioSourceMatchesRecordingSessionRef.set(
            Boolean.valueOf(verifyAudioConfig(audioRecord.getAudioSource(), audioRecord.getAudioSessionId(), audioRecord
                .getFormat(), audioRecord.getRoutedDevice(), configs))); 
    } 
    return numActiveRecordingSessions;
  }
  
  private static void assertTrue(boolean condition) {
    if (!condition)
      throw new AssertionError("Expected condition to be true"); 
  }
  
  private int channelCountToConfiguration(int channels) {
    return (channels == 1) ? 16 : 12;
  }
  
  public void setMicrophoneMute(boolean mute) {
    Logging.w("WebRtcAudioRecordExternal", "setMicrophoneMute(" + mute + ")");
    this.microphoneMute = mute;
  }
  
  private void releaseAudioResources() {
    Logging.d("WebRtcAudioRecordExternal", "releaseAudioResources");
    if (this.audioRecord != null) {
      this.audioRecord.release();
      this.audioRecord = null;
    } 
    this.audioSourceMatchesRecordingSessionRef.set(null);
  }
  
  private void reportWebRtcAudioRecordInitError(String errorMessage) {
    Logging.e("WebRtcAudioRecordExternal", "Init recording error: " + errorMessage);
    WebRtcAudioUtils.logAudioState("WebRtcAudioRecordExternal", this.context, this.audioManager);
    logRecordingConfigurations(this.audioRecord, false);
    if (this.errorCallback != null)
      this.errorCallback.onWebRtcAudioRecordInitError(errorMessage); 
  }
  
  private void reportWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode errorCode, String errorMessage) {
    Logging.e("WebRtcAudioRecordExternal", "Start recording error: " + errorCode + ". " + errorMessage);
    WebRtcAudioUtils.logAudioState("WebRtcAudioRecordExternal", this.context, this.audioManager);
    logRecordingConfigurations(this.audioRecord, false);
    if (this.errorCallback != null)
      this.errorCallback.onWebRtcAudioRecordStartError(errorCode, errorMessage); 
  }
  
  private void reportWebRtcAudioRecordError(String errorMessage) {
    Logging.e("WebRtcAudioRecordExternal", "Run-time recording error: " + errorMessage);
    WebRtcAudioUtils.logAudioState("WebRtcAudioRecordExternal", this.context, this.audioManager);
    if (this.errorCallback != null)
      this.errorCallback.onWebRtcAudioRecordError(errorMessage); 
  }
  
  private void doAudioRecordStateCallback(int audioState) {
    Logging.d("WebRtcAudioRecordExternal", "doAudioRecordStateCallback: " + audioStateToString(audioState));
    if (this.stateCallback != null)
      if (audioState == 0) {
        this.stateCallback.onWebRtcAudioRecordStart();
      } else if (audioState == 1) {
        this.stateCallback.onWebRtcAudioRecordStop();
      } else {
        Logging.e("WebRtcAudioRecordExternal", "Invalid audio state");
      }  
  }
  
  private static int getBytesPerSample(int audioFormat) {
    switch (audioFormat) {
      case 3:
        return 1;
      case 1:
      case 2:
      case 13:
        return 2;
      case 4:
        return 4;
    } 
    throw new IllegalArgumentException("Bad audio format " + audioFormat);
  }
  
  private void scheduleLogRecordingConfigurationsTask(AudioRecord audioRecord) {
    Logging.d("WebRtcAudioRecordExternal", "scheduleLogRecordingConfigurationsTask");
    if (Build.VERSION.SDK_INT < 24)
      return; 
    Callable<String> callable = () -> {
        if (this.audioRecord == audioRecord) {
          logRecordingConfigurations(audioRecord, true);
        } else {
          Logging.d("WebRtcAudioRecordExternal", "audio record has changed");
        } 
        return "Scheduled task is done";
      };
    if (this.future != null && !this.future.isDone())
      this.future.cancel(true); 
    this.future = this.executor.schedule(callable, 100L, TimeUnit.MILLISECONDS);
  }
  
  @TargetApi(24)
  private static boolean logActiveRecordingConfigs(int session, List<AudioRecordingConfiguration> configs) {
    assertTrue(!configs.isEmpty());
    Iterator<AudioRecordingConfiguration> it = configs.iterator();
    Logging.d("WebRtcAudioRecordExternal", "AudioRecordingConfigurations: ");
    while (it.hasNext()) {
      AudioRecordingConfiguration config = it.next();
      StringBuilder conf = new StringBuilder();
      int audioSource = config.getClientAudioSource();
      conf.append("  client audio source=")
        .append(WebRtcAudioUtils.audioSourceToString(audioSource))
        .append(", client session id=")
        .append(config.getClientAudioSessionId())
        
        .append(" (")
        .append(session)
        .append(")")
        .append("\n");
      AudioFormat format = config.getFormat();
      conf.append("  Device AudioFormat: ")
        .append("channel count=")
        .append(format.getChannelCount())
        .append(", channel index mask=")
        .append(format.getChannelIndexMask())
        
        .append(", channel mask=")
        .append(WebRtcAudioUtils.channelMaskToString(format.getChannelMask()))
        .append(", encoding=")
        .append(WebRtcAudioUtils.audioEncodingToString(format.getEncoding()))
        .append(", sample rate=")
        .append(format.getSampleRate())
        .append("\n");
      format = config.getClientFormat();
      conf.append("  Client AudioFormat: ")
        .append("channel count=")
        .append(format.getChannelCount())
        .append(", channel index mask=")
        .append(format.getChannelIndexMask())
        
        .append(", channel mask=")
        .append(WebRtcAudioUtils.channelMaskToString(format.getChannelMask()))
        .append(", encoding=")
        .append(WebRtcAudioUtils.audioEncodingToString(format.getEncoding()))
        .append(", sample rate=")
        .append(format.getSampleRate())
        .append("\n");
      AudioDeviceInfo device = config.getAudioDevice();
      if (device != null) {
        assertTrue(device.isSource());
        conf.append("  AudioDevice: ")
          .append("type=")
          .append(WebRtcAudioUtils.deviceTypeToString(device.getType()))
          .append(", id=")
          .append(device.getId());
      } 
      Logging.d("WebRtcAudioRecordExternal", conf.toString());
    } 
    return true;
  }
  
  @TargetApi(24)
  private static boolean verifyAudioConfig(int source, int session, AudioFormat format, AudioDeviceInfo device, List<AudioRecordingConfiguration> configs) {
    assertTrue(!configs.isEmpty());
    Iterator<AudioRecordingConfiguration> it = configs.iterator();
    while (it.hasNext()) {
      AudioRecordingConfiguration config = it.next();
      AudioDeviceInfo configDevice = config.getAudioDevice();
      if (configDevice == null)
        continue; 
      if (config.getClientAudioSource() == source && config
        .getClientAudioSessionId() == session && config
        
        .getClientFormat().getEncoding() == format.getEncoding() && config
        .getClientFormat().getSampleRate() == format.getSampleRate() && config
        .getClientFormat().getChannelMask() == format.getChannelMask() && config
        .getClientFormat().getChannelIndexMask() == format.getChannelIndexMask() && config
        
        .getFormat().getEncoding() != 0 && config
        .getFormat().getSampleRate() > 0 && (config
        
        .getFormat().getChannelMask() != 0 || config
        .getFormat().getChannelIndexMask() != 0) && 
        checkDeviceMatch(configDevice, device)) {
        Logging.d("WebRtcAudioRecordExternal", "verifyAudioConfig: PASS");
        return true;
      } 
    } 
    Logging.e("WebRtcAudioRecordExternal", "verifyAudioConfig: FAILED");
    return false;
  }
  
  @TargetApi(24)
  private static boolean checkDeviceMatch(AudioDeviceInfo devA, AudioDeviceInfo devB) {
    return (devA.getId() == devB.getId() && devA.getType() == devB.getType());
  }
  
  private static String audioStateToString(int state) {
    switch (state) {
      case 0:
        return "START";
      case 1:
        return "STOP";
    } 
    return "INVALID";
  }
  
  private static final AtomicInteger nextSchedulerId = new AtomicInteger(0);
  
  static ScheduledExecutorService newDefaultScheduler() {
    final AtomicInteger nextThreadId = new AtomicInteger(0);
    return Executors.newScheduledThreadPool(0, new ThreadFactory() {
          public Thread newThread(Runnable r) {
            Thread thread = Executors.defaultThreadFactory().newThread(r);
            thread.setName(String.format("WebRtcAudioRecordScheduler-%s-%s", WebRtcAudioRecord.nextSchedulerId.getAndIncrement(), nextThreadId.getAndIncrement()));
            return thread;
          }
        });
  }
  
  private native void nativeCacheDirectBufferAddress(long paramLong, ByteBuffer paramByteBuffer);
  
  private native void nativeDataIsRecorded(long paramLong1, int paramInt, long paramLong2);
}
