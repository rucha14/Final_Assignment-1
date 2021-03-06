/*------------------------------------------------------------------------
* (The MIT License)
* 
* Copyright (c) 2008-2011 Rhomobile, Inc.
* 
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
* 
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
* THE SOFTWARE.
* 
* http://rhomobile.com
*------------------------------------------------------------------------*/



import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.Vector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.rhomobile.rhodes.alert.Alert;
import com.rhomobile.rhodes.alert.StatusNotification;
import com.rhomobile.rhodes.event.EventStore;
import com.rhomobile.rhodes.extmanager.RhoExtManager;
import com.rhomobile.rhodes.file.RhoFileApi;
import com.rhomobile.rhodes.geolocation.GeoLocation;
import com.rhomobile.rhodes.mainview.MainView;
import com.rhomobile.rhodes.mainview.SplashScreen;
import com.rhomobile.rhodes.osfunctionality.AndroidFunctionalityManager;
import com.rhomobile.rhodes.ui.AboutDialog;
import com.rhomobile.rhodes.ui.LogOptionsDialog;
import com.rhomobile.rhodes.ui.LogViewDialog;
import com.rhomobile.rhodes.uri.ExternalHttpHandler;
import com.rhomobile.rhodes.uri.LocalFileHandler;
import com.rhomobile.rhodes.uri.MailUriHandler;
import com.rhomobile.rhodes.uri.SmsUriHandler;
import com.rhomobile.rhodes.uri.TelUriHandler;
import com.rhomobile.rhodes.uri.UriHandler;
import com.rhomobile.rhodes.uri.VideoUriHandler;
import com.rhomobile.rhodes.util.ContextFactory;
import com.rhomobile.rhodes.util.JSONGenerator;
import com.rhomobile.rhodes.util.PerformOnUiThread;
import com.rhomobile.rhodes.util.PhoneId;
import com.rhomobile.rhodes.util.Utils;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.RemoteViews;

public class RhodesService extends Service {
	
	private static final String TAG = RhodesService.class.getSimpleName();
	
	private static final boolean DEBUG = false;
	
	public static final String INTENT_EXTRA_PREFIX = "com.rhomobile.rhodes";
	
	public static final String INTENT_SOURCE = INTENT_EXTRA_PREFIX + ".intent_source";
	
	public static int WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN;
	public static int WINDOW_MASK = WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN;
	public static boolean ANDROID_TITLE = true;
	
	private static final int DOWNLOAD_PACKAGE_ID = 1;
	
	private static final String ACTION_ASK_CANCEL_DOWNLOAD = "com.rhomobile.rhodes.DownloadManager.ACTION_ASK_CANCEL_DOWNLOAD";
	private static final String ACTION_CANCEL_DOWNLOAD = "com.rhomobile.rhodes.DownloadManager.ACTION_CANCEL_DOWNLOAD";

    private static final String NOTIFICATION_NONE = "none";
    private static final String NOTIFICATION_BACKGROUND = "background";
    private static final String NOTIFICATION_ALWAYS = "always";

	private static RhodesService sInstance = null;
	
	private final IBinder mBinder = new LocalBinder();
	
	private BroadcastReceiver mConnectionChangeReceiver;
	
	@SuppressWarnings("rawtypes")
	private static final Class[] mStartForegroundSignature = new Class[] {int.class, Notification.class};
	@SuppressWarnings("rawtypes")
	private static final Class[] mStopForegroundSignature = new Class[] {boolean.class};
	@SuppressWarnings("rawtypes")
	private static final Class[] mSetForegroundSignature = new Class[] {boolean.class};
	
	private Method mStartForeground;
	private Method mStopForeground;
	private Method mSetForeground;
	
	private NotificationManager mNM;
	
	private boolean mNeedGeoLocationRestart = false;
	
	class PowerWakeLock {
	    private PowerManager.WakeLock wakeLockObject = null;
	    private boolean wakeLockEnabled = false;

        synchronized boolean isHeld() {
            return (wakeLockObject != null) && wakeLockObject.isHeld(); 
        }
        synchronized boolean acquire(boolean enable) {
            if (enable) {
                Logger.I(TAG, "Enable WakeLock");
                wakeLockEnabled = true;
            }
            if (wakeLockEnabled) {
                if (wakeLockObject == null) {
                    PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        Logger.I(TAG, "Acquire WakeLock");
                        wakeLockObject = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);
                        wakeLockObject.setReferenceCounted(false);
                        wakeLockObject.acquire();
                    }
                    else {
                        Logger.E(TAG, "Can not get PowerManager to acquire WakeLock!!!");
                    }
                    return false;
                }
                //return wakeLockObject.isHeld();
                return true;
            }
            return false;
        }
        synchronized boolean release()
        {
            if (wakeLockObject != null) {
                Logger.I(TAG, "Release WakeLock");
                wakeLockObject.release();
                wakeLockObject = null;
                return true;
            }
            return false;
	    }
	    
	    synchronized boolean reset() {
            if (wakeLockObject != null) {
                Logger.I(TAG, "Reset WakeLock");
                wakeLockObject.release();
                wakeLockObject = null;
                wakeLockEnabled = false;
                return true;
            }
            return false;
	    }
	}
	
	private PowerWakeLock wakeLock = new PowerWakeLock();
	
	private Vector<UriHandler> mUriHandlers = new Vector<UriHandler>();
	
	public boolean handleUrlLoading(String url) {
		Enumeration<UriHandler> e = mUriHandlers.elements();
		while (e.hasMoreElements()) {
			UriHandler handler = e.nextElement();
			try {
				if (handler.handle(url))
					return true;
			}
			catch (Exception ex) {
				Logger.E(TAG, ex.getMessage());
				continue;
			}
		}
		
		return false;
	}
	
	Handler mHandler = null;
	
	public native void doSyncAllSources(boolean v);
	public native void doSyncSource(String source);
	
	public native String normalizeUrl(String url);
	
	public static native void doRequest(String url);
	public static native void doRequestAsync(String url);
	public static native void doRequestEx(String url, String body, String data, boolean waitForResponse);
    public static native void doRequestJson(String url, String body, String data, boolean waitForResponse);
    
    public static native void loadUrl(String url);
    public static native String currentLocation(int tab);
	
	public static native void navigateBack();
	
	public static native void onScreenOrientationChanged(int width, int height, int angle);
	
	public static native void callUiCreatedCallback();
	public static native void callUiDestroyedCallback();
	public static native void callActivationCallback(boolean active);
	
	public static native String getBuildConfig(String key);
	
	public static native String getInvalidSecurityTokenMessage();
	
	public static native void resetHttpLogging(String http_log_url);
	public static native void resetFileLogging(String log_path);
	
	public static native boolean isMotorolaLicencePassed(String license, String company, String appName);
	
	public native void notifyNetworkStatusChanged( int status );
	
	
	public static RhodesService getInstance() {
		return sInstance;
	}
	
	public static native boolean isTitleEnabled();
	
	private static final String CONF_PHONE_ID = "phone_id";
	private PhoneId getPhoneId() {
	    String strPhoneId = RhoConf.getString(CONF_PHONE_ID);
	    PhoneId phoneId = PhoneId.getId(this, strPhoneId);
	    if (strPhoneId == null || strPhoneId.length() == 0)
	        RhoConf.setString(CONF_PHONE_ID, phoneId.toString());

	    return phoneId;
	}
	
	public class LocalBinder extends Binder {
		RhodesService getService() {
			return RhodesService.this;
		}
	};

	@Override
	public IBinder onBind(Intent intent) {
		Logger.D(TAG, "onBind");
		return mBinder;
	}

	@Override
	public void onCreate() {
		Logger.D(TAG, "onCreate");

		sInstance = this;

		Context context = this;

		mNM = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);

        LocalFileProvider.revokeUriPermissions(this);

        Logger.I("Rhodes", "Loading...");
        RhodesApplication.create();

        RhodesActivity ra = RhodesActivity.getInstance();
        if (ra != null) {
            // Show splash screen only if we have active activity
            SplashScreen splashScreen = ra.getSplashScreen();
            splashScreen.start();

            // Increase WebView rendering priority
            WebView w = new WebView(context);
            WebSettings webSettings = w.getSettings();
            webSettings.setRenderPriority(WebSettings.RenderPriority.HIGH);
        }

		initForegroundServiceApi();

		// Register custom uri handlers here
		mUriHandlers.addElement(new ExternalHttpHandler(context));
		mUriHandlers.addElement(new LocalFileHandler(context));
		mUriHandlers.addElement(new MailUriHandler(context));
		mUriHandlers.addElement(new TelUriHandler(context));
		mUriHandlers.addElement(new SmsUriHandler(context));
		mUriHandlers.addElement(new VideoUriHandler(context));

        mConnectionChangeReceiver = new ConnectionChangeReceiver();
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(mConnectionChangeReceiver,filter);

		RhodesApplication.start();

		if (BaseActivity.getActivitiesCount() > 0)
			handleAppActivation();
	}

	public static void handleAppStarted()
	{
	    RhodesApplication.handleAppStarted();
	}
	
	private void initForegroundServiceApi() {
		try {
			mStartForeground = getClass().getMethod("startForeground", mStartForegroundSignature);
			mStopForeground = getClass().getMethod("stopForeground", mStopForegroundSignature);
			mSetForeground = getClass().getMethod("setForeground", mSetForegroundSignature);
		}
		catch (NoSuchMethodException e) {
			mStartForeground = null;
			mStopForeground = null;
			mSetForeground = null;
		}
	}
	
	@Override
	public void onDestroy() {
	
		if(DEBUG)
			Log.d(TAG, "+++ onDestroy");
		sInstance = null;
		RhodesApplication.stop();
	}
	
	@Override
	public void onStart(Intent intent, int startId) {
		Log.d(TAG, "onStart");
		try {
			handleCommand(intent, startId);
		}
		catch (Exception e) {
			Logger.E(TAG, "Can't handle service command");
			Logger.E(TAG, e);
		}
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Logger.D(TAG, "onStartCommand");
		try {
			handleCommand(intent, startId);
		}
		catch (Exception e) {
            Logger.E(TAG, "Can't handle service command");
            Logger.E(TAG, e);
		}
		return Service.START_STICKY;
	}
	
	private void handleCommand(Intent intent, int startId) {
		if (intent == null) {
			return;
		}
		String source = intent.getStringExtra(INTENT_SOURCE);
		Logger.I(TAG, "handleCommand: startId=" + startId + ", source=" + source);
		if (source == null)
			throw new IllegalArgumentException("Service command received from empty source");
		
		if (source.equals(BaseActivity.INTENT_SOURCE)) {
			Logger.D(TAG, "New activity was created");
		}
		else if (source.equals(PushContract.INTENT_SOURCE)) {
			int type = intent.getIntExtra(PushContract.INTENT_TYPE, PushContract.INTENT_TYPE_UNKNOWN);
			switch (type) {
			case PushContract.INTENT_TYPE_REGISTRATION_ID:
			{
				String id = intent.getStringExtra(PushContract.INTENT_REGISTRATION_ID);
                String pushType = intent.getStringExtra(PushContract.INTENT_PUSH_CLIENT);
				if (id == null)
					throw new IllegalArgumentException("Empty registration id received in service command");
				Logger.I(TAG, "Received PUSH registration id: " + id);
				setPushRegistrationId(pushType, id);
			}
				break;
            case PushContract.INTENT_TYPE_MESSAGE:
                if(intent.hasExtra(PushContract.INTENT_MESSAGE_EXTRAS)) {
                    final String pushType = intent.getStringExtra(PushContract.INTENT_PUSH_CLIENT);
                    final Bundle extras = intent.getBundleExtra(PushContract.INTENT_MESSAGE_EXTRAS);
                    Logger.D(TAG, "Received PUSH message: " + extras);
                    RhodesApplication.runWhen(
                        RhodesApplication.AppState.AppStarted,
                        new RhodesApplication.StateHandler(true) {
                            @Override
                            public void run()
                            {
                                handlePushMessage(pushType, extras);
                            }
                        });
                    break;
                }
                else if (intent.hasExtra(PushContract.INTENT_MESSAGE_JSON)){
                    final String pushType = intent.getStringExtra(PushContract.INTENT_PUSH_CLIENT);
                    final String json = intent.getStringExtra(PushContract.INTENT_MESSAGE_JSON);
                    if (json != null) {
                        Logger.D(TAG, "Received PUSH message (JSON): " + json);
                        RhodesApplication.runWhen(
                            RhodesApplication.AppState.AppStarted,
                            new RhodesApplication.StateHandler(true) {
                                @Override
                                public void run()
                                {
                                    handlePushMessage(pushType, json);
                                }
                            });
                    }
                    break;
                }
            default:
                Logger.W(TAG, "Unknown command type received from " + source + ": " + type);
            }
		}
	}
	
	public void startServiceForeground(int id, Notification notification) {
		if (mStartForeground != null) {
			try {
				mStartForeground.invoke(this, new Object[] {Integer.valueOf(id), notification});
			}
			catch (InvocationTargetException e) {
				Log.e(TAG, "Unable to invoke startForeground", e);
			}
			catch (IllegalAccessException e) {
				Log.e(TAG, "Unable to invoke startForeground", e);
			}
			return;
		}
		
		if (mSetForeground != null) {
			try {
				mSetForeground.invoke(this, new Object[] {Boolean.valueOf(true)});
			}
			catch (InvocationTargetException e) {
				Log.e(TAG, "Unable to invoke setForeground", e);
			}
			catch (IllegalAccessException e) {
				Log.e(TAG, "Unable to invoke setForeground", e);
			}
		}
		mNM.notify(id, notification);
	}
	
	public void stopServiceForeground(int id) {
		if (mStopForeground != null) {
			try {
				mStopForeground.invoke(this, new Object[] {Integer.valueOf(id)});
			}
			catch (InvocationTargetException e) {
				Log.e(TAG, "Unable to invoke stopForeground", e);
			}
			catch (IllegalAccessException e) {
				Log.e(TAG, "Unable to invoke stopForeground", e);
			}
			return;
		}
		
		mNM.cancel(id);
		if (mSetForeground != null) {
			try {
				mSetForeground.invoke(this, new Object[] {Boolean.valueOf(false)});
			}
			catch (InvocationTargetException e) {
				Log.e(TAG, "Unable to invoke setForeground", e);
			}
			catch (IllegalAccessException e) {
				Log.e(TAG, "Unable to invoke setForeground", e);
			}
		}
	}
	
	public void setMainView(MainView v) throws NullPointerException {
		RhodesActivity.safeGetInstance().setMainView(v);
	}
	
	public MainView getMainView() {
		RhodesActivity ra = RhodesActivity.getInstance();
		if (ra == null)
			return null;
		return ra.getMainView();
	}

	public static void exit() {
	    PerformOnUiThread.exec(new Runnable() {
	        @Override
	        public void run() {
                Logger.I(TAG, "Exit application");
                try {
                    // Do this fake state change in order to make processing before server is stopped
                    RhodesApplication.stateChanged(RhodesApplication.UiState.MainActivityPaused);
        
                    RhodesService service = RhodesService.getInstance();
                    if (service != null)
                    {
                        Logger.T(TAG, "stop RhodesService");
                        service.wakeLock.reset();
                        service.stopSelf();
                    }
                    
                    Logger.T(TAG, "stop RhodesApplication");
                    RhodesApplication.stop();
                }
                catch (Exception e) {
                    Logger.E(TAG, e);
                }
	        }
	    });
	}
	
	public static void showAboutDialog() {
		PerformOnUiThread.exec(new Runnable() {
			public void run() {
				final AboutDialog aboutDialog = new AboutDialog(ContextFactory.getUiContext());
				aboutDialog.setTitle("About");
				aboutDialog.setCanceledOnTouchOutside(true);
				aboutDialog.setCancelable(true);
				aboutDialog.show();
			}
		});
	}
	
	public static void showLogView() {
		PerformOnUiThread.exec(new Runnable() {
			public void run() {
				final LogViewDialog logViewDialog = new LogViewDialog(ContextFactory.getUiContext());
				logViewDialog.setTitle("Log View");
				logViewDialog.setCancelable(true);
				logViewDialog.show();
			}
		});
	}
	
	public static void showLogOptions() {
		PerformOnUiThread.exec(new Runnable() {
			public void run() {
				final LogOptionsDialog logOptionsDialog = new LogOptionsDialog(ContextFactory.getUiContext());
				logOptionsDialog.setTitle("Logging Options");
				logOptionsDialog.setCancelable(true);
				logOptionsDialog.show();
			}
		});
	}
	
	// Called from native code
	public static void deleteFilesInFolder(String folder) {
		try {
			String[] children = new File(folder).list();
			for (int i = 0; i != children.length; ++i)
				Utils.deleteRecursively(new File(folder, children[i]));
		}
		catch (Exception e) {
			Logger.E(TAG, e);
		}
	}
	
	public static boolean pingHost(String host) {
		HttpURLConnection conn = null;
		boolean hostExists = false;
		try {
				URL url = new URL(host);
				HttpURLConnection.setFollowRedirects(false);
				conn = (HttpURLConnection) url.openConnection();

				conn.setRequestMethod("HEAD");
				conn.setAllowUserInteraction( false );
				conn.setDoInput( true );
				conn.setDoOutput( true );
				conn.setUseCaches( false );
				conn.setConnectTimeout( 10000 );
				conn.setReadTimeout( 10000 );

				hostExists = (conn.getContentLength() > 0);
				if(hostExists)
					Logger.I(TAG, "PING network SUCCEEDED.");
				else
					Logger.E(TAG, "PING network FAILED.");
		}
		catch (Exception e) {
			Logger.E(TAG, e);
		}
		finally {            
		    if (conn != null) 
		    {
		    	try
		    	{
		    		conn.disconnect(); 
		    	}
		    	catch(Exception e) 
		    	{
		    		Logger.E(TAG, e);
		    	}
		    }
		}
		
		return hostExists;
	}
	
	private static boolean hasNetworkEx( boolean checkCell, boolean checkWifi, boolean checkEthernet, boolean checkWimax, boolean checkBluetooth, boolean checkAny) {
		if (!Capabilities.NETWORK_STATE_ENABLED) {
			Logger.E(TAG, "HAS_NETWORK: Capability NETWORK_STATE disabled");
			return false;
		}
		
		Context ctx = RhodesService.getContext();
		ConnectivityManager conn = (ConnectivityManager)ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
		if (conn == null)
		{
			Logger.E(TAG, "HAS_NETWORK: cannot create ConnectivityManager");
			return false;
		}
		 
		NetworkInfo[] info = conn.getAllNetworkInfo();
		if (info == null)
		{
			Logger.E(TAG, "HAS_NETWORK: cannot issue getAllNetworkInfo");
			return false;
		}

		//{
		//	Utils.platformLog("NETWORK", "$$$$$$$$$$$$$$$$$$$   Networks ; $$$$$$$$$$$$$$$$$$$$$$");
		//	for (int i = 0, lim = info.length; i < lim; ++i) 
		//	{
		//		int type = info[i].getType();
		//		String name = info[i].getTypeName();
		//		boolean is_connected = info[i].getState() == NetworkInfo.State.CONNECTED;
		//		Utils.platformLog("NETWORK", "        - Name ["+name+"],  type ["+String.valueOf(type)+"], connected ["+String.valueOf(is_connected)+"]");
		//	}
		//}
		
		for (int i = 0, lim = info.length; i < lim; ++i) 
		{
			boolean is_connected = info[i].getState() == NetworkInfo.State.CONNECTED;
			int type = info[i].getType();
			if (is_connected) {
				if ((type == ConnectivityManager.TYPE_MOBILE) && (checkCell)) return true;
				if ((type == ConnectivityManager.TYPE_WIFI) && (checkWifi)) return true;
				if ((type == 6) && (checkWimax)) return true;
				if ((type == 9) && (checkEthernet)) return true;
				if ((type == 7) && (checkBluetooth)) return true;
				if (checkAny) return true; 
			}
		}

    	Logger.I(TAG, "HAS_NETWORK: all networks are disconnected");
		
		return false;
	}
	
	private static boolean hasWiFiNetwork() {
		return hasNetworkEx(false, true, true, true, false, false);
	}
	
	private static boolean hasCellNetwork() {
		return hasNetworkEx(true, false, false, false, false, false);
	}
	
	private static boolean hasNetwork() {
		return hasNetworkEx(true, true, true, true, false, true);
	}
	
	private static String getCurrentLocale() {
		String locale = Locale.getDefault().getLanguage();
		if (locale.length() == 0)
			locale = "en";
		return locale;
	}
	
	private static String getCurrentCountry() {
		String cl = Locale.getDefault().getCountry();
		return cl;
	}

    public static int getScreenWidth() {
        if (BaseActivity.getScreenProperties() != null)
            return BaseActivity.getScreenProperties().getWidth();
        else
            return 0;
    }
	
    public static int getScreenHeight() {
        if (BaseActivity.getScreenProperties() != null)
            return BaseActivity.getScreenProperties().getHeight();
        else
            return 0;
    }

    public static float getScreenPpiX() {
        if (BaseActivity.getScreenProperties() != null)
            return BaseActivity.getScreenProperties().getPpiX();
        else
            return 0;
    }
    
    public static float getScreenPpiY() {
        if (BaseActivity.getScreenProperties() != null)
            return BaseActivity.getScreenProperties().getPpiY();
        else
            return 0;
    }

    public static int getScreenOrientation() {
        if (BaseActivity.getScreenProperties() != null)
            return BaseActivity.getScreenProperties().getOrientation();
        else
            return Configuration.ORIENTATION_UNDEFINED;
    }

    public static Object getProperty(String name) {
		try {
			if (name.equalsIgnoreCase("platform"))
				return "ANDROID";
			else if (name.equalsIgnoreCase("locale"))
				return getCurrentLocale();
			else if (name.equalsIgnoreCase("country"))
				return getCurrentCountry();
			else if (name.equalsIgnoreCase("screen_width"))
				return Integer.valueOf(getScreenWidth());
			else if (name.equalsIgnoreCase("screen_height"))
				return Integer.valueOf(getScreenHeight());
			else if (name.equalsIgnoreCase("screen_orientation")) {
			    int orientation = getScreenOrientation();
				if ((orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
				 || (orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE))
					return "landscape";
				else
					return "portrait";
			}
			else if (name.equalsIgnoreCase("has_network"))
				return Boolean.valueOf(hasNetwork());
			else if (name.equalsIgnoreCase("has_wifi_network"))
				return Boolean.valueOf(hasWiFiNetwork());
			else if (name.equalsIgnoreCase("has_cell_network"))
				return Boolean.valueOf(hasCellNetwork());
			else if (name.equalsIgnoreCase("ppi_x"))
				return Float.valueOf(getScreenPpiX());
			else if (name.equalsIgnoreCase("ppi_y"))
				return Float.valueOf(getScreenPpiY());
			else if (name.equalsIgnoreCase("phone_number")) {
                Context context = ContextFactory.getContext();
                String number = "";
                if (context != null) {
                    TelephonyManager manager = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
                    number = manager.getLine1Number();
                    Logger.I(TAG, "Phone number: " + number + "<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");
                }
				return number;
			}
			else if (name.equalsIgnoreCase("device_owner_name")) {
				return AndroidFunctionalityManager.getAndroidFunctionality().AccessOwnerInfo_getUsername(getContext());
			}
			else if (name.equalsIgnoreCase("device_owner_email")) {
				return AndroidFunctionalityManager.getAndroidFunctionality().AccessOwnerInfo_getEmail(getContext());
			}
			else if (name.equalsIgnoreCase("device_name")) {
				return Build.MANUFACTURER + " " + Build.DEVICE;
			}
			else if (name.equalsIgnoreCase("is_emulator")) {
			    String strDevice = Build.DEVICE;
				return Boolean.valueOf(strDevice != null && strDevice.equalsIgnoreCase("generic"));
			}
			else if (name.equalsIgnoreCase("os_version")) {
				return Build.VERSION.RELEASE;
			}
			else if (name.equalsIgnoreCase("has_calendar")) {
				return Boolean.valueOf(EventStore.hasCalendar());
			}
			else if (name.equalsIgnoreCase("phone_id")) {
                RhodesService service = RhodesService.getInstance();
                if (service != null) {
                    PhoneId phoneId = service.getPhoneId();
                    return phoneId.toString();
                } else {
                    return "";
                }
            }
            else if (name.equalsIgnoreCase("webview_framework")) {
                return RhodesActivity.safeGetInstance().getMainView().getWebView(-1).getEngineId();
            }
            else if (name.equalsIgnoreCase("is_motorola_device")) {
                return isMotorolaDevice();
            }
            else if (name.equalsIgnoreCase("oem_info")) {
                return Build.PRODUCT;
            }
            else if (name.equalsIgnoreCase("uuid")) {
                return fetchUUID();
            }
            else if (name.equalsIgnoreCase("has_camera")) {
                return Boolean.TRUE;
            }
            else {
                return RhoExtManager.getImplementationInstance().getProperty(name);
            }
        }
		catch (Exception e) {
			Logger.E(TAG, "Can't get property \"" + name + "\": " + e);
		}
		
		return null;
	}
    
    public static Boolean isMotorolaDevice() {
        Boolean res = false;
        try
        {
            Class<?> commonClass = Class.forName("com.motorolasolutions.rhoelements.Common");
            Method isEmdkDeviceMethod = commonClass.getDeclaredMethod("isEmdkDevice");
            res = (Boolean)isEmdkDeviceMethod.invoke(null);
        } 
        catch (Throwable e) { }
        return Boolean.valueOf(Capabilities.MOTOROLA_ENABLED && res);
    }
	
	public static String getTimezoneStr() {
		Calendar cal = Calendar.getInstance();
		TimeZone tz = cal.getTimeZone();
		return tz.getDisplayName();
	}
	
	public static void runApplication(String appName, Object params) {
		try {
			Context ctx = RhodesService.getContext();
			PackageManager mgr = ctx.getPackageManager();
			PackageInfo info = mgr.getPackageInfo(appName, PackageManager.GET_ACTIVITIES);
			if (info.activities.length == 0) {
				Logger.E(TAG, "No activities found for application " + appName);
				return;
			}
			ActivityInfo ainfo = info.activities[0];
			String className = ainfo.name;
			if (className.startsWith("."))
				className = ainfo.packageName + className;

			Intent intent = new Intent();
			intent.setClassName(appName, className);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			if (params != null) {
				Bundle startParams = new Bundle();
				if (params instanceof String) {
					if (((String)params).length() != 0) {
						String[] paramStrings = ((String)params).split("&");
						for(int i = 0; i < paramStrings.length; ++i) {
							String key = paramStrings[i];
							String value = "";
							int splitIdx = key.indexOf('=');
							if (splitIdx != -1) {
								value = key.substring(splitIdx + 1); 
								key = key.substring(0, splitIdx);
							}
							startParams.putString(key, value);
						}
					}
				}
				else
					throw new IllegalArgumentException("Unknown type of incoming parameter");

				intent.putExtras(startParams);
			}
			ctx.startActivity(intent);
		}
		catch (Exception e) {
			Logger.E(TAG, "Can't run application " + appName + ": " + e.getMessage());
		}
	}
	
	public static boolean isAppInstalled(String appName) {
		try {
			RhodesService.getContext().getPackageManager().getPackageInfo(appName, 0);
			return true;
		}
		catch (NameNotFoundException ne) {
			return false;
		}
		catch (Exception e) {
			Logger.E(TAG, "Can't check is app " + appName + " installed: " + e.getMessage());
			return false;
		}
	}
	
	private void updateDownloadNotification(String url, int totalBytes, int currentBytes) {
		Context context = RhodesActivity.getContext();
		
		Notification n = new Notification();
		n.icon = android.R.drawable.stat_sys_download;
		n.flags |= Notification.FLAG_ONGOING_EVENT;
		
		RemoteViews expandedView = new RemoteViews(context.getPackageName(),
				R.layout.status_bar_ongoing_event_progress_bar);
		
		StringBuilder newUrl = new StringBuilder();
		if (url.length() < 17)
			newUrl.append(url);
		else {
			newUrl.append(url.substring(0, 7));
			newUrl.append("...");
			newUrl.append(url.substring(url.length() - 7, url.length()));
		}
		expandedView.setTextViewText(R.id.title, newUrl.toString());
		
		StringBuffer downloadingText = new StringBuffer();
		if (totalBytes > 0) {
			long progress = currentBytes*100/totalBytes;
			downloadingText.append(progress);
			downloadingText.append('%');
		}
		expandedView.setTextViewText(R.id.progress_text, downloadingText.toString());
		expandedView.setProgressBar(R.id.progress_bar,
				totalBytes < 0 ? 100 : totalBytes,
				currentBytes,
				totalBytes < 0);
		n.contentView = expandedView;
		
		Intent intent = new Intent(ACTION_ASK_CANCEL_DOWNLOAD);
		n.contentIntent = PendingIntent.getBroadcast(context, 0, intent, 0);
		intent = new Intent(ACTION_CANCEL_DOWNLOAD);
		n.deleteIntent = PendingIntent.getBroadcast(context, 0, intent, 0);

		mNM.notify(DOWNLOAD_PACKAGE_ID, n);
	}
	
	private File downloadPackage(String url) throws IOException {
		final Context ctx = RhodesActivity.getContext();
		
		final Thread thisThread = Thread.currentThread();
		
		final Runnable cancelAction = new Runnable() {
			public void run() {
				thisThread.interrupt();
			}
		};
		
		BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				String action = intent.getAction();
				if (action.equals(ACTION_ASK_CANCEL_DOWNLOAD)) {
					AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
					builder.setMessage("Cancel download?");
					AlertDialog dialog = builder.create();
					dialog.setButton(AlertDialog.BUTTON_POSITIVE, ctx.getText(android.R.string.yes),
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog, int which) {
									cancelAction.run();
								}
					});
					dialog.setButton(AlertDialog.BUTTON_NEGATIVE, ctx.getText(android.R.string.no),
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog, int which) {
									// Nothing
								}
							});
					dialog.show();
				}
				else if (action.equals(ACTION_CANCEL_DOWNLOAD)) {
					cancelAction.run();
				}
			}
		};
		IntentFilter filter = new IntentFilter();
		filter.addAction(ACTION_ASK_CANCEL_DOWNLOAD);
		filter.addAction(ACTION_CANCEL_DOWNLOAD);
		ctx.registerReceiver(downloadReceiver, filter);
		
		File tmpFile = null;
		InputStream is = null;
		OutputStream os = null;
		try {
			updateDownloadNotification(url, -1, 0);
			
			/*
			List<File> folders = new ArrayList<File>();
			folders.add(Environment.getDownloadCacheDirectory());
			folders.add(Environment.getDataDirectory());
			folders.add(ctx.getCacheDir());
			folders.add(ctx.getFilesDir());
			try {
				folders.add(new File(ctx.getPackageManager().getApplicationInfo(ctx.getPackageName(), 0).dataDir));
			} catch (NameNotFoundException e1) {
				// Ignore
			}
			folders.add(Environment.getExternalStorageDirectory());
			
			for (File folder : folders) {
				File tmpRootFolder = new File(folder, "rhodownload");
				File tmpFolder = new File(tmpRootFolder, ctx.getPackageName());
				if (tmpFolder.exists())
					deleteFilesInFolder(tmpFolder.getAbsolutePath());
				else
					tmpFolder.mkdirs();
				
				File of = new File(tmpFolder, UUID.randomUUID().toString() + ".apk");
				Logger.D(TAG, "Check path " + of.getAbsolutePath() + "...");
				try {
					os = new FileOutputStream(of);
				}
				catch (FileNotFoundException e) {
					Logger.D(TAG, "Can't open file " + of.getAbsolutePath() + ", check next path");
					continue;
				}
				Logger.D(TAG, "File " + of.getAbsolutePath() + " succesfully opened for write, start download app");
				
				tmpFile = of;
				break;
			}
			*/
			
			tmpFile = ctx.getFileStreamPath(UUID.randomUUID().toString() + ".apk");
			os = ctx.openFileOutput(tmpFile.getName(), Context.MODE_WORLD_READABLE);
			
			Logger.D(TAG, "Download " + url + " to " + tmpFile.getAbsolutePath() + "...");
			
			URL u = new URL(url);
			URLConnection conn = u.openConnection();
			int totalBytes = -1;
			if (conn instanceof HttpURLConnection) {
				HttpURLConnection httpConn = (HttpURLConnection)conn;
				totalBytes = httpConn.getContentLength();
			}
			is = conn.getInputStream();
			
			int downloaded = 0;
			updateDownloadNotification(url, totalBytes, downloaded);
			
			long prevProgress = 0;
			byte[] buf = new byte[65536];
			for (;;) {
				if (thisThread.isInterrupted()) {
					tmpFile.delete();
					Logger.D(TAG, "Download of " + url + " was canceled");
					return null;
				}
				int nread = is.read(buf);
				if (nread == -1)
					break;
				
				//Logger.D(TAG, "Downloading " + url + ": got " + nread + " bytes...");
				os.write(buf, 0, nread);
				
				downloaded += nread;
				if (totalBytes > 0) {
					// Update progress view only if current progress is greater than
					// previous by more than 10%. Otherwise, if update it very frequently,
					// user will no have chance to click on notification view and cancel if need
					long progress = downloaded*10/totalBytes;
					if (progress > prevProgress) {
						updateDownloadNotification(url, totalBytes, downloaded);
						prevProgress = progress;
					}
				}
			}
			
			Logger.D(TAG, "File stored to " + tmpFile.getAbsolutePath());
			
			return tmpFile;
		}
		catch (IOException e) {
			if (tmpFile != null)
				tmpFile.delete();
			throw e;
		}
		finally {
			try {
				if (is != null)
					is.close();
			} catch (IOException e) {}
			try {
				if (os != null)
					os.close();
			} catch (IOException e) {}
			
			mNM.cancel(DOWNLOAD_PACKAGE_ID);
			ctx.unregisterReceiver(downloadReceiver);
		}
	}
	
	public static void installApplication(final String url) {
		Thread bgThread = new Thread(new Runnable() {
			public void run() {
				try {
					final RhodesService r = RhodesService.getInstance();
					final File tmpFile = r.downloadPackage(url);
					if (tmpFile != null) {
						PerformOnUiThread.exec(new Runnable() {
							public void run() {
								try {
									Logger.D(TAG, "Install package " + tmpFile.getAbsolutePath());
									Uri uri = Uri.fromFile(tmpFile);
									Intent intent = new Intent(Intent.ACTION_VIEW);
									intent.setDataAndType(uri, "application/vnd.android.package-archive");
									r.startActivity(intent);
								}
								catch (Exception e) {
									Log.e(TAG, "Can't install file from " + tmpFile.getAbsolutePath(), e);
									Logger.E(TAG, "Can't install file from " + tmpFile.getAbsolutePath() + ": " + e.getMessage());
								}
							}
						});
					}
				}
				catch (IOException e) {
					Log.e(TAG, "Can't download package from " + url, e);
					Logger.E(TAG, "Can't download package from " + url + ": " + e.getMessage());
				}
			}
		});
		bgThread.setPriority(Thread.MIN_PRIORITY);
		bgThread.start();
	}
	
	public static void uninstallApplication(String appName) {
		try {
			Uri packageUri = Uri.parse("package:" + appName);
			Intent intent = new Intent(Intent.ACTION_DELETE, packageUri);
			RhodesService.getContext().startActivity(intent);
		}
		catch (Exception e) {
			Logger.E(TAG, "Can't uninstall application " + appName + ": " + e.getMessage());
		}
	}

    /** Opens remote or local URL
     * @throws URISyntaxException, ActivityNotFoundException */
    public static void openExternalUrl(String url) throws URISyntaxException, ActivityNotFoundException
    {
//        try
//        {
            if(url.charAt(0) == '/')
                url = "file://" + RhoFileApi.absolutePath(url);

            //FIXME: Use common URI handling
            Context ctx = RhodesService.getContext();
            LocalFileHandler fileHandler = new LocalFileHandler(ctx);
            if(!fileHandler.handle(url))
            {
                Logger.D(TAG, "Handling URI: " + url);

                Intent intent = Intent.parseUri(url, 0);
                ctx.startActivity(Intent.createChooser(intent, "Open in..."));
            }
//        }
//        catch (Exception e) {
//            Logger.E(TAG, "Can't open url :'" + url + "': " + e.getMessage());
//        }
    }

	public native void setPushRegistrationId(String type, String id);

    private native boolean callPushCallback(String type, String json);

	private void handlePushMessage(String pushType, Bundle extras) {
		Logger.D(TAG, "Handle PUSH message");
		
		if (extras == null) {
			Logger.W(TAG, "Empty PUSH message received");
			return;
		}

        String phoneId = extras.getString("phone_id");
        if (phoneId != null && phoneId.length() > 0 &&
                !phoneId.equals(this.getPhoneId().toString())) {
            Logger.W(TAG, "Push message for another phone_id: " + phoneId);
            Logger.W(TAG, "Current phone_id: " + this.getPhoneId().toString());
            return;
        }

        String json = new JSONGenerator(extras).toString();

        Logger.D(TAG, "Received PUSH message: " + json);
        if (callPushCallback(pushType, json)) {
            Logger.T(TAG, "Push message completely handled in callback");
            return;
        }

        final String alert = extras.getString("alert");

        boolean statusNotification = false;
        if (Push.PUSH_NOTIFICATIONS.equals(NOTIFICATION_ALWAYS))
            statusNotification = true;
        else if (Push.PUSH_NOTIFICATIONS.equals(NOTIFICATION_BACKGROUND))
            statusNotification = !RhodesApplication.canHandleNow(RhodesApplication.AppState.AppActivated);
        
        if (statusNotification) {
            Intent intent = new Intent(getContext(), RhodesActivity.class);
            StatusNotification.simpleNotification(TAG, 0, getContext(), intent, getBuildConfig("name"), alert);
        }

		if (alert != null) {
			Logger.D(TAG, "PUSH: Alert: " + alert);
            Alert.showPopup(alert);
		}
		final String sound = extras.getString("sound");
		if (sound != null) {
			Logger.D(TAG, "PUSH: Sound file name: " + sound);
            Alert.playFile("/public/alerts/" + sound, null);
		}
		String vibrate = extras.getString("vibrate");
		if (vibrate != null) {
			Logger.D(TAG, "PUSH: Vibrate: " + vibrate);
			int duration;
			try {
				duration = Integer.parseInt(vibrate);
			}
			catch (NumberFormatException e) {
				duration = 5;
			}
			final int arg_duration = duration;
			Logger.D(TAG, "Vibrate " + duration + " seconds");
            Alert.vibrate(arg_duration);
		}
		
		String syncSources = extras.getString("do_sync");
		if ((syncSources != null) && (syncSources.length() > 0)) {
			Logger.D(TAG, "PUSH: Sync:");
			boolean syncAll = false;
			for (String source : syncSources.split(",")) {
				Logger.D(TAG, "url = " + source);
				if (source.equalsIgnoreCase("all")) {
					syncAll = true;
				    break;
				} else {
				    final String arg_source = source.trim();
                    doSyncSource(arg_source);
				}
			}
			
			if (syncAll) {
                doSyncAllSources(true); 
			}
		}
	}

    private void handlePushMessage(String type, String json) {
        Logger.T(TAG, "Handle push message");
        
        Logger.D(TAG, "Push message JSON: " + json);
        
        if (callPushCallback(type, json)) {
            Logger.T(TAG, "Push message completely handled in callback");
            return;
        }
        if (json != null) {
            JSONObject jsonObject;
            try {
                jsonObject = (JSONObject)new JSONTokener(json).nextValue();
    
                final String alert = jsonObject.optString("alert");
    
                boolean statusNotification = false;
                if (Push.PUSH_NOTIFICATIONS.equals(NOTIFICATION_ALWAYS)) {
                    Logger.D(TAG, "Show push notification always");
                    statusNotification = true;
                } else if (Push.PUSH_NOTIFICATIONS.equals(NOTIFICATION_BACKGROUND)) {
                    Logger.D(TAG, "Show push notification from background");
                    statusNotification = !RhodesApplication.canHandleNow(RhodesApplication.AppState.AppActivated);
                }
    
                if (statusNotification) {
                    Logger.D(TAG, "Showing status push notification");
                    Intent intent = new Intent(getContext(), RhodesActivity.class);
                    StatusNotification.simpleNotification(TAG, 0, getContext(), intent, getBuildConfig("name"), alert);
                }
    
                if (alert.length() > 0) {
                    Logger.D(TAG, "PUSH: Alert: " + alert);
                    Alert.showPopup(alert);
                }
                final String sound = jsonObject.optString("sound");
                if (sound.length() > 0) {
                    Logger.D(TAG, "PUSH: Sound file name: " + sound);
                    Alert.playFile("/public/alerts/" + sound, null);
                }
                int vibrate = jsonObject.optInt("vibrate");
                if (vibrate > 0) {
                    Logger.D(TAG, "PUSH: Vibrate: " + vibrate);
                    Logger.D(TAG, "Vibrate " + vibrate + " seconds");
                    Alert.vibrate(vibrate);
                }
                JSONArray syncSources = jsonObject.optJSONArray("do_sync");
                if ((syncSources != null) && (syncSources.length() > 0)) {
                    Logger.D(TAG, "PUSH: Sync:");
                    boolean syncAll = false;
                    for (int i = 0; i < syncSources.length(); ++i) {
                        String source = syncSources.optString(i);
                        Logger.D(TAG, "source = " + source);
                        if (source.equalsIgnoreCase("all")) {
                            syncAll = true;
                            break;
                        } else {
                            doSyncSource(source);
                        }
                    }
                    
                    if (syncAll) {
                        doSyncAllSources(true); 
                    }
                }
            } catch (JSONException e) {
                Logger.E(TAG, "Error parsing JSON payload in push message: " + e.getMessage());
            }
        }
    }

	private void restartGeoLocationIfNeeded() {
		if (mNeedGeoLocationRestart) {
			//GeoLocation.restart();
			mNeedGeoLocationRestart = false;
		}
	}
	
	private void stopGeoLocation() {
		mNeedGeoLocationRestart = GeoLocation.isAvailable();
		//GeoLocation.stop();
	}
	
	private void restoreWakeLockIfNeeded() {
		 wakeLock.acquire(false);
	}
	
	private void stopWakeLock() {
		Logger.I(TAG, "activityStopped() temporary release wakeLock object");
		wakeLock.release();
	}
	
	public static int rho_sys_set_sleeping(int enable) {
		Logger.I(TAG, "rho_sys_set_sleeping("+enable+")");
		RhodesService rs = RhodesService.getInstance();
        int wasEnabled = rs.wakeLock.isHeld() ? 1 : 0;
		if(rs != null)
		{
		
	        if (enable != 0) {
	            // disable lock device
				wasEnabled = rs.wakeLock.reset() ? 1 : 0;
			}
	        else {
	            // lock device from sleep
			    PerformOnUiThread.exec(new Runnable() {
			        public void run() {
			            RhodesService rs = RhodesService.getInstance();
			            if(rs != null) rs.wakeLock.acquire(true);
			            else Logger.E(TAG, "rho_sys_set_sleeping() - No RhodesService has initialized !!!"); }
			    });
			}
		}
		return wasEnabled;
	}
	
	private static String fetchUUID()
	{
		String uuid = "";
		// Get serial number from UUID file built into image
		try
		{
		    if (isMotorolaDevice())
		    {
				BufferedReader reader = new BufferedReader(new FileReader("/sys/hardware_id/uuid"));
				uuid = reader.readLine();
				Logger.E(TAG, "uuid: " + uuid);
		    }
		    else
		    {
				uuid = computeUUID();
				Logger.E(TAG, "uuid: " + uuid);
		    }
		}
		catch (Exception e)
		{
		    Logger.E(TAG, "Cannot determine device UUID");
		}
		finally
		{
			return uuid;
		}
	}
	
	/**
	 * This method is used only for non-Motorola devices as the UUID needs to be computed by other parameters.
	 * @return 32-byte long UUID
	 */
	private static String computeUUID()
	{
	    RhodesService srv = RhodesService.getInstance();
        if (srv == null)
			throw new IllegalStateException("No rhodes service instance at this moment");
		String res="";
	    WifiManager wifi = (WifiManager) srv.getSystemService(Context.WIFI_SERVICE);
	    // Get WiFi status
	    WifiInfo wifiInfo = wifi.getConnectionInfo();
	    String macAddress = wifiInfo.getMacAddress();
	    macAddress = macAddress.replaceAll(":", "");
	    UUID localUuid = UUID.nameUUIDFromBytes(macAddress.getBytes());
	    res = localUuid.toString().replaceAll("-", "");
	    return res.toUpperCase();
	}
	
	void handleAppActivation() {
		if (DEBUG)
			Log.d(TAG, "handle app activation");
		restartGeoLocationIfNeeded();
		restoreWakeLockIfNeeded();
		callActivationCallback(true);
        RhodesApplication.stateChanged(RhodesApplication.AppState.AppActivated);
	}
	
	void handleAppDeactivation() {
		if (DEBUG)
			Log.d(TAG, "handle app deactivation");
        RhodesApplication.stateChanged(RhodesApplication.AppState.AppDeactivated);
		stopWakeLock();
		stopGeoLocation();
		callActivationCallback(false);
	}

	@Override
	public void startActivity(Intent intent) {

        RhodesActivity ra = RhodesActivity.getInstance();
        if(intent.getComponent() != null && intent.getComponent().compareTo(new ComponentName(this, RhodesActivity.class.getName())) == 0) {
            Logger.T(TAG, "Start or bring main activity: " + RhodesActivity.class.getName() + ".");
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (ra == null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                super.startActivity(intent);
                return;
            }
        }

        if (ra != null) {
            Logger.T(TAG, "Starting new activity on top.");
            if (DEBUG) {
                Bundle extras = intent.getExtras();
                if (extras != null) {
                    for (String key: extras.keySet()) {
                        Object val = extras.get(key);
                        if(val != null)
                            Log.d(TAG, key + ": " + val.toString());
                        else
                            Log.d(TAG, key + ": <empty>");
                    }
                }
            }
            ra.startActivity(intent);
        } else {
            throw new IllegalStateException("Trying to start activity, but there is no main activity instance (we are in background, no UI active)");
        }
    }

    public static void setFullscreen(boolean enable) {
        BaseActivity.setFullScreenMode(enable);
    }

    public static boolean getFullscreen() {
        return BaseActivity.getFullScreenMode();
    }

    public static void setScreenAutoRotate(boolean enable) {
        BaseActivity.setScreenAutoRotateMode(enable);
    }

    public static boolean getScreenAutoRotate() {
        return BaseActivity.getScreenAutoRotateMode();
    }

    public static Context getContext() {
		RhodesService r = RhodesService.getInstance();
		if (r == null)
			throw new IllegalStateException("No rhodes service instance at this moment");
		return r;
	}
	
	public static boolean isJQTouch_mode() {
		return RhoConf.getBool("jqtouch_mode");
	}

    public static void bringToFront() {
        if (RhodesApplication.isRhodesActivityStarted()) {
            Logger.T(TAG, "Main activity is already at front, do nothing");
            return;
        }

        RhodesService srv = RhodesService.getInstance();
        if (srv == null)
            throw new IllegalStateException("No rhodes service instance at this moment");

        Logger.T(TAG, "Bring main activity to front");

        Intent intent = new Intent(srv, RhodesActivity.class);
        srv.startActivity(intent);
    }

    public static String getNativeMenu() {
        List<Object> menuItems = RhodesActivity.safeGetInstance().getMenu().getMenuDescription();
        String menuJson = new JSONGenerator(menuItems).toString();

        Logger.T(TAG, "Menu: " + menuJson);

        return menuJson;
    }

    public static void setNativeMenu(List<String> jsonMenu) {
        List<Map<String, String>> nativeMenu = new ArrayList<Map<String, String>>();

        Iterator<String> iter = jsonMenu.iterator();
        while(iter.hasNext()) {
            try {
                String jsonItem = iter.next();
                Map<String, String> menuItem = new HashMap<String, String>();
                JSONObject json = new JSONObject(jsonItem);
                Iterator<String> itemIter = json.keys();
                while(itemIter.hasNext()) {
                    Logger.T(TAG, "New menu item");
                    String itemKey = itemIter.next();
                    String itemDescr = json.getString(itemKey);

                    Logger.T(TAG, itemKey + "->" + itemDescr);

                    menuItem.put(itemKey, itemDescr);
                }
                nativeMenu.add(menuItem);
            } catch (JSONException e) {
                Logger.E(TAG, e);
            }
        }
        RhodesActivity.safeGetInstance().getMenu().setMenu(nativeMenu);
    }
}
