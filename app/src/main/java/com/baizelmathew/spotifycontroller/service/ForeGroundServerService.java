/*
  @Author: Baizel Mathew
 */
package com.baizelmathew.spotifycontroller.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.widget.Toast;

import com.baizelmathew.spotifycontroller.R;
import com.baizelmathew.spotifycontroller.spotifywrapper.Player;
import com.baizelmathew.spotifycontroller.utils.OnFailSocketCallBack;
import com.baizelmathew.spotifycontroller.utils.OnEventCallback;
import com.baizelmathew.spotifycontroller.web.WebServer;
import com.google.gson.Gson;
import com.spotify.android.appremote.api.Connector;
import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.protocol.client.Subscription;
import com.spotify.protocol.types.PlayerState;
import com.spotify.protocol.types.Track;

import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;

import java.io.IOException;
import java.net.NoRouteToHostException;

/**
 * This class is responsible for managing the web server in the background.
 */
public class ForeGroundServerService extends Service {

    public static final String ACTION_STOP_FOREGROUND_SERVICE = "ACTION_STOP_FOREGROUND_SERVICE";
    public static final String ACTION_START_FOREGROUND_SERVICE = "ACTION_START_FOREGROUND_SERVICE";

    public static final String ACTION_SERVER_ADDRESS_BROADCAST = ForeGroundServerService.class.getName() + "AddressBroadcast";
    public static final String ACTION_TRACK_BROADCAST = ForeGroundServerService.class.getName() + "TrackBroadcast";
    public static final String ACTION_SERVICE_STOPPED = ForeGroundServerService.class.getName() + "Stopped";

    public static final String EXTRA_SERVER_ADDRESS = "extra_server_address";
    public static final String EXTRA_TRACK = "extra_track";

    private static final String NOTIFICATION_CHANNEL_ID = "dev.baizel.spot";
    private static final int ONGOING_NOTIFICATION_ID = 1234;
    private static final String ACTION_STOP_FOREGROUND_SERVICE_ID = "ACTION_STOP_FOREGROUND_SERVICE_ID";

    private WebServer webServer;
    private Player player;
    private ServiceBroadcastReceiver serviceBroadcastReceiver = new ServiceBroadcastReceiver();
    private OnFailSocketCallBack onFailSocketCallBack;

    public ForeGroundServerService() {
        //https://developer.android.com/guide/components/services
        onFailSocketCallBack = getOnFailSocketCallBack();
    }

    /**
     * Initialises the web server and registers the stop service receiver.
     * Also connects Spotify SDK to client.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter stopServiceFilter = new IntentFilter(ACTION_STOP_FOREGROUND_SERVICE);
        stopServiceFilter.addAction(ACTION_STOP_FOREGROUND_SERVICE);
        this.registerReceiver(serviceBroadcastReceiver, stopServiceFilter);

        setWebServer();
        initSpotifyPlayerConnection();
        startForegroundService();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        player.disconnect();
        webServer.stop();
        this.unregisterReceiver(serviceBroadcastReceiver);
        debugToast("Stopping Web Server");

    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    private void stopService() {
        Intent intent = new Intent(ACTION_SERVICE_STOPPED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        stopSelf();
    }

    private Notification buildNotification() {
        Intent snoozeIntent = new Intent(this, ServiceBroadcastReceiver.class);
        snoozeIntent.setAction(ACTION_STOP_FOREGROUND_SERVICE);
        snoozeIntent.putExtra(ACTION_STOP_FOREGROUND_SERVICE_ID, 0);
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(this, 0, snoozeIntent, 0);
        return new NotificationCompat
                .Builder(this, NOTIFICATION_CHANNEL_ID)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_tap_and_play_black_24dp)
                .setContentTitle("Server running in background")
                .setPriority(NotificationManager.IMPORTANCE_LOW)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(R.drawable.ic_stop_black_24dp, "Stop Server", stopPendingIntent)
                .build();
    }

    private Subscription.EventCallback<PlayerState> getPlayerStateEventCallback() {
        return new Subscription.EventCallback<PlayerState>() {
            @Override
            public void onEvent(PlayerState playerState) {
                handlePlayerStateOnEvent(playerState);
            }
        };
    }

    private Connector.ConnectionListener getConnectionListener() {
        return new Connector.ConnectionListener() {
            @Override
            public void onConnected(SpotifyAppRemote spotifyAppRemote) {
                handleConnectionListnerOnConnected();
            }

            @Override
            public void onFailure(Throwable throwable) {
                handleConnectionListenerOnFail(throwable);
            }
        };
    }

    private void handleConnectionListenerOnFail(Throwable throwable) {
        stopService();
        debugToast("Service Not Started " + throwable.getLocalizedMessage());
    }

    private void handlePlayerStateOnEvent(PlayerState playerState) {
        sendBroadcastAddress(webServer.getHttpAddress());
        sendBroadcastTrack(playerState.track);
        try {
            webServer.startServer();
        } catch (IOException | WebsocketNotConnectedException e) {
            e.printStackTrace();
            stopService();
        }
    }

    private void handleConnectionListnerOnConnected() {
        webServer.startListening();
        player.getPlayerState(new OnEventCallback() {
            @Override
            public void onEvent(PlayerState playerState) {
                sendBroadcastTrack(playerState.track);
            }
        });
    }

    private void startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startMyOwnForeground();
        else
            startForeground(ONGOING_NOTIFICATION_ID, buildNotification());
    }

    private void setWebServer() {
        try {
            webServer = WebServer.getInstance(onFailSocketCallBack);
        } catch (NoRouteToHostException e) {
            e.printStackTrace();
        }
    }

    private void initSpotifyPlayerConnection() {
        player = Player.getInstance();
        Subscription.EventCallback<PlayerState> playerStateEventCallback = getPlayerStateEventCallback();
        Connector.ConnectionListener connectionListener = getConnectionListener();
        player.connect(this, connectionListener, playerStateEventCallback);
    }

    private void debugToast(String s) {
        Toast toast = Toast.makeText(this, s, Toast.LENGTH_LONG);
        toast.show();
    }

    /**
     * Method used to broadcast the web server address to anything listening.
     * Used to retrieve the web server address by the main activity so it can be updated
     * and shown to the user when the server is launched.
     *
     * @param serverLink as a http link
     */
    private void sendBroadcastAddress(String serverLink) {
        if (serverLink != null) {
            Intent intent = new Intent(ACTION_SERVER_ADDRESS_BROADCAST);
            intent.putExtra(EXTRA_SERVER_ADDRESS, serverLink);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }
    }

    private void sendBroadcastTrack(Track track) {
        if (track != null) {
            Intent intent = new Intent(ACTION_TRACK_BROADCAST);
            intent.putExtra(EXTRA_TRACK, new Gson().toJson(track));
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }
    }

    private void startMyOwnForeground() {
        String channelName = "Web Server";

        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_NONE);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        assert manager != null;

        manager.createNotificationChannel(chan);
        Notification notification = buildNotification();
        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }

    private OnFailSocketCallBack getOnFailSocketCallBack() {
        return new OnFailSocketCallBack() {
            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                //TODO: Shows disconnected users
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                // stopService();
            }
        };
    }
}