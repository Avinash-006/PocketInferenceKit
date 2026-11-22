# WorkManager reconstructs persisted workers from their class names.
-keep class io.github.avinash006.pocketinferencekit.models.ModelDownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
