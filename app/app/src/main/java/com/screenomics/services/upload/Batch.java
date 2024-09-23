package com.screenomics.services.upload;

import com.screenomics.Constants;

import java.io.File;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

//class for sending a batch of files all at once
public class Batch {

    private final MediaType PNG = MediaType.parse("image/*");

    //Variable which keeps track of all the images
    private final List<File> files;

    private final OkHttpClient client;

    //constructor, takes a list of files to send and a client
    //to send the files to
    Batch(List<File> files, OkHttpClient client) {
        this.files = files;
        this.client = client;
    }

    //Function to actually send the files
    public String sendFiles() {

        //MultipartBody Builder is a class for adding data to http packets easily
        MultipartBody.Builder bodyPart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM);

        //iterating through all our files and checking their validity
        //if the file corresponds to an actual image then we add it to the packet
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).isFile()) {
                bodyPart.addFormDataPart("file" + (i + 1), files.get(i).getName(), RequestBody.create(PNG, files.get(i)));
            }
        }

        //Turning our data into an actual packet to be sent to client
        RequestBody body = bodyPart.build();
        Request request = new Request.Builder()
                .addHeader("Content-Type", "multipart/form-data")
                .url(Constants.UPLOAD_ADDRESS)
                .post(body)
                .build();

        //initiating variable to store response from client
        Response response = null;
        try {
            //variable to keep track of how long our upload takes
            long startTime = System.nanoTime();
            //sending the upload request and storing the response
            response = client.newCall(request).execute();
            //printing out how long upload too to our console
            System.out.println("Upload of " + files.size() + " files took " + (System.nanoTime() - startTime)/1000000 + "ms");
        } catch (Exception e) {
            e.printStackTrace();
        }

        //turning our response into aa code
        int code = response != null ? response.code() : 999;
        //checking the range of the code
        if (code >= 400  && code < 500) {
            System.out.println(response.toString());
        }
        //closing the connection if we got a response
        if (response != null) response.close();
        //returning the response sent by client
        return String.valueOf(code);
    }

    //deletes all the files currently stored on users device
    public void deleteFiles() {
        files.forEach(File::delete);
    }

    //Returning the number of files being sent
    public int size() {
        return files.size();
    }

}
