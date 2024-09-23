package com.screenomics.services.upload;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.screenomics.Constants;

import org.joda.time.DateTime;

import java.io.File;
import java.io.FileFilter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

//Documentation for the worker class: https://developer.android.com/reference/androidx/work/Worker
public class SenderWorker extends Worker {
    //constructor is just the super constructor
    public SenderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    //fields were originally copy/pasted from upload service but most of them
    //were not used within SenderWorker. Most have been either deleted or turned
    //into stack variables to tru and improve the readability of the code

    //stores all the batches sent by the sender worker
    private final List<Batch> batches = new ArrayList<>();

    //the client we are sending the batches to, batch objects use this in their constructor
    private final OkHttpClient client = new OkHttpClient.Builder().readTimeout(Constants.REQ_TIMEOUT, TimeUnit.SECONDS).build();

    //keeps track of when the upload started so the program does not try and upload images
    //taken after the upload button was pressed
    private LocalDateTime startDateTime;


    //main function of the class
    @Override
    public Result doWork() {

        //leaving the code in as a comment but it seems to only deny the ability
        //to upload during certain hours but still report the upload as successful?
        /*
        final List<Number> hours = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8);
        if (!hours.contains(DateTime.now().getHourOfDay())) {
            return Result.success();
        }
         */

        System.out.println("Starting upload!");

        //encrypting our image files?
        Context context = getApplicationContext();
        File f_encrypt = new File(context.getExternalFilesDir(null).getAbsolutePath() + File.separator + "encrypt");
        //getting the directory of our encrypted image files
        File dir = new File(f_encrypt.getAbsolutePath());

        //grabbing the files that are from before the upload button was pressed
        File[] files = dir.listFiles(onlyFilesBeforeStart);

        //fail condition for when zero files are uploaded
        //unsure why the code returns success when we deny an upload/upload fails
        if (files == null || files.length == 0) {
            return Result.success();
        }

        //turning our files from an array into a list to be processed
        //I am not sure why we iterate through a list instead of a vector
        //changing what datastructures are used might be ones means of optimizing
        LinkedList<File> fileList = new LinkedList<>(Arrays.asList(files));

        //finding out how many files should be sent in a single batch as well as
        //how many files should be sent total
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int batchSize = prefs.getInt("batchSize", Constants.BATCH_SIZE_DEFAULT);
        int maxToSend = prefs.getInt("maxSend", Constants.MAX_TO_SEND_DEFAULT);

        //counter for keeping track of how many files we have decided to upload
        int numToUpload = 0;

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

        //debugging info
        System.out.println("GOT " + batches.size() + " BATCHES WITH " + numToUpload + "IMAGES TO UPLOAD" );
        System.out.println("TOTAL OF " + fileList.size() + " IMAGES THO");

        Log.d("SenderWorker", "sending batches: " + batches);

        //assuming code 201 is a successful upload and this is to delete the files after the batch has uploaded
        for (Batch batch : batches) {
            String code = batch.sendFiles();
            if (code.equals("201")) {
                batch.deleteFiles();
            }
        }

        return Result.success();
    }

    //function to grab all images created before we started uploading
    private final FileFilter onlyFilesBeforeStart = new FileFilter() {
        @Override
        public boolean accept(File file) {
            List<String> parts = Arrays.asList(file.getName().replace(".png", "").split("_"));
            Integer[] dP = parts.subList(parts.size() - 7, parts.size() - 1).stream().map(Integer::valueOf).toArray(Integer[]::new);
            LocalDateTime imageCreateTime = LocalDateTime.of(dP[0], dP[1], dP[2], dP[3], dP[4], dP[5]);
            return imageCreateTime.isBefore(LocalDateTime.now());
        }
    };
}
