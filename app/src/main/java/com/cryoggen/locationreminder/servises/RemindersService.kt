package com.cryoggen.locationreminder.servises

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.location.Location
import android.os.*
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import com.cryoggen.locationreminder.R
import com.cryoggen.locationreminder.addeditreminder.*
import com.cryoggen.locationreminder.data.Reminder
import com.cryoggen.locationreminder.geofence.GeofenceHelper
import com.cryoggen.locationreminder.geofence.GeofencingConstants.GEOFENCE_RADIUS_IN_METERS
import com.cryoggen.locationreminder.reminders.RemindersViewModel
import com.google.android.gms.location.*


class RemindersService : LifecycleService() {

    var reminderMinDistance: Reminder? = null
    var minDistanceLocation = 0.0F

    // FusedLocationProviderClient - Main class for receiving location updates.
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    // LocationRequest - Requirements for the location updates, i.e., how often you should receive
    // updates, the priority, etc.
    private lateinit var locationRequest: LocationRequest

    // LocationCallback - Called when FusedLocationProviderClient has a new Location.
    private lateinit var locationCallback: LocationCallback

    // Used only for local storage of the last known location. Usually, this would be saved to your
    // database, but because this is a simplified sample without a full database, we only need the
    // last location to create a Notification if the user navigates away from the app.
    private var currentLocation: Location? = null

    var sumActiveReminders = -1

    private val geofenceHelper by lazy { GeofenceHelper(this) }

    private var listReminders = listOf<Reminder>()

    private val viewModel by lazy { RemindersViewModel(application) }

    private val context = this

    val notificationManager by lazy {
        ContextCompat.getSystemService(
            this,
            NotificationManager::class.java
        ) as NotificationManager
    }

    private var serviceLooper: Looper? = null
    private var serviceHandler: ServiceHandler? = null

    // Handler that receives messages from the thread
    private inner class ServiceHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {

            try {
                for (i in 1..500) {
                    when (sumActiveReminders) {
                        in 1..100 -> {
                            sendNotification()
                            println("11111 " + i)
                        }
                        0 -> {
                            break
                        }
                    }
                    Thread.sleep(1000)
                }
            } catch (e: InterruptedException) {
                // Restore interrupt status.
                Thread.currentThread().interrupt()
            }

            stopSelf(msg.arg1)

        }


        private fun sendNotification() {
            val textNotification: String =
                if (reminderMinDistance == null) getString(R.string.loading_notification_text) else "${reminderMinDistance!!.title} · " + getString(
                    R.string.after
                ) + " · $minDistanceLocation" + "km."

            notificationManager.notify(
                NOTIFICATION_GEOFENCE_STATUS_ID,
                sendNotificationStatus(context, textNotification)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createLocationRequest()
        startNotifictionForegraund()
        HandlerThread("ServiceStartArguments", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()

            // Get the HandlerThread's Looper and use it for our Handler
            serviceLooper = looper
            serviceHandler = ServiceHandler(looper)
        }
        startNotifictionForegraund()
        // For each start request, send a message to start a job and deliver the
        // start ID so we know which request we're stopping when we finish the job

        observeUpdateReminder()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

//        Toast.makeText(this, "service starting", Toast.LENGTH_SHORT).show()
        if (intent?.action == ACTION_DEACTIVATE_REMINDER) {
            val idReminder = intent.getStringExtra(DEACIVATE_REMINDER_ID)
            for (reminder in listReminders) {
                if (reminder.id == idReminder) {
                    viewModel.completeReminder(reminder, true)
                }
            }

        } else {
            subscribeToLocationUpdates()
            serviceHandler?.obtainMessage()?.also { msg ->
                msg.arg1 = startId
                serviceHandler?.sendMessage(msg)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        // We don't provide binding, so return null
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
//        Toast.makeText(this, "service done", Toast.LENGTH_SHORT).show()
        deactivateAllReminders()
        unsubscribeToLocationUpdates()
        stopSelf()
    }

    private fun startNotifictionForegraund() {
        startForeground(
            NOTIFICATION_GEOFENCE_STATUS_ID,
            sendNotificationStatus(
                context,
                resources.getString(R.string.loading_notification_text)
            )
        )
    }

    private fun deactivateAllReminders() {
        sumActiveReminders = 0
        for (reminder in listReminders) {
            if (reminder.isActive) {
                viewModel.completeReminder(reminder, true)
            }
        }

    }

    private fun observeUpdateReminder() {
        viewModel.items.observe(this, Observer {
            listReminders = it
            serviceManagement()
        })
    }

    private fun serviceManagement() {
        updateSumActiveReminders()
        refreshGeofenseReminders()
    }


    fun updateSumActiveReminders() {
        var sumReminders = 0
        for (reminder in listReminders) {
            if (reminder.isActive) {
                sumReminders++
            }
        }
        sumActiveReminders = sumReminders
    }

    fun refreshGeofenseReminders() {
        geofenceHelper.removeAllGeofences()
        if (sumActiveReminders > 0) {
            for (reminder in listReminders) {
                if (reminder.isActive) {
                    geofenceHelper.addGeofenceForReminder(
                        reminder.id,
                        reminder.latitude,
                        reminder.longitude
                    )
                }
            }
        }
    }

    fun createLocationRequest() {
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.create().apply {
            interval = 1000
            fastestInterval = 1000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            maxWaitTime = 1000
        }
        LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)

                // Normally, you want to save a new location to a database. We are simplifying
                // things a bit and just saving it as a local variable, as we only need it again
                // if a Notification is created (when the user navigates away from app).
                currentLocation = locationResult.lastLocation
                nearestGeofence()
            }
        }

    }

    @SuppressLint("MissingPermission")
    fun subscribeToLocationUpdates() {
        try {
            fusedLocationProviderClient.requestLocationUpdates(
                locationRequest, locationCallback, Looper.getMainLooper()
            )
        } catch (unlikely: SecurityException) {
            //checks if there are permissions to access geolocation

        }
    }

    fun unsubscribeToLocationUpdates() {

        try {
            val removeTask = fusedLocationProviderClient.removeLocationUpdates(locationCallback)
            removeTask.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    stopSelf()
                } else {

                }
            }

        } catch (unlikely: SecurityException) {

        }

    }

    fun nearestGeofence() {
        reminderMinDistance = null
        minDistanceLocation = Float.MAX_VALUE
        var reminderLocation = Location("location")
        var reminderDistanceLocation = 0.0F
        if (sumActiveReminders > 0) {

            for (reminder in listReminders) {

                if (reminder.isActive) {
                    reminderLocation.latitude = reminder.latitude
                    reminderLocation.longitude = reminder.longitude

                    if (currentLocation != null) {
                        reminderDistanceLocation = currentLocation!!.distanceTo(reminderLocation)
                        if (minDistanceLocation > reminderDistanceLocation) {
                            minDistanceLocation = reminderDistanceLocation
                            reminderMinDistance = reminder
                        }

                    }

                }

            }
            minDistanceLocation = metersToKilometers(minDistanceLocation)
        }
    }

    private fun metersToKilometers(minDistanceLocation: Float): Float {

        return if ((minDistanceLocation - GEOFENCE_RADIUS_IN_METERS) >= 0) ((((minDistanceLocation - GEOFENCE_RADIUS_IN_METERS) * 0.1).toInt()) / 100.0f) else 0.0F
    }
}


const val ACTION_DEACTIVATE_REMINDER = "com.cryoggen.action.ACTION_DEACTIVATE_REMINDER"
const val DEACIVATE_REMINDER_ID = "com.cryoggen.action.REMINDER_DEACIVATE_ID"