package com.lemonrsh.phoneshare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.List;

/** Disconnect button of the "connected to this phone" notification: ends every incoming session. */
public class IncomingDisconnectReceiver extends BroadcastReceiver {
    static final String ACTION_DISCONNECT = "com.lemonrsh.phoneshare.action.DISCONNECT_INCOMING";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_DISCONNECT.equals(intent.getAction())) {
            return;
        }
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        final Handler handler = new Handler(Looper.getMainLooper());
        Thread thread = new Thread(() -> {
            int ended = 0;
            for (IncomingSessions.Session session : IncomingSessions.scanNow()) {
                if (IncomingSessions.disconnectNow(session)) {
                    ++ended;
                }
            }
            DiagnosticLog.i("incoming", "Disconnect from the notification: " + ended + " session(s)");
            final List<IncomingSessions.Session> left = IncomingSessions.scanNow();
            final int count = ended;
            handler.post(() -> {
                if (!IncomingSessionService.refresh(null)) {
                    IncomingNotifier.show(app, left);
                }
                Toast.makeText(app, count > 0 ? app.getString(R.string.incoming_disconnected_all)
                        : app.getString(R.string.incoming_nothing_connected), Toast.LENGTH_SHORT).show();
                pending.finish();
            });
        }, "incoming-disconnect");
        thread.setDaemon(true);
        thread.start();
    }
}
