package org.las2mile.scrcpy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Stop button of the "scrcpy is running" notification: ends whatever session is running. */
public class StopReceiver extends BroadcastReceiver {
    static final String ACTION_STOP = "org.las2mile.scrcpy.action.STOP_SESSION";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_STOP.equals(intent.getAction())) {
            return;
        }
        DiagnosticLog.i("app", "Stop tapped in the notification");
        Runnable handler = RunningNotification.stopHandler();
        if (handler != null) {
            handler.run(); // full-screen session in MainActivity (receivers run on the main thread)
        }
        try {
            context.stopService(new Intent(context, FloatService.class));
        } catch (RuntimeException e) {
            Log.w("StopReceiver", "Could not stop the floating window", e);
        }
        SessionHandoff.Item pending = SessionHandoff.take();
        if (pending != null) {
            pending.client.stop();
        }
        SessionState.Status status = SessionState.getStatus();
        if (status == SessionState.Status.CONNECTED || status == SessionState.Status.CONNECTING) {
            SessionState.disconnected(null);
        }
        RunningNotification.cancel(context);
    }
}
