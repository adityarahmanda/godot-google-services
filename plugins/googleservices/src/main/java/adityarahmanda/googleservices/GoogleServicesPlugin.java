package adityarahmanda.googleservices;
import adityarahmanda.googleservices.Signals;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

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
  private FirebaseAnalytics firebaseAnalytics;
  private RewardedAd mRewardedAd;
  private ConsentInformation consentInformation;
  private GamesSignInClient gamesSignInClient;
  private AchievementsClient achievementsClient;
  private View testContainer;
  private boolean admobLoaded;
  private boolean authenticated;
  private AtomicBoolean admobInitialized;

  public GoogleServicesPlugin(Godot godot) {
    super(godot);
    admobInitialized = new AtomicBoolean(false);
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

    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_INIT_COMPLETE));
    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED_LOADED));
    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED, String.class, Integer.class ));
    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED_FAIL_TO_LOAD, Integer.class, String.class));

    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED_CLICKED));
    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED_DISMISSED_FULLSCREEN_CONTENT));
    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED_FAILED_SHOW_FULLSCREEN_CONTENT));
    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED_IMPRESSION));
    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_REWARDED_SHOWED_FULLSCREEN_CONTENT));

    signals.add(new SignalInfo(Signals.SIGNAL_ADMOB_ERROR_GDPR_CONSENT, Integer.class, String.class));

    return signals;
  }

  @UsedByGodot
  public void initializeAnalytics() {
    final Activity activity = getActivity();
    if(activity!=null && !activity.isFinishing()) {
      FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG);
      FirebaseAnalytics.getInstance(activity).setAnalyticsCollectionEnabled(true);
      firebaseAnalytics = FirebaseAnalytics.getInstance(activity);
    }
  }

  @UsedByGodot
  private void initializeAdmob(final boolean testMode, final String testDeviceId){
    final Activity activity = getActivity();
    if(activity!=null && !activity.isFinishing()) {
      consentInformation = UserMessagingPlatform.getConsentInformation(activity);
      ConsentRequestParameters params;
      if (testMode) {
        consentInformation.reset(); //reset to always ask

        //force geography area to GDPR
        ConsentDebugSettings debugSettings = new ConsentDebugSettings.Builder(activity)
          .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
          .addTestDeviceHashedId(testDeviceId)
          .build();

        params = new ConsentRequestParameters
          .Builder()
          .setConsentDebugSettings(debugSettings)
          .build();
      }
      else {
        // Set tag for under age of consent. false means users are not under age
        // of consent.
        params = new ConsentRequestParameters
          .Builder()
          .setTagForUnderAgeOfConsent(false)
          .build();
      }

      // Consent gathering failed.
      consentInformation.requestConsentInfoUpdate(
        activity,
        params,
        () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(
          activity,
          loadAndShowError -> {
            if (loadAndShowError != null) {
              // Consent gathering failed.
              sendErrorGDPRUserConsent(loadAndShowError);
            }

            // Consent has been gathered.
            if (consentInformation.canRequestAds()) {
              processInitializeAdmob(testMode, testDeviceId);
            }
          }
        ),
        this::sendErrorGDPRUserConsent);

      // Check if you can initialize the Google Mobile Ads SDK in parallel
      // while checking for new consent information. Consent obtained in
      // the previous session can be used to request ads.
      processInitializeAdmob(testMode, testDeviceId);
    }
  }

  private void processInitializeAdmob(boolean testMode, final String testDeviceId) {
    if (consentInformation.canRequestAds() && admobInitialized.compareAndSet(false, true)) {
        if (testMode) {
          List<String> testDeviceIds = Collections.singletonList(testDeviceId);
          RequestConfiguration configuration = new RequestConfiguration.Builder().setTestDeviceIds(testDeviceIds).build();
          MobileAds.setRequestConfiguration(configuration);
          runOnUiThread(()->Toast.makeText(getActivity(), "Test mode ENABLE!", Toast.LENGTH_LONG).show());
        }

        MobileAds.initialize(getActivity(), initializationStatus -> {
          emitSignal(Signals.SIGNAL_ADMOB_INIT_COMPLETE);
          admobLoaded = true;
        });
      }
  }

  @UsedByGodot
  public void initializePlayGames() {
    final Activity activity = getActivity();
    if(activity!=null && !activity.isFinishing()) {
      PlayGamesSdk.initialize(activity);
      gamesSignInClient = PlayGames.getGamesSignInClient(activity);
      achievementsClient = PlayGames.getAchievementsClient(activity);
      gamesSignInClient.isAuthenticated().addOnCompleteListener(isAuthenticatedTask -> {
        authenticated = (isAuthenticatedTask.isSuccessful() && isAuthenticatedTask.getResult().isAuthenticated());
      });
    }
  }

  private void sendErrorGDPRUserConsent(FormError formError) {
    Log.w("CONSENT_GDRP", String.format("%s: %s", formError.getErrorCode(), formError.getMessage()));
    emitSignal(Signals.SIGNAL_ADMOB_ERROR_GDPR_CONSENT, formError.getErrorCode(), formError.getMessage());
  }

  @UsedByGodot
  public void loadRewardedAd(final String rewardedId){
    AdRequest adRequest = new AdRequest.Builder().build();

    getActivity().runOnUiThread(()->
      RewardedAd.load(getActivity(), rewardedId,
        adRequest, new RewardedAdLoadCallback() {
        @Override
        public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
          // Handle the error.
          emitSignal(Signals.SIGNAL_ADMOB_REWARDED_FAIL_TO_LOAD, loadAdError.getCode(), loadAdError.getMessage());
          mRewardedAd = null;
        }

        @Override
        public void onAdLoaded(@NonNull RewardedAd ad) {
          mRewardedAd = ad;
          emitSignal(Signals.SIGNAL_ADMOB_REWARDED_LOADED);
        }
      })
    );
  }

  @UsedByGodot
  public void showLoadedRewardedAd() {
    if(mRewardedAd!=null) {
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
          emitSignal(Signals.SIGNAL_ADMOB_REWARDED, rewardItem.getType(), rewardItem.getAmount());
        }));
      }
    }
    else {
      emitSignal(Signals.SIGNAL_ADMOB_REWARDED_FAILED_SHOW_FULLSCREEN_CONTENT);
    }
  }

  @UsedByGodot
  public boolean isAdmobLoaded() {
    return this.admobLoaded;
  }

  @UsedByGodot
  public boolean isAuthenticated() {
    return this.authenticated;
  }

  @UsedByGodot
  public boolean isConnected() {
    boolean connected = false;
    final Activity activity = getActivity();

    if(activity!=null && !activity.isFinishing()) {
      ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
      NetworkInfo netInfo = cm.getActiveNetworkInfo();

      if (netInfo != null && netInfo.isConnectedOrConnecting()) {
        connected = true;
      }
    }

    return connected;
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
        paramText = String.format("{%s:%s}", key, value.toString());

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

    Log.d("ANALYTICS_EVENTS", String.format("Log Analytics Event: %s:%s", eventName, paramText));
    firebaseAnalytics.logEvent(eventName, bundle);
  }

  @UsedByGodot
  public void unlockAchievement(String achievementId) {
    if (!authenticated) return;
    
    final Activity activity = getActivity();
    if(activity!=null && !activity.isFinishing()) {
      achievementsClient.unlock(achievementId);
    }
  }
}
