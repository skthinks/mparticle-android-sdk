package com.mparticle;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import com.mparticle.Constants.ConfigKeys;
import com.mparticle.Constants.MessageKey;
import com.mparticle.Constants.PrefKeys;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * This class provides access to the mParticle API.
 */
public class MParticle {
    private static volatile MParticle instance;
    private static final String TAG = Constants.LOG_TAG;
    private static final HandlerThread sTimeoutHandlerThread = new HandlerThread("mParticleSessionTimeoutHandler",
            Process.THREAD_PRIORITY_BACKGROUND);
    private static SharedPreferences sPreferences;
    private final ConfigManager mConfigManager;
    private final AppStateManager mAppStateManager;

    private MessageManager mMessageManager;
    private Handler mTimeoutHandler;
    private MParticleLocationListener mLocationListener;
    private ExceptionHandler mExHandler;
    private Context mAppContext;
    private String mApiKey;
    private boolean mOptedOut = false;
    private boolean mDebugMode = false;

    /* package-private */String mSessionID;
    /* package-private */long mSessionStartTime = 0;
    /* package-private */long mLastEventTime = 0;
    //private int mSessionTimeout = 30 * 60 * 1000;
    private int mEventCount = 0;
    /* package-private */JSONArray mUserIdentities = new JSONArray();
    /* package-private */JSONObject mUserAttributes = new JSONObject();
    /* package-private */JSONObject mSessionAttributes;
    private String mLaunchUri;
    private boolean mAutoTrackingEnabled;

    /* package-private */MParticle(Context context, String apiKey, MessageManager messageManager, ConfigManager configManager) {
        mConfigManager = configManager;
        mAppContext = context.getApplicationContext();
        mApiKey = apiKey;
        mMessageManager = messageManager;
        mTimeoutHandler = new SessionTimeoutHandler(this, sTimeoutHandlerThread.getLooper());
        mAppStateManager = new AppStateManager(mAppContext);
        mOptedOut = sPreferences.getBoolean(PrefKeys.OPTOUT + mApiKey, false);
        String userAttrs = sPreferences.getString(PrefKeys.USER_ATTRS + mApiKey, null);
        if (null != userAttrs) {
            try {
                mUserAttributes = new JSONObject(userAttrs);
            } catch (JSONException e) {
                // carry on without user attributes
            }
        }
        
        String userIds = sPreferences.getString(PrefKeys.USER_IDENTITIES + mApiKey, null);
        if (null != userIds) {
            try {
                mUserIdentities = new JSONArray(userIds);
            } catch (JSONException e) {
                // carry on without user identities
            }
        }
        
        if (!sPreferences.contains(PrefKeys.INSTALL_TIME)) {
            sPreferences.edit().putLong(PrefKeys.INSTALL_TIME, System.currentTimeMillis()).commit();
        }
    }

    /**
     * Initialize or return an instance of the mParticle SDK
     *
     * @param context
     *            the Activity that is creating the instance
     * @param apiKey
     *            the API key for your account
     * @param secret
     *            the API secret for your account
     * @return An instance of the mParticle SDK configured with your API key
     */
    public static MParticle getInstance(Context context, String apiKey, String secret) {
        if (instance == null){
            synchronized (MParticle.class){
                if (instance == null){
                    if (null == context) {
                        throw new IllegalArgumentException("context is required");
                    }

                    if (PackageManager.PERMISSION_DENIED == context
                            .checkCallingOrSelfPermission(android.Manifest.permission.INTERNET)) {
                        throw new IllegalArgumentException("mParticle requires android.permission.INTERNET permission");
                    }

                    if (!sTimeoutHandlerThread.isAlive()) {
                        sTimeoutHandlerThread.start();
                    }

                    if (null == sPreferences) {
                        sPreferences = context.getSharedPreferences(Constants.PREFS_FILE, Context.MODE_PRIVATE);
                    }

                    Boolean firstRun = sPreferences.getBoolean(PrefKeys.FIRSTRUN + apiKey, true);

                    if(firstRun) {
                        sPreferences.edit().putBoolean(PrefKeys.FIRSTRUN + apiKey, false).commit();
                    }

                    ConfigManager appConfigManager = new ConfigManager(context, apiKey, secret);
                    Context appContext = context.getApplicationContext();
                    MessageManager messageManager = new MessageManager(appContext, appConfigManager);
                    messageManager.start(appContext, firstRun);

                    instance = new MParticle(appContext, apiKey, messageManager, appConfigManager);
                    if (context instanceof Activity) {
                        instance.mLaunchUri = ((Activity) context).getIntent().getDataString();
                        if (instance.mLaunchUri != null) {
                            Log.d(TAG, "launchuri: "+instance.mLaunchUri);
                        }
                    }

                   /* if (appConfigManager.isPushEnabled()) {
                        if (Boolean.parseBoolean(sDefaultSettings.getProperty(ConfigKeys.ENABLE_PUSH_NOTIFICATIONS))) {
                            String senderId = sDefaultSettings.getProperty(ConfigKeys.PUSH_NOTIFICATION_SENDER_ID);
                            instance.enablePushNotifications(senderId);
                        }
                    }*/
                    if (appConfigManager.getLogUnhandledExceptions()){
                        instance.enableUncaughtExceptionLogging();
                    }
                    instance.logStateTransition(Constants.StateTransitionType.STATE_TRANS_INIT);
                }
            }
        }
        return instance;
    }

    void logStateTransition(String transitionType) {
        instance.ensureActiveSession();
        mMessageManager.logStateTransition(transitionType, mSessionID, mSessionStartTime);
    }

    /**
     * Initialize or return an instance of the mParticle SDK
     *
     * @param context
     *            the Context that is creating the instance
     * @param apiKey
     *            the API key for your account
     * @param secret
     *            the API secret for your account
     * @param sandboxMode
     *            the Stream mode is forced
     * @return An instance of the mParticle SDK configured with your API key
     **/
    public static MParticle getInstance(Context context, String apiKey, String secret, boolean sandboxMode) {
    	MParticle instance = MParticle.getInstance(context, apiKey, secret);
    	instance.mMessageManager.setSandboxMode(sandboxMode);
    	return instance;
   }


    /**
     * Initialize or return an instance of the mParticle SDK using api_key and api_secret from the
     * mparticle.properties file.
     *
     * @param context
     *            the Activity that is creating the instance
     * @return An instance of the mParticle SDK configured with your API key
     */
    public static MParticle getInstance(Context context) {
        return getInstance(context, null, null);
    }

    /**
     * Starts tracking a user session. If a session is already active, it will be resumed.
     *
     * This method should be called from an Activity's onStart() method.
     */
    public void activityStarted(Activity activity) {
        if (mOptedOut) {
            return;
        }
        long initialStartTime = mSessionStartTime;
        ensureActiveSession();
        if (initialStartTime == mSessionStartTime) {
            if (mDebugMode)
                debugLog("Resumed session");
        }
        mAppStateManager.onActivityStarted(activity);
    }

    /**
     * Stop tracking a user session. If the session is restarted before the session timeout it will
     * be resumed.
     *
     * This method should be called from an Activity's onStop() method.
     *
     * To explicitly end a session use the endSession() method.
     */
    public void activityStopped(Activity activity) {
        if (mSessionStartTime == 0 || mOptedOut) {
            return;
        }
        mAppStateManager.onActivityStopped(activity);
    }

    /**
     * Begin tracking a new session. Ends the current session.
     */
    public void newSession() {
        if (mOptedOut) {
            return;
        }
        endSession();
        beginSession();
    }

    /**
     * Explicitly terminates the user session.
     */
    public void endSession() {
        if (mSessionStartTime == 0 || mOptedOut) {
            return;
        }
        long sessionEndTime = System.currentTimeMillis();
        endSession(sessionEndTime);
    }

    /**
     * Explicitly end the session at the given time and generate the end-session message
     *
     * @param sessionEndTime
     */
    private void endSession(long sessionEndTime) {
        if (mDebugMode)
            debugLog("Ended session");

        mMessageManager.stopSession(mSessionID, sessionEndTime, sessionEndTime - mSessionStartTime);
        mMessageManager.endSession(mSessionID, sessionEndTime, sessionEndTime - mSessionStartTime);
        // reset agent to unstarted state
        mSessionStartTime = 0;
        mSessionID = "";
    }

    /**
     * Ensures a session is active.
     */
    private void ensureActiveSession() {
    //    checkSessionTimeout();
        mLastEventTime = System.currentTimeMillis();
        if (0 == mSessionStartTime) {
            beginSession();
        }
    }

    /**
     * Check current session timeout and end the session if needed. Will not start a new session.
     */
    /* package-private */boolean checkSessionTimeout() {
        long now = System.currentTimeMillis();
        if (0 != mSessionStartTime &&
                mAppStateManager.isBackgrounded() &&
                (mConfigManager.getSessionTimeout() < now - mLastEventTime)) {
            if (mDebugMode)
                debugLog("Session timed out");

            endSession(mLastEventTime);
            return true;
        }
        return false;
    }

    /**
     * Creates a new session and generates the start-session message.
     */
    private void beginSession() {
        mLastEventTime = mSessionStartTime = System.currentTimeMillis();
        mSessionID = UUID.randomUUID().toString();
        mEventCount = 0;
        mSessionAttributes = new JSONObject();
        mMessageManager.startSession(mSessionID, mSessionStartTime, mLaunchUri);
        mTimeoutHandler.sendEmptyMessageDelayed(0, mConfigManager.getSessionTimeout());
        if (mDebugMode)
            debugLog("Started new session");
        // clear the launch URI so it isn't sent on future sessions
        mLaunchUri = null;
    }

    /**
     * Upload queued messages to the mParticle server.
     */
    public void upload() {
        mMessageManager.doUpload();
    }
    
    /**
     * Manually set the install referrer 
     */
    public void setInstallReferrer(String referrer) {
        sPreferences.edit().putString(PrefKeys.INSTALL_REFERRER, referrer).commit();
        if (mDebugMode)
            debugLog("Set installReferrer: "+referrer);
    }

    /**
     * Log an event
     *
     * @param eventName
     *            the name of the event to be tracked
     * @param eventType
     *            the type of the event to be tracked
     */
    public void logEvent(String eventName, EventType eventType) {
        logEvent(eventName, eventType, null);
    }

    /**
     * Log an event with data attributes
     *
     * @param eventName
     *            the name of the event to be tracked
     * @param eventType
     *            the type of the event to be tracked
     * @param eventData
     *            a Map of data attributes
     */
    public void logEvent(String eventName, EventType eventType, Map<String, String> eventData) {
        if (mOptedOut) {
            return;
        }
        if (null == eventName) {
            Log.w(TAG, "eventName is required for logEvent");
            return;
        }
        if (eventName.length() > Constants.LIMIT_NAME) {
            Log.w(TAG, "The event name was too long. Discarding event.");
            return;
        }
        ensureActiveSession();
        if (checkEventLimit()) {
            JSONObject eventDataJSON = enforceAttributeConstraints(eventData);
            mMessageManager.logEvent(mSessionID, mSessionStartTime, mLastEventTime, eventName, eventType, eventDataJSON);
            if (mDebugMode)
                if (null == eventDataJSON) {
                    debugLog("Logged event: " + eventName);
                } else {
                    debugLog("Logged event: " + eventName + " with data " + eventDataJSON);
                }
        }
    }

    /**
     * Log a screen view event
     *
     * @param screenName
     *            the name of the screen to be tracked
     */
    public void logScreen(String screenName) {
        logScreen(screenName, null);
    }

    /**
     * Log a screen view event with data attributes
     *
     * @param screenName
     *            the name of the screen to be tracked
     * @param eventData
     *            a Map of data attributes
     */
    public void logScreen(String screenName, Map<String, String> eventData) {
        if (mOptedOut) {
            return;
        }
        if (null == screenName) {
            Log.w(TAG, "screenName is required for logScreenView");
            return;
        }
        if (screenName.length() > Constants.LIMIT_NAME) {
            Log.w(TAG, "The screen name was too long. Discarding event.");
            return;
        }
        ensureActiveSession();
        if (checkEventLimit()) {
            JSONObject eventDataJSON = enforceAttributeConstraints(eventData);
            mMessageManager.logScreen(mSessionID, mSessionStartTime, mLastEventTime, screenName, eventDataJSON);
            if (mDebugMode)
                if (null == eventDataJSON) {
                    debugLog("Logged screen: " + screenName);
                } else {
                    debugLog("Logged screen: " + screenName + " with data " + eventDataJSON);
                }
        }
    }

    /**
     * Log an error event with just a Message
     *
     * @param message
     *            the name of the error event to be tracked
     */
    public void logError(String message) {
        logError(message, null, null);
    }

    /**
     * Log an error event with just an Exception
     *
     * @param exception
     *            an Exception
     */
    public void logError(Exception exception) {
        logError(null, exception, null);
    }

    /**
     * Log an error event with an Exception and any additional data
     *
     * @param exception
     *            an Exception
     * @param eventData
     *            a Map of data attributes
     */
    public void logError(Exception exception, Map<String, String> eventData) {
        logError(null, exception, eventData);
    }

    /**
     * Log an error event with an Exception and any additional data
     *
     * @param message
     *            the name of the error to be tracked
     * @param eventData
     *            a Map of data attributes
     */
    public void logError(String message, Map<String, String> eventData) {
        logError(message, null, eventData);
    }

    /**
     * Log an error event with a Message, Exception, and any additional data
     *
     * @param message
     *            the name of the error event to be tracked
     * @param exception
     *            an Exception
     * @param eventData
     *            a Map of data attributes
     */
    public void logError(String message, Exception exception, Map<String, String> eventData) {
        if (mOptedOut) {
            return;
        }
        if (null == message) {
            Log.w(TAG, "message is required for logErrorEvent");
            return;
        }
        ensureActiveSession();
        if (checkEventLimit()) {
            JSONObject eventDataJSON = enforceAttributeConstraints(eventData);
            mMessageManager.logErrorEvent(mSessionID, mSessionStartTime, mLastEventTime, message, exception, eventDataJSON);
            if (mDebugMode)
                debugLog(
                        "Logged error with message: " + (message == null ? "<none>" : message) +
                        " with data: " + (eventDataJSON == null ? "<none>" : eventDataJSON.toString()) +
                        " with exception: " + (exception == null ? "<none>" : exception.getMessage()));
        }
    }


    void logUnhandledError(Throwable t){
        ensureActiveSession();
        mMessageManager.logErrorEvent(mSessionID, mSessionStartTime, mLastEventTime, t != null ? t.getMessage() : null, t, null, false);
        //we know that the app is about to crash and therefore exit
        logStateTransition(Constants.StateTransitionType.STATE_TRANS_EXIT);
        endSession(System.currentTimeMillis());
    }
    /**
     * Enables location tracking given a provider and update frequency criteria. The provider must
     * be available and the correct permissions must have been requested during installation.
     *
     * @param provider
     *            the provider key
     * @param minTime
     *            the minimum time (in milliseconds) to trigger an update
     * @param minDistance
     *            the minimum distance (in meters) to trigger an update
     */
    public void enableLocationTracking(String provider, long minTime, long minDistance) {
        if (mOptedOut) {
            return;
        }
        try {
            LocationManager locationManager = (LocationManager) mAppContext.getSystemService(Context.LOCATION_SERVICE);
            if (!locationManager.isProviderEnabled(provider)) {
                Log.w(TAG, "That requested location provider is not available");
                return;
            }

            if (null == mLocationListener) {
                mLocationListener = new MParticleLocationListener(this);
            } else {
                // clear the location listener, so it can be added again
                locationManager.removeUpdates(mLocationListener);
            }
            locationManager.requestLocationUpdates(provider, minTime, minDistance, mLocationListener);
        } catch (SecurityException e) {
            Log.w(TAG, "The app must require the appropriate permissions to track location using this provider");
        }
    }

    /**
     * Disables any mParticle location tracking that had been started
     */
    public void disableLocationTracking() {
        if (null != mLocationListener) {
            LocationManager locationManager = (LocationManager) mAppContext.getSystemService(Context.LOCATION_SERVICE);
            locationManager.removeUpdates(mLocationListener);
            mLocationListener = null;
        }
    }

    /**
     * Set the current location of the active session.
     *
     * @param location
     */
    public void setLocation(Location location) {
        mMessageManager.setLocation(location);
    }

    /**
     * Set a single session attribute. The attribute will combined with any existing attributes.
     *
     * @param key
     *            the attribute key
     * @param value
     *            the attribute value
     */
    public void setSessionAttribute(String key, String value) {
        if (mOptedOut) {
            return;
        }
        ensureActiveSession();
        if (mDebugMode)
            debugLog("Set session attribute: " + key + "=" + value);
        if (setCheckedAttribute(mSessionAttributes, key, value)) {
            mMessageManager.setSessionAttributes(mSessionID, mSessionAttributes);
        }
    }

    /**
     * Set a single user attribute. The attribute will combined with any existing attributes.
     *
     * @param key
     *            the attribute key
     * @param value
     *            the attribute value
     */
    public void setUserAttribute(String key, String value) {
        if (mOptedOut) {
            return;
        }
        if (mDebugMode)
            debugLog("Set user attribute: " + key + "=" + value);
        if (setCheckedAttribute(mUserAttributes, key, value)) {
            sPreferences.edit().putString(PrefKeys.USER_ATTRS + mApiKey, mUserAttributes.toString()).commit();
        }
    }
    
    public void setUserIdentity(String id, IdentityType identityType) {
    	if(mOptedOut){
    		return;
    	}

        if (mDebugMode)
    	    debugLog("Setting user identity: " + id);
    	
    	if (null != id && id.length() > Constants.LIMIT_ATTR_VALUE) {
            Log.w(TAG, "Id value length exceeds limit. Discarding id: " + id);
            return;
        }
    	
    	try {
    		JSONObject identity = new JSONObject();
    		identity.put(MessageKey.IDENTITY_NAME, identityType.value);
    		identity.put(MessageKey.IDENTITY_VALUE, id);
    		
    		// verify there is not another IDENTITY_VALUE...if so, remove it first...to do this, copy the
    		//   existing array to a new one
    		JSONArray newUserIdentities = new JSONArray();
 
    		for (int i=0; i<mUserIdentities.length(); i++) {
    			JSONObject testid = mUserIdentities.getJSONObject(i);
    			if (testid.get(MessageKey.IDENTITY_NAME).equals(identityType.value)) {
    				// remove this one by not copying it
    				continue;
    			}
    			newUserIdentities.put(testid);
    		}
    		// now add this one...only if the id is not null
    		if ((id != null) && (id.length()>0)) {
    			newUserIdentities.put(identity);
    		}
    		// now make the new array the saved one
    		mUserIdentities = newUserIdentities;
    	}
    	catch(JSONException e) {
    		Log.w(TAG, "Error setting identity: " + id);
            return;
    	}
    	
    	sPreferences.edit().putString(PrefKeys.USER_IDENTITIES + mApiKey, mUserIdentities.toString()).commit();
    }

    /* package-private */void clearUserAttributes() {
        mUserAttributes = new JSONObject();
        sPreferences.edit().putString(PrefKeys.USER_ATTRS + mApiKey, mUserAttributes.toString()).commit();
    }

    /**
     * Control the opt-in/opt-out status for the application.
     *
     * @param optOutStatus
     *            set to <code>true</code> to opt out of event tracking
     */
    public void setOptOut(boolean optOutStatus) {
        if (optOutStatus == mOptedOut) {
            return;
        }
        if (!optOutStatus) {
            ensureActiveSession();
        }
        mMessageManager.optOut(mSessionID, mSessionStartTime, System.currentTimeMillis(), optOutStatus);
        if (optOutStatus && mSessionStartTime > 0) {
            endSession();
        }

        sPreferences.edit().putBoolean(PrefKeys.OPTOUT + mApiKey, optOutStatus).commit();
        mOptedOut = optOutStatus;

        if (mDebugMode)
            debugLog("Set opt-out: " + mOptedOut);
    }

    /**
     * Get the current opt-out status for the application.
     *
     * @return the opt-out status
     */
    public boolean getOptOut() {
        return mOptedOut;
    }

    /**
     * Turn on or off debug mode for mParticle. In debug mode, the mParticle SDK will output
     * informational messages to LogCat.
     *
     * @param debugMode
     */
    public void setDebug(boolean debugMode) {
        mDebugMode = debugMode;
        mMessageManager.setDebugMode(debugMode);
    }

    /**
     * Set the user session timeout interval.
     *
     * A session is ended when no events (logged events or start/stop events) has occurred within
     * the session timeout interval.
     *
     * @param sessionTimeout
     */
    public void setSessionTimeout(int sessionTimeout) {
        mConfigManager.setSessionTimeout(sessionTimeout);
    }

    /**
     * Set the upload interval period to control how frequently uploads occur.
     *
     * @param uploadInterval
     *            the number of seconds between uploads
     */
    public void setUploadInterval(int uploadInterval) {
        mConfigManager.setUploadInterval(uploadInterval);
    }

    /**
     * Set the upload interval period to control how frequently uploads occur when in debug mode.
     *
     * @param uploadInterval
     *            the number of seconds between uploads
     */
    public void setDebugUploadInterval(int uploadInterval) {
        mConfigManager.setDebugUploadInterval(uploadInterval);
    }


    /**
     * Enable SSL transport when uploading data
     *
     * @param sslEnabled
     *            true to turn on SSL transport, false to use non-SSL transport
     */
    public void setSecureTransport(boolean sslEnabled) {
        mConfigManager.setUseSsl(sslEnabled);
        if (mDebugMode)
            debugLog("Set secure transport: " + sslEnabled);
    }
    
    /**
     * Enable mParticle exception handling to automatically log events on uncaught exceptions
     */
    public void enableUncaughtExceptionLogging() {
        if (null == mExHandler) {
            UncaughtExceptionHandler currentUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
            if (!(currentUncaughtExceptionHandler instanceof ExceptionHandler)) {
                mExHandler = new ExceptionHandler(mMessageManager, currentUncaughtExceptionHandler);
                Thread.setDefaultUncaughtExceptionHandler(mExHandler);
            }
        }
    }

    /**
     * Disables mParticle exception handling and restores the original UncaughtExceptionHandler
     */
    public void disableUncaughtExceptionLogging() {
        if (null != mExHandler) {
            UncaughtExceptionHandler currentUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
            if (currentUncaughtExceptionHandler instanceof ExceptionHandler) {
                Thread.setDefaultUncaughtExceptionHandler(mExHandler.getOriginalExceptionHandler());
                mExHandler = null;
            }
        }
    }

    /**
     * Register the application for GCM notifications
     *
     * @param senderId
     *            the SENDER_ID for the application
     */
    public void enablePushNotifications(String senderId) {
        if (null == getPushRegistrationId()) {
            Intent registrationIntent = new Intent("com.google.android.c2dm.intent.REGISTER");
            registrationIntent.putExtra("app", PendingIntent.getBroadcast(mAppContext, 0, new Intent(), 0));
            registrationIntent.putExtra("sender", senderId);
            ComponentName svc = mAppContext.startService(registrationIntent);
            if (svc == null) {
            	Log.w(TAG, "enablePushNotifications can't start service");
            }
        }
    }

    /**
     * Unregister the device from GCM notifications
     */
    public void clearPushNotifications() {
        if (null != getPushRegistrationId()) {
            Intent unregIntent = new Intent("com.google.android.c2dm.intent.UNREGISTER");
            unregIntent.putExtra("app", PendingIntent.getBroadcast(mAppContext, 0, new Intent(), 0));
            mAppContext.startService(unregIntent);
        }
    }

    /**
     * Manually register the device token for receiving push notifications from mParticle
     * Called from intent handler
     * 
     * @param registrationId
     *            the device registration id
     */
    /* package-private */void setPushRegistrationId(String registrationId) {
        if (mDebugMode)
            debugLog("Set push registration token: " + registrationId);

        if (registrationId == null) {
        	clearPushRegistrationId();
        } else {
        	sPreferences.edit().putString(PrefKeys.PUSH_REGISTRATION_ID, registrationId).commit();
        	mMessageManager.setPushRegistrationId(registrationId, true);
        }
    }

    /**
     * Manually un-register the device token for receiving push notifications from mParticle
     * 
     * Called from intent handler
     */
    /* package-private */void clearPushRegistrationId() {
        if (mDebugMode)
            debugLog("Cleared push registration token");

        String registrationId = getPushRegistrationId();
        if (null != registrationId) {
        	// set the registered msg to false
            mMessageManager.setPushRegistrationId(registrationId, false);
            // then clear the flag
            sPreferences.edit().remove(PrefKeys.PUSH_REGISTRATION_ID).commit();
        } else {
            Log.i(TAG, "Clear push registration requested but device is not registered");
        }
    }

    /**
     * Retrieve the push registration id if it has been setup for this device using either
     * setPushRegistrationId or registerForPushNotifications
     *
     * @return the push registration id
     */
    /* package-private */String getPushRegistrationId() {
        return sPreferences.getString(PrefKeys.PUSH_REGISTRATION_ID, null);
    }

    /**
     * This method checks the event count is below the limit and increments the event count. A
     * warning is logged if the limit has been reached.
     *
     * @return true if event count is below limit
     */
    private boolean checkEventLimit() {
        if (mEventCount < Constants.EVENT_LIMIT) {
            mEventCount++;
            return true;
        } else {
            Log.w(TAG, "The event limit has been exceeded for this session.");
            return false;
        }
    }

    /**
     * This method makes sure the constraints on event attributes are enforced. A JSONObject version
     * of the attributes is return with data that exceeds the limits removed. NOTE: Non-string
     * attributes are not converted to strings, currently.
     *
     * @param attributes
     *            the user-provided JSONObject
     * @return a cleansed copy of the JSONObject
     */
    /* package-private */JSONObject enforceAttributeConstraints(Map<String, String> attributes) {
        if (null == attributes) {
            return null;
        }
        JSONObject checkedAttributes = new JSONObject();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            setCheckedAttribute(checkedAttributes, key, value);
        }
        return checkedAttributes;
    }

    /* package-private */boolean setCheckedAttribute(JSONObject attributes, String key, String value) {
        if (null == attributes || null == key) {
            return false;
        }
        try {
            if (Constants.LIMIT_ATTR_COUNT == attributes.length() && !attributes.has(key)) {
                Log.w(TAG, "Attribute count exceeds limit. Discarding attribute: " + key);
                return false;
            }
            if (null != value && value.toString().length() > Constants.LIMIT_ATTR_VALUE) {
                Log.w(TAG, "Attribute value length exceeds limit. Discarding attribute: " + key);
                return false;
            }
            if (key.length() > Constants.LIMIT_ATTR_NAME) {
                Log.w(TAG, "Attribute name length exceeds limit. Discarding attribute: " + key);
                return false;
            }
            attributes.put(key, value);
        } catch (JSONException e) {
            Log.w(TAG, "JSON error processing attributes. Discarding attribute: " + key);
            return false;
        }
        return true;
    }

    private void debugLog(String message) {
            if (null != mSessionID) {
                Log.d(TAG, mApiKey + ": " + mSessionID + ": " + message);
            } else {
                Log.d(TAG, mApiKey + ": " + message);
            }
    }

    public boolean isAutoTrackingEnabled() {
        return mAutoTrackingEnabled;
    }

    public long getSessionTimeout() {
        return mConfigManager.getSessionTimeout();
    }

    private static final class SessionTimeoutHandler extends Handler {
        private final MParticle mParticle;

        public SessionTimeoutHandler(MParticle mParticle, Looper looper) {
            super(looper);
            this.mParticle = mParticle;
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (!mParticle.checkSessionTimeout()){
                sendEmptyMessageDelayed(0, mParticle.getSessionTimeout());
            }
        }
    }

    private static final class MParticleLocationListener implements LocationListener {
        private final MParticle mParticle;

        public MParticleLocationListener(MParticle mParticle) {
            this.mParticle = mParticle;
        }

        @Override
        public void onLocationChanged(Location location) {
            mParticle.setLocation(location);
        }

        @Override
        public void onProviderDisabled(String provider) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

    }

    public enum EventType {
        Unknown, Navigation, Location, Search, Transaction, UserContent, UserPreference, Social, Other;
        public String toString() {
//            return name().toLowerCase(Locale.US);
            return name();
        }
    }
    
    public enum IdentityType {
    	OTHER(0),
    	CUSTOMERID(1),
    	FACEBOOK(2),
    	TWITTER(3),
    	GOOGLE(4),
    	MICROSOFT(5),
    	YAHOO(6),
    	EMAIL(7);
    	
    	private final int value;
    	
    	private IdentityType(int value) {
    		this.value = value;
    	}
    	
    	public int getValue() {
    		return value;
    	}
    }
}