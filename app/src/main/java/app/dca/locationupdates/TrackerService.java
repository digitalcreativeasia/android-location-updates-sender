package app.dca.locationupdates;

import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.gcm.GcmNetworkManager;
import com.google.android.gms.gcm.OneoffTask;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import java.io.File;
import java.io.FileWriter;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class TrackerService extends Service implements LocationListener {

    private static final String TAG = TrackerService.class.getSimpleName();
    public static final String STATUS_INTENT = "status";

    private static final int NOTIFICATION_ID = 1;
    private static final int FOREGROUND_SERVICE_ID = 1;

    private static final String WAKELOCK_TAG = "Tracking-WakeLock";

    private GoogleApiClient mGoogleApiClient;

    private static final long DEFAULT_INTERVAL = 10000;
    private static final long FASTEST_iNTERVAL = 5000;
    private static final long LOCATION_MIN_DISTANCE_CHANGED = 20;
    private static final double SLEEP_HOURS_DURATION = 4;
    private static final int SLEEP_HOUR_OF_DAY = 1;
    private static final int MAX_STATUSES = 10;

    public final static String PREF_NAME = "my.pref";


    private LinkedList<Map<String, Object>> mTransportStatuses = new LinkedList<>();
    private NotificationManager mNotificationManager;
    private NotificationCompat.Builder mNotificationBuilder;
    private PowerManager.WakeLock mWakelock;

    private SharedPreferences mPrefs;


    public TrackerService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        buildNotification();
        setStatusMessage(R.string.app_name);
        mPrefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        loadPreviousStatuses();

    }


    private void loadPreviousStatuses() {

        boolean isTracked = mPrefs.getBoolean("isTracked", false);
        if(!isTracked)
            startLocationTracking();

    }


    @Override
    public void onDestroy() {
        setStatusMessage(R.string.not_tracking);
        mNotificationManager.cancel(NOTIFICATION_ID);
        if (mGoogleApiClient != null) {
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient,
                    TrackerService.this);
        }
        if (mWakelock != null) {
            mWakelock.release();
        }
        super.onDestroy();
    }



    private GoogleApiClient.ConnectionCallbacks mLocationRequestCallback = new GoogleApiClient
            .ConnectionCallbacks() {

        @SuppressLint({"MissingPermission", "InvalidWakeLockTag"})
        @Override
        public void onConnected(Bundle bundle) {
            LocationRequest request = new LocationRequest();
            request.setInterval(DEFAULT_INTERVAL);
            request.setFastestInterval(FASTEST_iNTERVAL);
            request.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
            LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient,
                    request, TrackerService.this);
            setStatusMessage(R.string.tracking);
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            mWakelock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
            mWakelock.acquire();
        }

        @Override
        public void onConnectionSuspended(int reason) {
            // TODO: Handle gracefully
        }
    };

    private void startLocationTracking() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(mLocationRequestCallback)
                .addApi(LocationServices.API)
                .build();
        mGoogleApiClient.connect();
    }


    private boolean locationIsAtStatus(Location location, int statusIndex) {
        if (mTransportStatuses.size() <= statusIndex) {
            return false;
        }
        Map<String, Object> status = mTransportStatuses.get(statusIndex);
        Location locationForStatus = new Location("");
        locationForStatus.setLatitude((double) status.get("lat"));
        locationForStatus.setLongitude((double) status.get("lng"));
        float distance = location.distanceTo(locationForStatus);
        Log.d(TAG, String.format("Distance from status %s is %sm", statusIndex, distance));
        return distance < LOCATION_MIN_DISTANCE_CHANGED;
    }

    private float getBatteryLevel() {
        Intent batteryStatus = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int batteryLevel = -1;
        int batteryScale = 1;
        if (batteryStatus != null) {
            batteryLevel = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, batteryLevel);
            batteryScale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, batteryScale);
        }
        return batteryLevel / (float) batteryScale * 100;
    }

    private void logStatusToStorage(Map<String, Object> transportStatus) {
        try {
            File path = new File(Environment.getExternalStoragePublicDirectory(""),
                    "transport-tracker-log.txt");
            if (!path.exists()) {
                path.createNewFile();
            }
            FileWriter logFile = new FileWriter(path.getAbsolutePath(), true);
            logFile.append(transportStatus.toString() + "\n");
            logFile.close();
        } catch (Exception e) {
            Log.e(TAG, "Log file error", e);
        }
    }

    private void shutdownAndScheduleStartup(int when) {
        Log.i(TAG, "overnight shutdown, seconds to startup: " + when);
        com.google.android.gms.gcm.Task task = new OneoffTask.Builder()
                .setService(TrackerTaskService.class)
                .setExecutionWindow(when, when + 60)
                .setUpdateCurrent(true)
                .setTag(TrackerTaskService.TAG)
                .setRequiredNetwork(com.google.android.gms.gcm.Task.NETWORK_STATE_ANY)
                .setRequiresCharging(false)
                .build();
        GcmNetworkManager.getInstance(this).schedule(task);
        stopSelf();
    }


    @Override
    public void onLocationChanged(Location location) {

        long hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int startupSeconds = (int) (SLEEP_HOURS_DURATION * 3600);
        if (hour == SLEEP_HOUR_OF_DAY) {
            shutdownAndScheduleStartup(startupSeconds);
            return;
        }

        Map<String, Object> transportStatus = new HashMap<>();
        transportStatus.put("lat", location.getLatitude());
        transportStatus.put("lng", location.getLongitude());
        transportStatus.put("time", new Date().getTime());
        transportStatus.put("power", getBatteryLevel());

        if (locationIsAtStatus(location, 1) && locationIsAtStatus(location, 0)) {
            mTransportStatuses.set(0, transportStatus);
            // todo: push new data
        } else {
            while (mTransportStatuses.size() >= MAX_STATUSES) {
                mTransportStatuses.removeLast();
            }
            mTransportStatuses.addFirst(transportStatus);
            // todo: push new data
        }

        if (BuildConfig.DEBUG) {
            logStatusToStorage(transportStatus);
        }

        NetworkInfo info = ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE))
                .getActiveNetworkInfo();
        boolean connected = info != null && info.isConnectedOrConnecting();
        setStatusMessage(connected ? R.string.tracking : R.string.not_tracking);
    }

    private void buildNotification() {
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        PendingIntent resultPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        mNotificationBuilder = new NotificationCompat.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
                .setColor(getBaseContext().getResources().getColor(R.color.colorPrimary))
                .setContentTitle(getString(R.string.app_name))
                .setOngoing(true)
                .setContentIntent(resultPendingIntent);
        startForeground(FOREGROUND_SERVICE_ID, mNotificationBuilder.build());
    }

    /**
     * Sets the current status message (connecting/tracking/not tracking).
     */
    private void setStatusMessage(int stringId) {
        mNotificationBuilder.setContentText(getString(stringId));
        mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());

        // Also display the status message in the activity.
        Intent intent = new Intent(STATUS_INTENT);
        intent.putExtra(getString(R.string.status), stringId);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

}
