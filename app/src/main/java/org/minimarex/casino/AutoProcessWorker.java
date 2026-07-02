package org.minimarex.casino;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.PeriodicWorkRequest;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Periodic fallback: if the OS kills {@link CasinoService}, WorkManager re-launches it so bets keep
 * auto-revealing / resolving. The worker itself does no node work (the IPC model is async); it just
 * ensures the foreground service is alive.
 */
public class AutoProcessWorker extends Worker {

    private static final String UNIQUE = "casino_auto_process";

    public AutoProcessWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            ContextCompat.startForegroundService(getApplicationContext(),
                    new Intent(getApplicationContext(), CasinoService.class));
        } catch (Exception ignored) {}
        return Result.success();
    }

    /** Schedule the ~15-minute fallback (WorkManager's minimum period). */
    public static void schedule(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                AutoProcessWorker.class, 15, TimeUnit.MINUTES).build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req);
    }
}
