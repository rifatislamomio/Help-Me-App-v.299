package com.nsu.group06.cse299.sec02.helpmeapp;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;
import com.karumi.dexter.Dexter;
import com.karumi.dexter.PermissionToken;
import com.karumi.dexter.listener.PermissionDeniedResponse;
import com.karumi.dexter.listener.PermissionGrantedResponse;
import com.karumi.dexter.listener.PermissionRequest;
import com.karumi.dexter.listener.single.PermissionListener;
import com.nsu.group06.cse299.sec02.helpmeapp.auth.Authentication;
import com.nsu.group06.cse299.sec02.helpmeapp.auth.AuthenticationUser;
import com.nsu.group06.cse299.sec02.helpmeapp.auth.FirebaseEmailPasswordAuthentication;
import com.nsu.group06.cse299.sec02.helpmeapp.database.Database;
import com.nsu.group06.cse299.sec02.helpmeapp.database.firebase_database.FirebaseRDBApiEndPoint;
import com.nsu.group06.cse299.sec02.helpmeapp.database.firebase_database.FirebaseRDBSingleOperation;
import com.nsu.group06.cse299.sec02.helpmeapp.fetchLocation.FetchedLocation;
import com.nsu.group06.cse299.sec02.helpmeapp.fetchLocation.LocationFetcher;
import com.nsu.group06.cse299.sec02.helpmeapp.fetchLocation.fusedLocationApi.FusedLocationFetcherApiAdapter;
import com.nsu.group06.cse299.sec02.helpmeapp.imageUpload.CapturedImage;
import com.nsu.group06.cse299.sec02.helpmeapp.imageUpload.FileUploader;
import com.nsu.group06.cse299.sec02.helpmeapp.imageUpload.firebaseStorage.FirebaseStorageFileUploader;
import com.nsu.group06.cse299.sec02.helpmeapp.models.HelpPost;
import com.nsu.group06.cse299.sec02.helpmeapp.sharedPreferences.EmergencyContactsSharedPref;
import com.nsu.group06.cse299.sec02.helpmeapp.utils.NosqlDatabasePathUtils;
import com.nsu.group06.cse299.sec02.helpmeapp.utils.RemoteStoragePathsUtils;
import com.nsu.group06.cse299.sec02.helpmeapp.utils.SessionUtils;
import com.nsu.group06.cse299.sec02.helpmeapp.utils.UserInputValidator;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

public class HelpPostActivity extends AppCompatActivity {

    private static final String TAG = "HPA-debug";

    // request code for camera intent
    private static final int REQUEST_IMAGE_CAPTURE = 935;

    // auth to access taken image file
    private static final String FILE_PROVIDER_AUTHORITY = "com.nsu.group06.cse299.sec02.helpmeapp.fileprovider";

    // ui
    private ImageView mCapturedImageView;
    private Button mTakeImageButton, mFetchLocationButton, mPostButton;
    private EditText mPostDescriptionEditText, mAddressEditText, mPhoneNumberEditText;

    // model
    private CapturedImage mCapturedImage;
    private boolean mImageWasCaptured = false; // necessary for when user presses take photo but doesn't take a photo
    private FetchedLocation mFetchedLocation;
    private boolean mLocationWasFethced = false;
    private HelpPost mHelpPost;
    private ArrayList<String> emergencyContactPhoneNumbers;

    // variables used for fetching user uid
    private Authentication mAuth;
    private Authentication.AuthenticationCallbacks mAuthCallbacks =
            new Authentication.AuthenticationCallbacks() {
                @Override
                public void onAuthenticationSuccess(AuthenticationUser user) {

                    mHelpPost.setAuthorId(user.getmUid());

                    Log.d(TAG, "onAuthenticationSuccess: uid = "+user.getmUid());
                }

                @Override
                public void onAuthenticationFailure(String message) {

                    SessionUtils.logout(HelpPostActivity.this, mAuth);
                }
            };

    // variables used to fetch location
    private LocationFetcher mLocationFetcher;
    private LocationFetcher.LocationSettingsSetupListener mLocationSettingsSetupListener =
            new LocationFetcher.LocationSettingsSetupListener() {
                @Override
                public void onLocationSettingsSetupSuccess() {

                    startLocationUpdates(mLocationFetcher);
                }

                @Override
                public void onLocationSettingsSetupFailed(String message) {

                    // user will be automatically be asked to enable location settings
                    // see method in HelpPostActivity: 'onActivityResult(...)'

                    Log.d(TAG, "onLocationSettingsSetupFailed: location settings setup failed ->" + message);
                }
            };
    private LocationFetcher.LocationUpdateListener mLocationUpdateListener =
            new LocationFetcher.LocationUpdateListener() {
                @Override
                public void onNewLocationUpdate(FetchedLocation fetchedLocation) {

                    if(!mLocationWasFethced){

                        fetchLocationSuccessUI();

                        mLocationWasFethced = true;
                        mFetchedLocation = fetchedLocation;
                    }

                    else if(mFetchedLocation.getmAccuracy() > fetchedLocation.getmAccuracy()
                            || FetchedLocation.isLocationSignificantlyDifferent(mFetchedLocation, fetchedLocation)) {

                        mFetchedLocation = fetchedLocation;
                    }

                    Log.d(TAG, "onNewLocationUpdate: location -> "+fetchedLocation.toString());
                }

                @Override
                public void onPermissionNotGranted() {

                    fetchLocationFailedUI();
                    mLocationFetcher.stopLocationUpdate();

                    Log.d(TAG, "onPermissionNotGranted: location permission not granted");
                }

                @Override
                public void onError(String message) {

                    if(!mLocationWasFethced) fetchLocationFailedUI();

                    mLocationFetcher.stopLocationUpdate();

                    Log.d(TAG, "onError: location update error -> "+message);
                }
            };


    // variables to upload photo to firebase storage
    private FirebaseStorageFileUploader mFileUploader;
    private FileUploader.FileUploadCallbacks mFileUploadCallbacks = new FileUploader.FileUploadCallbacks() {
        @Override
        public void onUploadComplete(Uri uploadedImageLink) {

            try {

                URL link = new URL(uploadedImageLink.toString());

                mHelpPost.setPhotoURL(link.toString());

                // make database entry
                mHelpPostSingleOperationDatabase.createWithId(mHelpPost.getPostId(), mHelpPost);

            } catch (MalformedURLException e) {

                Log.d(TAG, "onUploadComplete: image upload error->"+e.getStackTrace());
            }
        }

        @Override
        public void onUploadFailed(String message) {

            imageUploadFailedUI();

            sendHelpPostFailedUI();

            Log.d(TAG, "onUploadFailed: error->" + message);
        }
    };


    // variables to store help post to database
    private Database.SingleOperationDatabase<HelpPost> mHelpPostSingleOperationDatabase;
    private FirebaseRDBApiEndPoint mApiEndPoint;
    private Database.SingleOperationDatabase.SingleOperationDatabaseCallback<HelpPost> mHelpPostSingleOperationDatabaseCallback =
            new Database.SingleOperationDatabase.SingleOperationDatabaseCallback<HelpPost>() {
                @Override
                public void onDataRead(HelpPost data) {
                    // keep black, no reading anything
                }

                @Override
                public void onDatabaseOperationSuccess() {

                    mHelpPostDatabaseSendDone = true;

                    sendHelpPostSuccessUI();
                }

                @Override
                public void onDatabaseOperationFailed(String message) {

                    sendHelpPostFailedUI();

                    Log.d(TAG, "onDatabaseOperationFailed: help post database upload failed -> "+message);
                }
            };


    // help post sms and database upload flags
    private boolean mHelpPostSmsSendDone = false, mHelpPostDatabaseSendDone = false;


    // variables for sending sms to emergency contacts
    private EmergencyContactsSharedPref mEmergencyContactsSharedPref;
    private int NUMBER_OF_EMERGENCY_CONTACTS_SENT_TO = 0;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help_post);

        init();
    }

    /*
    Required for reacting to photo captured

    Required for reacting to user response when setting up required location settings
    user response to default "turn on location" dialog is handled through this method
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode){

            case REQUEST_IMAGE_CAPTURE:

                if(resultCode!=RESULT_OK) return; // image was not captured

                mImageWasCaptured = true;

                mCapturedImageView.setVisibility(View.VISIBLE);
                mCapturedImageView.setImageURI(mCapturedImage.getmPhotoUri());

                break;

            case LocationFetcher.REQUEST_CHECK_LOCATION_SETTINGS:

                if(resultCode==RESULT_OK){
                    // user enabled location settings

                    startLocationUpdates(mLocationFetcher);
                }

                else{
                    // location settings not met

                    showLocationSettingsExplanationDialog();
                }
                break;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        try{

            // need to stop location fetching when activity stops
            // enable user to fetch location again
            mFetchLocationButton.setEnabled(true);
            mFetchLocationButton.setText(R.string.helpPost_PickLocation_Button_label);

            stopLocationUpdates(mLocationFetcher);

        }catch (Exception e){

            Log.d(TAG, "onStop: failed to stop location updates -> "+e.getStackTrace());
        }
    }


    private void init() {

        mCapturedImageView = findViewById(R.id.helpPost_capturedPhoto_ImageView);
        mTakeImageButton = findViewById(R.id.helpPost_takePhoto_Button);
        mFetchLocationButton = findViewById(R.id.helpPost_PickLocation_Button);
        mPostButton = findViewById(R.id.helpPost_Post_Button);
        mPostDescriptionEditText = findViewById(R.id.helpPost_description_EditText);
        mAddressEditText = findViewById(R.id.helpPost_Address_EditText);
        mPhoneNumberEditText = findViewById(R.id.helpPost_phoneNumber_editText);

        mHelpPost = new HelpPost();
        mHelpPost.setAuthor("anonymous");

        mAuth = new FirebaseEmailPasswordAuthentication();

        mImageWasCaptured = false;

        mLocationWasFethced = false;
        mLocationFetcher = new FusedLocationFetcherApiAdapter(
                1000, this,
                mLocationSettingsSetupListener,
                mLocationUpdateListener
        );

        mFileUploader = new FirebaseStorageFileUploader();

        mEmergencyContactsSharedPref = EmergencyContactsSharedPref.build(this);

        mHelpPostSmsSendDone = false;
        mHelpPostSmsSendDone = false;

        authenticateUserLoginState(mAuth, mAuthCallbacks);

        getSmsPermission();
    }

    /**
     * authenticate if user is logged in
     * @param auth authenticator object
     * @param authCallbacks authentication sucess/failure callback
     */
    private void authenticateUserLoginState(Authentication auth, Authentication.AuthenticationCallbacks authCallbacks) {

        auth.setmAuthenticationCallbacks(authCallbacks);
        auth.authenticateUser();
    }

    /*
    Ask for location access permission
    using the open source library- <https://github.com/Karumi/Dexter>
     */
    private void getSmsPermission(){

        Dexter.withContext(this)
                .withPermission(Manifest.permission.SEND_SMS)
                .withListener(new PermissionListener() {
                    @Override
                    public void onPermissionGranted(PermissionGrantedResponse permissionGrantedResponse) {
                        // not doing anything immediately after permission approval
                    }

                    @Override
                    public void onPermissionDenied(PermissionDeniedResponse permissionDeniedResponse) {

                        showSmsPermissionExplanationDialog(permissionDeniedResponse.isPermanentlyDenied());
                    }

                    @Override
                    public void onPermissionRationaleShouldBeShown(PermissionRequest permissionRequest, PermissionToken permissionToken) {
                        // ignore for now
                        permissionToken.continuePermissionRequest();
                    }
                })
                .check();
    }


    /*
    'Take Photo' button click
     */
    public void takePhotoClick(View view) {

        try {

            mCapturedImage = CapturedImage.build(this);

            dispatchTakePictureIntent(mCapturedImage);

        } catch (IOException e) {

            showToast(getString(R.string.no_default_camera_app_found));

            Log.d(TAG, "takePhotoClick: error->"+e.getMessage());
        }
    }

    /**
     * open default camera app to take an image
     * @param image model for image to be capture, with info of temporarily created local file info
     */
    private void dispatchTakePictureIntent(CapturedImage image) {

        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // Ensure that there's a camera activity to handle the intent
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {

            File photoFile = image.getmPhotoFile();

            if (photoFile != null) {

                Uri photoURI = FileProvider.getUriForFile(
                        this,
                        FILE_PROVIDER_AUTHORITY,
                        photoFile
                );

                image.setmPhotoUri(photoURI);

                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);

                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
            }
        }
    }


    /*
    'Pick Location' button click
     */
    public void pickLocationClick(View view) {

        mLocationWasFethced = false;

        fetchLocationInProgressUI();

        startFetchingLocation();
    }

    private void startFetchingLocation() {

        // after getting the location permissions the location fetching starts
        getLocationPermissions();
    }

    /*
    Ask for location access permission
    using the open source library- <https://github.com/Karumi/Dexter>
     */
    private void getLocationPermissions() {

        Dexter.withContext(this)
                .withPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                .withListener(new PermissionListener() {
                    @Override
                    public void onPermissionGranted(PermissionGrantedResponse permissionGrantedResponse) {

                        requestLocationSettings(mLocationFetcher);
                    }

                    @Override
                    public void onPermissionDenied(PermissionDeniedResponse permissionDeniedResponse) {

                        showLocationPermissionExplanationDialog(permissionDeniedResponse.isPermanentlyDenied());
                    }

                    @Override
                    public void onPermissionRationaleShouldBeShown(PermissionRequest permissionRequest, PermissionToken permissionToken) {
                        // ignore for now
                        permissionToken.continuePermissionRequest();
                    }
                })
                .check();
    }

    /**
     * request necessary location settings
     * @param locationFetcher location fetcher/settings requesting object
     */
    private void requestLocationSettings(LocationFetcher locationFetcher) {

        mLocationFetcher.setupLocationSettings(this);
    }

    /**
     * start location updates/fetching (after permission has been granted and settings are met)
     * @param locationFetcher location fetcher object
     */
    private void startLocationUpdates(LocationFetcher locationFetcher) {

        locationFetcher.startLocationUpdate();
    }

    /**
     * stop fetching locations
     * @param locationFetcher location fetcher object
     */
    private void stopLocationUpdates(LocationFetcher locationFetcher) {

        locationFetcher.stopLocationUpdate();
    }


    /*
    'make public' checkbox click
     */
    public void makePublicCheckboxClick(View view) {

        mHelpPost.setIsPublic(!mHelpPost.getIsPublic());
    }

    /*
    'Post' button click
     */
    public void postClick(View view) {

        String description = mPostDescriptionEditText.getText().toString().trim();
        String address = mAddressEditText.getText().toString().trim();
        String phoneNumber = mPhoneNumberEditText.getText().toString().trim();
        if(!phoneNumber.startsWith("+88")) phoneNumber = "+88" + phoneNumber;

        if(validateInputs(description, phoneNumber, mLocationWasFethced)){

            mHelpPost.setPostId(HelpPost.generateUniquePostId(mHelpPost.getAuthorId()));
            mHelpPost.setAuthor("anonymous");
            mHelpPost.setAuthorPhoneNumber(phoneNumber);
            mHelpPost.setContent(description);
            mHelpPost.setLatitude(mFetchedLocation.getmLatitude());
            mHelpPost.setLongitude(mFetchedLocation.getmLongitude());
            mHelpPost.setAltitude(mFetchedLocation.getmAltitude());
            mHelpPost.setAddress(address);
            mHelpPost.setTimeStamp(getCurrentTime());

            stopLocationUpdates(mLocationFetcher);
            if(!checkFetchedLocationAccuracy(mFetchedLocation)) showInaccurateLocationDialog();

            forwardHelpPost();
        }
    }

    /*
    send the help post to emergency contacts as well as database
     */
    private void forwardHelpPost() {

        sendHelpPostInProgressUI();

        smsToEmergencyContacts(mHelpPost, mEmergencyContactsSharedPref);

        mApiEndPoint = new FirebaseRDBApiEndPoint("/" + NosqlDatabasePathUtils.HELP_POSTS_NODE);

        mHelpPostSingleOperationDatabase = new FirebaseRDBSingleOperation<HelpPost>(
                HelpPost.class,
                mApiEndPoint,
                mHelpPostSingleOperationDatabaseCallback
        );

        if(mImageWasCaptured) sendHelpPostWithPhoto(mCapturedImage, mFileUploader, mFileUploadCallbacks);

        else sendHelpPostWithoutPhoto(mHelpPostSingleOperationDatabase, mHelpPost);
    }


    /**
     * validate user inputs
     * @param description content of the help post
     * @param locationWasFetched location fetch flag
     * @param phoneNumber help poster phone number
     * @return valid or not
     */
    private boolean validateInputs(String description, String phoneNumber, boolean locationWasFetched) {

        boolean isValid = locationWasFetched;

        if(description.isEmpty()){

            isValid = false;
            mPostDescriptionEditText.setError(getString(R.string.invalid_description));
        }

        if(!UserInputValidator.isPhoneNumberValid(phoneNumber)){

            isValid = false;
            mPhoneNumberEditText.setError(getString(R.string.invalid_phone_number));
        }

        if(!locationWasFetched) showToast(getString(R.string.no_location_error));

        return isValid;
    }

    /**
     * check location accuracy
     * @param fetchedLocation location to be checked
     * @return if fetched location is accurate enough
     */
    private boolean checkFetchedLocationAccuracy(FetchedLocation fetchedLocation) {

        return FetchedLocation.isLocationAccurateEnough(fetchedLocation);
    }

    /**
     * get current time
     * @return time string format- Date/Month/Year, hour:minutes:second
     * courtesy - <https://stackoverflow.com/questions/5175728/how-to-get-the-current-date-time-in-java>
     */
    private String getCurrentTime() {

        DateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        Date date = new Date();

        return dateFormat.format(date);
    }


    /**
     * send help post to database without photo
     * @param helpPostSingleOperationDatabase database operation object
     * @param helpPost model object for a help post
     */
    private void sendHelpPostWithoutPhoto(Database.SingleOperationDatabase<HelpPost> helpPostSingleOperationDatabase,
                                          HelpPost helpPost) {

        helpPostSingleOperationDatabase.createWithId(helpPost.getPostId(), helpPost);
    }

    /**
     * send help post to database with photo
     * @param capturedImage captured photo model
     * @param fileUploader uploader object
     * @param fileUploadCallbacks callback for uploading status
     */
    private void sendHelpPostWithPhoto(CapturedImage capturedImage, FirebaseStorageFileUploader fileUploader,
                                       FileUploader.FileUploadCallbacks fileUploadCallbacks) {


        uploadImage(fileUploader, fileUploadCallbacks, capturedImage);
    }

    /**
     * send sms to all saved emergency contacts
     * @param helpPost help post model object
     * @param emergencyContactsSharedPref shared pref with emergency contact phone numbers saved
     */
    private void smsToEmergencyContacts(HelpPost helpPost,
                                        EmergencyContactsSharedPref emergencyContactsSharedPref) {

        /*
         * to check if sms was sent, we need to register a broadcast receiver
         * courtesy- <https://stackoverflow.com/questions/3875354/android-sms-message-delivery-report-intent>
         */
        PendingIntent sentPI = PendingIntent.getBroadcast(this, 0, new Intent("sent_intent"), 0);
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context arg0, Intent arg1) {
                int resultCode = getResultCode();

                if(resultCode!=RESULT_OK){

                    mHelpPostSmsSendDone = false;
                    failedToSendSMSUI();
                }

                else {

                    NUMBER_OF_EMERGENCY_CONTACTS_SENT_TO--;

                    if(NUMBER_OF_EMERGENCY_CONTACTS_SENT_TO==0){

                        mHelpPostSmsSendDone = true;
                        sendHelpPostSuccessUI();
                    }
                }

                Log.d(TAG, "sms intent onReceive: data->"+getResultData());
            }
        }, new IntentFilter("sent_intent"));
        /* END */

        ArrayList<String> emergencyContactPhoneNumbers =
                emergencyContactsSharedPref.getPhoneNumbers();

        NUMBER_OF_EMERGENCY_CONTACTS_SENT_TO = emergencyContactPhoneNumbers.size();
        if(NUMBER_OF_EMERGENCY_CONTACTS_SENT_TO == 0) mHelpPostSmsSendDone = true;

        String message = HelpPost.getSMSBody(helpPost);
        Log.d(TAG, "smsToEmergencyContacts: message = "+message + " "+message.length());

        for(String phoneNumber : emergencyContactPhoneNumbers){

            try {

                SmsManager smsManager = SmsManager.getDefault();
                smsManager.sendTextMessage(phoneNumber,null,message, sentPI,null);

                Log.d(TAG, "smsToEmergencyContacts: "+phoneNumber);

            } catch (Exception e){

                showToast(getString(R.string.failed_to_send_sms));

                Log.d(TAG, "smsToEmergencyContacts: failed to send sms to "+phoneNumber + "error->"+e.getStackTrace());
            }
        }
    }

    /**
     * upload captured food image to remote data storage
     * @param fileUploader file uploader object
     * @param image object for image to be uploaded
     */
    private void uploadImage(FirebaseStorageFileUploader fileUploader,
                             FileUploader.FileUploadCallbacks fileUploadCallbacks,
                             CapturedImage image){

        fileUploader.uploadFile(
                image,
                RemoteStoragePathsUtils.HELP_POST_PHOTO_NODE + "/" + image.getmPhotoFileName(),
                fileUploadCallbacks
        );
    }


    /*
    show alert dialog explaining why sms permission is a MUST
    with a simple dialog, quit activity if permission is permanently denied
    courtesy - <https://stackoverflow.com/questions/26097513/android-simple-alert-dialog
     */
    private void showSmsPermissionExplanationDialog(boolean isPermissionPermanentlyDenied) {

        AlertDialog alertDialog = new AlertDialog.Builder(HelpPostActivity.this).create();

        String title = getString(R.string.sms_permission);
        String explanation;

        if(isPermissionPermanentlyDenied)
            explanation = getString(R.string.sms_permission_permanantely_denied_explanation);
        else
            explanation = getString(R.string.sms_permission_explanation);

        alertDialog.setTitle(title);
        alertDialog.setMessage(explanation);
        alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.ok),
                (dialog, which) -> {
                    if(!isPermissionPermanentlyDenied)
                        getSmsPermission();
                    else
                        finish();
                });

        alertDialog.show();
    }

    /*
    show alert dialog explaining why location permission is a MUST
    with a simple dialog, quit activity if permission is permanently denied
    courtesy - <https://stackoverflow.com/questions/26097513/android-simple-alert-dialog
     */
    private void showLocationPermissionExplanationDialog(boolean isPermissionPermanentlyDenied) {

        AlertDialog alertDialog = new AlertDialog.Builder(HelpPostActivity.this).create();

        String title = getString(R.string.location_permission);
        String explanation;

        if(isPermissionPermanentlyDenied)
            explanation = getString(R.string.location_permission_permanantely_denied_explanation);
        else
            explanation = getString(R.string.location_permission_explanation);

        alertDialog.setTitle(title);
        alertDialog.setMessage(explanation);
        alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.ok),
                (dialog, which) -> {
                    if(!isPermissionPermanentlyDenied)
                        getLocationPermissions();
                    else
                        finish();
                });

        alertDialog.show();
    }

    /*
    show alert dialog explaining why location settings MUST be enabled
    courtesy - <https://stackoverflow.com/questions/26097513/android-simple-alert-dialog
     */
    private void showLocationSettingsExplanationDialog() {

        AlertDialog alertDialog = new AlertDialog.Builder(HelpPostActivity.this).create();

        String title = getString(R.string.location_settings);
        String explanation = getString(R.string.location_settings_explanation);

        alertDialog.setTitle(title);
        alertDialog.setMessage(explanation);
        alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.ok),
                (dialog, which) -> mLocationFetcher.setupLocationSettings(HelpPostActivity.this));

        alertDialog.show();
    }

    /*
    user attempted to post but the fetched location was inaccurate
    show alert asking to retry or fetch again
    courtesy - <https://stackoverflow.com/questions/26097513/android-simple-alert-dialog
     */
    private void showInaccurateLocationDialog(){

        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setMessage(getString(R.string.location_is_innaccurate))

                .setPositiveButton(getString(R.string.retry), (dialog, which) -> {

                    startFetchingLocation();
                    dialog.dismiss();
                })

                .setNegativeButton(getString(R.string.ignore_and_send), (dialog, which) -> {

                    forwardHelpPost();
                    dialog.dismiss();
                })

                .show();
    }


    private void fetchLocationInProgressUI(){

        mAddressEditText.setVisibility(View.GONE);

        mFetchLocationButton.setEnabled(false);
        mFetchLocationButton.setText(R.string.fetching_location);
    }


    private void fetchLocationSuccessUI(){

        mAddressEditText.setVisibility(View.VISIBLE);

        mFetchLocationButton.setEnabled(false);
        mFetchLocationButton.setText(R.string.location_fetched);
    }


    private void fetchLocationFailedUI(){

        showToast(getString(R.string.location_fetch_failed));

        mFetchLocationButton.setEnabled(true);
        mFetchLocationButton.setText(R.string.helpPost_PickLocation_Button_label);
    }

    private void sendHelpPostInProgressUI(){

        mPostButton.setEnabled(false);
        mPostButton.setText(getString(R.string.posting));
    }

    private void sendHelpPostSuccessUI(){

        if(mHelpPostSmsSendDone && mHelpPostDatabaseSendDone) {
            showToast(getString(R.string.posted));
            finish();
        }

        else if(mHelpPostDatabaseSendDone){

            showToast(getString(R.string.failed_to_send_sms_but_uploaded_to_database));
        }
    }

    private void sendHelpPostFailedUI(){

        mPostButton.setEnabled(true);
        mPostButton.setText(getString(R.string.helpPost_Post_Button_label));
    }

    private void imageUploadFailedUI() {

        showToast(getString(R.string.image_upload_failed));
    }

    private void failedToSendSMSUI(){

        View view = findViewById(R.id.helpPost_main_layout);
        Snackbar.make(view, R.string.failed_to_send_sms, Snackbar.LENGTH_INDEFINITE)
        .setAction(R.string.retry, v -> smsToEmergencyContacts(mHelpPost, mEmergencyContactsSharedPref))
        .show();
    }

    private void showToast(String message){

        Toast.makeText(this, message, Toast.LENGTH_SHORT)
                .show();
    }
}