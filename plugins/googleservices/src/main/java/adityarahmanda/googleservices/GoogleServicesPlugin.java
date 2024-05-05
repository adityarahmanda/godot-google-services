// code modified from https://github.com/hastatus-games/pilum-godot/tree/master
package adityarahmanda.googleservices;
import adityarahmanda.googleservices.Signals;

import android.app.Activity;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.gms.games.PlayGamesSdk;
import com.google.android.gms.games.PlayGames;
import com.google.android.gms.games.GamesSignInClient;
import com.google.android.gms.games.AchievementsClient;
import com.google.android.gms.games.AuthenticationResult;
import com.google.android.ump.ConsentDebugSettings;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.FormError;
import com.google.android.ump.UserMessagingPlatform;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;
import org.godotengine.godot.plugin.SignalInfo;
import org.godotengine.godot.plugin.UsedByGodot;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class GoogleServicesPlugin extends GodotPlugin {
  private FirebaseAnalytics mFirebaseAnalytics;
  private RewardedAd mRewardedAd;
  private AtomicBoolean mMobileAdsInitialized;
  private ConsentInformation mConsentInformation;
  private GamesSignInClient mGamesSignInClient;
  private AchievementsClient mAchievementsClient;
  private ConnectivityManager.NetworkCallback mNetworkCallback;
  private boolean mIsAuthenticated;
  
  private static final int RC_ACHIEVEMENT_UI = 9003;

  public GoogleServicesPlugin(Godot godot) {
    super(godot);
    mMobileAdsInitialized = new AtomicBoolean(false);
  }

  @Override
  @NonNull
  public String getPluginName() {
    return "GoogleServices";
  }

  @NonNull
  @Override
  public Set<SignalInfo> getPluginSignals() {
    Set<SignalInfo> signals = new HashSet<>();
    signals.add(new SignalInfo(Signals.SIGNAL_MOBILE_ADS_INIT_COMPLETE));
    signals.add(new SignalInfo(Signals.SIGNAL_CONSENT_GDPR_ERROR, Integer.class, String.class));
    signals.add(new SignalInfo(Signals.SIGNAL_CONSENT_FORM_DISMISSED));
    signals.add(new SignalInfo(Signals.SIGNAL_PLAY_GAMES_AUTH_COMPLETE));
    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED_LOADED));
    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED, String.class, Integer.class ));
    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED_FAIL_TO_LOAD, Integer.class, String.class));
    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED_CLICKED));
    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED_DISMISSED_FULLSCREEN_CONTENT));
    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED_FAILED_SHOW_FULLSCREEN_CONTENT));
    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED_IMPRESSION));
    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED_SHOWED_FULLSCREEN_CONTENT));
    signals.add(new SignalInfo(Signals.SIGNAL_CONNECTIVITY_AVAILABLE));
    signals.add(new SignalInfo(Signals.SIGNAL_CONNECTIVITY_LOST));
    return signals;
  }

  @Override
  public void onMainPause(){
    unregisterOnNetworkAvailableHandler();
  }

  @Override
  public void onMainResume(){
    registerOnNetworkAvailableHandler();
  }

  @UsedByGodot
  public void initializeAnalytics() {
    final Activity activity = getActivity();
    if(activity!=null && !activity.isFinishing()) {
      Log.d("ANALYTICS", "Initializing Analytics...");
      FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG);
      FirebaseAnalytics.getInstance(activity).setAnalyticsCollectionEnabled(true);
      mFirebaseAnalytics = FirebaseAnalytics.getInstance(activity);
    }
  }
  
  @UsedByGodot
  public void logEvent(String eventName, org.godotengine.godot.Dictionary params) {
    Bundle bundle = new Bundle();

    String paramText = "";
    if(params!=null) {
      Set<Map.Entry<String, Object>> entrySet = params.entrySet();
      for (Map.Entry<String, Object> entry : entrySet) {
        String key = entry.getKey();
        Object value = entry.getValue();
        paramText = String.format("{%s:%s}", key, String.valueOf(value));

        if(entry.getValue() instanceof String) {
          bundle.putString(key, (String)value);
        }
        else if(entry.getValue() instanceof Integer) {
          bundle.putInt(key, (Integer)value);
        }
        else if(entry.getValue() instanceof Boolean) {
          bundle.putBoolean(key, (Boolean)value);
        }
        else if(entry.getValue() instanceof Float) {
          bundle.putFloat(key, (Float)value);
        }
        else if(entry.getValue() instanceof Double) {
          bundle.putDouble(key, (Double)value);
        }
        else if(entry.getValue() instanceof Long) {
          bundle.putLong(key, (Long)value);
        }
      }
    }

    Log.d("ANALYTICS", String.format("Logging Analytics %s Event with parameter %s...", eventName, paramText));
    mFirebaseAnalytics.logEvent(eventName, bundle);
  }

  @UsedByGodot
  private void initializeMobileAds(final boolean testMode, final String testDeviceId){
    final Activity activity = getActivity();
    if(activity!=null && !activity.isFinishing()) {
      Log.d("MOBILE_ADS", String.format("Initializing Mobile Ads (TestMode:%s,TestDeviceId:%s)...", String.valueOf(testMode), testDeviceId));
      mConsentInformation = UserMessagingPlatform.getConsentInformation(activity);
      ConsentRequestParameters params;
      if (testMode) {
        mConsentInformation.reset(); //reset to always ask

        //force geography area to GDPR
        ConsentDebugSettings debugSettings = new ConsentDebugSettings.Builder(activity)
          .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
          .addTestDeviceHashedId(testDeviceId)
          .build();

        params = new ConsentRequestParameters
          .Builder()
          .setConsentDebugSettings(debugSettings)
          .build();
      } else {
        params = new ConsentRequestParameters
          .Builder()
          .setTagForUnderAgeOfConsent(true) // Set tag for under age of consent. false means users are not under age of consent
          .build();
      }

      runOnUiThread(() -> mConsentInformation.requestConsentInfoUpdate(activity, params,
        () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity,
          loadAndShowError -> {
            if (loadAndShowError != null) {
              // Consent gathering failed.
              sendErrorGDPRUserConsent(loadAndShowError);
            }

            // Consent has been gathered.
            if (mConsentInformation.canRequestAds()) {
              tryInitializeMobileAds(testMode, testDeviceId);
            }
          }
        ),
        this::sendErrorGDPRUserConsent));

      // Check if you can initialize the Google Mobile Ads SDK in parallel
      // while checking for new consent information. Consent obtained in
      // the previous session can be used to request ads.
      tryInitializeMobileAds(testMode, testDeviceId);
    }
  }

  private void sendErrorGDPRUserConsent(FormError formError) {
    Log.w("GDPR_CONSENT", String.format("Error %s - %s", formError.getErrorCode(), formError.getMessage()));
    emitSignal(Signals.SIGNAL_CONSENT_GDPR_ERROR, formError.getErrorCode(), formError.getMessage());
  }

  private void tryInitializeMobileAds(boolean testMode, final String testDeviceId) {
    Log.d("MOBILE_ADS", String.format("Trying to initialize Mobile Ads (ConsentRequestAds:%s)...", String.valueOf(mConsentInformation.canRequestAds())));
    if (mConsentInformation.canRequestAds() && mMobileAdsInitialized.compareAndSet(false, true)) {
      RequestConfiguration.Builder configurationBuilder = new RequestConfiguration.Builder()
        .setTagForUnderAgeOfConsent(RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_TRUE) // Set tag for under age of consent. false means users are not under age of consent
        .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G); // Set Ad Content rating. G means content suitable for general audiences, including families
      if (testMode) {
        List<String> testDeviceIds = Collections.singletonList(testDeviceId);
        configurationBuilder.setTestDeviceIds(testDeviceIds);
      }
      MobileAds.setRequestConfiguration(configurationBuilder.build());
      MobileAds.initialize(getActivity(), initializationStatus -> {
        emitSignal(Signals.SIGNAL_MOBILE_ADS_INIT_COMPLETE);
        Log.d("MOBILE_ADS", "Mobile Ads Initialization Success");
      });
    }
    else
    {
      Log.d("MOBILE_ADS", "Mobile Ads Initialization Failed");
    }
  }

  @UsedByGodot
  public void showPrivacyOptionsForm() {
    final Activity activity = getActivity();
    if(activity!=null && !activity.isFinishing()) {
      Log.d("GDPR_CONSENT", "Showing privacy options form...");
      runOnUiThread(() -> UserMessagingPlatform.showPrivacyOptionsForm(activity, 
        loadAndShowError -> {
          if (loadAndShowError != null) {
            // Consent gathering failed.
            sendErrorGDPRUserConsent(loadAndShowError);
          }

          // Consent has been gathered.
          if (mConsentInformation.canRequestAds()) {
            emitSignal(Signals.SIGNAL_CONSENT_FORM_DISMISSED);
          }
        }));
      }
  }
  
  @UsedByGodot
  public void loadRewardedAd(final String rewardedId){
    Log.d("ADMOB", String.format("Loading Rewarded Ad with id %s...", rewardedId));
    AdRequest adRequest = new AdRequest.Builder().build();
    runOnUiThread(()->
      RewardedAd.load(getActivity(), rewardedId,
        adRequest, new RewardedAdLoadCallback() {
        @Override
        public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
          // Handle the error.
          emitSignal(Signals.SIGNAL_ADMOB_REWARDED_FAIL_TO_LOAD, loadAdError.getCode(), loadAdError.getMessage());
          mRewardedAd = null;
          Log.d("ADMOB", String.format("Rewarded Ad with id %s failed to load", rewardedId));
        }

        @Override
        public void onAdLoaded(@NonNull RewardedAd ad) {
          mRewardedAd = ad;
          emitSignal(Signals.SIGNAL_ADMOB_REWARDED_LOADED);
          Log.d("ADMOB", String.format("Rewarded Ad with id %s is loaded!", rewardedId));
        }
      })
    );
  }

  @UsedByGodot
  public void showLoadedRewardedAd() {
    if(mRewardedAd!=null) {
      Log.d("ADMOB", "Showing loaded rewarded ad...");
      mRewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {
        @Override
        public void onAdClicked() {
          // Called when a click is recorded for an ad.
          emitSignal(Signals.SIGNAL_ADMOB_REWARDED_CLICKED);
        }

        @Override
        public void onAdDismissedFullScreenContent() {
          // Called when ad is dismissed.
          // Set the ad reference to null so you don't show the ad a second time.
          emitSignal(Signals.SIGNAL_ADMOB_REWARDED_DISMISSED_FULLSCREEN_CONTENT);
          mRewardedAd = null;
        }

        @Override
        public void onAdFailedToShowFullScreenContent(AdError adError) {
          // Called when ad fails to show.
          emitSignal(Signals.SIGNAL_ADMOB_REWARDED_FAILED_SHOW_FULLSCREEN_CONTENT);
          mRewardedAd = null;
        }

        @Override
        public void onAdImpression() {
          // Called when an impression is recorded for an ad.
          emitSignal(Signals.SIGNAL_ADMOB_REWARDED_IMPRESSION);
        }

        @Override
        public void onAdShowedFullScreenContent() {
          // Called when ad is shown.
          emitSignal(Signals.SIGNAL_ADMOB_REWARDED_SHOWED_FULLSCREEN_CONTENT);
        }
      });

      final Activity activity = getActivity();
      if(activity!=null && !activity.isFinishing()) {
        getActivity().runOnUiThread(() -> mRewardedAd.show(activity, rewardItem -> {
          // Handle the reward.
          String itemType = rewardItem.getType();
          int rewardAmount = rewardItem.getAmount();
          Log.d("ADMOB", String.format("Earned %s with %s reward amount from Rewarded Ad", itemType, String.valueOf(rewardAmount)));
          emitSignal(Signals.SIGNAL_ADMOB_REWARDED, itemType, rewardAmount);
        }));
      }
    }
    else {
      emitSignal(Signals.SIGNAL_ADMOB_REWARDED_FAILED_SHOW_FULLSCREEN_CONTENT);
    }
  }

  @UsedByGodot
  public boolean isRewardedAdLoaded() {
    return this.mRewardedAd != null;
  }

  @UsedByGodot
  public boolean hasGdprConsentForAds() {
    return this.mConsentInformation.canRequestAds();
  }

  @UsedByGodot
  public void initializePlayGames() {
    final Activity activity = getActivity();
    if(activity!=null && !activity.isFinishing()) {
      Log.d("PLAY_GAMES", "Initializing Play Games...");
      PlayGamesSdk.initialize(activity);
      mGamesSignInClient = PlayGames.getGamesSignInClient(activity);
      mGamesSignInClient.isAuthenticated().addOnCompleteListener(isAuthenticatedTask -> onSignInPlayGames(activity, isAuthenticatedTask));
    }
  }

  @UsedByGodot
  public void signInPlayGames() {
    if(mIsAuthenticated) return;
    
    final Activity activity = getActivity();
    if(activity!=null && !activity.isFinishing()) {
      mGamesSignInClient.signIn().addOnCompleteListener(isAuthenticatedTask -> onSignInPlayGames(activity, isAuthenticatedTask));
    }
  }

  private void onSignInPlayGames(Activity activity, Task<AuthenticationResult> isAuthenticatedTask) {
    mIsAuthenticated = (isAuthenticatedTask.isSuccessful() && isAuthenticatedTask.getResult().isAuthenticated());
    if (mIsAuthenticated) {
      mAchievementsClient = PlayGames.getAchievementsClient(activity);
      Log.d("PLAY_GAMES", "Play Games Authentication Success!");
    } else {
      Log.d("PLAY_GAMES", "Play Games Authentication Failed!");
    }
    emitSignal(Signals.SIGNAL_PLAY_GAMES_AUTH_COMPLETE);
  }

  @UsedByGodot
  public void unlockAchievement(String achievementId) {
    if (!mIsAuthenticated) {
      Log.d("PLAY_GAMES", String.format("Can't unlock achievement with id %s, Play Games is not authenticated", achievementId));
      return;
    }

    final Activity activity = getActivity();
    if(activity!=null && !activity.isFinishing()) {
      Log.d("PLAY_GAMES", String.format("Unlocking achievement with id %s...", achievementId));
      mAchievementsClient.unlock(achievementId);
    }
  }

  @UsedByGodot
  private void showAchievements() {
    if (mAchievementsClient == null) return;
    final Activity activity = getActivity();
    if(activity!=null && !activity.isFinishing()) {
      Log.d("PLAY_GAMES", "Showing Achivements...");
      mAchievementsClient.getAchievementsIntent().addOnSuccessListener(intent -> {
        activity.startActivityForResult(intent, RC_ACHIEVEMENT_UI);
      });
    }
  }

  @UsedByGodot
  public boolean isAuthenticated() {
    return this.mIsAuthenticated;
  }

  private void registerOnNetworkAvailableHandler()
  {
    final Activity activity = getActivity();
    if(activity!=null && !activity.isFinishing()) {
      Log.d("CONNECTIVITY", "Registering Network Callbacks...");
      mNetworkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
          super.onAvailable(network);
          emitSignal(Signals.SIGNAL_CONNECTIVITY_AVAILABLE);
          Log.d("CONNECTIVITY", "Network Connectivity Available");
        }

        @Override
        public void onLost(@NonNull Network network) {
          super.onLost(network);
          emitSignal(Signals.SIGNAL_CONNECTIVITY_LOST);
          Log.d("CONNECTIVITY", "Network Connectivity Lost");
        }
      };

      ConnectivityManager connectivityManager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
      NetworkRequest networkRequest = new NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .build();
      connectivityManager.registerNetworkCallback(networkRequest, mNetworkCallback);
    }
  }

  private void unregisterOnNetworkAvailableHandler()
  {
    final Activity activity = getActivity();
    if(activity!=null && !activity.isFinishing()) {
      Log.d("CONNECTIVITY", "Unregistering up Network Callbacks...");
      ConnectivityManager connectivityManager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
      connectivityManager.unregisterNetworkCallback(mNetworkCallback);
    }
  }

  @UsedByGodot
  public boolean isConnectedToNetwork() {
    boolean connected = false;
    final Activity activity = getActivity();
    if(activity!=null && !activity.isFinishing()) {
      ConnectivityManager connectivityManager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
      NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
      if (networkInfo != null && networkInfo.isConnectedOrConnecting()) {
        connected = true;
      }
    }
    return connected;
  }

  @UsedByGodot
  public void showToast(String message) {
    final Activity activity = getActivity();
    if(activity!=null && !activity.isFinishing()) {
      runOnUiThread(()->Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
    }
  }
}
