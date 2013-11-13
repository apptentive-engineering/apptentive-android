/*
 * Copyright (c) 2013, Apptentive, Inc. All Rights Reserved.
 * Please refer to the LICENSE file for the terms and conditions
 * under which redistribution and use of this file is permitted.
 */

package com.apptentive.android.sdk.module.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;
import com.apptentive.android.sdk.GlobalInfo;
import com.apptentive.android.sdk.Log;
import com.apptentive.android.sdk.comm.ApptentiveClient;
import com.apptentive.android.sdk.comm.ApptentiveHttpResponse;
import com.apptentive.android.sdk.model.AutomatedMessage;
import com.apptentive.android.sdk.model.Message;
import com.apptentive.android.sdk.model.MessageFactory;
import com.apptentive.android.sdk.module.metric.MetricModule;
import com.apptentive.android.sdk.storage.ApptentiveDatabase;
import com.apptentive.android.sdk.storage.MessageStore;
import com.apptentive.android.sdk.storage.PayloadSendWorker;
import com.apptentive.android.sdk.util.Constants;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Sky Kelsey
 */
public class MessageManager {

	private static OnSentMessageListener internalSentMessageListener;

	public static void asyncFetchAndStoreMessages(final Context context, final MessagesUpdatedListener listener) {
		Thread thread = new Thread() {
			@Override
			public void run() {
				MessageManager.fetchAndStoreMessages(context, listener);
			}
		};
		Thread.UncaughtExceptionHandler handler = new Thread.UncaughtExceptionHandler() {
			@Override
			public void uncaughtException(Thread thread, Throwable throwable) {
				Log.w("UncaughtException in MessageManager.", throwable);
				MetricModule.sendError(context.getApplicationContext(), throwable, null, null);
			}
		};
		thread.setUncaughtExceptionHandler(handler);
		thread.setName("Apptentive-MessageManagerFetchMessages");
		thread.start();
	}

	/**
	 * Make sure to run this off the UI Thread, as it blocks on IO.
	 */
	public static void fetchAndStoreMessages(Context context, MessagesUpdatedListener listener) {
		if (GlobalInfo.conversationToken == null) {
			return;
		}
		// Fetch the messages.
		String lastId = getMessageStore(context).getLastReceivedMessageId();
		Log.d("Fetching messages after last id: " + lastId);
		List<Message> messagesToSave = fetchMessages(lastId);

		if (messagesToSave != null && messagesToSave.size() > 0) {
			Log.d("Messages retrieved.");
			// Also get the count of incoming unread messages.
			int incomingUnreadMessages = 0;
			// Mark messages from server where sender is the app user as read.
			for (Message message : messagesToSave) {
				if(message.isOutgoingMessage()) {
					message.setRead(true);
				} else {
					incomingUnreadMessages++;
				}
			}
			getMessageStore(context).addOrUpdateMessages(messagesToSave.toArray(new Message[messagesToSave.size()]));
			// Signal listener
			if(incomingUnreadMessages > 0) {
				listener.onMessagesUpdated();
			}
		}
	}

	public static List<Message> getMessages(Context context) {
		return getMessageStore(context).getAllMessages();
	}

	public static void sendMessage(Context context, Message message) {
		getMessageStore(context).addOrUpdateMessages(message);
		ApptentiveDatabase.getInstance(context).addPayload(message);
		PayloadSendWorker.start(context);
	}

	/**
	 * This doesn't need to be run during normal program execution. Testing only.
	 */
	public static void deleteAllMessages(Context context) {
		Log.e("Deleting all messages.");
		getMessageStore(context).deleteAllMessages();
	}

	private static List<Message> fetchMessages(String after_id) {
		Log.d("Fetching messages newer than: " + after_id);
		ApptentiveHttpResponse response = ApptentiveClient.getMessages(null, after_id, null);

		List<Message> ret = new ArrayList<Message>();
		if (!response.isSuccessful()) {
			return ret;
		}
		try {
			ret = parseMessagesString(response.getContent());
		} catch (JSONException e) {
			Log.e("Error parsing messages JSON.", e);
		} catch (Exception e) {
			Log.e("Unexpected error parsing messages JSON.", e);
		}
		return ret;
	}

	public static void updateMessage(Context context, Message message) {
		getMessageStore(context).updateMessage(message);
	}

	protected static List<Message> parseMessagesString(String messageString) throws JSONException {
		List<Message> ret = new ArrayList<Message>();
			JSONObject root = new JSONObject(messageString);
			if (!root.isNull("items")) {
				JSONArray items = root.getJSONArray("items");
				for (int i = 0; i < items.length(); i++) {
					String json = items.getJSONObject(i).toString();
					Message message = MessageFactory.fromJson(json);
					// Since these came back from the server, mark them saved before updating them in the DB.
					message.setState(Message.State.saved);
					ret.add(message);
				}
			}
		return ret;
	}

	private static MessageStore getMessageStore(Context context) {
		return ApptentiveDatabase.getInstance(context);
	}

	public interface MessagesUpdatedListener {
		public void onMessagesUpdated();
	}

	public static void onSentMessage(Context context, Message message, ApptentiveHttpResponse response) {
		if (response == null || !response.isSuccessful()) {
			return;
		}
		if(response.isSuccessful()) {
			try {
				JSONObject responseJson = new JSONObject(response.getContent());
				if (message.getState() == Message.State.sending) {
					message.setState(Message.State.sent);
				}
				message.setId(responseJson.getString(Message.KEY_ID));
				message.setCreatedAt(responseJson.getDouble(Message.KEY_CREATED_AT));
			} catch (JSONException e) {
				Log.e("Error parsing sent message response.", e);
			}
			getMessageStore(context).updateMessage(message);

			if(internalSentMessageListener != null) {
				internalSentMessageListener.onSentMessage(message);
			}
		}
/*
		if(response.isBadpayload()) {
			// TODO: Tell the user that this message failed to send. It will be deleted by the record send worker.
		}
*/
	}

	public interface OnSentMessageListener {
		public void onSentMessage(Message message);
	}

	public static void setInternalSentMessageListener(OnSentMessageListener onSentMessageListener) {
		internalSentMessageListener = onSentMessageListener;
	}

	public static int getUnreadMessageCount(Context context) {
		return getMessageStore(context).getUnreadMessageCount();
	}

	/**
	 * This method will show either a Welcome or a No Love AutomatedMessage. If a No Love message has been shown, no other
	 * AutomatedMessage shall be shown, and no AutomatedMessage shall be shown twice.
	 * @param context The context from which this method is called.
	 * @param forced If true, show a Welcome AutomatedMessage, else show a NoLove AutomatedMessage.
	 */
	public static void createMessageCenterAutoMessage(Context context,boolean forced) {
		SharedPreferences prefs = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE);
		boolean shownManual = prefs.getBoolean(Constants.PREF_KEY_AUTO_MESSAGE_SHOWN_MANUAL, false);
		boolean shownNoLove = prefs.getBoolean(Constants.PREF_KEY_AUTO_MESSAGE_SHOWN_NO_LOVE, false);

		AutomatedMessage message = null;

		if(!shownNoLove) {
			if(forced) {
				if(!shownManual) {
					prefs.edit().putBoolean(Constants.PREF_KEY_AUTO_MESSAGE_SHOWN_MANUAL, true).commit();
					message = AutomatedMessage.createWelcomeMessage(context);
				}
			} else {
				prefs.edit().putBoolean(Constants.PREF_KEY_AUTO_MESSAGE_SHOWN_NO_LOVE, true).commit();
				message = AutomatedMessage.createNoLoveMessage(context);
			}
			if(message != null) {
				getMessageStore(context).addOrUpdateMessages(message);
				ApptentiveDatabase.getInstance(context).addPayload(message);
			}
		}
	}
}
