package com.screenomics.services.upload;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;

import com.screenomics.Constants;
import com.screenomics.R;
import com.screenomics.util.InternetConnection;

import java.io.File;
import java.io.FileFilter;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;


public class UploadService extends Service {

    private final OkHttpClient client =
            new OkHttpClient.Builder().readTimeout(Constants.REQ_TIMEOUT, TimeUnit.SECONDS).build();
    public int numToUpload = 0;
    public int numUploaded = 0;
    public int numTotal = 0;
    public boolean uploading = false;
    public Status status = Status.IDLE;
    public String errorCode = "";
    public String lastActivityTime = "";
    public boolean continueWithoutWifi = false;

    private int numBatchesSending = 0;
    private int numBatchesToSend = 1;
    private final List<Batch> batches = new ArrayList<>();
    private final FileFilter onlyFilesBeforeStart = new FileFilter() {
        @Override
        public boolean accept(File file) {
            List<String> parts = Arrays.asList(file.getName().replace(".png", "").split("_"));
            // May 2nd fix: move 1 back in the list because of new descriptor component
            Integer[] dP =
                    parts.subList(parts.size() - 7, parts.size() - 1).stream().map(Integer::valueOf).toArray(Integer[]::new);
            LocalDateTime imageCreateTime = LocalDateTime.of(dP[0], dP[1], dP[2], dP[3], dP[4],
                    dP[5]);
            return imageCreateTime.isBefore(LocalDateTime.now());
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
    }

    private void sendNextBatch() {
        //making sure we have batches to send
        if (batches.isEmpty()) return;

        //making sure we arent sending more than the maximum allowed number of batches
        if (numBatchesSending >= numBatchesToSend) return;

        //checking user has wifi access
        if (!continueWithoutWifi && !InternetConnection.checkWiFiConnection(this))
            sendFailure("NOWIFI");

        //getting the first batch to send from our list of batches
        Batch batch = batches.remove(0);

        //incrementing our variable keeping track of how many batches we are sending
        numBatchesSending++;
        System.out.println("SENDING NEXT BATCH, numBatchesSending " + numBatchesSending + " out " +
                "of " + numBatchesToSend + " with " + batch.size() + " images");

        //creates a sender worker to upload the batches?
        new Sender().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, batch);
    }

    //if a batch was sent successfully update corresponding variables
    private void sendSuccessful(Batch batch) {
        //decrement number of batches we are currently sending
        numBatchesSending--;
        //update total number of files sent
        numUploaded += batch.size();
        //update the number of files we still need to send
        numToUpload -= batch.size();

        //checking if the system should allow for more batches to be sent
        if (numBatchesToSend < Constants.MAX_BATCHES_TO_SEND) numBatchesToSend++;

        //sending more batches, may be able to compare directly to the constant, will change later
        for (int i = 0; i < numBatchesToSend; i++) {
            sendNextBatch();
        }

        //terminating program if we have sent as many files as necessary
        if (numToUpload <= 0) {
            System.out.println("Sending Successful!");
            status = Status.SUCCESS;
            reset();
            stopForeground(true);
            stopSelf();
        }
    }

    //for when we fail to send a batch
    private void sendFailure(String code) {
        status = Status.FAILED;
        errorCode = code;
        setNotification("Failure in Uploading", "Error code: " + errorCode);
        reset();
    }

    //resetting all the important counters in the class so we can upload more files
    private void reset() {
        ZonedDateTime dateTime = ZonedDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
        lastActivityTime = dateTime.format(formatter);
        uploading = false;
        numBatchesToSend = 0;
        numTotal = 0;
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        //clearing current notifications
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.cancelAll();

        //creating notification to let user know we are uploading
        createNotificationChannel();
        Notification notification = setNotification("Uploading..", "Preparing..");

        // Notification ID cannot be 0.
        startForeground(5, notification);

        //checking if there is no intent to upload or we are already sending
        if (intent == null || status == Status.SENDING) {
            stopForeground(true);
            stopSelf();
            return super.onStartCommand(intent, flags, startId);
        }

        //getting path where files are stored as well as whether we should upload without wifi
        String dirPath = intent.getStringExtra("dirPath");
        continueWithoutWifi = intent.getBooleanExtra("continueWithoutWifi", false);

        //getting the directory of our files and grabbing all files that were captured before starting upload
        File dir = new File(dirPath);
        File[] files = dir.listFiles(onlyFilesBeforeStart);

        //if there are no files then stop the upload
        if (files == null || files.length == 0) {
            stopForeground(true);
            stopSelf();
            return super.onStartCommand(intent, flags, startId);
        }

        //creating a list of all our files to upload
        LinkedList<File> fileList = new LinkedList<>(Arrays.asList(files));

        //getting the number of files per batch and the number of files to send overall
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int batchSize = prefs.getInt("batchSize", Constants.BATCH_SIZE_DEFAULT);
        int maxToSend = prefs.getInt("maxSend", Constants.MAX_TO_SEND_DEFAULT);

        //resetting our counter for how many files we are attempting to upload
        numToUpload = 0;

        System.out.println("INITIAL FILELIST SIZE " + fileList.size() + "");

        //split our list of files into batches
        while (!fileList.isEmpty() && (maxToSend == 0 || numToUpload < maxToSend)) {
            //creating a list of files for a single batch
            List<File> nextBatch = new LinkedList<>();

            //iterating through our list of files adding them to our nextBatch list while
            //ensuring that we do not send to many in one batch and that the file list does not run out
            for (int i = 0; i < batchSize && (maxToSend == 0 || numToUpload < maxToSend) && !fileList.isEmpty(); i++) {
                numToUpload ++;
                nextBatch.add(fileList.remove());
            }

            //creating a new batch with our list of files and adding it to our array of batches
            Batch batch = new Batch(nextBatch, client);
            batches.add(batch);
        }

        //setting our counter variables for sending the first batch
        numTotal = numToUpload;
        numUploaded = 0;
        numBatchesToSend = 1;
        System.out.println("GOT " + batches.size() + " BATCHES WITH " + numToUpload + "IMAGES TO " +
                "UPLOAD");
        System.out.println("TOTAL OF " + fileList.size() + " IMAGES THO");

        //updating status so system knows we are sending
        status = Status.SENDING;
        // Send the first batch, on success will cause more batches to be sent
        //on failure will stop the system
        sendNextBatch();

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new LocalBinder();
    }

    //creating the notification to let the user know an upload is happening
    //lots of syntactic sugar I don't fully understand
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    "uploading-channel",
                    "Screenomics Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setSound(null, null);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(serviceChannel);
        }
    }

    //lots more syntactic sugar I don't fully understand, seems to just be a way
    //to set a notification to have a title and a message
    private Notification setNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, UploadService.class);

        int intentflags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            intentflags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        } else {
            intentflags = PendingIntent.FLAG_UPDATE_CURRENT;
        }

        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent, intentflags);
        Notification notification =
                new Notification.Builder(this, "uploading-channel")
                        .setContentTitle(title)
                        .setContentText(content)
                        .setSmallIcon(R.drawable.dna)
                        .setContentIntent(pendingIntent)
                        .build();

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.notify(5, notification);
        return notification;
    }

    //enum to keep track of whether or not we are currently sending anything
    public enum Status {
        IDLE, SENDING, FAILED, SUCCESS
    }

    //according to android studio this can cause leaks, probably the source of the crashes
    //reads the return codes of the batches and deletes files that were successfully uploaded
    public class Sender extends AsyncTask<Batch, Integer, Void> {
        @Override
        protected Void doInBackground(Batch... batches) {
            String code = batches[0].sendFiles();
            if (code.equals("201")) {
                batches[0].deleteFiles();
                sendSuccessful(batches[0]);
            } else {
                sendFailure(code);
            }
            return null;
        }
    }

    public class LocalBinder extends Binder {
        public UploadService getService() {
            return UploadService.this;
        }
    }
}
