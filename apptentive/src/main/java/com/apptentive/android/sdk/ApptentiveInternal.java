/*
 * Copyright (c) 2015, Apptentive, Inc. All Rights Reserved.
 * Please refer to the LICENSE file for the terms and conditions
 * under which redistribution and use of this file is permitted.
 */

package com.apptentive.android.sdk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;

import com.apptentive.android.sdk.comm.ApptentiveClient;
import com.apptentive.android.sdk.comm.ApptentiveHttpResponse;
import com.apptentive.android.sdk.model.AppRelease;
import com.apptentive.android.sdk.model.Configuration;
import com.apptentive.android.sdk.model.ConversationTokenRequest;
import com.apptentive.android.sdk.model.CustomData;
import com.apptentive.android.sdk.model.Device;
import com.apptentive.android.sdk.model.Event;
import com.apptentive.android.sdk.model.Person;
import com.apptentive.android.sdk.model.Sdk;
import com.apptentive.android.sdk.module.engagement.EngagementModule;
import com.apptentive.android.sdk.module.engagement.interaction.InteractionManager;
import com.apptentive.android.sdk.module.engagement.interaction.model.MessageCenterInteraction;
import com.apptentive.android.sdk.module.messagecenter.MessageManager;
import com.apptentive.android.sdk.module.metric.MetricModule;
import com.apptentive.android.sdk.module.rating.IRatingProvider;
import com.apptentive.android.sdk.module.rating.impl.GooglePlayRatingProvider;
import com.apptentive.android.sdk.module.survey.OnSurveyFinishedListener;
import com.apptentive.android.sdk.storage.AppReleaseManager;
import com.apptentive.android.sdk.storage.ApptentiveDatabase;
import com.apptentive.android.sdk.storage.DeviceManager;
import com.apptentive.android.sdk.storage.PersonManager;
import com.apptentive.android.sdk.storage.SdkManager;
import com.apptentive.android.sdk.storage.VersionHistoryStore;
import com.apptentive.android.sdk.util.Constants;
import com.apptentive.android.sdk.util.Util;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * This class contains only internal methods. These methods should not be access directly by the host app.
 *
 * @author Sky Kelsey
 */
public class ApptentiveInternal {

	// These variables are initialized in Apptentive.register(), and so they are freely thereafter. If they are unexpectedly null, then if means the host app did not register Apptentive.
	public static Context appContext;
	public static boolean appIsInForeground;
	public static boolean isAppDebuggable;
	public static SharedPreferences prefs;
	public static String apiKey;
	public static String conversationToken;
	public static String conversationId;
	public static String personId;
	public static String androidId;

	private static IRatingProvider ratingProvider;
	private static Map<String, String> ratingProviderArgs;
	private static WeakReference<OnSurveyFinishedListener> onSurveyFinishedListener;

	// Used for temporarily holding customData that needs to be sent on the next message the consumer sends.
	private static Map<String, Object> customData;

	public static final String PUSH_ACTION = "action";

	public enum PushAction {
		pmc,       // Present Message Center.
		unknown;   // Anything unknown will not be handled.

		public static PushAction parse(String name) {
			try {
				return PushAction.valueOf(name);
			} catch (IllegalArgumentException e) {
				Log.d("Error parsing unknown PushAction: " + name);
			}
			return unknown;
		}
	}

	public static void onAppLaunch(final Activity activity) {
		EngagementModule.engageInternal(activity, Event.EventLabel.app__launch.getLabelName());
	}

	public static void onAppExit(final Activity activity) {
		EngagementModule.engageInternal(activity, Event.EventLabel.app__exit.getLabelName());
	}

	static void init(final Context applicationContext) {
		appContext = applicationContext;
		prefs = appContext.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE);
		conversationToken = prefs.getString(Constants.PREF_KEY_CONVERSATION_TOKEN, null);
		conversationId = prefs.getString(Constants.PREF_KEY_CONVERSATION_ID, null);
		personId = prefs.getString(Constants.PREF_KEY_PERSON_ID, null);

		boolean apptentiveDebug = false;
		String logLevelOverride = null;
		String manifestApiKey = null;
		try {
			ApplicationInfo ai = appContext.getPackageManager().getApplicationInfo(appContext.getPackageName(), PackageManager.GET_META_DATA);
			Bundle metaData = ai.metaData;
			if (metaData != null) {
				manifestApiKey = metaData.getString(Constants.MANIFEST_KEY_APPTENTIVE_API_KEY);
				logLevelOverride = metaData.getString(Constants.MANIFEST_KEY_APPTENTIVE_LOG_LEVEL);
				apptentiveDebug = metaData.getBoolean(Constants.MANIFEST_KEY_APPTENTIVE_DEBUG);
				isAppDebuggable = (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
			}
		} catch (Exception e) {
			Log.e("Unexpected error while reading application info.", e);
		}

		// The API Key is stored in SharedPreferences after the first time it is provided to the SDK. This allows us to override the one provided in the manifest during debug sessions.
		apiKey = prefs.getString(Constants.PREF_KEY_API_KEY, null);
		if (apiKey == null) {
			if (manifestApiKey == null) {
				Log.a("UNRECOVERABLE ERROR: No Apptentive API Key found. Please make sure you have specified your Apptentive API Key in your AndroidManifest.xml");
			} else {
				Log.d("Saving API key for the first time: %s", manifestApiKey);
				prefs.edit().putString(Constants.PREF_KEY_API_KEY, manifestApiKey).apply();
			}
		} else {
			Log.d("Using cached Apptentive API Key");
		}
		Log.d("Apptentive API Key: %s", apiKey);

		// Set debuggable and appropriate log level.
		if (apptentiveDebug) {
			Log.i("Apptentive debug logging set to VERBOSE.");
			ApptentiveInternal.setMinimumLogLevel(Log.Level.VERBOSE);
		} else if (logLevelOverride != null) {
			Log.i("Overriding log level: %s", logLevelOverride);
			ApptentiveInternal.setMinimumLogLevel(Log.Level.parse(logLevelOverride));
		} else {
			if (isAppDebuggable) {
				ApptentiveInternal.setMinimumLogLevel(Log.Level.VERBOSE);
			}
		}

		Log.i("Debug mode enabled? %b", isAppDebuggable);

		// Grab app info we need to access later on.
		androidId = Settings.Secure.getString(appContext.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
		Log.d("Android ID: ", androidId);

		// Check the host app version, and notify modules if it's changed.
		try {
			PackageManager packageManager = appContext.getPackageManager();
			PackageInfo packageInfo = packageManager.getPackageInfo(appContext.getPackageName(), 0);

			Integer currentVersionCode = packageInfo.versionCode;
			String currentVersionName = packageInfo.versionName;
			VersionHistoryStore.VersionHistoryEntry lastVersionEntrySeen = VersionHistoryStore.getLastVersionSeen(appContext);
			if (lastVersionEntrySeen == null) {
				onVersionChanged(appContext, null, currentVersionCode, null, currentVersionName);
			} else {
				if (!currentVersionCode.equals(lastVersionEntrySeen.versionCode) || !currentVersionName.equals(lastVersionEntrySeen.versionName)) {
					onVersionChanged(appContext, lastVersionEntrySeen.versionCode, currentVersionCode, lastVersionEntrySeen.versionName, currentVersionName);
				}
			}

			GlobalInfo.appDisplayName = packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageInfo.packageName, 0)).toString();
		} catch (PackageManager.NameNotFoundException e) {
			// Nothing we can do then.
			GlobalInfo.appDisplayName = "this app";
		}

		String lastSeenSdkVersion = prefs.getString(Constants.PREF_KEY_LAST_SEEN_SDK_VERSION, "");
		if (!lastSeenSdkVersion.equals(Constants.APPTENTIVE_SDK_VERSION)) {
			onSdkVersionChanged(appContext, lastSeenSdkVersion, Constants.APPTENTIVE_SDK_VERSION);
		}
////
		// Initialize the Conversation Token, or fetch if needed. Fetch config it the token is available.
		if (conversationToken == null || personId == null) {
			asyncFetchConversationToken(appContext);
		} else {
			asyncFetchAppConfiguration(appContext);
			InteractionManager.asyncFetchAndStoreInteractions(appContext);
		}

		// TODO: Do this on a dedicated thread if it takes too long. Some devices are slow to read device data.
		syncDevice(appContext);
		syncSdk(appContext);
		syncPerson(appContext);

		Log.d("Default Locale: %s", Locale.getDefault().toString());
		Log.d("Conversation id: %s", appContext.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE).getString(Constants.PREF_KEY_CONVERSATION_ID, "null"));
	}

	private static void onVersionChanged(Context context, Integer previousVersionCode, Integer currentVersionCode, String previousVersionName, String currentVersionName) {
		Log.i("Version changed: Name: %s => %s, Code: %d => %d", previousVersionName, currentVersionName, previousVersionCode, currentVersionCode);
		VersionHistoryStore.updateVersionHistory(context, currentVersionCode, currentVersionName);
		AppRelease appRelease = AppReleaseManager.storeAppReleaseAndReturnDiff(context);
		if (appRelease != null) {
			Log.d("App release was updated.");
			ApptentiveDatabase.getInstance(context).addPayload(appRelease);
		}
		invalidateCaches(context);
	}

	private static void onSdkVersionChanged(Context context, String previousSdkVersion, String currentSdkVersion) {
		Log.i("SDK version changed: %s => %s", previousSdkVersion, currentSdkVersion);
		context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE).edit().putString(Constants.PREF_KEY_LAST_SEEN_SDK_VERSION, currentSdkVersion).apply();
		invalidateCaches(context);
	}

	/**
	 * We want to make sure the app is using the latest configuration from the server if the app or sdk version changes.
	 */
	private static void invalidateCaches(Context context) {
		InteractionManager.updateCacheExpiration(context, 0);
		Configuration config = Configuration.load(context);
		config.setConfigurationCacheExpirationMillis(System.currentTimeMillis());
		config.save(context);
	}

	private synchronized static void asyncFetchConversationToken(final Context context) {
		Thread thread = new Thread() {
			@Override
			public void run() {
				fetchConversationToken(context);
			}
		};
		Thread.UncaughtExceptionHandler handler = new Thread.UncaughtExceptionHandler() {
			@Override
			public void uncaughtException(Thread thread, Throwable throwable) {
				Log.w("Caught UncaughtException in thread \"%s\"", throwable, thread.getName());
				MetricModule.sendError(context.getApplicationContext(), throwable, null, null);
			}
		};
		thread.setUncaughtExceptionHandler(handler);
		thread.setName("Apptentive-FetchConversationToken");
		thread.start();
	}

	/**
	 * First looks to see if we've saved the ConversationToken in memory, then in SharedPreferences, and finally tries to get one
	 * from the server.
	 */
	private static void fetchConversationToken(Context context) {
		// Try to fetch a new one from the server.
		ConversationTokenRequest request = new ConversationTokenRequest();

		// Send the Device and Sdk now, so they are available on the server from the start.
		request.setDevice(DeviceManager.storeDeviceAndReturnIt(context));
		request.setSdk(SdkManager.storeSdkAndReturnIt(context));
		request.setPerson(PersonManager.storePersonAndReturnIt(context));

		ApptentiveHttpResponse response = ApptentiveClient.getConversationToken(context, request);
		if (response == null) {
			Log.w("Got null response fetching ConversationToken.");
			return;
		}
		if (response.isSuccessful()) {
			try {
				JSONObject root = new JSONObject(response.getContent());
				String conversationToken = root.getString("token");
				Log.d("ConversationToken: " + conversationToken);
				String conversationId = root.getString("id");
				Log.d("New Conversation id: %s", conversationId);

				if (conversationToken != null && !conversationToken.equals("")) {
					setConversationToken(conversationToken);
					setConversationId(conversationId);
				}
				String personId = root.getString("person_id");
				Log.d("PersonId: " + personId);
				if (personId != null && !personId.equals("")) {
					setPersonId(personId);
				}
				// Try to fetch app configuration, since it depends on the conversation token.
				asyncFetchAppConfiguration(context);
				InteractionManager.asyncFetchAndStoreInteractions(context);
			} catch (JSONException e) {
				Log.e("Error parsing ConversationToken response json.", e);
			}
		}
	}

	/**
	 * Fetches the global app configuration from the server and stores the keys into our SharedPreferences.
	 */
	private static void fetchAppConfiguration(Context context) {
		boolean force = isAppDebuggable;

		// Don't get the app configuration unless forced, or the cache has expired.
		if (force || Configuration.load(context).hasConfigurationCacheExpired()) {
			Log.i("Fetching new Configuration.");
			ApptentiveHttpResponse response = ApptentiveClient.getAppConfiguration(context);
			try {
				Map<String, String> headers = response.getHeaders();
				if (headers != null) {
					String cacheControl = headers.get("Cache-Control");
					Integer cacheSeconds = Util.parseCacheControlHeader(cacheControl);
					if (cacheSeconds == null) {
						cacheSeconds = Constants.CONFIG_DEFAULT_APP_CONFIG_EXPIRATION_DURATION_SECONDS;
					}
					Log.d("Caching configuration for %d seconds.", cacheSeconds);
					Configuration config = new Configuration(response.getContent());
					config.setConfigurationCacheExpirationMillis(System.currentTimeMillis() + cacheSeconds * 1000);
					config.save(context);
				}
			} catch (JSONException e) {
				Log.e("Error parsing app configuration from server.", e);
			}
		} else {
			Log.v("Using cached Configuration.");
		}
	}

	private static void asyncFetchAppConfiguration(final Context context) {
		Thread thread = new Thread() {
			public void run() {
				fetchAppConfiguration(context);
			}
		};
		Thread.UncaughtExceptionHandler handler = new Thread.UncaughtExceptionHandler() {
			@Override
			public void uncaughtException(Thread thread, Throwable throwable) {
				Log.e("Caught UncaughtException in thread \"%s\"", throwable, thread.getName());
				MetricModule.sendError(context.getApplicationContext(), throwable, null, null);
			}
		};
		thread.setUncaughtExceptionHandler(handler);
		thread.setName("Apptentive-FetchAppConfiguration");
		thread.start();
	}

	/**
	 * Sends current Device to the server if it differs from the last time it was sent.
	 */
	static void syncDevice(Context context) {
		Device deviceInfo = DeviceManager.storeDeviceAndReturnDiff(context);
		if (deviceInfo != null) {
			Log.d("Device info was updated.");
			Log.v(deviceInfo.toString());
			ApptentiveDatabase.getInstance(context).addPayload(deviceInfo);
		} else {
			Log.d("Device info was not updated.");
		}
	}

	/**
	 * Sends current Sdk to the server if it differs from the last time it was sent.
	 */
	private static void syncSdk(Context context) {
		Sdk sdk = SdkManager.storeSdkAndReturnDiff(context);
		if (sdk != null) {
			Log.d("Sdk was updated.");
			Log.v(sdk.toString());
			ApptentiveDatabase.getInstance(context).addPayload(sdk);
		} else {
			Log.d("Sdk was not updated.");
		}
	}

	/**
	 * Sends current Person to the server if it differs from the last time it was sent.
	 */
	private static void syncPerson(Context context) {
		Person person = PersonManager.storePersonAndReturnDiff(context);
		if (person != null) {
			Log.d("Person was updated.");
			Log.v(person.toString());
			ApptentiveDatabase.getInstance(context).addPayload(person);
		} else {
			Log.d("Person was not updated.");
		}
	}

	public static IRatingProvider getRatingProvider() {
		if (ratingProvider == null) {
			ratingProvider = new GooglePlayRatingProvider();
		}
		return ratingProvider;
	}

	public static void setRatingProvider(IRatingProvider ratingProvider) {
		ApptentiveInternal.ratingProvider = ratingProvider;
	}

	public static Map<String, String> getRatingProviderArgs() {
		return ratingProviderArgs;
	}

	public static void putRatingProviderArg(String key, String value) {
		if (ratingProviderArgs == null) {
			ratingProviderArgs = new HashMap<String, String>();
		}
		ratingProviderArgs.put(key, value);
	}

	public static void setOnSurveyFinishedListener(OnSurveyFinishedListener onSurveyFinishedListener) {
		if (onSurveyFinishedListener != null) {
			ApptentiveInternal.onSurveyFinishedListener = new WeakReference<OnSurveyFinishedListener>(onSurveyFinishedListener);
		} else {
			ApptentiveInternal.onSurveyFinishedListener = null;
		}
	}

	public static OnSurveyFinishedListener getOnSurveyFinishedListener() {
		return (onSurveyFinishedListener == null) ? null : onSurveyFinishedListener.get();
	}

	/**
	 * Pass in a log level to override the default, which is {@link Log.Level#INFO}
	 */
	public static void setMinimumLogLevel(Log.Level level) {
		Log.overrideLogLevel(level);
	}

	private static String pushCallbackActivityName;

	public static void setPushCallbackActivity(Class<? extends Activity> activity) {
		pushCallbackActivityName = activity.getName();
		Log.d("Setting push callback activity name to %s", pushCallbackActivityName);
	}

	public static String getPushCallbackActivityName() {
		return pushCallbackActivityName;
	}

	/**
	 * The key that is used to store extra data on an Apptentive push notification.
	 */
	static final String APPTENTIVE_PUSH_EXTRA_KEY = "apptentive";

	static final String PARSE_PUSH_EXTRA_KEY = "com.parse.Data";

	static String getApptentivePushNotificationData(Intent intent) {
		String apptentive = null;
		if (intent != null) {
			Log.v("Got an Intent.");
			// Parse
			if (intent.hasExtra(PARSE_PUSH_EXTRA_KEY)) {
				String parseStringExtra = intent.getStringExtra(PARSE_PUSH_EXTRA_KEY);
				Log.v("Got a Parse Push.");
				try {
					JSONObject parseJson = new JSONObject(parseStringExtra);
					apptentive = parseJson.optString(APPTENTIVE_PUSH_EXTRA_KEY, null);
				} catch (JSONException e) {
					Log.e("Corrupt Parse String Extra: %s", parseStringExtra);
				}
			} else {
				// Straight GCM / SNS
				Log.v("Got a non-Parse push.");
				apptentive = intent.getStringExtra(APPTENTIVE_PUSH_EXTRA_KEY);
			}
		}
		return apptentive;
	}

	static String getApptentivePushNotificationData(Bundle pushBundle) {
		if (pushBundle != null) {
			return pushBundle.getString(APPTENTIVE_PUSH_EXTRA_KEY);
		}
		return null;
	}

	static boolean setPendingPushNotification(Context context, String apptentivePushData) {
		if (apptentivePushData != null) {
			Log.d("Saving Apptentive push notification data.");
			SharedPreferences prefs = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE);
			prefs.edit().putString(Constants.PREF_KEY_PENDING_PUSH_NOTIFICATION, apptentivePushData).apply();
			MessageManager.startMessagePreFetchTask(context);
			return true;
		}
		return false;
	}

	public static boolean showMessageCenterInternal(Activity activity, Map<String, Object> customData) {
		boolean interactionShown = false;
		if (EngagementModule.canShowInteraction(activity, "com.apptentive", "app", MessageCenterInteraction.DEFAULT_INTERNAL_EVENT_NAME)) {
			if (customData != null) {
				Iterator<String> keysIterator = customData.keySet().iterator();
				while (keysIterator.hasNext()) {
					String key = keysIterator.next();
					Object value = customData.get(key);
					if (value != null) {
						if (!(value instanceof String ||
								value instanceof Boolean ||
								value instanceof Long ||
								value instanceof Double ||
								value instanceof Float ||
								value instanceof Integer ||
								value instanceof Short)) {
							Log.w("Removing invalid customData type: %s", value.getClass().getSimpleName());
							keysIterator.remove();
						}
					}
				}
			}
			ApptentiveInternal.customData = customData;
			interactionShown = EngagementModule.engageInternal(activity, MessageCenterInteraction.DEFAULT_INTERNAL_EVENT_NAME);
			if (!interactionShown) {
				ApptentiveInternal.customData = null;
			}
		} else {
			showMessageCenterFallback(activity);
		}
		return interactionShown;
	}

	public static void showMessageCenterFallback(Activity activity) {
		Intent intent = MessageCenterInteraction.generateMessageCenterErrorIntent(activity.getApplicationContext());
		activity.startActivity(intent);
	}

	public static boolean canShowMessageCenterInternal(Context context) {
		return EngagementModule.canShowInteraction(context, "com.apptentive", "app", MessageCenterInteraction.DEFAULT_INTERNAL_EVENT_NAME);
	}

	public static Map<String, Object> getAndClearCustomData() {
		Map<String, Object> customData = ApptentiveInternal.customData;
		ApptentiveInternal.customData = null;
		return customData;
	}

	public static void addCustomDeviceData(Context context, String key, Object value) {
		if (key == null || key.trim().length() == 0) {
			return;
		}
		key = key.trim();
		CustomData customData = DeviceManager.loadCustomDeviceData(context);
		if (customData != null) {
			try {
				customData.put(key, value);
				DeviceManager.storeCustomDeviceData(context, customData);
			} catch (JSONException e) {
				Log.w("Unable to add custom device data.", e);
			}
		}
	}

	public static void addCustomPersonData(Context context, String key, Object value) {
		if (key == null || key.trim().length() == 0) {
			return;
		}
		CustomData customData = PersonManager.loadCustomPersonData(context);
		if (customData != null) {
			try {
				customData.put(key, value);
				PersonManager.storeCustomPersonData(context, customData);
			} catch (JSONException e) {
				Log.w("Unable to add custom person data.", e);
			}
		}
	}

	private static void setConversationToken(String newConversationToken) {
		conversationToken = newConversationToken;
		prefs.edit().putString(Constants.PREF_KEY_CONVERSATION_TOKEN, conversationToken).apply();
	}

	private static void setConversationId(String newConversationId) {
		conversationId = newConversationId;
		prefs.edit().putString(Constants.PREF_KEY_CONVERSATION_ID, conversationId).apply();
	}

	private static void setPersonId(String newPersonId) {
		personId = newPersonId;
		prefs.edit().putString(Constants.PREF_KEY_PERSON_ID, personId).apply();
	}


}
