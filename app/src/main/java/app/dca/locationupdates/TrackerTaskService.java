package app.dca.locationupdates;

import android.content.Intent;
import android.util.Log;

import com.google.android.gms.gcm.GcmNetworkManager;
import com.google.android.gms.gcm.GcmTaskService;
import com.google.android.gms.gcm.TaskParams;

public class TrackerTaskService extends GcmTaskService {

    public static final String TAG = TrackerTaskService.class.getSimpleName();

    @Override
    public int onRunTask(TaskParams taskParams) {
        Log.i(TAG, "onRunTask");
        //Todo: remove null
        Intent start = new Intent(this, MainActivity.class);
        start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(start);
        return GcmNetworkManager.RESULT_SUCCESS;
    }

}