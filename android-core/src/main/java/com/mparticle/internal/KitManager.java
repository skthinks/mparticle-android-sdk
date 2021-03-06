package com.mparticle.internal;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;

import com.mparticle.MPEvent;
import com.mparticle.MParticle;
import com.mparticle.commerce.CommerceEvent;

import org.json.JSONArray;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface KitManager {

    WeakReference<Activity> getCurrentActivity();

    void logEvent(MPEvent event);

    void logCommerceEvent(CommerceEvent event);

    void logScreen(MPEvent screenEvent);

    void leaveBreadcrumb(String breadcrumb);

    void logError(String message, Map<String, String> eventData);

    void logNetworkPerformance(String url, long startTime, String method, long length, long bytesSent, long bytesReceived, String requestString, int responseCode);

    void checkForDeepLink();

    void logException(Exception exception, Map<String, String> eventData, String message);

    void setLocation(Location location);

    void logout();

    void setUserAttribute(String key, String value);

    void setUserAttributeList(String key, List<String> value);

    void removeUserAttribute(String key);

    void setUserIdentity(String id, MParticle.IdentityType identityType);

    void removeUserIdentity(MParticle.IdentityType id);

    void setOptOut(boolean optOutStatus);

    Uri getSurveyUrl(int serviceProviderId, Map<String, String> userAttributes, Map<String, List<String>> userAttributeLists);

    boolean onMessageReceived(Context context, Intent intent);

    boolean onPushRegistration(String instanceId, String senderId);

    boolean isKitActive(int kitId);

    Object getKitInstance(int kitId);

    Set<Integer> getSupportedKits();

    void updateKits(JSONArray jsonArray);

    String getActiveModuleIds();

    void onActivityCreated(Activity activity, Bundle savedInstanceState);

    void onActivityStarted(Activity activity);

    void onActivityResumed(Activity activity);

    void onActivityPaused(Activity activity);

    void onActivityStopped(Activity activity);

    void onActivitySaveInstanceState(Activity activity, Bundle outState);

    void onActivityDestroyed(Activity activity);

    void onSessionEnd();

    void onSessionStart();

    void installReferrerUpdated();

    void onApplicationForeground();

    void onApplicationBackground();
}